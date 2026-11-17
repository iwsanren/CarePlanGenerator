package com.page24.backend.service;

import com.page24.backend.entity.CarePlan;
import com.page24.backend.exception.ValidationError;
import com.page24.backend.repository.OrderListQuery;
import com.page24.backend.repository.OrderSortField;
import com.page24.backend.repository.OrderSortKey;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Checks the raw parameters of the order list and turns them into an OrderListQuery, or throws a ValidationError
 * (HTTP 400). All rules of the list endpoint are here, so they can be tested without a database.
 *
 * Error messages are fixed texts. They never repeat what the caller sent, because a filter value can be a patient
 * name or an MRN and must not end up in a response body, a log line or an audit record.
 */
public final class OrderListQueryParser {

    public static final int MAX_PAGE_SIZE = 100;
    /** Longest accepted search text (name, MRN, medication, provider). */
    public static final int MAX_TEXT_LENGTH = 100;
    /** Dates outside this range cannot be real, and a huge year would make the database fail. */
    private static final LocalDate EARLIEST_DATE = LocalDate.of(1900, 1, 1);
    private static final LocalDate LATEST_DATE = LocalDate.of(2999, 12, 31);

    private static final OrderSortKey NEWEST_FIRST = new OrderSortKey(OrderSortField.CREATED_AT, false);
    private static final OrderSortKey TIE_BREAKER = new OrderSortKey(OrderSortField.ID, false);

    private OrderListQueryParser() {
    }

    public static OrderListQuery parse(OrderListParams params) {
        validatePage(params.getPage(), params.getPageSize());
        validatePositiveId(params.getPatientId(), "patient_id", "INVALID_PATIENT_ID");
        validatePositiveId(params.getProviderId(), "provider_id", "INVALID_PROVIDER_ID");
        CarePlan.Status status = parseStatus(params.getStatus());
        validateDates(params.getCreatedFrom(), params.getCreatedTo());

        String patientName = text(params.getPatientName(), "patient_name");
        String mrn = text(params.getMrn(), "mrn");
        String medication = text(params.getMedication(), "medication");
        String providerName = text(params.getProviderName(), "provider_name");

        return new OrderListQuery(
                params.getPage(),
                params.getPageSize(),
                status,
                params.getPatientId(),
                params.getProviderId(),
                patientName == null ? null : LikePatterns.contains(patientName),
                mrn,
                medication == null ? null : LikePatterns.contains(medication),
                providerName == null ? null : LikePatterns.contains(providerName),
                params.getCreatedFrom() == null ? null : params.getCreatedFrom().atStartOfDay(),
                createdBefore(params.getCreatedTo()),
                parseSort(params.getSort()));
    }

    private static void validatePage(int page, int pageSize) {
        if (page < 1) {
            throw new ValidationError("INVALID_PAGE", "page must be greater than or equal to 1");
        }
        if (pageSize < 1) {
            throw new ValidationError("INVALID_PAGE_SIZE", "page_size must be greater than or equal to 1");
        }
        if (pageSize > MAX_PAGE_SIZE) {
            throw new ValidationError("INVALID_PAGE_SIZE",
                    "page_size must be less than or equal to " + MAX_PAGE_SIZE);
        }
        // The first row's index is passed to the database as an int; a page this far out is empty anyway.
        if ((long) (page - 1) * pageSize > Integer.MAX_VALUE) {
            throw new ValidationError("INVALID_PAGE", "page is too large");
        }
    }

    private static void validatePositiveId(Long id, String parameterName, String errorCode) {
        if (id != null && id < 1) {
            throw new ValidationError(errorCode, parameterName + " must be greater than or equal to 1");
        }
    }

    private static CarePlan.Status parseStatus(String status) {
        String normalized = blankToNull(status);
        if (normalized == null) {
            return null;
        }
        try {
            return CarePlan.Status.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ValidationError(
                    "INVALID_STATUS",
                    "status must be one of: pending, processing, completed, failed");
        }
    }

    private static void validateDates(LocalDate from, LocalDate to) {
        validateDateRange(from, "created_from");
        validateDateRange(to, "created_to");
        if (from != null && to != null && from.isAfter(to)) {
            throw new ValidationError("INVALID_DATE_RANGE", "created_from must not be after created_to");
        }
    }

    private static void validateDateRange(LocalDate date, String parameterName) {
        if (date != null && (date.isBefore(EARLIEST_DATE) || date.isAfter(LATEST_DATE))) {
            throw new ValidationError("INVALID_DATE",
                    parameterName + " must be between " + EARLIEST_DATE + " and " + LATEST_DATE);
        }
    }

    /** created_to names a whole day and includes it, so the query stops before the start of the next day. */
    private static LocalDateTime createdBefore(LocalDate to) {
        return to == null ? null : to.plusDays(1).atStartOfDay();
    }

    /** A search text: trimmed, blank means "no filter", and an overlong one is refused. */
    private static String text(String value, String parameterName) {
        String normalized = blankToNull(value);
        if (normalized != null && normalized.length() > MAX_TEXT_LENGTH) {
            throw new ValidationError("INVALID_SEARCH_TEXT",
                    parameterName + " must be at most " + MAX_TEXT_LENGTH + " characters");
        }
        return normalized;
    }

    private static String blankToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    /**
     * Every ?sort= value is "field" or "field,asc|desc" (direction defaults to asc, case does not matter). The field
     * must be on the whitelist and may appear once. With no sort at all the list is newest first. The id is always
     * the last key (unless the caller already sorted by it), so rows with equal values keep one fixed order and a
     * page boundary never shuffles them.
     */
    private static List<OrderSortKey> parseSort(List<String> rawValues) {
        List<OrderSortKey> keys = new ArrayList<>();
        Set<OrderSortField> seen = EnumSet.noneOf(OrderSortField.class);
        if (rawValues != null) {
            for (String raw : rawValues) {
                OrderSortKey key = parseSortKey(raw);
                if (!seen.add(key.field())) {
                    throw invalidSort();
                }
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            keys.add(NEWEST_FIRST);
            seen.add(NEWEST_FIRST.field());
        }
        if (!seen.contains(TIE_BREAKER.field())) {
            keys.add(TIE_BREAKER);
        }
        return keys;
    }

    private static OrderSortKey parseSortKey(String raw) {
        if (raw == null) {
            throw invalidSort();
        }
        String[] parts = raw.split(",", -1);
        if (parts.length > 2) {
            throw invalidSort();
        }
        Optional<OrderSortField> field = OrderSortField.fromParameterName(parts[0].trim().toLowerCase(Locale.ROOT));
        if (field.isEmpty()) {
            throw invalidSort();
        }
        boolean ascending = true;
        if (parts.length == 2) {
            String direction = parts[1].trim().toLowerCase(Locale.ROOT);
            if (direction.equals("desc")) {
                ascending = false;
            } else if (!direction.equals("asc")) {
                throw invalidSort();
            }
        }
        return new OrderSortKey(field.get(), ascending);
    }

    private static ValidationError invalidSort() {
        return new ValidationError("INVALID_SORT",
                "sort must look like field or field,asc or field,desc, with each field used at most once; "
                        + "field must be one of: " + OrderSortField.allowedNames());
    }
}

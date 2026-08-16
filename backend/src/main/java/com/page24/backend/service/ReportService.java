package com.page24.backend.service;

import com.page24.backend.dto.ReportFile;
import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.Order;
import com.page24.backend.exception.ValidationError;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds files for pharmacy reporting.
 *
 * The first report intentionally supports CSV only. CSV is easy to open in
 * Excel and keeps the initial implementation and test surface small; XLSX can
 * be added later without changing the HTTP endpoint.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_NAME_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final List<String> HEADER = List.of(
            "Order ID",
            "Created At",
            "Care Plan Status",
            "Patient MRN",
            "Patient Name",
            "Date of Birth",
            "Medication",
            "Primary Diagnosis",
            "Provider NPI",
            "Provider Name",
            "Care Plan Updated At"
    );

    private final OrderRepository orderRepository;
    private final CarePlanRepository carePlanRepository;

    @Transactional(readOnly = true)
    public ReportFile exportOrders(
            String format,
            String startDateValue,
            String endDateValue,
            String statusValue,
            Long providerId
    ) {
        validateFormat(format);
        LocalDate startDate = parseDate(startDateValue, "start_date");
        LocalDate endDate = parseDate(endDateValue, "end_date");
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new ValidationError("INVALID_DATE_RANGE", "start_date must be on or before end_date");
        }
        if (providerId != null && providerId <= 0) {
            throw new ValidationError("INVALID_PROVIDER_ID", "provider_id must be greater than zero");
        }

        CarePlan.Status status = parseStatus(statusValue);
        List<Order> orders = orderRepository.findAll();
        Map<Long, CarePlan> carePlansByOrderId = carePlanRepository.findByOrderIn(orders).stream()
                .collect(Collectors.toMap(carePlan -> carePlan.getOrder().getId(), carePlan -> carePlan));

        StringBuilder csv = new StringBuilder();
        appendRow(csv, HEADER);

        orders.stream()
                .filter(order -> matches(order, carePlansByOrderId.get(order.getId()), startDate, endDate, status, providerId))
                .sorted(Comparator.comparing(Order::getCreatedAt, Comparator.reverseOrder())
                        .thenComparing(Order::getId, Comparator.reverseOrder()))
                .forEach(order -> appendRow(csv, toRow(order, carePlansByOrderId.get(order.getId()))));

        String filename = "orders_report_" + FILE_NAME_TIMESTAMP.format(LocalDateTime.now()) + ".csv";
        return new ReportFile(filename, csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private boolean matches(
            Order order,
            CarePlan carePlan,
            LocalDate startDate,
            LocalDate endDate,
            CarePlan.Status status,
            Long providerId
    ) {
        LocalDate orderDate = order.getCreatedAt().toLocalDate();
        return (startDate == null || !orderDate.isBefore(startDate))
                && (endDate == null || !orderDate.isAfter(endDate))
                && (status == null || (carePlan != null && carePlan.getStatus() == status))
                && (providerId == null || order.getProvider().getId().equals(providerId));
    }

    private List<String> toRow(Order order, CarePlan carePlan) {
        return List.of(
                value(order.getId()),
                dateTime(order.getCreatedAt()),
                carePlan == null ? "" : carePlan.getStatus().name(),
                value(order.getPatient().getMrn()),
                joinName(order.getPatient().getFirstName(), order.getPatient().getLastName()),
                value(order.getPatient().getDateOfBirth()),
                value(order.getMedicationName()),
                value(order.getPrimaryDiagnosis()),
                value(order.getProvider().getNpi()),
                value(order.getProvider().getName()),
                carePlan == null ? "" : dateTime(carePlan.getUpdatedAt())
        );
    }

    private void validateFormat(String format) {
        if (format != null && !format.isBlank() && !"csv".equalsIgnoreCase(format)) {
            throw new ValidationError("INVALID_EXPORT_FORMAT", "format must be csv");
        }
    }

    private LocalDate parseDate(String value, String parameterName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException ex) {
            throw new ValidationError("INVALID_DATE", parameterName + " must use YYYY-MM-DD format");
        }
    }

    private CarePlan.Status parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return CarePlan.Status.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ValidationError("INVALID_STATUS", "status must be PENDING, PROCESSING, COMPLETED, or FAILED");
        }
    }

    private void appendRow(StringBuilder csv, List<String> values) {
        csv.append(values.stream().map(this::escapeCsvCell).collect(Collectors.joining(","))).append("\r\n");
    }

    private String escapeCsvCell(String value) {
        String safeValue = value == null ? "" : value;
        // A spreadsheet can interpret a cell beginning with these characters as a formula.
        if (!safeValue.isEmpty() && "=+-@".indexOf(safeValue.charAt(0)) >= 0) {
            safeValue = "'" + safeValue;
        }
        return '"' + safeValue.replace("\"", "\"\"") + '"';
    }

    private String joinName(String firstName, String lastName) {
        return (value(firstName) + " " + value(lastName)).trim();
    }

    private String dateTime(LocalDateTime value) {
        return value == null ? "" : DATE_TIME_FORMAT.format(value);
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }
}

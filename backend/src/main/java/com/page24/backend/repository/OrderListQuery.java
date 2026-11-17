package com.page24.backend.repository;

import com.page24.backend.entity.CarePlan;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A validated, normalised order search. It is built by OrderListQueryParser, so every value here is already
 * checked: the repository only turns it into SQL and never has to validate anything.
 *
 * A null filter means "do not filter on this". The text patterns are ready for LIKE ... ESCAPE '\': lower-cased,
 * wildcards escaped, wrapped in %...%. The date range is half-open: createdFrom is included, createdBefore is not.
 *
 * @param sort never empty; the last key is always unique (the id), so paging is stable
 */
public record OrderListQuery(
        int page,
        int pageSize,
        CarePlan.Status status,
        Long patientId,
        Long providerId,
        String patientNamePattern,
        String mrn,
        String medicationPattern,
        String providerNamePattern,
        LocalDateTime createdFrom,
        LocalDateTime createdBefore,
        List<OrderSortKey> sort
) {
    public OrderListQuery {
        sort = List.copyOf(sort);
    }

    /** Index of the first row of the requested page; the parser guarantees it fits in an int. */
    public int offset() {
        return (page - 1) * pageSize;
    }
}

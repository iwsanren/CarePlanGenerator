package com.page24.backend.repository;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The only fields the order list may be sorted by. The wire name is what a caller writes in ?sort=name,dir;
 * anything that is not in this list is rejected before a query is built, so no caller text ever reaches the
 * ORDER BY clause.
 */
public enum OrderSortField {
    ID("id"),
    CREATED_AT("created_at"),
    MEDICATION("medication"),
    PATIENT_NAME("patient_name"),
    PROVIDER_NAME("provider_name"),
    STATUS("status");

    private final String parameterName;

    OrderSortField(String parameterName) {
        this.parameterName = parameterName;
    }

    public String parameterName() {
        return parameterName;
    }

    /** The field for a wire name such as "created_at", or empty when it is not on the list. */
    public static Optional<OrderSortField> fromParameterName(String name) {
        return Arrays.stream(values()).filter(field -> field.parameterName.equals(name)).findFirst();
    }

    /** "id, created_at, medication, ..." for error messages (a fixed text, never the caller's input). */
    public static String allowedNames() {
        return Arrays.stream(values()).map(OrderSortField::parameterName).collect(Collectors.joining(", "));
    }
}

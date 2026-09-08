package com.page24.backend.dto;

/**
 * A duplicate check warning.
 * actionRequired = true  → Must set `confirm` to `true` to create the order;
 * actionRequired = false → Pure informational message; does not block the operation.
 */
public record Warning(String code, String message, boolean actionRequired) {
}

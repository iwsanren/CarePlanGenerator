package com.page24.backend.repository;

/** One sort criterion: a whitelisted field and a direction. */
public record OrderSortKey(OrderSortField field, boolean ascending) {
}

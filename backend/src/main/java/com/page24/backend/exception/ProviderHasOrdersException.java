package com.page24.backend.exception;

import java.util.List;

/** Thrown when an attempt is made to delete a Provider referenced by Orders. */
public class ProviderHasOrdersException extends RuntimeException {

    private final List<Long> orderIds;

    public ProviderHasOrdersException(List<Long> orderIds) {
        super("Provider cannot be deleted because it is associated with existing orders");
        this.orderIds = List.copyOf(orderIds);
    }

    public List<Long> getOrderIds() {
        return orderIds;
    }
}

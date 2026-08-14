package com.page24.backend.exception;

import org.springframework.http.HttpStatus;

/** Thrown when an API request references an order that does not exist. */
public class OrderNotFoundException extends BaseAppException {

    public OrderNotFoundException(Long orderId) {
        super("error", "ORDER_NOT_FOUND", "Order not found", null, HttpStatus.NOT_FOUND);
    }
}

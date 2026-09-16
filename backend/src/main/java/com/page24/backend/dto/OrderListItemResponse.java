package com.page24.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/** Compact representation returned by the order list endpoint. */
@Data
@AllArgsConstructor
public class OrderListItemResponse {
    private Long id;

    private String patientName;

    private String patientMrn;

    private String medicationName;

    private String status;

    private Instant createdAt;

    private String providerName;

    private String providerNpi;
}

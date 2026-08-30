package com.page24.backend.dto;

import lombok.Data;

import java.time.Instant;

/** Order fields included inside GET /patients/{id}. */
@Data
public class PatientOrderSummaryResponse {
    private Long id;

    private String medicationName;

    private String status;

    private Instant createdAt;
}

package com.page24.backend.dto;

import lombok.Data;

import java.time.Instant;

/** Order and CarePlan summary returned by GET /patients/{id}/history. */
@Data
public class PatientHistoryOrderResponse {
    private Long id;

    private String patientMrn;

    private String patientName;

    private String providerNpi;

    private String providerName;

    private String medicationName;

    private String status;

    private boolean hasCarePlan;

    private Instant createdAt;
}

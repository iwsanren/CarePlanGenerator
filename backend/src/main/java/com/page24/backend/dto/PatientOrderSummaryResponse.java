package com.page24.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;

/** Order fields included inside GET /patients/{id}. */
@Data
public class PatientOrderSummaryResponse {
    private Long id;

    @JsonProperty("medication_name")
    private String medicationName;

    private String status;

    @JsonProperty("created_at")
    private Instant createdAt;
}

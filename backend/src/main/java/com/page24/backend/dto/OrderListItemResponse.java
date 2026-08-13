package com.page24.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/** Compact representation returned by the order list endpoint. */
@Data
@AllArgsConstructor
public class OrderListItemResponse {
    private Long id;

    @JsonProperty("patient_name")
    private String patientName;

    @JsonProperty("medication_name")
    private String medicationName;

    private String status;

    @JsonProperty("created_at")
    private Instant createdAt;
}

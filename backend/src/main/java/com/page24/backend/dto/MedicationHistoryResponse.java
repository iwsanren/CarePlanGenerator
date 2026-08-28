package com.page24.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;

@Data
public class MedicationHistoryResponse {

    private Long id;

    @JsonProperty("medication_name")
    private String medicationName;

    private String dosage;

    private String frequency;

    @JsonProperty("is_current")
    private boolean current;

    @JsonProperty("created_at")
    private Instant createdAt;
}

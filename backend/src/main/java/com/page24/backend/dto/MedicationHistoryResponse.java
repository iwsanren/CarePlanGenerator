package com.page24.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;

@Data
public class MedicationHistoryResponse {

    private Long id;

    private String medicationName;

    private String dosage;

    private String frequency;

    @JsonProperty("isCurrent")
    private boolean current;

    private Instant createdAt;
}

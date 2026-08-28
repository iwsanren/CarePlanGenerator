package com.page24.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;

@Data
public class PatientDiagnosisResponse {

    private Long id;

    @JsonProperty("icd10_code")
    private String icd10Code;

    private String description;

    @JsonProperty("is_primary")
    private boolean primary;

    @JsonProperty("created_at")
    private Instant createdAt;
}

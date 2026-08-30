package com.page24.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;

@Data
public class PatientDiagnosisResponse {

    private Long id;

    private String icd10Code;

    private String description;

    @JsonProperty("isPrimary")
    private boolean primary;

    private Instant createdAt;
}

package com.page24.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;

/** Summary fields returned for each patient in GET /patients. */
@Data
public class PatientListItemResponse {
    private Long id;

    @JsonProperty("first_name")
    private String firstName;

    @JsonProperty("last_name")
    private String lastName;

    private String mrn;

    @JsonProperty("primary_diagnosis")
    private String primaryDiagnosis;

    @JsonProperty("created_at")
    private Instant createdAt;
}

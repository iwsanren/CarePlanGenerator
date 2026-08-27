package com.page24.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** Summary fields returned for each patient in GET /patients. */
@Data
public class PatientListItemResponse {
    private Long id;

    @JsonProperty("first_name")
    private String firstName;

    @JsonProperty("last_name")
    private String lastName;

    private String mrn;

    @JsonProperty("full_name")
    private String fullName;

    @JsonProperty("primary_diagnosis_code")
    private String primaryDiagnosis;
}

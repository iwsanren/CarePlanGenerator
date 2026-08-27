package com.page24.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Data
public class PatientResponse {
    private Long id;

    @JsonProperty("first_name")
    private String firstName;

    @JsonProperty("last_name")
    private String lastName;

    private String mrn;

    @JsonProperty("date_of_birth")
    private LocalDate dateOfBirth;

    private String sex;

    @JsonProperty("weight_kg")
    private Double weightKg;

    private String allergies;

    @JsonProperty("primary_diagnosis_code")
    private String primaryDiagnosis;

    @JsonProperty("primary_diagnosis_description")
    private String primaryDiagnosisDescription;

    @JsonProperty("additional_diagnoses")
    private List<String> additionalDiagnoses;

    @JsonProperty("created_at")
    private Instant createdAt;
}

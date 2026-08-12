package com.page24.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Response body for GET /patients/{id}.
 *
 * This is separate from PatientResponse because the list/create endpoints do
 * not need to expose a patient's medication history or order summaries.
 */
@Data
public class PatientDetailResponse {
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

    @JsonProperty("primary_diagnosis")
    private String primaryDiagnosis;

    @JsonProperty("additional_diagnoses")
    private List<String> additionalDiagnoses;

    @JsonProperty("medication_history")
    private List<String> medicationHistory;

    private List<PatientOrderSummaryResponse> orders;

    @JsonProperty("created_at")
    private Instant createdAt;
}

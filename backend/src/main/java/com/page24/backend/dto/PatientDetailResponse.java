package com.page24.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Response body for GET /patients/{id}.
 *
 * This is separate from PatientResponse because list/create endpoints do not
 * need to expose a patient's diagnoses and medication history resources.
 */
@Data
public class PatientDetailResponse {
    private Long id;

    @JsonProperty("first_name")
    private String firstName;

    @JsonProperty("last_name")
    private String lastName;

    @JsonProperty("full_name")
    private String fullName;

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

    private List<PatientDiagnosisResponse> diagnoses;

    @JsonProperty("medication_history")
    private List<MedicationHistoryResponse> medicationHistory;

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("updated_at")
    private Instant updatedAt;
}

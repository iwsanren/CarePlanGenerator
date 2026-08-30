package com.page24.backend.dto;

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

    private String firstName;

    private String lastName;

    private String fullName;

    private String mrn;

    private LocalDate dateOfBirth;

    private String sex;

    private Double weightKg;

    private String allergies;

    private String primaryDiagnosis;

    private String primaryDiagnosisDescription;

    private List<PatientDiagnosisResponse> diagnoses;

    private List<MedicationHistoryResponse> medicationHistory;

    private Instant createdAt;

    private Instant updatedAt;
}

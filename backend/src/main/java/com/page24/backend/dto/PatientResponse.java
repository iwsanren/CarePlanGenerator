package com.page24.backend.dto;

import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Data
public class PatientResponse {
    private Long id;

    private String firstName;

    private String lastName;

    private String mrn;

    private LocalDate dateOfBirth;

    private String sex;

    private Double weightKg;

    private String allergies;

    private String primaryDiagnosis;

    private String primaryDiagnosisDescription;

    private List<String> additionalDiagnoses;

    private Instant createdAt;
}

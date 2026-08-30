package com.page24.backend.dto;

import lombok.Data;

/** Summary fields returned for each patient in GET /patients. */
@Data
public class PatientListItemResponse {
    private Long id;

    private String firstName;

    private String lastName;

    private String mrn;

    private String fullName;

    private String primaryDiagnosis;
}

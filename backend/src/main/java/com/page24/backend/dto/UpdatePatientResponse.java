package com.page24.backend.dto;

import lombok.Data;

import java.time.Instant;

/** Response body for PUT /patients/{id}. */
@Data
public class UpdatePatientResponse {
    private Long id;

    private String firstName;

    private String lastName;

    private String mrn;

    private Double weightKg;

    private String allergies;

    private Instant updatedAt;
}

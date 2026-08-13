package com.page24.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;

/** Response body for PUT /patients/{id}. */
@Data
public class UpdatePatientResponse {
    private Long id;

    @JsonProperty("first_name")
    private String firstName;

    @JsonProperty("last_name")
    private String lastName;

    private String mrn;

    @JsonProperty("weight_kg")
    private Double weightKg;

    private String allergies;

    @JsonProperty("updated_at")
    private Instant updatedAt;
}

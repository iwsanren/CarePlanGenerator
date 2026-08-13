package com.page24.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** Partial-update request body for PUT /patients/{id}. */
@Data
public class UpdatePatientRequest {

    @JsonProperty("first_name")
    @Pattern(regexp = ".*\\S.*", message = "first_name cannot be blank")
    private String firstName;

    @JsonProperty("last_name")
    @Pattern(regexp = ".*\\S.*", message = "last_name cannot be blank")
    private String lastName;

    /** MRN is accepted only so the API can return a clear immutability error. */
    private String mrn;
    private boolean mrnProvided;

    @JsonSetter("mrn")
    public void setMrn(String mrn) {
        this.mrn = mrn;
        this.mrnProvided = true;
    }

    @JsonProperty("date_of_birth")
    private LocalDate dateOfBirth;

    private String sex;

    @JsonProperty("weight_kg")
    @Positive(message = "weight_kg must be greater than 0")
    private Double weightKg;

    private String allergies;

    @JsonProperty("primary_diagnosis")
    @Pattern(regexp = CreatePatientRequest.ICD10_REGEX, message = "primary_diagnosis must be a valid ICD-10 code")
    private String primaryDiagnosis;

    @JsonProperty("additional_diagnoses")
    private List<
            @Pattern(regexp = CreatePatientRequest.ICD10_REGEX, message = "additional_diagnoses must contain valid ICD-10 codes")
            String
            > additionalDiagnoses;
}

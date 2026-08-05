package com.page24.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class CreatePatientRequest {

    public static final String ICD10_REGEX = "^[A-Z][0-9][0-9A-Z](\\.[0-9A-Z]{1,4})?$";

    @NotBlank(message = "first_name is required")
    @JsonProperty("first_name")
    private String firstName;

    @NotBlank(message = "last_name is required")
    @JsonProperty("last_name")
    private String lastName;

    @NotBlank(message = "mrn is required")
    @Pattern(regexp = "^\\d{6}$", message = "MRN must be exactly 6 digits")
    private String mrn;

    @NotNull(message = "date_of_birth is required")
    @JsonProperty("date_of_birth")
    private LocalDate dateOfBirth;

    private String sex;

    @NotNull(message = "weight_kg is required")
    @Positive(message = "weight_kg must be greater than 0")
    @JsonProperty("weight_kg")
    private Double weightKg;

    private String allergies;

    @NotBlank(message = "primary_diagnosis is required")
    @Pattern(regexp = ICD10_REGEX, message = "primary_diagnosis must be a valid ICD-10 code")
    @JsonProperty("primary_diagnosis")
    private String primaryDiagnosis;

    @JsonProperty("additional_diagnoses")
    private List<
            @Pattern(regexp = ICD10_REGEX, message = "additional_diagnoses must contain valid ICD-10 codes")
            String
            > additionalDiagnoses;
}

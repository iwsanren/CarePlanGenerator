package com.page24.backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

import com.page24.backend.validation.Icd10Codes;

@Data
public class CreatePatientRequest {

    @NotBlank(message = "first_name is required")
    private String firstName;

    @NotBlank(message = "last_name is required")
    private String lastName;

    @NotBlank(message = "mrn is required")
    @Pattern(regexp = "^\\d{6}$", message = "MRN must be exactly 6 digits")
    private String mrn;

    @PastOrPresent(message = "date_of_birth cannot be in the future")
    private LocalDate dateOfBirth;

    private String sex;

    @Positive(message = "weight_kg must be greater than 0")
    @DecimalMax(value = "500", message = "weight_kg must be less than or equal to 500")
    private Double weightKg;

    private String allergies;

    @NotBlank(message = "primary_diagnosis_code is required")
    @Pattern(regexp = Icd10Codes.REGEX, message = "primary_diagnosis_code must be a valid ICD-10 code")
    @JsonAlias("primary_diagnosis")
    private String primaryDiagnosis;

    @Size(max = 500, message = "primary_diagnosis_description must be at most 500 characters")
    private String primaryDiagnosisDescription;

    private List<
            @Pattern(regexp = Icd10Codes.REGEX, message = "additional_diagnoses must contain valid ICD-10 codes")
            String
            > additionalDiagnoses;
}

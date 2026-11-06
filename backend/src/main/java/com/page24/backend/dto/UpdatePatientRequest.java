package com.page24.backend.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

import com.page24.backend.validation.Icd10Codes;

/** Partial-update request body for PUT /patients/{id}. */
@Data
public class UpdatePatientRequest {

    @Pattern(regexp = ".*\\S.*", message = "first_name cannot be blank")
    private String firstName;

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

    private LocalDate dateOfBirth;

    private String sex;

    @Positive(message = "weight_kg must be greater than 0")
    private Double weightKg;

    private String allergies;

    @Pattern(regexp = Icd10Codes.REGEX, message = "primary_diagnosis must be a valid ICD-10 code")
    private String primaryDiagnosis;

    /**
     * Optional description of the primary diagnosis. When the primary diagnosis code changes and this is not sent,
     * the stored description is cleared, because the old text no longer describes the new code.
     */
    @Size(max = 500, message = "primary_diagnosis_description must be at most 500 characters")
    private String primaryDiagnosisDescription;

    private List<
            @Pattern(regexp = Icd10Codes.REGEX, message = "additional_diagnoses must contain valid ICD-10 codes")
            String
            > additionalDiagnoses;
}

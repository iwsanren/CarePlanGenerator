package com.page24.backend.intake;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

import com.page24.backend.validation.Icd10Codes;

/**
 * Day9 Step1:
 * Internal unified model for all external intake sources.
 * Only business-required fields. No audit/debug metadata for now.
 */
@Data
public class InternalOrder {
    // Reusable patterns (can later move to ValidationPatterns class)
    public static final String NPI_REGEX = "^\\d{10}$";
    public static final String MRN_REGEX = "^\\d{6}$";

    @NotNull(message = "patient is required")
    @Valid
    private Patient patient;

    @NotNull(message = "provider is required")
    @Valid
    private Provider provider;

    @NotNull(message = "medication is required")
    @Valid
    private Medication medication;

    @NotNull(message = "diagnosis is required")
    @Valid
    private Diagnosis diagnosis;

    @Data
    public static class Patient {
        @NotBlank(message = "patient.firstName is required")
        private String firstName;

        @NotBlank(message = "patient.lastName is required")
        private String lastName;

        @NotBlank(message = "patient.mrn is required")
        @Pattern(regexp = MRN_REGEX, message = "patient.mrn must be exactly 6 digits")
        private String mrn;

        @NotNull(message = "patient.dateOfBirth is required")
        private LocalDate dateOfBirth;
    }

    @Data
    public static class Provider {
        @NotBlank(message = "provider.name is required")
        private String name;

        @NotBlank(message = "provider.npi is required")
        @Pattern(regexp = NPI_REGEX, message = "provider.npi must be exactly 10 digits")
        private String npi;
    }

    @Data
    public static class Medication {
        @NotBlank(message = "medication.name is required")
        private String name;
    }

    @Data
    public static class Diagnosis {
        @NotBlank(message = "diagnosis.primaryDiagnosis is required")
        @Pattern(regexp = Icd10Codes.REGEX, message = "diagnosis.primaryDiagnosis must be a valid ICD-10 code")
        private String primaryDiagnosis;

        // Optional list; each item must be valid ICD-10 when present
        private List<@Pattern(
                regexp = Icd10Codes.REGEX,
                message = "diagnosis.additionalDiagnoses item must be a valid ICD-10 code"
        ) String> additionalDiagnoses;
    }

}

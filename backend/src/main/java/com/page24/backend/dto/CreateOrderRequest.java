package com.page24.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDate;

import com.page24.backend.validation.Icd10Codes;

/**
 * The form data submitted by the user when the frontend creates an order.
 */
@Data
public class CreateOrderRequest {

    @NotBlank(message = "patientFirstName is required")
    private String patientFirstName;

    @NotBlank(message = "patientLastName is required")
    private String patientLastName;

    @NotBlank(message = "patientMrn is required")
    @Pattern(regexp = "^\\d{6}$", message = "MRN must be exactly 6 digits")
    private String patientMrn;

    @NotNull(message = "patientDateOfBirth is required")
    private LocalDate patientDateOfBirth;

    @NotBlank(message = "providerName is required")
    private String providerName;

    @NotBlank(message = "providerNpi is required")
    @Pattern(regexp = "^\\d{10}$", message = "NPI must be exactly 10 digits")
    private String providerNpi;

    @NotBlank(message = "medicationName is required")
    private String medicationName;

    @NotBlank(message = "primaryDiagnosis is required")
    @Pattern(regexp = Icd10Codes.REGEX, message = "primaryDiagnosis must be a valid ICD-10 code")
    private String primaryDiagnosis;

    @Pattern(regexp = Icd10Codes.LIST_REGEX, message = "additionalDiagnosis must be comma-separated ICD-10 codes")
    private String additionalDiagnosis;
    private String medicationHistory;
    private String patientRecords;

    // Day 8: Allows submission after the user acknowledges a warning, such as a same-medication refill on a different day.
    private Boolean confirm;
}

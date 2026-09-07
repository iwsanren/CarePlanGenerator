package com.page24.backend.dto;

import jakarta.validation.constraints.NotBlank;
//import jakarta.validation.constraints.NotNull;
import java.util.List;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.time.LocalDate;

import com.page24.backend.validation.Icd10Codes;

/**
 * The form data submitted by the user when the frontend creates an order.
 */
@Data
public class CreateOrderRequest {
    private String patientSex;

    @NotBlank(message = "patientFirstName is required")
    private String patientFirstName;

    @NotBlank(message = "patientLastName is required")
    private String patientLastName;

    @NotBlank(message = "patientMrn is required")
    @Pattern(regexp = "^\\d{6}$", message = "MRN must be exactly 6 digits")
    private String patientMrn;

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

    private List<
            @Pattern(regexp = Icd10Codes.REGEX, message = "additionalDiagnoses must contain valid ICD-10 codes")
                    String
            > additionalDiagnoses;
    private List<
            @Size(max = 500, message = "each medicationHistory entry must be at most 500 characters")
                    String
            > medicationHistory;
    private String patientRecords;

    // Allows submission after the user acknowledges a warning, such as a same-medication refill on a different day.
    private Boolean confirm;

    @Positive(message = "patientWeightKg must be greater than 0")
    @DecimalMax(value = "500", message = "patientWeightKg must be less than or equal to 500")
    private Double patientWeightKg;

    private String patientAllergies;
}

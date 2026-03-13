package com.page24.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDate;

/**
 * 前端发请求时带的数据：用户提交的表单
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
    private String primaryDiagnosis;

    private String additionalDiagnosis;
    private String medicationHistory;
    private String patientRecords;

    // Day 8: 用户确认 warning 后可继续提交（如不同天同药续方）
    private Boolean confirm;
}

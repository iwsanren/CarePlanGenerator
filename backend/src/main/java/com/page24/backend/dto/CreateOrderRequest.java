package com.page24.backend.dto;

import lombok.Data;
import java.time.LocalDate;

/**
 * 前端发请求时带的数据：用户提交的表单
 */
@Data
public class CreateOrderRequest {
    private String patientFirstName;
    private String patientLastName;
    private String patientMrn;
    private LocalDate patientDateOfBirth;

    private String providerName;
    private String providerNpi;

    private String medicationName;
    private String primaryDiagnosis;
    private String additionalDiagnosis;
    private String medicationHistory;
    private String patientRecords;

    // Day 8: 用户确认 warning 后可继续提交（如不同天同药续方）
    private Boolean confirm;
}


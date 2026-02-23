package com.page24.backend.dto;

import lombok.Data;
import java.time.LocalDate;

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
}


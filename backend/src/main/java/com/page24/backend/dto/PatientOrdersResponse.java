package com.page24.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** Response body for GET /patients/{id}/orders. */
@Data
@AllArgsConstructor
public class PatientOrdersResponse {
    private Long patientId;

    private String patientName;

    private List<PatientOrderSummaryResponse> orders;
}

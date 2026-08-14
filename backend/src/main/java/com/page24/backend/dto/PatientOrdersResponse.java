package com.page24.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** Response body for GET /patients/{id}/orders. */
@Data
@AllArgsConstructor
public class PatientOrdersResponse {
    @JsonProperty("patient_id")
    private Long patientId;

    @JsonProperty("patient_name")
    private String patientName;

    private List<PatientOrderSummaryResponse> orders;
}

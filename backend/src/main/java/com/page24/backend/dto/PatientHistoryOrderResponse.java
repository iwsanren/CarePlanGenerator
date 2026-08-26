package com.page24.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;

/** Order and CarePlan summary returned by GET /patients/{id}/history. */
@Data
public class PatientHistoryOrderResponse {
    private Long id;

    @JsonProperty("patient_mrn")
    private String patientMrn;

    @JsonProperty("patient_name")
    private String patientName;

    @JsonProperty("provider_npi")
    private String providerNpi;

    @JsonProperty("provider_name")
    private String providerName;

    @JsonProperty("medication_name")
    private String medicationName;

    private String status;

    @JsonProperty("has_care_plan")
    private boolean hasCarePlan;

    @JsonProperty("created_at")
    private Instant createdAt;
}

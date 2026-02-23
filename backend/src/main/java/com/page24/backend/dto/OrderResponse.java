package com.page24.backend.dto;

import lombok.Data;

@Data
public class OrderResponse {
    private Long id;
    private Long patientId;
    private Long providerId;
    private String medicationName;
    private String status;
    private String carePlanContent;
}


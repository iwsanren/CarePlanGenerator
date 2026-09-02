package com.page24.backend.dto;

import lombok.Data;

import java.util.List;

/**
 * The API response returned to the frontend after an order request is processed,
 * such as the result of a query or submission.
 */
@Data
public class OrderResponse {
    private Long id;
    private Long patientId;
    private Long providerId;
    private String medicationName;
    private String status;
    private String carePlanContent;

    // Day 8: High-level outcome classification for the request (SUCCESS or WARNING).
    private String resultType;
    private String message;
    private List<String> warnings;
    private Boolean requiresConfirm;
}


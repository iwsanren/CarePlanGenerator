package com.page24.backend.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class ProviderResponse {
    private Long id;
    private String name;
    private String npi;
    private String phone;
    private String fax;

    private Instant createdAt;

    private Instant updatedAt;
}

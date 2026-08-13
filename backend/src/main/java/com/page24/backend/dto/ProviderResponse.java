package com.page24.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;

@Data
public class ProviderResponse {
    private Long id;
    private String name;
    private String npi;

    @JsonProperty("created_at")
    private Instant createdAt;
}

package com.page24.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Lightweight Provider representation returned by the paginated list API. */
@Data
@AllArgsConstructor
public class ProviderListItemResponse {
    private Long id;
    private String npi;
    private String name;
}

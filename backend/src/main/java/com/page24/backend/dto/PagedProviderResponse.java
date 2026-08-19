package com.page24.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** Response contract for GET /api/v1/providers/. */
@Data
@AllArgsConstructor
public class PagedProviderResponse {
    private long count;
    private String next;
    private String previous;
    private List<ProviderListItemResponse> results;
}

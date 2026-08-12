package com.page24.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** Paginated response body for GET /patients. */
@Data
@AllArgsConstructor
public class PagedPatientResponse {
    private long count;
    private int page;

    @JsonProperty("page_size")
    private int pageSize;

    private List<PatientListItemResponse> results;
}

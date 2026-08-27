package com.page24.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** Paginated response body for GET /patients. */
@Data
@AllArgsConstructor
public class PagedPatientResponse {
    private long count;
    private String next;
    private String previous;
    private List<PatientListItemResponse> results;
}

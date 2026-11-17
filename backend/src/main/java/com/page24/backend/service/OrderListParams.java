package com.page24.backend.service;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/**
 * The raw query parameters of GET /api/v1/orders, exactly as the caller sent them. Nothing here is checked yet:
 * OrderListQueryParser validates and normalises it. A builder is used because most fields are Strings, which a
 * positional constructor would make easy to mix up.
 */
@Getter
@Builder
public class OrderListParams {
    @Builder.Default
    private final int page = 1;
    @Builder.Default
    private final int pageSize = 20;
    private final String status;
    private final Long patientId;
    private final Long providerId;
    private final String patientName;
    private final String mrn;
    private final String medication;
    private final String providerName;
    private final LocalDate createdFrom;
    private final LocalDate createdTo;
    /** Every ?sort= value, in the order given; each looks like "field" or "field,asc|desc". */
    private final List<String> sort;
}

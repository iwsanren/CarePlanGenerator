package com.page24.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Paginated response for GET /api/orders.
 *
 * count is the total number of records matching the current filters,
 * not the size of the current page.
 */
@Data
@AllArgsConstructor
public class PagedOrderResponse {
    private long count;
    private int page;

    private int pageSize;

    private List<OrderListItemResponse> results;
}

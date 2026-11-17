package com.page24.backend.repository;

import java.util.List;

/**
 * One page of search results.
 *
 * @param total number of rows matching the filters over ALL pages
 * @param rows  the rows of the requested page (empty when the page is past the end)
 */
public record OrderSearchPage(long total, List<OrderListRow> rows) {
    public OrderSearchPage {
        rows = List.copyOf(rows);
    }
}

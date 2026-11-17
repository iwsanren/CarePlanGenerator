package com.page24.backend.repository;

/**
 * The order list search. Spring Data merges this fragment into OrderRepository and finds the implementation by
 * its name (OrderSearchRepositoryImpl).
 */
public interface OrderSearchRepository {

    /** Runs a validated search: one query for the total, one for the page (none when the page is past the end). */
    OrderSearchPage search(OrderListQuery query);
}

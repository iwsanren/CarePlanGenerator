package com.page24.backend.repository;

import com.page24.backend.entity.CarePlan;

import java.time.LocalDateTime;

/**
 * One row of the order list, read straight from the database as columns. Selecting columns instead of loading
 * Order entities is what keeps the list at two SQL statements: an Order drags in two EAGER collections and its
 * patient and provider, which would cost extra statements for every row.
 */
public record OrderListRow(
        Long id,
        String patientFirstName,
        String patientLastName,
        String patientMrn,
        String medicationName,
        CarePlan.Status status,
        LocalDateTime createdAt,
        String providerName,
        String providerNpi
) {
}

package com.page24.backend.validation;

/** Shared NPI/MRN format rules, referenced by every DTO and controller that validates them. */
public final class IdentifierFormats {
    private IdentifierFormats() {}

    // National Provider Identifier: exactly 10 digits.
    public static final String NPI_REGEX = "^\\d{10}$";

    // Medical Record Number: exactly 6 digits.
    public static final String MRN_REGEX = "^\\d{6}$";
}

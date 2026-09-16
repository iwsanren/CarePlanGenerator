package com.page24.backend.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("IdentifierFormats — NPI and MRN format validation")
class IdentifierFormatsTest {

    private static final Pattern NPI_PATTERN = Pattern.compile(IdentifierFormats.NPI_REGEX);
    private static final Pattern MRN_PATTERN = Pattern.compile(IdentifierFormats.MRN_REGEX);

    @ParameterizedTest
    @ValueSource(strings = {"1234567890", "0000000000", "9999999999"})
    @DisplayName("NPI: exactly 10 digits is accepted")
    void acceptsValidNpi(String npi) {
        assertTrue(NPI_PATTERN.matcher(npi).matches());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "123456789",     // 9 digits — too short
            "12345678901",   // 11 digits — too long
            "12345 6789",    // contains a space
            "123456789a",    // contains a letter
            "",              // empty
    })
    @DisplayName("NPI: anything but exactly 10 digits is rejected")
    void rejectsInvalidNpi(String npi) {
        assertFalse(NPI_PATTERN.matcher(npi).matches());
    }

    @ParameterizedTest
    @ValueSource(strings = {"000000", "123456", "999999"})
    @DisplayName("MRN: exactly 6 digits is accepted")
    void acceptsValidMrn(String mrn) {
        assertTrue(MRN_PATTERN.matcher(mrn).matches());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "12345",    // 5 digits — too short
            "1234567",  // 7 digits — too long
            "12 345",   // contains a space
            "12345a",   // contains a letter
            "",         // empty
    })
    @DisplayName("MRN: anything but exactly 6 digits is rejected")
    void rejectsInvalidMrn(String mrn) {
        assertFalse(MRN_PATTERN.matcher(mrn).matches());
    }
}

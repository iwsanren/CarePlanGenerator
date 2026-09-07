package com.page24.backend.validation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Icd10Codes.REGEX — Single ICD-10-CM diagnosis code format validation")
class Icd10CodesTest {

    private static final Pattern PATTERN = Pattern.compile(Icd10Codes.REGEX);

    @ParameterizedTest
    @ValueSource(strings = {
            "I10",        // Category only, no decimal portion
            "E11.9",      // Common code with a decimal
            "E11.65",     // Two characters after the decimal point
            "M79.3",
            "Z99.89",
            "A00",
            "T81.4XXA",   // Four letters after the decimal point (ICD-10-CM 7th character extension)
            "V97.33XD",   // Initial letter V is valid (immediately follows excluded U)
    })
    @DisplayName("Valid codes should be accepted")
    void acceptsValidCodes(String code) {
        assertTrue(PATTERN.matcher(code).matches(), () -> "Should be accepted: " + code);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "U07.1",      // U is a reserved category and is no longer accepted after Part 1 tightening
            "U12.3",
            "A1B",        // Characters 2–3 must be digits and are no longer accepted after Part 1 tightening
            "AB1",
            "110",        // First character must be a letter
            "i10",        // Lowercase is not accepted
            "E11.",       // Decimal point present but decimal portion is empty
            "E11.12345",  // More than four characters after the decimal point
            "E11 9",      // Space used as a separator
            "",           // Empty string
            " I10",       // Leading whitespace
    })
    @DisplayName("Invalid or malformed codes should be rejected")
    void rejectsInvalidCodes(String code) {
        assertFalse(PATTERN.matcher(code).matches(), () -> "Should be rejected: " + code);
    }
}
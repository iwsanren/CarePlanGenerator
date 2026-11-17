package com.page24.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** What a person types becomes a literal "contains" pattern: wildcards are escaped, case is ignored. */
class LikePatternsTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
            "smith     | %smith%",
            "SmItH     | %smith%",
            "o_neil    | %o\\_neil%",
            "100%      | %100\\%%",
            "%          | %\\%%",
            "_         | %\\_%",
            "a\\b      | %a\\\\b%",
            "\\%_      | %\\\\\\%\\_%"})
    void escapesTheWildcardsAndLowerCases(String typed, String pattern) {
        assertThat(LikePatterns.contains(typed)).isEqualTo(pattern);
    }

    @Test
    @DisplayName("an apostrophe, a space and a hyphen are ordinary characters")
    void leavesOtherCharactersAlone() {
        assertThat(LikePatterns.contains("O'Brien-Smith Jr")).isEqualTo("%o'brien-smith jr%");
    }

    @Test
    @DisplayName("case folding does not depend on the machine's language (Turkish dotless i)")
    void lowerCasesWithTheRootLocale() {
        assertThat(LikePatterns.contains("ILIAS")).isEqualTo("%ilias%");
    }
}

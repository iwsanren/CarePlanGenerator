package com.page24.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvWriterTest {

    @ParameterizedTest
    @ValueSource(strings = {"=SUM(A1:A9)", "+1+1", "-1+1", "@cmd"})
    @DisplayName("a value starting with =, +, -, or @ is prefixed with a single quote to defuse it as a spreadsheet formula")
    void neutralizesLeadingFormulaCharacters(String dangerous) {
        assertThat(CsvWriter.escapeCell(dangerous)).isEqualTo("\"'" + dangerous + "\"");
    }

    @Test
    @DisplayName("a normal value is quoted as-is")
    void quotesOrdinaryValues() {
        assertThat(CsvWriter.escapeCell("Alice Wong")).isEqualTo("\"Alice Wong\"");
    }

    @Test
    @DisplayName("an internal double-quote is doubled per CSV escaping rules")
    void doublesInternalQuotes() {
        assertThat(CsvWriter.escapeCell("5\" tall")).isEqualTo("\"5\"\" tall\"");
    }

    @Test
    @DisplayName("null becomes an empty quoted cell")
    void nullBecomesEmptyCell() {
        assertThat(CsvWriter.escapeCell(null)).isEqualTo("\"\"");
    }

    @Test
    @DisplayName("appendRow joins escaped cells with commas and ends with CRLF")
    void appendRowJoinsWithCommasAndCrlf() {
        StringBuilder csv = new StringBuilder();
        CsvWriter.appendRow(csv, List.of("a", "b,c", "d\"e"));
        assertThat(csv.toString()).isEqualTo("\"a\",\"b,c\",\"d\"\"e\"\r\n");
    }
}

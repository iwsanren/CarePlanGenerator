package com.page24.backend.service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Shared CSV formatting for every report/export endpoint. Centralized so the
 * formula-injection guard (escapeCell) only has to be gotten right once —
 * previously this lived duplicated inside ReportService.
 */
public final class CsvWriter {

    private CsvWriter() {}

    public static void appendRow(StringBuilder csv, List<String> values) {
        csv.append(values.stream().map(CsvWriter::escapeCell).collect(Collectors.joining(","))).append("\r\n");
    }

    /** Quotes every cell and neutralizes a leading =+-@ so a spreadsheet can't run it as a formula. */
    public static String escapeCell(String value) {
        String safeValue = value == null ? "" : value;
        if (!safeValue.isEmpty() && "=+-@".indexOf(safeValue.charAt(0)) >= 0) {
            safeValue = "'" + safeValue;
        }
        return '"' + safeValue.replace("\"", "\"\"") + '"';
    }
}

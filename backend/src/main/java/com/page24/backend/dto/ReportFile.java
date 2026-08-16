package com.page24.backend.dto;

/** A generated report that a controller can return as a file download. */
public record ReportFile(String filename, byte[] content) {
}

package com.page24.backend.dto;

/** The file name and bytes returned by a completed CarePlan download. */
public record CarePlanDownload(String filename, byte[] content) {
}

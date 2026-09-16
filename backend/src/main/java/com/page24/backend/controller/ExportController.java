package com.page24.backend.controller;

import com.page24.backend.dto.ReportFile;
import com.page24.backend.service.ExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * A single, unfiltered "export everything" endpoint — deliberately separate
 * from ReportController's filtered /reports/* endpoints. This is what the
 * reference implementation calls GET /export/, and what the frontend's
 * "Export CSV" button is actually supposed to hit.
 */
@RestController
@RequestMapping("/api/v1/export")
@RequiredArgsConstructor
public class ExportController {

    private final ExportService exportService;

    @GetMapping({"", "/"})
    public ResponseEntity<byte[]> exportAll() {
        ReportFile report = exportService.exportAll();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + report.filename() + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .contentLength(report.content().length)
                .body(report.content());
    }
}

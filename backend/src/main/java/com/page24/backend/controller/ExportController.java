package com.page24.backend.controller;

import com.page24.backend.dto.ReportFile;
import com.page24.backend.service.ExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Export", description = "Unfiltered CSV export (PHARMACIST only)")
public class ExportController {

    private final ExportService exportService;

    @Operation(summary = "Export every order and care plan as CSV",
            description = "PHARMACIST only. Returns a CSV file download (Content-Disposition: attachment).",
            responses = @ApiResponse(responseCode = "200", description = "CSV file",
                    content = @Content(mediaType = "text/csv", schema = @Schema(type = "string", format = "binary"))))
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

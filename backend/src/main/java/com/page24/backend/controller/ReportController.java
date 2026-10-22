package com.page24.backend.controller;

import com.page24.backend.dto.ReportFile;
import com.page24.backend.service.ReportService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@Tag(name = "Reports", description = "Filtered CSV reports (PHARMACIST only)")
public class ReportController {

    private final ReportService reportService;

    @Operation(summary = "Export orders as CSV with optional filters",
            description = "PHARMACIST only. Filters: start_date, end_date, status, provider_id. "
                    + "Returns a CSV file download (Content-Disposition: attachment).",
            responses = @ApiResponse(responseCode = "200", description = "CSV file",
                    content = @Content(mediaType = "text/csv", schema = @Schema(type = "string", format = "binary"))))
    @GetMapping("/orders/export")
    public ResponseEntity<byte[]> exportOrders(
            @RequestParam(required = false) String format,
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(required = false) String status,
            @RequestParam(name = "provider_id", required = false) Long providerId
    ) {
        ReportFile report = reportService.exportOrders(format, startDate, endDate, status, providerId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + report.filename() + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .contentLength(report.content().length)
                .body(report.content());
    }
}

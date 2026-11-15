package com.page24.backend.controller;

import com.page24.backend.dto.DashboardStatsResponse;
import com.page24.backend.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP layer only: reads the range and delegates to {@link DashboardService}. */
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "Order, care plan and LLM statistics (PHARMACIST only)")
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(summary = "Dashboard statistics",
            description = "PHARMACIST only (ASSISTANT gets 403). Statistics of the orders created in the last "
                    + "`days` whole UTC calendar days, today included: daily order volume (days without orders "
                    + "are 0), care plan status shares, top medications, per-provider figures and the KPI row. "
                    + "Failure rate = failed / (completed + failed) over LLM-generated care plans; manual uploads "
                    + "and unfinished plans are excluded. Durations are seconds from started_at to finished_at "
                    + "(including retry back-off) over COMPLETED LLM care plans; plans without timing data are "
                    + "left out. A rate or average with nothing to measure is null, not 0. "
                    + "`days` must be 7 or 30, otherwise 400 INVALID_DAYS.")
    @GetMapping("/stats")
    public ResponseEntity<DashboardStatsResponse> getStats(
            @Parameter(description = "Window length in days: 7 or 30", schema = @Schema(allowableValues = {"7", "30"}))
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(dashboardService.getStats(days));
    }
}

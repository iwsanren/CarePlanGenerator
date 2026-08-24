package com.page24.backend.controller;

import com.page24.backend.dto.OrderResponse;
import com.page24.backend.service.IntakeService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Day9 intake endpoints for external sources.
 * Step3 focus: source-specific parsing/mapping, then reuse OrderService.
 */
@RestController
@RequestMapping("/api/v1/intake")
@RequiredArgsConstructor
@Validated
public class IntakeController {

    private final IntakeService intakeService;

    @PostMapping(value = "/clinic-b", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponse> createFromClinicB(
            @RequestBody @NotBlank(message = "Clinic B payload is required") String rawJson,
            @RequestParam(defaultValue = "false") boolean confirm
    ) {
        OrderResponse response = intakeService.createFromClinicBJson(rawJson, confirm);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping(value = "/pharma-corp", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ResponseEntity<OrderResponse> createFromPharmaCorp(
            @RequestBody @NotBlank(message = "PharmaCorp payload is required") String rawXml,
            @RequestParam(defaultValue = "false") boolean confirm
    ) {
        OrderResponse response = intakeService.createFromPharmaCorpXml(rawXml, confirm);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping(value = "/hospital-d", consumes = {MediaType.TEXT_PLAIN_VALUE, "text/csv"})
    public ResponseEntity<OrderResponse> createFromHospitalD(
            @RequestBody @NotBlank(message = "Hospital D payload is required") String rawCsv,
            @RequestParam(defaultValue = "false") boolean confirm
    ){
        OrderResponse response = intakeService.createFromHospitalDCsv(rawCsv, confirm);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}


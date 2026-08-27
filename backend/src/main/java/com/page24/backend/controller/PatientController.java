package com.page24.backend.controller;

import com.page24.backend.dto.CreatePatientRequest;
import com.page24.backend.dto.PatientDetailResponse;
import com.page24.backend.dto.PatientHistoryOrderResponse;
import com.page24.backend.dto.PagedPatientResponse;
import com.page24.backend.dto.PatientResponse;
import com.page24.backend.dto.UpdatePatientRequest;
import com.page24.backend.dto.UpdatePatientResponse;
import com.page24.backend.dto.PatientOrdersResponse;
import com.page24.backend.service.PatientService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

@RestController
@RequestMapping("/api/v1/patients")
@RequiredArgsConstructor
public class PatientController {

    private final PatientService patientService;

    @PostMapping
    public ResponseEntity<PatientResponse> createPatient(@Valid @RequestBody CreatePatientRequest request) {
        PatientResponse response = patientService.createPatient(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<PagedPatientResponse> getPatients(
            @RequestParam(defaultValue = "1") String page,
            HttpServletRequest request
    ) {
        String baseUrl = ServletUriComponentsBuilder.fromRequest(request)
                .replaceQuery(null)
                .toUriString();
        return ResponseEntity.ok(patientService.getPatients(page, baseUrl));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PatientDetailResponse> getPatientById(@PathVariable Long id) {
        return ResponseEntity.ok(patientService.getPatientById(id));
    }

    @GetMapping("/by-mrn/{mrn}")
    public ResponseEntity<PatientDetailResponse> getPatientByMrn(@PathVariable String mrn) {
        if (!mrn.matches("\\d{6}")) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(patientService.getPatientByMrn(mrn));
    }

    @GetMapping("/{id}/orders")
    public ResponseEntity<PatientOrdersResponse> getPatientOrders(@PathVariable Long id) {
        return ResponseEntity.ok(patientService.getPatientOrders(id));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<List<PatientHistoryOrderResponse>> getPatientHistory(@PathVariable Long id) {
        return ResponseEntity.ok(patientService.getPatientHistory(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<UpdatePatientResponse> updatePatient(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePatientRequest request
    ) {
        return ResponseEntity.ok(patientService.updatePatient(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePatient(@PathVariable Long id) {
        patientService.deletePatient(id);
        return ResponseEntity.noContent().build();
    }
}

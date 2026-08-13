package com.page24.backend.controller;

import com.page24.backend.dto.CreatePatientRequest;
import com.page24.backend.dto.PatientDetailResponse;
import com.page24.backend.dto.PagedPatientResponse;
import com.page24.backend.dto.PatientResponse;
import com.page24.backend.dto.UpdatePatientRequest;
import com.page24.backend.dto.UpdatePatientResponse;
import com.page24.backend.service.PatientService;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PatientController {

    private final PatientService patientService;

    @PostMapping("/patients")
    public ResponseEntity<PatientResponse> createPatient(@Valid @RequestBody CreatePatientRequest request) {
        PatientResponse response = patientService.createPatient(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/patients")
    public ResponseEntity<PagedPatientResponse> getPatients(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false) String search
    ) {
        return ResponseEntity.ok(patientService.getPatients(page, pageSize, search));
    }

    @GetMapping("/patients/{id}")
    public ResponseEntity<PatientDetailResponse> getPatientById(@PathVariable Long id) {
        return ResponseEntity.ok(patientService.getPatientById(id));
    }

    @PutMapping("/patients/{id}")
    public ResponseEntity<UpdatePatientResponse> updatePatient(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePatientRequest request
    ) {
        return ResponseEntity.ok(patientService.updatePatient(id, request));
    }

    @DeleteMapping("/patients/{id}")
    public ResponseEntity<Void> deletePatient(@PathVariable Long id) {
        patientService.deletePatient(id);
        return ResponseEntity.noContent().build();
    }
}

package com.page24.backend.controller;

import com.page24.backend.dto.CreateProviderRequest;
import com.page24.backend.dto.ProviderResponse;
import com.page24.backend.service.ProviderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ProviderController {

    private final ProviderService providerService;

    @PostMapping("/providers")
    public ResponseEntity<ProviderResponse> createProvider(@Valid @RequestBody CreateProviderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(providerService.createProvider(request));
    }
}

package com.page24.backend.controller;

import com.page24.backend.dto.CreateProviderRequest;
import com.page24.backend.dto.PagedProviderResponse;
import com.page24.backend.dto.ProviderResponse;
import com.page24.backend.service.ProviderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequiredArgsConstructor
public class ProviderController {

    private final ProviderService providerService;

    @PostMapping("/providers")
    public ResponseEntity<ProviderResponse> createProvider(@Valid @RequestBody CreateProviderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(providerService.createProvider(request));
    }

    @PostMapping({"/api/v1/providers", "/api/v1/providers/"})
    public ResponseEntity<ProviderResponse> createProviderV1(@Valid @RequestBody CreateProviderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(providerService.createProvider(request));
    }

    @GetMapping({"/api/v1/providers", "/api/v1/providers/"})
    public ResponseEntity<PagedProviderResponse> getProviders(
            @RequestParam(defaultValue = "1") int page,
            HttpServletRequest request
    ) {
        String baseUrl = ServletUriComponentsBuilder.fromRequest(request)
                .replaceQuery(null)
                .toUriString();
        return ResponseEntity.ok(providerService.getProviders(page, baseUrl));
    }
}

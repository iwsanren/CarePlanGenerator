package com.page24.backend.controller;

import com.page24.backend.dto.CreateProviderRequest;
import com.page24.backend.dto.PagedProviderResponse;
import com.page24.backend.dto.PatchProviderRequest;
import com.page24.backend.dto.ProviderResponse;
import com.page24.backend.dto.UpdateProviderRequest;
import com.page24.backend.service.ProviderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/providers")
@RequiredArgsConstructor
public class ProviderController {

    private final ProviderService providerService;

    @PostMapping({"", "/"})
    public ResponseEntity<ProviderResponse> createProviderV1(@Valid @RequestBody CreateProviderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(providerService.createProvider(request));
    }

    @GetMapping({"", "/"})
    public ResponseEntity<PagedProviderResponse> getProviders(
            @RequestParam(defaultValue = "1") int page,
            HttpServletRequest request
    ) {
        String baseUrl = ServletUriComponentsBuilder.fromRequest(request)
                .replaceQuery(null)
                .toUriString();
        return ResponseEntity.ok(providerService.getProviders(page, baseUrl));
    }

    @GetMapping({"/by-id/{id}", "/by-id/{id}/"})
    public ResponseEntity<ProviderResponse> getProviderById(@PathVariable Long id) {
        return ResponseEntity.ok(providerService.getProviderById(id));
    }

    @GetMapping({"/by-npi/{npi}", "/by-npi/{npi}/"})
    public ResponseEntity<ProviderResponse> getProviderByNpi(@PathVariable String npi) {
        return ResponseEntity.ok(providerService.getProviderByNpi(npi));
    }

    @PutMapping({"/{id}", "/{id}/"})
    public ResponseEntity<ProviderResponse> updateProvider(
            @PathVariable Long id,
            @Valid @RequestBody UpdateProviderRequest request
    ) {
        return ResponseEntity.ok(providerService.updateProvider(id, request));
    }

    @PatchMapping({"/{id}", "/{id}/"})
    public ResponseEntity<ProviderResponse> patchProvider(
            @PathVariable Long id,
            @Valid @RequestBody PatchProviderRequest request
    ) {
        return ResponseEntity.ok(providerService.patchProvider(id, request));
    }

    @DeleteMapping({"/{id}", "/{id}/"})
    public ResponseEntity<Void> deleteProvider(@PathVariable Long id) {
        providerService.deleteProvider(id);
        return ResponseEntity.noContent().build();
    }
}

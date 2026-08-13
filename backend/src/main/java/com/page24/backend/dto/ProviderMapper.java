package com.page24.backend.dto;

import com.page24.backend.entity.Provider;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;

@Component
public class ProviderMapper {

    public ProviderResponse toResponse(Provider provider) {
        ProviderResponse response = new ProviderResponse();
        response.setId(provider.getId());
        response.setName(provider.getName());
        response.setNpi(provider.getNpi());
        if (provider.getCreatedAt() != null) {
            response.setCreatedAt(provider.getCreatedAt().atOffset(ZoneOffset.UTC).toInstant());
        }
        return response;
    }
}

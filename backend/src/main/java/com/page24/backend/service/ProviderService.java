package com.page24.backend.service;

import com.page24.backend.dto.CreateProviderRequest;
import com.page24.backend.dto.PagedProviderResponse;
import com.page24.backend.dto.ProviderMapper;
import com.page24.backend.dto.ProviderResponse;
import com.page24.backend.entity.Provider;
import com.page24.backend.exception.ProviderNameDuplicateException;
import com.page24.backend.exception.ProviderNpiConflictException;
import com.page24.backend.repository.ProviderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProviderService {

    private static final int PROVIDER_LIST_PAGE_SIZE = 20;

    private final ProviderRepository providerRepository;
    private final ProviderMapper providerMapper;

    @Transactional
    public ProviderResponse createProvider(CreateProviderRequest request) {
        String name = request.getName().trim();
        String npi = request.getNpi();

        providerRepository.findByNpi(npi).ifPresent(existingProvider -> {
            String message = sameText(existingProvider.getName(), name)
                    ? "A provider with NPI " + npi + " already exists"
                    : "Provider conflict: NPI " + npi + " already belongs to '"
                            + existingProvider.getName() + "'";
            throw new ProviderNpiConflictException(message, existingProvider.getId());
        });

        providerRepository.findFirstByNameIgnoreCase(name).ifPresent(existingProvider -> {
            throw new ProviderNameDuplicateException(
                    "A provider named '" + existingProvider.getName() + "' already exists with NPI "
                            + existingProvider.getNpi() + ". Please verify this is not a duplicate.",
                    existingProvider.getId()
            );
        });

        Provider provider = new Provider();
        provider.setName(name);
        provider.setNpi(npi);
        provider.setPhone(request.getPhone());
        provider.setFax(request.getFax());
        return providerMapper.toResponse(providerRepository.save(provider));
    }

    @Transactional(readOnly = true)
    public PagedProviderResponse getProviders(int page, String baseUrl) {
        PageRequest pageRequest = PageRequest.of(
                page - 1,
                PROVIDER_LIST_PAGE_SIZE,
                Sort.by(Sort.Direction.ASC, "name")
        );
        Page<Provider> providers = providerRepository.findAll(pageRequest);

        return new PagedProviderResponse(
                providers.getTotalElements(),
                providers.hasNext() ? pageUrl(baseUrl, page + 1) : null,
                providers.hasPrevious() ? pageUrl(baseUrl, page - 1) : null,
                providers.getContent().stream().map(providerMapper::toListItemResponse).toList()
        );
    }

    private String pageUrl(String baseUrl, int page) {
        return baseUrl + "?page=" + page;
    }

    private boolean sameText(String first, String second) {
        return first != null && second != null && first.trim().equalsIgnoreCase(second.trim());
    }
}

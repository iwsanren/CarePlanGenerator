package com.page24.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Full-replacement request body for PUT /api/v1/providers/{id}. */
@Data
public class UpdateProviderRequest {

    @NotBlank(message = "name is required")
    private String name;

    @NotBlank(message = "npi is required")
    @Pattern(regexp = "^\\d{10}$", message = "NPI must be exactly 10 digits")
    private String npi;

    @Size(max = 20, message = "phone must not exceed 20 characters")
    private String phone;

    @Size(max = 20, message = "fax must not exceed 20 characters")
    private String fax;
}

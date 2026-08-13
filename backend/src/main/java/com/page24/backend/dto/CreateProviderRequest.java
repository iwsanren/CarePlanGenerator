package com.page24.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class CreateProviderRequest {

    @NotBlank(message = "name is required")
    private String name;

    @NotBlank(message = "npi is required")
    @Pattern(regexp = "^\\d{10}$", message = "NPI must be exactly 10 digits")
    private String npi;
}

package com.page24.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Partial-update request body for PATCH /api/v1/providers/{id}. */
@Data
public class PatchProviderRequest {

    @Pattern(regexp = ".*\\S.*", message = "name cannot be blank")
    private String name;

    @Pattern(regexp = "^\\d{10}$", message = "NPI must be exactly 10 digits")
    private String npi;

    @Size(max = 20, message = "phone must not exceed 20 characters")
    private String phone;

    @Size(max = 20, message = "fax must not exceed 20 characters")
    private String fax;

    @JsonIgnore
    private boolean nameProvided;

    @JsonIgnore
    private boolean npiProvided;

    @JsonIgnore
    private boolean phoneProvided;

    @JsonIgnore
    private boolean faxProvided;

    @JsonSetter("name")
    public void setName(String name) {
        this.name = name;
        this.nameProvided = true;
    }

    @JsonSetter("npi")
    public void setNpi(String npi) {
        this.npi = npi;
        this.npiProvided = true;
    }

    @JsonSetter("phone")
    public void setPhone(String phone) {
        this.phone = phone;
        this.phoneProvided = true;
    }

    @JsonSetter("fax")
    public void setFax(String fax) {
        this.fax = fax;
        this.faxProvided = true;
    }
}

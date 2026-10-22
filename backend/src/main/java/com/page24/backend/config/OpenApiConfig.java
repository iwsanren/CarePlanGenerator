package com.page24.backend.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata. The docs endpoints are disabled unless springdoc.api-docs.enabled=true
 * (see the "docs" profile), so this class has no effect on a default run.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "CarePlan Generator API",
                version = "v1",
                description = "Orders, patients, providers, multi-source intake and CSV export. "
                        + "All /api/v1 endpoints need a Keycloak access token with the ASSISTANT or "
                        + "PHARMACIST client role; click Authorize and paste the token only (no 'Bearer ' prefix)."),
        security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
@SecurityScheme(
        name = OpenApiConfig.BEARER_AUTH,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "Keycloak access token (not the ID token) from the careplan realm.")
public class OpenApiConfig {
    public static final String BEARER_AUTH = "bearerAuth";
}

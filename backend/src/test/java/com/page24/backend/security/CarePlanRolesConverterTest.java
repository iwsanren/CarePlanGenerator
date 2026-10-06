package com.page24.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class CarePlanRolesConverterTest {
    private final CarePlanRolesConverter converter = new CarePlanRolesConverter();
    private Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("test").header("alg", "RS256").claim("sub", "employee").claims(c -> c.putAll(claims)).build();
    }

    @Test void mapsOnlyAllowlistedRolesFromApiClient() {
        var roles = converter.convert(jwt(Map.of("resource_access", Map.of("careplan-api",
                Map.of("roles", List.of("ASSISTANT", "PHARMACIST", "ADMIN", "MONITOR"))))));
        assertThat(roles).extracting(Object::toString).containsExactly("ROLE_ASSISTANT", "ROLE_PHARMACIST");
    }

    @Test void realmRolesAndOtherClientRolesNeverGrantApiAccess() {
        assertThat(converter.convert(jwt(Map.of("realm_access", Map.of("roles", List.of("PHARMACIST")),
                "resource_access", Map.of("other-client", Map.of("roles", List.of("PHARMACIST"))))))).isEmpty();
    }

    @Test void missingOrMalformedRolesAreSafe() {
        for (Map<String, Object> claims : List.of(Map.<String,Object>of(), Map.<String,Object>of("resource_access", "invalid"),
                Map.<String,Object>of("resource_access", Map.of("careplan-api", Map.of("roles", "PHARMACIST")))))
            assertThat(converter.convert(jwt(claims))).isEmpty();
    }
}

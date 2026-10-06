package com.page24.backend.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Only roles for our API client can grant clinical application access. */
public class CarePlanRolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object access = jwt.getClaim("resource_access");
        if (!(access instanceof Map<?, ?> clients)
                || !(clients.get("careplan-api") instanceof Map<?, ?> api)
                || !(api.get("roles") instanceof Collection<?> roles)) return List.of();
        Set<String> allowed = Set.of("ASSISTANT", "PHARMACIST");
        return roles.stream().filter(String.class::isInstance).map(String.class::cast)
                .filter(allowed::contains).distinct()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
    }
}

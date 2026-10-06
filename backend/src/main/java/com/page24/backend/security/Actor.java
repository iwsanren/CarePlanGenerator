package com.page24.backend.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Identity always comes from the verified security context, never request data. */
public final class Actor {
    private Actor() {}

    public static String subject() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())
                ? auth.getName() : "system";
    }
}

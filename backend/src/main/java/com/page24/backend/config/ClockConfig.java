package com.page24.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The application clock, so that "today" can be fixed in tests. UTC because the dashboard cuts days at UTC
 * midnight (the stored created_at values are read as UTC everywhere in the API).
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}

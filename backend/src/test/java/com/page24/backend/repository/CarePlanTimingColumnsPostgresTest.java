package com.page24.backend.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "empty database" half of the V3 migration, run like production: on a brand-new PostgreSQL Flyway goes
 * first (V3 finds no care_plans table and does nothing), then Hibernate (ddl-auto=update from the shipped
 * application.properties) creates care_plans from the CarePlan entity, including the three timing columns.
 *
 * It proves that the entity mapping and V3 describe the same columns: the expected list below is identical to
 * the one asserted for the migrated legacy table in {@link FlywayMigrationPostgresTest}. Skipped without Docker.
 */
@SpringBootTest(classes = CarePlanTimingColumnsPostgresTest.TestApplication.class)
@Testcontainers(disabledWithoutDocker = true)
class CarePlanTimingColumnsPostgresTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("on an empty database Hibernate creates care_plans with the three timing columns, nullable, no default")
    void hibernateCreatesTheTimingColumnsAfterFlywayDidNothing() {
        // V3 was recorded as successful although care_plans did not exist when it ran
        assertThat(jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true AND version IS NOT NULL ORDER BY installed_rank",
                String.class))
                .containsExactly("1", "2", "3", "4");

        List<String> columns = jdbcTemplate.queryForList("""
                SELECT column_name || ' ' || data_type || ' ' || is_nullable || ' ' || coalesce(column_default, 'null')
                FROM information_schema.columns
                WHERE table_name = 'care_plans' AND column_name IN ('started_at', 'finished_at', 'attempt_count')
                ORDER BY column_name""", String.class);

        assertThat(columns).containsExactly(
                "attempt_count integer YES null",
                "finished_at timestamp without time zone YES null",
                "started_at timestamp without time zone YES null");
    }

    /** Datasource + Flyway + JPA only: the same auto-configurations, in the same order, as the real application. */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            TransactionAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class
    })
    @EntityScan("com.page24.backend.entity")
    static class TestApplication {
    }
}

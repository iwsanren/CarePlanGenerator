package com.page24.backend.repository;

import com.page24.backend.entity.Order;
import com.page24.backend.entity.Patient;
import com.page24.backend.entity.Provider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JPA persistence tests on a real PostgreSQL, wired like production:
 * Flyway runs first (V1), then Hibernate (ddl-auto=update) creates the remaining tables.
 *
 * It reuses the "test" profile (no Redis, no worker, LLM mock) but replaces the H2 settings
 * with the container's datasource and turns Flyway back on. Skipped when Docker is unavailable.
 */
@DataJpaTest(properties = "spring.test.database.replace=none") // do not swap our PostgreSQL for an embedded DB
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class OrderPersistencePostgresTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    // The container's random port is only known at runtime, so the datasource properties are registered dynamically.
    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private ProviderRepository providerRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Flyway V1 is applied, and Hibernate then creates orders with the audit columns")
    void flywayAndHibernateWorkTogether() {
        // V1 recorded as successful
        List<String> applied = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true AND version IS NOT NULL", String.class);
        assertThat(applied).contains("1");

        // audit_events comes from the Flyway migration
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'audit_events'", Integer.class))
                .isEqualTo(1);

        // orders comes from Hibernate and carries the audit columns declared on the Order entity
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'orders'", String.class);
        assertThat(columns).contains("created_by_subject", "updated_by_subject");
    }

    @Test
    @DisplayName("an order with its element collections is saved and reloaded in order")
    void savesAndReloadsOrder() {
        // Given: a patient and provider (orders reference both), then an order with two lists
        Patient patient = patientRepository.save(patient("123456"));
        Provider provider = providerRepository.save(provider("0987654321"));
        Order order = new Order();
        order.setPatient(patient);
        order.setProvider(provider);
        order.setMedicationName("Gamunex-C");
        order.setPrimaryDiagnosis("G70.00");
        order.setAdditionalDiagnoses(List.of("E11.9", "I10"));
        order.setMedicationHistory(List.of("Metformin 500mg", "Aspirin 81mg"));
        Long id = orderRepository.save(order).getId();

        // When: flush to the database and clear the persistence context so the next read really hits PostgreSQL
        entityManager.flush();
        entityManager.clear();
        Order reloaded = orderRepository.findById(id).orElseThrow();

        // Then: values and the order of both lists survive the round trip, and @PrePersist defaults were applied
        assertThat(reloaded.getMedicationName()).isEqualTo("Gamunex-C");
        assertThat(reloaded.getAdditionalDiagnoses()).containsExactly("E11.9", "I10");
        assertThat(reloaded.getMedicationHistory()).containsExactly("Metformin 500mg", "Aspirin 81mg");
        assertThat(reloaded.getCreatedBySubject()).isEqualTo("legacy");
        assertThat(reloaded.getUpdatedBySubject()).isEqualTo("legacy");
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("PostgreSQL rejects a second patient with the same MRN")
    void rejectsDuplicateMrn() {
        patientRepository.saveAndFlush(patient("654321"));

        assertThatThrownBy(() -> patientRepository.saveAndFlush(patient("654321")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Patient patient(String mrn) {
        Patient patient = new Patient();
        patient.setFirstName("Jane");
        patient.setLastName("Smith");
        patient.setMrn(mrn);
        patient.setDateOfBirth(LocalDate.of(1985, 3, 22));
        return patient;
    }

    private Provider provider(String npi) {
        Provider provider = new Provider();
        provider.setName("Dr. Emily Johnson");
        provider.setNpi(npi);
        provider.setCreatedAt(LocalDateTime.now());
        provider.setUpdatedAt(LocalDateTime.now());
        return provider;
    }
}

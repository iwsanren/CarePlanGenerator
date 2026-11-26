package com.page24.backend.config;

import com.page24.backend.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * kafka mode with no broker at all: topic creation fails and is logged, the application still starts. Nothing
 * listens on port 1; the short admin timeouts only keep the test quick. The outbox needs a real PostgreSQL, so this
 * needs Docker; skipped without it (CI fails if a *PostgresTest is skipped).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "careplan.queue.type=kafka",
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.admin.properties[request.timeout.ms]=2000",
        "spring.kafka.admin.properties[default.api.timeout.ms]=3000",
        "spring.main.lazy-initialization=false",
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QueueTypeKafkaBrokerDownPostgresTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private OrderService orderService;

    @Test
    @DisplayName("an unreachable broker does not stop the application from starting")
    void applicationStartsWithoutABroker() {
        assertThat(orderService).isNotNull();
    }
}

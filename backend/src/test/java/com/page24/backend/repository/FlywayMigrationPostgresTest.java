package com.page24.backend.repository;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the real Flyway migrations (src/main/resources/db/migration) against a real PostgreSQL
 * started by Testcontainers.
 *
 * The other integration tests use in-memory H2 with Flyway switched off, so the SQL migration
 * itself is never executed there. This test closes that gap. It needs Docker; when Docker is
 * not available the test is skipped instead of failing (GitHub Actions runners have Docker).
 *
 * Flyway is configured exactly like application.properties: baselineOnMigrate with baseline version 0.
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationPostgresTest {

    // One container for the whole class (static), shared by all tests; started and stopped by Testcontainers.
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    // Tables created by V2__spring_batch_schema.sql (Spring Batch 6 job repository), alphabetically
    private static final List<String> EXPECTED_BATCH_TABLES = List.of(
            "batch_job_execution",
            "batch_job_execution_context",
            "batch_job_execution_params",
            "batch_job_instance",
            "batch_step_execution",
            "batch_step_execution_context");

    @BeforeEach
    void resetDatabase() throws SQLException {
        // Every test starts from a completely empty schema.
        execute("DROP SCHEMA public CASCADE", "CREATE SCHEMA public");
    }

    @Test
    @DisplayName("on an empty database V1 creates audit_events with its columns and index")
    void migratesEmptyDatabase() throws SQLException {
        // When
        MigrateResult result = flyway().migrate();

        // Then: V1 and V2 ran and both are recorded as successful
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(2);
        assertThat(query("SELECT version || ':' || success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank"))
                .containsExactly("1:true", "2:true");

        // audit_events has the expected columns, types and nullability
        assertThat(query("""
                SELECT column_name || ' ' || data_type || ' ' || is_nullable
                FROM information_schema.columns
                WHERE table_name = 'audit_events'
                ORDER BY ordinal_position"""))
                .containsExactly(
                        "id bigint NO",
                        "actor_subject character varying NO",
                        "action character varying NO",
                        "target_id bigint YES",
                        "occurred_at timestamp with time zone NO");

        // the lookup index exists
        assertThat(query("SELECT indexname FROM pg_indexes WHERE tablename = 'audit_events' AND indexname = 'audit_events_actor_time'"))
                .containsExactly("audit_events_actor_time");

        // "orders" does not exist yet (Hibernate creates it later), and the ALTER ... IF EXISTS is skipped without error
        assertThat(query("SELECT table_name FROM information_schema.tables WHERE table_name = 'orders'")).isEmpty();
    }

    @Test
    @DisplayName("on a legacy database V1 adds the audit columns and backfills existing orders with 'legacy'")
    void migratesLegacyDatabaseWithExistingOrders() throws SQLException {
        // Given: an older database that already has an orders table with data and no Flyway history
        execute("CREATE TABLE orders (id BIGSERIAL PRIMARY KEY, medication_name VARCHAR(255))",
                "INSERT INTO orders (medication_name) VALUES ('Gamunex-C'), ('Metformin')");

        // When
        MigrateResult result = flyway().migrate();

        // Then: baselineOnMigrate lets Flyway adopt the existing schema, then V1 is applied on top
        assertThat(result.success).isTrue();
        assertThat(query("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'orders' AND column_name IN ('created_by_subject', 'updated_by_subject')
                ORDER BY column_name"""))
                .containsExactly("created_by_subject", "updated_by_subject");
        // pre-existing rows get the column default
        assertThat(query("SELECT created_by_subject || '/' || updated_by_subject FROM orders"))
                .containsExactly("legacy/legacy", "legacy/legacy");
        // history: the baseline marker (0) for the adopted schema, then V1 and V2 applied on top of it
        assertThat(query("SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank"))
                .containsExactly("0", "1", "2");
        assertThat(batchTables()).containsExactlyElementsOf(EXPECTED_BATCH_TABLES);
    }

    @Test
    @DisplayName("V2 creates the Spring Batch tables, sequences and constraints on an empty database")
    void v2CreatesSpringBatchSchema() throws SQLException {
        flyway().migrate();

        // PostgreSQL folds the unquoted names of the script to lower case
        assertThat(batchTables()).containsExactlyElementsOf(EXPECTED_BATCH_TABLES);
        assertThat(query("SELECT sequencename FROM pg_sequences WHERE sequencename LIKE 'batch\\_%' ORDER BY sequencename"))
                .containsExactly("batch_job_execution_seq", "batch_job_instance_seq", "batch_step_execution_seq");
        // the one-instance-per-(name, key) rule that makes job restarts and duplicate launches detectable
        assertThat(query("SELECT conname FROM pg_constraint WHERE conname = 'job_inst_un'")).containsExactly("job_inst_un");
        // the metadata tables start empty
        assertThat(query("SELECT count(*)::text FROM batch_job_instance")).containsExactly("0");
    }

    @Test
    @DisplayName("V2 applies on top of a database where only V1 was applied")
    void v2AppliesOnDatabaseWithV1AlreadyApplied() throws SQLException {
        // Given: a database migrated up to V1 only, like an environment deployed before Spring Batch existed
        MigrateResult v1Only = flyway(configure -> configure.target("1")).migrate();
        assertThat(v1Only.migrationsExecuted).isEqualTo(1);
        assertThat(batchTables()).isEmpty();
        execute("INSERT INTO audit_events (actor_subject, action, occurred_at) VALUES ('user-1', 'LOGIN', now())");

        // When
        MigrateResult result = flyway().migrate();

        // Then: only V2 ran, V1 data is untouched
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(batchTables()).containsExactlyElementsOf(EXPECTED_BATCH_TABLES);
        assertThat(query("SELECT actor_subject FROM audit_events")).containsExactly("user-1");
    }

    @Test
    @DisplayName("V2 does not fail if the Batch tables already exist")
    void v2ToleratesExistingBatchTables() throws SQLException {
        // Given: the tables were created by hand before Flyway ran V2
        flyway(configure -> configure.target("1")).migrate();
        execute("CREATE TABLE batch_job_instance (job_instance_id BIGINT PRIMARY KEY)");

        // When / Then: IF NOT EXISTS keeps the existing table and creates the rest
        MigrateResult result = flyway().migrate();
        assertThat(result.success).isTrue();
        assertThat(batchTables()).containsExactlyElementsOf(EXPECTED_BATCH_TABLES);
    }

    @Test
    @DisplayName("running the migrations a second time changes nothing")
    void migrationIsIdempotent() throws SQLException {
        flyway().migrate();

        MigrateResult second = flyway().migrate();

        assertThat(second.success).isTrue();
        assertThat(second.migrationsExecuted).isZero();
    }

    @Test
    @DisplayName("audit_events accepts a complete row and rejects one without an actor")
    void auditEventsEnforcesNotNull() throws SQLException {
        flyway().migrate();

        execute("INSERT INTO audit_events (actor_subject, action, target_id, occurred_at) VALUES ('user-1', 'LOGIN', NULL, now())");
        assertThat(query("SELECT actor_subject || ':' || action FROM audit_events")).containsExactly("user-1:LOGIN");

        assertThatThrownBy(() -> execute(
                "INSERT INTO audit_events (actor_subject, action, occurred_at) VALUES (NULL, 'LOGIN', now())"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("actor_subject");
    }

    private Flyway flyway() {
        return flyway(configure -> { });
    }

    private Flyway flyway(Consumer<FluentConfiguration> customizer) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0");
        customizer.accept(configuration);
        return configuration.load();
    }

    private List<String> batchTables() throws SQLException {
        return query("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name LIKE 'batch\\_%'
                ORDER BY table_name""");
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void execute(String... statements) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private List<String> query(String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                rows.add(resultSet.getString(1));
            }
        }
        return rows;
    }
}

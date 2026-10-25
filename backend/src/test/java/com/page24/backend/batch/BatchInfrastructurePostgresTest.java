package com.page24.backend.batch;

import com.page24.backend.config.AsyncConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.batch.autoconfigure.BatchAutoConfiguration;
import org.springframework.boot.batch.autoconfigure.BatchJobLauncherAutoConfiguration;
import org.springframework.boot.batch.jdbc.autoconfigure.BatchJdbcAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spring Batch infrastructure on a real PostgreSQL, configured exactly like production:
 * Flyway creates the BATCH_* tables (V2), Hibernate then creates the entity tables, and the JDBC job
 * repository runs on the JPA transaction manager. The shipped application.properties is used as is
 * (initialize-schema=never, job.enabled=false); only the datasource comes from the container.
 *
 * The context is deliberately slim (no web, security, Redis or LLM beans). It only contains a trivial
 * test-only job; the real import job is added in later steps. Skipped when Docker is unavailable.
 */
@SpringBootTest(
        classes = BatchInfrastructurePostgresTest.TestApplication.class,
        properties = {
                "spring.jpa.show-sql=false",
                "logging.level.org.hibernate.SQL=INFO"
        })
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BatchInfrastructurePostgresTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JobOperator jobOperator;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private Job trivialJob;

    @Autowired
    private AtomicInteger taskletRuns;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @Order(1)
    @DisplayName("the context starts and runs no job at startup (spring.batch.job.enabled=false)")
    void nothingRunsAtStartup() {
        // A Job bean exists; with job.enabled=true Boot would have executed it while the context started.
        assertThat(taskletRuns).hasValue(0);
        assertThat(count("batch_job_instance")).isZero();
        assertThat(count("batch_job_execution")).isZero();
    }

    @Test
    @Order(2)
    @DisplayName("Flyway created the BATCH_* tables and Batch did not create anything itself")
    void batchTablesComeFromFlyway() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name LIKE 'batch\\_%'
                ORDER BY table_name""", String.class))
                .containsExactly(
                        "batch_job_execution",
                        "batch_job_execution_context",
                        "batch_job_execution_params",
                        "batch_job_instance",
                        "batch_step_execution",
                        "batch_step_execution_context");
        assertThat(jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true AND version IS NOT NULL", String.class))
                .contains("1", "2");
    }

    @Test
    @Order(3)
    @DisplayName("the job repository uses the JPA transaction manager of the application")
    void jobRepositoryUsesJpaTransactionManager() {
        assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
    }

    @Test
    @Order(4)
    @DisplayName("a trivial job launched with JobOperator completes and its execution is persisted")
    void launchesTrivialJobAndPersistsMetadata() throws Exception {
        // Given
        JobParameters parameters = new JobParametersBuilder().addString("run", "first").toJobParameters();

        // When: JobOperator is the Batch 6 entry point (JobLauncher is deprecated in its favour)
        JobExecution execution = jobOperator.start(trivialJob, parameters);

        // Then: the job finished before start() returned, i.e. it ran synchronously on the calling thread.
        // That also proves the application's own carePlanTaskExecutor is not picked up as the Batch executor.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
        assertThat(taskletRuns).hasValue(1);

        // And: the rows are really in PostgreSQL
        assertThat(jdbcTemplate.queryForList("""
                SELECT i.job_name || ':' || e.status || ':' || e.exit_code
                FROM batch_job_execution e JOIN batch_job_instance i ON i.job_instance_id = e.job_instance_id
                WHERE e.job_execution_id = ?""", String.class, execution.getId()))
                .containsExactly("trivialJob:COMPLETED:COMPLETED");
        assertThat(jdbcTemplate.queryForList(
                "SELECT step_name || ':' || status FROM batch_step_execution WHERE job_execution_id = ?",
                String.class, execution.getId()))
                .containsExactly("trivialStep:COMPLETED");
        assertThat(jdbcTemplate.queryForList(
                "SELECT parameter_name || '=' || parameter_value FROM batch_job_execution_params WHERE job_execution_id = ?",
                String.class, execution.getId()))
                .containsExactly("run=first");

        // And: reading it back through the repository gives the same state (fresh read, nothing cached in memory)
        JobExecution reloaded = jobRepository.getJobExecution(execution.getId());
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(reloaded.getStepExecutions()).extracting("stepName").containsExactly("trivialStep");
    }

    @Test
    @Order(5)
    @DisplayName("launching an already completed job instance again is rejected")
    void completedInstanceCannotBeRelaunched() {
        // Same job name and identifying parameters as the previous test = same job instance
        JobParameters sameParameters = new JobParametersBuilder().addString("run", "first").toJobParameters();

        assertThatThrownBy(() -> jobOperator.start(trivialJob, sameParameters))
                .isInstanceOf(JobInstanceAlreadyCompleteException.class);
        assertThat(taskletRuns).hasValue(1);
    }

    private long count(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    /**
     * Minimal application context: datasource + Flyway + JPA + Batch JDBC auto-configuration (the same
     * auto-configurations the real app gets), the application's AsyncConfig (to prove its executor does
     * not leak into Batch) and one trivial job.
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            TransactionAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            BatchJdbcAutoConfiguration.class,
            BatchAutoConfiguration.class,
            BatchJobLauncherAutoConfiguration.class
    })
    @EntityScan("com.page24.backend.entity")
    @Import({BatchConfig.class, AsyncConfig.class})
    static class TestApplication {

        @Bean
        AtomicInteger taskletRuns() {
            return new AtomicInteger();
        }

        @Bean
        Job trivialJob(JobRepository jobRepository, PlatformTransactionManager transactionManager, AtomicInteger taskletRuns) {
            Step step = new StepBuilder("trivialStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        taskletRuns.incrementAndGet();
                        return RepeatStatus.FINISHED;
                    }, transactionManager)
                    .build();
            return new JobBuilder("trivialJob", jobRepository).start(step).build();
        }
    }
}

package com.page24.backend.aws.lambda;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Lambda profile must not get any Spring Batch infrastructure: no job repository, no job operator,
 * no BatchConfig. The context is started the same way as LambdaSpringContext does (LambdaApplication,
 * profile "lambda", no web), only with an in-memory H2 database and without Flyway, because the real
 * RDS database is not available in a unit test.
 */
class LambdaBatchExclusionTest {

    private ConfigurableApplicationContext context;
    private String previousRegion;

    @BeforeEach
    void startLambdaContext() {
        // AwsSqsConfig calls SqsClient.create(), which needs a region (the Lambda runtime provides AWS_REGION).
        previousRegion = System.getProperty("aws.region");
        System.setProperty("aws.region", "us-east-1");

        context = new SpringApplicationBuilder(LambdaApplication.class)
                .profiles("lambda")
                .web(WebApplicationType.NONE)
                // Command-line arguments: they beat application.properties, which points at the local PostgreSQL.
                .run(
                        "--spring.datasource.url=jdbc:h2:mem:lambda_batch_exclusion;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "--spring.datasource.driver-class-name=org.h2.Driver",
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--spring.jpa.hibernate.ddl-auto=create-drop",
                        "--spring.jpa.show-sql=false",
                        "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                        "--spring.flyway.enabled=false",
                        "--spring.main.lazy-initialization=true");
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
        if (previousRegion == null) {
            System.clearProperty("aws.region");
        } else {
            System.setProperty("aws.region", previousRegion);
        }
    }

    @Test
    @DisplayName("the lambda profile starts without any Spring Batch bean")
    void lambdaProfileHasNoBatchBeans() {
        assertThat(context.getBeanNamesForType(JobRepository.class)).isEmpty();
        assertThat(context.getBeanNamesForType(JobOperator.class)).isEmpty();
        assertThat(context.getBeanNamesForType(com.page24.backend.batch.BatchConfig.class)).isEmpty();
        // the Lambda-specific wiring is still there
        assertThat(context.getBeanNamesForType(SqsCarePlanQueue.class)).isNotEmpty();
    }
}

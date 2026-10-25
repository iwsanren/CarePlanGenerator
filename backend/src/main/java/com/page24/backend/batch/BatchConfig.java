package com.page24.backend.batch;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Anchor for the Spring Batch jobs of the local application (bulk order import, added in later steps).
 *
 * The infrastructure itself comes from Spring Boot's auto-configuration, so this class deliberately
 * does NOT use {@code @EnableBatchProcessing} (that would make Boot back off and ignore the
 * {@code spring.batch.*} properties):
 * <ul>
 *   <li>job metadata is stored in PostgreSQL through the JDBC job repository; the BATCH_* tables
 *       are created by Flyway migration V2, not by Batch ({@code spring.batch.jdbc.initialize-schema=never});</li>
 *   <li>nothing runs at startup ({@code spring.batch.job.enabled=false}); jobs are started
 *       explicitly through {@code JobOperator};</li>
 *   <li>under the "lambda" profile this package is not active and Boot's Batch auto-configuration
 *       is excluded on {@code LambdaApplication}.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@Profile("!lambda")
public class BatchConfig {
}

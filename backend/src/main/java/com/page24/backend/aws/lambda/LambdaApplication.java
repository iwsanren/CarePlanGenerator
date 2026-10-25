package com.page24.backend.aws.lambda;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.batch.autoconfigure.BatchAutoConfiguration;
import org.springframework.boot.batch.autoconfigure.BatchJobLauncherAutoConfiguration;
import org.springframework.boot.batch.autoconfigure.observation.BatchObservationAutoConfiguration;
import org.springframework.boot.batch.jdbc.autoconfigure.BatchJdbcAutoConfiguration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Lambda-specific Spring entrypoint.
 * It scans the same services/repositories as the local app, but does not enable
 * local-only scheduling such as CarePlanWorker polling Redis.
 * Spring Batch (bulk order import) is a local-application feature, so its auto-configuration is excluded here:
 * no job repository is created and nothing touches the BATCH_* tables.
 */
@SpringBootApplication(scanBasePackages = "com.page24.backend", exclude = {
        BatchJdbcAutoConfiguration.class,
        BatchAutoConfiguration.class,
        BatchJobLauncherAutoConfiguration.class,
        BatchObservationAutoConfiguration.class
})
@EnableRetry
public class LambdaApplication {
}

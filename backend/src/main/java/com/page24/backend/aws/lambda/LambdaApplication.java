package com.page24.backend.aws.lambda;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Lambda-specific Spring entrypoint.
 * It scans the same services/repositories as the local app, but does not enable
 * local-only scheduling such as CarePlanWorker polling Redis.
 */
@SpringBootApplication(scanBasePackages = "com.page24.backend")
public class LambdaApplication {
}

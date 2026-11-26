package com.page24.backend.outbox;

import com.page24.backend.service.LogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** The rate limit of the outage log, with a clock the test moves by hand. */
class SendFailureLogTest {

    private final AtomicLong nanos = new AtomicLong();
    private final SendFailureLog failureLog = new SendFailureLog(Duration.ofSeconds(60), nanos::get);

    @Test
    @DisplayName("first failure, then one summary per minute, then one line on recovery")
    void warnsOnceThenSummarisesThenReportsRecovery() {
        try (LogCapture logs = new LogCapture(SendFailureLog.class)) {
            // an outage of three minutes with a failure every second
            for (int second = 0; second < 180; second++) {
                nanos.set(Duration.ofSeconds(second).toNanos());
                failureLog.failed("TimeoutException");
            }
            failureLog.succeeded();
            failureLog.succeeded(); // staying healthy is not worth a line

            assertThat(logs.text().lines()).containsExactly(
                    "Outbox: sending failed (error=TimeoutException). The rows stay in the outbox and are retried; "
                            + "further failures are only summarised.",
                    "Outbox: sending is still failing (error=TimeoutException, 60 more failed attempts since the "
                            + "last report)",
                    "Outbox: sending is still failing (error=TimeoutException, 60 more failed attempts since the "
                            + "last report)",
                    "Outbox: sending works again");
        }
    }

    @Test
    @DisplayName("nothing is logged while sending simply works")
    void silentWhenHealthy() {
        try (LogCapture logs = new LogCapture(SendFailureLog.class)) {
            failureLog.succeeded();

            assertThat(logs.text()).isEmpty();
        }
    }
}

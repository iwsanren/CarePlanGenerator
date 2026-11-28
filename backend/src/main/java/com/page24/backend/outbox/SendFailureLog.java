package com.page24.backend.outbox;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Keeps an outage from filling the log: the sender runs every second, so it must not log every failure. One warning
 * when sending starts to fail, then at most one summary per interval, and one line when it works again. Only
 * exception class names are logged, never messages or stack traces.
 */
@Slf4j
class SendFailureLog {

    private final long intervalNanos;
    private final LongSupplier nanoClock;

    private boolean failing;
    private long lastReport;
    private long failuresSinceReport;

    SendFailureLog(Duration interval, LongSupplier nanoClock) {
        this.intervalNanos = interval.toNanos();
        this.nanoClock = nanoClock;
    }

    synchronized void failed(String errorName) {
        long now = nanoClock.getAsLong();
        if (!failing) {
            failing = true;
            lastReport = now;
            failuresSinceReport = 0;
            log.warn("Outbox: sending failed (error={}). The rows stay in the outbox and are retried; "
                    + "further failures are only summarised.", errorName);
            return;
        }
        failuresSinceReport++;
        if (now - lastReport >= intervalNanos) {
            log.warn("Outbox: sending is still failing (error={}, {} more failed attempts since the last report)",
                    errorName, failuresSinceReport);
            lastReport = now;
            failuresSinceReport = 0;
        }
    }

    synchronized void succeeded() {
        if (failing) {
            failing = false;
            log.info("Outbox: sending works again");
        }
    }
}

package com.page24.backend.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The clean-up's batching and the wake-up listener's hand-over, with mocks. */
class OutboxCleanupAndWakeUpTest {

    @Test
    @DisplayName("clean-up deletes batch by batch until a batch comes back short")
    void cleanupRepeatsWhileBatchesAreFull() {
        OutboxRepository outbox = mock(OutboxRepository.class);
        Duration retention = Duration.ofDays(7);
        when(outbox.deleteSentOlderThan(retention, 1000)).thenReturn(1000, 1000, 3);

        new OutboxCleanup(outbox, retention).removeOldSentRows();

        verify(outbox, times(3)).deleteSentOlderThan(retention, 1000);
    }

    @Test
    @DisplayName("wake-ups run on another thread, never block the caller, and pile up to at most one waiting run")
    void wakeUpsNeverBlockTheCallerAndAreCoalesced() throws InterruptedException {
        OutboxRelay relay = mock(OutboxRelay.class);
        CountDownLatch firstRunStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch secondRunDone = new CountDownLatch(2);
        doAnswer(invocation -> {
            runs.incrementAndGet();
            firstRunStarted.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            secondRunDone.countDown();
            return null;
        }).when(relay).drain();
        OutboxWakeUpListener listener = new OutboxWakeUpListener(relay);

        // the first wake-up keeps the thread busy; many more arrive while it is
        listener.onOutboxWritten(new OutboxWritten());
        assertThat(firstRunStarted.await(10, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 100; i++) {
            listener.onOutboxWritten(new OutboxWritten()); // returns at once, never throws
        }
        release.countDown();

        assertThat(secondRunDone.await(10, TimeUnit.SECONDS)).isTrue();
        listener.shutDown();
        assertThat(runs.get()).isEqualTo(2);
        listener.onOutboxWritten(new OutboxWritten()); // after shutdown a wake-up is dropped, not an error
    }
}

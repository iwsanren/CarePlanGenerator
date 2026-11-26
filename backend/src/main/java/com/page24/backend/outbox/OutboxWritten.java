package com.page24.backend.outbox;

/**
 * Published inside the transaction that wrote an outbox row. Listeners act after the commit. It carries nothing: it
 * only means "there is something to send", and the sender reads what from the table.
 */
public record OutboxWritten() {
}

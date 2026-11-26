package com.page24.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "care_plans")
@Data
public class CarePlan {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /**
     * A short, safe message shown to the client when generation fails.
     * Technical exception details remain in server logs and must not be exposed.
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** True when this content was manually uploaded rather than LLM-generated.
     *  Field is named `uploaded` (not `isUploaded`) so Lombok generates the
     *  unambiguous isUploaded()/setUploaded() accessor pair.
     *  `columnDefinition` gives the column a DB-level default so ddl-auto=update
     *  backfills existing rows instead of leaving them NULL, which would crash
     *  Hibernate when it tries to bind NULL into a primitive boolean field. */
    @Column(name = "is_uploaded", nullable = false, columnDefinition = "boolean default false")
    private boolean uploaded = false;

    /**
     * When a worker (or the Lambda handler) picked this plan up and moved it to PROCESSING.
     * NULL means "no data": rows from before this column existed, manual uploads, and plans reset for
     * regeneration. Statistics must treat NULL as missing, never as 0.
     */
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    /**
     * When generation reached COMPLETED or FAILED. finished_at - started_at is the wall-clock time of one
     * run and includes the back-off sleeps between retries (2 s and 4 s); it excludes the time spent
     * waiting in the queue (that is started_at - created_at).
     */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /**
     * How many LLM attempts the last run used (1 to 3). Boxed Integer on purpose: a primitive int could not
     * represent NULL ("no data") and would crash or turn into 0 when Hibernate reads a NULL column.
     */
    @Column(name = "attempt_count")
    private Integer attemptCount;

    /*
     * Claim columns of the Kafka consumer. updatable = false: Hibernate leaves them out of every entity UPDATE, so a
     * whole-entity save (regenerate, upload) can never write back an old value over a newer claim. They are only
     * changed by the conditional statements of CarePlanClaimRepository.
     */

    /** Goes up by one on every claim; a generation result is only accepted with the current number. */
    @Column(name = "claim_token", nullable = false, updatable = false, columnDefinition = "integer default 0")
    private int claimToken = 0;

    /** The eventId of the message that made the latest claim. */
    @Column(name = "claimed_event_id", updatable = false)
    private UUID claimedEventId;

    /** Until when the latest claim holds, by the database clock; NULL when nothing is in flight. */
    @Column(name = "lease_expires_at", updatable = false)
    private LocalDateTime leaseExpiresAt;

    /** How often the current generation request was taken over from an earlier claim. */
    @Column(name = "reclaim_count", nullable = false, updatable = false, columnDefinition = "integer default 0")
    private int reclaimCount = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Starts a new generation run: PROCESSING, the start time is now, and any result of an earlier run is cleared. */
    public void markProcessing(LocalDateTime now) {
        status = Status.PROCESSING;
        startedAt = now;
        finishedAt = null;
        attemptCount = null;
    }

    /** Records the end of a run (COMPLETED or FAILED): when it ended and how many LLM attempts it took. */
    public void markFinished(LocalDateTime now, int attempts) {
        finishedAt = now;
        attemptCount = attempts;
    }

    /** Forgets all LLM timing data: used when a plan is regenerated or replaced by a manual upload. */
    public void clearGenerationTiming() {
        startedAt = null;
        finishedAt = null;
        attemptCount = null;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum Status {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }
}


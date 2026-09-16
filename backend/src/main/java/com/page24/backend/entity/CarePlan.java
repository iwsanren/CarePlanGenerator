package com.page24.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

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

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

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


package com.page24.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Entity
@Table(name = "audit_events")
@Data
public class AuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String actorSubject;
    @Column(nullable = false)
    private String action;
    private Long targetId;
    @Column(nullable = false)
    private Instant occurredAt;
}

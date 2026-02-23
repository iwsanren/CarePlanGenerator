package com.page24.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders")
@Data
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @ManyToOne
    @JoinColumn(name = "provider_id", nullable = false)
    private Provider provider;

    @Column(name = "medication_name", nullable = false)
    private String medicationName;

    @Column(name = "primary_diagnosis")
    private String primaryDiagnosis;

    @Column(name = "additional_diagnosis", columnDefinition = "TEXT")
    private String additionalDiagnosis;

    @Column(name = "medication_history", columnDefinition = "TEXT")
    private String medicationHistory;

    @Column(name = "patient_records", columnDefinition = "TEXT")
    private String patientRecords;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}


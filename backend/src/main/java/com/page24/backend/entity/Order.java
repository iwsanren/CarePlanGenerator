package com.page24.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

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

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "order_additional_diagnoses",
            joinColumns = @JoinColumn(name = "order_id")
    )
    @OrderColumn(name = "diagnosis_order")
    @Column(name = "diagnosis_code")
    private List<String> additionalDiagnoses;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "order_medication_history",
            joinColumns = @JoinColumn(name = "order_id")
    )
    @OrderColumn(name = "entry_order")
    @Column(name = "medication_entry", length = 500)
    private List<String> medicationHistory;

    @Column(name = "patient_records", columnDefinition = "TEXT")
    private String patientRecords;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        // Keep an explicitly supplied timestamp for imports and reports; new
        // orders created by the application still receive the current time.
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}


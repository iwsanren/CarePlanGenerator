package com.page24.backend.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "patients")
@Data
public class Patient {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "mrn", unique = true, nullable = false, length = 6)
    private String mrn;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "sex", length = 20)
    private String sex;

    @Column(name = "weight_kg")
    private Double weightKg;

    @Column(name = "allergies", columnDefinition = "TEXT")
    private String allergies;

    @Column(name = "primary_diagnosis")
    private String primaryDiagnosis;

    @ElementCollection
    @CollectionTable(
            name = "patient_additional_diagnoses",
            joinColumns = @JoinColumn(name = "patient_id")
    )
    @OrderColumn(name = "diagnosis_order")
    @Column(name = "diagnosis_code")
    private List<String> additionalDiagnoses;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}


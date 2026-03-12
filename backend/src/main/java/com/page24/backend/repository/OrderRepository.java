package com.page24.backend.repository;

import com.page24.backend.entity.Order;
import com.page24.backend.entity.Patient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByPatient(Patient patient);

    boolean existsByPatientAndMedicationNameIgnoreCaseAndCreatedAtBetween(
            Patient patient,
            String medicationName,
            LocalDateTime start,
            LocalDateTime end
    );

    Optional<Order> findFirstByPatientAndMedicationNameIgnoreCaseOrderByCreatedAtDesc(
            Patient patient,
            String medicationName
    );
}


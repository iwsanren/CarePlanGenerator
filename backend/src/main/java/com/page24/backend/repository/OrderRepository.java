package com.page24.backend.repository;

import com.page24.backend.entity.Order;
import com.page24.backend.entity.Patient;
import com.page24.backend.entity.CarePlan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByPatient(Patient patient);

    @Query("""
            select o
            from Order o
            join CarePlan cp on cp.order = o
            where cp.status = :status
            """)
    Page<Order> findByCarePlanStatus(
            @Param("status") CarePlan.Status status,
            Pageable pageable
    );

    @Query("""
            select o
            from Order o
            join o.patient p
            where lower(p.firstName) like :patientNamePattern
               or lower(p.lastName) like :patientNamePattern
               or lower(concat(concat(p.firstName, ' '), p.lastName)) like :patientNamePattern
            """)
    Page<Order> findByPatientName(
            @Param("patientNamePattern") String patientNamePattern,
            Pageable pageable
    );

    @Query("""
            select o
            from Order o
            join o.patient p
            join CarePlan cp on cp.order = o
            where cp.status = :status
              and (
                lower(p.firstName) like :patientNamePattern
                or lower(p.lastName) like :patientNamePattern
                or lower(concat(concat(p.firstName, ' '), p.lastName)) like :patientNamePattern
              )
            """)
    Page<Order> findByCarePlanStatusAndPatientName(
            @Param("status") CarePlan.Status status,
            @Param("patientNamePattern") String patientNamePattern,
            Pageable pageable
    );

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


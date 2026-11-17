package com.page24.backend.repository;

import com.page24.backend.entity.Order;
import com.page24.backend.entity.Patient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

@Repository
/**
 * Orders. The paginated, filterable, sortable list is in the OrderSearchRepository fragment
 * (OrderSearchRepositoryImpl), which replaced the fixed findByFilters query.
 */
public interface OrderRepository extends JpaRepository<Order, Long>, OrderSearchRepository {
    List<Order> findByPatient(Patient patient);
    List<Order> findByPatientOrderByCreatedAtDesc(Patient patient);

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

    @Query("select o.id from Order o where o.provider.id = :providerId order by o.id")
    List<Long> findIdsByProviderId(@Param("providerId") Long providerId);
}


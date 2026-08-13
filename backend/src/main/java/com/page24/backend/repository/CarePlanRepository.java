package com.page24.backend.repository;

import com.page24.backend.entity.CarePlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;
import java.util.Collection;

@Repository
public interface CarePlanRepository extends JpaRepository<CarePlan, Long> {
    Optional<CarePlan> findByOrderId(Long orderId);
    List<CarePlan> findByOrderIn(List<com.page24.backend.entity.Order> orders);

    @Query("""
            select cp.order.id
            from CarePlan cp
            where cp.order.patient.id = :patientId
              and cp.status in :statuses
            order by cp.order.id
            """)
    List<Long> findOrderIdsByPatientIdAndStatusIn(
            @Param("patientId") Long patientId,
            @Param("statuses") Collection<CarePlan.Status> statuses
    );
}


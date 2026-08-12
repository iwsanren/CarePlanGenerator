package com.page24.backend.repository;

import com.page24.backend.entity.CarePlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface CarePlanRepository extends JpaRepository<CarePlan, Long> {
    Optional<CarePlan> findByOrderId(Long orderId);
    List<CarePlan> findByOrderIn(List<com.page24.backend.entity.Order> orders);
}


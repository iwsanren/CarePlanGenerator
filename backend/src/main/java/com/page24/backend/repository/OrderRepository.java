package com.page24.backend.repository;

import com.page24.backend.entity.Order;
import com.page24.backend.entity.Patient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByPatient(Patient patient);
}


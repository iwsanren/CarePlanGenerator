package com.page24.backend.repository;

import com.page24.backend.entity.MedicationHistory;
import com.page24.backend.entity.Patient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MedicationHistoryRepository extends JpaRepository<MedicationHistory, Long> {

    List<MedicationHistory> findByPatientOrderByCreatedAtAsc(Patient patient);

    void deleteByPatient(Patient patient);
}

package com.page24.backend.repository;

import com.page24.backend.entity.Patient;
import com.page24.backend.entity.PatientDiagnosis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PatientDiagnosisRepository extends JpaRepository<PatientDiagnosis, Long> {

    List<PatientDiagnosis> findByPatientOrderByCreatedAtAsc(Patient patient);

    void deleteByPatient(Patient patient);
}

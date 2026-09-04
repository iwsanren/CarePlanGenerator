package com.page24.backend.repository;

import com.page24.backend.entity.Patient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

@DataJpaTest
@ActiveProfiles("test")
class PatientRepositoryNullDobTest {

    @Autowired
    private PatientRepository patientRepository;

    @Test
    void whatHappensWhenDobIsNull() {
        Patient patient = new Patient();
        patient.setFirstName("Jane");
        patient.setLastName("Doe");
        patient.setMrn("123456");
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patientRepository.save(patient);

        var result = patientRepository
                .findFirstByFirstNameIgnoreCaseAndLastNameIgnoreCaseAndDateOfBirth(
                        "Jane", "Doe", null);

        System.out.println("RESULT = " + result);
    }
}
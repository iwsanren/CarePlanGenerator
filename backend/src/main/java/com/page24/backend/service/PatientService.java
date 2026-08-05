package com.page24.backend.service;

import com.page24.backend.dto.CreatePatientRequest;
import com.page24.backend.dto.PatientMapper;
import com.page24.backend.dto.PatientResponse;
import com.page24.backend.entity.Patient;
import com.page24.backend.exception.PatientDuplicateException;
import com.page24.backend.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class PatientService {

    private final PatientRepository patientRepository;
    private final PatientMapper patientMapper;

    @Transactional
    public PatientResponse createPatient(CreatePatientRequest request) {
        patientRepository.findFirstByFirstNameIgnoreCaseAndLastNameIgnoreCaseAndDateOfBirth(
                        request.getFirstName(),
                        request.getLastName(),
                        request.getDateOfBirth()
                )
                .ifPresent(existingPatient -> {
                    throw new PatientDuplicateException(
                            "A patient with the same name and date of birth already exists",
                            existingPatient.getId()
                    );
                });

        patientRepository.findByMrn(request.getMrn())
                .ifPresent(existingPatient -> {
                    throw new PatientDuplicateException(
                            "A patient with the same MRN already exists",
                            existingPatient.getId()
                    );
                });

        Patient patient = new Patient();
        patient.setFirstName(request.getFirstName());
        patient.setLastName(request.getLastName());
        patient.setMrn(request.getMrn());
        patient.setDateOfBirth(request.getDateOfBirth());
        patient.setSex(request.getSex());
        patient.setWeightKg(request.getWeightKg());
        patient.setAllergies(request.getAllergies());
        patient.setPrimaryDiagnosis(request.getPrimaryDiagnosis());
        patient.setAdditionalDiagnoses(
                request.getAdditionalDiagnoses() == null
                        ? new ArrayList<>()
                        : new ArrayList<>(request.getAdditionalDiagnoses())
        );

        Patient savedPatient = patientRepository.save(patient);
        return patientMapper.toResponse(savedPatient);
    }
}

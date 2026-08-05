package com.page24.backend.dto;

import com.page24.backend.entity.Patient;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;

@Component
public class PatientMapper {

    public PatientResponse toResponse(Patient patient) {
        PatientResponse response = new PatientResponse();
        response.setId(patient.getId());
        response.setFirstName(patient.getFirstName());
        response.setLastName(patient.getLastName());
        response.setMrn(patient.getMrn());
        response.setDateOfBirth(patient.getDateOfBirth());
        response.setSex(patient.getSex());
        response.setWeightKg(patient.getWeightKg());
        response.setAllergies(patient.getAllergies());
        response.setPrimaryDiagnosis(patient.getPrimaryDiagnosis());
        response.setAdditionalDiagnoses(patient.getAdditionalDiagnoses());

        if (patient.getCreatedAt() != null) {
            response.setCreatedAt(patient.getCreatedAt().atOffset(ZoneOffset.UTC).toInstant());
        }

        return response;
    }
}

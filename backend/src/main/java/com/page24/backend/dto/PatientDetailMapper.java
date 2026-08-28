package com.page24.backend.dto;

import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.MedicationHistory;
import com.page24.backend.entity.Order;
import com.page24.backend.entity.Patient;
import com.page24.backend.entity.PatientDiagnosis;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

@Component
public class PatientDetailMapper {

    public PatientDetailResponse toResponse(
            Patient patient,
            List<PatientDiagnosis> diagnoses,
            List<MedicationHistory> medicationHistory
    ) {
        PatientDetailResponse response = new PatientDetailResponse();
        response.setId(patient.getId());
        response.setFirstName(patient.getFirstName());
        response.setLastName(patient.getLastName());
        response.setFullName(String.format("%s %s", patient.getFirstName(), patient.getLastName()).trim());
        response.setMrn(patient.getMrn());
        response.setDateOfBirth(patient.getDateOfBirth());
        response.setSex(patient.getSex());
        response.setWeightKg(patient.getWeightKg());
        response.setAllergies(patient.getAllergies());
        response.setPrimaryDiagnosis(patient.getPrimaryDiagnosis());
        response.setPrimaryDiagnosisDescription(patient.getPrimaryDiagnosisDescription());
        response.setDiagnoses(diagnoses.stream()
                .map(this::toDiagnosisResponse)
                .toList());
        response.setMedicationHistory(medicationHistory.stream()
                .map(this::toMedicationHistoryResponse)
                .toList());

        if (patient.getCreatedAt() != null) {
            response.setCreatedAt(patient.getCreatedAt().atOffset(ZoneOffset.UTC).toInstant());
        }
        if (patient.getUpdatedAt() != null) {
            response.setUpdatedAt(patient.getUpdatedAt().atOffset(ZoneOffset.UTC).toInstant());
        }

        return response;
    }

    private PatientDiagnosisResponse toDiagnosisResponse(PatientDiagnosis diagnosis) {
        PatientDiagnosisResponse response = new PatientDiagnosisResponse();
        response.setId(diagnosis.getId());
        response.setIcd10Code(diagnosis.getIcd10Code());
        response.setDescription(diagnosis.getDescription());
        response.setPrimary(diagnosis.isPrimary());
        if (diagnosis.getCreatedAt() != null) {
            response.setCreatedAt(diagnosis.getCreatedAt().atOffset(ZoneOffset.UTC).toInstant());
        }
        return response;
    }

    private MedicationHistoryResponse toMedicationHistoryResponse(MedicationHistory medicationHistory) {
        MedicationHistoryResponse response = new MedicationHistoryResponse();
        response.setId(medicationHistory.getId());
        response.setMedicationName(medicationHistory.getMedicationName());
        response.setDosage(medicationHistory.getDosage());
        response.setFrequency(medicationHistory.getFrequency());
        response.setCurrent(medicationHistory.isCurrent());
        if (medicationHistory.getCreatedAt() != null) {
            response.setCreatedAt(medicationHistory.getCreatedAt().atOffset(ZoneOffset.UTC).toInstant());
        }
        return response;
    }

    public PatientOrderSummaryResponse toOrderSummary(Order order, CarePlan carePlan) {
        PatientOrderSummaryResponse response = new PatientOrderSummaryResponse();
        response.setId(order.getId());
        response.setMedicationName(order.getMedicationName());
        response.setStatus(toStatus(carePlan));

        if (order.getCreatedAt() != null) {
            response.setCreatedAt(order.getCreatedAt().atOffset(ZoneOffset.UTC).toInstant());
        }

        return response;
    }

    public PatientHistoryOrderResponse toHistoryResponse(Order order, CarePlan carePlan) {
        Patient patient = order.getPatient();
        PatientHistoryOrderResponse response = new PatientHistoryOrderResponse();
        response.setId(order.getId());
        response.setPatientMrn(patient.getMrn());
        response.setPatientName(String.format("%s %s", patient.getFirstName(), patient.getLastName()).trim());
        response.setProviderNpi(order.getProvider().getNpi());
        response.setProviderName(order.getProvider().getName());
        response.setMedicationName(order.getMedicationName());
        response.setStatus(toStatus(carePlan));
        response.setHasCarePlan(carePlan != null);

        if (order.getCreatedAt() != null) {
            response.setCreatedAt(order.getCreatedAt().atOffset(ZoneOffset.UTC).toInstant());
        }

        return response;
    }

    private String toStatus(CarePlan carePlan) {
        return carePlan == null
                ? CarePlan.Status.PENDING.name().toLowerCase(Locale.ROOT)
                : carePlan.getStatus().name().toLowerCase(Locale.ROOT);
    }
}

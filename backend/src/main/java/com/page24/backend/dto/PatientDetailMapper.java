package com.page24.backend.dto;

import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.Order;
import com.page24.backend.entity.Patient;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class PatientDetailMapper {

    public PatientDetailResponse toResponse(
            Patient patient,
            List<String> medicationHistory,
            List<Order> orders,
            Map<Long, CarePlan> carePlansByOrderId
    ) {
        PatientDetailResponse response = new PatientDetailResponse();
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
        response.setMedicationHistory(medicationHistory);
        response.setOrders(orders.stream()
                .map(order -> toOrderSummary(order, carePlansByOrderId.get(order.getId())))
                .toList());

        if (patient.getCreatedAt() != null) {
            response.setCreatedAt(patient.getCreatedAt().atOffset(ZoneOffset.UTC).toInstant());
        }

        return response;
    }

    private PatientOrderSummaryResponse toOrderSummary(Order order, CarePlan carePlan) {
        PatientOrderSummaryResponse response = new PatientOrderSummaryResponse();
        response.setId(order.getId());
        response.setMedicationName(order.getMedicationName());
        response.setStatus(carePlan == null
                ? CarePlan.Status.PENDING.name().toLowerCase(Locale.ROOT)
                : carePlan.getStatus().name().toLowerCase(Locale.ROOT));

        if (order.getCreatedAt() != null) {
            response.setCreatedAt(order.getCreatedAt().atOffset(ZoneOffset.UTC).toInstant());
        }

        return response;
    }
}

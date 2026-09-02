package com.page24.backend.dto;

import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.Order;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.Locale;

/**
 * OrderMapper - maps persistence entities to API response DTOs.
 *
 * Converts the Order and CarePlan entities into the OrderResponse shape expected by the frontend.
 * This follows the view-object assembly pattern: copy common properties and populate derived fields.
 *
 * Keeping this mapping separate preserves clear boundaries:
 * - Controllers do not need to know how a response is assembled.
 * - Services do not need to know the frontend response format.
 * - Mappers transform data only; they do not apply business rules.
 */
@Component
public class OrderMapper {

    /**
     * Converts an Order and its CarePlan into the response returned to the frontend.
     *
     * This logic previously lived in OrderController's toResponse() method.
     */
    public OrderResponse toResponse(Order order, CarePlan carePlan) {
        OrderResponse response = new OrderResponse();
        response.setId(order.getId());
        response.setPatientId(order.getPatient().getId());
        response.setProviderId(order.getProvider().getId());
        response.setMedicationName(order.getMedicationName());
        response.setResultType("SUCCESS");
        response.setRequiresConfirm(false);

        if (carePlan != null) {
            response.setStatus(carePlan.getStatus().name());
            // Expose the generated plan only after generation has completed.
            if (carePlan.getStatus() == CarePlan.Status.COMPLETED) {
                response.setCarePlanContent(carePlan.getContent());
            }
        } else {
            response.setStatus("PENDING");
        }

        return response;
    }

    /** Maps an order to the smaller shape required by GET /api/orders. */
    public OrderListItemResponse toListItemResponse(Order order, CarePlan carePlan) {
        String patientName = String.format("%s %s",
                order.getPatient().getFirstName(), order.getPatient().getLastName()).trim();
        String status = carePlan == null
                ? "pending"
                : carePlan.getStatus().name().toLowerCase(Locale.ROOT);

        return new OrderListItemResponse(
                order.getId(),
                patientName,
                order.getMedicationName(),
                status,
                order.getCreatedAt().atOffset(ZoneOffset.UTC).toInstant()
        );
    }
}


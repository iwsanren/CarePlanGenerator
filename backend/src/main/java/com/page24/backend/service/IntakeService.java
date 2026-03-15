package com.page24.backend.service;

import com.page24.backend.dto.CreateOrderRequest;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.exception.ValidationError;
import com.page24.backend.intake.ClinicB;
import com.page24.backend.intake.PharmaCorp;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Set;

/**
 * Day9 intake service: keep source-specific parse/mapping here,
 * then reuse the existing OrderService business pipeline.
 */
@Service
@RequiredArgsConstructor
public class IntakeService {

    private final Validator validator;
    private final OrderService orderService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ClinicB clinicB = new ClinicB();
    private final PharmaCorp pharmaCorp = new PharmaCorp();

    public OrderResponse createFromClinicBJson(String rawJson, Boolean confirm) {
        try {
            ClinicB.ClinicBPayload payload = clinicB.parseInput(rawJson, objectMapper);
            validateClinicPayload(payload);

            CreateOrderRequest mappedRequest = clinicB.toCreateOrderRequest(payload, confirm);
            validateMappedRequest(mappedRequest);
            return orderService.createOrder(mappedRequest);
        } catch (IOException e) {
            throw new ValidationError("INVALID_CLINIC_B_JSON", "Clinic B JSON payload is invalid");
        }
    }

    public OrderResponse createFromPharmaCorpXml(String rawXml, Boolean confirm) {
        try {
            PharmaCorp.PharmaCorpPayload payload = pharmaCorp.parseInput(rawXml);
            validatePharmaPayload(payload);

            CreateOrderRequest mappedRequest = pharmaCorp.toCreateOrderRequest(payload, confirm);
            validateMappedRequest(mappedRequest);
            return orderService.createOrder(mappedRequest);
        } catch (IllegalArgumentException e) {
            throw new ValidationError("INVALID_PHARMA_XML", "PharmaCorp XML payload is invalid");
        }
    }

    private void validateMappedRequest(CreateOrderRequest request) {
        Set<ConstraintViolation<CreateOrderRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    private void validateClinicPayload(ClinicB.ClinicBPayload payload) {
        if (payload == null || payload.getPt() == null || payload.getProvider() == null
                || payload.getDx() == null || payload.getRx() == null) {
            throw new ValidationError("INVALID_CLINIC_B_JSON", "Clinic B JSON missing required nested fields");
        }
    }

    private void validatePharmaPayload(PharmaCorp.PharmaCorpPayload payload) {
        if (payload == null) {
            throw new ValidationError("INVALID_PHARMA_XML", "PharmaCorp XML payload is invalid");
        }
    }
}






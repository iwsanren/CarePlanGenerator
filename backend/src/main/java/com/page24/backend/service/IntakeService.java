package com.page24.backend.service;

import com.page24.backend.dto.CreateOrderRequest;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.exception.ValidationError;
import com.page24.backend.intake.ClinicBAdapter;
import com.page24.backend.intake.Common;
import com.page24.backend.intake.HospitalDAdapter;
import com.page24.backend.intake.IntakeAdapterFactory;
import com.page24.backend.intake.InternalOrder;
import com.page24.backend.intake.PharmaCorp;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
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
    private final IntakeAdapterFactory intakeAdapterFactory;

    private final PharmaCorp pharmaCorp = new PharmaCorp();

    public OrderResponse createFromClinicBJson(String rawJson, Boolean confirm) {
        ClinicBAdapter clinicBAdapter = intakeAdapterFactory.getAdapter("clinic-b", ClinicBAdapter.class);
        ClinicBAdapter.ClinicBPayload payload = clinicBAdapter.parse(rawJson);

        InternalOrder internalOrder = clinicBAdapter.transform(payload);
        clinicBAdapter.validate(internalOrder);

        CreateOrderRequest mappedRequest = toCreateOrderRequest(internalOrder, payload, confirm);
        validateMappedRequest(mappedRequest);
        return orderService.createOrder(mappedRequest);
    }

    //TODO:
    // 处理csv格式的数据，并将结果return给controller。
    public OrderResponse createFromHospitalDCsv(String rawCsv, boolean confirm){
        HospitalDAdapter hospitalDAdapter = intakeAdapterFactory.getAdapter("hospital-d", HospitalDAdapter.class);
        HospitalDAdapter.HospitalDPayload payload = hospitalDAdapter.parse(rawCsv);

        InternalOrder internalOrder = hospitalDAdapter.transform(payload);
        hospitalDAdapter.validate(internalOrder);

        CreateOrderRequest mappedRequest = toCreateOrderRequest(
                internalOrder,
                payload.getMedicationHistory(),
                payload.getClinicalNotes(),
                confirm
        );
        validateMappedRequest(mappedRequest);
        return orderService.createOrder(mappedRequest);
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

    private CreateOrderRequest toCreateOrderRequest(
            InternalOrder internalOrder,
            ClinicBAdapter.ClinicBPayload payload,
            Boolean confirm
    ) {
        return toCreateOrderRequest(internalOrder, payload.getMedHx(), payload.getClinicalNotes(), confirm);
    }

    private CreateOrderRequest toCreateOrderRequest(
            InternalOrder internalOrder,
            List<String> medicationHistory,
            String clinicalNotes,
            Boolean confirm
    ) {
        CreateOrderRequest request = new CreateOrderRequest();

        request.setPatientFirstName(internalOrder.getPatient().getFirstName());
        request.setPatientLastName(internalOrder.getPatient().getLastName());
        request.setPatientMrn(internalOrder.getPatient().getMrn());
        request.setPatientDateOfBirth(internalOrder.getPatient().getDateOfBirth());

        request.setProviderName(internalOrder.getProvider().getName());
        request.setProviderNpi(internalOrder.getProvider().getNpi());

        request.setMedicationName(internalOrder.getMedication().getName());
        request.setPrimaryDiagnosis(internalOrder.getDiagnosis().getPrimaryDiagnosis());
        request.setAdditionalDiagnosis(Common.joinByComma(internalOrder.getDiagnosis().getAdditionalDiagnoses()));

        request.setMedicationHistory(Common.joinByNewLine(medicationHistory));
        request.setPatientRecords(clinicalNotes);
        request.setConfirm(Boolean.TRUE.equals(confirm));

        return request;
    }

    private void validateMappedRequest(CreateOrderRequest request) {
        Set<ConstraintViolation<CreateOrderRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    private void validatePharmaPayload(PharmaCorp.PharmaCorpPayload payload) {
        if (payload == null) {
            throw new ValidationError("INVALID_PHARMA_XML", "PharmaCorp XML payload is invalid");
        }
    }
}

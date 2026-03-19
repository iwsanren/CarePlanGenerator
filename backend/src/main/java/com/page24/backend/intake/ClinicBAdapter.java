package com.page24.backend.intake;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Clinic B JSON adapter.
 * Scope: parse -> transform -> validate (format/structure only).
 */
@Component
@RequiredArgsConstructor
public class ClinicBAdapter implements BaseIntakeAdapter<ClinicBAdapter.ClinicBPayload> {

    // Keep mapper local so adapter does not depend on ObjectMapper bean registration.
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Validator validator;

    @Override
    public String source() {
        return "clinic-b";
    }

    @Override
    public ClinicBPayload parse(String rawPayload) throws IntakeParseException {
        if (rawPayload == null || rawPayload.isBlank()) {
            throw new IntakeParseException("INVALID_CLINIC_B_JSON", "Clinic B payload is required");
        }

        try {
            return objectMapper.readValue(rawPayload, ClinicBPayload.class);
        } catch (JsonProcessingException e) {
            throw new IntakeParseException(
                    "INVALID_CLINIC_B_JSON",
                    "Clinic B JSON payload is invalid",
                    Map.of("reason", e.getOriginalMessage())
            );
        }
    }

    @Override
    public InternalOrder transform(ClinicBPayload sourceDto) {
        if (sourceDto == null) {
            throw new IntakeParseException("INVALID_CLINIC_B_JSON", "Clinic B JSON payload is invalid");
        }

        InternalOrder order = new InternalOrder();

        InternalOrder.Patient patient = new InternalOrder.Patient();
        if (sourceDto.getPt() != null) {
            patient.setFirstName(sourceDto.getPt().getFname());
            patient.setLastName(sourceDto.getPt().getLname());
            patient.setMrn(sourceDto.getPt().getMrn());
            patient.setDateOfBirth(Common.parseUsDate(sourceDto.getPt().getDob()));
        }
        order.setPatient(patient);

        InternalOrder.Provider provider = new InternalOrder.Provider();
        if (sourceDto.getProvider() != null) {
            provider.setName(sourceDto.getProvider().getName());
            provider.setNpi(sourceDto.getProvider().getNpiNum());
        }
        order.setProvider(provider);

        InternalOrder.Medication medication = new InternalOrder.Medication();
        if (sourceDto.getRx() != null) {
            medication.setName(sourceDto.getRx().getMedName());
        }
        order.setMedication(medication);

        InternalOrder.Diagnosis diagnosis = new InternalOrder.Diagnosis();
        if (sourceDto.getDx() != null) {
            diagnosis.setPrimaryDiagnosis(sourceDto.getDx().getPrimary());
            diagnosis.setAdditionalDiagnoses(sourceDto.getDx().getSecondary());
        }
        order.setDiagnosis(diagnosis);

        return order;
    }

    @Override
    public void validate(InternalOrder internalOrder) throws IntakeValidationException {
        if (internalOrder == null) {
            throw new IntakeValidationException("INVALID_INTERNAL_ORDER", "InternalOrder is required");
        }

        Set<ConstraintViolation<InternalOrder>> violations = validator.validate(internalOrder);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    @Data
    public static class ClinicBPayload {
        @JsonProperty("order_info")
        private OrderInfo orderInfo;
        private Pt pt;
        private Provider provider;
        private Dx dx;
        private Rx rx;
        private List<String> allergies;

        @JsonProperty("med_hx")
        private List<String> medHx;

        @JsonProperty("clinical_notes")
        private String clinicalNotes;
    }

    @Data
    public static class OrderInfo {
        private String created;
        private String src;
    }

    @Data
    public static class Pt {
        private String mrn;
        private String fname;
        private String lname;
        private String mi;
        private String dob;
        private String gender;
        private Double wt;

        @JsonProperty("wt_unit")
        private String wtUnit;
    }

    @Data
    public static class Provider {
        private String name;

        @JsonProperty("npi_num")
        private String npiNum;
    }

    @Data
    public static class Dx {
        private String primary;
        private List<String> secondary;
    }

    @Data
    public static class Rx {
        @JsonProperty("med_name")
        private String medName;
        private String ndc;
        private String dosage;
        private String freq;
    }
}

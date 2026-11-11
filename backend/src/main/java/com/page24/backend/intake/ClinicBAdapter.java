package com.page24.backend.intake;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
    // Unknown fields are ignored, so a clinic that adds a field to its JSON does not break its orders. Data that is
    // required is still checked by validate(), so a mistyped name for a required field is reported as missing.
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
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
                    Map.of("reason", describe(e))
            );
        }
    }

    // The reason is written here instead of copying Jackson's message: that message names internal classes
    // ("class com.page24...ClinicBAdapter$ClinicBPayload") and, for a syntax error, quotes the text that was sent.
    private static String describe(JsonProcessingException e) {
        // A syntax error met while a section is being read comes wrapped in a JsonMappingException that carries the
        // path of that section; it is still a syntax error, not a wrong type, so look at what it wraps.
        JsonProcessingException problem = e.getCause() instanceof StreamReadException syntaxError ? syntaxError : e;
        if (problem instanceof JsonMappingException mappingException) {
            String path = fieldPath(mappingException.getPath());
            return path.isEmpty()
                    ? "The JSON does not have the structure of an order"
                    : "Field '" + path + "' has the wrong type";
        }
        JsonLocation location = problem.getLocation();
        return location == null
                ? "Malformed JSON"
                : "Malformed JSON at line " + location.getLineNr() + ", column " + location.getColumnNr();
    }

    private static String fieldPath(List<JsonMappingException.Reference> references) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : references) {
            if (reference.getFieldName() != null) {
                if (path.length() > 0) {
                    path.append('.');
                }
                path.append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
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
            patient.setDateOfBirth(parseDateOfBirth(sourceDto.getPt().getDob()));
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

    private static LocalDate parseDateOfBirth(String dob) {
        try {
            return Common.parseUsDate(dob);
        } catch (DateTimeParseException e) {
            throw Common.invalidDateOfBirth("pt.dob", "MM/dd/yyyy");
        }
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

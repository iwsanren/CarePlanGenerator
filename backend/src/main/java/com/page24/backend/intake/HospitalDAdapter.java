package com.page24.backend.intake;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class HospitalDAdapter implements BaseIntakeAdapter<HospitalDAdapter.HospitalDPayload> {

    private static final DateTimeFormatter HOSPITAL_D_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String[] DEFAULT_HEADERS = new String[]{
            "source_system", "request_id", "created_at", "pt_id", "pt_given", "pt_family",
            "pt_dob_yyyymmdd", "doc_npi", "doc_full_name", "drug_label", "dx_primary",
            "dx_extra", "med_hist_blob", "clinical_note_blob"
    };

    private final Validator validator;

    @Override
    public String source() {
        return "hospital-d";
    }

    @Override
    public HospitalDPayload parse(String rawPayload) throws IntakeParseException {
        List<String> nonEmptyLines = rawPayload.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();

        String headerLine;
        String dataLine;

        if (nonEmptyLines.get(0).toLowerCase().contains("source_system")) {
            headerLine = nonEmptyLines.get(0);
            dataLine = nonEmptyLines.get(1);
        } else {
            headerLine = String.join(",", DEFAULT_HEADERS);
            dataLine = nonEmptyLines.get(0);
        }

        List<String> headers = splitCsvLine(headerLine);
        List<String> values = splitCsvLine(dataLine);

        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i).trim();
            String value = i < values.size() ? unquote(values.get(i).trim()) : "";
            map.put(header, value);
        }

        HospitalDPayload payload = new HospitalDPayload();
        payload.setPtId(map.get("pt_id"));
        payload.setPtGiven(map.get("pt_given"));
        payload.setPtFamily(map.get("pt_family"));
        payload.setPtDobYyyymmdd(map.get("pt_dob_yyyymmdd"));
        payload.setDocNpi(map.get("doc_npi"));
        payload.setDocFullName(map.get("doc_full_name"));
        payload.setDrugLabel(map.get("drug_label"));
        payload.setDxPrimary(map.get("dx_primary"));
        payload.setDxExtra(splitByPipe(map.get("dx_extra")));
        payload.setMedicationHistory(splitBySemicolon(map.get("med_hist_blob")));
        payload.setClinicalNotes(map.get("clinical_note_blob"));
        return payload;
    }

    @Override
    public InternalOrder transform(HospitalDPayload payload) {
        InternalOrder order = new InternalOrder();

        InternalOrder.Patient patient = new InternalOrder.Patient();
        patient.setFirstName(payload.getPtGiven());
        patient.setLastName(payload.getPtFamily());
        patient.setMrn(payload.getPtId());
        patient.setDateOfBirth(parseHospitalDate(payload.getPtDobYyyymmdd()));
        order.setPatient(patient);

        InternalOrder.Provider provider = new InternalOrder.Provider();
        provider.setName(payload.getDocFullName());
        provider.setNpi(payload.getDocNpi());
        order.setProvider(provider);

        InternalOrder.Medication medication = new InternalOrder.Medication();
        medication.setName(payload.getDrugLabel());
        order.setMedication(medication);

        InternalOrder.Diagnosis diagnosis = new InternalOrder.Diagnosis();
        diagnosis.setPrimaryDiagnosis(payload.getDxPrimary());
        diagnosis.setAdditionalDiagnoses(payload.getDxExtra());
        order.setDiagnosis(diagnosis);

        return order;
    }

    @Override
    public void validate(InternalOrder internalOrder) throws IntakeValidationException {
        Set<ConstraintViolation<InternalOrder>> violations = validator.validate(internalOrder);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    private LocalDate parseHospitalDate(String value) {
        return LocalDate.parse(value, HOSPITAL_D_DATE);
    }

    private List<String> splitByPipe(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split("\\|"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private List<String> splitBySemicolon(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private String unquote(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    // Minimal CSV split that keeps commas inside quotes.
    private List<String> splitCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (c == ',' && !inQuotes) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values;
    }

    @Data
    public static class HospitalDPayload {
        private String ptId;
        private String ptGiven;
        private String ptFamily;
        private String ptDobYyyymmdd;
        private String docNpi;
        private String docFullName;
        private String drugLabel;
        private String dxPrimary;
        private List<String> dxExtra;
        private List<String> medicationHistory;
        private String clinicalNotes;
    }
}

package com.page24.backend.intake;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.page24.backend.dto.CreateOrderRequest;
import lombok.Data;

import java.io.IOException;
import java.util.List;

/**
 * Clinic B intake mapper.
 * Step1: parse clinic JSON into ClinicBPayload.
 * Step2: transform ClinicBPayload into CreateOrderRequest.
 */
public class ClinicB {

    public ClinicBPayload parseInput(String json, ObjectMapper objectMapper) throws IOException {
        return objectMapper.readValue(json, ClinicBPayload.class);
    }

    public CreateOrderRequest toCreateOrderRequest(ClinicBPayload payload, Boolean confirm) {
        CreateOrderRequest request = new CreateOrderRequest();

        request.setPatientFirstName(payload.getPt().getFname());
        request.setPatientLastName(payload.getPt().getLname());
        request.setPatientMrn(payload.getPt().getMrn());
        request.setPatientDateOfBirth(Common.parseUsDate(payload.getPt().getDob()));

        request.setProviderName(payload.getProvider().getName());
        request.setProviderNpi(payload.getProvider().getNpiNum());

        request.setMedicationName(payload.getRx().getMedName());
        request.setPrimaryDiagnosis(payload.getDx().getPrimary());
        request.setAdditionalDiagnosis(Common.joinByComma(payload.getDx().getSecondary()));
        request.setMedicationHistory(Common.joinByNewLine(payload.getMedHx()));
        request.setPatientRecords(payload.getClinicalNotes());
        request.setConfirm(Boolean.TRUE.equals(confirm));

        return request;
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

/*
clinic_b_data = {
    "order_info": {
        "created": "01/15/2025 2:30 PM",
        "src": "DOWNTOWN_CLINIC"
    },
    "pt": {
        "mrn": "234567",
        "fname": "Jane",
        "lname": "Smith",
        "mi": "A",
        "dob": "03/22/1985",
        "gender": "F",
        "wt": 65,
        "wt_unit": "kg"
    },
    "provider": {
        "name": "Dr. Emily Johnson",
        "npi_num": "0987654321"
    },
    "dx": {
        "primary": "G70.00",
        "secondary": ["E11.9", "I10"]
    },
    "rx": {
        "med_name": "Gamunex-C",
        "ndc": "13533-0800-20",
        "dosage": "32.5g",
        "freq": "every day"
    },
    "allergies": ["Penicillin", "Sulfa"],
    "med_hx": [
        "Metformin 500mg twice daily",
        "Lisinopril 5mg once daily",
        "Atorvastatin 20mg at bedtime",
        "Aspirin 81mg once daily"
    ],
    "clinical_notes": "Patient presents with progressive weakness over past 3 weeks. Diagnosed with MG 6 months ago. Neuro consult recommends IVIG therapy. Patient educated on infusion process."
}
 */
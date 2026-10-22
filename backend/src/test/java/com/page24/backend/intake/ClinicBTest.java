package com.page24.backend.intake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.page24.backend.dto.CreateOrderRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests (no Spring, no mocks) for the legacy Clinic B mapper:
 * parse the clinic's JSON, then map it onto CreateOrderRequest.
 */
class ClinicBTest {

    private static final String CLINIC_B_JSON = """
            {
              "order_info": {"created": "01/15/2025 2:30 PM", "src": "DOWNTOWN_CLINIC"},
              "pt": {"mrn": "234567", "fname": "Jane", "lname": "Smith", "mi": "A",
                     "dob": "03/22/1985", "gender": "F", "wt": 65, "wt_unit": "kg"},
              "provider": {"name": "Dr. Emily Johnson", "npi_num": "0987654321"},
              "dx": {"primary": "G70.00", "secondary": ["E11.9", "I10"]},
              "rx": {"med_name": "Gamunex-C", "ndc": "13533-0800-20", "dosage": "32.5g", "freq": "every day"},
              "allergies": ["Penicillin", "Sulfa"],
              "med_hx": ["Metformin 500mg twice daily", "Aspirin 81mg once daily"],
              "clinical_notes": "Progressive weakness over 3 weeks."
            }
            """;

    private final ClinicB clinicB = new ClinicB();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("parseInput maps the clinic's snake_case JSON onto the payload object")
    void parsesClinicJson() throws IOException {
        ClinicB.ClinicBPayload payload = clinicB.parseInput(CLINIC_B_JSON, objectMapper);

        assertThat(payload.getPt().getFname()).isEqualTo("Jane");
        assertThat(payload.getProvider().getNpiNum()).isEqualTo("0987654321");
        assertThat(payload.getRx().getMedName()).isEqualTo("Gamunex-C");
        assertThat(payload.getDx().getSecondary()).containsExactly("E11.9", "I10");
        assertThat(payload.getMedHx()).hasSize(2);
        assertThat(payload.getClinicalNotes()).isEqualTo("Progressive weakness over 3 weeks.");
        assertThat(payload.getOrderInfo().getSrc()).isEqualTo("DOWNTOWN_CLINIC");
    }

    @Test
    @DisplayName("parseInput rejects malformed JSON")
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> clinicB.parseInput("{not json", objectMapper))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    @DisplayName("toCreateOrderRequest maps every field, converting the US-format date of birth")
    void mapsPayloadToRequest() throws IOException {
        ClinicB.ClinicBPayload payload = clinicB.parseInput(CLINIC_B_JSON, objectMapper);

        CreateOrderRequest request = clinicB.toCreateOrderRequest(payload, true);

        assertThat(request.getPatientFirstName()).isEqualTo("Jane");
        assertThat(request.getPatientLastName()).isEqualTo("Smith");
        assertThat(request.getPatientMrn()).isEqualTo("234567");
        assertThat(request.getPatientDateOfBirth()).isEqualTo(LocalDate.of(1985, 3, 22));
        assertThat(request.getProviderName()).isEqualTo("Dr. Emily Johnson");
        assertThat(request.getProviderNpi()).isEqualTo("0987654321");
        assertThat(request.getMedicationName()).isEqualTo("Gamunex-C");
        assertThat(request.getPrimaryDiagnosis()).isEqualTo("G70.00");
        assertThat(request.getAdditionalDiagnoses()).containsExactly("E11.9", "I10");
        assertThat(request.getMedicationHistory()).containsExactly(
                "Metformin 500mg twice daily", "Aspirin 81mg once daily");
        assertThat(request.getPatientRecords()).isEqualTo("Progressive weakness over 3 weeks.");
        assertThat(request.getConfirm()).isTrue();
    }

    @Test
    @DisplayName("a missing confirm flag is treated as false")
    void missingConfirmMeansFalse() throws IOException {
        ClinicB.ClinicBPayload payload = clinicB.parseInput(CLINIC_B_JSON, objectMapper);

        assertThat(clinicB.toCreateOrderRequest(payload, null).getConfirm()).isFalse();
        assertThat(clinicB.toCreateOrderRequest(payload, false).getConfirm()).isFalse();
    }
}

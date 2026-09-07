package com.page24.backend.service;

import com.page24.backend.entity.Order;
import com.page24.backend.entity.Patient;
import com.page24.backend.entity.Provider;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for CarePlanGenerationService.buildPatientInfo().
 */
class CarePlanGenerationServicePromptTest {

    private final CarePlanGenerationService service = new CarePlanGenerationService(null, null);

    @Test
    void includesAllSectionHeadersWhenEveryFieldIsPresent() {
        Order order = fullOrder();

        String patientInfo = service.buildPatientInfo(order);

        assertThat(patientInfo)
                .contains("## PATIENT DEMOGRAPHICS")
                .contains("## DIAGNOSES")
                .contains("## MEDICATION HISTORY")
                .contains("## CLINICAL NOTES");
    }

    @Test
    void rendersMissingDateOfBirthAsNotProvided() {
        Order order = fullOrder();
        order.getPatient().setDateOfBirth(null);

        String patientInfo = service.buildPatientInfo(order);

        assertThat(patientInfo).contains("Date of Birth: Not provided");
    }

    @Test
    void rendersMissingAllergiesAsNotDocumented() {
        Order order = fullOrder();
        order.getPatient().setAllergies(null);

        String patientInfo = service.buildPatientInfo(order);

        // Missing allergy documentation must never be rendered as a confirmed "no allergies"
        // ("None") or a generic "N/A" - both would let the LLM treat it as a clinical
        // fact instead of an information gap.
        assertThat(patientInfo)
                .contains("Allergies: Not documented")
                .doesNotContain("Allergies: None")
                .doesNotContain("Allergies: N/A");
    }

    @Test
    void rendersEmptyAdditionalDiagnosesAsNoneUnderSecondary() {
        Order order = fullOrder();
        order.setAdditionalDiagnoses(List.of());

        String patientInfo = service.buildPatientInfo(order);

        assertThat(patientInfo).contains("Secondary:\nNone");
    }

    @Test
    void rendersEachAdditionalDiagnosisAsItsOwnListItem() {
        Order order = fullOrder();
        order.setAdditionalDiagnoses(List.of("I10", "E11.9"));

        String patientInfo = service.buildPatientInfo(order);

        assertThat(patientInfo).contains("- I10\n- E11.9");
    }

    private Order fullOrder() {
        Patient patient = new Patient();
        patient.setFirstName("Alice");
        patient.setLastName("Wong");
        patient.setMrn("123456");
        patient.setDateOfBirth(LocalDate.of(1990, 5, 10));
        patient.setSex("Female");
        patient.setWeightKg(65.5);
        patient.setAllergies("Penicillin");

        Provider provider = new Provider();
        provider.setName("Dr. Green");
        provider.setNpi("1111111111");

        Order order = new Order();
        order.setPatient(patient);
        order.setProvider(provider);
        order.setMedicationName("IVIG");
        order.setPrimaryDiagnosis("G70.00");
        order.setAdditionalDiagnoses(List.of("I10", "E11.9"));
        order.setMedicationHistory(List.of("Metformin 500mg BID"));
        order.setPatientRecords("Progressive muscle weakness over 2 weeks.");
        return order;
    }
}

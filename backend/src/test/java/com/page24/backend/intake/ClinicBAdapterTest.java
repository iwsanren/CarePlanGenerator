package com.page24.backend.intake;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for the Clinic B JSON adapter (parse, transform, validate). The validator is the real Bean
 * Validation one, so validate() is checked against the actual rules of InternalOrder.
 */
class ClinicBAdapterTest {

    private static final String JSON = """
            {
              "order_info": {"created": "01/15/2025 2:30 PM", "src": "DOWNTOWN_CLINIC"},
              "pt": {"mrn": "234567", "fname": "Jane", "lname": "Smith", "mi": "A",
                     "dob": "03/22/1985", "gender": "F", "wt": 65.5, "wt_unit": "kg"},
              "provider": {"name": "Dr. Emily Johnson", "npi_num": "0987654321"},
              "dx": {"primary": "G70.00", "secondary": ["E11.9", "I10"]},
              "rx": {"med_name": "Gamunex-C", "ndc": "13533-0800-20", "dosage": "32.5g", "freq": "every day"},
              "allergies": ["Penicillin", "Sulfa"],
              "med_hx": ["Metformin 500mg twice daily", "Aspirin 81mg once daily"],
              "clinical_notes": "Progressive weakness over 3 weeks."
            }
            """;

    private static ValidatorFactory validatorFactory;
    private static ClinicBAdapter adapter;

    @BeforeAll
    static void createAdapter() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        adapter = new ClinicBAdapter(validatorFactory.getValidator());
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("The adapter registers itself under the source name used in the URL")
    void sourceIsClinicB() {
        assertThat(adapter.source()).isEqualTo("clinic-b");
    }

    // ---------------------------------------------------------------- parse

    @Test
    @DisplayName("parse maps the clinic's snake_case JSON onto the payload, including numbers and lists")
    void parsesClinicJson() {
        ClinicBAdapter.ClinicBPayload payload = adapter.parse(JSON);

        assertThat(payload.getOrderInfo().getSrc()).isEqualTo("DOWNTOWN_CLINIC");
        assertThat(payload.getPt().getMrn()).isEqualTo("234567");
        assertThat(payload.getPt().getFname()).isEqualTo("Jane");
        assertThat(payload.getPt().getLname()).isEqualTo("Smith");
        assertThat(payload.getPt().getDob()).isEqualTo("03/22/1985");
        assertThat(payload.getPt().getWt()).isEqualTo(65.5);
        assertThat(payload.getPt().getWtUnit()).isEqualTo("kg");
        assertThat(payload.getProvider().getNpiNum()).isEqualTo("0987654321");
        assertThat(payload.getDx().getPrimary()).isEqualTo("G70.00");
        assertThat(payload.getDx().getSecondary()).containsExactly("E11.9", "I10");
        assertThat(payload.getRx().getMedName()).isEqualTo("Gamunex-C");
        assertThat(payload.getAllergies()).containsExactly("Penicillin", "Sulfa");
        assertThat(payload.getMedHx()).containsExactly("Metformin 500mg twice daily", "Aspirin 81mg once daily");
        assertThat(payload.getClinicalNotes()).isEqualTo("Progressive weakness over 3 weeks.");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" is rejected as missing")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\n\t "})
    @DisplayName("parse rejects a missing or blank payload with a 400 and the code INVALID_CLINIC_B_JSON")
    void rejectsAMissingPayload(String raw) {
        assertThatThrownBy(() -> adapter.parse(raw))
                .isInstanceOfSatisfying(IntakeParseException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVALID_CLINIC_B_JSON");
                    assertThat(ex.getType()).isEqualTo("validation");
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessage()).isEqualTo("Clinic B payload is required");
                    assertThat(ex.getDetail()).isNull();
                });
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"{not json", "[1, 2, 3]", "\"just a string\"", "{\"pt\": \"not an object\"}",
            "{\"pt\": {\"wt\": \"heavy\"}}"})
    @DisplayName("parse rejects text that is not JSON of the expected shape, and gives the parser's reason")
    void rejectsJsonThatCannotBeRead(String raw) {
        assertThatThrownBy(() -> adapter.parse(raw))
                .isInstanceOfSatisfying(IntakeParseException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVALID_CLINIC_B_JSON");
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessage()).isEqualTo("Clinic B JSON payload is invalid");
                    assertThat(ex.getDetail()).isInstanceOfSatisfying(Map.class,
                            detail -> assertThat(detail.get("reason")).asString().isNotBlank());
                });
    }

    // ------------------------------------------------------------ transform

    @Test
    @DisplayName("transform moves every payload field to its place in the internal order and converts the date")
    void transformsPayloadToInternalOrder() {
        InternalOrder order = adapter.transform(adapter.parse(JSON));

        assertThat(order.getPatient().getFirstName()).isEqualTo("Jane");
        assertThat(order.getPatient().getLastName()).isEqualTo("Smith");
        assertThat(order.getPatient().getMrn()).isEqualTo("234567");
        assertThat(order.getPatient().getDateOfBirth()).isEqualTo(LocalDate.of(1985, 3, 22));
        assertThat(order.getProvider().getName()).isEqualTo("Dr. Emily Johnson");
        assertThat(order.getProvider().getNpi()).isEqualTo("0987654321");
        assertThat(order.getMedication().getName()).isEqualTo("Gamunex-C");
        assertThat(order.getDiagnosis().getPrimaryDiagnosis()).isEqualTo("G70.00");
        assertThat(order.getDiagnosis().getAdditionalDiagnoses()).containsExactly("E11.9", "I10");
    }

    @Test
    @DisplayName("transform of a payload without sections gives empty sections, so validation can name what is missing")
    void transformKeepsMissingSectionsEmpty() {
        InternalOrder order = adapter.transform(adapter.parse("{}"));

        assertThat(order.getPatient()).isNotNull();
        assertThat(order.getPatient().getFirstName()).isNull();
        assertThat(order.getPatient().getDateOfBirth()).isNull();
        assertThat(order.getProvider()).isNotNull();
        assertThat(order.getProvider().getNpi()).isNull();
        assertThat(order.getMedication()).isNotNull();
        assertThat(order.getMedication().getName()).isNull();
        assertThat(order.getDiagnosis()).isNotNull();
        assertThat(order.getDiagnosis().getPrimaryDiagnosis()).isNull();
        assertThat(order.getDiagnosis().getAdditionalDiagnoses()).isNull();
    }

    @Test
    @DisplayName("transform treats a blank date of birth as missing")
    void blankDateOfBirthBecomesNull() {
        InternalOrder order = adapter.transform(adapter.parse(JSON.replace("03/22/1985", "  ")));

        assertThat(order.getPatient().getDateOfBirth()).isNull();
    }

    @Test
    @DisplayName("transform rejects a missing payload (the JSON literal null) with INVALID_CLINIC_B_JSON")
    void transformRejectsANullPayload() {
        assertThat(adapter.parse("null")).isNull();

        assertThatThrownBy(() -> adapter.transform(null))
                .isInstanceOfSatisfying(IntakeParseException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVALID_CLINIC_B_JSON");
                    assertThat(ex.getMessage()).isEqualTo("Clinic B JSON payload is invalid");
                });
    }

    // ------------------------------------------------------------- validate

    @Test
    @DisplayName("validate accepts a complete, well-formed order")
    void validateAcceptsAValidOrder() {
        InternalOrder order = adapter.transform(adapter.parse(JSON));

        assertThatCode(() -> adapter.validate(order)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validate rejects a missing internal order with INVALID_INTERNAL_ORDER")
    void validateRejectsANullOrder() {
        assertThatThrownBy(() -> adapter.validate(null))
                .isInstanceOfSatisfying(IntakeValidationException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVALID_INTERNAL_ORDER");
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessage()).isEqualTo("InternalOrder is required");
                });
    }

    @Test
    @DisplayName("validate of an empty payload names every required field")
    void validateReportsEveryMissingField() {
        InternalOrder order = adapter.transform(adapter.parse("{}"));

        assertThat(violationMessages(order)).containsExactlyInAnyOrder(
                "patient.firstName is required",
                "patient.lastName is required",
                "patient.mrn is required",
                "patient.dateOfBirth is required",
                "provider.name is required",
                "provider.npi is required",
                "medication.name is required",
                "diagnosis.primaryDiagnosis is required");
    }

    @Test
    @DisplayName("validate rejects a bad MRN, NPI and ICD-10 code, reporting each field")
    void validateReportsEachInvalidField() {
        String json = JSON.replace("234567", "12AB").replace("0987654321", "123")
                .replace("G70.00", "not-a-code").replace("\"E11.9\"", "\"zzz\"");
        InternalOrder order = adapter.transform(adapter.parse(json));

        assertThat(violationMessages(order)).containsExactlyInAnyOrder(
                "patient.mrn must be exactly 6 digits",
                "provider.npi must be exactly 10 digits",
                "diagnosis.primaryDiagnosis must be a valid ICD-10 code",
                "diagnosis.additionalDiagnoses item must be a valid ICD-10 code");
    }

    @ParameterizedTest(name = "[{index}] date of birth \"{0}\"")
    @ValueSource(strings = {"1985-03-22", "22/03/1985", "13/01/2020", "3/22/1985", "03-22-1985", "not a date",
            "02/30/1985", "02/31/1985", "04/31/1985", "02/29/1985"})
    @DisplayName("transform refuses a date of birth that is not MM/dd/yyyy as a bad request, not a server error")
    void refusesADateOfBirthInTheWrongFormat(String dateOfBirth) {
        ClinicBAdapter.ClinicBPayload payload = adapter.parse(JSON.replace("03/22/1985", dateOfBirth));

        assertThatThrownBy(() -> adapter.transform(payload))
                .isInstanceOfSatisfying(IntakeParseException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVALID_DATE_OF_BIRTH");
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessage()).isEqualTo("Date of birth must be a real date in the format MM/dd/yyyy");
                    assertThat(ex.getDetail())
                            .isEqualTo(Map.of("field", "pt.dob", "expectedFormat", "MM/dd/yyyy"));
                });
    }

    private List<String> violationMessages(InternalOrder order) {
        assertThatThrownBy(() -> adapter.validate(order)).isInstanceOf(ConstraintViolationException.class);
        try {
            adapter.validate(order);
        } catch (ConstraintViolationException ex) {
            return ex.getConstraintViolations().stream().map(violation -> violation.getMessage()).toList();
        }
        throw new AssertionError("validate did not throw");
    }
}

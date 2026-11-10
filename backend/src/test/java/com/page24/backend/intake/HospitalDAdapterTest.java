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
 * Pure unit tests for the Hospital D CSV adapter. The validator is the real Bean Validation one, so validate()
 * is checked against the actual rules of InternalOrder instead of against a stub.
 */
class HospitalDAdapterTest {

    private static final String HEADER = "source_system,request_id,created_at,pt_id,pt_given,pt_family,"
            + "pt_dob_yyyymmdd,doc_npi,doc_full_name,drug_label,dx_primary,dx_extra,med_hist_blob,"
            + "clinical_note_blob";
    private static final String ROW = "HOSPITAL_D,REQ-1,2025-01-15T14:30:00,456789,Jane,Smith,19850322,"
            + "1122334455,Dr. Emily Johnson,Privigen,G70.00,E11.9|I10,"
            + "Metformin 500mg twice daily;Aspirin 81mg once daily,Progressive weakness over 3 weeks.";

    private static ValidatorFactory validatorFactory;
    private static HospitalDAdapter adapter;

    @BeforeAll
    static void createAdapter() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        adapter = new HospitalDAdapter(validatorFactory.getValidator());
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("The adapter registers itself under the source name used in the URL")
    void sourceIsHospitalD() {
        assertThat(adapter.source()).isEqualTo("hospital-d");
    }

    // ------------------------------------------------------ unusable input: a bad request, not a server error

    @ParameterizedTest(name = "[{index}] payload \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\n\n", " \r\n \r\n"})
    @DisplayName("parse refuses a payload with no content at all")
    void refusesAPayloadWithNoContent(String payload) {
        assertThatThrownBy(() -> adapter.parse(payload))
                .isInstanceOfSatisfying(IntakeParseException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVALID_HOSPITAL_D_CSV");
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessage()).isEqualTo("Hospital D payload is required");
                });
    }

    @ParameterizedTest(name = "[{index}] payload \"{0}\"")
    @ValueSource(strings = {HEADER, HEADER + "\n", "\n" + HEADER + "\n\n", "Source_System,pt_id"})
    @DisplayName("parse refuses a header line that is not followed by a data row")
    void refusesAHeaderWithoutADataRow(String payload) {
        assertThatThrownBy(() -> adapter.parse(payload))
                .isInstanceOfSatisfying(IntakeParseException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVALID_HOSPITAL_D_CSV");
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessage()).isEqualTo("Hospital D CSV has a header but no data row");
                });
    }

    @ParameterizedTest(name = "[{index}] date of birth \"{0}\"")
    @ValueSource(strings = {"1985-03-22", "22031985", "03221985", "not a date", "19851301", "19850230", "1985032"})
    @DisplayName("transform refuses a date of birth that is not a real yyyyMMdd date")
    void refusesADateOfBirthThatIsNotARealCompactDate(String dateOfBirth) {
        HospitalDAdapter.HospitalDPayload payload =
                adapter.parse(HEADER + "\n" + ROW.replace("19850322", dateOfBirth));

        assertThatThrownBy(() -> adapter.transform(payload))
                .isInstanceOfSatisfying(IntakeParseException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVALID_DATE_OF_BIRTH");
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessage()).isEqualTo("Date of birth must be a real date in the format yyyyMMdd");
                    assertThat(ex.getDetail())
                            .isEqualTo(Map.of("field", "pt_dob_yyyymmdd", "expectedFormat", "yyyyMMdd"));
                });
    }

    @Test
    @DisplayName("A missing date of birth is not a parse error: it is left empty and validate then asks for it")
    void leavesAMissingDateOfBirthEmptyForValidationToReport() {
        HospitalDAdapter.HospitalDPayload payload =
                adapter.parse(HEADER + "\n" + ROW.replace("19850322", ""));

        InternalOrder order = adapter.transform(payload);

        assertThat(order.getPatient().getDateOfBirth()).isNull();
        assertThatThrownBy(() -> adapter.validate(order))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("patient.dateOfBirth is required");
    }

    @Test
    @DisplayName("A data row without a date of birth column at all is treated the same way")
    void leavesAnAbsentDateOfBirthColumnEmpty() {
        HospitalDAdapter.HospitalDPayload payload = adapter.parse("source_system,pt_id\nHOSPITAL_D,456789");

        assertThat(adapter.transform(payload).getPatient().getDateOfBirth()).isNull();
    }

    // ---------------------------------------------------------------- parse

    @Test
    @DisplayName("parse reads a header line plus one data line into the payload")
    void parsesHeaderAndDataLine() {
        HospitalDAdapter.HospitalDPayload payload = adapter.parse(HEADER + "\n" + ROW);

        assertThat(payload.getPtId()).isEqualTo("456789");
        assertThat(payload.getPtGiven()).isEqualTo("Jane");
        assertThat(payload.getPtFamily()).isEqualTo("Smith");
        assertThat(payload.getPtDobYyyymmdd()).isEqualTo("19850322");
        assertThat(payload.getDocNpi()).isEqualTo("1122334455");
        assertThat(payload.getDocFullName()).isEqualTo("Dr. Emily Johnson");
        assertThat(payload.getDrugLabel()).isEqualTo("Privigen");
        assertThat(payload.getDxPrimary()).isEqualTo("G70.00");
        assertThat(payload.getDxExtra()).containsExactly("E11.9", "I10");
        assertThat(payload.getMedicationHistory())
                .containsExactly("Metformin 500mg twice daily", "Aspirin 81mg once daily");
        assertThat(payload.getClinicalNotes()).isEqualTo("Progressive weakness over 3 weeks.");
    }

    @Test
    @DisplayName("parse takes the columns from the header, so their order in the file does not matter")
    void mapsColumnsByHeaderName() {
        String csv = "source_system,pt_family,pt_given,pt_id\nHOSPITAL_D,Smith,Jane,456789";

        HospitalDAdapter.HospitalDPayload payload = adapter.parse(csv);

        assertThat(payload.getPtGiven()).isEqualTo("Jane");
        assertThat(payload.getPtFamily()).isEqualTo("Smith");
        assertThat(payload.getPtId()).isEqualTo("456789");
    }

    @Test
    @DisplayName("parse recognises the header line whatever its letter case")
    void headerDetectionIgnoresCase() {
        // Had the first line not been taken as a header, it would be read as data with the default column order,
        // and pt_id (the 4th default column) would be empty instead of 456789.
        String csv = "Source_System,pt_id\nHOSPITAL_D,456789";

        HospitalDAdapter.HospitalDPayload payload = adapter.parse(csv);

        assertThat(payload.getPtId()).isEqualTo("456789");
    }

    @Test
    @DisplayName("parse uses the default column order when the file has no header line")
    void usesDefaultHeadersWithoutHeaderLine() {
        HospitalDAdapter.HospitalDPayload payload = adapter.parse(ROW);

        assertThat(payload.getPtId()).isEqualTo("456789");
        assertThat(payload.getPtGiven()).isEqualTo("Jane");
        assertThat(payload.getDxPrimary()).isEqualTo("G70.00");
        assertThat(payload.getClinicalNotes()).isEqualTo("Progressive weakness over 3 weeks.");
    }

    @Test
    @DisplayName("parse ignores blank lines and the whitespace around lines")
    void ignoresBlankLinesAndOuterWhitespace() {
        String csv = "\n  \n  " + HEADER + "  \n\n   " + ROW + "   \n\n";

        HospitalDAdapter.HospitalDPayload payload = adapter.parse(csv);

        assertThat(payload.getPtId()).isEqualTo("456789");
        assertThat(payload.getClinicalNotes()).isEqualTo("Progressive weakness over 3 weeks.");
    }

    @Test
    @DisplayName("parse reads CRLF line endings the same way")
    void parsesCrlfLineEndings() {
        HospitalDAdapter.HospitalDPayload payload = adapter.parse(HEADER + "\r\n" + ROW + "\r\n");

        assertThat(payload.getPtGiven()).isEqualTo("Jane");
        assertThat(payload.getDxExtra()).containsExactly("E11.9", "I10");
    }

    @Test
    @DisplayName("parse keeps commas that are inside quotes and removes the quotes")
    void keepsCommasInsideQuotes() {
        String csv = "source_system,pt_id,doc_full_name,clinical_note_blob\n"
                + "HOSPITAL_D,456789,\"Johnson, Emily\",\"Weak, tired, and dizzy\"";

        HospitalDAdapter.HospitalDPayload payload = adapter.parse(csv);

        assertThat(payload.getDocFullName()).isEqualTo("Johnson, Emily");
        assertThat(payload.getClinicalNotes()).isEqualTo("Weak, tired, and dizzy");
    }

    @Test
    @DisplayName("parse removes quotes only when the value both starts and ends with one")
    void keepsQuotesOfAValueThatIsNotFullyWrapped() {
        // The comma is inside quotes, so this is one value. It starts with a quote but ends with "n".
        String csv = "source_system,pt_given,pt_family\nHOSPITAL_D,\"Jane,O\"Brien";

        HospitalDAdapter.HospitalDPayload payload = adapter.parse(csv);

        assertThat(payload.getPtGiven()).isEqualTo("\"Jane,O\"Brien");
        assertThat(payload.getPtFamily()).isEmpty();
    }

    @Test
    @DisplayName("parse gives an empty value to a column the data line does not reach")
    void missingTrailingValuesBecomeEmpty() {
        String csv = "source_system,pt_id,pt_given,pt_family,dx_extra,med_hist_blob\nHOSPITAL_D,456789,Jane";

        HospitalDAdapter.HospitalDPayload payload = adapter.parse(csv);

        assertThat(payload.getPtId()).isEqualTo("456789");
        assertThat(payload.getPtFamily()).isEmpty();
        assertThat(payload.getDxExtra()).isEmpty();
        assertThat(payload.getMedicationHistory()).isEmpty();
    }

    @Test
    @DisplayName("parse gives null or empty lists for columns that are not in the file at all")
    void absentColumnsGiveNullFieldsAndEmptyLists() {
        HospitalDAdapter.HospitalDPayload payload = adapter.parse("source_system,pt_id\nHOSPITAL_D,456789");

        assertThat(payload.getPtGiven()).isNull();
        assertThat(payload.getClinicalNotes()).isNull();
        assertThat(payload.getDxExtra()).isEmpty();
        assertThat(payload.getMedicationHistory()).isEmpty();
    }

    @Test
    @DisplayName("parse splits the extra diagnoses on '|' and the medication history on ';', trimming each item")
    void splitsListColumnsAndDropsEmptyItems() {
        String csv = "source_system,dx_extra,med_hist_blob\n"
                + "HOSPITAL_D,\" E11.9 | |I10 ||\",\" Aspirin ;; Metformin ; \"";

        HospitalDAdapter.HospitalDPayload payload = adapter.parse(csv);

        assertThat(payload.getDxExtra()).containsExactly("E11.9", "I10");
        assertThat(payload.getMedicationHistory()).containsExactly("Aspirin", "Metformin");
    }

    @Test
    @DisplayName("parse reads only the first data line; later lines are ignored")
    void readsOnlyTheFirstDataLine() {
        String csv = HEADER + "\n" + ROW + "\n" + ROW.replace("Jane", "Other");

        HospitalDAdapter.HospitalDPayload payload = adapter.parse(csv);

        assertThat(payload.getPtGiven()).isEqualTo("Jane");
    }

    // ------------------------------------------------------------ transform

    @Test
    @DisplayName("transform moves every payload field to its place in the internal order")
    void transformsPayloadToInternalOrder() {
        HospitalDAdapter.HospitalDPayload payload = adapter.parse(HEADER + "\n" + ROW);

        InternalOrder order = adapter.transform(payload);

        assertThat(order.getPatient().getFirstName()).isEqualTo("Jane");
        assertThat(order.getPatient().getLastName()).isEqualTo("Smith");
        assertThat(order.getPatient().getMrn()).isEqualTo("456789");
        assertThat(order.getPatient().getDateOfBirth()).isEqualTo(LocalDate.of(1985, 3, 22));
        assertThat(order.getProvider().getName()).isEqualTo("Dr. Emily Johnson");
        assertThat(order.getProvider().getNpi()).isEqualTo("1122334455");
        assertThat(order.getMedication().getName()).isEqualTo("Privigen");
        assertThat(order.getDiagnosis().getPrimaryDiagnosis()).isEqualTo("G70.00");
        assertThat(order.getDiagnosis().getAdditionalDiagnoses()).containsExactly("E11.9", "I10");
    }

    // ------------------------------------------------------------- validate

    @Test
    @DisplayName("validate accepts a complete, well-formed order")
    void validateAcceptsAValidOrder() {
        InternalOrder order = adapter.transform(adapter.parse(HEADER + "\n" + ROW));

        assertThatCode(() -> adapter.validate(order)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validate rejects a bad MRN, NPI and ICD-10 code, reporting each field")
    void validateReportsEachInvalidField() {
        String row = ROW.replace("456789", "12AB").replace("1122334455", "123")
                .replace("G70.00", "not-a-code").replace("E11.9|I10", "E11.9|zzz");
        InternalOrder order = adapter.transform(adapter.parse(HEADER + "\n" + row));

        assertThatThrownBy(() -> adapter.validate(order))
                .isInstanceOfSatisfying(ConstraintViolationException.class, ex -> {
                    List<String> messages = ex.getConstraintViolations().stream()
                            .map(violation -> violation.getMessage()).toList();
                    assertThat(messages).containsExactlyInAnyOrder(
                            "patient.mrn must be exactly 6 digits",
                            "provider.npi must be exactly 10 digits",
                            "diagnosis.primaryDiagnosis must be a valid ICD-10 code",
                            "diagnosis.additionalDiagnoses item must be a valid ICD-10 code");
                });
    }

    @Test
    @DisplayName("validate rejects blank required fields")
    void validateRejectsBlankRequiredFields() {
        String row = ROW.replace("Jane", " ").replace("Privigen", "");
        InternalOrder order = adapter.transform(adapter.parse(HEADER + "\n" + row));

        assertThatThrownBy(() -> adapter.validate(order))
                .isInstanceOfSatisfying(ConstraintViolationException.class, ex -> {
                    List<String> messages = ex.getConstraintViolations().stream()
                            .map(violation -> violation.getMessage()).toList();
                    assertThat(messages).containsExactlyInAnyOrder(
                            "patient.firstName is required", "medication.name is required");
                });
    }
}

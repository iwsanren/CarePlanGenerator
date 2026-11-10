package com.page24.backend.intake;

import com.page24.backend.dto.CreateOrderRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure unit tests (no Spring, no mocks) for the PharmaCorp XML mapper: parse the XML, then map it. */
class PharmaCorpTest {

    private static final String XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <CareOrderRequest>
                <RequestMetadata><SourceSystem>PharmaCorp_Portal</SourceSystem></RequestMetadata>
                <PatientInformation>
                    <MedicalRecordNumber>345678</MedicalRecordNumber>
                    <PatientName><FirstName>Robert</FirstName><LastName>Williams</LastName></PatientName>
                    <DateOfBirth>1972-11-30</DateOfBirth>
                </PatientInformation>
                <PrescriberInformation>
                    <FullName>Dr. Michael Chen</FullName>
                    <NPINumber>5678901234</NPINumber>
                </PrescriberInformation>
                <DiagnosisList>
                    <PrimaryDiagnosis><ICDCode>G70.01</ICDCode></PrimaryDiagnosis>
                    <SecondaryDiagnoses>
                        <Diagnosis><ICDCode>I10</ICDCode></Diagnosis>
                        <Diagnosis><ICDCode>E78.5</ICDCode></Diagnosis>
                    </SecondaryDiagnoses>
                </DiagnosisList>
                <MedicationOrder><DrugName>Octagam</DrugName></MedicationOrder>
                <MedicationHistory>
                    <Medication>
                        <MedicationName>Pyridostigmine</MedicationName><Dosage>60 mg</Dosage>
                        <Route>Oral</Route><Frequency>Every 6 hours</Frequency>
                    </Medication>
                    <Medication>
                        <MedicationName>Prednisone</MedicationName><Dosage>15 mg</Dosage>
                        <Route>Oral</Route><Frequency>Once daily</Frequency>
                    </Medication>
                </MedicationHistory>
                <ClinicalDocumentation>
                    <NarrativeText>Acute exacerbation of known MG.</NarrativeText>
                </ClinicalDocumentation>
            </CareOrderRequest>
            """;

    private final PharmaCorp pharmaCorp = new PharmaCorp();

    @Test
    @DisplayName("parseInput reads every field of a complete PharmaCorp document")
    void parsesACompleteDocument() {
        PharmaCorp.PharmaCorpPayload payload = pharmaCorp.parseInput(XML);

        assertThat(payload.getSourceSystem()).isEqualTo("PharmaCorp_Portal");
        assertThat(payload.getMrn()).isEqualTo("345678");
        assertThat(payload.getFirstName()).isEqualTo("Robert");
        assertThat(payload.getLastName()).isEqualTo("Williams");
        assertThat(payload.getDob()).isEqualTo(LocalDate.of(1972, 11, 30));
        assertThat(payload.getProviderName()).isEqualTo("Dr. Michael Chen");
        assertThat(payload.getProviderNpi()).isEqualTo("5678901234");
        assertThat(payload.getPrimaryDiagnosis()).isEqualTo("G70.01");
        assertThat(payload.getSecondaryDiagnoses()).containsExactly("I10", "E78.5");
        assertThat(payload.getMedicationName()).isEqualTo("Octagam");
        assertThat(payload.getMedicationHistory())
                .containsExactly("Pyridostigmine 60 mg Oral Every 6 hours", "Prednisone 15 mg Oral Once daily");
        assertThat(payload.getClinicalNotes()).isEqualTo("Acute exacerbation of known MG.");
    }

    @Test
    @DisplayName("parseInput trims the whitespace around values and drops blank diagnosis codes")
    void trimsValuesAndSkipsBlankCodes() {
        String xml = XML
                .replace("<FirstName>Robert</FirstName>", "<FirstName>\n   Robert  \n</FirstName>")
                .replace("<DateOfBirth>1972-11-30</DateOfBirth>", "<DateOfBirth> 1972-11-30 </DateOfBirth>")
                .replace("<ICDCode>I10</ICDCode>", "<ICDCode>   </ICDCode>")
                .replace("<ICDCode>E78.5</ICDCode>", "<ICDCode>  E78.5 </ICDCode>");

        PharmaCorp.PharmaCorpPayload payload = pharmaCorp.parseInput(xml);

        assertThat(payload.getFirstName()).isEqualTo("Robert");
        assertThat(payload.getDob()).isEqualTo(LocalDate.of(1972, 11, 30));
        assertThat(payload.getSecondaryDiagnoses()).containsExactly("E78.5");
    }

    @Test
    @DisplayName("parseInput of a document with the wrong content gives empty text, no date and empty lists")
    void missingElementsGiveEmptyValues() {
        PharmaCorp.PharmaCorpPayload payload = pharmaCorp.parseInput("<Other><x>1</x></Other>");

        assertThat(payload.getMrn()).isEmpty();
        assertThat(payload.getFirstName()).isEmpty();
        assertThat(payload.getDob()).isNull();
        assertThat(payload.getPrimaryDiagnosis()).isEmpty();
        assertThat(payload.getSecondaryDiagnoses()).isEmpty();
        assertThat(payload.getMedicationHistory()).isEmpty();
    }

    @Test
    @DisplayName("parseInput writes a medication with missing parts without trailing spaces")
    void medicationWithMissingPartsHasNoTrailingSpaces() {
        String xml = "<CareOrderRequest><MedicationHistory><Medication>"
                + "<MedicationName>Aspirin</MedicationName><Dosage>81 mg</Dosage>"
                + "</Medication></MedicationHistory></CareOrderRequest>";

        PharmaCorp.PharmaCorpPayload payload = pharmaCorp.parseInput(xml);

        assertThat(payload.getMedicationHistory()).containsExactly("Aspirin 81 mg");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" is not valid XML")
    @ValueSource(strings = {"", "plain text", "<CareOrderRequest><PatientInformation></CareOrderRequest>"})
    @DisplayName("parseInput reports text that is not well-formed XML as an IllegalArgumentException")
    void rejectsXmlThatIsNotWellFormed(String xml) {
        assertThatThrownBy(() -> pharmaCorp.parseInput(xml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid XML payload");
    }

    @Test
    @DisplayName("parseInput refuses a document with a DOCTYPE, so external entities are never read")
    void refusesDoctype() {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE CareOrderRequest "
                + "[<!ENTITY secret SYSTEM \"file:///etc/passwd\">]>"
                + "<CareOrderRequest><PatientInformation><MedicalRecordNumber>&secret;</MedicalRecordNumber>"
                + "</PatientInformation></CareOrderRequest>";

        assertThatThrownBy(() -> pharmaCorp.parseInput(xml)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("toCreateOrderRequest maps every payload field onto the order request")
    void mapsPayloadToRequest() {
        PharmaCorp.PharmaCorpPayload payload = pharmaCorp.parseInput(XML);

        CreateOrderRequest request = pharmaCorp.toCreateOrderRequest(payload, true);

        assertThat(request.getPatientFirstName()).isEqualTo("Robert");
        assertThat(request.getPatientLastName()).isEqualTo("Williams");
        assertThat(request.getPatientMrn()).isEqualTo("345678");
        assertThat(request.getPatientDateOfBirth()).isEqualTo(LocalDate.of(1972, 11, 30));
        assertThat(request.getProviderName()).isEqualTo("Dr. Michael Chen");
        assertThat(request.getProviderNpi()).isEqualTo("5678901234");
        assertThat(request.getMedicationName()).isEqualTo("Octagam");
        assertThat(request.getPrimaryDiagnosis()).isEqualTo("G70.01");
        assertThat(request.getAdditionalDiagnoses()).containsExactly("I10", "E78.5");
        assertThat(request.getMedicationHistory())
                .containsExactly("Pyridostigmine 60 mg Oral Every 6 hours", "Prednisone 15 mg Oral Once daily");
        assertThat(request.getPatientRecords()).isEqualTo("Acute exacerbation of known MG.");
        assertThat(request.getConfirm()).isTrue();
    }

    @Test
    @DisplayName("Only an explicit true confirms: a missing or false flag is treated as false")
    void onlyTrueConfirms() {
        PharmaCorp.PharmaCorpPayload payload = pharmaCorp.parseInput(XML);

        assertThat(pharmaCorp.toCreateOrderRequest(payload, null).getConfirm()).isFalse();
        assertThat(pharmaCorp.toCreateOrderRequest(payload, false).getConfirm()).isFalse();
        assertThat(pharmaCorp.toCreateOrderRequest(payload, true).getConfirm()).isTrue();
    }

    @ParameterizedTest(name = "[{index}] date of birth \"{0}\"")
    @ValueSource(strings = {"15-01-1990", "1990/01/15", "01/15/1990", "not a date", "1990-13-01", "1990-02-30"})
    @DisplayName("A date of birth that is not a real yyyy-MM-dd date is refused as a bad request, not a server error")
    void refusesADateOfBirthThatIsNotARealIsoDate(String dateOfBirth) {
        String xml = XML.replace("1972-11-30", dateOfBirth);

        assertThatThrownBy(() -> pharmaCorp.parseInput(xml))
                .isInstanceOfSatisfying(IntakeParseException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVALID_DATE_OF_BIRTH");
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessage()).isEqualTo("Date of birth must be a real date in the format yyyy-MM-dd");
                    assertThat(ex.getDetail())
                            .isEqualTo(Map.of("field", "DateOfBirth", "expectedFormat", "yyyy-MM-dd"));
                });
    }
}

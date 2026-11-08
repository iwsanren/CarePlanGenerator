package com.page24.backend.service;

import com.page24.backend.dto.CreateOrderRequest;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.exception.ValidationError;
import com.page24.backend.intake.ClinicBAdapter;
import com.page24.backend.intake.HospitalDAdapter;
import com.page24.backend.intake.IntakeAdapterFactory;
import com.page24.backend.intake.IntakeParseException;
import com.page24.backend.intake.IntakeValidationException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link IntakeService} with the real adapters, the real factory and the real Bean Validation, and only the
 * order pipeline replaced by a mock. This checks the chain parse, transform, validate, map and hand over: what
 * reaches {@link OrderService#createOrder} is what the source sent, and nothing reaches it when the input is bad.
 */
class IntakeServiceTest {

    private static final String CLINIC_B_JSON = """
            {
              "pt": {"mrn": "234567", "fname": "Jane", "lname": "Smith", "dob": "03/22/1985"},
              "provider": {"name": "Dr. Emily Johnson", "npi_num": "0987654321"},
              "dx": {"primary": "G70.00", "secondary": ["E11.9", "I10"]},
              "rx": {"med_name": "Gamunex-C"},
              "med_hx": ["Metformin 500mg twice daily", "Aspirin 81mg once daily"],
              "clinical_notes": "Progressive weakness over 3 weeks."
            }
            """;

    private static final String HOSPITAL_D_CSV = "source_system,pt_id,pt_given,pt_family,pt_dob_yyyymmdd,doc_npi,"
            + "doc_full_name,drug_label,dx_primary,dx_extra,med_hist_blob,clinical_note_blob\n"
            + "HOSPITAL_D,456789,Robert,Williams,19721130,1122334455,Dr. Michael Chen,Privigen,G70.01,"
            + "I10|E78.5,Pyridostigmine 60 mg;Prednisone 15 mg,Acute exacerbation of known MG.";

    private static final String PHARMA_XML = """
            <CareOrderRequest>
                <PatientInformation>
                    <MedicalRecordNumber>345678</MedicalRecordNumber>
                    <PatientName><FirstName>Robert</FirstName><LastName>Williams</LastName></PatientName>
                    <DateOfBirth>1972-11-30</DateOfBirth>
                </PatientInformation>
                <PrescriberInformation><FullName>Dr. Michael Chen</FullName><NPINumber>5678901234</NPINumber>
                </PrescriberInformation>
                <DiagnosisList>
                    <PrimaryDiagnosis><ICDCode>G70.01</ICDCode></PrimaryDiagnosis>
                    <SecondaryDiagnoses><Diagnosis><ICDCode>I10</ICDCode></Diagnosis></SecondaryDiagnoses>
                </DiagnosisList>
                <MedicationOrder><DrugName>Octagam</DrugName></MedicationOrder>
                <MedicationHistory><Medication><MedicationName>Prednisone</MedicationName><Dosage>15 mg</Dosage>
                </Medication></MedicationHistory>
                <ClinicalDocumentation><NarrativeText>Acute exacerbation.</NarrativeText></ClinicalDocumentation>
            </CareOrderRequest>
            """;

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    private OrderService orderService;
    private OrderResponse orderResponse;
    private IntakeService intakeService;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        orderResponse = new OrderResponse();
        orderResponse.setId(99L);
        when(orderService.createOrder(any())).thenReturn(orderResponse);

        IntakeAdapterFactory factory = new IntakeAdapterFactory(
                List.of(new ClinicBAdapter(validator), new HospitalDAdapter(validator)));
        intakeService = new IntakeService(validator, orderService, factory);
    }

    private CreateOrderRequest handedOverRequest() {
        ArgumentCaptor<CreateOrderRequest> captor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(orderService).createOrder(captor.capture());
        return captor.getValue();
    }

    // -------------------------------------------------------------- Clinic B

    @Test
    @DisplayName("Clinic B: a valid JSON is mapped field by field and handed to the order pipeline")
    void clinicBHandsOverTheMappedRequest() {
        OrderResponse result = intakeService.createFromClinicBJson(CLINIC_B_JSON, true);

        assertThat(result).isSameAs(orderResponse);
        CreateOrderRequest request = handedOverRequest();
        assertThat(request.getPatientFirstName()).isEqualTo("Jane");
        assertThat(request.getPatientLastName()).isEqualTo("Smith");
        assertThat(request.getPatientMrn()).isEqualTo("234567");
        assertThat(request.getPatientDateOfBirth()).isEqualTo(LocalDate.of(1985, 3, 22));
        assertThat(request.getProviderName()).isEqualTo("Dr. Emily Johnson");
        assertThat(request.getProviderNpi()).isEqualTo("0987654321");
        assertThat(request.getMedicationName()).isEqualTo("Gamunex-C");
        assertThat(request.getPrimaryDiagnosis()).isEqualTo("G70.00");
        assertThat(request.getAdditionalDiagnoses()).containsExactly("E11.9", "I10");
        assertThat(request.getMedicationHistory())
                .containsExactly("Metformin 500mg twice daily", "Aspirin 81mg once daily");
        assertThat(request.getPatientRecords()).isEqualTo("Progressive weakness over 3 weeks.");
        assertThat(request.getConfirm()).isTrue();
    }

    @Test
    @DisplayName("Clinic B: only an explicit true confirms, a missing or false flag becomes false")
    void clinicBConfirmDefaultsToFalse() {
        intakeService.createFromClinicBJson(CLINIC_B_JSON, null);
        intakeService.createFromClinicBJson(CLINIC_B_JSON, false);

        ArgumentCaptor<CreateOrderRequest> captor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(orderService, org.mockito.Mockito.times(2)).createOrder(captor.capture());
        assertThat(captor.getAllValues()).extracting(CreateOrderRequest::getConfirm).containsExactly(false, false);
    }

    @Test
    @DisplayName("Clinic B: JSON that cannot be read is a parse error and nothing is created")
    void clinicBRejectsUnreadableJson() {
        Throwable thrown = catchThrowable(() -> intakeService.createFromClinicBJson("{not json", true));

        assertThat(thrown).isInstanceOf(IntakeParseException.class);
        verify(orderService, never()).createOrder(any());
    }

    @Test
    @DisplayName("Clinic B: a field in the wrong format fails validation and nothing is created")
    void clinicBRejectsAnInvalidField() {
        Throwable thrown = catchThrowable(
                () -> intakeService.createFromClinicBJson(CLINIC_B_JSON.replace("0987654321", "123"), true));

        assertThat(thrown).isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("provider.npi must be exactly 10 digits");
        verify(orderService, never()).createOrder(any());
    }

    @Test
    @DisplayName("Clinic B: the order request is validated again after mapping, so an overlong history entry is refused")
    void clinicBValidatesTheMappedRequest() {
        String longEntry = "x".repeat(501);

        Throwable thrown = catchThrowable(() -> intakeService.createFromClinicBJson(
                CLINIC_B_JSON.replace("Aspirin 81mg once daily", longEntry), true));

        assertThat(thrown).isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("each medicationHistory entry must be at most 500 characters");
        verify(orderService, never()).createOrder(any());
    }

    // ------------------------------------------------------------ Hospital D

    @Test
    @DisplayName("Hospital D: a valid CSV is mapped field by field and handed to the order pipeline")
    void hospitalDHandsOverTheMappedRequest() {
        OrderResponse result = intakeService.createFromHospitalDCsv(HOSPITAL_D_CSV, true);

        assertThat(result).isSameAs(orderResponse);
        CreateOrderRequest request = handedOverRequest();
        assertThat(request.getPatientFirstName()).isEqualTo("Robert");
        assertThat(request.getPatientLastName()).isEqualTo("Williams");
        assertThat(request.getPatientMrn()).isEqualTo("456789");
        assertThat(request.getPatientDateOfBirth()).isEqualTo(LocalDate.of(1972, 11, 30));
        assertThat(request.getProviderName()).isEqualTo("Dr. Michael Chen");
        assertThat(request.getProviderNpi()).isEqualTo("1122334455");
        assertThat(request.getMedicationName()).isEqualTo("Privigen");
        assertThat(request.getPrimaryDiagnosis()).isEqualTo("G70.01");
        assertThat(request.getAdditionalDiagnoses()).containsExactly("I10", "E78.5");
        assertThat(request.getMedicationHistory()).containsExactly("Pyridostigmine 60 mg", "Prednisone 15 mg");
        assertThat(request.getPatientRecords()).isEqualTo("Acute exacerbation of known MG.");
        assertThat(request.getConfirm()).isTrue();
    }

    @Test
    @DisplayName("Hospital D: the confirm flag is passed on as given")
    void hospitalDPassesTheConfirmFlagOn() {
        intakeService.createFromHospitalDCsv(HOSPITAL_D_CSV, false);

        assertThat(handedOverRequest().getConfirm()).isFalse();
    }

    @Test
    @DisplayName("Hospital D: a field in the wrong format fails validation and nothing is created")
    void hospitalDRejectsAnInvalidField() {
        Throwable thrown = catchThrowable(
                () -> intakeService.createFromHospitalDCsv(HOSPITAL_D_CSV.replace("456789", "12AB"), true));

        assertThat(thrown).isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("patient.mrn must be exactly 6 digits");
        verify(orderService, never()).createOrder(any());
    }

    @Test
    @DisplayName("Hospital D: the order request is validated again after mapping, so an overlong history entry is refused")
    void hospitalDValidatesTheMappedRequest() {
        Throwable thrown = catchThrowable(() -> intakeService.createFromHospitalDCsv(
                HOSPITAL_D_CSV.replace("Prednisone 15 mg", "x".repeat(501)), true));

        assertThat(thrown).isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("each medicationHistory entry must be at most 500 characters");
        verify(orderService, never()).createOrder(any());
    }

    // ------------------------------------------------------------ PharmaCorp

    @Test
    @DisplayName("PharmaCorp: a valid XML is mapped field by field and handed to the order pipeline")
    void pharmaCorpHandsOverTheMappedRequest() {
        OrderResponse result = intakeService.createFromPharmaCorpXml(PHARMA_XML, true);

        assertThat(result).isSameAs(orderResponse);
        CreateOrderRequest request = handedOverRequest();
        assertThat(request.getPatientFirstName()).isEqualTo("Robert");
        assertThat(request.getPatientLastName()).isEqualTo("Williams");
        assertThat(request.getPatientMrn()).isEqualTo("345678");
        assertThat(request.getPatientDateOfBirth()).isEqualTo(LocalDate.of(1972, 11, 30));
        assertThat(request.getProviderNpi()).isEqualTo("5678901234");
        assertThat(request.getMedicationName()).isEqualTo("Octagam");
        assertThat(request.getPrimaryDiagnosis()).isEqualTo("G70.01");
        assertThat(request.getAdditionalDiagnoses()).containsExactly("I10");
        assertThat(request.getMedicationHistory()).containsExactly("Prednisone 15 mg");
        assertThat(request.getPatientRecords()).isEqualTo("Acute exacerbation.");
        assertThat(request.getConfirm()).isTrue();
    }

    @Test
    @DisplayName("PharmaCorp: a missing confirm flag becomes false")
    void pharmaCorpConfirmDefaultsToFalse() {
        intakeService.createFromPharmaCorpXml(PHARMA_XML, null);

        assertThat(handedOverRequest().getConfirm()).isFalse();
    }

    @Test
    @DisplayName("PharmaCorp: text that is not XML becomes a 400 INVALID_PHARMA_XML and nothing is created")
    void pharmaCorpRejectsMalformedXml() {
        Throwable thrown = catchThrowable(() -> intakeService.createFromPharmaCorpXml("<broken>", true));

        assertThat(thrown).isInstanceOfSatisfying(ValidationError.class, ex -> {
            assertThat(ex.getCode()).isEqualTo("INVALID_PHARMA_XML");
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(ex.getMessage()).isEqualTo("PharmaCorp XML payload is invalid");
        });
        verify(orderService, never()).createOrder(any());
    }

    @Test
    @DisplayName("PharmaCorp: the mapped request is validated, so a bad NPI is refused and nothing is created")
    void pharmaCorpValidatesTheMappedRequest() {
        Throwable thrown = catchThrowable(
                () -> intakeService.createFromPharmaCorpXml(PHARMA_XML.replace("5678901234", "123"), true));

        assertThat(thrown).isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("NPI must be exactly 10 digits");
        verify(orderService, never()).createOrder(any());
    }

    // ----------------------------------------------------- adapter registry

    @Test
    @DisplayName("A source whose adapter is not the expected type is reported as a type mismatch")
    void reportsAnAdapterOfTheWrongType() {
        // "hospital-d" is registered, but with the Clinic B adapter instance, which the Hospital D path cannot use.
        IntakeAdapterFactory wrongFactory = new IntakeAdapterFactory(List.of(new ClinicBAdapter(validator) {
            @Override
            public String source() {
                return "hospital-d";
            }
        }));
        IntakeService service = new IntakeService(validator, orderService, wrongFactory);

        Throwable thrown = catchThrowable(() -> service.createFromHospitalDCsv(HOSPITAL_D_CSV, true));

        assertThat(thrown).isInstanceOfSatisfying(IntakeValidationException.class,
                ex -> assertThat(ex.getCode()).isEqualTo("INTAKE_ADAPTER_TYPE_MISMATCH"));
        verify(orderService, never()).createOrder(any());
    }

    @Test
    @DisplayName("A source with no registered adapter is reported as unsupported")
    void reportsAMissingAdapter() {
        IntakeService service = new IntakeService(validator, orderService, new IntakeAdapterFactory(List.of()));

        Throwable thrown = catchThrowable(() -> service.createFromClinicBJson(CLINIC_B_JSON, true));

        assertThat(thrown).isInstanceOfSatisfying(IntakeValidationException.class,
                ex -> assertThat(ex.getCode()).isEqualTo("UNSUPPORTED_INTAKE_SOURCE"));
        verify(orderService, never()).createOrder(any());
    }
}

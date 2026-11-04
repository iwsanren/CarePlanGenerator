package com.page24.backend.controller;

import com.page24.backend.entity.Patient;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.repository.MedicationHistoryRepository;
import com.page24.backend.repository.OrderRepository;
import com.page24.backend.repository.PatientDiagnosisRepository;
import com.page24.backend.repository.PatientRepository;
import com.page24.backend.repository.ProviderRepository;
import com.page24.backend.service.DataInitializationService;
import com.page24.backend.service.QueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Writing patients: the duplicate rules when creating, and which fields PUT changes. Every rejected request is
 * followed by a read of the stored patient, and a field-by-field update checks that all other fields stayed put.
 */
@WithMockUser(username = "test-pharmacist", roles = "PHARMACIST")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PatientControllerWriteIntegrationTest {

    private static final LocalDate DOB = LocalDate.of(1979, 6, 8);

    @Autowired private MockMvc mockMvc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private CarePlanRepository carePlanRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private MedicationHistoryRepository medicationHistoryRepository;
    @Autowired private PatientDiagnosisRepository patientDiagnosisRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private ProviderRepository providerRepository;

    @MockitoBean private QueueService queueService;
    @MockitoBean private DataInitializationService dataInitializationService;

    private Patient john;
    private Map<String, Object> johnBefore;

    @BeforeEach
    void cleanDatabaseAndCreateJohn() {
        carePlanRepository.deleteAll();
        orderRepository.deleteAll();
        medicationHistoryRepository.deleteAll();
        patientDiagnosisRepository.deleteAll();
        patientRepository.deleteAll();
        providerRepository.deleteAll();

        Patient patient = new Patient();
        patient.setFirstName("John");
        patient.setLastName("Smith");
        patient.setMrn("001234");
        patient.setDateOfBirth(DOB);
        patient.setSex("Female");
        patient.setWeightKg(72.0);
        patient.setAllergies("None known");
        patient.setPrimaryDiagnosis("G70.00");
        patient.setPrimaryDiagnosisDescription("Myasthenia gravis without acute exacerbation");
        patient.setAdditionalDiagnoses(new ArrayList<>(List.of("I10", "K21.0")));
        john = patientRepository.saveAndFlush(patient);
        johnBefore = stored(john.getId());
    }

    // ----- POST: duplicates -----

    @Test
    @DisplayName("POST /api/v1/patients - an MRN that already exists returns 409 and creates nothing")
    void shouldRejectAnMrnThatAlreadyExists() throws Exception {
        mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("Jane", "Doe", "001234", "1990-01-01")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.warning").value("A patient with the same MRN already exists"))
                .andExpect(jsonPath("$.existing_patient_id").value(john.getId()));

        assertThat(patientRepository.count()).isEqualTo(1);
        assertThat(stored(john.getId())).isEqualTo(johnBefore);
    }

    @Test
    @DisplayName("POST /api/v1/patients - same name and date of birth is reported first, even when the MRN also exists")
    void shouldReportTheNameAndDateOfBirthDuplicateBeforeTheMrnDuplicate() throws Exception {
        mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("John", "Smith", "001234", "1979-06-08")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.warning").value("A patient with the same name and date of birth already exists"))
                .andExpect(jsonPath("$.existing_patient_id").value(john.getId()));

        assertThat(patientRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /api/v1/patients - the name comparison ignores case")
    void shouldTreatTheNameAsCaseInsensitiveWhenLookingForDuplicates() throws Exception {
        mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("john", "SMITH", "009999", "1979-06-08")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existing_patient_id").value(john.getId()));

        assertThat(patientRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /api/v1/patients - same name with a different date of birth and MRN is a different patient")
    void shouldAllowTheSameNameWithADifferentDateOfBirth() throws Exception {
        mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("John", "Smith", "009999", "1985-03-04")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mrn").value("009999"));

        assertThat(patientRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("POST /api/v1/patients - malformed JSON returns a fixed 400 body and creates nothing")
    void shouldRejectMalformedJsonOnCreate() throws Exception {
        mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"firstName\": \"Eve\", "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.details.body").value("Malformed or unreadable JSON request body"));

        assertThat(patientRepository.count()).isEqualTo(1);
    }

    // ----- PUT: one field at a time -----

    @Test
    @DisplayName("PUT /api/v1/patients/{id} - only first_name changes when only first_name is sent")
    void shouldUpdateOnlyTheFirstName() throws Exception {
        updateOk(john.getId(), """
                { "firstName": "Jonathan" }
                """);

        assertOnlyChanged("firstName", "Jonathan");
    }

    @Test
    @DisplayName("PUT /api/v1/patients/{id} - only last_name changes when only last_name is sent")
    void shouldUpdateOnlyTheLastName() throws Exception {
        updateOk(john.getId(), """
                { "lastName": "Smyth" }
                """);

        assertOnlyChanged("lastName", "Smyth");
    }

    @Test
    @DisplayName("PUT /api/v1/patients/{id} - only the date of birth changes when only it is sent")
    void shouldUpdateOnlyTheDateOfBirth() throws Exception {
        updateOk(john.getId(), """
                { "dateOfBirth": "1980-01-02" }
                """);

        assertOnlyChanged("dateOfBirth", LocalDate.of(1980, 1, 2));
    }

    @Test
    @DisplayName("PUT /api/v1/patients/{id} - only sex changes when only sex is sent")
    void shouldUpdateOnlyTheSex() throws Exception {
        updateOk(john.getId(), """
                { "sex": "Male" }
                """);

        assertOnlyChanged("sex", "Male");
    }

    @Test
    @DisplayName("PUT /api/v1/patients/{id} - only the primary diagnosis changes when only it is sent")
    void shouldUpdateOnlyThePrimaryDiagnosis() throws Exception {
        updateOk(john.getId(), """
                { "primaryDiagnosis": "E11.9" }
                """);

        assertOnlyChanged("primaryDiagnosis", "E11.9");
    }

    @Test
    @DisplayName("PUT /api/v1/patients/{id} - the additional diagnoses are replaced as a whole, in the order sent")
    void shouldReplaceTheAdditionalDiagnoses() throws Exception {
        updateOk(john.getId(), """
                { "additionalDiagnoses": ["E11.9", "I10"] }
                """);

        assertOnlyChanged("additionalDiagnoses", List.of("E11.9", "I10"));
    }

    @Test
    @DisplayName("PUT /api/v1/patients/{id} - an empty list clears the additional diagnoses")
    void shouldClearTheAdditionalDiagnosesWithAnEmptyList() throws Exception {
        updateOk(john.getId(), """
                { "additionalDiagnoses": [] }
                """);

        assertOnlyChanged("additionalDiagnoses", List.of());
    }

    @Test
    @DisplayName("PUT /api/v1/patients/{id} - null or missing fields mean 'leave as is', so null cannot clear a field")
    void shouldTreatNullAsNotProvided() throws Exception {
        updateOk(john.getId(), """
                { "sex": null, "allergies": null, "additionalDiagnoses": null }
                """);

        assertThat(stored(john.getId())).isEqualTo(johnBefore);
    }

    @Test
    @DisplayName("PUT /api/v1/patients/{id} - an empty body changes nothing")
    void shouldChangeNothingForAnEmptyBody() throws Exception {
        updateOk(john.getId(), "{}");

        assertThat(stored(john.getId())).isEqualTo(johnBefore);
    }

    // ----- PUT: validation and errors -----

    @Test
    @DisplayName("PUT /api/v1/patients/{id} - blank first_name and last_name return 400 with API field names")
    void shouldRejectBlankNames() throws Exception {
        mockMvc.perform(put("/api/v1/patients/{id}", john.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "firstName": "  ", "lastName": "" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.details.first_name").value("first_name cannot be blank"))
                .andExpect(jsonPath("$.details.last_name").value("last_name cannot be blank"));

        assertThat(stored(john.getId())).isEqualTo(johnBefore);
    }

    @Test
    @DisplayName("PUT /api/v1/patients/{id} - invalid ICD-10 codes return 400 and nothing is stored")
    void shouldRejectInvalidDiagnosisCodes() throws Exception {
        mockMvc.perform(put("/api/v1/patients/{id}", john.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "firstName": "Should not be saved",
                                  "primaryDiagnosis": "not-a-code",
                                  "additionalDiagnoses": ["I10", "XYZ"] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.primary_diagnosis").value("primary_diagnosis must be a valid ICD-10 code"))
                .andExpect(jsonPath("$.details['additional_diagnoses[1]']")
                        .value("additional_diagnoses must contain valid ICD-10 codes"));

        assertThat(stored(john.getId())).isEqualTo(johnBefore);
    }

    @Test
    @DisplayName("PUT /api/v1/patients/{id} - malformed JSON returns a fixed 400 body and changes nothing")
    void shouldRejectMalformedJsonOnUpdate() throws Exception {
        mockMvc.perform(put("/api/v1/patients/{id}", john.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"firstName\": \"Eve\" "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.details.body").value("Malformed or unreadable JSON request body"));

        assertThat(stored(john.getId())).isEqualTo(johnBefore);
    }

    // ----- permissions -----

    @Test
    @WithMockUser(username = "test-assistant", roles = "ASSISTANT")
    @DisplayName("ASSISTANT can create and update patients but still cannot delete one")
    void shouldLetAnAssistantCreateAndUpdateButNotDelete() throws Exception {
        mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("Jane", "Doe", "009999", "1990-01-01")))
                .andExpect(status().isCreated());
        updateOk(john.getId(), """
                { "sex": "Male" }
                """);
        assertOnlyChanged("sex", "Male");

        mockMvc.perform(delete("/api/v1/patients/{id}", john.getId()))
                .andExpect(status().isForbidden());
        assertThat(patientRepository.existsById(john.getId())).isTrue();
        assertThat(patientRepository.count()).isEqualTo(2);
    }

    // ----- helpers -----

    private void updateOk(Long id, String body) throws Exception {
        mockMvc.perform(put("/api/v1/patients/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.mrn").value("001234"))
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    /** The stored patient must equal the original except for one field. */
    private void assertOnlyChanged(String field, Object newValue) {
        Map<String, Object> expected = new HashMap<>(johnBefore);
        expected.put(field, newValue);
        assertThat(stored(john.getId())).isEqualTo(expected);
    }

    /** Reads the patient in its own transaction, so the result comes from the database and not from a cache. */
    private Map<String, Object> stored(Long id) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Patient patient = patientRepository.findById(id).orElseThrow();
            Map<String, Object> fields = new HashMap<>();
            fields.put("firstName", patient.getFirstName());
            fields.put("lastName", patient.getLastName());
            fields.put("mrn", patient.getMrn());
            fields.put("dateOfBirth", patient.getDateOfBirth());
            fields.put("sex", patient.getSex());
            fields.put("weightKg", patient.getWeightKg());
            fields.put("allergies", patient.getAllergies());
            fields.put("primaryDiagnosis", patient.getPrimaryDiagnosis());
            fields.put("primaryDiagnosisDescription", patient.getPrimaryDiagnosisDescription());
            fields.put("additionalDiagnoses", new ArrayList<>(patient.getAdditionalDiagnoses()));
            return fields;
        });
    }

    private String createBody(String firstName, String lastName, String mrn, String dateOfBirth) {
        return """
                {
                  "firstName": "%s",
                  "lastName": "%s",
                  "mrn": "%s",
                  "dateOfBirth": "%s",
                  "primaryDiagnosis": "G70.00"
                }
                """.formatted(firstName, lastName, mrn, dateOfBirth);
    }
}

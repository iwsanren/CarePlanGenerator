package com.page24.backend.controller;

import com.page24.backend.entity.Patient;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.repository.OrderRepository;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PatientControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CarePlanRepository carePlanRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private ProviderRepository providerRepository;

    @MockitoBean
    private QueueService queueService;

    @MockitoBean
    private DataInitializationService dataInitializationService;

    @BeforeEach
    void cleanDatabase() {
        carePlanRepository.deleteAll();
        orderRepository.deleteAll();
        patientRepository.deleteAll();
        providerRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /patients - creates patient with 201")
    void shouldCreatePatient() throws Exception {
        mockMvc.perform(post("/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("001234")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.first_name").value("John"))
                .andExpect(jsonPath("$.last_name").value("Smith"))
                .andExpect(jsonPath("$.mrn").value("001234"))
                .andExpect(jsonPath("$.date_of_birth").value("1979-06-08"))
                .andExpect(jsonPath("$.sex").value("Female"))
                .andExpect(jsonPath("$.weight_kg").value(72.0))
                .andExpect(jsonPath("$.allergies").value("None known"))
                .andExpect(jsonPath("$.primary_diagnosis").value("G70.00"))
                .andExpect(jsonPath("$.additional_diagnoses[0]").value("I10"))
                .andExpect(jsonPath("$.additional_diagnoses[1]").value("K21.0"))
                .andExpect(jsonPath("$.created_at").exists());
    }

    @Test
    @DisplayName("POST /patients - invalid MRN returns 400")
    void shouldRejectInvalidMrn() throws Exception {
        mockMvc.perform(post("/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("1234")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.details.mrn").value("MRN must be exactly 6 digits"));
    }

    @Test
    @DisplayName("POST /patients - non-positive weight returns 400")
    void shouldRejectNonPositiveWeight() throws Exception {
        mockMvc.perform(post("/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("001234", 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.details.weight_kg").value("weight_kg must be greater than 0"));
    }

    @Test
    @DisplayName("POST /patients - duplicate name and DOB returns 409")
    void shouldReturnConflictForDuplicateNameAndDob() throws Exception {
        Patient existingPatient = new Patient();
        existingPatient.setFirstName("John");
        existingPatient.setLastName("Smith");
        existingPatient.setMrn("009999");
        existingPatient.setDateOfBirth(LocalDate.of(1979, 6, 8));
        existingPatient.setSex("Female");
        existingPatient.setWeightKg(72.0);
        existingPatient.setAllergies("None known");
        existingPatient.setPrimaryDiagnosis("G70.00");
        existingPatient.setAdditionalDiagnoses(List.of("I10"));
        existingPatient = patientRepository.save(existingPatient);

        mockMvc.perform(post("/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("001234")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.warning").value("A patient with the same name and date of birth already exists"))
                .andExpect(jsonPath("$.existing_patient_id").value(existingPatient.getId()));
    }

    private String validRequestJson(String mrn) {
        return requestJson(mrn, 72);
    }

    private String requestJson(String mrn, int weightKg) {
        return String.format("""
                {
                  "first_name": "John",
                  "last_name": "Smith",
                  "mrn": "%s",
                  "date_of_birth": "1979-06-08",
                  "sex": "Female",
                  "weight_kg": %d,
                  "allergies": "None known",
                  "primary_diagnosis": "G70.00",
                  "additional_diagnoses": ["I10", "K21.0"]
                }
                """, mrn, weightKg);
    }
}

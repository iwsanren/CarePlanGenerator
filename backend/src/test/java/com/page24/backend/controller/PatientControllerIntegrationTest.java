package com.page24.backend.controller;

import com.page24.backend.entity.Patient;
import com.page24.backend.entity.Provider;
import com.page24.backend.entity.Order;
import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.MedicationHistory;
import com.page24.backend.entity.PatientDiagnosis;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    private PatientDiagnosisRepository patientDiagnosisRepository;

    @Autowired
    private MedicationHistoryRepository medicationHistoryRepository;

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
        medicationHistoryRepository.deleteAll();
        patientDiagnosisRepository.deleteAll();
        patientRepository.deleteAll();
        providerRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /api/v1/patients - accepts optional demographics and returns reference field names")
    void shouldCreatePatientWithOptionalDemographics() throws Exception {
        mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("001234")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.firstName").value("John"))
                .andExpect(jsonPath("$.lastName").value("Smith"))
                .andExpect(jsonPath("$.mrn").value("001234"))
                .andExpect(jsonPath("$.dateOfBirth").value("1979-06-08"))
                .andExpect(jsonPath("$.sex").value("Female"))
                .andExpect(jsonPath("$.weightKg").value(72.0))
                .andExpect(jsonPath("$.allergies").value("None known"))
                .andExpect(jsonPath("$.primaryDiagnosis").value("G70.00"))
                .andExpect(jsonPath("$.primaryDiagnosisDescription")
                        .value("Myasthenia gravis without acute exacerbation"))
                .andExpect(jsonPath("$.additionalDiagnoses[0]").value("I10"))
                .andExpect(jsonPath("$.additionalDiagnoses[1]").value("K21.0"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    @DisplayName("POST /api/v1/patients - creates patient from minimal reference-compatible request")
    void shouldCreatePatientFromMinimalReferenceCompatibleRequest() throws Exception {
        mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mrn": "001234",
                                  "firstName": "John",
                                  "lastName": "Smith",
                                  "primaryDiagnosis": "G70.00"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.mrn").value("001234"))
                .andExpect(jsonPath("$.firstName").value("John"))
                .andExpect(jsonPath("$.lastName").value("Smith"))
                .andExpect(jsonPath("$.primaryDiagnosis").value("G70.00"))
                .andExpect(jsonPath("$.primaryDiagnosisDescription").isEmpty())
                .andExpect(jsonPath("$.dateOfBirth").isEmpty())
                .andExpect(jsonPath("$.sex").isEmpty())
                .andExpect(jsonPath("$.weightKg").isEmpty())
                .andExpect(jsonPath("$.allergies").isEmpty());

        Patient savedPatient = patientRepository.findByMrn("001234").orElseThrow();
        org.junit.jupiter.api.Assertions.assertNull(savedPatient.getDateOfBirth());
        org.junit.jupiter.api.Assertions.assertNull(savedPatient.getWeightKg());
        org.junit.jupiter.api.Assertions.assertEquals("G70.00", savedPatient.getPrimaryDiagnosis());
    }

    @Test
    @DisplayName("POST /api/v1/patients - keeps legacy primary_diagnosis request compatibility")
    void shouldAcceptLegacyPrimaryDiagnosisRequestField() throws Exception {
        mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mrn": "001234",
                                  "firstName": "John",
                                  "lastName": "Smith",
                                  "primary_diagnosis": "G70.00"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.primaryDiagnosis").value("G70.00"));
    }

    @Test
    @DisplayName("POST /api/v1/patients - invalid MRN returns 400")
    void shouldRejectInvalidMrn() throws Exception {
        mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("1234")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.details.mrn").value("MRN must be exactly 6 digits"));
    }

    @Test
    @DisplayName("POST /api/v1/patients - non-positive weight returns 400")
    void shouldRejectNonPositiveWeight() throws Exception {
        mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("001234", 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.details.weight_kg").value("weight_kg must be greater than 0"));
    }

    @Test
    @DisplayName("POST /api/v1/patients - duplicate name and DOB returns 409")
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

        mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("001234")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.warning").value("A patient with the same name and date of birth already exists"))
                .andExpect(jsonPath("$.existing_patient_id").value(existingPatient.getId()));
    }

    @Test
    @DisplayName("GET /api/v1/patients/{id} - returns the reference-compatible detail representation")
    void shouldGetPatientById() throws Exception {
        Patient patient = new Patient();
        patient.setFirstName("John");
        patient.setLastName("Smith");
        patient.setMrn("001234");
        patient.setDateOfBirth(LocalDate.of(1979, 6, 8));
        patient.setSex("Female");
        patient.setWeightKg(72.0);
        patient.setAllergies("None known");
        patient.setPrimaryDiagnosis("G70.00");
        patient.setPrimaryDiagnosisDescription("Myasthenia gravis without acute exacerbation");
        patient.setAdditionalDiagnoses(new ArrayList<>(List.of("I10", "K21.0")));
        patient = patientRepository.save(patient);

        PatientDiagnosis primaryDiagnosis = new PatientDiagnosis();
        primaryDiagnosis.setPatient(patient);
        primaryDiagnosis.setIcd10Code("G70.00");
        primaryDiagnosis.setDescription("Myasthenia gravis without acute exacerbation");
        primaryDiagnosis.setPrimary(true);
        primaryDiagnosis = patientDiagnosisRepository.save(primaryDiagnosis);

        PatientDiagnosis secondaryDiagnosis = new PatientDiagnosis();
        secondaryDiagnosis.setPatient(patient);
        secondaryDiagnosis.setIcd10Code("I10");
        secondaryDiagnosis.setDescription("Essential hypertension");
        secondaryDiagnosis.setPrimary(false);
        patientDiagnosisRepository.save(secondaryDiagnosis);

        MedicationHistory medicationHistory = new MedicationHistory();
        medicationHistory.setPatient(patient);
        medicationHistory.setMedicationName("Pyridostigmine");
        medicationHistory.setDosage("60 mg");
        medicationHistory.setFrequency("PO q6h PRN");
        medicationHistory.setCurrent(true);
        medicationHistory = medicationHistoryRepository.save(medicationHistory);

        mockMvc.perform(get("/api/v1/patients/{id}", patient.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(patient.getId()))
                .andExpect(jsonPath("$.firstName").value("John"))
                .andExpect(jsonPath("$.lastName").value("Smith"))
                .andExpect(jsonPath("$.fullName").value("John Smith"))
                .andExpect(jsonPath("$.mrn").value("001234"))
                .andExpect(jsonPath("$.dateOfBirth").value("1979-06-08"))
                .andExpect(jsonPath("$.sex").value("Female"))
                .andExpect(jsonPath("$.weightKg").value(72.0))
                .andExpect(jsonPath("$.allergies").value("None known"))
                .andExpect(jsonPath("$.primaryDiagnosis").value("G70.00"))
                .andExpect(jsonPath("$.primaryDiagnosisDescription")
                        .value("Myasthenia gravis without acute exacerbation"))
                .andExpect(jsonPath("$.diagnoses.length()").value(2))
                .andExpect(jsonPath("$.diagnoses[0].id").value(primaryDiagnosis.getId()))
                .andExpect(jsonPath("$.diagnoses[0].icd10Code").value("G70.00"))
                .andExpect(jsonPath("$.diagnoses[0].description")
                        .value("Myasthenia gravis without acute exacerbation"))
                .andExpect(jsonPath("$.diagnoses[0].isPrimary").value(true))
                .andExpect(jsonPath("$.diagnoses[0].createdAt").exists())
                .andExpect(jsonPath("$.diagnoses[0].length()").value(5))
                .andExpect(jsonPath("$.medicationHistory[0].id").value(medicationHistory.getId()))
                .andExpect(jsonPath("$.medicationHistory[0].medicationName").value("Pyridostigmine"))
                .andExpect(jsonPath("$.medicationHistory[0].dosage").value("60 mg"))
                .andExpect(jsonPath("$.medicationHistory[0].frequency").value("PO q6h PRN"))
                .andExpect(jsonPath("$.medicationHistory[0].isCurrent").value(true))
                .andExpect(jsonPath("$.medicationHistory[0].createdAt").exists())
                .andExpect(jsonPath("$.medicationHistory[0].length()").value(6))
                .andExpect(jsonPath("$.additionalDiagnoses").doesNotExist())
                .andExpect(jsonPath("$.orders").doesNotExist())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.aMapWithSize(15)));
    }

    @Test
    @DisplayName("GET /api/v1/patients/{id} - returns 404 when patient does not exist")
    void shouldReturnNotFoundForUnknownPatient() throws Exception {
        mockMvc.perform(get("/api/v1/patients/{id}", 99999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Patient not found"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/patients/by-mrn/{mrn} - returns patient details for a six-digit MRN")
    void shouldGetPatientByMrn() throws Exception {
        Patient patient = createPatient("John", "Smith", "001234");

        mockMvc.perform(get("/api/v1/patients/by-mrn/{mrn}", patient.getMrn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(patient.getId()))
                .andExpect(jsonPath("$.firstName").value("John"))
                .andExpect(jsonPath("$.lastName").value("Smith"))
                .andExpect(jsonPath("$.mrn").value("001234"))
                .andExpect(jsonPath("$.dateOfBirth").value("1979-06-08"));
    }

    @Test
    @DisplayName("GET /api/v1/patients/by-mrn/{mrn} - returns reference-compatible 404 for an unknown MRN")
    void shouldReturnReferenceCompatibleNotFoundForUnknownMrn() throws Exception {
        mockMvc.perform(get("/api/v1/patients/by-mrn/{mrn}", "999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Patient not found"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/patients/by-mrn/{mrn} - rejects an MRN that is not six digits")
    void shouldRejectInvalidMrnPath() throws Exception {
        mockMvc.perform(get("/api/v1/patients/by-mrn/{mrn}", "12345"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/patients/{id}/history - returns reverse-chronological raw order history")
    void shouldGetPatientHistory() throws Exception {
        Patient patient = createPatient("John", "Smith", "001234");

        Order olderOrder = createOrder(patient, "IVIG");
        olderOrder.setCreatedAt(LocalDateTime.of(2026, 8, 20, 9, 0));
        olderOrder = orderRepository.saveAndFlush(olderOrder);

        Order newerOrder = createOrder(patient, "Rituximab");
        newerOrder.setCreatedAt(LocalDateTime.of(2026, 8, 21, 9, 0));
        newerOrder = orderRepository.saveAndFlush(newerOrder);
        createCarePlan(newerOrder, CarePlan.Status.COMPLETED);

        mockMvc.perform(get("/api/v1/patients/{id}/history", patient.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(newerOrder.getId()))
                .andExpect(jsonPath("$[0].patientMrn").value("001234"))
                .andExpect(jsonPath("$[0].patientName").value("John Smith"))
                .andExpect(jsonPath("$[0].providerNpi").value("1234567890"))
                .andExpect(jsonPath("$[0].providerName").value("Dr. Jane Wilson"))
                .andExpect(jsonPath("$[0].medicationName").value("Rituximab"))
                .andExpect(jsonPath("$[0].status").value("completed"))
                .andExpect(jsonPath("$[0].hasCarePlan").value(true))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[1].id").value(olderOrder.getId()))
                .andExpect(jsonPath("$[1].status").value("pending"))
                .andExpect(jsonPath("$[1].hasCarePlan").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/patients/{id}/history - returns an empty raw array when the patient has no orders")
    void shouldReturnEmptyPatientHistory() throws Exception {
        Patient patient = createPatient("John", "Smith", "001234");

        mockMvc.perform(get("/api/v1/patients/{id}/history", patient.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/patients/{id}/history - returns 404 when the patient does not exist")
    void shouldReturnNotFoundForUnknownPatientHistory() throws Exception {
        mockMvc.perform(get("/api/v1/patients/{id}/history", 99999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Patient not found"));
    }

    @Test
    @DisplayName("GET /api/v1/patients - returns DRF-style fixed pagination links")
    void shouldGetPatientsWithReferencePagination() throws Exception {
        for (int index = 1; index <= 21; index++) {
            createPatient("First%02d".formatted(index), "Last%02d".formatted(index), "10%04d".formatted(index));
        }

        mockMvc.perform(patientListRequest(null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(21))
                .andExpect(jsonPath("$.next").value("http://localhost:8080/api/v1/patients?page=2"))
                .andExpect(jsonPath("$.previous").isEmpty())
                .andExpect(jsonPath("$.results.length()").value(20))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.results[0].id").exists())
                .andExpect(jsonPath("$.results[0].mrn").value("100001"))
                .andExpect(jsonPath("$.results[0].firstName").value("First01"))
                .andExpect(jsonPath("$.results[0].lastName").value("Last01"))
                .andExpect(jsonPath("$.results[0].fullName").value("First01 Last01"))
                .andExpect(jsonPath("$.results[0].primaryDiagnosis").value("G70.00"))
                .andExpect(jsonPath("$.results[0].length()").value(6))
                .andExpect(jsonPath("$.page").doesNotExist())
                .andExpect(jsonPath("$.pageSize").doesNotExist());

        mockMvc.perform(patientListRequest(2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(21))
                .andExpect(jsonPath("$.next").isEmpty())
                .andExpect(jsonPath("$.previous").value("http://localhost:8080/api/v1/patients?page=1"))
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].lastName").value("Last21"));

        mockMvc.perform(patientListRequest("last"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].lastName").value("Last21"));
    }

    @Test
    @DisplayName("GET /api/v1/patients - ignores unsupported search and page_size query parameters")
    void shouldIgnoreUnsupportedListQueryParameters() throws Exception {
        createPatient("Zoe", "Adams", "001234");
        createPatient("Amy", "Adams", "001235");
        createPatient("John", "Smith", "001236");

        mockMvc.perform(patientListRequest(1)
                        .param("search", "sMi")
                        .param("page_size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.results.length()").value(3))
                .andExpect(jsonPath("$.results[0].firstName").value("Amy"))
                .andExpect(jsonPath("$.results[1].firstName").value("Zoe"))
                .andExpect(jsonPath("$.results[2].firstName").value("John"));
    }

    @Test
    @DisplayName("GET /api/v1/patients - returns DRF-style 404 for invalid page values")
    void shouldReturnReferenceCompatibleInvalidPageResponse() throws Exception {
        mockMvc.perform(get("/api/v1/patients").param("page", "0"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Invalid page."));

        mockMvc.perform(get("/api/v1/patients").param("page", "not-a-number"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Invalid page."));

        createPatient("John", "Smith", "001234");
        mockMvc.perform(patientListRequest(2))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Invalid page."));
    }

    @Test
    @DisplayName("PUT /api/v1/patients/{id} - partially updates patient and returns updated_at")
    void shouldUpdatePatient() throws Exception {
        Patient patient = createPatient("John", "Smith", "001234");
        patient.setWeightKg(72.0);
        patient.setAllergies("None known");
        patient = patientRepository.saveAndFlush(patient);

        mockMvc.perform(put("/api/v1/patients/{id}", patient.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "weightKg": 75,
                                  "allergies": "Penicillin"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(patient.getId()))
                .andExpect(jsonPath("$.firstName").value("John"))
                .andExpect(jsonPath("$.lastName").value("Smith"))
                .andExpect(jsonPath("$.mrn").value("001234"))
                .andExpect(jsonPath("$.weightKg").value(75.0))
                .andExpect(jsonPath("$.allergies").value("Penicillin"))
                .andExpect(jsonPath("$.updatedAt").exists());

        Patient reloadedPatient = patientRepository.findById(patient.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(LocalDate.of(1979, 6, 8), reloadedPatient.getDateOfBirth());
        org.junit.jupiter.api.Assertions.assertEquals("G70.00", reloadedPatient.getPrimaryDiagnosis());
    }

    @Test
    @DisplayName("PUT /api/v1/patients/{id} - rejects invalid weight")
    void shouldRejectInvalidUpdateWeight() throws Exception {
        Patient patient = createPatient("John", "Smith", "001234");

        mockMvc.perform(put("/api/v1/patients/{id}", patient.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"weightKg\": 0 }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.details.weight_kg").value("weight_kg must be greater than 0"));
    }

    @Test
    @DisplayName("PUT /api/v1/patients/{id} - rejects requests containing MRN")
    void shouldRejectMrnModification() throws Exception {
        Patient patient = createPatient("John", "Smith", "001234");

        mockMvc.perform(put("/api/v1/patients/{id}", patient.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"mrn\": \"009999\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.details.mrn").value("MRN cannot be modified"));
    }

    @Test
    @DisplayName("PUT /api/v1/patients/{id} - rejects MRN even when its value is null")
    void shouldRejectNullMrnModification() throws Exception {
        Patient patient = createPatient("John", "Smith", "001234");

        mockMvc.perform(put("/api/v1/patients/{id}", patient.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"mrn\": null }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.mrn").value("MRN cannot be modified"));
    }

    @Test
    @DisplayName("PUT /api/v1/patients/{id} - returns 404 for unknown patient")
    void shouldReturnNotFoundWhenUpdatingUnknownPatient() throws Exception {
        mockMvc.perform(put("/api/v1/patients/{id}", 99999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"weightKg\": 75 }"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Patient not found"));
    }

    @Test
    @DisplayName("DELETE /api/v1/patients/{id} - rejects deletion when an order is pending")
    void shouldRejectDeletionWhenPatientHasPendingOrders() throws Exception {
        Patient patient = createPatient("John", "Smith", "001234");
        Order order = createOrder(patient, "IVIG");
        createCarePlan(order, CarePlan.Status.PENDING);

        mockMvc.perform(delete("/api/v1/patients/{id}", patient.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Cannot delete patient with active orders"))
                .andExpect(jsonPath("$.active_orders[0]").value(order.getId()));

        org.junit.jupiter.api.Assertions.assertTrue(patientRepository.existsById(patient.getId()));
    }

    @Test
    @DisplayName("DELETE /api/v1/patients/{id} - rejects deletion when an order is processing")
    void shouldRejectDeletionWhenPatientHasProcessingOrders() throws Exception {
        Patient patient = createPatient("John", "Smith", "001234");
        Order order = createOrder(patient, "IVIG");
        createCarePlan(order, CarePlan.Status.PROCESSING);

        mockMvc.perform(delete("/api/v1/patients/{id}", patient.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.active_orders[0]").value(order.getId()));
    }

    @Test
    @DisplayName("DELETE /api/v1/patients/{id} - deletes completed and failed order history with the patient")
    void shouldDeletePatientAndInactiveOrderHistory() throws Exception {
        Patient patient = createPatient("John", "Smith", "001234");
        Order completedOrder = createOrder(patient, "IVIG");
        Order failedOrder = createOrder(patient, "Rituximab");
        createCarePlan(completedOrder, CarePlan.Status.COMPLETED);
        createCarePlan(failedOrder, CarePlan.Status.FAILED);

        mockMvc.perform(delete("/api/v1/patients/{id}", patient.getId()))
                .andExpect(status().isNoContent());

        org.junit.jupiter.api.Assertions.assertFalse(patientRepository.existsById(patient.getId()));
        org.junit.jupiter.api.Assertions.assertFalse(orderRepository.existsById(completedOrder.getId()));
        org.junit.jupiter.api.Assertions.assertFalse(orderRepository.existsById(failedOrder.getId()));
        org.junit.jupiter.api.Assertions.assertTrue(carePlanRepository.findByOrderId(completedOrder.getId()).isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(carePlanRepository.findByOrderId(failedOrder.getId()).isEmpty());
    }

    @Test
    @DisplayName("DELETE /api/v1/patients/{id} - deletes patient-owned detail resources with a patient that has no orders")
    void shouldDeletePatientWithNoOrders() throws Exception {
        Patient patient = createPatient("John", "Smith", "001234");

        PatientDiagnosis diagnosis = new PatientDiagnosis();
        diagnosis.setPatient(patient);
        diagnosis.setIcd10Code("G70.00");
        diagnosis.setPrimary(true);
        patientDiagnosisRepository.save(diagnosis);

        MedicationHistory medicationHistory = new MedicationHistory();
        medicationHistory.setPatient(patient);
        medicationHistory.setMedicationName("Pyridostigmine");
        medicationHistoryRepository.save(medicationHistory);

        mockMvc.perform(delete("/api/v1/patients/{id}", patient.getId()))
                .andExpect(status().isNoContent());

        org.junit.jupiter.api.Assertions.assertFalse(patientRepository.existsById(patient.getId()));
        org.junit.jupiter.api.Assertions.assertTrue(patientDiagnosisRepository.findByPatientOrderByCreatedAtAsc(patient).isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(medicationHistoryRepository.findByPatientOrderByCreatedAtAsc(patient).isEmpty());
    }

    @Test
    @DisplayName("DELETE /api/v1/patients/{id} - returns 404 for an unknown patient")
    void shouldReturnNotFoundWhenDeletingUnknownPatient() throws Exception {
        mockMvc.perform(delete("/api/v1/patients/{id}", 99999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Patient not found"));
    }

    @Test
    @DisplayName("GET /api/v1/patients/{id}/orders - returns all order summaries for the patient")
    void shouldGetPatientOrders() throws Exception {
        Patient patient = createPatient("John", "Smith", "001234");
        Order completedOrder = createOrder(patient, "IVIG");
        Order pendingOrder = createOrder(patient, "Rituximab");
        createCarePlan(completedOrder, CarePlan.Status.COMPLETED);
        createCarePlan(pendingOrder, CarePlan.Status.PENDING);

        mockMvc.perform(get("/api/v1/patients/{id}/orders", patient.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientId").value(patient.getId()))
                .andExpect(jsonPath("$.patientName").value("John Smith"))
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andExpect(jsonPath("$.orders[0].id").value(pendingOrder.getId()))
                .andExpect(jsonPath("$.orders[0].medicationName").value("Rituximab"))
                .andExpect(jsonPath("$.orders[0].status").value("pending"))
                .andExpect(jsonPath("$.orders[1].id").value(completedOrder.getId()))
                .andExpect(jsonPath("$.orders[1].medicationName").value("IVIG"))
                .andExpect(jsonPath("$.orders[1].status").value("completed"))
                .andExpect(jsonPath("$.orders[0].createdAt").exists());
    }

    @Test
    @DisplayName("GET /api/v1/patients/{id}/orders - returns an empty order collection for a patient with no orders")
    void shouldReturnEmptyOrdersForPatientWithNoOrders() throws Exception {
        Patient patient = createPatient("John", "Smith", "001234");

        mockMvc.perform(get("/api/v1/patients/{id}/orders", patient.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientId").value(patient.getId()))
                .andExpect(jsonPath("$.patientName").value("John Smith"))
                .andExpect(jsonPath("$.orders.length()").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/patients/{id}/orders - returns 404 for an unknown patient")
    void shouldReturnNotFoundForUnknownPatientOrders() throws Exception {
        mockMvc.perform(get("/api/v1/patients/{id}/orders", 99999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Patient not found"));
    }

    private Patient createPatient(String firstName, String lastName, String mrn) {
        Patient patient = new Patient();
        patient.setFirstName(firstName);
        patient.setLastName(lastName);
        patient.setMrn(mrn);
        patient.setDateOfBirth(LocalDate.of(1979, 6, 8));
        patient.setPrimaryDiagnosis("G70.00");
        patient.setAdditionalDiagnoses(new ArrayList<>());
        return patientRepository.save(patient);
    }

    private Order createOrder(Patient patient, String medicationName) {
        Provider provider = providerRepository.findByNpi("1234567890")
                .orElseGet(() -> {
                    Provider newProvider = new Provider();
                    newProvider.setName("Dr. Jane Wilson");
                    newProvider.setNpi("1234567890");
                    return providerRepository.save(newProvider);
                });

        Order order = new Order();
        order.setPatient(patient);
        order.setProvider(provider);
        order.setMedicationName(medicationName);
        return orderRepository.save(order);
    }

    private void createCarePlan(Order order, CarePlan.Status status) {
        CarePlan carePlan = new CarePlan();
        carePlan.setOrder(order);
        carePlan.setStatus(status);
        carePlanRepository.save(carePlan);
    }

    private String validRequestJson(String mrn) {
        return requestJson(mrn, 72);
    }

    private String requestJson(String mrn, int weightKg) {
        return String.format("""
                {
                  "firstName": "John",
                  "lastName": "Smith",
                  "mrn": "%s",
                  "dateOfBirth": "1979-06-08",
                  "sex": "Female",
                  "weightKg": %d,
                  "allergies": "None known",
                  "primaryDiagnosis": "G70.00",
                  "primaryDiagnosisDescription": "Myasthenia gravis without acute exacerbation",
                  "additionalDiagnoses": ["I10", "K21.0"]
                }
                """, mrn, weightKg);
    }

    private MockHttpServletRequestBuilder patientListRequest(Object page) {
        MockHttpServletRequestBuilder requestBuilder = get("/api/v1/patients");
        if (page != null) {
            requestBuilder.param("page", page.toString());
        }
        return requestBuilder.with(request -> {
                    request.setServerName("localhost");
                    request.setServerPort(8080);
                    request.setScheme("http");
                    return request;
                });
    }
}

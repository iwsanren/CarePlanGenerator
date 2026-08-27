package com.page24.backend.controller;

import com.page24.backend.entity.Patient;
import com.page24.backend.entity.Provider;
import com.page24.backend.entity.Order;
import com.page24.backend.entity.CarePlan;
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
    @DisplayName("POST /api/v1/patients - accepts optional demographics and returns reference field names")
    void shouldCreatePatientWithOptionalDemographics() throws Exception {
        mockMvc.perform(post("/api/v1/patients")
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
                .andExpect(jsonPath("$.primary_diagnosis_code").value("G70.00"))
                .andExpect(jsonPath("$.primary_diagnosis_description")
                        .value("Myasthenia gravis without acute exacerbation"))
                .andExpect(jsonPath("$.primary_diagnosis").doesNotExist())
                .andExpect(jsonPath("$.additional_diagnoses[0]").value("I10"))
                .andExpect(jsonPath("$.additional_diagnoses[1]").value("K21.0"))
                .andExpect(jsonPath("$.created_at").exists());
    }

    @Test
    @DisplayName("POST /api/v1/patients - creates patient from minimal reference-compatible request")
    void shouldCreatePatientFromMinimalReferenceCompatibleRequest() throws Exception {
        mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mrn": "001234",
                                  "first_name": "John",
                                  "last_name": "Smith",
                                  "primary_diagnosis_code": "G70.00"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.mrn").value("001234"))
                .andExpect(jsonPath("$.first_name").value("John"))
                .andExpect(jsonPath("$.last_name").value("Smith"))
                .andExpect(jsonPath("$.primary_diagnosis_code").value("G70.00"))
                .andExpect(jsonPath("$.primary_diagnosis_description").isEmpty())
                .andExpect(jsonPath("$.date_of_birth").isEmpty())
                .andExpect(jsonPath("$.sex").isEmpty())
                .andExpect(jsonPath("$.weight_kg").isEmpty())
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
                                  "first_name": "John",
                                  "last_name": "Smith",
                                  "primary_diagnosis": "G70.00"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.primary_diagnosis_code").value("G70.00"))
                .andExpect(jsonPath("$.primary_diagnosis").doesNotExist());
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
    @DisplayName("GET /api/v1/patients/{id} - returns patient details, history, and order summaries")
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
        patient.setAdditionalDiagnoses(new ArrayList<>(List.of("I10", "K21.0")));
        patient = patientRepository.save(patient);

        Provider provider = new Provider();
        provider.setName("Dr. Jane Wilson");
        provider.setNpi("1234567890");
        provider = providerRepository.save(provider);

        Order order = new Order();
        order.setPatient(patient);
        order.setProvider(provider);
        order.setMedicationName("IVIG");
        order.setMedicationHistory("Pyridostigmine 60mg\nPrednisone 10mg\nPyridostigmine 60mg");
        order = orderRepository.save(order);

        CarePlan carePlan = new CarePlan();
        carePlan.setOrder(order);
        carePlan.setStatus(CarePlan.Status.COMPLETED);
        carePlanRepository.save(carePlan);

        mockMvc.perform(get("/api/v1/patients/{id}", patient.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(patient.getId()))
                .andExpect(jsonPath("$.first_name").value("John"))
                .andExpect(jsonPath("$.last_name").value("Smith"))
                .andExpect(jsonPath("$.mrn").value("001234"))
                .andExpect(jsonPath("$.date_of_birth").value("1979-06-08"))
                .andExpect(jsonPath("$.weight_kg").value(72.0))
                .andExpect(jsonPath("$.additional_diagnoses[0]").value("I10"))
                .andExpect(jsonPath("$.medication_history[0]").value("Pyridostigmine 60mg"))
                .andExpect(jsonPath("$.medication_history[1]").value("Prednisone 10mg"))
                .andExpect(jsonPath("$.medication_history.length()").value(2))
                .andExpect(jsonPath("$.orders[0].id").value(order.getId()))
                .andExpect(jsonPath("$.orders[0].medication_name").value("IVIG"))
                .andExpect(jsonPath("$.orders[0].status").value("completed"))
                .andExpect(jsonPath("$.orders[0].created_at").exists())
                .andExpect(jsonPath("$.created_at").exists());
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
                .andExpect(jsonPath("$.first_name").value("John"))
                .andExpect(jsonPath("$.last_name").value("Smith"))
                .andExpect(jsonPath("$.mrn").value("001234"))
                .andExpect(jsonPath("$.date_of_birth").value("1979-06-08"));
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
                .andExpect(jsonPath("$[0].patient_mrn").value("001234"))
                .andExpect(jsonPath("$[0].patient_name").value("John Smith"))
                .andExpect(jsonPath("$[0].provider_npi").value("1234567890"))
                .andExpect(jsonPath("$[0].provider_name").value("Dr. Jane Wilson"))
                .andExpect(jsonPath("$[0].medication_name").value("Rituximab"))
                .andExpect(jsonPath("$[0].status").value("completed"))
                .andExpect(jsonPath("$[0].has_care_plan").value(true))
                .andExpect(jsonPath("$[0].created_at").exists())
                .andExpect(jsonPath("$[1].id").value(olderOrder.getId()))
                .andExpect(jsonPath("$[1].status").value("pending"))
                .andExpect(jsonPath("$[1].has_care_plan").value(false));
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
    @DisplayName("GET /api/v1/patients - returns a paginated summary response")
    void shouldGetPatientsWithPagination() throws Exception {
        createPatient("John", "Smith", "001234");
        createPatient("Jane", "Smith", "001235");
        createPatient("Alex", "Jones", "001236");

        mockMvc.perform(get("/api/v1/patients")
                        .param("page", "1")
                        .param("page_size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.page_size").value(2))
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].id").exists())
                .andExpect(jsonPath("$.results[0].mrn").exists())
                .andExpect(jsonPath("$.results[0].first_name").exists())
                .andExpect(jsonPath("$.results[0].last_name").exists())
                .andExpect(jsonPath("$.results[0].full_name").value("Alex Jones"))
                .andExpect(jsonPath("$.results[0].primary_diagnosis_code").value("G70.00"))
                .andExpect(jsonPath("$.results[0].length()").value(6))
                .andExpect(jsonPath("$.results[0].primary_diagnosis").doesNotExist())
                .andExpect(jsonPath("$.results[0].created_at").doesNotExist())
                .andExpect(jsonPath("$.results[0].date_of_birth").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/patients - searches first and last name without case sensitivity")
    void shouldSearchPatientsByName() throws Exception {
        createPatient("John", "Smith", "001234");
        createPatient("Jane", "Adams", "001235");

        mockMvc.perform(get("/api/v1/patients").param("search", "sMi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].first_name").value("John"))
                .andExpect(jsonPath("$.results[0].last_name").value("Smith"));
    }

    @Test
    @DisplayName("GET /api/v1/patients - returns an empty page when no patient matches search")
    void shouldReturnEmptyPageForNoSearchMatches() throws Exception {
        createPatient("John", "Smith", "001234");

        mockMvc.perform(get("/api/v1/patients").param("search", "NotFound"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.page_size").value(20))
                .andExpect(jsonPath("$.results.length()").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/patients - rejects invalid pagination values")
    void shouldRejectInvalidPaginationValues() throws Exception {
        mockMvc.perform(get("/api/v1/patients").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));

        mockMvc.perform(get("/api/v1/patients").param("page_size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE_SIZE"));
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
                                  "weight_kg": 75,
                                  "allergies": "Penicillin"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(patient.getId()))
                .andExpect(jsonPath("$.first_name").value("John"))
                .andExpect(jsonPath("$.last_name").value("Smith"))
                .andExpect(jsonPath("$.mrn").value("001234"))
                .andExpect(jsonPath("$.weight_kg").value(75.0))
                .andExpect(jsonPath("$.allergies").value("Penicillin"))
                .andExpect(jsonPath("$.updated_at").exists());

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
                        .content("{ \"weight_kg\": 0 }"))
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
                        .content("{ \"weight_kg\": 75 }"))
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
    @DisplayName("DELETE /api/v1/patients/{id} - deletes a patient with no orders")
    void shouldDeletePatientWithNoOrders() throws Exception {
        Patient patient = createPatient("John", "Smith", "001234");

        mockMvc.perform(delete("/api/v1/patients/{id}", patient.getId()))
                .andExpect(status().isNoContent());

        org.junit.jupiter.api.Assertions.assertFalse(patientRepository.existsById(patient.getId()));
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
                .andExpect(jsonPath("$.patient_id").value(patient.getId()))
                .andExpect(jsonPath("$.patient_name").value("John Smith"))
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andExpect(jsonPath("$.orders[0].id").value(pendingOrder.getId()))
                .andExpect(jsonPath("$.orders[0].medication_name").value("Rituximab"))
                .andExpect(jsonPath("$.orders[0].status").value("pending"))
                .andExpect(jsonPath("$.orders[1].id").value(completedOrder.getId()))
                .andExpect(jsonPath("$.orders[1].medication_name").value("IVIG"))
                .andExpect(jsonPath("$.orders[1].status").value("completed"))
                .andExpect(jsonPath("$.orders[0].created_at").exists());
    }

    @Test
    @DisplayName("GET /api/v1/patients/{id}/orders - returns an empty order collection for a patient with no orders")
    void shouldReturnEmptyOrdersForPatientWithNoOrders() throws Exception {
        Patient patient = createPatient("John", "Smith", "001234");

        mockMvc.perform(get("/api/v1/patients/{id}/orders", patient.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patient_id").value(patient.getId()))
                .andExpect(jsonPath("$.patient_name").value("John Smith"))
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
                  "first_name": "John",
                  "last_name": "Smith",
                  "mrn": "%s",
                  "date_of_birth": "1979-06-08",
                  "sex": "Female",
                  "weight_kg": %d,
                  "allergies": "None known",
                  "primary_diagnosis_code": "G70.00",
                  "primary_diagnosis_description": "Myasthenia gravis without acute exacerbation",
                  "additional_diagnoses": ["I10", "K21.0"]
                }
                """, mrn, weightKg);
    }
}

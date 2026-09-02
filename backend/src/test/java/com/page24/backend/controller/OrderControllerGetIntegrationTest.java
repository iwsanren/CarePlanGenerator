package com.page24.backend.controller;

import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.Order;
import com.page24.backend.entity.Patient;
import com.page24.backend.entity.Provider;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderControllerGetIntegrationTest {

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

    private Provider provider;

    @BeforeEach
    void cleanDatabase() {
        carePlanRepository.deleteAll();
        orderRepository.deleteAll();
        patientRepository.deleteAll();
        providerRepository.deleteAll();

        provider = new Provider();
        provider.setName("Dr. Green");
        provider.setNpi("1111111111");
        provider = providerRepository.save(provider);
    }

    @Test
    @DisplayName("GET /api/v1/orders - returns paginated response shape")
    void shouldReturnPagedOrders() throws Exception {
        createOrder("Alice", "Wong", "100001", CarePlan.Status.PENDING);
        createOrder("Bob", "Lee", "100002", CarePlan.Status.COMPLETED);
        createOrder("Cathy", "Chen", "100003", CarePlan.Status.FAILED);

        mockMvc.perform(get("/api/v1/orders")
                        .param("page", "1")
                        .param("page_size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(2))
                .andExpect(jsonPath("$.results.length()").value(2));
    }

    @Test
    @DisplayName("GET /api/v1/orders - status=completed returns only completed orders")
    void shouldFilterByStatus() throws Exception {
        createOrder("Alice", "Wong", "100001", CarePlan.Status.PENDING);
        createOrder("Bob", "Lee", "100002", CarePlan.Status.COMPLETED);

        mockMvc.perform(get("/api/v1/orders/")
                        .param("status", "completed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.results[0].status").value("completed"));
    }

    @Test
    @DisplayName("GET /api/v1/orders - patient_name supports fuzzy search")
    void shouldSearchByPatientName() throws Exception {
        createOrder("John", "Smith", "100001", CarePlan.Status.COMPLETED);
        createOrder("Alice", "Wong", "100002", CarePlan.Status.PENDING);

        mockMvc.perform(get("/api/v1/orders")
                        .param("patient_name", "John"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.results[0].status").value("completed"));
    }

    @Test
    @DisplayName("GET /api/v1/orders - supports status and patient_name together")
    void shouldFilterByStatusAndPatientName() throws Exception {
        createOrder("John", "Smith", "100001", CarePlan.Status.COMPLETED);
        createOrder("John", "Doe", "100002", CarePlan.Status.PENDING);
        createOrder("Alice", "Wong", "100003", CarePlan.Status.COMPLETED);

        mockMvc.perform(get("/api/v1/orders")
                        .param("status", "completed")
                        .param("patient_name", "John"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.results[0].status").value("completed"));
    }

    @Test
    @DisplayName("GET /api/v1/orders - combines status, patient_id, and provider_id filters")
    void shouldFilterByStatusPatientAndProvider() throws Exception {
        Patient matchingPatient = createPatient("Alice", "Wong", "100001");
        Patient otherPatient = createPatient("Bob", "Lee", "100002");
        Provider otherProvider = new Provider();
        otherProvider.setName("Dr. Blue");
        otherProvider.setNpi("2222222222");
        otherProvider = providerRepository.save(otherProvider);

        createOrder(matchingPatient, provider, CarePlan.Status.PENDING);
        createOrder(matchingPatient, otherProvider, CarePlan.Status.PENDING);
        createOrder(otherPatient, provider, CarePlan.Status.COMPLETED);

        mockMvc.perform(get("/api/v1/orders")
                        .param("status", "pending")
                        .param("patient_id", matchingPatient.getId().toString())
                        .param("provider_id", provider.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.results[0].patientName").value("Alice Wong"))
                .andExpect(jsonPath("$.results[0].medicationName").value("IVIG"))
                .andExpect(jsonPath("$.results[0].status").value("pending"))
                .andExpect(jsonPath("$.results[0].createdAt").exists());
    }

    @Test
    @DisplayName("GET /api/v1/orders - rejects non-positive patient_id")
    void shouldRejectNonPositivePatientId() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("patient_id", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PATIENT_ID"));
    }

    @Test
    @DisplayName("GET /api/v1/orders - invalid status returns 400")
    void shouldRejectInvalidStatus() throws Exception {
        mockMvc.perform(get("/api/v1/orders")
                        .param("status", "unknown"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
    }

    private void createOrder(String firstName, String lastName, String mrn, CarePlan.Status status) {
        Patient patient = new Patient();
        patient.setFirstName(firstName);
        patient.setLastName(lastName);
        patient.setMrn(mrn);
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient = patientRepository.save(patient);

        createOrder(patient, provider, status);
    }

    private Patient createPatient(String firstName, String lastName, String mrn) {
        Patient patient = new Patient();
        patient.setFirstName(firstName);
        patient.setLastName(lastName);
        patient.setMrn(mrn);
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        return patientRepository.save(patient);
    }

    private void createOrder(Patient patient, Provider orderProvider, CarePlan.Status status) {
        Order order = new Order();
        order.setPatient(patient);
        order.setProvider(orderProvider);
        order.setMedicationName("IVIG");
        order.setPrimaryDiagnosis("G70.00");
        order.setAdditionalDiagnosis("I10");
        order.setMedicationHistory("Prednisone");
        order.setPatientRecords("Test record");
        order = orderRepository.save(order);

        CarePlan carePlan = new CarePlan();
        carePlan.setOrder(order);
        carePlan.setStatus(status);
        if (status == CarePlan.Status.COMPLETED) {
            carePlan.setContent("Completed care plan");
        }
        carePlanRepository.save(carePlan);
    }
}

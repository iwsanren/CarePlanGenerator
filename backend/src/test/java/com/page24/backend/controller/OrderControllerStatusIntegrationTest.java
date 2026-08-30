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
class OrderControllerStatusIntegrationTest {

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
    @DisplayName("GET status - processing response contains only order ID and status")
    void shouldReturnProcessingStatus() throws Exception {
        Long orderId = createOrderWithCarePlan(CarePlan.Status.PROCESSING, null, null);

        mockMvc.perform(get("/api/v1/orders/{id}/status", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.status").value("processing"))
                .andExpect(jsonPath("$.carePlanPreview").doesNotExist())
                .andExpect(jsonPath("$.errorMessage").doesNotExist());
    }

    @Test
    @DisplayName("GET status - completed response contains a CarePlan preview")
    void shouldReturnCompletedStatusWithPreview() throws Exception {
        Long orderId = createOrderWithCarePlan(
                CarePlan.Status.COMPLETED,
                "Problem list: Need for rapid immunomodulation...",
                null
        );

        mockMvc.perform(get("/api/v1/orders/{id}/status", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.carePlanPreview").value("Problem list: Need for rapid immunomodulation..."))
                .andExpect(jsonPath("$.errorMessage").doesNotExist());
    }

    @Test
    @DisplayName("GET status - failed response contains the safe failure message")
    void shouldReturnFailedStatusWithErrorMessage() throws Exception {
        Long orderId = createOrderWithCarePlan(
                CarePlan.Status.FAILED,
                null,
                "LLM service unavailable"
        );

        mockMvc.perform(get("/api/v1/orders/{id}/status", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.errorMessage").value("LLM service unavailable"))
                .andExpect(jsonPath("$.carePlanPreview").doesNotExist());
    }

    @Test
    @DisplayName("GET status - unknown order returns 404")
    void shouldReturnNotFoundWhenOrderDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/v1/orders/{id}/status", 99999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    private Long createOrderWithCarePlan(CarePlan.Status status, String content, String errorMessage) {
        Patient patient = new Patient();
        patient.setFirstName("Alice");
        patient.setLastName("Wong");
        patient.setMrn("100001");
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient = patientRepository.save(patient);

        Provider provider = new Provider();
        provider.setName("Dr. Green");
        provider.setNpi("1111111111");
        provider = providerRepository.save(provider);

        Order order = new Order();
        order.setPatient(patient);
        order.setProvider(provider);
        order.setMedicationName("IVIG");
        order = orderRepository.save(order);

        CarePlan carePlan = new CarePlan();
        carePlan.setOrder(order);
        carePlan.setStatus(status);
        carePlan.setContent(content);
        carePlan.setErrorMessage(errorMessage);
        carePlanRepository.save(carePlan);

        return order.getId();
    }
}

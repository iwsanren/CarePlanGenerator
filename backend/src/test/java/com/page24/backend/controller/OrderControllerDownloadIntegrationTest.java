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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderControllerDownloadIntegrationTest {

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
    @DisplayName("GET CarePlan download - completed CarePlan downloads as a UTF-8 text file")
    void shouldDownloadCompletedCarePlan() throws Exception {
        String carePlanText = "Problem list: Need for rapid immunomodulation";
        Long orderId = createOrderWithCarePlan(CarePlan.Status.COMPLETED, carePlanText);

        mockMvc.perform(get("/api/v1/orders/{id}/careplan/download", orderId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"careplan_order_" + orderId + ".txt\""))
                .andExpect(content().string(carePlanText));
    }

    @Test
    @DisplayName("GET CarePlan download - non-completed CarePlans return the Ticket 10 404 response")
    void shouldRejectNonCompletedCarePlans() throws Exception {
        for (CarePlan.Status statusValue : new CarePlan.Status[]{
                CarePlan.Status.PENDING, CarePlan.Status.PROCESSING, CarePlan.Status.FAILED}) {
            Long orderId = createOrderWithCarePlan(statusValue, null);

            mockMvc.perform(get("/api/v1/orders/{id}/careplan/download", orderId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("CarePlan not yet generated"));
        }
    }

    @Test
    @DisplayName("GET CarePlan download - unknown order returns 404")
    void shouldReturnNotFoundForUnknownOrder() throws Exception {
        mockMvc.perform(get("/api/v1/orders/{id}/careplan/download", 99999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    private Long createOrderWithCarePlan(CarePlan.Status status, String content) {
        Patient patient = new Patient();
        patient.setFirstName("Alice");
        patient.setLastName("Wong");
        patient.setMrn("10000" + status.ordinal());
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient = patientRepository.save(patient);

        Provider provider = new Provider();
        provider.setName("Dr. Green");
        provider.setNpi("111111111" + status.ordinal());
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
        carePlanRepository.save(carePlan);

        return order.getId();
    }
}

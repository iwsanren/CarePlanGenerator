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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportControllerIntegrationTest {

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
    void exportsCompletedOrdersAsCsv() throws Exception {
        createOrder("Alice", "Wong", "100001", "IVIG", CarePlan.Status.COMPLETED,
                LocalDateTime.of(2026, 8, 5, 9, 0));
        createOrder("Bob", "Lee", "100002", "Humira", CarePlan.Status.PENDING,
                LocalDateTime.of(2026, 8, 6, 9, 0));

        mockMvc.perform(get("/api/reports/orders/export")
                        .param("format", "csv")
                        .param("status", "completed")
                        .param("start_date", "2026-08-01")
                        .param("end_date", "2026-08-31")
                        .param("provider_id", provider.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("attachment; filename=\"orders_report_")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(containsString("\"Care Plan Status\"")))
                .andExpect(content().string(containsString("\"Alice Wong\"")))
                .andExpect(content().string(containsString("\"COMPLETED\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("\"Bob Lee\""))));
    }

    @Test
    void returnsHeaderOnlyCsvWhenNoOrdersMatch() throws Exception {
        mockMvc.perform(get("/api/reports/orders/export").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"Order ID\"")));
    }

    @Test
    void rejectsInvalidDateRange() throws Exception {
        mockMvc.perform(get("/api/reports/orders/export")
                        .param("start_date", "2026-08-31")
                        .param("end_date", "2026-08-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }

    @Test
    void rejectsUnsupportedFormat() throws Exception {
        mockMvc.perform(get("/api/reports/orders/export").param("format", "xlsx"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EXPORT_FORMAT"));
    }

    private void createOrder(
            String firstName,
            String lastName,
            String mrn,
            String medication,
            CarePlan.Status status,
            LocalDateTime createdAt
    ) {
        Patient patient = new Patient();
        patient.setFirstName(firstName);
        patient.setLastName(lastName);
        patient.setMrn(mrn);
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient = patientRepository.save(patient);

        Order order = new Order();
        order.setPatient(patient);
        order.setProvider(provider);
        order.setMedicationName(medication);
        order.setPrimaryDiagnosis("G70.00");
        order.setCreatedAt(createdAt);
        order = orderRepository.save(order);

        CarePlan carePlan = new CarePlan();
        carePlan.setOrder(order);
        carePlan.setStatus(status);
        carePlanRepository.save(carePlan);
    }
}

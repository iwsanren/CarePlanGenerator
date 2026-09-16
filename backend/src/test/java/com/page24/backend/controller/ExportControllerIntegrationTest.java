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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExportControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private CarePlanRepository carePlanRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private ProviderRepository providerRepository;

    @MockitoBean private QueueService queueService;
    @MockitoBean private DataInitializationService dataInitializationService;

    @BeforeEach
    void cleanDatabase() {
        carePlanRepository.deleteAll();
        orderRepository.deleteAll();
        patientRepository.deleteAll();
        providerRepository.deleteAll();
    }

    @Test
    void headerMatchesTheReferenceOrdersExportCsvColumnLayout() throws Exception {
        mockMvc.perform(get("/api/v1/export"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("attachment; filename=\"orders_export_")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(containsString(
                        "\"order_id\",\"order_date\",\"patient_mrn\",\"patient_first_name\",\"patient_last_name\","
                        + "\"patient_date_of_birth\",\"provider_npi\",\"provider_name\",\"medication_name\","
                        + "\"primary_diagnosis_code\",\"care_plan_status\",\"care_plan_content\"")));
    }

    @Test
    void everyColumnOfACompletedOrderIsCorrect() throws Exception {
        Provider provider = new Provider();
        provider.setName("Dr. Green");
        provider.setNpi("1111111111");
        provider = providerRepository.save(provider);

        Patient patient = new Patient();
        patient.setFirstName("Carol");
        patient.setLastName("Diaz");
        patient.setMrn("100003");
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient = patientRepository.save(patient);

        Order order = new Order();
        order.setPatient(patient);
        order.setProvider(provider);
        order.setMedicationName("Xolair");
        order.setPrimaryDiagnosis("G70.00");
        order.setCreatedAt(LocalDateTime.of(2026, 9, 1, 10, 30));
        order = orderRepository.save(order);

        CarePlan carePlan = new CarePlan();
        carePlan.setOrder(order);
        carePlan.setStatus(CarePlan.Status.COMPLETED);
        carePlan.setContent("# Care Plan\nProblem list: none");
        carePlanRepository.save(carePlan);

        String expectedRow = String.join(",",
                "\"" + order.getId() + "\"",
                "\"2026-09-01\"",
                "\"100003\"",
                "\"Carol\"",
                "\"Diaz\"",
                "\"1990-01-01\"",
                "\"1111111111\"",
                "\"Dr. Green\"",
                "\"Xolair\"",
                "\"G70.00\"",
                "\"completed\"",
                "\"# Care Plan\nProblem list: none\"");

        mockMvc.perform(get("/api/v1/export"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(expectedRow)));
    }

    @Test
    void anOrderWithoutACompletedCarePlanHasBlankStatusAndContentCells() throws Exception {
        Provider provider = new Provider();
        provider.setName("Dr. Grey");
        provider.setNpi("2222222222");
        provider = providerRepository.save(provider);

        Patient patient = new Patient();
        patient.setFirstName("Dan");
        patient.setLastName("Wu");
        patient.setMrn("100004");
        // No date of birth set on purpose — it's optional since Phase 2.
        patient = patientRepository.save(patient);

        Order order = new Order();
        order.setPatient(patient);
        order.setProvider(provider);
        order.setMedicationName("Humira");
        order.setPrimaryDiagnosis("M06.9");
        order.setCreatedAt(LocalDateTime.of(2026, 9, 2, 8, 0));
        order = orderRepository.save(order);
        // Intentionally no CarePlan row at all for this order.

        String expectedRow = String.join(",",
                "\"" + order.getId() + "\"",
                "\"2026-09-02\"",
                "\"100004\"",
                "\"Dan\"",
                "\"Wu\"",
                "\"\"",   // date of birth was never set
                "\"2222222222\"",
                "\"Dr. Grey\"",
                "\"Humira\"",
                "\"M06.9\"",
                "\"\"",   // no CarePlan row -> blank status
                "\"\"");  // and blank content

        mockMvc.perform(get("/api/v1/export"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(expectedRow)));
    }
}

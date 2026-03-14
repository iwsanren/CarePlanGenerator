package com.page24.backend.controller;

import com.page24.backend.entity.Order;
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

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderControllerPostIntegrationTest {

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
    @DisplayName("POST /api/orders - 正常创建返回 201")
    void shouldCreateOrderSuccessfully() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("IVIG", false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultType").value("SUCCESS"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("POST /api/orders - Provider NPI冲突返回 409")
    void shouldReturnConflictWhenProviderNpiSameButNameDiffers() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("MED-1", false)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("Brian", "Lee", "223344", "1988-07-21", "Dr. Brown", "1111111111", "MED-2", false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("block"))
                .andExpect(jsonPath("$.code").value("DUPLICATE_NPI_NAME_MISMATCH"))
                .andExpect(jsonPath("$.httpStatus").value(409));
    }

    @Test
    @DisplayName("POST /api/orders - 同患者同药同天返回 409")
    void shouldReturnConflictForSamePatientMedicationSameDay() throws Exception {
        String body = validRequestJson("DUP_DRUG_SAME_DAY", false);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("block"))
                .andExpect(jsonPath("$.code").value("DUPLICATE_ORDER_SAME_DAY"))
                .andExpect(jsonPath("$.httpStatus").value(409));
    }

    @Test
    @DisplayName("POST /api/orders - 跨天同药 confirm=false 返回 200 warning")
    void shouldReturnWarningForCrossDayDuplicateWhenConfirmFalse() throws Exception {
        String medication = "HISTORICAL_DUP_DRUG";

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson(medication, true)))
                .andExpect(status().isCreated());

        Order firstOrder = orderRepository.findAll().get(0);
        firstOrder.setCreatedAt(LocalDateTime.now().minusDays(1));
        orderRepository.save(firstOrder);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson(medication, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("warning"))
                .andExpect(jsonPath("$.code").value("POTENTIAL_DUPLICATE_ORDER_CROSS_DAY"))
                .andExpect(jsonPath("$.detail.requiresConfirm").value(true))
                .andExpect(jsonPath("$.httpStatus").value(200));
    }

    @Test
    @DisplayName("POST /api/orders - 跨天同药 confirm=true 放行 201")
    void shouldAllowCrossDayDuplicateWhenConfirmTrue() throws Exception {
        String medication = "HISTORICAL_DUP_DRUG_ALLOW";

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson(medication, true)))
                .andExpect(status().isCreated());

        Order firstOrder = orderRepository.findAll().get(0);
        firstOrder.setCreatedAt(LocalDateTime.now().minusDays(1));
        orderRepository.save(firstOrder);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson(medication, true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultType").value("SUCCESS"));
    }

    private String validRequestJson(String medicationName, boolean confirm) {
        return requestJson(
                "Alice",
                "Wong",
                "123456",
                "1990-05-10",
                "Dr. Green",
                "1111111111",
                medicationName,
                confirm
        );
    }

    private String requestJson(
            String firstName,
            String lastName,
            String mrn,
            String dob,
            String providerName,
            String providerNpi,
            String medicationName,
            boolean confirm
    ) {
        return String.format("""
                {
                  "patientFirstName": "%s",
                  "patientLastName": "%s",
                  "patientMrn": "%s",
                  "patientDateOfBirth": "%s",
                  "providerName": "%s",
                  "providerNpi": "%s",
                  "medicationName": "%s",
                  "primaryDiagnosis": "G70.00",
                  "additionalDiagnosis": "I10",
                  "medicationHistory": "Prednisone",
                  "patientRecords": "Integration test record",
                  "confirm": %s
                }
                """, firstName, lastName, mrn, dob, providerName, providerNpi, medicationName, confirm);
    }
}

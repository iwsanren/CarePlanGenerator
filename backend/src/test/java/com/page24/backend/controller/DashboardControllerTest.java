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

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/dashboard/stats through the real security chain and the real queries on H2 (the PostgreSQL
 * numbers are checked in DashboardServicePostgresTest): roles, status codes, JSON shape and the 400 for a bad range.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardControllerTest {

    private static final String URL = "/api/v1/dashboard/stats";

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

    // ----- roles -----

    @Test
    @DisplayName("No token -> 401 AUTHENTICATION_REQUIRED")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("ASSISTANT -> 403 ACCESS_DENIED for every range")
    void assistantIsForbidden() throws Exception {
        mockMvc.perform(get(URL).with(user("assistant").roles("ASSISTANT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get(URL).param("days", "30").with(user("assistant").roles("ASSISTANT")))
                .andExpect(status().isForbidden());
        // role is checked before the parameter: an assistant cannot probe the validation either
        mockMvc.perform(get(URL).param("days", "14").with(user("assistant").roles("ASSISTANT")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A user without any known role -> 403")
    void unknownRoleIsForbidden() throws Exception {
        mockMvc.perform(get(URL).with(user("outsider").roles("GUEST")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Sub-paths are PHARMACIST only too")
    void subPathsAreProtected() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/anything").with(user("assistant").roles("ASSISTANT")))
                .andExpect(status().isForbidden());
    }

    // ----- success -----

    @Test
    @DisplayName("PHARMACIST -> 200 with the documented shape; the figures come from the database")
    void pharmacistGetsTheStats() throws Exception {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Provider provider = provider("Dr. Green", "1111111111");
        addOrder(provider, "Gamunex-C", now, CarePlan.Status.COMPLETED, 10);
        addOrder(provider, "Gamunex-C", now, CarePlan.Status.FAILED, 6);

        mockMvc.perform(get(URL).with(user("pharmacist").roles("PHARMACIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.timezone").value("UTC"))
                .andExpect(jsonPath("$.to").value(now.toLocalDate().toString()))
                .andExpect(jsonPath("$.from").value(now.toLocalDate().minusDays(6).toString()))
                .andExpect(jsonPath("$.dailyOrders", hasSize(7)))
                .andExpect(jsonPath("$.dailyOrders[6].date").value(now.toLocalDate().toString()))
                .andExpect(jsonPath("$.dailyOrders[6].count").value(2))
                .andExpect(jsonPath("$.dailyOrders[0].count").value(0))
                .andExpect(jsonPath("$.dailyTrend", hasSize(7)))
                .andExpect(jsonPath("$.dailyTrend[6].date").value(now.toLocalDate().toString()))
                .andExpect(jsonPath("$.dailyTrend[6].completedCount").value(1))
                .andExpect(jsonPath("$.dailyTrend[6].failedCount").value(1))
                .andExpect(jsonPath("$.dailyTrend[6].failureRate").value(0.5))
                .andExpect(jsonPath("$.dailyTrend[6].avgGenerationSeconds").value(10.0))
                .andExpect(jsonPath("$.dailyTrend[0].completedCount").value(0))
                .andExpect(jsonPath("$.dailyTrend[0].failureRate").value(nullValue()))
                .andExpect(jsonPath("$.dailyTrend[0].avgGenerationSeconds").value(nullValue()))
                .andExpect(jsonPath("$.kpi.totalOrders").value(2))
                .andExpect(jsonPath("$.kpi.completedCount").value(1))
                .andExpect(jsonPath("$.kpi.failedCount").value(1))
                .andExpect(jsonPath("$.kpi.failureRate").value(0.5))
                .andExpect(jsonPath("$.kpi.avgGenerationSeconds").value(10.0))
                .andExpect(jsonPath("$.kpi.manualUploadCount").value(0))
                .andExpect(jsonPath("$.statusBreakdown", hasSize(4)))
                .andExpect(jsonPath("$.statusBreakdown[2].status").value("COMPLETED"))
                .andExpect(jsonPath("$.statusBreakdown[2].count").value(1))
                .andExpect(jsonPath("$.statusBreakdown[2].share").value(0.5))
                .andExpect(jsonPath("$.topMedications[0].medicationName").value("Gamunex-C"))
                .andExpect(jsonPath("$.topMedications[0].orderCount").value(2))
                .andExpect(jsonPath("$.providerStats[0].providerName").value("Dr. Green"))
                .andExpect(jsonPath("$.providerStats[0].orderCount").value(2))
                .andExpect(jsonPath("$.providerStats[0].failedCount").value(1))
                .andExpect(jsonPath("$.providerStats[0].avgGenerationSeconds").value(10.0));
    }

    @Test
    @DisplayName("No days parameter -> 7; days=30 -> 30 daily entries (orders and trend)")
    void defaultsToSevenAndAcceptsThirty() throws Exception {
        mockMvc.perform(get(URL).with(user("pharmacist").roles("PHARMACIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.dailyOrders", hasSize(7)))
                .andExpect(jsonPath("$.dailyTrend", hasSize(7)));
        mockMvc.perform(get(URL).param("days", "30").with(user("pharmacist").roles("PHARMACIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(30))
                .andExpect(jsonPath("$.dailyOrders", hasSize(30)))
                .andExpect(jsonPath("$.dailyTrend", hasSize(30)));
    }

    @Test
    @DisplayName("Empty database -> 200, zero counts, and null (not 0) for the rate and the averages")
    void emptyDatabaseGivesNulls() throws Exception {
        mockMvc.perform(get(URL).with(user("pharmacist").roles("PHARMACIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpi.totalOrders").value(0))
                .andExpect(jsonPath("$.dailyTrend", hasSize(7)))
                .andExpect(jsonPath("$.dailyTrend[3].completedCount").value(0))
                .andExpect(jsonPath("$.dailyTrend[3].failureRate").value(nullValue()))
                .andExpect(jsonPath("$.dailyTrend[3].avgGenerationSeconds").value(nullValue()))
                .andExpect(jsonPath("$.kpi.failureRate").value(nullValue()))
                .andExpect(jsonPath("$.kpi.avgGenerationSeconds").value(nullValue()))
                .andExpect(jsonPath("$.statusBreakdown", hasSize(4)))
                .andExpect(jsonPath("$.topMedications", hasSize(0)))
                .andExpect(jsonPath("$.providerStats", hasSize(0)));
    }

    @Test
    @DisplayName("The response contains no patient name or MRN")
    void responseHasNoPatientData() throws Exception {
        addOrder(provider("Dr. Green", "1111111111"), "Gamunex-C", LocalDateTime.now(ZoneOffset.UTC),
                CarePlan.Status.COMPLETED, 5);

        String body = mockMvc.perform(get(URL).with(user("pharmacist").roles("PHARMACIST")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Zelda").doesNotContain("Quill").doesNotContain("654321");
    }

    // ----- bad range -----

    @Test
    @DisplayName("days=14 (or any other number) -> 400 INVALID_DAYS with a fixed message")
    void otherRangesAreRejected() throws Exception {
        for (String days : new String[] {"14", "0", "-7", "31", "365"}) {
            mockMvc.perform(get(URL).param("days", days).with(user("pharmacist").roles("PHARMACIST")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("validation"))
                    .andExpect(jsonPath("$.code").value("INVALID_DAYS"))
                    .andExpect(jsonPath("$.message").value("days must be 7 or 30"));
        }
    }

    @Test
    @DisplayName("days=abc -> 400 INVALID_REQUEST_PARAM (must be a number)")
    void nonNumericRangeIsRejected() throws Exception {
        mockMvc.perform(get(URL).param("days", "abc").with(user("pharmacist").roles("PHARMACIST")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAM"))
                .andExpect(jsonPath("$.detail.days").value("must be a number"));
    }

    // ----- data -----

    private Provider provider(String name, String npi) {
        Provider provider = new Provider();
        provider.setName(name);
        provider.setNpi(npi);
        return providerRepository.save(provider);
    }

    private void addOrder(Provider provider, String medication, LocalDateTime createdAt, CarePlan.Status status,
                          int runSeconds) {
        Patient patient = new Patient();
        patient.setFirstName("Zelda");
        patient.setLastName("Quill");
        patient.setMrn(String.valueOf(654321 - orderRepository.count()));
        patient = patientRepository.save(patient);

        Order order = new Order();
        order.setPatient(patient);
        order.setProvider(provider);
        order.setMedicationName(medication);
        order.setCreatedAt(createdAt);
        order = orderRepository.save(order);

        CarePlan carePlan = new CarePlan();
        carePlan.setOrder(order);
        carePlan.setStatus(status);
        carePlan.setStartedAt(createdAt);
        carePlan.setFinishedAt(createdAt.plusSeconds(runSeconds));
        carePlanRepository.save(carePlan);
    }
}

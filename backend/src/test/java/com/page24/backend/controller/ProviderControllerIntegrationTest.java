package com.page24.backend.controller;

import com.page24.backend.entity.CarePlan;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProviderControllerIntegrationTest {

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
    private DataInitializationService dataInitializationService;

    @MockitoBean
    private QueueService queueService;

    @BeforeEach
    void cleanDatabase() {
        carePlanRepository.deleteAll();
        orderRepository.deleteAll();
        patientRepository.deleteAll();
        providerRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /providers - creates provider with 201")
    void shouldCreateProvider() throws Exception {
        mockMvc.perform(post("/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("Dr. Jane Wilson", "1234567890")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Dr. Jane Wilson"))
                .andExpect(jsonPath("$.npi").value("1234567890"))
                .andExpect(jsonPath("$.created_at").exists());
    }

    @Test
    @DisplayName("POST /providers - blank name returns 400")
    void shouldRejectBlankName() throws Exception {
        mockMvc.perform(post("/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("   ", "1234567890")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.details.name").value("name is required"));
    }

    @Test
    @DisplayName("POST /providers - invalid NPI returns 400")
    void shouldRejectInvalidNpi() throws Exception {
        mockMvc.perform(post("/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("Dr. Jane Wilson", "123456789")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.details.npi").value("NPI must be exactly 10 digits"));
    }

    @Test
    @DisplayName("POST /providers - same name with different NPI returns duplicate warning")
    void shouldWarnForSameNameWithDifferentNpi() throws Exception {
        Provider existingProvider = saveProvider("Dr. Jane Wilson", "9876543210");

        mockMvc.perform(post("/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("dr. jane wilson", "1234567890")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.warning").value(
                        "A provider named 'Dr. Jane Wilson' already exists with NPI 9876543210. "
                                + "Please verify this is not a duplicate."
                ))
                .andExpect(jsonPath("$.existing_provider_id").value(existingProvider.getId()));
    }

    @Test
    @DisplayName("POST /providers - same NPI with different name returns conflict")
    void shouldRejectSameNpiWithDifferentName() throws Exception {
        Provider existingProvider = saveProvider("Dr. Jane Wilson", "1234567890");

        mockMvc.perform(post("/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("Dr. John Wilson", "1234567890")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        "Provider conflict: NPI 1234567890 already belongs to 'Dr. Jane Wilson'"
                ))
                .andExpect(jsonPath("$.existing_provider_id").value(existingProvider.getId()));
    }

    @Test
    @DisplayName("POST /providers - same NPI and name returns conflict")
    void shouldRejectDuplicateNpiAndName() throws Exception {
        Provider existingProvider = saveProvider("Dr. Jane Wilson", "1234567890");

        mockMvc.perform(post("/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("dr. jane wilson", "1234567890")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("A provider with NPI 1234567890 already exists"))
                .andExpect(jsonPath("$.existing_provider_id").value(existingProvider.getId()));
    }

    private Provider saveProvider(String name, String npi) {
        Provider provider = new Provider();
        provider.setName(name);
        provider.setNpi(npi);
        return providerRepository.save(provider);
    }

    private String requestJson(String name, String npi) {
        return """
                {
                  "name": "%s",
                  "npi": "%s"
                }
                """.formatted(name, npi);
    }
}

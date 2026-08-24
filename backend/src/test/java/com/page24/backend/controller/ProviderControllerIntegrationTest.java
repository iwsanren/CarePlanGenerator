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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
                .andExpect(jsonPath("$.phone").isEmpty())
                .andExpect(jsonPath("$.fax").isEmpty())
                .andExpect(jsonPath("$.created_at").exists())
                .andExpect(jsonPath("$.updated_at").exists());
    }

    @Test
    @DisplayName("POST /api/v1/providers - creates provider with optional contact fields")
    void shouldCreateProviderViaV1RouteWithContactFields() throws Exception {
        mockMvc.perform(post("/api/v1/providers/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(
                                "Dr. Jane Wilson",
                                "1234567890",
                                "+1-555-0100",
                                "+1-555-0101"
                        )))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Dr. Jane Wilson"))
                .andExpect(jsonPath("$.npi").value("1234567890"))
                .andExpect(jsonPath("$.phone").value("+1-555-0100"))
                .andExpect(jsonPath("$.fax").value("+1-555-0101"))
                .andExpect(jsonPath("$.created_at").exists())
                .andExpect(jsonPath("$.updated_at").exists());
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

    @Test
    @DisplayName("GET /api/v1/providers - returns Provider list sorted by name")
    void shouldListProvidersSortedByName() throws Exception {
        Provider firstProvider = saveProvider("Dr. Aaron Adams", "1111111111");
        Provider secondProvider = saveProvider("Dr. Zoe Wilson", "2222222222");

        mockMvc.perform(providerListRequest(1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.next").isEmpty())
                .andExpect(jsonPath("$.previous").isEmpty())
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].id").value(firstProvider.getId()))
                .andExpect(jsonPath("$.results[0].npi").value("1111111111"))
                .andExpect(jsonPath("$.results[0].name").value("Dr. Aaron Adams"))
                .andExpect(jsonPath("$.results[0].created_at").doesNotExist())
                .andExpect(jsonPath("$.results[1].id").value(secondProvider.getId()))
                .andExpect(jsonPath("$.results[1].name").value("Dr. Zoe Wilson"));
    }

    @Test
    @DisplayName("GET /api/v1/providers - paginates in fixed pages of twenty")
    void shouldPaginateProvidersWithFixedPageSize() throws Exception {
        for (int index = 1; index <= 21; index++) {
            saveProvider("Provider %02d".formatted(index), "%010d".formatted(index));
        }

        mockMvc.perform(providerListRequest(1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(21))
                .andExpect(jsonPath("$.next").value("http://localhost:8080/api/v1/providers/?page=2"))
                .andExpect(jsonPath("$.previous").isEmpty())
                .andExpect(jsonPath("$.results.length()").value(20))
                .andExpect(jsonPath("$.results[0].name").value("Provider 01"));

        mockMvc.perform(providerListRequest(2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(21))
                .andExpect(jsonPath("$.next").isEmpty())
                .andExpect(jsonPath("$.previous").value("http://localhost:8080/api/v1/providers/?page=1"))
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].name").value("Provider 21"));
    }

    @Test
    @DisplayName("GET /api/v1/providers/by-id/{id} - returns the complete Provider record")
    void shouldGetProviderById() throws Exception {
        Provider provider = saveProvider("Dr. Jane Wilson", "1234567890");
        provider.setPhone("+1-555-0100");
        provider.setFax("+1-555-0101");
        provider = providerRepository.saveAndFlush(provider);

        mockMvc.perform(get("/api/v1/providers/by-id/{id}/", provider.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(provider.getId()))
                .andExpect(jsonPath("$.npi").value("1234567890"))
                .andExpect(jsonPath("$.name").value("Dr. Jane Wilson"))
                .andExpect(jsonPath("$.phone").value("+1-555-0100"))
                .andExpect(jsonPath("$.fax").value("+1-555-0101"))
                .andExpect(jsonPath("$.created_at").exists())
                .andExpect(jsonPath("$.updated_at").exists());
    }

    @Test
    @DisplayName("GET /api/v1/providers/by-id/{id} - returns reference-style 404 when absent")
    void shouldReturnNotFoundWhenProviderDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/v1/providers/by-id/999999/"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"))
                .andExpect(jsonPath("$.message").value("No Provider matches the given query."))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @Test
    @DisplayName("GET /api/v1/providers/by-npi/{npi} - returns the complete Provider record")
    void shouldGetProviderByNpi() throws Exception {
        Provider provider = saveProvider("Dr. Jane Wilson", "1234567890");
        provider.setPhone("+1-555-0100");
        provider.setFax("+1-555-0101");
        providerRepository.saveAndFlush(provider);

        mockMvc.perform(get("/api/v1/providers/by-npi/1234567890/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(provider.getId()))
                .andExpect(jsonPath("$.npi").value("1234567890"))
                .andExpect(jsonPath("$.name").value("Dr. Jane Wilson"))
                .andExpect(jsonPath("$.phone").value("+1-555-0100"))
                .andExpect(jsonPath("$.fax").value("+1-555-0101"))
                .andExpect(jsonPath("$.created_at").exists())
                .andExpect(jsonPath("$.updated_at").exists());
    }

    @Test
    @DisplayName("GET /api/v1/providers/by-npi/{npi} - returns requested 404 body when absent")
    void shouldReturnProviderNotFoundWhenNpiDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/v1/providers/by-npi/1234567890/"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Provider not found"));
    }

    @Test
    @DisplayName("PUT /api/v1/providers/{id} - fully replaces Provider fields")
    void shouldFullyUpdateProvider() throws Exception {
        Provider provider = saveProvider("Dr. Jane Wilson", "1234567890");
        provider.setPhone("+1-555-0100");
        provider.setFax("+1-555-0101");
        provider = providerRepository.saveAndFlush(provider);

        mockMvc.perform(put("/api/v1/providers/{id}/", provider.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(
                                "Dr. Jane M. Wilson",
                                "1234567890",
                                "+1-555-0199",
                                "+1-555-0101"
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(provider.getId()))
                .andExpect(jsonPath("$.npi").value("1234567890"))
                .andExpect(jsonPath("$.name").value("Dr. Jane M. Wilson"))
                .andExpect(jsonPath("$.phone").value("+1-555-0199"))
                .andExpect(jsonPath("$.fax").value("+1-555-0101"))
                .andExpect(jsonPath("$.created_at").exists())
                .andExpect(jsonPath("$.updated_at").exists());
    }

    @Test
    @DisplayName("PUT /api/v1/providers/{id} - omitting optional contacts clears them")
    void shouldClearOptionalContactFieldsOnFullUpdate() throws Exception {
        Provider provider = saveProvider("Dr. Jane Wilson", "1234567890");
        provider.setPhone("+1-555-0100");
        provider.setFax("+1-555-0101");
        provider = providerRepository.saveAndFlush(provider);

        mockMvc.perform(put("/api/v1/providers/{id}/", provider.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("Dr. Jane Wilson", "1234567890")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").isEmpty())
                .andExpect(jsonPath("$.fax").isEmpty());
    }

    @Test
    @DisplayName("PUT /api/v1/providers/{id} - missing required NPI returns 400")
    void shouldRequireNpiForFullUpdate() throws Exception {
        Provider provider = saveProvider("Dr. Jane Wilson", "1234567890");

        mockMvc.perform(put("/api/v1/providers/{id}/", provider.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Dr. Jane M. Wilson" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.details.npi").value("npi is required"));
    }

    @Test
    @DisplayName("PATCH /api/v1/providers/{id} - updates only supplied fields")
    void shouldPatchOnlySuppliedProviderFields() throws Exception {
        Provider provider = saveProvider("Dr. Jane M. Wilson", "1234567890");
        provider.setPhone("+1-555-0199");
        provider.setFax("+1-555-0101");
        provider = providerRepository.saveAndFlush(provider);

        mockMvc.perform(patch("/api/v1/providers/{id}/", provider.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "phone": "+1-555-0200" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(provider.getId()))
                .andExpect(jsonPath("$.npi").value("1234567890"))
                .andExpect(jsonPath("$.name").value("Dr. Jane M. Wilson"))
                .andExpect(jsonPath("$.phone").value("+1-555-0200"))
                .andExpect(jsonPath("$.fax").value("+1-555-0101"))
                .andExpect(jsonPath("$.created_at").exists())
                .andExpect(jsonPath("$.updated_at").exists());
    }

    @Test
    @DisplayName("PATCH /api/v1/providers/{id} - explicit null clears an optional contact")
    void shouldClearOptionalContactWhenPatchContainsNull() throws Exception {
        Provider provider = saveProvider("Dr. Jane Wilson", "1234567890");
        provider.setPhone("+1-555-0100");
        provider.setFax("+1-555-0101");
        provider = providerRepository.saveAndFlush(provider);

        mockMvc.perform(patch("/api/v1/providers/{id}/", provider.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "phone": null }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").isEmpty())
                .andExpect(jsonPath("$.fax").value("+1-555-0101"));
    }

    @Test
    @DisplayName("PATCH /api/v1/providers/{id} - validates a supplied NPI")
    void shouldValidateSuppliedNpiOnPatch() throws Exception {
        Provider provider = saveProvider("Dr. Jane Wilson", "1234567890");

        mockMvc.perform(patch("/api/v1/providers/{id}/", provider.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "npi": "123" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.details.npi").value("NPI must be exactly 10 digits"));
    }

    @Test
    @DisplayName("DELETE /api/v1/providers/{id} - deletes Provider and returns 204 with no body")
    void shouldDeleteProvider() throws Exception {
        Provider provider = saveProvider("Dr. Jane Wilson", "1234567890");

        mockMvc.perform(delete("/api/v1/providers/{id}/", provider.getId()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        mockMvc.perform(get("/api/v1/providers/by-id/{id}/", provider.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/v1/providers/{id} - rejects deletion when Orders reference the Provider")
    void shouldRejectDeletingProviderWithAssociatedOrders() throws Exception {
        Provider provider = saveProvider("Dr. Jane Wilson", "1234567890");
        Order order = saveOrderForProvider(provider);

        mockMvc.perform(delete("/api/v1/providers/{id}/", provider.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        "Provider cannot be deleted because it is associated with existing orders"
                ))
                .andExpect(jsonPath("$.order_ids[0]").value(order.getId()));

        mockMvc.perform(get("/api/v1/providers/by-id/{id}/", provider.getId()))
                .andExpect(status().isOk());
    }

    private Provider saveProvider(String name, String npi) {
        Provider provider = new Provider();
        provider.setName(name);
        provider.setNpi(npi);
        return providerRepository.save(provider);
    }

    private Order saveOrderForProvider(Provider provider) {
        Patient patient = new Patient();
        patient.setFirstName("John");
        patient.setLastName("Doe");
        patient.setMrn("123456");
        patient = patientRepository.save(patient);

        Order order = new Order();
        order.setPatient(patient);
        order.setProvider(provider);
        order.setMedicationName("IVIG");
        return orderRepository.save(order);
    }

    private String requestJson(String name, String npi) {
        return """
                {
                  "name": "%s",
                  "npi": "%s"
                }
                """.formatted(name, npi);
    }

    private String requestJson(String name, String npi, String phone, String fax) {
        return """
                {
                  "name": "%s",
                  "npi": "%s",
                  "phone": "%s",
                  "fax": "%s"
                }
                """.formatted(name, npi, phone, fax);
    }

    private MockHttpServletRequestBuilder providerListRequest(int page) {
        return get("/api/v1/providers/")
                .param("page", String.valueOf(page))
                .with(request -> {
                    request.setServerName("localhost");
                    request.setServerPort(8080);
                    request.setScheme("http");
                    return request;
                });
    }
}

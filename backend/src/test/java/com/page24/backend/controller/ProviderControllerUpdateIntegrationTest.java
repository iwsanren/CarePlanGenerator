package com.page24.backend.controller;

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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT and PATCH on providers: NPI ownership conflicts, field-by-field updates, validation and the error body for
 * unreadable JSON. Every rejected request is followed by a database check that nothing was changed.
 */
@WithMockUser(username = "test-pharmacist", roles = "PHARMACIST")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProviderControllerUpdateIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private CarePlanRepository carePlanRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private ProviderRepository providerRepository;

    @MockitoBean private DataInitializationService dataInitializationService;
    @MockitoBean private QueueService queueService;

    private Provider jane;

    @BeforeEach
    void cleanDatabaseAndCreateJane() {
        carePlanRepository.deleteAll();
        orderRepository.deleteAll();
        patientRepository.deleteAll();
        providerRepository.deleteAll();

        jane = saveProvider("Dr. Jane Wilson", "1234567890", "+1-555-0100", "+1-555-0101");
    }

    // ----- PUT -----

    @Test
    @DisplayName("PUT /api/v1/providers/{id} - NPI that belongs to another provider returns 409 and changes nothing")
    void shouldRejectPutWhenTheNpiBelongsToAnotherProvider() throws Exception {
        Provider other = saveProvider("Dr. Other", "9876543210", null, null);

        mockMvc.perform(put("/api/v1/providers/{id}/", jane.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(putBody("Dr. Jane M. Wilson", "9876543210")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Provider conflict: NPI 9876543210 already belongs to 'Dr. Other'"))
                .andExpect(jsonPath("$.existing_provider_id").value(other.getId()));

        Provider stored = reload(jane);
        assertThat(stored.getName()).isEqualTo("Dr. Jane Wilson");
        assertThat(stored.getNpi()).isEqualTo("1234567890");
        assertThat(reload(other).getName()).isEqualTo("Dr. Other");
    }

    @Test
    @DisplayName("PUT /api/v1/providers/{id} - keeping the provider's own NPI is not a conflict; the name is trimmed")
    void shouldAllowPutThatKeepsTheOwnNpiAndTrimTheName() throws Exception {
        mockMvc.perform(put("/api/v1/providers/{id}/", jane.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(putBody("  Dr. Jane M. Wilson  ", "1234567890")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Dr. Jane M. Wilson"))
                .andExpect(jsonPath("$.npi").value("1234567890"));

        assertThat(reload(jane).getName()).isEqualTo("Dr. Jane M. Wilson");
    }

    @Test
    @DisplayName("PUT /api/v1/providers/{id} - unknown provider returns the 404 body")
    void shouldReturnNotFoundWhenPutTargetsAnUnknownProvider() throws Exception {
        mockMvc.perform(put("/api/v1/providers/{id}/", jane.getId() + 1000)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(putBody("Dr. Jane Wilson", "1234567890")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"))
                .andExpect(jsonPath("$.message").value("No Provider matches the given query."));
    }

    // ----- PATCH: NPI -----

    @Test
    @DisplayName("PATCH /api/v1/providers/{id} - a new unused NPI is saved and the other fields stay as they were")
    void shouldPatchTheNpiOnly() throws Exception {
        mockMvc.perform(patch("/api/v1/providers/{id}/", jane.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "npi": "1111111111" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.npi").value("1111111111"))
                .andExpect(jsonPath("$.name").value("Dr. Jane Wilson"));

        Provider stored = reload(jane);
        assertThat(stored.getNpi()).isEqualTo("1111111111");
        assertThat(stored.getName()).isEqualTo("Dr. Jane Wilson");
        assertThat(stored.getPhone()).isEqualTo("+1-555-0100");
        assertThat(stored.getFax()).isEqualTo("+1-555-0101");
        assertThat(providerRepository.findByNpi("1234567890")).isEmpty();
    }

    @Test
    @DisplayName("PATCH /api/v1/providers/{id} - NPI that belongs to another provider returns 409 and changes nothing")
    void shouldRejectPatchWhenTheNpiBelongsToAnotherProvider() throws Exception {
        Provider other = saveProvider("Dr. Other", "9876543210", null, null);

        mockMvc.perform(patch("/api/v1/providers/{id}/", jane.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "npi": "9876543210", "name": "Should not be saved" }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Provider conflict: NPI 9876543210 already belongs to 'Dr. Other'"))
                .andExpect(jsonPath("$.existing_provider_id").value(other.getId()));

        Provider stored = reload(jane);
        assertThat(stored.getNpi()).isEqualTo("1234567890");
        // The name in the same request must not slip through when the NPI is rejected.
        assertThat(stored.getName()).isEqualTo("Dr. Jane Wilson");
        assertThat(reload(other).getNpi()).isEqualTo("9876543210");
    }

    @Test
    @DisplayName("PATCH /api/v1/providers/{id} - sending the provider's own NPI is not a conflict")
    void shouldAllowPatchThatRepeatsTheOwnNpi() throws Exception {
        mockMvc.perform(patch("/api/v1/providers/{id}/", jane.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "npi": "1234567890", "phone": "+1-555-0300" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.npi").value("1234567890"))
                .andExpect(jsonPath("$.phone").value("+1-555-0300"));

        assertThat(reload(jane).getPhone()).isEqualTo("+1-555-0300");
    }

    // ----- PATCH: other fields -----

    @Test
    @DisplayName("PATCH /api/v1/providers/{id} - the name is trimmed and the other fields stay as they were")
    void shouldPatchTheNameOnly() throws Exception {
        mockMvc.perform(patch("/api/v1/providers/{id}/", jane.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "  Dr. Jane M. Wilson " }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Dr. Jane M. Wilson"));

        Provider stored = reload(jane);
        assertThat(stored.getName()).isEqualTo("Dr. Jane M. Wilson");
        assertThat(stored.getNpi()).isEqualTo("1234567890");
        assertThat(stored.getPhone()).isEqualTo("+1-555-0100");
        assertThat(stored.getFax()).isEqualTo("+1-555-0101");
    }

    @Test
    @DisplayName("PATCH /api/v1/providers/{id} - only the fax changes when only the fax is sent")
    void shouldPatchTheFaxOnly() throws Exception {
        mockMvc.perform(patch("/api/v1/providers/{id}/", jane.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "fax": "+1-555-0999" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fax").value("+1-555-0999"));

        Provider stored = reload(jane);
        assertThat(stored.getFax()).isEqualTo("+1-555-0999");
        assertThat(stored.getPhone()).isEqualTo("+1-555-0100");
        assertThat(stored.getName()).isEqualTo("Dr. Jane Wilson");
    }

    @Test
    @DisplayName("PATCH /api/v1/providers/{id} - an empty body changes nothing")
    void shouldLeaveEverythingUnchangedForAnEmptyPatch() throws Exception {
        mockMvc.perform(patch("/api/v1/providers/{id}/", jane.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Dr. Jane Wilson"))
                .andExpect(jsonPath("$.npi").value("1234567890"))
                .andExpect(jsonPath("$.phone").value("+1-555-0100"))
                .andExpect(jsonPath("$.fax").value("+1-555-0101"));

        Provider stored = reload(jane);
        assertThat(stored.getName()).isEqualTo("Dr. Jane Wilson");
        assertThat(stored.getPhone()).isEqualTo("+1-555-0100");
    }

    // ----- PATCH: validation -----

    @Test
    @DisplayName("PATCH /api/v1/providers/{id} - explicit null name returns 400 for 'name' and changes nothing")
    void shouldRejectAnExplicitNullName() throws Exception {
        mockMvc.perform(patch("/api/v1/providers/{id}/", jane.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": null, "phone": "+1-555-0777" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.details.name").value("name is required"));

        Provider stored = reload(jane);
        assertThat(stored.getName()).isEqualTo("Dr. Jane Wilson");
        assertThat(stored.getPhone()).isEqualTo("+1-555-0100");
    }

    @Test
    @DisplayName("PATCH /api/v1/providers/{id} - explicit null NPI returns 400 for 'npi' and changes nothing")
    void shouldRejectAnExplicitNullNpi() throws Exception {
        mockMvc.perform(patch("/api/v1/providers/{id}/", jane.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "npi": null }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.details.npi").value("npi is required"));

        assertThat(reload(jane).getNpi()).isEqualTo("1234567890");
    }

    @Test
    @DisplayName("PATCH /api/v1/providers/{id} - blank name returns 400 and changes nothing")
    void shouldRejectABlankName() throws Exception {
        mockMvc.perform(patch("/api/v1/providers/{id}/", jane.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "   " }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.name").value("name cannot be blank"));

        assertThat(reload(jane).getName()).isEqualTo("Dr. Jane Wilson");
    }

    @Test
    @DisplayName("PATCH /api/v1/providers/{id} - unknown provider returns the 404 body")
    void shouldReturnNotFoundWhenPatchTargetsAnUnknownProvider() throws Exception {
        mockMvc.perform(patch("/api/v1/providers/{id}/", jane.getId() + 1000)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "phone": "+1-555-0300" }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    // ----- unreadable JSON -----

    @Test
    @DisplayName("PATCH /api/v1/providers/{id} - malformed JSON returns a fixed 400 body that echoes nothing")
    void shouldRejectMalformedJsonOnPatch() throws Exception {
        mockMvc.perform(patch("/api/v1/providers/{id}/", jane.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"name\": \"Dr. Secret\", "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.details.body").value("Malformed or unreadable JSON request body"));

        assertThat(reload(jane).getName()).isEqualTo("Dr. Jane Wilson");
    }

    @Test
    @DisplayName("PUT /api/v1/providers/{id} - a body that is not JSON returns the same fixed 400 body")
    void shouldRejectMalformedJsonOnPut() throws Exception {
        mockMvc.perform(put("/api/v1/providers/{id}/", jane.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("this is not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.body").value("Malformed or unreadable JSON request body"));
    }

    // ----- permissions -----

    @Test
    @WithMockUser(username = "test-assistant", roles = "ASSISTANT")
    @DisplayName("ASSISTANT can update providers with PUT and PATCH (only DELETE is restricted)")
    void shouldLetAnAssistantUpdateProviders() throws Exception {
        mockMvc.perform(put("/api/v1/providers/{id}/", jane.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(putBody("Dr. Jane M. Wilson", "1234567890")))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/providers/{id}/", jane.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "phone": "+1-555-0500" }
                                """))
                .andExpect(status().isOk());

        Provider stored = reload(jane);
        assertThat(stored.getName()).isEqualTo("Dr. Jane M. Wilson");
        assertThat(stored.getPhone()).isEqualTo("+1-555-0500");

        // Proves the ASSISTANT role really applies here: the one restricted operation is still refused.
        mockMvc.perform(delete("/api/v1/providers/{id}/", jane.getId()))
                .andExpect(status().isForbidden());
        assertThat(providerRepository.existsById(jane.getId())).isTrue();
    }

    // ----- helpers -----

    private Provider saveProvider(String name, String npi, String phone, String fax) {
        Provider provider = new Provider();
        provider.setName(name);
        provider.setNpi(npi);
        provider.setPhone(phone);
        provider.setFax(fax);
        return providerRepository.saveAndFlush(provider);
    }

    private Provider reload(Provider provider) {
        return providerRepository.findById(provider.getId()).orElseThrow();
    }

    private String putBody(String name, String npi) {
        return """
                { "name": "%s", "npi": "%s" }
                """.formatted(name, npi);
    }
}

package com.page24.backend.config;

import com.jayway.jsonpath.JsonPath;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Docs enabled (profile "docs"): spec and Swagger UI are public, every /api/v1 rule is unchanged. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "docs"})
class OpenApiDocsTest {

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

    private String spec() throws Exception {
        return mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("GET /v3/api-docs is public and describes the API, bearer auth and tags")
    void specIsPublicAndComplete() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value(org.hamcrest.Matchers.startsWith("3.")))
                .andExpect(jsonPath("$.info.title").value("CarePlan Generator API"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/orders'].post.summary").value("Create an order"))
                .andExpect(jsonPath("$.paths['/api/v1/orders/{id}/careplan/download'].get.responses['200'].content['text/plain'].schema.format")
                        .value("binary"))
                .andExpect(jsonPath("$.paths['/api/v1/export'].get.responses['200'].content['text/csv']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/intake/clinic-b'].post.requestBody.content['application/json']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/intake/pharma-corp'].post.requestBody.content['application/xml']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/intake/hospital-d'].post.requestBody.content['text/csv']").exists())
                .andExpect(jsonPath("$.tags[*].name").value(org.hamcrest.Matchers.hasItems(
                        "Orders", "Patients", "Providers", "Intake", "Export", "Reports")));
    }

    @Test
    @DisplayName("Swagger UI is public when docs are enabled")
    void swaggerUiIsPublic() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Enabling docs does not open the API: /api/v1 and other paths still need a token")
    void apiRulesAreUnchanged() throws Exception {
        mockMvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/export")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        // The docs permitAll is GET-only
        mockMvc.perform(post("/v3/api-docs")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Documented Clinic B example is accepted by the real endpoint")
    void clinicBExampleIsAccepted() throws Exception {
        // springdoc embeds a JSON example as a JSON object, not as an escaped string
        Object value = JsonPath.read(spec(),
                "$.paths['/api/v1/intake/clinic-b'].post.requestBody.content['application/json'].examples['Clinic B'].value");
        String example = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        mockMvc.perform(post("/api/v1/intake/clinic-b").with(user("docs-test").roles("PHARMACIST"))
                        .contentType(MediaType.APPLICATION_JSON).content(example))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultType").value("SUCCESS"));
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Documented PharmaCorp example is accepted by the real endpoint")
    void pharmaCorpExampleIsAccepted() throws Exception {
        String example = JsonPath.read(spec(),
                "$.paths['/api/v1/intake/pharma-corp'].post.requestBody.content['application/xml'].examples['PharmaCorp'].value");
        mockMvc.perform(post("/api/v1/intake/pharma-corp").with(user("docs-test").roles("PHARMACIST"))
                        .contentType(MediaType.APPLICATION_XML).content(example))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultType").value("SUCCESS"));
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Documented Hospital D example is accepted by the real endpoint")
    void hospitalDExampleIsAccepted() throws Exception {
        String example = JsonPath.read(spec(),
                "$.paths['/api/v1/intake/hospital-d'].post.requestBody.content['text/csv'].examples['Hospital D'].value");
        mockMvc.perform(post("/api/v1/intake/hospital-d").with(user("docs-test").roles("PHARMACIST"))
                        .contentType("text/csv").content(example.trim()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultType").value("SUCCESS"));
        assertThat(orderRepository.count()).isEqualTo(1);
    }
}

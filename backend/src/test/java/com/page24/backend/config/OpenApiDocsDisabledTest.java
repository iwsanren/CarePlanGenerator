package com.page24.backend.config;

import com.page24.backend.service.DataInitializationService;
import com.page24.backend.service.QueueService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Default configuration (docs profile NOT active): the spec and Swagger UI must not be served. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiDocsDisabledTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private QueueService queueService;
    @MockitoBean private DataInitializationService dataInitializationService;

    @Test
    @DisplayName("Anonymous requests to the spec and Swagger UI are rejected by the existing denyAll rule")
    void docsAreNotPublicByDefault() throws Exception {
        for (String path : new String[]{"/v3/api-docs", "/v3/api-docs.yaml", "/swagger-ui.html", "/swagger-ui/index.html"}) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("Even an authenticated pharmacist does not get the spec or UI by default")
    void docsAreNotServedEvenToStaff() throws Exception {
        for (String path : new String[]{"/v3/api-docs", "/swagger-ui/index.html"}) {
            mockMvc.perform(get(path).with(user("staff").roles("PHARMACIST"))).andExpect(status().isForbidden());
        }
    }
}

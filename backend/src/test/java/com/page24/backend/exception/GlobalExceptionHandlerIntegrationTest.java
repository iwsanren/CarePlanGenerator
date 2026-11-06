package com.page24.backend.exception;

import com.page24.backend.service.DataInitializationService;
import com.page24.backend.service.QueueService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mistakes made by the caller must be answered with a 4xx status and the usual error body, not with a 500.
 * Every body is also checked for not repeating what the caller sent.
 */
@org.springframework.security.test.context.support.WithMockUser(username = "test-pharmacist", roles = "PHARMACIST")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GlobalExceptionHandlerIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private QueueService queueService;
    @MockitoBean private DataInitializationService dataInitializationService;

    @ParameterizedTest(name = "{0} -> 400 naming the parameter {1}")
    @CsvSource({
            "/api/v1/orders/abc, id",
            "/api/v1/orders/abc/status, id",
            "/api/v1/orders/abc/careplan/download, id",
            "/api/v1/patients/abc, id",
            "/api/v1/providers/by-id/abc, id",
            "/api/v1/orders?page=abc, page",
            "/api/v1/orders?page_size=abc, page_size",
            "/api/v1/orders?patient_id=abc, patient_id",
            "/api/v1/reports/orders/export?provider_id=abc, provider_id"
    })
    @DisplayName("A path or query value of the wrong type returns 400 and names only the parameter")
    void shouldReturnBadRequestForAValueOfTheWrongType(String url, String parameter) throws Exception {
        mockMvc.perform(get(url))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("validation"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAM"))
                .andExpect(jsonPath("$.httpStatus").value(400))
                .andExpect(jsonPath("$.detail['" + parameter + "']").value("must be a number"))
                // The rejected value is never repeated back to the caller.
                .andExpect(content().string(not(containsString("abc"))));
    }

    @Test
    @DisplayName("A value that is not a number or a boolean is reported as an invalid value for that parameter")
    void shouldReturnBadRequestForANonBooleanFlag() throws Exception {
        mockMvc.perform(post("/api/v1/intake/clinic-b")
                        .param("confirm", "maybe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAM"))
                .andExpect(jsonPath("$.detail.confirm").value("has an invalid value"))
                .andExpect(content().string(not(containsString("maybe"))));
    }

    @Test
    @DisplayName("A client error is still answered in JSON when the caller only accepts XML")
    void shouldAnswerInJsonEvenWhenTheCallerAsksForXml() throws Exception {
        mockMvc.perform(get("/api/v1/orders/abc").header("Accept", "application/xml"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAM"));
    }

    @Test
    @DisplayName("A method the path does not support returns 405 with an Allow header")
    void shouldReturnMethodNotAllowed() throws Exception {
        mockMvc.perform(delete("/api/v1/orders/1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.httpStatus").value(405));
    }

    @Test
    @DisplayName("A body with an unsupported content type returns 415 and does not repeat it")
    void shouldReturnUnsupportedMediaTypeForABodyThatIsNotJson() throws Exception {
        mockMvc.perform(post("/api/v1/orders").contentType("text/plain").content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.httpStatus").value(415))
                .andExpect(content().string(not(containsString("text/plain"))));
    }

    @Test
    @DisplayName("An upload that is not multipart returns 415")
    void shouldReturnUnsupportedMediaTypeForANonMultipartUpload() throws Exception {
        mockMvc.perform(post("/api/v1/orders/1/careplan/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    @DisplayName("A path that does not exist returns 404 and does not repeat the path")
    void shouldReturnNotFoundForAnUnknownPath() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"))
                .andExpect(jsonPath("$.httpStatus").value(404))
                .andExpect(content().string(not(containsString("does-not-exist"))));
    }
}

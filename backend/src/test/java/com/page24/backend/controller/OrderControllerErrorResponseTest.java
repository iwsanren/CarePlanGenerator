package com.page24.backend.controller;

import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.repository.OrderRepository;
import com.page24.backend.repository.PatientRepository;
import com.page24.backend.repository.ProviderRepository;
import com.page24.backend.service.DataInitializationService;
import com.page24.backend.service.OrderService;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderControllerErrorResponseTest {

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

    @MockitoBean
    private OrderService orderService;

    @BeforeEach
    void cleanDatabase() {
        carePlanRepository.deleteAll();
        orderRepository.deleteAll();
        patientRepository.deleteAll();
        providerRepository.deleteAll();
    }

    @Test
    @DisplayName("Error test: NPI 不是10位 -> 400 validation + 统一错误字段")
    void shouldReturnValidationErrorWhenNpiInvalid() throws Exception {
        String body = """
                {
                  "patientFirstName": "Alice",
                  "patientLastName": "Wong",
                  "patientMrn": "123456",
                  "patientDateOfBirth": "1990-05-10",
                  "providerName": "Dr. Green",
                  "providerNpi": "12345",
                  "medicationName": "IVIG",
                  "primaryDiagnosis": "G70.00",
                  "confirm": false
                }
                """;

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("validation"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.detail.providerNpi").exists())
                .andExpect(jsonPath("$.httpStatus").value(400));
    }

    @Test
    @DisplayName("Error test: MRN 不是6位 -> 400 validation + 统一错误字段")
    void shouldReturnValidationErrorWhenMrnInvalid() throws Exception {
        String body = """
                {
                  "patientFirstName": "Alice",
                  "patientLastName": "Wong",
                  "patientMrn": "1234",
                  "patientDateOfBirth": "1990-05-10",
                  "providerName": "Dr. Green",
                  "providerNpi": "1111111111",
                  "medicationName": "IVIG",
                  "primaryDiagnosis": "G70.00",
                  "confirm": false
                }
                """;

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("validation"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.detail.patientMrn").exists())
                .andExpect(jsonPath("$.httpStatus").value(400));
    }

    @Test
    @DisplayName("Error test: malformed JSON -> 400 validation + 统一错误字段")
    void shouldReturnValidationErrorWhenJsonMalformed() throws Exception {
        String malformedBody = """
                {
                  "patientFirstName": "Alice",
                  "patientLastName": "Wong",
                """;

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("validation"))
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.httpStatus").value(400));
    }

    @Test
    @DisplayName("Error test: 未预期异常 -> 500 + 响应体不暴露异常类名")
    void shouldNotExposeExceptionClassNameOnUnexpectedError() throws Exception {
        when(orderService.getOrderById(anyLong())).thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get("/api/v1/orders/{id}", 999L))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.detail.exception").doesNotExist())
                .andExpect(content().string(not(containsString("RuntimeException"))))
                .andExpect(content().string(not(containsString("boom"))));
    }
}



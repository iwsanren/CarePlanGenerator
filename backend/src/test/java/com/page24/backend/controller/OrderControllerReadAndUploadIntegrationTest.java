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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The single-order endpoint, the order search and the manual care plan upload, all through the real service and an
 * in-memory database. Rejected uploads are followed by a read of the stored care plan to prove it was not touched.
 */
@org.springframework.security.test.context.support.WithMockUser(username = "test-pharmacist", roles = "PHARMACIST")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderControllerReadAndUploadIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private CarePlanRepository carePlanRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private ProviderRepository providerRepository;

    @MockitoBean private QueueService queueService;
    @MockitoBean private DataInitializationService dataInitializationService;

    private Provider provider;

    @BeforeEach
    void cleanDatabaseAndCreateProvider() {
        carePlanRepository.deleteAll();
        orderRepository.deleteAll();
        patientRepository.deleteAll();
        providerRepository.deleteAll();

        Provider newProvider = new Provider();
        newProvider.setName("Dr. Green");
        newProvider.setNpi("1111111111");
        provider = providerRepository.save(newProvider);
    }

    // ----- GET /api/v1/orders/{id} -----

    @Test
    @DisplayName("GET /api/v1/orders/{id} - a completed order returns the patient, provider and care plan content")
    void shouldReturnACompletedOrderWithItsCarePlan() throws Exception {
        Order order = orderWithCarePlan(patient("Alice", "Wong", "100001"), CarePlan.Status.COMPLETED, "Final plan");

        mockMvc.perform(get("/api/v1/orders/{id}", order.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(order.getId()))
                .andExpect(jsonPath("$.patientFirstName").value("Alice"))
                .andExpect(jsonPath("$.patientLastName").value("Wong"))
                .andExpect(jsonPath("$.patientMrn").value("100001"))
                .andExpect(jsonPath("$.providerNpi").value("1111111111"))
                .andExpect(jsonPath("$.medicationName").value("IVIG"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.carePlanContent").value("Final plan"));
    }

    @Test
    @DisplayName("GET /api/v1/orders/{id} - content that is not completed yet is never exposed")
    void shouldNotExposeContentOfAnUnfinishedCarePlan() throws Exception {
        Order order = orderWithCarePlan(patient("Alice", "Wong", "100001"), CarePlan.Status.PROCESSING, "half written");

        mockMvc.perform(get("/api/v1/orders/{id}", order.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.carePlanContent").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/orders/{id} - unknown order returns the 404 error body")
    void shouldReturnNotFoundForAnUnknownOrder() throws Exception {
        mockMvc.perform(get("/api/v1/orders/{id}", 987654))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.httpStatus").value(404));
    }

    @Test
    @DisplayName("GET /api/v1/orders/{id} - an order without a care plan returns CAREPLAN_NOT_FOUND")
    void shouldReportAnOrderThatHasNoCarePlan() throws Exception {
        Order order = orderWithoutCarePlan(patient("Alice", "Wong", "100001"));

        mockMvc.perform(get("/api/v1/orders/{id}", order.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("validation"))
                .andExpect(jsonPath("$.code").value("CAREPLAN_NOT_FOUND"));
    }

    // ----- GET /api/v1/orders/search -----

    @Test
    @DisplayName("GET /api/v1/orders/search?mrn= - returns only the orders of the patient with that MRN")
    void shouldSearchOrdersByMrn() throws Exception {
        Patient alice = patient("Alice", "Wong", "100001");
        orderWithCarePlan(alice, CarePlan.Status.COMPLETED, "plan 1");
        orderWithCarePlan(alice, CarePlan.Status.PENDING, null);
        orderWithCarePlan(patient("Bob", "Lee", "100002"), CarePlan.Status.PENDING, null);

        mockMvc.perform(get("/api/v1/orders/search").param("mrn", "100001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].patientMrn", containsInAnyOrder("100001", "100001")));
    }

    @Test
    @DisplayName("GET /api/v1/orders/search?mrn= - an unknown MRN returns an empty list")
    void shouldReturnAnEmptyListForAnUnknownMrn() throws Exception {
        orderWithCarePlan(patient("Alice", "Wong", "100001"), CarePlan.Status.PENDING, null);

        mockMvc.perform(get("/api/v1/orders/search").param("mrn", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/orders/search?patientName= - matches part of a first or last name, ignoring case")
    void shouldSearchOrdersByPartOfThePatientName() throws Exception {
        orderWithCarePlan(patient("Alice", "Wong", "100001"), CarePlan.Status.PENDING, null);
        orderWithCarePlan(patient("Bob", "Alison", "100002"), CarePlan.Status.PENDING, null);
        orderWithCarePlan(patient("Cathy", "Chen", "100003"), CarePlan.Status.PENDING, null);

        mockMvc.perform(get("/api/v1/orders/search").param("patientName", "ALI"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].patientMrn", containsInAnyOrder("100001", "100002")));
    }

    @Test
    @DisplayName("GET /api/v1/orders/search - the MRN wins when both an MRN and a name are given")
    void shouldLetTheMrnTakePrecedenceOverTheName() throws Exception {
        orderWithCarePlan(patient("Alice", "Wong", "100001"), CarePlan.Status.PENDING, null);
        orderWithCarePlan(patient("Bob", "Lee", "100002"), CarePlan.Status.PENDING, null);

        mockMvc.perform(get("/api/v1/orders/search").param("mrn", "100002").param("patientName", "Alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].patientMrn").value("100002"));
    }

    @Test
    @DisplayName("GET /api/v1/orders/search - without criteria every order is returned, with PENDING for a missing plan")
    void shouldReturnEveryOrderWhenThereAreNoCriteria() throws Exception {
        orderWithCarePlan(patient("Alice", "Wong", "100001"), CarePlan.Status.COMPLETED, "plan");
        orderWithoutCarePlan(patient("Bob", "Lee", "100002"));

        mockMvc.perform(get("/api/v1/orders/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].status", containsInAnyOrder("COMPLETED", "PENDING")));
    }

    // ----- POST /api/v1/orders/{id}/careplan/upload -----

    @Test
    @DisplayName("POST .../careplan/upload - a file replaces the care plan; it is read as UTF-8 and marked uploaded")
    void shouldReplaceTheCarePlanWithAnUploadedFile() throws Exception {
        Order order = orderWithCarePlan(patient("Alice", "Wong", "100001"), CarePlan.Status.FAILED, null);
        CarePlan failed = carePlanOf(order);
        failed.setErrorMessage("LLM service unavailable");
        carePlanRepository.saveAndFlush(failed);
        String text = "护理计划: 每日一次, 10mg";
        MockMultipartFile file = new MockMultipartFile(
                "file", "careplan.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/orders/{id}/careplan/upload", order.getId()).file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.carePlanContent").value(text));

        CarePlan stored = carePlanOf(order);
        assertThat(stored.getContent()).isEqualTo(text);
        assertThat(stored.getStatus()).isEqualTo(CarePlan.Status.COMPLETED);
        assertThat(stored.isUploaded()).isTrue();
        assertThat(stored.getErrorMessage()).isNull();
        // An upload is not generated, so it never goes through the generation queue.
        verifyNoInteractions(queueService);
    }

    @Test
    @DisplayName("POST .../careplan/upload - pasted text replaces the care plan")
    void shouldReplaceTheCarePlanWithPastedText() throws Exception {
        Order order = orderWithCarePlan(patient("Alice", "Wong", "100001"), CarePlan.Status.COMPLETED, "old plan");

        mockMvc.perform(multipart("/api/v1/orders/{id}/careplan/upload", order.getId())
                        .param("content", "reviewed plan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carePlanContent").value("reviewed plan"));

        CarePlan stored = carePlanOf(order);
        assertThat(stored.getContent()).isEqualTo("reviewed plan");
        assertThat(stored.isUploaded()).isTrue();
    }

    @Test
    @DisplayName("POST .../careplan/upload - neither text nor file returns CAREPLAN_UPLOAD_INVALID and changes nothing")
    void shouldRejectAnUploadWithNoContent() throws Exception {
        Order order = orderWithCarePlan(patient("Alice", "Wong", "100001"), CarePlan.Status.COMPLETED, "old plan");

        mockMvc.perform(multipart("/api/v1/orders/{id}/careplan/upload", order.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAREPLAN_UPLOAD_INVALID"));

        assertCarePlanUnchanged(order, "old plan");
    }

    @Test
    @DisplayName("POST .../careplan/upload - text and file together return CAREPLAN_UPLOAD_INVALID and change nothing")
    void shouldRejectAnUploadWithTextAndFile() throws Exception {
        Order order = orderWithCarePlan(patient("Alice", "Wong", "100001"), CarePlan.Status.COMPLETED, "old plan");
        MockMultipartFile file = new MockMultipartFile(
                "file", "careplan.txt", "text/plain", "from file".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/orders/{id}/careplan/upload", order.getId())
                        .file(file)
                        .param("content", "from text"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAREPLAN_UPLOAD_INVALID"));

        assertCarePlanUnchanged(order, "old plan");
    }

    @Test
    @DisplayName("POST .../careplan/upload - while the care plan is PROCESSING returns 409 and changes nothing")
    void shouldRejectAnUploadWhileGenerationIsRunning() throws Exception {
        Order order = orderWithCarePlan(patient("Alice", "Wong", "100001"), CarePlan.Status.PROCESSING, "partial");

        mockMvc.perform(multipart("/api/v1/orders/{id}/careplan/upload", order.getId())
                        .param("content", "late upload"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("block"))
                .andExpect(jsonPath("$.code").value("CAREPLAN_ALREADY_PROCESSING"));

        CarePlan stored = carePlanOf(order);
        assertThat(stored.getStatus()).isEqualTo(CarePlan.Status.PROCESSING);
        assertThat(stored.getContent()).isEqualTo("partial");
    }

    @Test
    @DisplayName("POST .../careplan/upload - unknown order returns the 404 error body")
    void shouldRejectAnUploadForAnUnknownOrder() throws Exception {
        mockMvc.perform(multipart("/api/v1/orders/{id}/careplan/upload", 987654)
                        .param("content", "text"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    // ----- helpers -----

    private void assertCarePlanUnchanged(Order order, String expectedContent) {
        CarePlan stored = carePlanOf(order);
        assertThat(stored.getContent()).isEqualTo(expectedContent);
        assertThat(stored.getStatus()).isEqualTo(CarePlan.Status.COMPLETED);
        assertThat(stored.isUploaded()).isFalse();
    }

    private CarePlan carePlanOf(Order order) {
        return carePlanRepository.findByOrderId(order.getId()).orElseThrow();
    }

    private Patient patient(String firstName, String lastName, String mrn) {
        Patient patient = new Patient();
        patient.setFirstName(firstName);
        patient.setLastName(lastName);
        patient.setMrn(mrn);
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        return patientRepository.save(patient);
    }

    private Order orderWithoutCarePlan(Patient patient) {
        Order order = new Order();
        order.setPatient(patient);
        order.setProvider(provider);
        order.setMedicationName("IVIG");
        order.setPrimaryDiagnosis("G70.00");
        order.setAdditionalDiagnoses(List.of("I10"));
        order.setMedicationHistory(List.of("Prednisone"));
        order.setPatientRecords("Test record");
        return orderRepository.save(order);
    }

    private Order orderWithCarePlan(Patient patient, CarePlan.Status status, String content) {
        Order order = orderWithoutCarePlan(patient);
        CarePlan carePlan = new CarePlan();
        carePlan.setOrder(order);
        carePlan.setStatus(status);
        carePlan.setContent(content);
        carePlanRepository.save(carePlan);
        return order;
    }
}

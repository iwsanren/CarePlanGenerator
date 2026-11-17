package com.page24.backend.controller;

import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.Order;
import com.page24.backend.entity.Patient;
import com.page24.backend.entity.Provider;
import com.page24.backend.repository.AuditEventRepository;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.repository.OrderRepository;
import com.page24.backend.repository.PatientRepository;
import com.page24.backend.repository.ProviderRepository;
import com.page24.backend.service.DataInitializationService;
import com.page24.backend.service.QueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/orders over HTTP (H2): the new filters and sort, the 400 error format, and that no patient name or
 * MRN reaches the log, the audit trail or an error message. The exact row order on PostgreSQL is in
 * OrderSearchPostgresTest; the parameter rules are in OrderListQueryParserTest.
 */
@org.springframework.security.test.context.support.WithMockUser(username = "test-pharmacist", roles = "PHARMACIST")
@SpringBootTest(properties = {
        // as loud as the application can be, to prove that even then no filter value is written
        "logging.level.com.page24.backend=DEBUG",
        "logging.level.org.hibernate.SQL=DEBUG"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class OrderControllerListSearchIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private CarePlanRepository carePlanRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private ProviderRepository providerRepository;
    @Autowired private AuditEventRepository auditEventRepository;

    @MockitoBean private QueueService queueService;
    @MockitoBean private DataInitializationService dataInitializationService;

    private Provider green;
    private Provider adams;

    @BeforeEach
    void insertRows() {
        carePlanRepository.deleteAll();
        orderRepository.deleteAll();
        patientRepository.deleteAll();
        providerRepository.deleteAll();

        green = provider("Dr. Green", "1111111111");
        adams = provider("Dr. Adams", "2222222222");

        // oldest to newest, so the default order is: Zoe, Yann, Xia, Wes
        add("Wes", "Wong", "100001", green, "IVIG", CarePlan.Status.COMPLETED, LocalDateTime.of(2026, 11, 10, 9, 0));
        add("Xia", "Q_Tip", "100002", adams, "Insulin", CarePlan.Status.PENDING, LocalDateTime.of(2026, 11, 11, 9, 0));
        add("Yann", "QxTip", "100003", green, "Rituximab", CarePlan.Status.FAILED, LocalDateTime.of(2026, 11, 12, 9, 0));
        add("Zoe", "50%Sure", "100004", adams, "ivig", CarePlan.Status.COMPLETED, LocalDateTime.of(2026, 11, 13, 9, 0));
    }

    // ----- filters and sort over HTTP -----

    @Test
    @DisplayName("no parameters: the old defaults (page 1, 20 per page, newest first) and the same response shape")
    void keepsTheOldDefaults() throws Exception {
        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(4))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.results[*].patientName").value(
                        org.hamcrest.Matchers.contains("Zoe 50%Sure", "Yann QxTip", "Xia Q_Tip", "Wes Wong")))
                .andExpect(jsonPath("$.results[0].status").value("completed"))
                .andExpect(jsonPath("$.results[0].providerNpi").value("2222222222"));
    }

    @Test
    @DisplayName("medication, provider_name, mrn, created_from and created_to filter, and are ANDed")
    void filtersOverHttp() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("medication", "IVIG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));
        mockMvc.perform(get("/api/v1/orders").param("provider_name", "adams"))
                .andExpect(jsonPath("$.count").value(2));
        mockMvc.perform(get("/api/v1/orders").param("mrn", "100003"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.results[0].patientName").value("Yann QxTip"));
        mockMvc.perform(get("/api/v1/orders").param("created_from", "2026-11-11").param("created_to", "2026-11-12"))
                .andExpect(jsonPath("$.count").value(2));
        mockMvc.perform(get("/api/v1/orders")
                        .param("medication", "ivig").param("provider_name", "green")
                        .param("status", "completed").param("created_to", "2026-11-10"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.results[0].patientName").value("Wes Wong"));
        mockMvc.perform(get("/api/v1/orders").param("medication", "ivig").param("provider_name", "adams")
                        .param("created_to", "2026-11-10"))
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.results.length()").value(0));
    }

    @Test
    @DisplayName("% and _ typed in a name are literal characters")
    void wildcardsAreLiteralOverHttp() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("patient_name", "%"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.results[0].patientName").value("Zoe 50%Sure"));
        mockMvc.perform(get("/api/v1/orders").param("patient_name", "q_tip"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.results[0].patientName").value("Xia Q_Tip"));
        mockMvc.perform(get("/api/v1/orders").param("patient_name", "WONG"))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    @DisplayName("sort=patient_name,asc as ONE parameter keeps its comma (field and direction stay together)")
    void aSortValueWithACommaIsOneCriterion() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("sort", "patient_name,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[*].patientName").value(
                        org.hamcrest.Matchers.contains("Zoe 50%Sure", "Xia Q_Tip", "Yann QxTip", "Wes Wong")));
        mockMvc.perform(get("/api/v1/orders").param("sort", "patient_name,desc"))
                .andExpect(jsonPath("$.results[0].patientName").value("Wes Wong"));
    }

    @Test
    @DisplayName("several sort parameters: the first has priority")
    void severalSortParameters() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("sort", "status,asc", "created_at,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[*].patientName").value(
                        org.hamcrest.Matchers.contains("Wes Wong", "Zoe 50%Sure", "Yann QxTip", "Xia Q_Tip")));
    }

    @Test
    @DisplayName("paging with a sort: page 2 of size 3 holds the last row")
    void pagingWithASort() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("sort", "medication,asc").param("page", "2").param("page_size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(4))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.pageSize").value(3))
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].medicationName").value("Rituximab"));
        mockMvc.perform(get("/api/v1/orders").param("page", "3").param("page_size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(4))
                .andExpect(jsonPath("$.results.length()").value(0));
    }

    @Test
    @DisplayName("page_size 100 is the largest accepted size")
    void pageSizeLimitOverHttp() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("page_size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageSize").value(100))
                .andExpect(jsonPath("$.results.length()").value(4));
    }

    // ----- 400 responses -----

    @Test
    @DisplayName("a field that is not on the sort whitelist -> 400 INVALID_SORT in the ApiErrorResponse format")
    void rejectsASortFieldOutsideTheWhitelist() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("sort", "patient_mrn,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.type").value("validation"))
                .andExpect(jsonPath("$.code").value("INVALID_SORT"))
                .andExpect(jsonPath("$.httpStatus").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.endsWith(
                        "field must be one of: id, created_at, medication, patient_name, provider_name, status")))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    @DisplayName("a bad direction, a repeated field and an empty value are 400 too")
    void rejectsOtherBadSortValues() throws Exception {
        for (String[] values : new String[][] {{"id,up"}, {"status,asc", "status,desc"}, {""}, {"id,asc,desc"}}) {
            mockMvc.perform(get("/api/v1/orders").param("sort", values))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_SORT"));
        }
    }

    @Test
    @DisplayName("created_from after created_to -> 400 INVALID_DATE_RANGE")
    void rejectsAReversedDateRange() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("created_from", "2026-11-15").param("created_to", "2026-11-14"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("validation"))
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"))
                .andExpect(jsonPath("$.message").value("created_from must not be after created_to"))
                .andExpect(jsonPath("$.httpStatus").value(400));
    }

    @Test
    @DisplayName("a date that is not yyyy-MM-dd -> 400 INVALID_REQUEST_PARAM naming the parameter, not its value")
    void rejectsAMalformedDate() throws Exception {
        for (String bad : new String[] {"11/12/2026", "2026-13-01", "yesterday", "2026-11-31"}) {
            mockMvc.perform(get("/api/v1/orders").param("created_from", bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAM"))
                    .andExpect(jsonPath("$.detail.created_from").value("has an invalid value"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(bad))));
        }
    }

    @Test
    @DisplayName("page_size above 100, or below 1 -> 400 INVALID_PAGE_SIZE")
    void rejectsAPageSizeOutOfRange() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("page_size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE_SIZE"))
                .andExpect(jsonPath("$.message").value("page_size must be less than or equal to 100"));
        mockMvc.perform(get("/api/v1/orders").param("page_size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE_SIZE"));
        mockMvc.perform(get("/api/v1/orders").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));
    }

    @Test
    @DisplayName("a search text over 100 characters -> 400 and the text is not repeated")
    void rejectsAnOverlongText() throws Exception {
        String tooLong = "Longname-" + "x".repeat(100);

        mockMvc.perform(get("/api/v1/orders").param("patient_name", tooLong))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SEARCH_TEXT"))
                .andExpect(jsonPath("$.message").value("patient_name must be at most 100 characters"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Longname"))));
    }

    // ----- privacy -----

    @Test
    @DisplayName("a search by name and MRN writes the name and MRN to no log line and no audit record")
    void searchValuesAreNeverLoggedOrAudited(CapturedOutput output) throws Exception {
        long auditBefore = auditEventRepository.count();

        mockMvc.perform(get("/api/v1/orders")
                        .param("patient_name", "Quentin Zzyzx").param("mrn", "987654")
                        .param("medication", "Secretomab").param("provider_name", "Dr. Hidden"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
        // the failing paths too: a rejected request must not repeat the values either
        mockMvc.perform(get("/api/v1/orders")
                        .param("patient_name", "Quentin Zzyzx").param("mrn", "987654")
                        .param("sort", "quentin_zzyzx"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/orders")
                        .param("patient_name", "Quentin Zzyzx").param("mrn", "987654")
                        .param("created_from", "2026-11-15").param("created_to", "2026-11-14"))
                .andExpect(status().isBadRequest());

        // logging really is on (the list's SQL was logged), so "does not contain" below means something
        assertThat(output.getAll()).contains("care_plans cp1_0").contains("escape '\\'");
        assertThat(output.getAll())
                .doesNotContain("Zzyzx").doesNotContain("Quentin").doesNotContain("987654")
                .doesNotContain("Secretomab").doesNotContain("Dr. Hidden").doesNotContain("quentin_zzyzx");
        assertThat(auditEventRepository.count()).as("reading the list writes no audit record").isEqualTo(auditBefore);
    }

    @Test
    @DisplayName("the old /orders/search endpoint still answers as before (it is only marked deprecated)")
    void theOldSearchStillWorks() throws Exception {
        mockMvc.perform(get("/api/v1/orders/search").param("mrn", "100001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].patientMrn").value("100001"));
        mockMvc.perform(get("/api/v1/orders/search").param("patient_name", "wong"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ----- helpers -----

    private Provider provider(String name, String npi) {
        Provider provider = new Provider();
        provider.setName(name);
        provider.setNpi(npi);
        return providerRepository.save(provider);
    }

    private void add(String firstName, String lastName, String mrn, Provider provider, String medication,
                     CarePlan.Status status, LocalDateTime createdAt) {
        Patient patient = new Patient();
        patient.setFirstName(firstName);
        patient.setLastName(lastName);
        patient.setMrn(mrn);
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
        carePlanRepository.save(carePlan);
    }
}

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The filtering, ordering and validation of the orders CSV export. ReportControllerIntegrationTest covers the
 * main path; this class covers the edges: date boundaries, orders with no care plan, other providers, sort order,
 * and every kind of rejected parameter.
 */
@WithMockUser(username = "test-pharmacist", roles = "PHARMACIST")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportExportFilteringIntegrationTest {

    private static final String EXPORT = "/api/v1/reports/orders/export";

    @Autowired private MockMvc mockMvc;
    @Autowired private CarePlanRepository carePlanRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private ProviderRepository providerRepository;

    @MockitoBean private QueueService queueService;
    @MockitoBean private DataInitializationService dataInitializationService;

    private Provider green;
    private Provider blue;
    private int mrnCounter;

    @BeforeEach
    void cleanDatabase() {
        carePlanRepository.deleteAll();
        orderRepository.deleteAll();
        patientRepository.deleteAll();
        providerRepository.deleteAll();
        mrnCounter = 100000;

        green = saveProvider("Dr. Green", "1111111111");
        blue = saveProvider("Dr. Blue", "2222222222");
    }

    // --------------------------------------------------------------- filters

    @Test
    @DisplayName("start_date and end_date are inclusive and compare the day, not the time of day")
    void dateRangeIsInclusiveOfBothEndDays() throws Exception {
        createOrder("Before", green, null, LocalDateTime.of(2026, 7, 31, 23, 59, 59));
        createOrder("FirstDay", green, null, LocalDateTime.of(2026, 8, 1, 0, 0, 0));
        createOrder("LastDay", green, null, LocalDateTime.of(2026, 8, 31, 23, 59, 59));
        createOrder("After", green, null, LocalDateTime.of(2026, 9, 1, 0, 0, 0));

        String csv = export("start_date", "2026-08-01", "end_date", "2026-08-31");

        assertThat(patientNames(csv)).containsExactly("LastDay Smith", "FirstDay Smith");
    }

    @Test
    @DisplayName("Either end of the date range can be used on its own")
    void startOrEndDateCanBeUsedAlone() throws Exception {
        createOrder("Early", green, null, LocalDateTime.of(2026, 1, 10, 9, 0));
        createOrder("Middle", green, null, LocalDateTime.of(2026, 6, 10, 9, 0));
        createOrder("Late", green, null, LocalDateTime.of(2026, 12, 10, 9, 0));

        assertThat(patientNames(export("start_date", "2026-06-10")))
                .containsExactly("Late Smith", "Middle Smith");
        assertThat(patientNames(export("end_date", "2026-06-10")))
                .containsExactly("Middle Smith", "Early Smith");
    }

    @Test
    @DisplayName("provider_id keeps only that provider's orders")
    void providerFilterExcludesOtherProviders() throws Exception {
        createOrder("Green", green, null, LocalDateTime.of(2026, 8, 1, 9, 0));
        createOrder("Blue", blue, null, LocalDateTime.of(2026, 8, 2, 9, 0));

        assertThat(patientNames(export("provider_id", blue.getId().toString()))).containsExactly("Blue Smith");
        assertThat(patientNames(export("provider_id", "999999"))).isEmpty();
    }

    @Test
    @DisplayName("The status filter ignores letter case and spaces, and drops orders that have no care plan")
    void statusFilterIgnoresCaseAndSkipsOrdersWithoutACarePlan() throws Exception {
        createOrder("Failed", green, CarePlan.Status.FAILED, LocalDateTime.of(2026, 8, 1, 9, 0));
        createOrder("Done", green, CarePlan.Status.COMPLETED, LocalDateTime.of(2026, 8, 2, 9, 0));
        createOrder("NoPlan", green, null, LocalDateTime.of(2026, 8, 3, 9, 0));

        assertThat(patientNames(export("status", "  failed "))).containsExactly("Failed Smith");
        assertThat(patientNames(export("status", "COMPLETED"))).containsExactly("Done Smith");
    }

    @Test
    @DisplayName("Without filters every order is listed, including one that has no care plan yet")
    void noFilterListsEveryOrder() throws Exception {
        createOrder("Failed", green, CarePlan.Status.FAILED, LocalDateTime.of(2026, 8, 1, 9, 0));
        createOrder("NoPlan", green, null, LocalDateTime.of(2026, 8, 3, 9, 0));

        assertThat(patientNames(export())).containsExactly("NoPlan Smith", "Failed Smith");
    }

    @Test
    @DisplayName("Blank filter values are ignored, and the csv format can be written in any letter case")
    void blankFiltersAreIgnored() throws Exception {
        createOrder("Only", green, CarePlan.Status.PENDING, LocalDateTime.of(2026, 8, 1, 9, 0));

        String csv = export("format", "CSV", "start_date", " ", "end_date", "", "status", "  ");

        assertThat(patientNames(csv)).containsExactly("Only Smith");
        assertThat(patientNames(export("format", ""))).containsExactly("Only Smith");
    }

    // ------------------------------------------------------------------ rows

    @Test
    @DisplayName("An order without a care plan has an empty status and an empty care plan update time")
    void orderWithoutACarePlanHasEmptyCarePlanCells() throws Exception {
        Order order = createOrder("NoPlan", green, null, LocalDateTime.of(2026, 9, 1, 10, 30));

        List<String> cells = cells(rowFor(export(), order));

        assertThat(cells).containsExactly(
                order.getId().toString(), "2026-09-01 10:30:00", "", order.getPatient().getMrn(), "NoPlan Smith",
                "1990-01-01", "IVIG", "G70.00", "1111111111", "Dr. Green", "");
    }

    @Test
    @DisplayName("A patient with no date of birth on file has an empty date of birth cell")
    void patientWithoutADateOfBirthHasAnEmptyCell() throws Exception {
        Order order = createOrder("NoDob", green, CarePlan.Status.PENDING, LocalDateTime.of(2026, 9, 1, 10, 30));
        Patient patient = order.getPatient();
        patient.setDateOfBirth(null);
        patientRepository.save(patient);

        List<String> cells = cells(rowFor(export(), order));

        assertThat(cells.get(4)).isEqualTo("NoDob Smith");
        assertThat(cells.get(5)).isEmpty();
        assertThat(cells.get(6)).isEqualTo("IVIG");
    }

    @Test
    @DisplayName("The care plan update time is written as yyyy-MM-dd HH:mm:ss")
    void carePlanUpdateTimeUsesTheDateTimeFormat() throws Exception {
        Order order = createOrder("Done", green, CarePlan.Status.COMPLETED, LocalDateTime.of(2026, 9, 1, 10, 30));

        List<String> cells = cells(rowFor(export(), order));

        assertThat(cells.get(10)).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    @DisplayName("Orders are sorted newest first, and orders created at the same time by highest id first")
    void ordersAreSortedNewestFirst() throws Exception {
        LocalDateTime sameTime = LocalDateTime.of(2026, 8, 5, 9, 0);
        createOrder("Oldest", green, null, LocalDateTime.of(2026, 8, 1, 9, 0));
        createOrder("TieFirst", green, null, sameTime);
        createOrder("TieSecond", green, null, sameTime);
        createOrder("Newest", green, null, LocalDateTime.of(2026, 8, 9, 9, 0));

        assertThat(patientNames(export()))
                .containsExactly("Newest Smith", "TieSecond Smith", "TieFirst Smith", "Oldest Smith");
    }

    @Test
    @DisplayName("A name that starts with a formula character is neutralised in the file")
    void formulaCharactersAreNeutralised() throws Exception {
        Order order = createOrder("=cmd", green, CarePlan.Status.PENDING, LocalDateTime.of(2026, 8, 1, 9, 0));

        assertThat(rowFor(export(), order)).contains("\"'=cmd Smith\"");
    }

    // ------------------------------------------------------------ rejections

    @ParameterizedTest(name = "{0}={1} is rejected as INVALID_DATE naming the parameter")
    @CsvSource({
            "start_date, 2026-13-01",
            "start_date, 01/08/2026",
            "start_date, yesterday",
            "end_date, 2026-02-30",
            "end_date, 2026/08/01"
    })
    @DisplayName("A date that is not YYYY-MM-DD is refused")
    void rejectsADateInTheWrongFormat(String parameter, String value) throws Exception {
        mockMvc.perform(get(EXPORT).param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE"))
                .andExpect(jsonPath("$.message").value(parameter + " must use YYYY-MM-DD format"));
    }

    @ParameterizedTest(name = "status={0} is rejected")
    @ValueSource(strings = {"done", "cancelled", "1"})
    @DisplayName("A status that is not one of the four care plan statuses is refused")
    void rejectsAnUnknownStatus(String value) throws Exception {
        mockMvc.perform(get(EXPORT).param("status", value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
    }

    @ParameterizedTest(name = "provider_id={0} is rejected")
    @ValueSource(strings = {"0", "-1"})
    @DisplayName("A provider id that is zero or negative is refused")
    void rejectsANonPositiveProviderId(String value) throws Exception {
        mockMvc.perform(get(EXPORT).param("provider_id", value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROVIDER_ID"))
                .andExpect(jsonPath("$.message").value("provider_id must be greater than zero"));
    }

    @Test
    @DisplayName("A bad parameter is refused before any data is read, so the first rule that fails is reported")
    void reportsTheFirstFailingRule() throws Exception {
        // The format is checked before the dates, the dates before the status.
        mockMvc.perform(get(EXPORT).param("format", "pdf").param("start_date", "nope").param("status", "nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EXPORT_FORMAT"));
        mockMvc.perform(get(EXPORT).param("start_date", "nope").param("status", "nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE"));
    }

    // --------------------------------------------------------------- helpers

    private String export(String... parameters) throws Exception {
        var request = get(EXPORT);
        for (int i = 0; i < parameters.length; i += 2) {
            request = request.param(parameters[i], parameters[i + 1]);
        }
        ResultActions result = mockMvc.perform(request).andExpect(status().isOk());
        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** The patient name column of every data row, in file order. */
    private List<String> patientNames(String csv) {
        return Arrays.stream(csv.split("\r\n")).skip(1).map(row -> cells(row).get(4)).toList();
    }

    private String rowFor(String csv, Order order) {
        return Arrays.stream(csv.split("\r\n"))
                .filter(row -> row.startsWith("\"" + order.getId() + "\","))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row for order " + order.getId() + " in:\n" + csv));
    }

    /** Splits one row of the export; every cell is quoted and none of these test values contains a quote. */
    private List<String> cells(String row) {
        return Arrays.stream(row.substring(1, row.length() - 1).split("\",\"", -1)).toList();
    }

    private Provider saveProvider(String name, String npi) {
        Provider provider = new Provider();
        provider.setName(name);
        provider.setNpi(npi);
        return providerRepository.save(provider);
    }

    private Order createOrder(
            String firstName, Provider provider, CarePlan.Status status, LocalDateTime createdAt) {
        Patient patient = new Patient();
        patient.setFirstName(firstName);
        patient.setLastName("Smith");
        patient.setMrn(String.valueOf(mrnCounter++));
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient = patientRepository.save(patient);

        Order order = new Order();
        order.setPatient(patient);
        order.setProvider(provider);
        order.setMedicationName("IVIG");
        order.setPrimaryDiagnosis("G70.00");
        order.setCreatedAt(createdAt);
        order = orderRepository.save(order);

        if (status != null) {
            CarePlan carePlan = new CarePlan();
            carePlan.setOrder(order);
            carePlan.setStatus(status);
            carePlanRepository.save(carePlan);
        }
        return order;
    }
}

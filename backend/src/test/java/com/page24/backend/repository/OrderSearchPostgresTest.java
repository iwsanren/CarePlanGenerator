package com.page24.backend.repository;

import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.Order;
import com.page24.backend.entity.Patient;
import com.page24.backend.entity.Provider;
import com.page24.backend.service.OrderListParams;
import com.page24.backend.service.OrderListQueryParser;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order list search on a real PostgreSQL (the production database): known rows, exact expected order for every
 * filter, every sort field in both directions, page boundaries, wildcard escaping and the number of SQL statements.
 * Skipped when Docker is not available.
 *
 * The rows are named by a letter. They are inserted in this order, so the id of A is the smallest and of L the
 * largest; "id desc" therefore reads L, K, J, ...
 * <pre>
 *     patient (mrn)          provider     medication  status      created
 *  A  Alice Wong  (100001)   Dr. Green    IVIG        COMPLETED   11-10 09:00
 *  B  bob wong    (100002)   Dr. Green    ivig        PENDING     11-10 15:00
 *  C  Carol Adams (100003)   Dr. Adams    Rituximab   FAILED      11-11 08:00
 *  D  Dan Q_Tip   (100004)   Dr. Adams    Gamunex-C   COMPLETED   11-12 12:00
 *  E  Erin Olsen  (100005)   Dr. Brown    Insulin     PROCESSING  11-12 12:00   same instant as D
 *  F  Fay 50%Sure (100006)   Dr. Brown    Insulin     COMPLETED   11-13 23:59:59
 *  G  Gus Bell    (100007)   Dr. Brown    IVIG        COMPLETED   11-14 00:00   first instant of 11-14
 *  H  Hana QxTip  (100008)   Dr. Green    Rituximab   PENDING     11-14 10:00
 *  J  Jack Zed    (100009)   Dr. Adams    IVIG        FAILED      11-15 00:00
 *  K  Kara Adams  (100010)   Dr. Brown    Rituximab   COMPLETED   11-11 08:00   same instant as C
 *  L  Alice Wong  (100001)   Dr. Green    Insulin     COMPLETED   11-09 10:00   second order of A's patient
 * </pre>
 * Q_Tip and QxTip, and the % in 50%Sure, are there to prove that "_" and "%" typed by a person are literal
 * characters and not LIKE wildcards. Last names start with different letters on purpose, so the expected order does
 * not depend on how the database collates punctuation.
 */
@SpringBootTest(classes = OrderSearchPostgresTest.TestApplication.class, properties =
        "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers(disabledWithoutDocker = true)
class OrderSearchPostgresTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private OrderRepository orderRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private ProviderRepository providerRepository;
    @Autowired private CarePlanRepository carePlanRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private final Map<String, Long> idByLabel = new HashMap<>();
    private final Map<Long, String> labelById = new HashMap<>();
    private Provider green;
    private Patient alice;

    @BeforeEach
    void insertKnownRows() {
        carePlanRepository.deleteAll();
        orderRepository.deleteAll();
        patientRepository.deleteAll();
        providerRepository.deleteAll();
        idByLabel.clear();
        labelById.clear();

        green = provider("Dr. Green", "1111111111");
        Provider adams = provider("Dr. Adams", "2222222222");
        Provider brown = provider("Dr. Brown", "3333333333");
        alice = patient("Alice", "Wong", "100001");

        add("A", alice, green, "IVIG", CarePlan.Status.COMPLETED, at(10, 9, 0, 0));
        add("B", patient("bob", "wong", "100002"), green, "ivig", CarePlan.Status.PENDING, at(10, 15, 0, 0));
        add("C", patient("Carol", "Adams", "100003"), adams, "Rituximab", CarePlan.Status.FAILED, at(11, 8, 0, 0));
        add("D", patient("Dan", "Q_Tip", "100004"), adams, "Gamunex-C", CarePlan.Status.COMPLETED, at(12, 12, 0, 0));
        add("E", patient("Erin", "Olsen", "100005"), brown, "Insulin", CarePlan.Status.PROCESSING, at(12, 12, 0, 0));
        add("F", patient("Fay", "50%Sure", "100006"), brown, "Insulin", CarePlan.Status.COMPLETED, at(13, 23, 59, 59));
        add("G", patient("Gus", "Bell", "100007"), brown, "IVIG", CarePlan.Status.COMPLETED, at(14, 0, 0, 0));
        add("H", patient("Hana", "QxTip", "100008"), green, "Rituximab", CarePlan.Status.PENDING, at(14, 10, 0, 0));
        add("J", patient("Jack", "Zed", "100009"), adams, "IVIG", CarePlan.Status.FAILED, at(15, 0, 0, 0));
        add("K", patient("Kara", "Adams", "100010"), brown, "Rituximab", CarePlan.Status.COMPLETED, at(11, 8, 0, 0));
        add("L", alice, green, "Insulin", CarePlan.Status.COMPLETED, at(9, 10, 0, 0));
    }

    // ----- no filter, default order, row content -----

    @Test
    @DisplayName("no parameters: every order, newest first; rows with the same instant are ordered by id, newest first")
    void listsEverythingNewestFirst() {
        OrderSearchPage page = search(p -> p);

        assertThat(page.total()).isEqualTo(11);
        assertThat(labels(page)).containsExactly("J", "H", "G", "F", "E", "D", "K", "C", "B", "A", "L");
    }

    @Test
    @DisplayName("a row carries the columns the list shows")
    void mapsAllColumns() {
        OrderListRow row = search(p -> p.page(1).pageSize(1)).rows().get(0);

        assertThat(row.id()).isEqualTo(idByLabel.get("J"));
        assertThat(row.patientFirstName()).isEqualTo("Jack");
        assertThat(row.patientLastName()).isEqualTo("Zed");
        assertThat(row.patientMrn()).isEqualTo("100009");
        assertThat(row.medicationName()).isEqualTo("IVIG");
        assertThat(row.status()).isEqualTo(CarePlan.Status.FAILED);
        assertThat(row.createdAt()).isEqualTo(at(15, 0, 0, 0));
        assertThat(row.providerName()).isEqualTo("Dr. Adams");
        assertThat(row.providerNpi()).isEqualTo("2222222222");
    }

    // ----- single filters -----

    @Test
    @DisplayName("status filter")
    void filtersByStatus() {
        assertThat(labels(search(p -> p.status("completed")))).containsExactly("G", "F", "D", "K", "A", "L");
        assertThat(labels(search(p -> p.status("PENDING")))).containsExactly("H", "B");
        assertThat(labels(search(p -> p.status("failed")))).containsExactly("J", "C");
        assertThat(labels(search(p -> p.status("processing")))).containsExactly("E");
    }

    @Test
    @DisplayName("date range 11-11 .. 11-13: whole days, keeps 11-13 23:59:59 (F) and drops 11-14 00:00 (G)")
    void rangeKeepsTheLastSecondAndDropsTheNextMidnight() {
        assertThat(labels(search(p -> p.createdFrom(LocalDate.of(2026, 11, 11)).createdTo(LocalDate.of(2026, 11, 13)))))
                .containsExactly("F", "E", "D", "K", "C");
    }

    @Test
    @DisplayName("one day on both ends is a one-day range; 11-14 starts at its first instant")
    void aSingleDayIsInclusive() {
        LocalDate day = LocalDate.of(2026, 11, 14);

        assertThat(labels(search(p -> p.createdFrom(day).createdTo(day)))).containsExactly("H", "G");
    }

    @Test
    @DisplayName("an open end: only created_to, or only created_from")
    void openEndedRanges() {
        assertThat(labels(search(p -> p.createdTo(LocalDate.of(2026, 11, 10))))).containsExactly("B", "A", "L");
        assertThat(labels(search(p -> p.createdFrom(LocalDate.of(2026, 11, 14))))).containsExactly("J", "H", "G");
    }

    @Test
    @DisplayName("medication: contains, ignoring case")
    void filtersByMedication() {
        assertThat(labels(search(p -> p.medication("ivig")))).containsExactly("J", "G", "B", "A");
        assertThat(labels(search(p -> p.medication("IVIG")))).containsExactly("J", "G", "B", "A");
        assertThat(labels(search(p -> p.medication("in")))).containsExactly("F", "E", "L");
        assertThat(labels(search(p -> p.medication("  gamunex  ")))).containsExactly("D");
    }

    @Test
    @DisplayName("provider name: contains, ignoring case; provider_id: exact")
    void filtersByProvider() {
        assertThat(labels(search(p -> p.providerName("dr. g")))).containsExactly("H", "B", "A", "L");
        assertThat(labels(search(p -> p.providerName("ADAMS")))).containsExactly("J", "D", "C");
        assertThat(labels(search(p -> p.providerId(green.getId())))).containsExactly("H", "B", "A", "L");
    }

    @Test
    @DisplayName("patient name: first name, last name or \"first last\", ignoring case; never the provider's name")
    void filtersByPatientName() {
        assertThat(labels(search(p -> p.patientName("WONG")))).containsExactly("B", "A", "L");
        assertThat(labels(search(p -> p.patientName("alice")))).containsExactly("A", "L");
        assertThat(labels(search(p -> p.patientName("alice wong")))).containsExactly("A", "L");
        assertThat(labels(search(p -> p.patientName("Bob Wong")))).containsExactly("B");
        // two patients are called Adams, but Dr. Adams's other orders (D, J) belong to other patients
        assertThat(labels(search(p -> p.patientName("adams")))).containsExactly("K", "C");
    }

    @Test
    @DisplayName("MRN is an exact match, never a prefix; patient_id selects one patient")
    void filtersByMrnAndPatient() {
        assertThat(labels(search(p -> p.mrn("100003")))).containsExactly("C");
        assertThat(labels(search(p -> p.mrn("100001")))).containsExactly("A", "L");
        assertThat(labels(search(p -> p.mrn("10000")))).isEmpty();
        assertThat(labels(search(p -> p.patientId(alice.getId())))).containsExactly("A", "L");
    }

    // ----- wildcards -----

    @Test
    @DisplayName("% typed by a person is the character %, not 'anything'")
    void percentIsLiteral() {
        assertThat(labels(search(p -> p.patientName("%")))).containsExactly("F");
        assertThat(labels(search(p -> p.patientName("50%")))).containsExactly("F");
        assertThat(labels(search(p -> p.patientName("a%e")))).isEmpty();   // would match Alice, Olsen, Bell ... as a wildcard
        assertThat(labels(search(p -> p.medication("%")))).isEmpty();
        assertThat(labels(search(p -> p.providerName("%")))).isEmpty();
    }

    @Test
    @DisplayName("_ typed by a person is the character _, not 'any one character'")
    void underscoreIsLiteral() {
        assertThat(labels(search(p -> p.patientName("_")))).containsExactly("D");
        assertThat(labels(search(p -> p.patientName("q_tip")))).containsExactly("D"); // QxTip (H) must not match
        assertThat(labels(search(p -> p.patientName("qxtip")))).containsExactly("H");
        assertThat(labels(search(p -> p.patientName("____")))).isEmpty();
    }

    @Test
    @DisplayName("a backslash is an ordinary character too, and does not break the query")
    void backslashIsLiteral() {
        assertThat(labels(search(p -> p.patientName("\\")))).isEmpty();
        assertThat(labels(search(p -> p.patientName("\\%")))).isEmpty();
        assertThat(labels(search(p -> p.patientName("wong\\")))).isEmpty();
    }

    @Test
    @DisplayName("an SQL fragment is just text and matches nothing")
    void sqlFragmentsAreJustText() {
        assertThat(labels(search(p -> p.patientName("' or '1'='1")))).isEmpty();
        assertThat(labels(search(p -> p.medication("x'; drop table orders; --")))).isEmpty();
        assertThat(search(p -> p).total()).isEqualTo(11);
    }

    // ----- combinations (AND) -----

    @Test
    @DisplayName("filters are ANDed")
    void combinesFilters() {
        assertThat(labels(search(p -> p.status("completed").medication("ivig").createdFrom(LocalDate.of(2026, 11, 10)))))
                .containsExactly("G", "A");
        assertThat(labels(search(p -> p.providerId(green.getId()).status("pending").patientName("wong"))))
                .containsExactly("B");
        assertThat(labels(search(p -> p.status("completed").providerName("brown").createdTo(LocalDate.of(2026, 11, 13)))))
                .containsExactly("F", "K");
        assertThat(labels(search(p -> p.mrn("100003").patientName("wong")))).isEmpty();
        assertThat(labels(search(p -> p.mrn("100001").medication("insulin").status("completed"))))
                .containsExactly("L");
    }

    @Test
    @DisplayName("every filter at once")
    void combinesEveryFilter() {
        OrderSearchPage page = search(p -> p.status("completed").patientId(alice.getId()).providerId(green.getId())
                .patientName("alice").mrn("100001").medication("ivig").providerName("green")
                .createdFrom(LocalDate.of(2026, 11, 10)).createdTo(LocalDate.of(2026, 11, 10)));

        assertThat(labels(page)).containsExactly("A");
        assertThat(page.total()).isEqualTo(1);
    }

    @Test
    @DisplayName("a combination that matches nothing: total 0 and no rows")
    void emptyResult() {
        OrderSearchPage page = search(p -> p.status("failed").medication("insulin"));

        assertThat(page.total()).isZero();
        assertThat(page.rows()).isEmpty();
    }

    // ----- sorting -----

    @Test
    @DisplayName("sort=id")
    void sortsById() {
        assertThat(labels(search(p -> p.sort(List.of("id,asc")))))
                .containsExactly("A", "B", "C", "D", "E", "F", "G", "H", "J", "K", "L");
        assertThat(labels(search(p -> p.sort(List.of("id,desc")))))
                .containsExactly("L", "K", "J", "H", "G", "F", "E", "D", "C", "B", "A");
    }

    @Test
    @DisplayName("sort=created_at; equal instants fall back to id, newest first, in both directions")
    void sortsByCreatedAt() {
        assertThat(labels(search(p -> p.sort(List.of("created_at,asc")))))
                .containsExactly("L", "A", "B", "K", "C", "E", "D", "F", "G", "H", "J");
        assertThat(labels(search(p -> p.sort(List.of("created_at,desc")))))
                .containsExactly("J", "H", "G", "F", "E", "D", "K", "C", "B", "A", "L");
    }

    @Test
    @DisplayName("sort=medication, ignoring case (IVIG and ivig are the same medication)")
    void sortsByMedication() {
        assertThat(labels(search(p -> p.sort(List.of("medication,asc")))))
                .containsExactly("D", "L", "F", "E", "J", "G", "B", "A", "K", "H", "C");
        assertThat(labels(search(p -> p.sort(List.of("medication,desc")))))
                .containsExactly("K", "H", "C", "J", "G", "B", "A", "L", "F", "E", "D");
    }

    @Test
    @DisplayName("sort=patient_name: last name, then first name, ignoring case")
    void sortsByPatientName() {
        // 50%sure | adams carol, adams kara | bell | olsen | q_tip | qxtip | wong alice (A, L) | wong bob | zed
        assertThat(labels(search(p -> p.sort(List.of("patient_name,asc")))))
                .containsExactly("F", "C", "K", "G", "E", "D", "H", "L", "A", "B", "J");
        assertThat(labels(search(p -> p.sort(List.of("patient_name,desc")))))
                .containsExactly("J", "B", "L", "A", "H", "D", "E", "G", "K", "C", "F");
    }

    @Test
    @DisplayName("sort=provider_name")
    void sortsByProviderName() {
        assertThat(labels(search(p -> p.sort(List.of("provider_name,asc")))))
                .containsExactly("J", "D", "C", "K", "G", "F", "E", "L", "H", "B", "A");
        assertThat(labels(search(p -> p.sort(List.of("provider_name,desc")))))
                .containsExactly("L", "H", "B", "A", "K", "G", "F", "E", "J", "D", "C");
    }

    @Test
    @DisplayName("sort=status (alphabetical: COMPLETED, FAILED, PENDING, PROCESSING)")
    void sortsByStatus() {
        assertThat(labels(search(p -> p.sort(List.of("status,asc")))))
                .containsExactly("L", "K", "G", "F", "D", "A", "J", "C", "H", "B", "E");
        assertThat(labels(search(p -> p.sort(List.of("status,desc")))))
                .containsExactly("E", "H", "B", "J", "C", "L", "K", "G", "F", "D", "A");
    }

    @Test
    @DisplayName("several sort keys: the first one wins, the next ones order the ties")
    void sortsByTwoKeys() {
        assertThat(labels(search(p -> p.sort(List.of("status,asc", "created_at,desc")))))
                .containsExactly("G", "F", "D", "K", "A", "L", "J", "C", "H", "B", "E");
        assertThat(labels(search(p -> p.sort(List.of("provider_name,asc", "medication,desc", "patient_name")))))
                .containsExactly("C", "J", "D", "K", "G", "F", "E", "H", "A", "B", "L");
    }

    @Test
    @DisplayName("sorting and filtering together")
    void sortsFilteredRows() {
        assertThat(labels(search(p -> p.status("completed").sort(List.of("medication,asc")))))
                .containsExactly("D", "L", "F", "G", "A", "K");
    }

    // ----- paging -----

    @Test
    @DisplayName("pages of 4: full, full, partial, then empty past the end; the total is the same on every page")
    void pagesOfFour() {
        OrderSearchPage first = search(p -> p.page(1).pageSize(4));
        OrderSearchPage second = search(p -> p.page(2).pageSize(4));
        OrderSearchPage third = search(p -> p.page(3).pageSize(4));
        OrderSearchPage fourth = search(p -> p.page(4).pageSize(4));

        assertThat(labels(first)).containsExactly("J", "H", "G", "F");
        assertThat(labels(second)).containsExactly("E", "D", "K", "C");
        assertThat(labels(third)).containsExactly("B", "A", "L");
        assertThat(fourth.rows()).isEmpty();
        assertThat(List.of(first.total(), second.total(), third.total(), fourth.total())).containsOnly(11L);
    }

    @Test
    @DisplayName("page size 1 walks the whole list, ties included, with no row repeated or skipped")
    void pageSizeOneVisitsEveryRowOnce() {
        List<String> walked = new ArrayList<>();
        for (int page = 1; page <= 11; page++) {
            int current = page;
            walked.addAll(labels(search(p -> p.page(current).pageSize(1))));
        }

        assertThat(walked).containsExactly("J", "H", "G", "F", "E", "D", "K", "C", "B", "A", "L");
        assertThat(search(p -> p.page(12).pageSize(1)).rows()).isEmpty();
    }

    @Test
    @DisplayName("paging a sorted list gives the same order as the unpaged list")
    void pagingKeepsTheSortOrder() {
        List<String> unpaged = labels(search(p -> p.sort(List.of("status,asc"))));
        List<String> walked = new ArrayList<>();
        for (int page = 1; page <= 4; page++) {
            int current = page;
            walked.addAll(labels(search(p -> p.sort(List.of("status,asc")).page(current).pageSize(3))));
        }

        assertThat(walked).isEqualTo(unpaged);
    }

    @Test
    @DisplayName("page size 100 (the limit) returns everything on one page")
    void pageSizeAtTheLimit() {
        OrderSearchPage page = search(p -> p.pageSize(100));

        assertThat(page.rows()).hasSize(11);
        assertThat(page.total()).isEqualTo(11);
    }

    @Test
    @DisplayName("a page past the end of a filtered list is empty but still reports the filtered total")
    void pastTheEndOfAFilteredList() {
        OrderSearchPage page = search(p -> p.status("failed").page(2).pageSize(2));

        assertThat(page.rows()).isEmpty();
        assertThat(page.total()).isEqualTo(2);
    }

    // ----- N+1 -----

    @Test
    @DisplayName("a page is two SQL statements (count + rows), whatever the page size; past the end it is one")
    void usesAFixedNumberOfStatements() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        statistics.clear();
        search(p -> p.pageSize(2));
        assertThat(statistics.getPrepareStatementCount()).as("2 rows").isEqualTo(2);

        statistics.clear();
        OrderSearchPage all = search(p -> p.pageSize(100));
        assertThat(all.rows()).hasSize(11);
        assertThat(statistics.getPrepareStatementCount()).as("11 rows").isEqualTo(2);
        assertThat(statistics.getEntityLoadCount()).as("no entity is loaded for the list").isZero();

        statistics.clear();
        search(p -> p.status("completed").patientName("a").providerName("dr").sort(List.of("patient_name,desc")));
        assertThat(statistics.getPrepareStatementCount()).as("with filters and sort").isEqualTo(2);

        statistics.clear();
        search(p -> p.page(5).pageSize(4));
        assertThat(statistics.getPrepareStatementCount()).as("past the end: only the count").isEqualTo(1);
    }

    // ----- helpers -----

    private OrderSearchPage search(UnaryOperator<OrderListParams.OrderListParamsBuilder> params) {
        OrderListQuery query = OrderListQueryParser.parse(params.apply(OrderListParams.builder()).build());
        return orderRepository.search(query);
    }

    private List<String> labels(OrderSearchPage page) {
        return page.rows().stream().map(row -> labelById.get(row.id())).toList();
    }

    private static LocalDateTime at(int day, int hour, int minute, int second) {
        return LocalDateTime.of(2026, 11, day, hour, minute, second);
    }

    private Provider provider(String name, String npi) {
        Provider provider = new Provider();
        provider.setName(name);
        provider.setNpi(npi);
        return providerRepository.save(provider);
    }

    private Patient patient(String firstName, String lastName, String mrn) {
        Patient patient = new Patient();
        patient.setFirstName(firstName);
        patient.setLastName(lastName);
        patient.setMrn(mrn);
        return patientRepository.save(patient);
    }

    private void add(String label, Patient patient, Provider provider, String medication, CarePlan.Status status,
                     LocalDateTime createdAt) {
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

        idByLabel.put(label, order.getId());
        labelById.put(order.getId(), label);
    }

    /** Datasource + Flyway + JPA + repositories: no web, security, Redis or LLM. */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            TransactionAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class
    })
    @EntityScan("com.page24.backend.entity")
    @EnableJpaRepositories("com.page24.backend.repository")
    static class TestApplication {
    }
}

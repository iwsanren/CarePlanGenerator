package com.page24.backend.service;

import com.page24.backend.dto.DashboardStatsResponse;
import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.Order;
import com.page24.backend.entity.Patient;
import com.page24.backend.entity.Provider;
import com.page24.backend.exception.ValidationError;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.repository.OrderRepository;
import com.page24.backend.repository.PatientRepository;
import com.page24.backend.repository.ProviderRepository;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The dashboard aggregates on a real PostgreSQL (production database). Known rows are inserted and every statistic
 * is asserted to the exact value, so a wrong GROUP BY, a window off by one, or a NULL duration counted as 0 fails
 * here. "Today" is fixed to 2026-11-14 (UTC); the 7-day window is therefore 2026-11-08 00:00 up to
 * 2026-11-15 00:00 and the 30-day window starts on 2026-10-16. Skipped when Docker is not available.
 *
 * The data (created_at / care plan / provider / medication / duration in seconds):
 * <pre>
 *  #1  11-08 00:00:00  A Gamunex-C  COMPLETED  10 s     first instant of the 7-day window
 *  #2  11-08 23:59:59  A gamunex-c  COMPLETED  20 s     other spelling, same medication
 *  #3  11-10 09:00     A Gamunex-C  FAILED      6 s
 *  #4  11-10 15:00     B Metformin  COMPLETED  30 s
 *  #5  11-10 16:00     B Metformin  PENDING     -
 *  #6  11-14 23:59:59  B Lisinopril PROCESSING  started, not finished     last instant of the window
 *  #7  11-12 08:00     B Metformin  COMPLETED  uploaded manually, no timing
 *  #8  11-12 09:00     C Lisinopril COMPLETED  old row, timing columns NULL
 *  #9  11-07 23:59:59  A Gamunex-C  COMPLETED 100 s     one second before the 7-day window
 *  #10 11-15 00:00:00  A Gamunex-C  FAILED      6 s     first instant after the window
 *  #11 10-20 10:00     A Insulin    COMPLETED  40 s     only inside the 30-day window
 * </pre>
 */
@SpringBootTest(classes = DashboardServicePostgresTest.TestApplication.class)
@Testcontainers(disabledWithoutDocker = true)
class DashboardServicePostgresTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private DashboardService dashboardService;
    @Autowired private ProviderRepository providerRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private CarePlanRepository carePlanRepository;

    private Provider alpha;
    private Provider beta;
    private Provider gamma;
    private int mrnCounter;

    @BeforeEach
    void cleanDatabase() {
        carePlanRepository.deleteAll();
        orderRepository.deleteAll();
        patientRepository.deleteAll();
        providerRepository.deleteAll();
        mrnCounter = 100000;
        alpha = provider("Dr. Alpha", "1000000001");
        beta = provider("Dr. Beta", "1000000002");
        gamma = provider("Dr. Gamma", "1000000003");
    }

    @Test
    @DisplayName("7 days: every statistic has the exact value for the known rows")
    void sevenDayWindowHasExactFigures() {
        insertKnownRows();

        DashboardStatsResponse stats = dashboardService.getStats(7);

        assertThat(stats.days()).isEqualTo(7);
        assertThat(stats.from()).isEqualTo(LocalDate.of(2026, 11, 8));
        assertThat(stats.to()).isEqualTo(LocalDate.of(2026, 11, 14));
        assertThat(stats.timezone()).isEqualTo("UTC");

        // daily volume: window edges are included/excluded exactly; days without orders are 0
        assertThat(stats.dailyOrders()).extracting(d -> d.date() + "=" + d.count()).containsExactly(
                "2026-11-08=2", "2026-11-09=0", "2026-11-10=3", "2026-11-11=0",
                "2026-11-12=2", "2026-11-13=0", "2026-11-14=1");

        // daily trend (date|completed|failed|failureRate|avgSeconds), LLM-generated plans only, per creation day:
        //  11-08 #1 #2 completed (10 s, 20 s)             -> 2|0|0.0|15.0
        //  11-10 #3 failed, #4 completed 30 s, #5 pending -> 1|1|0.5|30.0 (the failed run's 6 s is not averaged)
        //  11-12 #8 completed without timing (#7 is an upload, left out) -> 1|0|0.0|null: a real 0 rate, no average
        //  11-14 #6 still processing                      -> 0|0|null|null: nothing finished, nothing to rate
        assertThat(stats.dailyTrend()).extracting(t -> t.date() + "|" + t.completedCount() + "|" + t.failedCount()
                + "|" + t.failureRate() + "|" + t.avgGenerationSeconds()).containsExactly(
                "2026-11-08|2|0|0.0|15.0",
                "2026-11-09|0|0|null|null",
                "2026-11-10|1|1|0.5|30.0",
                "2026-11-11|0|0|null|null",
                "2026-11-12|1|0|0.0|null",
                "2026-11-13|0|0|null|null",
                "2026-11-14|0|0|null|null");
        // the per-day counts add up to the KPI counts
        assertThat(stats.dailyTrend().stream().mapToLong(t -> t.completedCount()).sum())
                .isEqualTo(stats.kpi().completedCount());
        assertThat(stats.dailyTrend().stream().mapToLong(t -> t.failedCount()).sum())
                .isEqualTo(stats.kpi().failedCount());

        // status shares: always four entries, in enum order
        assertThat(stats.statusBreakdown()).extracting(s -> s.status() + "=" + s.count() + "/" + s.share())
                .containsExactly("PENDING=1/0.125", "PROCESSING=1/0.125", "COMPLETED=5/0.625", "FAILED=1/0.125");

        // KPI: completed #1 #2 #4 #8, failed #3, manual upload #7; PENDING/PROCESSING are not part of the rate
        DashboardStatsResponse.Kpi kpi = stats.kpi();
        assertThat(kpi.totalOrders()).isEqualTo(8);
        assertThat(kpi.completedCount()).isEqualTo(4);
        assertThat(kpi.failedCount()).isEqualTo(1);
        assertThat(kpi.manualUploadCount()).isEqualTo(1);
        assertThat(kpi.failureRate()).isEqualTo(0.2);
        // (10 + 20 + 30) / 3 = 20; the old row #8 (NULL timing) is left out, not counted as 0 (that would give 15)
        assertThat(kpi.avgGenerationSeconds()).isEqualTo(20.0);

        // medications: spellings merged; tie at 3 orders broken by name; Lisinopril has 2
        assertThat(stats.topMedications()).hasSize(3);
        assertThat(stats.topMedications().get(0).medicationName()).isEqualToIgnoringCase("Gamunex-C");
        assertThat(stats.topMedications().get(0).orderCount()).isEqualTo(3);
        assertThat(stats.topMedications().get(1).medicationName()).isEqualTo("Metformin");
        assertThat(stats.topMedications().get(1).orderCount()).isEqualTo(3);
        assertThat(stats.topMedications().get(2).medicationName()).isEqualTo("Lisinopril");
        assertThat(stats.topMedications().get(2).orderCount()).isEqualTo(2);

        // providers: B 4 orders / 0 failed / only #4 counts (30 s, #7 is an upload); A 3 / 1 / (10 + 20) / 2 = 15;
        // C has one old row without timing -> no average at all (null)
        assertThat(stats.providerStats()).extracting(p -> p.providerName() + "|" + p.orderCount() + "|"
                + p.failedCount() + "|" + p.avgGenerationSeconds())
                .containsExactly("Dr. Beta|4|0|30.0", "Dr. Alpha|3|1|15.0", "Dr. Gamma|1|0|null");
        assertThat(stats.providerStats()).extracting(p -> p.providerId())
                .containsExactly(beta.getId(), alpha.getId(), gamma.getId());
    }

    @Test
    @DisplayName("30 days: wider window picks up the older rows, 30 daily entries")
    void thirtyDayWindowHasExactFigures() {
        insertKnownRows();

        DashboardStatsResponse stats = dashboardService.getStats(30);

        assertThat(stats.from()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(stats.dailyOrders()).hasSize(30);
        assertThat(stats.dailyOrders().get(0).date()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(stats.dailyOrders().stream().filter(d -> d.date().equals(LocalDate.of(2026, 10, 20)))
                .findFirst().orElseThrow().count()).isEqualTo(1);
        assertThat(stats.dailyOrders().stream().filter(d -> d.date().equals(LocalDate.of(2026, 11, 7)))
                .findFirst().orElseThrow().count()).isEqualTo(1);
        // trend: the two older days are filled in, 11-15 00:00 (#10) is still outside
        assertThat(stats.dailyTrend()).hasSize(30);
        assertThat(stats.dailyTrend().stream().filter(t -> t.date().equals(LocalDate.of(2026, 10, 20)))
                .findFirst().orElseThrow()).extracting(t -> t.completedCount() + "|" + t.failureRate() + "|"
                + t.avgGenerationSeconds()).isEqualTo("1|0.0|40.0");
        assertThat(stats.dailyTrend().stream().filter(t -> t.date().equals(LocalDate.of(2026, 11, 7)))
                .findFirst().orElseThrow()).extracting(t -> t.completedCount() + "|" + t.failureRate() + "|"
                + t.avgGenerationSeconds()).isEqualTo("1|0.0|100.0");
        assertThat(stats.dailyTrend().stream().filter(t -> t.date().equals(LocalDate.of(2026, 10, 17)))
                .findFirst().orElseThrow()).extracting(t -> t.failureRate() + "|" + t.avgGenerationSeconds())
                .isEqualTo("null|null");
        // #10 (11-15 00:00) is still outside
        assertThat(stats.kpi().totalOrders()).isEqualTo(10);
        assertThat(stats.statusBreakdown()).extracting(s -> s.status() + "=" + s.count())
                .containsExactly("PENDING=1", "PROCESSING=1", "COMPLETED=7", "FAILED=1");
        // completed: #1 #2 #4 #8 #9 #11 = 6, failed #3 -> 1 / 7
        assertThat(stats.kpi().completedCount()).isEqualTo(6);
        assertThat(stats.kpi().failedCount()).isEqualTo(1);
        assertThat(stats.kpi().failureRate()).isEqualTo(0.1429);
        // (10 + 20 + 30 + 100 + 40) / 5 = 40
        assertThat(stats.kpi().avgGenerationSeconds()).isEqualTo(40.0);
    }

    @Test
    @DisplayName("No orders in the window -> zero counts, null rate and null average (never 0)")
    void emptyWindowHasNullRateAndAverage() {
        DashboardStatsResponse stats = dashboardService.getStats(7);

        assertThat(stats.dailyOrders()).hasSize(7).allMatch(d -> d.count() == 0);
        assertThat(stats.dailyTrend()).hasSize(7).allMatch(t -> t.completedCount() == 0 && t.failedCount() == 0
                && t.failureRate() == null && t.avgGenerationSeconds() == null);
        assertThat(stats.statusBreakdown()).hasSize(4).allMatch(s -> s.count() == 0 && s.share() == 0.0);
        assertThat(stats.kpi().totalOrders()).isZero();
        assertThat(stats.kpi().failureRate()).isNull();
        assertThat(stats.kpi().avgGenerationSeconds()).isNull();
        assertThat(stats.topMedications()).isEmpty();
        assertThat(stats.providerStats()).isEmpty();
    }

    @Test
    @DisplayName("Only plans that have not finished (and a manual upload) -> failure rate stays null")
    void unfinishedPlansGiveNoFailureRate() {
        LocalDateTime at = LocalDateTime.of(2026, 11, 12, 10, 0);
        addOrder(alpha, "Insulin", at, CarePlan.Status.PENDING, false, null);
        addProcessingOrder(alpha, "Insulin", at);
        addOrder(alpha, "Insulin", at, CarePlan.Status.COMPLETED, true, null);

        DashboardStatsResponse.Kpi kpi = dashboardService.getStats(7).kpi();

        assertThat(kpi.totalOrders()).isEqualTo(3);
        assertThat(kpi.completedCount()).isZero();
        assertThat(kpi.failedCount()).isZero();
        assertThat(kpi.manualUploadCount()).isEqualTo(1);
        assertThat(kpi.failureRate()).isNull();
        assertThat(kpi.avgGenerationSeconds()).isNull();
        // the same day in the trend: three plans, none of them finished by the LLM -> nothing to rate
        DashboardStatsResponse.DailyTrend day = dashboardService.getStats(7).dailyTrend().get(4);
        assertThat(day.completedCount()).isZero();
        assertThat(day.failedCount()).isZero();
        assertThat(day.failureRate()).isNull();
        assertThat(day.avgGenerationSeconds()).isNull();
    }

    @Test
    @DisplayName("Durations with fractions of a second are averaged as they are, not truncated to whole seconds")
    void keepsFractionsOfASecond() {
        LocalDateTime at = LocalDateTime.of(2026, 11, 12, 10, 0);
        for (long millis : new long[] {1500, 2000}) {
            CarePlan carePlan = newCarePlan(alpha, "Insulin", at, CarePlan.Status.COMPLETED, false);
            carePlan.setStartedAt(at.plusMinutes(1));
            carePlan.setFinishedAt(at.plusMinutes(1).plusNanos(millis * 1_000_000));
            carePlanRepository.save(carePlan);
        }

        DashboardStatsResponse stats = dashboardService.getStats(7);

        // (1.5 + 2.0) / 2 = 1.75; truncating each run to whole seconds would give 1.5
        assertThat(stats.kpi().avgGenerationSeconds()).isEqualTo(1.75);
        assertThat(stats.providerStats().get(0).avgGenerationSeconds()).isEqualTo(1.75);
        assertThat(stats.dailyTrend().get(4).avgGenerationSeconds()).isEqualTo(1.75); // 2026-11-12
    }

    @Test
    @DisplayName("Millisecond-sized durations (the local mock LLM finishes in 5-70 ms) are kept, not rounded to 0")
    void keepsMillisecondDurations() {
        LocalDateTime at = LocalDateTime.of(2026, 11, 12, 10, 0);
        for (long millis : new long[] {7, 13}) {
            CarePlan carePlan = newCarePlan(alpha, "Insulin", at, CarePlan.Status.COMPLETED, false);
            carePlan.setStartedAt(at.plusMinutes(1));
            carePlan.setFinishedAt(at.plusMinutes(1).plusNanos(millis * 1_000_000));
            carePlanRepository.save(carePlan);
        }

        DashboardStatsResponse stats = dashboardService.getStats(7);

        // (0.007 + 0.013) / 2 = 0.010 s; the API stays in seconds, so the UI decides how to show 10 ms
        assertThat(stats.kpi().avgGenerationSeconds()).isCloseTo(0.010, within(1e-9));
        assertThat(stats.dailyTrend().get(4).avgGenerationSeconds()).isCloseTo(0.010, within(1e-9));
    }

    @Test
    @DisplayName("More than 10 medications / providers -> only the top 10 come back")
    void listsAreLimitedToTen() {
        LocalDateTime at = LocalDateTime.of(2026, 11, 12, 10, 0);
        IntStream.rangeClosed(1, 12).forEach(i -> {
            Provider provider = provider("Dr. " + (char) ('A' + i), String.format("20000000%02d", i));
            addOrder(provider, "Drug " + (char) ('A' + i), at, CarePlan.Status.PENDING, false, null);
        });

        DashboardStatsResponse stats = dashboardService.getStats(7);

        assertThat(stats.kpi().totalOrders()).isEqualTo(12);
        assertThat(stats.topMedications()).hasSize(10);
        assertThat(stats.providerStats()).hasSize(10);
    }

    @Test
    @DisplayName("days other than 7 or 30 is rejected before any query")
    void rejectsOtherRanges() {
        assertThatThrownBy(() -> dashboardService.getStats(14))
                .isInstanceOf(ValidationError.class)
                .hasMessage("days must be 7 or 30");
    }

    // ----- data -----

    private void insertKnownRows() {
        addOrder(alpha, "Gamunex-C", at(11, 8, 0, 0, 0), CarePlan.Status.COMPLETED, false, 10);
        addOrder(alpha, "gamunex-c", at(11, 8, 23, 59, 59), CarePlan.Status.COMPLETED, false, 20);
        addOrder(alpha, "Gamunex-C", at(11, 10, 9, 0, 0), CarePlan.Status.FAILED, false, 6);
        addOrder(beta, "Metformin", at(11, 10, 15, 0, 0), CarePlan.Status.COMPLETED, false, 30);
        addOrder(beta, "Metformin", at(11, 10, 16, 0, 0), CarePlan.Status.PENDING, false, null);
        addProcessingOrder(beta, "Lisinopril", at(11, 14, 23, 59, 59));
        addOrder(beta, "Metformin", at(11, 12, 8, 0, 0), CarePlan.Status.COMPLETED, true, null);
        addOrder(gamma, "Lisinopril", at(11, 12, 9, 0, 0), CarePlan.Status.COMPLETED, false, null);
        addOrder(alpha, "Gamunex-C", at(11, 7, 23, 59, 59), CarePlan.Status.COMPLETED, false, 100);
        addOrder(alpha, "Gamunex-C", at(11, 15, 0, 0, 0), CarePlan.Status.FAILED, false, 6);
        addOrder(alpha, "Insulin", LocalDateTime.of(2026, 10, 20, 10, 0), CarePlan.Status.COMPLETED, false,
                40);
    }

    private static LocalDateTime at(int month, int day, int hour, int minute, int second) {
        return LocalDateTime.of(2026, month, day, hour, minute, second);
    }

    private Provider provider(String name, String npi) {
        Provider provider = new Provider();
        provider.setName(name);
        provider.setNpi(npi);
        return providerRepository.save(provider);
    }

    /**
     * Inserts an order and its care plan. runSeconds is how long the LLM run took (the run starts one minute after
     * the order); null leaves the timing columns NULL, like a plan that never ran or an old row.
     */
    private void addOrder(Provider provider, String medication, LocalDateTime createdAt, CarePlan.Status status,
                          boolean uploaded, Integer runSeconds) {
        CarePlan carePlan = newCarePlan(provider, medication, createdAt, status, uploaded);
        if (runSeconds != null) {
            LocalDateTime startedAt = createdAt.plusMinutes(1);
            carePlan.setStartedAt(startedAt);
            carePlan.setFinishedAt(startedAt.plusSeconds(runSeconds));
            carePlan.setAttemptCount(1);
        }
        carePlanRepository.save(carePlan);
    }

    /** A plan a worker has picked up: started_at is set, finished_at is still NULL. */
    private void addProcessingOrder(Provider provider, String medication, LocalDateTime createdAt) {
        CarePlan carePlan = newCarePlan(provider, medication, createdAt, CarePlan.Status.PROCESSING, false);
        carePlan.markProcessing(createdAt);
        carePlanRepository.save(carePlan);
    }

    private CarePlan newCarePlan(Provider provider, String medication, LocalDateTime createdAt,
                                 CarePlan.Status status, boolean uploaded) {
        Patient patient = new Patient();
        patient.setFirstName("Test");
        patient.setLastName("Patient");
        patient.setMrn(String.valueOf(mrnCounter++));
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
        carePlan.setUploaded(uploaded);
        return carePlan;
    }

    /** Datasource + Flyway + JPA + the dashboard service with a fixed clock: no web, security, Redis or LLM. */
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
    @Import(DashboardService.class)
    static class TestApplication {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC);
        }
    }
}

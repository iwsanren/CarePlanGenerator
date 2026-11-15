package com.page24.backend.service;

import com.page24.backend.dto.DashboardStatsResponse;
import com.page24.backend.entity.CarePlan;
import com.page24.backend.exception.ValidationError;
import com.page24.backend.repository.DashboardQueryRepository;
import com.page24.backend.repository.DashboardQueryRepository.DailyCountRow;
import com.page24.backend.repository.DashboardQueryRepository.KpiRow;
import com.page24.backend.repository.DashboardQueryRepository.MedicationCountRow;
import com.page24.backend.repository.DashboardQueryRepository.ProviderRow;
import com.page24.backend.repository.DashboardQueryRepository.StatusCountRow;
import com.page24.backend.repository.DashboardQueryRepository.TrendRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DashboardService} with a mocked repository and a fixed clock: the window, the zero-filling of days,
 * the failure rate and the rule that missing data stays null. (Whether the SQL itself groups correctly is checked
 * on a real PostgreSQL in {@link DashboardServicePostgresTest}.)
 */
class DashboardServiceTest {

    // 2026-11-14 12:00 UTC
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC);

    private DashboardQueryRepository queries;
    private DashboardService service;

    @BeforeEach
    void setUp() {
        queries = mock(DashboardQueryRepository.class);
        service = new DashboardService(queries, CLOCK);
        // an empty database by default; tests override what they need
        when(queries.countOrdersPerDay(any(), any())).thenReturn(List.of());
        when(queries.dailyTrend(any(), any(), any(), any())).thenReturn(List.of());
        when(queries.countCarePlansPerStatus(any(), any())).thenReturn(List.of());
        when(queries.topMedications(any(), any(), any())).thenReturn(List.of());
        when(queries.providerStats(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(queries.kpi(any(), any(), any(), any())).thenReturn(new Kpi(null, null, null, null));
    }

    // ----- window and range -----

    @Test
    @DisplayName("7 days -> window is the 7 UTC days ending today; the query gets [start of day 1, start of tomorrow)")
    void queriesTheWholeUtcDaysIncludingToday() {
        DashboardStatsResponse stats = service.getStats(7);

        assertThat(stats.days()).isEqualTo(7);
        assertThat(stats.from()).isEqualTo(LocalDate.of(2026, 11, 8));
        assertThat(stats.to()).isEqualTo(LocalDate.of(2026, 11, 14));
        assertThat(stats.timezone()).isEqualTo("UTC");
        verify(queries).countOrdersPerDay(LocalDateTime.of(2026, 11, 8, 0, 0), LocalDateTime.of(2026, 11, 15, 0, 0));
        verify(queries).kpi(eq(LocalDateTime.of(2026, 11, 8, 0, 0)), eq(LocalDateTime.of(2026, 11, 15, 0, 0)),
                eq(CarePlan.Status.COMPLETED), eq(CarePlan.Status.FAILED));
    }

    @Test
    @DisplayName("30 days -> 30 daily entries starting 29 days before today")
    void thirtyDayWindow() {
        DashboardStatsResponse stats = service.getStats(30);

        assertThat(stats.from()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(stats.dailyOrders()).hasSize(30);
        assertThat(stats.dailyTrend()).hasSize(30);
        verify(queries).countOrdersPerDay(LocalDateTime.of(2026, 10, 16, 0, 0), LocalDateTime.of(2026, 11, 15, 0, 0));
    }

    @Test
    @DisplayName("The trend query gets the same window and the same two statuses as the KPI query")
    void trendUsesTheSameWindowAndStatusesAsTheKpi() {
        service.getStats(7);

        verify(queries).dailyTrend(LocalDateTime.of(2026, 11, 8, 0, 0), LocalDateTime.of(2026, 11, 15, 0, 0),
                CarePlan.Status.COMPLETED, CarePlan.Status.FAILED);
    }

    @Test
    @DisplayName("Just before UTC midnight it is still the old day; at midnight the window moves on")
    void todayFollowsUtcMidnight() {
        Clock beforeMidnight = Clock.fixed(Instant.parse("2026-11-14T23:59:59Z"), ZoneOffset.UTC);
        Clock atMidnight = Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC);

        assertThat(new DashboardService(queries, beforeMidnight).getStats(7).to()).isEqualTo(LocalDate.of(2026, 11, 14));
        assertThat(new DashboardService(queries, atMidnight).getStats(7).to()).isEqualTo(LocalDate.of(2026, 11, 15));
    }

    @ParameterizedTest(name = "days={0} is rejected")
    @ValueSource(ints = {-7, 0, 1, 6, 8, 14, 29, 31, 90})
    @DisplayName("Any range other than 7 or 30 -> INVALID_DAYS (400) and no query runs")
    void rejectsOtherRanges(int days) {
        Throwable thrown = catchThrowable(() -> service.getStats(days));

        assertThat(thrown).isInstanceOf(ValidationError.class);
        ValidationError error = (ValidationError) thrown;
        assertThat(error.getCode()).isEqualTo("INVALID_DAYS");
        assertThat(error.getMessage()).isEqualTo("days must be 7 or 30");
        assertThat(error.getHttpStatus().value()).isEqualTo(400);
        verifyNoInteractions(queries);
    }

    // ----- daily orders -----

    @Test
    @DisplayName("Days without orders are returned with count 0, in date order, for every day of the window")
    void fillsMissingDaysWithZero() {
        when(queries.countOrdersPerDay(any(), any())).thenReturn(List.of(
                new Day(LocalDate.of(2026, 11, 9), 4), new Day(LocalDate.of(2026, 11, 12), 1)));

        DashboardStatsResponse stats = service.getStats(7);

        assertThat(stats.dailyOrders()).extracting(d -> d.date() + "=" + d.count()).containsExactly(
                "2026-11-08=0", "2026-11-09=4", "2026-11-10=0", "2026-11-11=0",
                "2026-11-12=1", "2026-11-13=0", "2026-11-14=0");
        assertThat(stats.kpi().totalOrders()).isEqualTo(5);
    }

    @Test
    @DisplayName("No orders at all -> every day is 0 and the total is 0")
    void emptyDatabaseGivesAllZeroDays() {
        DashboardStatsResponse stats = service.getStats(7);

        assertThat(stats.dailyOrders()).hasSize(7).allMatch(d -> d.count() == 0);
        assertThat(stats.kpi().totalOrders()).isZero();
    }

    // ----- daily trend -----

    @Test
    @DisplayName("No care plans -> every day of the window is present with 0 counts and null rate / average")
    void emptyTrendHasEveryDayWithNulls() {
        DashboardStatsResponse stats = service.getStats(7);

        assertThat(stats.dailyTrend()).extracting(t -> t.date().toString()).containsExactly(
                "2026-11-08", "2026-11-09", "2026-11-10", "2026-11-11", "2026-11-12", "2026-11-13", "2026-11-14");
        assertThat(stats.dailyTrend()).allMatch(t -> t.completedCount() == 0 && t.failedCount() == 0
                && t.failureRate() == null && t.avgGenerationSeconds() == null);
    }

    @Test
    @DisplayName("Days with rows get their own rate and average; the other days stay null (gaps, not zeros)")
    void trendMapsRowsToTheirDay() {
        when(queries.dailyTrend(any(), any(), any(), any())).thenReturn(List.of(
                new Trend(LocalDate.of(2026, 11, 9), 3L, 1L, 12.345),
                new Trend(LocalDate.of(2026, 11, 12), 0L, 2L, null)));

        DashboardStatsResponse stats = service.getStats(7);

        assertThat(stats.dailyTrend()).extracting(t -> t.date() + "|" + t.completedCount() + "|" + t.failedCount()
                + "|" + t.failureRate() + "|" + t.avgGenerationSeconds()).containsExactly(
                "2026-11-08|0|0|null|null",
                "2026-11-09|3|1|0.25|12.35",
                "2026-11-10|0|0|null|null",
                "2026-11-11|0|0|null|null",
                "2026-11-12|0|2|1.0|null",
                "2026-11-13|0|0|null|null",
                "2026-11-14|0|0|null|null");
    }

    @Test
    @DisplayName("All plans of a day finished OK -> real rate 0.0; no timing data -> average stays null")
    void trendKeepsRealZeroAndMissingTimingApart() {
        when(queries.dailyTrend(any(), any(), any(), any())).thenReturn(List.of(
                new Trend(LocalDate.of(2026, 11, 10), 2L, 0L, null)));

        DashboardStatsResponse.DailyTrend day = service.getStats(7).dailyTrend().get(2);

        assertThat(day.failureRate()).isEqualTo(0.0);
        assertThat(day.avgGenerationSeconds()).isNull();
    }

    @Test
    @DisplayName("A day that only has unfinished plans (NULL sums) -> counts 0, rate null, average null")
    void trendDayWithNullSumsIsEmpty() {
        when(queries.dailyTrend(any(), any(), any(), any())).thenReturn(List.of(
                new Trend(LocalDate.of(2026, 11, 10), null, null, null)));

        DashboardStatsResponse.DailyTrend day = service.getStats(7).dailyTrend().get(2);

        assertThat(day.completedCount()).isZero();
        assertThat(day.failedCount()).isZero();
        assertThat(day.failureRate()).isNull();
        assertThat(day.avgGenerationSeconds()).isNull();
    }

    @Test
    @DisplayName("Per-day rate uses the same rounding as the KPI: 1 of 3 -> 0.3333")
    void trendRateIsRoundedLikeTheKpi() {
        when(queries.dailyTrend(any(), any(), any(), any())).thenReturn(List.of(
                new Trend(LocalDate.of(2026, 11, 10), 2L, 1L, 1.0)));

        assertThat(service.getStats(7).dailyTrend().get(2).failureRate()).isEqualTo(0.3333);
    }

    // ----- status shares -----

    @Test
    @DisplayName("All four statuses are always returned in enum order; missing ones are 0")
    void statusBreakdownAlwaysHasFourEntries() {
        when(queries.countCarePlansPerStatus(any(), any())).thenReturn(List.of(
                new StatusRow(CarePlan.Status.FAILED, 1), new StatusRow(CarePlan.Status.COMPLETED, 3)));

        DashboardStatsResponse stats = service.getStats(7);

        assertThat(stats.statusBreakdown()).extracting(s -> s.status() + "=" + s.count() + "/" + s.share())
                .containsExactly("PENDING=0/0.0", "PROCESSING=0/0.0", "COMPLETED=3/0.75", "FAILED=1/0.25");
    }

    @Test
    @DisplayName("Shares are rounded to 4 decimals")
    void sharesAreRounded() {
        when(queries.countCarePlansPerStatus(any(), any())).thenReturn(List.of(
                new StatusRow(CarePlan.Status.PENDING, 1), new StatusRow(CarePlan.Status.COMPLETED, 2)));

        assertThat(service.getStats(7).statusBreakdown()).extracting(DashboardStatsResponse.StatusShare::share)
                .containsExactly(0.3333, 0.0, 0.6667, 0.0);
    }

    @Test
    @DisplayName("No care plans -> four zero entries with share 0 (no division by zero)")
    void noCarePlansGivesZeroShares() {
        assertThat(service.getStats(7).statusBreakdown()).hasSize(4)
                .allMatch(s -> s.count() == 0 && s.share() == 0.0);
    }

    // ----- failure rate and duration -----

    @Test
    @DisplayName("Failure rate = failed / (completed + failed): 1 of 4 finished plans -> 0.25")
    void failureRateIsFailedOverFinished() {
        when(queries.kpi(any(), any(), any(), any())).thenReturn(new Kpi(3L, 1L, 2L, 8.0));

        DashboardStatsResponse.Kpi kpi = service.getStats(7).kpi();

        assertThat(kpi.completedCount()).isEqualTo(3);
        assertThat(kpi.failedCount()).isEqualTo(1);
        assertThat(kpi.failureRate()).isEqualTo(0.25);
        assertThat(kpi.manualUploadCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Failure rate is rounded to 4 decimals")
    void failureRateIsRounded() {
        when(queries.kpi(any(), any(), any(), any())).thenReturn(new Kpi(2L, 1L, 0L, 1.0));

        assertThat(service.getStats(7).kpi().failureRate()).isEqualTo(0.3333);
    }

    @Test
    @DisplayName("Everything failed -> rate 1.0; nothing failed -> rate 0.0 (a real zero, because plans finished)")
    void rateExtremes() {
        when(queries.kpi(any(), any(), any(), any())).thenReturn(new Kpi(0L, 2L, 0L, null));
        assertThat(service.getStats(7).kpi().failureRate()).isEqualTo(1.0);

        when(queries.kpi(any(), any(), any(), any())).thenReturn(new Kpi(2L, 0L, 0L, 5.0));
        assertThat(service.getStats(7).kpi().failureRate()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Nothing finished (SUMs are NULL in the database) -> counts 0, rate null, average null")
    void noFinishedPlansGiveNullRateAndAverage() {
        // an empty window: the database answers one row whose SUMs and AVG are NULL
        DashboardStatsResponse.Kpi kpi = service.getStats(7).kpi();

        assertThat(kpi.completedCount()).isZero();
        assertThat(kpi.failedCount()).isZero();
        assertThat(kpi.manualUploadCount()).isZero();
        assertThat(kpi.failureRate()).isNull();
        assertThat(kpi.avgGenerationSeconds()).isNull();
    }

    @Test
    @DisplayName("Plans finished but none has timing data (old rows) -> average stays null, not 0")
    void missingTimingIsNullNotZero() {
        when(queries.kpi(any(), any(), any(), any())).thenReturn(new Kpi(5L, 1L, 0L, null));

        DashboardStatsResponse.Kpi kpi = service.getStats(7).kpi();

        assertThat(kpi.failureRate()).isNotNull();
        assertThat(kpi.avgGenerationSeconds()).isNull();
    }

    @Test
    @DisplayName("Average duration is passed through rounded to 2 decimals")
    void averageIsRounded() {
        when(queries.kpi(any(), any(), any(), any())).thenReturn(new Kpi(3L, 0L, 0L, 8.456));

        assertThat(service.getStats(7).kpi().avgGenerationSeconds()).isEqualTo(8.46);
    }

    // ----- top lists -----

    @Test
    @DisplayName("Medications and providers are asked for the top 10 only")
    void asksForTheTopTen() {
        service.getStats(7);

        ArgumentCaptor<Pageable> medications = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<Pageable> providers = ArgumentCaptor.forClass(Pageable.class);
        verify(queries).topMedications(any(), any(), medications.capture());
        verify(queries).providerStats(any(), any(), any(), any(), providers.capture());
        assertThat(medications.getValue().getPageNumber()).isZero();
        assertThat(medications.getValue().getPageSize()).isEqualTo(10);
        assertThat(providers.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("Medication rows are passed through in the order the database returned them")
    void keepsMedicationOrder() {
        when(queries.topMedications(any(), any(), any())).thenReturn(List.of(
                new Medication("Gamunex-C", 5), new Medication("Metformin", 2)));

        assertThat(service.getStats(7).topMedications())
                .extracting(m -> m.medicationName() + "=" + m.orderCount())
                .containsExactly("Gamunex-C=5", "Metformin=2");
    }

    @Test
    @DisplayName("Provider with no failures (NULL sum) -> failedCount 0; provider without timing -> average null")
    void providerStatsKeepMissingDataAsNull() {
        when(queries.providerStats(any(), any(), any(), any(), any())).thenReturn(List.of(
                new Provider(1L, "Dr. A", 4, 1L, 12.345),
                new Provider(2L, "Dr. B", 1, null, null)));

        assertThat(service.getStats(7).providerStats())
                .extracting(p -> p.providerId() + "|" + p.providerName() + "|" + p.orderCount() + "|"
                        + p.failedCount() + "|" + p.avgGenerationSeconds())
                .containsExactly("1|Dr. A|4|1|12.35", "2|Dr. B|1|0|null");
    }

    // ----- fake rows: a record component named getX() implements the interface method getX() -----

    private record Day(LocalDate getDay, long getOrderCount) implements DailyCountRow {
    }

    private record Trend(LocalDate getDay, Long getCompletedCount, Long getFailedCount, Double getAvgSeconds)
            implements TrendRow {
    }

    private record StatusRow(CarePlan.Status getStatus, long getPlanCount) implements StatusCountRow {
    }

    private record Medication(String getMedicationName, long getOrderCount) implements MedicationCountRow {
    }

    private record Kpi(Long getCompletedCount, Long getFailedCount, Long getManualUploadCount, Double getAvgSeconds)
            implements KpiRow {
    }

    private record Provider(Long getProviderId, String getProviderName, long getOrderCount, Long getFailedCount,
                            Double getAvgSeconds) implements ProviderRow {
    }
}

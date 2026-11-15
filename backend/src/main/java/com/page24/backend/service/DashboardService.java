package com.page24.backend.service;

import com.page24.backend.dto.DashboardStatsResponse;
import com.page24.backend.dto.DashboardStatsResponse.DailyOrders;
import com.page24.backend.dto.DashboardStatsResponse.DailyTrend;
import com.page24.backend.dto.DashboardStatsResponse.Kpi;
import com.page24.backend.dto.DashboardStatsResponse.MedicationCount;
import com.page24.backend.dto.DashboardStatsResponse.ProviderStats;
import com.page24.backend.dto.DashboardStatsResponse.StatusShare;
import com.page24.backend.entity.CarePlan;
import com.page24.backend.exception.ValidationError;
import com.page24.backend.repository.DashboardQueryRepository;
import com.page24.backend.repository.DashboardQueryRepository.DailyCountRow;
import com.page24.backend.repository.DashboardQueryRepository.KpiRow;
import com.page24.backend.repository.DashboardQueryRepository.TrendRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the dashboard figures. The database does all the grouping and summing
 * ({@link DashboardQueryRepository}); this class only validates the range, fills in the days without orders
 * and turns counts into rates.
 *
 * Window: the last {@code days} whole UTC calendar days including today, i.e. from the start of
 * (today - days + 1) up to the start of tomorrow. "Today" comes from the injected {@link Clock} (UTC).
 * This relies on orders.created_at being a UTC wall-clock value, as every mapper in this API already assumes.
 *
 * Rules for missing data: a rate or an average with nothing to measure is null, not 0.
 * Failure rate = failed / (completed + failed) over LLM-generated plans; PENDING, PROCESSING and manually
 * uploaded plans are not part of it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('PHARMACIST')")
public class DashboardService {

    /** How many medications and providers are returned. */
    static final int TOP_N = 10;

    private static final String TIMEZONE = "UTC";

    private final DashboardQueryRepository queries;
    private final Clock clock;

    @Transactional(readOnly = true)
    public DashboardStatsResponse getStats(int days) {
        if (days != 7 && days != 30) {
            throw new ValidationError("INVALID_DAYS", "days must be 7 or 30");
        }

        LocalDate to = LocalDate.now(clock);
        LocalDate from = to.minusDays(days - 1L);
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        log.debug("Building dashboard stats: days={}, from={}, to={}", days, from, to);

        List<DailyOrders> dailyOrders = fillMissingDays(queries.countOrdersPerDay(start, end), from, days);
        List<DailyTrend> dailyTrend = fillMissingTrendDays(
                queries.dailyTrend(start, end, CarePlan.Status.COMPLETED, CarePlan.Status.FAILED), from, days);
        List<StatusShare> statusBreakdown = statusBreakdown(start, end);
        List<MedicationCount> medications = queries.topMedications(start, end, PageRequest.of(0, TOP_N)).stream()
                .map(row -> new MedicationCount(row.getMedicationName(), row.getOrderCount()))
                .toList();
        List<ProviderStats> providers = queries.providerStats(start, end,
                        CarePlan.Status.COMPLETED, CarePlan.Status.FAILED, PageRequest.of(0, TOP_N)).stream()
                .map(row -> new ProviderStats(row.getProviderId(), row.getProviderName(), row.getOrderCount(),
                        orZero(row.getFailedCount()), roundOrNull(row.getAvgSeconds(), 2)))
                .toList();

        long totalOrders = dailyOrders.stream().mapToLong(DailyOrders::count).sum();
        return new DashboardStatsResponse(days, from, to, TIMEZONE,
                kpi(start, end, totalOrders), dailyOrders, dailyTrend, statusBreakdown, medications, providers);
    }

    /** The query returns only days that have orders; the chart needs every day of the window. */
    private static List<DailyOrders> fillMissingDays(List<DailyCountRow> rows, LocalDate from, int days) {
        Map<LocalDate, Long> countByDay = new HashMap<>();
        for (DailyCountRow row : rows) {
            countByDay.put(row.getDay(), row.getOrderCount());
        }
        List<DailyOrders> result = new ArrayList<>(days);
        for (int offset = 0; offset < days; offset++) {
            LocalDate day = from.plusDays(offset);
            result.add(new DailyOrders(day, countByDay.getOrDefault(day, 0L)));
        }
        return result;
    }

    private List<StatusShare> statusBreakdown(LocalDateTime start, LocalDateTime end) {
        Map<CarePlan.Status, Long> countByStatus = new EnumMap<>(CarePlan.Status.class);
        queries.countCarePlansPerStatus(start, end)
                .forEach(row -> countByStatus.put(row.getStatus(), row.getPlanCount()));
        long total = countByStatus.values().stream().mapToLong(Long::longValue).sum();

        List<StatusShare> result = new ArrayList<>();
        for (CarePlan.Status status : CarePlan.Status.values()) {
            long count = countByStatus.getOrDefault(status, 0L);
            double share = total == 0 ? 0.0 : round((double) count / total, 4);
            result.add(new StatusShare(status.name(), count, share));
        }
        return result;
    }

    private Kpi kpi(LocalDateTime start, LocalDateTime end, long totalOrders) {
        KpiRow row = queries.kpi(start, end, CarePlan.Status.COMPLETED, CarePlan.Status.FAILED);
        long completed = orZero(row.getCompletedCount());
        long failed = orZero(row.getFailedCount());
        return new Kpi(totalOrders, completed, failed, failureRate(completed, failed),
                roundOrNull(row.getAvgSeconds(), 2), orZero(row.getManualUploadCount()));
    }

    /** The query returns only days that have care plans; the trend chart needs every day, with gaps as null. */
    private static List<DailyTrend> fillMissingTrendDays(List<TrendRow> rows, LocalDate from, int days) {
        Map<LocalDate, TrendRow> rowByDay = new HashMap<>();
        for (TrendRow row : rows) {
            rowByDay.put(row.getDay(), row);
        }
        List<DailyTrend> result = new ArrayList<>(days);
        for (int offset = 0; offset < days; offset++) {
            LocalDate day = from.plusDays(offset);
            TrendRow row = rowByDay.get(day);
            if (row == null) {
                result.add(new DailyTrend(day, 0L, 0L, null, null));
                continue;
            }
            long completed = orZero(row.getCompletedCount());
            long failed = orZero(row.getFailedCount());
            result.add(new DailyTrend(day, completed, failed, failureRate(completed, failed),
                    roundOrNull(row.getAvgSeconds(), 2)));
        }
        return result;
    }

    /** failed / (completed + failed). Nothing finished: no rate to show, which is different from a rate of 0. */
    private static Double failureRate(long completed, long failed) {
        long finished = completed + failed;
        return finished == 0 ? null : round((double) failed / finished, 4);
    }

    private static long orZero(Long count) {
        return count == null ? 0L : count;
    }

    private static Double roundOrNull(Double value, int scale) {
        return value == null ? null : round(value, scale);
    }

    private static double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}

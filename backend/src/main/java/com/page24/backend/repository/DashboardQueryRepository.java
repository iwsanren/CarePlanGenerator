package com.page24.backend.repository;

import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.Order;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Read-only aggregate queries for the dashboard. Every GROUP BY / SUM / AVG runs in the database and only the
 * aggregated rows come back; no order is loaded into memory.
 *
 * All queries take the same half-open window on orders.created_at: from (inclusive) to to (exclusive).
 * The statements are plain JPQL so that they run on H2 (MockMvc tests) and PostgreSQL (production,
 * Testcontainers tests). They select no patient column at all.
 *
 * Duration = finished_at - started_at, in seconds (wall-clock, includes the retry back-off). It is only taken
 * from COMPLETED, LLM-generated plans (uploaded = false) that have both timestamps: a NULL timestamp makes the
 * CASE produce NULL, and AVG ignores NULL, so missing data is never counted as 0.
 */
public interface DashboardQueryRepository extends Repository<Order, Long> {

    interface DailyCountRow {
        LocalDate getDay();

        long getOrderCount();
    }

    interface StatusCountRow {
        CarePlan.Status getStatus();

        long getPlanCount();
    }

    interface MedicationCountRow {
        String getMedicationName();

        long getOrderCount();
    }

    /** One row; every SUM is null when the window holds no care plan. */
    interface KpiRow {
        Long getCompletedCount();

        Long getFailedCount();

        Long getManualUploadCount();

        Double getAvgSeconds();
    }

    interface ProviderRow {
        Long getProviderId();

        String getProviderName();

        long getOrderCount();

        Long getFailedCount();

        Double getAvgSeconds();
    }

    @Query("""
            select cast(o.createdAt as LocalDate) as day, count(o) as orderCount
            from Order o
            where o.createdAt >= :from and o.createdAt < :to
            group by cast(o.createdAt as LocalDate)
            order by cast(o.createdAt as LocalDate)
            """)
    List<DailyCountRow> countOrdersPerDay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("""
            select cp.status as status, count(cp) as planCount
            from CarePlan cp join cp.order o
            where o.createdAt >= :from and o.createdAt < :to
            group by cp.status
            """)
    List<StatusCountRow> countCarePlansPerStatus(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * Names are grouped case-insensitively (like duplicate detection). The spelling shown is one of the spellings
     * used (the database's smallest, which depends on its collation); the count is exact either way.
     */
    @Query("""
            select min(o.medicationName) as medicationName, count(o) as orderCount
            from Order o
            where o.createdAt >= :from and o.createdAt < :to
            group by lower(o.medicationName)
            order by count(o) desc, lower(o.medicationName) asc
            """)
    List<MedicationCountRow> topMedications(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                            Pageable limit);

    @Query("""
            select
              sum(case when cp.status = :completed and cp.uploaded = false then 1 else 0 end) as completedCount,
              sum(case when cp.status = :failed and cp.uploaded = false then 1 else 0 end) as failedCount,
              sum(case when cp.uploaded = true then 1 else 0 end) as manualUploadCount,
              avg(case when cp.status = :completed and cp.uploaded = false
                        and cp.startedAt is not null and cp.finishedAt is not null
                       then (cp.finishedAt - cp.startedAt) by second else null end) as avgSeconds
            from CarePlan cp join cp.order o
            where o.createdAt >= :from and o.createdAt < :to
            """)
    KpiRow kpi(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
               @Param("completed") CarePlan.Status completed, @Param("failed") CarePlan.Status failed);

    @Query("""
            select p.id as providerId, p.name as providerName, count(cp) as orderCount,
              sum(case when cp.status = :failed and cp.uploaded = false then 1 else 0 end) as failedCount,
              avg(case when cp.status = :completed and cp.uploaded = false
                        and cp.startedAt is not null and cp.finishedAt is not null
                       then (cp.finishedAt - cp.startedAt) by second else null end) as avgSeconds
            from CarePlan cp join cp.order o join o.provider p
            where o.createdAt >= :from and o.createdAt < :to
            group by p.id, p.name
            order by count(cp) desc, p.name asc, p.id asc
            """)
    List<ProviderRow> providerStats(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                    @Param("completed") CarePlan.Status completed,
                                    @Param("failed") CarePlan.Status failed, Pageable limit);
}

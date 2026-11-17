package com.page24.backend.repository;

import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.Order;
import com.page24.backend.entity.Patient;
import com.page24.backend.entity.Provider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the order list query from the conditions that are actually present.
 *
 * Why Criteria and not one fixed JPQL string: with optional filters the fixed form needs
 * "(:x is null or col = :x)" for every filter, which makes PostgreSQL reuse one plan for all combinations, and
 * the ORDER BY would have to be pasted into the string. Here a filter that was not asked for adds nothing to the
 * SQL, and every column name comes from a fixed list in this class, never from the caller.
 *
 * The root is CarePlan because the foreign key lives there (care_plans.order_id): CarePlan -> Order -> Patient and
 * Provider are plain joins, and Order needs no back-reference. The select lists the columns the list shows, so no
 * entity is loaded: one statement for the page, one for the total, whatever the page size.
 *
 * Nothing in this class logs: the filter values can be a patient name or an MRN.
 */
public class OrderSearchRepositoryImpl implements OrderSearchRepository {

    /** The escape character of every LIKE; OrderListQueryParser escapes %, _ and \ with it. */
    private static final char LIKE_ESCAPE = '\\';

    private final EntityManager entityManager;

    public OrderSearchRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public OrderSearchPage search(OrderListQuery query) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        long total = count(cb, query);
        if (total == 0 || query.offset() >= total) {
            return new OrderSearchPage(total, List.of());
        }

        CriteriaQuery<OrderListRow> select = cb.createQuery(OrderListRow.class);
        Tables t = Tables.of(select.from(CarePlan.class));
        select.select(cb.construct(OrderListRow.class,
                t.order().get("id"),
                t.patient().get("firstName"),
                t.patient().get("lastName"),
                t.patient().get("mrn"),
                t.order().get("medicationName"),
                t.carePlan().get("status"),
                t.order().get("createdAt"),
                t.provider().get("name"),
                t.provider().get("npi")));
        select.where(predicates(cb, t, query).toArray(new Predicate[0]));
        select.orderBy(orderBy(cb, t, query.sort()));

        List<OrderListRow> rows = entityManager.createQuery(select)
                .setFirstResult(query.offset())
                .setMaxResults(query.pageSize())
                .getResultList();
        return new OrderSearchPage(total, rows);
    }

    private long count(CriteriaBuilder cb, OrderListQuery query) {
        CriteriaQuery<Long> count = cb.createQuery(Long.class);
        Tables t = Tables.of(count.from(CarePlan.class));
        count.select(cb.count(t.carePlan()));
        count.where(predicates(cb, t, query).toArray(new Predicate[0]));
        return entityManager.createQuery(count).getSingleResult();
    }

    /** The tables of the query, joined once and shared by the page query and the count query. */
    private record Tables(
            Root<CarePlan> carePlan,
            Join<CarePlan, Order> order,
            Join<Order, Patient> patient,
            Join<Order, Provider> provider
    ) {
        static Tables of(Root<CarePlan> carePlan) {
            Join<CarePlan, Order> order = carePlan.join("order");
            return new Tables(carePlan, order, order.join("patient"), order.join("provider"));
        }
    }

    /** One predicate per filter that was given; all of them are ANDed by the caller. */
    private List<Predicate> predicates(CriteriaBuilder cb, Tables t, OrderListQuery q) {
        List<Predicate> predicates = new ArrayList<>();
        if (q.status() != null) {
            predicates.add(cb.equal(t.carePlan().get("status"), q.status()));
        }
        if (q.patientId() != null) {
            predicates.add(cb.equal(t.patient().get("id"), q.patientId()));
        }
        if (q.providerId() != null) {
            predicates.add(cb.equal(t.provider().get("id"), q.providerId()));
        }
        if (q.mrn() != null) {
            predicates.add(cb.equal(t.patient().get("mrn"), q.mrn()));
        }
        if (q.medicationPattern() != null) {
            predicates.add(contains(cb, t.order().get("medicationName"), q.medicationPattern()));
        }
        if (q.providerNamePattern() != null) {
            predicates.add(contains(cb, t.provider().get("name"), q.providerNamePattern()));
        }
        if (q.patientNamePattern() != null) {
            Expression<String> first = t.patient().get("firstName");
            Expression<String> last = t.patient().get("lastName");
            Expression<String> full = cb.concat(cb.concat(first, " "), last);
            predicates.add(cb.or(
                    contains(cb, first, q.patientNamePattern()),
                    contains(cb, last, q.patientNamePattern()),
                    contains(cb, full, q.patientNamePattern())));
        }
        if (q.createdFrom() != null) {
            predicates.add(cb.greaterThanOrEqualTo(t.order().<LocalDateTime>get("createdAt"), q.createdFrom()));
        }
        if (q.createdBefore() != null) {
            predicates.add(cb.lessThan(t.order().<LocalDateTime>get("createdAt"), q.createdBefore()));
        }
        return predicates;
    }

    /** Case-insensitive "contains": both sides lower-cased, the pattern's wildcards already escaped. */
    private static Predicate contains(CriteriaBuilder cb, Expression<String> column, String pattern) {
        return cb.like(cb.lower(column), pattern, LIKE_ESCAPE);
    }

    private List<jakarta.persistence.criteria.Order> orderBy(CriteriaBuilder cb, Tables t, List<OrderSortKey> keys) {
        List<jakarta.persistence.criteria.Order> orders = new ArrayList<>();
        for (OrderSortKey key : keys) {
            for (Expression<?> expression : sortExpressions(cb, t, key.field())) {
                orders.add(key.ascending() ? cb.asc(expression) : cb.desc(expression));
            }
        }
        return orders;
    }

    /**
     * The columns a whitelisted field sorts by. Text is compared lower-cased so the order does not depend on the
     * database collation (and is the same on H2 and PostgreSQL); a patient is sorted by last name, then first.
     */
    private List<Expression<?>> sortExpressions(CriteriaBuilder cb, Tables t, OrderSortField field) {
        return switch (field) {
            case ID -> List.of(t.order().get("id"));
            case CREATED_AT -> List.of(t.order().get("createdAt"));
            case MEDICATION -> List.of(cb.lower(t.order().<String>get("medicationName")));
            case PATIENT_NAME -> List.of(
                    cb.lower(t.patient().<String>get("lastName")),
                    cb.lower(t.patient().<String>get("firstName")));
            case PROVIDER_NAME -> List.of(cb.lower(t.provider().<String>get("name")));
            case STATUS -> List.of(t.carePlan().get("status"));
        };
    }
}

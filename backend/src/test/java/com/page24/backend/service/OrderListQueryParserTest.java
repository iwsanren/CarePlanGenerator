package com.page24.backend.service;

import com.page24.backend.entity.CarePlan;
import com.page24.backend.exception.ValidationError;
import com.page24.backend.repository.OrderListQuery;
import com.page24.backend.repository.OrderSortField;
import com.page24.backend.repository.OrderSortKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Every rule of the order list parameters, without a database: paging limits, the status, the date range, the
 * search texts and the sort whitelist. A rejected value must give a 400 ValidationError whose message never repeats
 * what the caller sent (a filter can be a patient name or an MRN).
 */
class OrderListQueryParserTest {

    private static OrderListQuery parse(OrderListParams.OrderListParamsBuilder params) {
        return OrderListQueryParser.parse(params.build());
    }

    private static ValidationError rejected(OrderListParams.OrderListParamsBuilder params) {
        Throwable thrown = catchThrowable(() -> parse(params));
        assertThat(thrown).isInstanceOf(ValidationError.class);
        return (ValidationError) thrown;
    }

    private static OrderSortKey key(OrderSortField field, boolean ascending) {
        return new OrderSortKey(field, ascending);
    }

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        @DisplayName("no parameters -> page 1, 20 rows, no filter, newest first with the id as tie-breaker")
        void usesTheDocumentedDefaults() {
            OrderListQuery query = parse(OrderListParams.builder());

            assertThat(query.page()).isEqualTo(1);
            assertThat(query.pageSize()).isEqualTo(20);
            assertThat(query.offset()).isZero();
            assertThat(query.status()).isNull();
            assertThat(query.patientId()).isNull();
            assertThat(query.providerId()).isNull();
            assertThat(query.patientNamePattern()).isNull();
            assertThat(query.mrn()).isNull();
            assertThat(query.medicationPattern()).isNull();
            assertThat(query.providerNamePattern()).isNull();
            assertThat(query.createdFrom()).isNull();
            assertThat(query.createdBefore()).isNull();
            assertThat(query.sort()).containsExactly(key(OrderSortField.CREATED_AT, false),
                    key(OrderSortField.ID, false));
        }
    }

    @Nested
    @DisplayName("paging")
    class Paging {

        @Test
        @DisplayName("page 3 of 25 rows starts at index 50")
        void computesTheOffset() {
            assertThat(parse(OrderListParams.builder().page(3).pageSize(25)).offset()).isEqualTo(50);
        }

        @Test
        @DisplayName("page_size 1 and 100 are accepted (the limits themselves)")
        void acceptsTheLimits() {
            assertThat(parse(OrderListParams.builder().pageSize(1)).pageSize()).isEqualTo(1);
            assertThat(parse(OrderListParams.builder().pageSize(100)).pageSize()).isEqualTo(100);
        }

        @ParameterizedTest(name = "page {0} -> INVALID_PAGE")
        @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
        void rejectsAPageBelowOne(int page) {
            ValidationError error = rejected(OrderListParams.builder().page(page));

            assertThat(error.getCode()).isEqualTo("INVALID_PAGE");
            assertThat(error.getMessage()).isEqualTo("page must be greater than or equal to 1");
        }

        @ParameterizedTest(name = "page_size {0} -> INVALID_PAGE_SIZE")
        @ValueSource(ints = {0, -5})
        void rejectsAPageSizeBelowOne(int pageSize) {
            ValidationError error = rejected(OrderListParams.builder().pageSize(pageSize));

            assertThat(error.getCode()).isEqualTo("INVALID_PAGE_SIZE");
            assertThat(error.getMessage()).isEqualTo("page_size must be greater than or equal to 1");
        }

        @ParameterizedTest(name = "page_size {0} -> INVALID_PAGE_SIZE")
        @ValueSource(ints = {101, 1000, Integer.MAX_VALUE})
        void rejectsAPageSizeAboveTheLimit(int pageSize) {
            ValidationError error = rejected(OrderListParams.builder().pageSize(pageSize));

            assertThat(error.getCode()).isEqualTo("INVALID_PAGE_SIZE");
            assertThat(error.getMessage()).isEqualTo("page_size must be less than or equal to 100");
            assertThat(error.getHttpStatus().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("a page so far out that its first row would not fit an int is rejected, not overflowed")
        void rejectsAnOffsetThatOverflows() {
            ValidationError error = rejected(OrderListParams.builder().page(Integer.MAX_VALUE).pageSize(100));

            assertThat(error.getCode()).isEqualTo("INVALID_PAGE");
            assertThat(error.getMessage()).isEqualTo("page is too large");
        }

        @Test
        @DisplayName("the largest page whose offset still fits is accepted")
        void acceptsTheLastPageThatFits() {
            OrderListQuery query = parse(OrderListParams.builder().page(Integer.MAX_VALUE).pageSize(1));

            assertThat(query.offset()).isEqualTo(Integer.MAX_VALUE - 1);
        }
    }

    @Nested
    @DisplayName("ids and status")
    class IdsAndStatus {

        @Test
        @DisplayName("patient_id and provider_id below 1 are rejected with their own codes")
        void rejectsNonPositiveIds() {
            assertThat(rejected(OrderListParams.builder().patientId(0L)).getCode()).isEqualTo("INVALID_PATIENT_ID");
            assertThat(rejected(OrderListParams.builder().providerId(-3L)).getCode())
                    .isEqualTo("INVALID_PROVIDER_ID");
            assertThat(parse(OrderListParams.builder().patientId(1L).providerId(2L)).patientId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("status is case-insensitive and trimmed; blank means no filter")
        void parsesTheStatus() {
            assertThat(parse(OrderListParams.builder().status(" Completed ")).status())
                    .isEqualTo(CarePlan.Status.COMPLETED);
            assertThat(parse(OrderListParams.builder().status("failed")).status()).isEqualTo(CarePlan.Status.FAILED);
            assertThat(parse(OrderListParams.builder().status("  ")).status()).isNull();
            assertThat(parse(OrderListParams.builder().status(null)).status()).isNull();
        }

        @Test
        @DisplayName("an unknown status is rejected and not repeated in the message")
        void rejectsAnUnknownStatus() {
            ValidationError error = rejected(OrderListParams.builder().status("john-smith"));

            assertThat(error.getCode()).isEqualTo("INVALID_STATUS");
            assertThat(error.getMessage()).isEqualTo("status must be one of: pending, processing, completed, failed");
        }
    }

    @Nested
    @DisplayName("created_from / created_to")
    class Dates {

        @Test
        @DisplayName("the range is whole UTC days: from 00:00 of created_from up to, not including, the day after created_to")
        void buildsAHalfOpenRange() {
            OrderListQuery query = parse(OrderListParams.builder()
                    .createdFrom(LocalDate.of(2026, 11, 8)).createdTo(LocalDate.of(2026, 11, 14)));

            assertThat(query.createdFrom()).isEqualTo(LocalDateTime.of(2026, 11, 8, 0, 0));
            assertThat(query.createdBefore()).isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 0));
        }

        @Test
        @DisplayName("the same day on both ends is a valid one-day range")
        void acceptsASingleDay() {
            LocalDate day = LocalDate.of(2026, 11, 12);

            OrderListQuery query = parse(OrderListParams.builder().createdFrom(day).createdTo(day));

            assertThat(query.createdFrom()).isEqualTo(LocalDateTime.of(2026, 11, 12, 0, 0));
            assertThat(query.createdBefore()).isEqualTo(LocalDateTime.of(2026, 11, 13, 0, 0));
        }

        @Test
        @DisplayName("one end alone is an open range")
        void acceptsOneEnd() {
            OrderListQuery from = parse(OrderListParams.builder().createdFrom(LocalDate.of(2026, 1, 1)));
            OrderListQuery to = parse(OrderListParams.builder().createdTo(LocalDate.of(2026, 1, 31)));

            assertThat(from.createdFrom()).isNotNull();
            assertThat(from.createdBefore()).isNull();
            assertThat(to.createdFrom()).isNull();
            assertThat(to.createdBefore()).isEqualTo(LocalDateTime.of(2026, 2, 1, 0, 0));
        }

        @Test
        @DisplayName("created_from after created_to -> INVALID_DATE_RANGE (400)")
        void rejectsAReversedRange() {
            ValidationError error = rejected(OrderListParams.builder()
                    .createdFrom(LocalDate.of(2026, 11, 15)).createdTo(LocalDate.of(2026, 11, 14)));

            assertThat(error.getCode()).isEqualTo("INVALID_DATE_RANGE");
            assertThat(error.getMessage()).isEqualTo("created_from must not be after created_to");
            assertThat(error.getHttpStatus().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("a date outside 1900..2999 is rejected instead of reaching the database")
        void rejectsImpossibleYears() {
            ValidationError early = rejected(OrderListParams.builder().createdFrom(LocalDate.of(1899, 12, 31)));
            ValidationError late = rejected(OrderListParams.builder().createdTo(LocalDate.of(3000, 1, 1)));
            ValidationError huge = rejected(OrderListParams.builder().createdTo(LocalDate.MAX));

            assertThat(early.getCode()).isEqualTo("INVALID_DATE");
            assertThat(early.getMessage()).isEqualTo("created_from must be between 1900-01-01 and 2999-12-31");
            assertThat(late.getMessage()).isEqualTo("created_to must be between 1900-01-01 and 2999-12-31");
            assertThat(huge.getCode()).isEqualTo("INVALID_DATE");
        }

        @Test
        @DisplayName("the first and last allowed days are accepted")
        void acceptsTheBounds() {
            OrderListQuery query = parse(OrderListParams.builder()
                    .createdFrom(LocalDate.of(1900, 1, 1)).createdTo(LocalDate.of(2999, 12, 31)));

            assertThat(query.createdFrom()).isEqualTo(LocalDateTime.of(1900, 1, 1, 0, 0));
            assertThat(query.createdBefore()).isEqualTo(LocalDateTime.of(3000, 1, 1, 0, 0));
        }
    }

    @Nested
    @DisplayName("search texts")
    class Texts {

        @Test
        @DisplayName("names and medication become lower-cased, escaped, %...% patterns; the MRN stays exact")
        void buildsThePatterns() {
            OrderListQuery query = parse(OrderListParams.builder()
                    .patientName("  O_Neil ").medication("Gamunex-C").providerName("DR. 100%").mrn(" 123456 "));

            assertThat(query.patientNamePattern()).isEqualTo("%o\\_neil%");
            assertThat(query.medicationPattern()).isEqualTo("%gamunex-c%");
            assertThat(query.providerNamePattern()).isEqualTo("%dr. 100\\%%");
            assertThat(query.mrn()).isEqualTo("123456");
        }

        @Test
        @DisplayName("blank texts mean no filter")
        void treatsBlankAsNoFilter() {
            OrderListQuery query = parse(OrderListParams.builder()
                    .patientName("").medication("   ").providerName("\t").mrn(" "));

            assertThat(query.patientNamePattern()).isNull();
            assertThat(query.medicationPattern()).isNull();
            assertThat(query.providerNamePattern()).isNull();
            assertThat(query.mrn()).isNull();
        }

        @Test
        @DisplayName("100 characters are accepted, 101 are rejected without repeating the text")
        void limitsTheLength() {
            String atLimit = "a".repeat(100);
            String tooLong = "SECRET-NAME-" + "b".repeat(89);

            assertThat(parse(OrderListParams.builder().patientName(atLimit)).patientNamePattern())
                    .isEqualTo("%" + atLimit + "%");

            ValidationError patient = rejected(OrderListParams.builder().patientName(tooLong));
            ValidationError mrn = rejected(OrderListParams.builder().mrn(tooLong));
            ValidationError medication = rejected(OrderListParams.builder().medication(tooLong));
            ValidationError provider = rejected(OrderListParams.builder().providerName(tooLong));

            assertThat(patient.getCode()).isEqualTo("INVALID_SEARCH_TEXT");
            assertThat(patient.getMessage()).isEqualTo("patient_name must be at most 100 characters");
            assertThat(mrn.getMessage()).isEqualTo("mrn must be at most 100 characters");
            assertThat(medication.getMessage()).isEqualTo("medication must be at most 100 characters");
            assertThat(provider.getMessage()).isEqualTo("provider_name must be at most 100 characters");
            assertThat(patient.getMessage()).doesNotContain("SECRET");
        }
    }

    @Nested
    @DisplayName("sort")
    class Sorting {

        private OrderListParams.OrderListParamsBuilder sorted(String... values) {
            return OrderListParams.builder().sort(Arrays.asList(values));
        }

        @Test
        @DisplayName("a field without a direction is ascending; the id is appended as the last, newest-first key")
        void defaultsToAscendingAndAppendsTheTieBreaker() {
            assertThat(parse(sorted("medication")).sort())
                    .containsExactly(key(OrderSortField.MEDICATION, true), key(OrderSortField.ID, false));
        }

        @Test
        @DisplayName("several sort values keep their order; the first has the highest priority")
        void keepsTheOrderOfSeveralKeys() {
            assertThat(parse(sorted("status,asc", "created_at,desc", "patient_name")).sort()).containsExactly(
                    key(OrderSortField.STATUS, true),
                    key(OrderSortField.CREATED_AT, false),
                    key(OrderSortField.PATIENT_NAME, true),
                    key(OrderSortField.ID, false));
        }

        @Test
        @DisplayName("sorting by id itself does not get a second id key")
        void doesNotRepeatTheId() {
            assertThat(parse(sorted("id,asc")).sort()).containsExactly(key(OrderSortField.ID, true));
            assertThat(parse(sorted("status", "id,desc")).sort())
                    .containsExactly(key(OrderSortField.STATUS, true), key(OrderSortField.ID, false));
        }

        @Test
        @DisplayName("field and direction are case-insensitive and may have spaces around them")
        void ignoresCaseAndSpaces() {
            assertThat(parse(sorted(" Created_At , DESC ")).sort())
                    .containsExactly(key(OrderSortField.CREATED_AT, false), key(OrderSortField.ID, false));
        }

        @ParameterizedTest(name = "{0} is on the whitelist")
        @ValueSource(strings = {"id", "created_at", "medication", "patient_name", "provider_name", "status"})
        void acceptsEveryWhitelistedField(String field) {
            assertThat(parse(sorted(field + ",desc")).sort().get(0).field().parameterName()).isEqualTo(field);
        }

        @ParameterizedTest(name = "\"{0}\" -> INVALID_SORT")
        @ValueSource(strings = {
                "mrn", "patientName", "createdAt", "password", "provider_npi", "care_plan.content",
                "created_at;drop table orders", "created_at desc", "", " ", ",", ",desc", "id,",
                "id,up", "id,ascending", "id,asc,desc", "id,desc,"})
        void rejectsAnythingElse(String value) {
            ValidationError error = rejected(sorted(value));

            assertThat(error.getCode()).isEqualTo("INVALID_SORT");
            assertThat(error.getHttpStatus().value()).isEqualTo(400);
            assertThat(error.getMessage()).endsWith(
                    "field must be one of: id, created_at, medication, patient_name, provider_name, status");
        }

        @Test
        @DisplayName("a null element is rejected")
        void rejectsANullElement() {
            assertThat(rejected(OrderListParams.builder().sort(Arrays.asList("id", null))).getCode())
                    .isEqualTo("INVALID_SORT");
        }

        @Test
        @DisplayName("the same field twice is rejected, even with different directions")
        void rejectsADuplicateField() {
            assertThat(rejected(sorted("status,asc", "status,desc")).getCode()).isEqualTo("INVALID_SORT");
            assertThat(rejected(sorted("id", "ID,desc")).getCode()).isEqualTo("INVALID_SORT");
        }

        @Test
        @DisplayName("the message never repeats the rejected value")
        void doesNotEchoTheValue() {
            ValidationError error = rejected(sorted("jane_doe_123456,asc"));

            assertThat(error.getMessage()).doesNotContain("jane_doe").doesNotContain("123456");
        }

        @Test
        @DisplayName("an empty or missing sort list means the default order")
        void treatsAnEmptyListAsTheDefault() {
            List<OrderSortKey> expected =
                    List.of(key(OrderSortField.CREATED_AT, false), key(OrderSortField.ID, false));

            assertThat(parse(OrderListParams.builder().sort(List.of())).sort()).isEqualTo(expected);
            assertThat(parse(OrderListParams.builder().sort(null)).sort()).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("combined parameters")
    class Combined {

        @Test
        @DisplayName("every filter at once is carried into the query")
        void carriesEveryFilter() {
            OrderListQuery query = parse(OrderListParams.builder()
                    .page(2).pageSize(10).status("pending").patientId(4L).providerId(5L)
                    .patientName("wong").mrn("123456").medication("ivig").providerName("green")
                    .createdFrom(LocalDate.of(2026, 11, 1)).createdTo(LocalDate.of(2026, 11, 30))
                    .sort(List.of("patient_name,asc")));

            assertThat(query.page()).isEqualTo(2);
            assertThat(query.pageSize()).isEqualTo(10);
            assertThat(query.status()).isEqualTo(CarePlan.Status.PENDING);
            assertThat(query.patientId()).isEqualTo(4L);
            assertThat(query.providerId()).isEqualTo(5L);
            assertThat(query.patientNamePattern()).isEqualTo("%wong%");
            assertThat(query.mrn()).isEqualTo("123456");
            assertThat(query.medicationPattern()).isEqualTo("%ivig%");
            assertThat(query.providerNamePattern()).isEqualTo("%green%");
            assertThat(query.createdFrom()).isEqualTo(LocalDateTime.of(2026, 11, 1, 0, 0));
            assertThat(query.createdBefore()).isEqualTo(LocalDateTime.of(2026, 12, 1, 0, 0));
            assertThat(query.sort()).containsExactly(
                    key(OrderSortField.PATIENT_NAME, true), key(OrderSortField.ID, false));
        }
    }
}

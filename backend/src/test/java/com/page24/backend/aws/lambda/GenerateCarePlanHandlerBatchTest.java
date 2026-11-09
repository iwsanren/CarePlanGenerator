package com.page24.backend.aws.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.page24.backend.entity.CarePlan;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.service.CarePlanGenerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The SQS handler's batch rules that {@link GenerateCarePlanHandlerTest} does not reach: which messages are reported
 * back to SQS as failures (so SQS redelivers only those), why a message is rejected, and which statuses are processed.
 *
 * <p>The repository and the generation service are mocks, so these tests check what the handler itself decides.
 * The reason a message was rejected is not in the response, only in the exception raised when the message has no
 * id; the tests use that to check each reason.
 */
@ExtendWith(MockitoExtension.class)
class GenerateCarePlanHandlerBatchTest {

    @Mock
    private CarePlanGenerationService carePlanGenerationService;

    @Mock
    private CarePlanRepository carePlanRepository;

    private GenerateCarePlanHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GenerateCarePlanHandler(
                new ObjectMapper().findAndRegisterModules(),
                carePlanGenerationService,
                carePlanRepository
        );
    }

    static Stream<Arguments> rejectedBodies() {
        return Stream.of(
                Arguments.of(null, "SQS message body is required"),
                Arguments.of("", "SQS message body is required"),
                Arguments.of("   ", "SQS message body is required"),
                Arguments.of("not-json", "SQS message body must be valid JSON"),
                Arguments.of("{\"carePlanId\":", "SQS message body must be valid JSON"),
                Arguments.of("{}", "SQS message body must contain carePlanId"),
                Arguments.of("{\"carePlanId\":null}", "SQS message body must contain carePlanId"),
                Arguments.of("null", "SQS message body must contain carePlanId"),
                Arguments.of("[42]", "SQS message body must contain carePlanId"),
                Arguments.of("{\"carePlanId\":\"abc\"}", "carePlanId must be a number"),
                Arguments.of("{\"carePlanId\":\"\"}", "carePlanId must be a number"),
                Arguments.of("{\"carePlanId\":4.5}", "carePlanId must be a number"),
                Arguments.of("{\"carePlanId\":true}", "carePlanId must be a number"),
                Arguments.of("{\"carePlanId\":[42]}", "carePlanId must be a number"),
                Arguments.of("{\"carePlanId\":{\"id\":42}}", "carePlanId must be a number"),
                Arguments.of("{\"carePlanId\":0}", "carePlanId must be positive"),
                Arguments.of("{\"carePlanId\":-5}", "carePlanId must be positive"),
                Arguments.of("{\"carePlanId\":\"0\"}", "carePlanId must be positive")
        );
    }

    @ParameterizedTest(name = "[{index}] body {0}")
    @MethodSource("rejectedBodies")
    @DisplayName("A message whose body is unusable is reported as a failure and nothing is read or generated")
    void reportsAnUnusableBodyAsAFailure(String body) {
        Map<String, Object> response = handler.handleRequest(event(record("msg-1", body)));

        assertThat(batchItemFailures(response)).containsExactly(Map.of("itemIdentifier", "msg-1"));
        verifyNoInteractions(carePlanRepository, carePlanGenerationService);
    }

    @ParameterizedTest(name = "[{index}] body {0} -> {1}")
    @MethodSource("rejectedBodies")
    @DisplayName("Without a message id the reason of the rejection is raised instead of reported")
    void raisesTheReasonWhenTheMessageHasNoId(String body, String reason) {
        Map<String, Object> event = event(record(null, body));

        assertThatThrownBy(() -> handler.handleRequest(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SQS record is missing messageId")
                .satisfies(ex -> assertThat(ex.getCause()).hasMessage(reason));
        verifyNoInteractions(carePlanRepository, carePlanGenerationService);
    }

    @ParameterizedTest(name = "[{index}] message id \"{0}\"")
    @ValueSource(strings = {"", "   "})
    @DisplayName("A blank message id counts as a missing one")
    void treatsABlankMessageIdAsMissing(String messageId) {
        Map<String, Object> event = event(record(messageId, "{\"carePlanId\":0}"));

        assertThatThrownBy(() -> handler.handleRequest(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SQS record is missing messageId");
    }

    @Test
    @DisplayName("A message without an id is still processed when nothing goes wrong")
    void processesAMessageWithoutAnIdWhenItSucceeds() {
        CarePlan pending = carePlan(42L, CarePlan.Status.PENDING);
        CarePlan completed = carePlan(42L, CarePlan.Status.COMPLETED);
        when(carePlanRepository.findById(42L)).thenReturn(Optional.of(pending), Optional.of(completed));

        Map<String, Object> response = handler.handleRequest(event(record(null, "{\"carePlanId\":42}")));

        assertThat(batchItemFailures(response)).isEmpty();
        verify(carePlanGenerationService).generateWithRetry(42L);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(value = CarePlan.Status.class, names = {"PENDING", "PROCESSING", "FAILED"})
    @DisplayName("Every status except COMPLETED is processed: marked PROCESSING and saved, then generated")
    void processesEveryStatusExceptCompleted(CarePlan.Status status) {
        CarePlan before = carePlan(42L, status);
        CarePlan after = carePlan(42L, CarePlan.Status.COMPLETED);
        when(carePlanRepository.findById(42L)).thenReturn(Optional.of(before), Optional.of(after));

        Map<String, Object> response = handler.handleRequest(event(record("msg-1", "{\"carePlanId\":42}")));

        assertThat(batchItemFailures(response)).isEmpty();
        assertThat(before.getStatus()).isEqualTo(CarePlan.Status.PROCESSING);
        InOrder order = inOrder(carePlanRepository, carePlanGenerationService);
        order.verify(carePlanRepository).save(before);
        order.verify(carePlanGenerationService).generateWithRetry(42L);
    }

    @Test
    @DisplayName("An id beyond the int range is read as a long")
    void readsAnIdBeyondTheIntRange() {
        long id = 3_000_000_000L;
        CarePlan pending = carePlan(id, CarePlan.Status.PENDING);
        CarePlan completed = carePlan(id, CarePlan.Status.COMPLETED);
        when(carePlanRepository.findById(id)).thenReturn(Optional.of(pending), Optional.of(completed));

        Map<String, Object> response = handler.handleRequest(event(record("msg-1", "{\"carePlanId\":3000000000}")));

        assertThat(batchItemFailures(response)).isEmpty();
        verify(carePlanGenerationService).generateWithRetry(id);
    }

    @Test
    @DisplayName("A care plan that does not exist is reported as a failure and not generated")
    void reportsAnUnknownCarePlan() {
        when(carePlanRepository.findById(42L)).thenReturn(Optional.empty());

        Map<String, Object> response = handler.handleRequest(event(record("msg-1", "{\"carePlanId\":42}")));

        assertThat(batchItemFailures(response)).containsExactly(Map.of("itemIdentifier", "msg-1"));
        verify(carePlanRepository, never()).save(any());
        verifyNoInteractions(carePlanGenerationService);
    }

    @Test
    @DisplayName("A care plan that does not exist and a message without an id raises the not-found reason")
    void raisesNotFoundWhenTheMessageHasNoId() {
        when(carePlanRepository.findById(42L)).thenReturn(Optional.empty());
        Map<String, Object> event = event(record(null, "{\"carePlanId\":42}"));

        assertThatThrownBy(() -> handler.handleRequest(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SQS record is missing messageId")
                .satisfies(ex -> assertThat(ex.getCause()).hasMessage("CarePlan not found: 42"));
    }

    @Test
    @DisplayName("A generation that throws is reported as a failure, after the care plan was marked PROCESSING")
    void reportsAGenerationThatThrows() {
        CarePlan pending = carePlan(42L, CarePlan.Status.PENDING);
        when(carePlanRepository.findById(42L)).thenReturn(Optional.of(pending));
        doThrow(new IllegalStateException("provider down")).when(carePlanGenerationService).generateWithRetry(42L);

        Map<String, Object> response = handler.handleRequest(event(record("msg-1", "{\"carePlanId\":42}")));

        assertThat(batchItemFailures(response)).containsExactly(Map.of("itemIdentifier", "msg-1"));
        verify(carePlanRepository).save(pending);
        assertThat(pending.getStatus()).isEqualTo(CarePlan.Status.PROCESSING);
    }

    @Test
    @DisplayName("A care plan that ends FAILED after the retries is reported as a failure")
    void reportsACarePlanThatEndsFailed() {
        CarePlan pending = carePlan(42L, CarePlan.Status.PENDING);
        CarePlan failed = carePlan(42L, CarePlan.Status.FAILED);
        when(carePlanRepository.findById(42L)).thenReturn(Optional.of(pending), Optional.of(failed));

        Map<String, Object> response = handler.handleRequest(event(record("msg-1", "{\"carePlanId\":42}")));

        assertThat(batchItemFailures(response)).containsExactly(Map.of("itemIdentifier", "msg-1"));
        verify(carePlanGenerationService).generateWithRetry(42L);
    }

    @Test
    @DisplayName("The reason for a FAILED care plan is raised when the message has no id")
    void raisesTheFailedReasonWhenTheMessageHasNoId() {
        CarePlan pending = carePlan(42L, CarePlan.Status.PENDING);
        CarePlan failed = carePlan(42L, CarePlan.Status.FAILED);
        when(carePlanRepository.findById(42L)).thenReturn(Optional.of(pending), Optional.of(failed));
        Map<String, Object> event = event(record(null, "{\"carePlanId\":42}"));

        assertThatThrownBy(() -> handler.handleRequest(event))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(ex -> assertThat(ex.getCause())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("CarePlan generation failed after retries: 42"));
    }

    @Test
    @DisplayName("A care plan that disappears during generation is reported as a failure")
    void reportsACarePlanThatDisappears() {
        CarePlan pending = carePlan(42L, CarePlan.Status.PENDING);
        when(carePlanRepository.findById(42L)).thenReturn(Optional.of(pending), Optional.empty());

        Map<String, Object> response = handler.handleRequest(event(record("msg-1", "{\"carePlanId\":42}")));

        assertThat(batchItemFailures(response)).containsExactly(Map.of("itemIdentifier", "msg-1"));
        verify(carePlanGenerationService).generateWithRetry(42L);
    }

    @Test
    @DisplayName("In a batch only the failing messages are reported and the others are still processed")
    void reportsOnlyTheFailingMessagesOfABatch() {
        CarePlan pending1 = carePlan(1L, CarePlan.Status.PENDING);
        CarePlan done1 = carePlan(1L, CarePlan.Status.COMPLETED);
        CarePlan pending4 = carePlan(4L, CarePlan.Status.PENDING);
        CarePlan done4 = carePlan(4L, CarePlan.Status.COMPLETED);
        when(carePlanRepository.findById(1L)).thenReturn(Optional.of(pending1), Optional.of(done1));
        when(carePlanRepository.findById(3L)).thenReturn(Optional.empty());
        when(carePlanRepository.findById(4L)).thenReturn(Optional.of(pending4), Optional.of(done4));

        Map<String, Object> response = handler.handleRequest(Map.of("Records", List.of(
                record("msg-1", "{\"carePlanId\":1}"),
                record("msg-bad", "not-json"),
                record("msg-3", "{\"carePlanId\":3}"),
                record("msg-4", "{\"carePlanId\":4}"))));

        assertThat(batchItemFailures(response)).containsExactly(
                Map.of("itemIdentifier", "msg-bad"),
                Map.of("itemIdentifier", "msg-3"));
        verify(carePlanGenerationService).generateWithRetry(1L);
        verify(carePlanGenerationService).generateWithRetry(4L);
        verify(carePlanGenerationService, never()).generateWithRetry(3L);
    }

    @Test
    @DisplayName("An empty batch reports no failures and does nothing")
    void acceptsAnEmptyBatch() {
        Map<String, Object> response = handler.handleRequest(Map.of("Records", List.of()));

        assertThat(response).containsOnlyKeys("batchItemFailures");
        assertThat(batchItemFailures(response)).isEmpty();
        verifyNoInteractions(carePlanRepository, carePlanGenerationService);
    }

    @Test
    @DisplayName("An event without a Records list is refused as a whole")
    void refusesAnEventWithoutRecords() {
        Map<String, Object> event = Map.of();

        assertThatThrownBy(() -> handler.handleRequest(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SQS event must contain a Records list");
        verifyNoInteractions(carePlanRepository, carePlanGenerationService);
    }

    @Test
    @DisplayName("An event whose Records is not a list is refused as a whole")
    void refusesRecordsThatAreNotAList() {
        Map<String, Object> event = Map.of("Records", "not a list");

        assertThatThrownBy(() -> handler.handleRequest(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SQS event must contain a Records list");
    }

    @Test
    @DisplayName("A record that is not an object is refused before any record is processed")
    void refusesARecordThatIsNotAnObject() {
        List<Object> records = new ArrayList<>();
        records.add(record("msg-1", "{\"carePlanId\":1}"));
        records.add("not an object");
        Map<String, Object> event = Map.of("Records", records);

        assertThatThrownBy(() -> handler.handleRequest(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Each SQS record must be an object");
        verifyNoInteractions(carePlanRepository, carePlanGenerationService);
    }

    private Map<String, Object> event(Map<String, Object> record) {
        return Map.of("Records", List.of(record));
    }

    /** A record built from a mutable map so that the id and the body can be left out by passing null. */
    private Map<String, Object> record(String messageId, String body) {
        Map<String, Object> record = new HashMap<>();
        if (messageId != null) {
            record.put("messageId", messageId);
        }
        if (body != null) {
            record.put("body", body);
        }
        return record;
    }

    private CarePlan carePlan(Long id, CarePlan.Status status) {
        CarePlan carePlan = new CarePlan();
        carePlan.setId(id);
        carePlan.setStatus(status);
        return carePlan;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> batchItemFailures(Map<String, Object> response) {
        return (List<Map<String, String>>) response.get("batchItemFailures");
    }
}

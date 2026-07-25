package com.page24.backend.aws.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.page24.backend.entity.CarePlan;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.service.CarePlanGenerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerateCarePlanHandlerTest {

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

    @Test
    @DisplayName("SQS handler processes a valid carePlanId message")
    void processesValidCarePlanMessage() {
        CarePlan pending = carePlan(42L, CarePlan.Status.PENDING);
        CarePlan completed = carePlan(42L, CarePlan.Status.COMPLETED);
        when(carePlanRepository.findById(42L)).thenReturn(Optional.of(pending), Optional.of(completed));

        Map<String, Object> response = handler.handleRequest(event(record("msg-1", "{\"carePlanId\":42}")));

        assertThat(response).containsKey("batchItemFailures");
        assertThat(batchItemFailures(response)).isEmpty();
        assertThat(pending.getStatus()).isEqualTo(CarePlan.Status.PROCESSING);
        verify(carePlanRepository).save(pending);
        verify(carePlanGenerationService).generateWithRetry(42L);
    }

    @Test
    @DisplayName("SQS handler reports malformed messages as partial batch failures")
    void reportsMalformedMessageAsBatchFailure() {
        Map<String, Object> response = handler.handleRequest(event(record("msg-bad", "not-json")));

        assertThat(batchItemFailures(response))
                .containsExactly(Map.of("itemIdentifier", "msg-bad"));
        verify(carePlanGenerationService, never()).generateWithRetry(42L);
    }

    @Test
    @DisplayName("SQS handler skips duplicate delivery when care plan is already completed")
    void skipsCompletedCarePlan() {
        CarePlan completed = carePlan(42L, CarePlan.Status.COMPLETED);
        when(carePlanRepository.findById(42L)).thenReturn(Optional.of(completed));

        Map<String, Object> response = handler.handleRequest(event(record("msg-1", "{\"carePlanId\":\"42\"}")));

        assertThat(batchItemFailures(response)).isEmpty();
        verify(carePlanRepository, never()).save(completed);
        verify(carePlanGenerationService, never()).generateWithRetry(42L);
    }

    private Map<String, Object> event(Map<String, Object> record) {
        return Map.of("Records", List.of(record));
    }

    private Map<String, Object> record(String messageId, String body) {
        return Map.of(
                "messageId", messageId,
                "body", body
        );
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

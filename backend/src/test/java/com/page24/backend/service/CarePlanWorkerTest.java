package com.page24.backend.service;

import com.page24.backend.entity.CarePlan;
import com.page24.backend.repository.CarePlanRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the queue worker. The queue, repository and generation service are
 * mocked, so these tests check the worker's own logic only: drain the queue, mark each
 * care plan PROCESSING, then hand it to the generation service.
 */
@ExtendWith(MockitoExtension.class)
class CarePlanWorkerTest {

    @Mock
    private QueueService queueService;

    @Mock
    private CarePlanRepository carePlanRepository;

    @Mock
    private CarePlanGenerationService carePlanGenerationService;

    @InjectMocks
    private CarePlanWorker worker;

    @Test
    @DisplayName("pollQueue processes every queued task until the queue is empty")
    void pollQueueDrainsWholeQueue() {
        // Given: two tasks, then an empty queue (dequeue returns null)
        CarePlan first = carePlan(1L);
        CarePlan second = carePlan(2L);
        when(queueService.dequeue()).thenReturn(1L, 2L, null);
        when(carePlanRepository.findById(1L)).thenReturn(Optional.of(first));
        when(carePlanRepository.findById(2L)).thenReturn(Optional.of(second));

        // When
        worker.pollQueue();

        // Then: each plan is marked PROCESSING and saved BEFORE it is generated, in queue order
        assertThat(first.getStatus()).isEqualTo(CarePlan.Status.PROCESSING);
        assertThat(second.getStatus()).isEqualTo(CarePlan.Status.PROCESSING);
        InOrder inOrder = inOrder(carePlanRepository, carePlanGenerationService);
        inOrder.verify(carePlanRepository).save(first);
        inOrder.verify(carePlanGenerationService).generateWithRetry(1L);
        inOrder.verify(carePlanRepository).save(second);
        inOrder.verify(carePlanGenerationService).generateWithRetry(2L);
    }

    @Test
    @DisplayName("pollQueue does nothing when the queue is empty")
    void pollQueueDoesNothingWhenEmpty() {
        when(queueService.dequeue()).thenReturn(null);

        worker.pollQueue();

        verifyNoInteractions(carePlanRepository, carePlanGenerationService);
    }

    @Test
    @DisplayName("the queued-event trigger drains the queue the same way as the scheduled poll")
    void queuedEventDrainsQueue() {
        // Given
        CarePlan plan = carePlan(5L);
        when(queueService.dequeue()).thenReturn(5L, (Long) null);
        when(carePlanRepository.findById(5L)).thenReturn(Optional.of(plan));

        // When: the event fires (in production, after the enqueuing transaction commits)
        worker.onCarePlanQueued(new CarePlanQueuedEvent(5L));

        // Then
        verify(carePlanGenerationService).generateWithRetry(5L);
        assertThat(plan.getStatus()).isEqualTo(CarePlan.Status.PROCESSING);
    }

    @Test
    @DisplayName("a task whose care plan row no longer exists is skipped for saving, and the worker keeps going")
    void missingCarePlanRowIsNotSaved() {
        // Given: id 9 has no row; id 10 is fine
        CarePlan plan = carePlan(10L);
        when(queueService.dequeue()).thenReturn(9L, 10L, null);
        when(carePlanRepository.findById(9L)).thenReturn(Optional.empty());
        when(carePlanRepository.findById(10L)).thenReturn(Optional.of(plan));

        // When
        worker.pollQueue();

        // Then: only the existing row (10) is saved, and both tasks are still handed to generation
        verify(carePlanRepository, times(1)).save(any(CarePlan.class));
        verify(carePlanRepository).save(plan);
        verify(carePlanGenerationService).generateWithRetry(9L);
        verify(carePlanGenerationService).generateWithRetry(10L);
    }

    private CarePlan carePlan(Long id) {
        CarePlan carePlan = new CarePlan();
        carePlan.setId(id);
        return carePlan;
    }
}

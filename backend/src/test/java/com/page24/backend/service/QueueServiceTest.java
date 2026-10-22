package com.page24.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Redis-backed queue.
 * RedisTemplate and its ListOperations are mocked, so no Redis server is needed:
 * we only check that QueueService issues the right Redis list commands.
 */
@ExtendWith(MockitoExtension.class)
class QueueServiceTest {

    private static final String QUEUE_NAME = "careplan:queue";

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ListOperations<String, Object> listOperations;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private QueueService queueService;

    @BeforeEach
    void setUp() {
        queueService = new QueueService(redisTemplate, eventPublisher);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
    }

    @Test
    @DisplayName("enqueue pushes the id to the tail of the Redis list, then publishes a CarePlanQueuedEvent")
    void enqueuePushesIdThenPublishesEvent() {
        // Given: the list reports size 1 after the push (only used for logging)
        when(listOperations.size(QUEUE_NAME)).thenReturn(1L);

        // When
        queueService.enqueue(42L);

        // Then: the push must happen before the event, so the worker never wakes up to an empty queue
        InOrder inOrder = inOrder(listOperations, eventPublisher);
        inOrder.verify(listOperations).rightPush(QUEUE_NAME, "42");
        inOrder.verify(eventPublisher).publishEvent(new CarePlanQueuedEvent(42L));
    }

    @Test
    @DisplayName("dequeue pops the head of the list and parses it as a Long")
    void dequeueReturnsParsedId() {
        when(listOperations.leftPop(QUEUE_NAME)).thenReturn("42");

        assertThat(queueService.dequeue()).isEqualTo(42L);
    }

    @Test
    @DisplayName("dequeue returns null when the queue is empty")
    void dequeueReturnsNullWhenEmpty() {
        when(listOperations.leftPop(QUEUE_NAME)).thenReturn(null);

        assertThat(queueService.dequeue()).isNull();
    }

    @Test
    @DisplayName("getQueueSize returns the list length")
    void queueSizeReturnsListLength() {
        when(listOperations.size(QUEUE_NAME)).thenReturn(3L);

        assertThat(queueService.getQueueSize()).isEqualTo(3L);
    }

    @Test
    @DisplayName("getQueueSize treats a null answer from Redis as zero")
    void queueSizeTreatsNullAsZero() {
        when(listOperations.size(QUEUE_NAME)).thenReturn(null);

        assertThat(queueService.getQueueSize()).isZero();
    }

    @Test
    @DisplayName("viewQueue returns an empty list without reading the range when the queue is empty")
    void viewQueueIsEmptyWhenNothingQueued() {
        when(listOperations.size(QUEUE_NAME)).thenReturn(0L);

        assertThat(queueService.viewQueue()).isEmpty();
        verify(listOperations, never()).range(QUEUE_NAME, 0, -1);
    }

    @Test
    @DisplayName("viewQueue returns every queued item without removing any")
    void viewQueueReturnsAllItems() {
        when(listOperations.size(QUEUE_NAME)).thenReturn(2L);
        when(listOperations.range(QUEUE_NAME, 0, -1)).thenReturn(List.of("1", "2"));

        assertThat(queueService.viewQueue()).containsExactly("1", "2");
        verify(listOperations, never()).leftPop(QUEUE_NAME);
    }
}

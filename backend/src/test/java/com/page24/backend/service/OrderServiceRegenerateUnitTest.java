package com.page24.backend.service;

import com.page24.backend.dto.OrderMapper;
import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.Order;
import com.page24.backend.entity.Patient;
import com.page24.backend.entity.Provider;
import com.page24.backend.exception.BlockError;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.repository.OrderRepository;
import com.page24.backend.repository.PatientRepository;
import com.page24.backend.repository.ProviderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OrderService#regenerateCarePlan}: a finished plan goes back to PENDING, loses its content, error and
 * generation timing (NULL = "no data" for the next run), and is queued again.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceRegenerateUnitTest {

    private static final long ORDER_ID = 7L;

    @Mock private PatientRepository patientRepository;
    @Mock private ProviderRepository providerRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private CarePlanRepository carePlanRepository;
    @Mock private CarePlanQueue carePlanQueue;

    @Spy private OrderMapper orderMapper = new OrderMapper();

    @InjectMocks private OrderService orderService;

    private Order order;
    private CarePlan carePlan;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        order = new Order();
        order.setId(ORDER_ID);
        order.setPatient(new Patient());
        order.setProvider(new Provider());
        carePlan = new CarePlan();
        carePlan.setId(30L);
        carePlan.setOrder(order);
        carePlan.setStatus(CarePlan.Status.COMPLETED);
        carePlan.setContent("old content");
        carePlan.setErrorMessage("old error");
        carePlan.setStartedAt(LocalDateTime.of(2026, 1, 1, 10, 0, 0));
        carePlan.setFinishedAt(LocalDateTime.of(2026, 1, 1, 10, 0, 9));
        carePlan.setAttemptCount(2);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Regenerate -> PENDING, content/error and all three timing fields reset to NULL, saved and re-queued")
    void resetsTheTimingFieldsAndRequeues() {
        givenOrderAndCarePlanExist();

        orderService.regenerateCarePlan(ORDER_ID);

        assertThat(carePlan.getStatus()).isEqualTo(CarePlan.Status.PENDING);
        assertThat(carePlan.getContent()).isNull();
        assertThat(carePlan.getErrorMessage()).isNull();
        assertThat(carePlan.getStartedAt()).isNull();
        assertThat(carePlan.getFinishedAt()).isNull();
        assertThat(carePlan.getAttemptCount()).isNull();
        verify(carePlanRepository).save(carePlan);
        verify(carePlanQueue).enqueue(30L);
    }

    @Test
    @DisplayName("Regenerate a failed plan -> same reset, and a previous manual upload flag is cleared")
    void resetsAFailedPlanAndTheUploadFlag() {
        carePlan.setStatus(CarePlan.Status.FAILED);
        carePlan.setUploaded(true);
        givenOrderAndCarePlanExist();

        orderService.regenerateCarePlan(ORDER_ID);

        assertThat(carePlan.getStatus()).isEqualTo(CarePlan.Status.PENDING);
        assertThat(carePlan.isUploaded()).isFalse();
        assertThat(carePlan.getAttemptCount()).isNull();
    }

    @Test
    @DisplayName("Generation in progress -> 409 and the running plan's timing is left alone")
    void rejectsWhileProcessingWithoutTouchingTheTiming() {
        carePlan.setStatus(CarePlan.Status.PROCESSING);
        givenOrderAndCarePlanExist();

        Throwable thrown = catchThrowable(() -> orderService.regenerateCarePlan(ORDER_ID));

        assertThat(thrown).isInstanceOf(BlockError.class);
        assertThat(carePlan.getStartedAt()).isNotNull();
        verify(carePlanRepository, never()).save(any(CarePlan.class));
        verify(carePlanQueue, never()).enqueue(any());
    }

    @Test
    @DisplayName("Plan still PENDING -> rejected, timing untouched, nothing queued")
    void rejectsWhilePending() {
        carePlan.setStatus(CarePlan.Status.PENDING);
        givenOrderAndCarePlanExist();

        Throwable thrown = catchThrowable(() -> orderService.regenerateCarePlan(ORDER_ID));

        assertThat(thrown).isInstanceOf(BlockError.class);
        assertThat(carePlan.getFinishedAt()).isNotNull();
        verify(carePlanQueue, never()).enqueue(any());
    }

    private void givenOrderAndCarePlanExist() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(carePlanRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(carePlan));
    }
}

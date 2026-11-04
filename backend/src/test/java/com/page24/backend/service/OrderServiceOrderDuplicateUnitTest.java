package com.page24.backend.service;

import com.page24.backend.dto.CreateOrderRequest;
import com.page24.backend.dto.OrderMapper;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.dto.Warning;
import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.Order;
import com.page24.backend.entity.Patient;
import com.page24.backend.entity.Provider;
import com.page24.backend.exception.BlockError;
import com.page24.backend.exception.WarningException;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.repository.OrderRepository;
import com.page24.backend.repository.PatientRepository;
import com.page24.backend.repository.ProviderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Order-level duplicate detection and the confirmation gate of {@link OrderService#createOrder}.
 * The provider and patient are always resolved (existing NPI and MRN), so only the order checks decide the outcome.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceOrderDuplicateUnitTest {

    private static final LocalDate DOB = LocalDate.of(1990, 5, 10);

    @Mock private PatientRepository patientRepository;
    @Mock private ProviderRepository providerRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private CarePlanRepository carePlanRepository;
    @Mock private CarePlanQueue carePlanQueue;

    @Spy private OrderMapper orderMapper = new OrderMapper();

    @InjectMocks private OrderService orderService;

    private Patient existingPatient;

    @BeforeEach
    void setUp() {
        // Actor.subject() falls back to "system" only when nobody is authenticated.
        SecurityContextHolder.clearContext();

        existingPatient = patient(1L);
        lenient().when(providerRepository.findByNpi("1111111111"))
                .thenReturn(Optional.of(provider("Dr. Green", "1111111111", 100L)));
        lenient().when(patientRepository.findByMrn("123456")).thenReturn(Optional.of(existingPatient));

        // By default there is no earlier order for this patient and medication.
        lenient().when(orderRepository.existsByPatientAndMedicationNameIgnoreCaseAndCreatedAtBetween(
                        any(Patient.class), any(String.class), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(false);
        lenient().when(orderRepository.findFirstByPatientAndMedicationNameIgnoreCaseOrderByCreatedAtDesc(
                        any(Patient.class), any(String.class)))
                .thenReturn(Optional.empty());
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            if (order.getId() == null) {
                order.setId(200L);
            }
            return order;
        });
        lenient().when(carePlanRepository.save(any(CarePlan.class))).thenAnswer(inv -> {
            CarePlan carePlan = inv.getArgument(0);
            if (carePlan.getId() == null) {
                carePlan.setId(300L);
            }
            return carePlan;
        });
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Same patient + same medication + same day -> BlockError 409, nothing saved or queued")
    void blocksSecondOrderForSamePatientAndMedicationOnTheSameDay() {
        when(orderRepository.existsByPatientAndMedicationNameIgnoreCaseAndCreatedAtBetween(
                eq(existingPatient), eq("IVIG"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(true);

        Throwable thrown = catchThrowable(() -> orderService.createOrder(baseRequest()));

        assertThat(thrown).isInstanceOf(BlockError.class);
        BlockError error = (BlockError) thrown;
        assertThat(error.getCode()).isEqualTo("DUPLICATE_ORDER_SAME_DAY");
        assertThat(error.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);

        verify(orderRepository, never()).save(any(Order.class));
        verify(carePlanRepository, never()).save(any(CarePlan.class));
        verify(carePlanQueue, never()).enqueue(any());
        // The hard block must stop before the softer "other day" lookup runs.
        verify(orderRepository, never())
                .findFirstByPatientAndMedicationNameIgnoreCaseOrderByCreatedAtDesc(any(), any());
    }

    @Test
    @DisplayName("The same-day check looks at exactly one calendar day, midnight to midnight")
    void sameDayCheckCoversOneCalendarDay() {
        orderService.createOrder(baseRequest());

        ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderRepository).existsByPatientAndMedicationNameIgnoreCaseAndCreatedAtBetween(
                eq(existingPatient), eq("IVIG"), start.capture(), end.capture());
        assertThat(start.getValue().toLocalTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(end.getValue()).isEqualTo(start.getValue().plusDays(1));
    }

    @Test
    @DisplayName("Same medication on another day + no confirm -> WarningException, nothing saved or queued")
    void asksForConfirmationWhenTheMedicationWasOrderedOnAnotherDay() {
        when(orderRepository.findFirstByPatientAndMedicationNameIgnoreCaseOrderByCreatedAtDesc(
                eq(existingPatient), eq("IVIG")))
                .thenReturn(Optional.of(new Order()));

        Throwable thrown = catchThrowable(() -> orderService.createOrder(baseRequest()));

        assertThat(thrown).isInstanceOf(WarningException.class);
        WarningException error = (WarningException) thrown;
        assertThat(error.getCode()).isEqualTo("CONFIRMATION_REQUIRED");
        List<Warning> warnings = warningsFrom(error);
        assertThat(warnings).extracting(Warning::code).containsExactly("ORDER_CROSS_DAY_DUPLICATE");
        assertThat(warnings).allMatch(Warning::actionRequired);

        verify(orderRepository, never()).save(any(Order.class));
        verify(carePlanRepository, never()).save(any(CarePlan.class));
        verify(carePlanQueue, never()).enqueue(any());
    }

    @Test
    @DisplayName("Same medication on another day + confirm=true -> order saved, flagged as confirmed, care plan queued")
    void createsOrderWhenTheCrossDayDuplicateIsConfirmed() {
        when(orderRepository.findFirstByPatientAndMedicationNameIgnoreCaseOrderByCreatedAtDesc(
                eq(existingPatient), eq("IVIG")))
                .thenReturn(Optional.of(new Order()));
        CreateOrderRequest request = baseRequest();
        request.setConfirm(true);

        OrderResponse response = orderService.createOrder(request);

        assertThat(response.getResultType()).isEqualTo("WARNING");
        assertThat(response.getMessage()).isEqualTo("Order created with warnings");
        assertThat(response.getWarnings()).extracting(Warning::code).containsExactly("ORDER_CROSS_DAY_DUPLICATE");
        assertThat(response.getStatus()).isEqualTo("PENDING");

        ArgumentCaptor<Order> savedOrder = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(savedOrder.capture());
        Order order = savedOrder.getValue();
        assertThat(order.isConfirmedNotDuplicate()).isTrue();
        assertThat(order.getPatient()).isSameAs(existingPatient);
        assertThat(order.getProvider().getNpi()).isEqualTo("1111111111");
        assertThat(order.getMedicationName()).isEqualTo("IVIG");
        assertThat(order.getPrimaryDiagnosis()).isEqualTo("G70.00");
        assertThat(order.getAdditionalDiagnoses()).containsExactly("I10");
        assertThat(order.getMedicationHistory()).containsExactly("Prednisone");
        assertThat(order.getCreatedBySubject()).isEqualTo("system");
        assertThat(order.getUpdatedBySubject()).isEqualTo("system");

        ArgumentCaptor<CarePlan> savedCarePlan = ArgumentCaptor.forClass(CarePlan.class);
        verify(carePlanRepository).save(savedCarePlan.capture());
        assertThat(savedCarePlan.getValue().getStatus()).isEqualTo(CarePlan.Status.PENDING);
        verify(carePlanQueue).enqueue(300L);
    }

    @Test
    @DisplayName("confirm=true but nothing to confirm -> order is not flagged as confirmed-not-duplicate")
    void doesNotFlagTheOrderWhenThereWasNothingToConfirm() {
        CreateOrderRequest request = baseRequest();
        request.setConfirm(true);

        OrderResponse response = orderService.createOrder(request);

        assertThat(response.getResultType()).isEqualTo("SUCCESS");
        assertThat(response.getWarnings()).isNullOrEmpty();
        ArgumentCaptor<Order> savedOrder = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(savedOrder.capture());
        // The flag is an audit trail: it is true only when a person confirmed past a real warning.
        assertThat(savedOrder.getValue().isConfirmedNotDuplicate()).isFalse();
        verify(carePlanQueue).enqueue(300L);
    }

    @Test
    @DisplayName("Only an informational warning -> order created without confirm, flag stays false")
    void createsOrderWithoutConfirmationWhenOnlyInformationalWarningsExist() {
        CreateOrderRequest request = baseRequest();
        request.setProviderNpi("2222222222");
        request.setProviderName("Dr. Beth Green");
        when(providerRepository.findByNpi("2222222222")).thenReturn(Optional.empty());
        when(providerRepository.findByNameContainingIgnoreCase("Green"))
                .thenReturn(List.of(provider("Dr. Alan Green", "1111111111", 100L)));
        when(providerRepository.save(any(Provider.class)))
                .thenReturn(provider("Dr. Beth Green", "2222222222", 101L));

        OrderResponse response = orderService.createOrder(request);

        assertThat(response.getResultType()).isEqualTo("WARNING");
        assertThat(response.getWarnings()).extracting(Warning::code).containsExactly("PROVIDER_SIMILAR_NAME");
        assertThat(response.getWarnings()).noneMatch(Warning::actionRequired);
        assertThat(response.getProviderId()).isEqualTo(101L);

        ArgumentCaptor<Order> savedOrder = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(savedOrder.capture());
        assertThat(savedOrder.getValue().isConfirmedNotDuplicate()).isFalse();
        verify(providerRepository).save(any(Provider.class));
        verify(carePlanQueue).enqueue(300L);
    }

    // ----- helpers -----

    @SuppressWarnings("unchecked")
    private static List<Warning> warningsFrom(WarningException ex) {
        Map<String, Object> detail = (Map<String, Object>) ex.getDetail();
        assertThat(detail).containsEntry("requiresConfirm", true);
        return (List<Warning>) detail.get("warnings");
    }

    private CreateOrderRequest baseRequest() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setPatientFirstName("Alice");
        request.setPatientLastName("Wong");
        request.setPatientMrn("123456");
        request.setPatientDateOfBirth(DOB);
        request.setProviderName("Dr. Green");
        request.setProviderNpi("1111111111");
        request.setMedicationName("IVIG");
        request.setPrimaryDiagnosis("G70.00");
        request.setAdditionalDiagnoses(List.of("I10"));
        request.setMedicationHistory(List.of("Prednisone"));
        request.setPatientRecords("Unit test patient records");
        request.setConfirm(false);
        return request;
    }

    private static Patient patient(Long id) {
        Patient patient = new Patient();
        patient.setId(id);
        patient.setFirstName("Alice");
        patient.setLastName("Wong");
        patient.setMrn("123456");
        patient.setDateOfBirth(DOB);
        return patient;
    }

    private static Provider provider(String name, String npi, Long id) {
        Provider provider = new Provider();
        provider.setId(id);
        provider.setName(name);
        provider.setNpi(npi);
        return provider;
    }
}

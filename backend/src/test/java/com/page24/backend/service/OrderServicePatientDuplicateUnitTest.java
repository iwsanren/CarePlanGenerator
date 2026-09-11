package com.page24.backend.service;

import com.page24.backend.dto.CreateOrderRequest;
import com.page24.backend.dto.OrderMapper;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.dto.Warning;
import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.Order;
import com.page24.backend.entity.Patient;
import com.page24.backend.entity.Provider;
import com.page24.backend.exception.WarningException;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.repository.OrderRepository;
import com.page24.backend.repository.PatientRepository;
import com.page24.backend.repository.ProviderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServicePatientDuplicateUnitTest {

    @Mock private PatientRepository patientRepository;
    @Mock private ProviderRepository providerRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private CarePlanRepository carePlanRepository;
    @Mock private QueueService queueService;

    @Spy private OrderMapper orderMapper = new OrderMapper();

    @InjectMocks private OrderService orderService;

    @BeforeEach
    void setUpCommonMocks() {
        // Every test resolves the provider by NPI with a matching name,
        // so the provider similar-name path is never exercised in this class.
        when(providerRepository.findByNpi("1111111111"))
                .thenReturn(Optional.of(provider("Dr. Green", "1111111111", 100L)));

        // These stubs are only reached on the "order actually gets created" paths.
        // Mark them lenient so the "stopped at the confirmation gate" tests are not
        // failed by MockitoExtension's strict unnecessary-stubbing check.
        lenient().when(orderRepository.existsByPatientAndMedicationNameIgnoreCaseAndCreatedAtBetween(
                        any(Patient.class), any(String.class), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(false);
        lenient().when(orderRepository.findFirstByPatientAndMedicationNameIgnoreCaseOrderByCreatedAtDesc(
                        any(Patient.class), any(String.class)))
                .thenReturn(Optional.empty());
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            if (order.getId() == null) order.setId(200L);
            return order;
        });
        lenient().when(carePlanRepository.save(any(CarePlan.class))).thenAnswer(inv -> {
            CarePlan carePlan = inv.getArgument(0);
            if (carePlan.getId() == null) carePlan.setId(300L);
            if (carePlan.getStatus() == null) carePlan.setStatus(CarePlan.Status.PENDING);
            return carePlan;
        });
    }

    @Test
    @DisplayName("MRN matches + name & DOB match -> reuse patient, no warning, order created")
    void reusesPatientWhenMrnAndIdentityMatch() {
        CreateOrderRequest request = baseRequest();
        when(patientRepository.findByMrn("123456"))
                .thenReturn(Optional.of(patient("Alice", "Wong", "123456", LocalDate.of(1990, 5, 10), 1L)));

        OrderResponse response = orderService.createOrder(request);

        assertThat(response.getResultType()).isEqualTo("SUCCESS");
        assertThat(response.getWarnings()).isNullOrEmpty();
        verify(patientRepository, never()).save(any(Patient.class));
        verify(queueService).enqueue(300L);
    }

    @Test
    @DisplayName("MRN matches + name differs + no confirm -> WarningException, nothing persisted")
    void requiresConfirmationWhenMrnMatchesButIdentityDiffers() {
        CreateOrderRequest request = baseRequest();
        request.setPatientFirstName("Alicia");
        when(patientRepository.findByMrn("123456"))
                .thenReturn(Optional.of(patient("Alice", "Wong", "123456", LocalDate.of(1990, 5, 10), 1L)));

        Throwable thrown = catchThrowable(() -> orderService.createOrder(request));

        assertThat(thrown).isInstanceOf(WarningException.class);
        WarningException ex = (WarningException) thrown;
        assertThat(ex.getCode()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(warningsFrom(ex)).extracting(Warning::code).contains("PATIENT_MRN_CONFLICT");
        assertThat(warningsFrom(ex)).anyMatch(Warning::actionRequired);

        verify(patientRepository, never()).save(any(Patient.class));
        verify(orderRepository, never()).save(any(Order.class));
        verify(queueService, never()).enqueue(any());
    }

    @Test
    @DisplayName("MRN conflict + confirm=true -> order created, confirmedNotDuplicate = true")
    void createsOrderWhenMrnConflictIsConfirmed() {
        CreateOrderRequest request = baseRequest();
        request.setPatientFirstName("Alicia");
        request.setConfirm(true);
        when(patientRepository.findByMrn("123456"))
                .thenReturn(Optional.of(patient("Alice", "Wong", "123456", LocalDate.of(1990, 5, 10), 1L)));

        OrderResponse response = orderService.createOrder(request);

        assertThat(response.getResultType()).isEqualTo("WARNING");
        assertThat(response.getWarnings()).extracting(Warning::code).contains("PATIENT_MRN_CONFLICT");

        ArgumentCaptor<Order> savedOrder = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(savedOrder.capture());
        assertThat(savedOrder.getValue().isConfirmedNotDuplicate()).isTrue();
        verify(patientRepository, never()).save(any(Patient.class));
    }

    @Test
    @DisplayName("New MRN + same name & DOB as another patient + no confirm -> WarningException, no new patient")
    void requiresConfirmationWhenSameNameDobDifferentMrn() {
        CreateOrderRequest request = baseRequest();
        request.setPatientMrn("654321");
        when(patientRepository.findByMrn("654321")).thenReturn(Optional.empty());
        when(patientRepository.findFirstByFirstNameIgnoreCaseAndLastNameIgnoreCaseAndDateOfBirth(
                "Alice", "Wong", LocalDate.of(1990, 5, 10)))
                .thenReturn(Optional.of(patient("Alice", "Wong", "123456", LocalDate.of(1990, 5, 10), 9L)));

        Throwable thrown = catchThrowable(() -> orderService.createOrder(request));

        assertThat(thrown).isInstanceOf(WarningException.class);
        assertThat(warningsFrom((WarningException) thrown))
                .extracting(Warning::code).contains("PATIENT_POSSIBLE_DUPLICATE");
        verify(patientRepository, never()).save(any(Patient.class));
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("Possible-duplicate conflict + confirm=true -> new patient + order created")
    void createsNewPatientWhenPossibleDuplicateIsConfirmed() {
        CreateOrderRequest request = baseRequest();
        request.setPatientMrn("654321");
        request.setConfirm(true);
        when(patientRepository.findByMrn("654321")).thenReturn(Optional.empty());
        when(patientRepository.findFirstByFirstNameIgnoreCaseAndLastNameIgnoreCaseAndDateOfBirth(
                "Alice", "Wong", LocalDate.of(1990, 5, 10)))
                .thenReturn(Optional.of(patient("Alice", "Wong", "123456", LocalDate.of(1990, 5, 10), 9L)));
        when(patientRepository.save(any(Patient.class)))
                .thenReturn(patient("Alice", "Wong", "654321", LocalDate.of(1990, 5, 10), 10L));

        OrderResponse response = orderService.createOrder(request);

        assertThat(response.getPatientId()).isEqualTo(10L);
        verify(patientRepository).save(any(Patient.class));
        verify(queueService).enqueue(300L);
    }

    @Test
    @DisplayName("New MRN + same name only (no DOB match) -> non-blocking warning, order created")
    void warnsButCreatesWhenOnlyNameMatchesDifferentMrn() {
        CreateOrderRequest request = baseRequest();
        request.setPatientMrn("654321");
        when(patientRepository.findByMrn("654321")).thenReturn(Optional.empty());
        when(patientRepository.findFirstByFirstNameIgnoreCaseAndLastNameIgnoreCaseAndDateOfBirth(
                any(), any(), any())).thenReturn(Optional.empty());
        when(patientRepository.findByFirstNameIgnoreCaseAndLastNameIgnoreCase("Alice", "Wong"))
                .thenReturn(List.of(patient("Alice", "Wong", "123456", LocalDate.of(1980, 1, 1), 9L)));
        when(patientRepository.save(any(Patient.class)))
                .thenReturn(patient("Alice", "Wong", "654321", LocalDate.of(1990, 5, 10), 11L));

        OrderResponse response = orderService.createOrder(request);

        assertThat(response.getResultType()).isEqualTo("WARNING");
        assertThat(response.getWarnings()).extracting(Warning::code).contains("PATIENT_SIMILAR_NAME");
        assertThat(response.getWarnings()).noneMatch(Warning::actionRequired);
        verify(patientRepository).save(any(Patient.class));
        verify(queueService).enqueue(300L);
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
        request.setPatientDateOfBirth(LocalDate.of(1990, 5, 10));
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

    private Patient patient(String firstName, String lastName, String mrn, LocalDate dob, Long id) {
        Patient patient = new Patient();
        patient.setId(id);
        patient.setFirstName(firstName);
        patient.setLastName(lastName);
        patient.setMrn(mrn);
        patient.setDateOfBirth(dob);
        return patient;
    }

    private Provider provider(String name, String npi, Long id) {
        Provider provider = new Provider();
        provider.setId(id);
        provider.setName(name);
        provider.setNpi(npi);
        return provider;
    }
}
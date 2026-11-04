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

/**
 * How {@link OrderService#createOrder} decides that a request's patient is "the same person" as the patient
 * already stored under that MRN: first name, last name and date of birth are compared one by one.
 */
@ExtendWith(MockitoExtension.class)
class OrderServicePatientIdentityUnitTest {

    private static final LocalDate DOB = LocalDate.of(1990, 5, 10);

    @Mock private PatientRepository patientRepository;
    @Mock private ProviderRepository providerRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private CarePlanRepository carePlanRepository;
    @Mock private CarePlanQueue carePlanQueue;

    @Spy private OrderMapper orderMapper = new OrderMapper();

    @InjectMocks private OrderService orderService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        when(providerRepository.findByNpi("1111111111"))
                .thenReturn(Optional.of(provider("Dr. Green", "1111111111", 100L)));

        // Only reached when the identity check lets the order through.
        lenient().when(orderRepository.existsByPatientAndMedicationNameIgnoreCaseAndCreatedAtBetween(
                        any(Patient.class), any(String.class), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(false);
        lenient().when(orderRepository.findFirstByPatientAndMedicationNameIgnoreCaseOrderByCreatedAtDesc(
                        any(Patient.class), any(String.class)))
                .thenReturn(Optional.empty());
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            order.setId(200L);
            return order;
        });
        lenient().when(carePlanRepository.save(any(CarePlan.class))).thenAnswer(inv -> {
            CarePlan carePlan = inv.getArgument(0);
            carePlan.setId(300L);
            return carePlan;
        });
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("MRN matches, names equal, DOB differs -> MRN conflict needing confirmation, nothing saved")
    void dateOfBirthMismatchIsAConflict() {
        storedPatient("Alice", "Wong", DOB);
        CreateOrderRequest request = baseRequest();
        request.setPatientDateOfBirth(DOB.plusDays(1));

        assertMrnConflictStopsBeforeSaving(request);
    }

    @Test
    @DisplayName("MRN matches, first name equal, last name differs -> MRN conflict needing confirmation")
    void lastNameMismatchAloneIsAConflict() {
        storedPatient("Alice", "Wong", DOB);
        CreateOrderRequest request = baseRequest();
        request.setPatientLastName("Chan");

        assertMrnConflictStopsBeforeSaving(request);
    }

    @Test
    @DisplayName("MRN matches, stored DOB missing but the request has one -> MRN conflict needing confirmation")
    void missingStoredDateOfBirthCannotConfirmTheRequestedOne() {
        storedPatient("Alice", "Wong", null);

        assertMrnConflictStopsBeforeSaving(baseRequest());
    }

    @Test
    @DisplayName("MRN matches, names equal, request has no DOB -> treated as the same patient, no warning")
    void requestWithoutDateOfBirthMatchesTheStoredPatient() {
        Patient stored = storedPatient("Alice", "Wong", DOB);
        CreateOrderRequest request = baseRequest();
        request.setPatientDateOfBirth(null);

        OrderResponse response = orderService.createOrder(request);

        assertThat(response.getResultType()).isEqualTo("SUCCESS");
        assertThat(response.getWarnings()).isNullOrEmpty();
        assertThat(response.getPatientId()).isEqualTo(stored.getId());
        verify(patientRepository, never()).save(any(Patient.class));
        verify(carePlanQueue).enqueue(300L);
    }

    @Test
    @DisplayName("Names that differ only by case and surrounding spaces still identify the same patient")
    void nameComparisonIgnoresCaseAndSurroundingSpaces() {
        Patient stored = storedPatient("Alice", "Wong", DOB);
        CreateOrderRequest request = baseRequest();
        request.setPatientFirstName("  ALICE ");
        request.setPatientLastName("wong  ");

        OrderResponse response = orderService.createOrder(request);

        assertThat(response.getResultType()).isEqualTo("SUCCESS");
        assertThat(response.getWarnings()).isNullOrEmpty();
        assertThat(response.getPatientId()).isEqualTo(stored.getId());
        verify(patientRepository, never()).save(any(Patient.class));
    }

    // ----- helpers -----

    private void assertMrnConflictStopsBeforeSaving(CreateOrderRequest request) {
        Throwable thrown = catchThrowable(() -> orderService.createOrder(request));

        assertThat(thrown).isInstanceOf(WarningException.class);
        WarningException error = (WarningException) thrown;
        assertThat(error.getCode()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(warningsFrom(error)).extracting(Warning::code).containsExactly("PATIENT_MRN_CONFLICT");
        assertThat(warningsFrom(error)).allMatch(Warning::actionRequired);

        verify(patientRepository, never()).save(any(Patient.class));
        verify(orderRepository, never()).save(any(Order.class));
        verify(carePlanRepository, never()).save(any(CarePlan.class));
        verify(carePlanQueue, never()).enqueue(any());
    }

    private Patient storedPatient(String firstName, String lastName, LocalDate dob) {
        Patient patient = new Patient();
        patient.setId(1L);
        patient.setFirstName(firstName);
        patient.setLastName(lastName);
        patient.setMrn("123456");
        patient.setDateOfBirth(dob);
        when(patientRepository.findByMrn("123456")).thenReturn(Optional.of(patient));
        return patient;
    }

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

    private static Provider provider(String name, String npi, Long id) {
        Provider provider = new Provider();
        provider.setId(id);
        provider.setName(name);
        provider.setNpi(npi);
        return provider;
    }
}

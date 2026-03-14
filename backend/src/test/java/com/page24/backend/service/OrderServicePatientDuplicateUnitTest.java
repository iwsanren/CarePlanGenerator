package com.page24.backend.service;

import com.page24.backend.dto.CreateOrderRequest;
import com.page24.backend.dto.OrderMapper;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.Order;
import com.page24.backend.entity.Patient;
import com.page24.backend.entity.Provider;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.repository.OrderRepository;
import com.page24.backend.repository.PatientRepository;
import com.page24.backend.repository.ProviderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServicePatientDuplicateUnitTest {

    @Mock
    private PatientRepository patientRepository;

    @Mock
    private ProviderRepository providerRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CarePlanRepository carePlanRepository;

    @Mock
    private QueueService queueService;

    @Spy
    private OrderMapper orderMapper = new OrderMapper();

    @InjectMocks
    private OrderService orderService;

    private Provider existingProvider;

    @BeforeEach
    void setUpCommonMocks() {
        existingProvider = provider("Dr. Green", "1111111111", 100L);

        when(providerRepository.findByNpi("1111111111")).thenReturn(Optional.of(existingProvider));

        when(orderRepository.existsByPatientAndMedicationNameIgnoreCaseAndCreatedAtBetween(
                any(Patient.class),
                any(String.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(false);

        when(orderRepository.findFirstByPatientAndMedicationNameIgnoreCaseOrderByCreatedAtDesc(
                any(Patient.class),
                any(String.class)
        )).thenReturn(Optional.empty());

        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            if (order.getId() == null) {
                order.setId(200L);
            }
            return order;
        });

        when(carePlanRepository.save(any(CarePlan.class))).thenAnswer(invocation -> {
            CarePlan carePlan = invocation.getArgument(0);
            if (carePlan.getId() == null) {
                carePlan.setId(300L);
            }
            if (carePlan.getStatus() == null) {
                carePlan.setStatus(CarePlan.Status.PENDING);
            }
            return carePlan;
        });
    }

    @Test
    @DisplayName("Rule: MRN相同 + 名字DOB相同 -> 复用已有Patient，不产生warning")
    void shouldReusePatientWhenMrnAndIdentityMatch() {
        CreateOrderRequest request = baseRequest();
        Patient existingPatient = patient("Alice", "Wong", "123456", LocalDate.of(1990, 5, 10), 1L);

        when(patientRepository.findByMrn("123456")).thenReturn(Optional.of(existingPatient));

        OrderResponse response = orderService.createOrder(request);

        assertThat(response.getResultType()).isEqualTo("SUCCESS");
        assertThat(response.getWarnings()).isNullOrEmpty();

        verify(patientRepository, never()).save(any(Patient.class));
        verify(queueService).enqueue(300L);
    }

    @Test
    @DisplayName("Rule: MRN相同 + 名字或DOB不同 -> warning，但允许创建")
    void shouldReturnWarningWhenMrnMatchesButNameOrDobDiffers() {
        CreateOrderRequest request = baseRequest();
        request.setPatientFirstName("Alicia");

        Patient existingPatient = patient("Alice", "Wong", "123456", LocalDate.of(1990, 5, 10), 1L);
        when(patientRepository.findByMrn("123456")).thenReturn(Optional.of(existingPatient));

        OrderResponse response = orderService.createOrder(request);

        assertThat(response.getResultType()).isEqualTo("SUCCESS");
        assertThat(response.getWarnings())
                .isNotNull()
                .contains("Patient warning: MRN exists but name or DOB is different");

        verify(patientRepository, never()).save(any(Patient.class));
        verify(queueService).enqueue(300L);
    }

    @Test
    @DisplayName("Rule: 名字DOB相同 + MRN不同 -> warning，创建新Patient")
    void shouldReturnWarningAndCreateNewPatientWhenNameDobMatchButMrnDiffers() {
        CreateOrderRequest request = baseRequest();
        request.setPatientMrn("654321");

        Patient nameDobMatchedPatient = patient("Alice", "Wong", "123456", LocalDate.of(1990, 5, 10), 9L);
        Patient newlySavedPatient = patient("Alice", "Wong", "654321", LocalDate.of(1990, 5, 10), 10L);

        when(patientRepository.findByMrn("654321")).thenReturn(Optional.empty());
        when(patientRepository.findFirstByFirstNameIgnoreCaseAndLastNameIgnoreCaseAndDateOfBirth(
                "Alice", "Wong", LocalDate.of(1990, 5, 10)
        )).thenReturn(Optional.of(nameDobMatchedPatient));
        when(patientRepository.save(ArgumentMatchers.any(Patient.class))).thenReturn(newlySavedPatient);

        OrderResponse response = orderService.createOrder(request);

        assertThat(response.getResultType()).isEqualTo("SUCCESS");
        assertThat(response.getWarnings())
                .isNotNull()
                .contains("Patient warning: same name + DOB exists with different MRN");
        assertThat(response.getPatientId()).isEqualTo(10L);

        verify(patientRepository).save(any(Patient.class));
        verify(queueService).enqueue(300L);
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
        request.setAdditionalDiagnosis("I10");
        request.setMedicationHistory("Prednisone");
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


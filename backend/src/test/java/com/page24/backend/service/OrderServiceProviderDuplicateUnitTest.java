package com.page24.backend.service;

import com.page24.backend.dto.CreateOrderRequest;
import com.page24.backend.dto.OrderMapper;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.dto.Warning;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceProviderDuplicateUnitTest {

    @Mock private PatientRepository patientRepository;
    @Mock private ProviderRepository providerRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private CarePlanRepository carePlanRepository;
    @Mock private QueueService queueService;
    @Spy private OrderMapper orderMapper = new OrderMapper();
    @InjectMocks private OrderService orderService;

    @BeforeEach
    void setUpCommonMocks() {
        // Brand-new patient every time -> no patient-side conflicts, no order-history checks.
        when(patientRepository.findByMrn(any())).thenReturn(Optional.empty());
        lenient().when(patientRepository.findFirstByFirstNameIgnoreCaseAndLastNameIgnoreCaseAndDateOfBirth(
                any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(patientRepository.findByFirstNameIgnoreCaseAndLastNameIgnoreCase(any(), any()))
                .thenReturn(List.of());
        lenient().when(patientRepository.save(any(Patient.class))).thenAnswer(inv -> {
            Patient p = inv.getArgument(0);
            if (p.getId() == null) p.setId(1L);
            return p;
        });
        lenient().when(providerRepository.save(any(Provider.class))).thenAnswer(inv -> {
            Provider p = inv.getArgument(0);
            if (p.getId() == null) p.setId(2L);
            return p;
        });
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getId() == null) o.setId(3L);
            return o;
        });
        lenient().when(carePlanRepository.save(any(CarePlan.class))).thenAnswer(inv -> {
            CarePlan c = inv.getArgument(0);
            if (c.getId() == null) c.setId(4L);
            c.setStatus(CarePlan.Status.PENDING);
            return c;
        });
    }

    @Test
    @DisplayName("New NPI + same surname as an existing provider -> non-blocking PROVIDER_SIMILAR_NAME")
    void warnsWhenSurnameMatchesButNpiIsNew() {
        CreateOrderRequest request = baseRequest("Dr. Sara Lee", "2222222222");
        when(providerRepository.findByNpi("2222222222")).thenReturn(Optional.empty());
        when(providerRepository.findByNameContainingIgnoreCase("Lee"))
                .thenReturn(List.of(provider("Dr. Sarah Lee", "1111111111", 100L)));

        OrderResponse response = orderService.createOrder(request);

        assertThat(response.getResultType()).isEqualTo("WARNING");
        assertThat(response.getWarnings()).extracting(Warning::code).contains("PROVIDER_SIMILAR_NAME");
        assertThat(response.getWarnings()).noneMatch(Warning::actionRequired);
        verify(providerRepository).save(any(Provider.class));
    }

    @Test
    @DisplayName("New NPI + different surname -> no similar-name warning")
    void noWarningWhenSurnameDiffers() {
        CreateOrderRequest request = baseRequest("Dr. Sarah Kim", "3333333333");
        when(providerRepository.findByNpi("3333333333")).thenReturn(Optional.empty());
        when(providerRepository.findByNameContainingIgnoreCase("Kim")).thenReturn(List.of());

        OrderResponse response = orderService.createOrder(request);

        assertThat(response.getResultType()).isEqualTo("SUCCESS");
        assertThat(response.getWarnings()).isNullOrEmpty();
    }

    private CreateOrderRequest baseRequest(String providerName, String providerNpi) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setPatientFirstName("Bob");
        request.setPatientLastName("Stone");
        request.setPatientMrn("999999");
        request.setPatientDateOfBirth(LocalDate.of(1975, 3, 3));
        request.setProviderName(providerName);
        request.setProviderNpi(providerNpi);
        request.setMedicationName("IVIG");
        request.setPrimaryDiagnosis("G70.00");
        request.setAdditionalDiagnoses(List.of());
        request.setMedicationHistory(List.of());
        request.setPatientRecords("record");
        request.setConfirm(false);
        return request;
    }

    private Provider provider(String name, String npi, Long id) {
        Provider provider = new Provider();
        provider.setId(id);
        provider.setName(name);
        provider.setNpi(npi);
        return provider;
    }
}
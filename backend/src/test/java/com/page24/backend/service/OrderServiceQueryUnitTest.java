package com.page24.backend.service;

import com.page24.backend.dto.CarePlanDownload;
import com.page24.backend.dto.CarePlanStatusResponse;
import com.page24.backend.dto.OrderMapper;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.dto.PagedOrderResponse;
import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.Order;
import com.page24.backend.entity.Patient;
import com.page24.backend.entity.Provider;
import com.page24.backend.exception.CarePlanNotReadyException;
import com.page24.backend.exception.OrderNotFoundException;
import com.page24.backend.exception.ValidationError;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The read side of {@link OrderService}: single order, paged list, search, status polling and download.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceQueryUnitTest {

    private static final long ORDER_ID = 7L;

    @Mock private PatientRepository patientRepository;
    @Mock private ProviderRepository providerRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private CarePlanRepository carePlanRepository;
    @Mock private CarePlanQueue carePlanQueue;

    @Spy private OrderMapper orderMapper = new OrderMapper();

    @InjectMocks private OrderService orderService;

    private Order order;

    @BeforeEach
    void setUp() {
        order = order(ORDER_ID, patient(1L, "Alice", "Wong", "123456"));
    }

    // ----- getOrderById -----

    @Test
    @DisplayName("Existing order with a completed care plan -> response carries the patient, provider and content")
    void returnsTheOrderTogetherWithItsCompletedCarePlan() {
        CarePlan carePlan = carePlan(order, CarePlan.Status.COMPLETED, "the plan");
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(carePlanRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(carePlan));

        OrderResponse response = orderService.getOrderById(ORDER_ID);

        assertThat(response.getId()).isEqualTo(ORDER_ID);
        assertThat(response.getPatientMrn()).isEqualTo("123456");
        assertThat(response.getProviderNpi()).isEqualTo("1111111111");
        assertThat(response.getMedicationName()).isEqualTo("IVIG");
        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        assertThat(response.getCarePlanContent()).isEqualTo("the plan");
    }

    @Test
    @DisplayName("A care plan that is not completed yet never exposes its content")
    void hidesTheContentUntilTheCarePlanIsCompleted() {
        CarePlan carePlan = carePlan(order, CarePlan.Status.PROCESSING, "half written");
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(carePlanRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(carePlan));

        OrderResponse response = orderService.getOrderById(ORDER_ID);

        assertThat(response.getStatus()).isEqualTo("PROCESSING");
        assertThat(response.getCarePlanContent()).isNull();
    }

    @Test
    @DisplayName("Unknown order -> OrderNotFoundException (404) and the care plan is never looked up")
    void rejectsAnUnknownOrder() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> orderService.getOrderById(ORDER_ID));

        assertThat(thrown).isInstanceOf(OrderNotFoundException.class);
        OrderNotFoundException error = (OrderNotFoundException) thrown;
        assertThat(error.getCode()).isEqualTo("ORDER_NOT_FOUND");
        assertThat(error.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(carePlanRepository);
    }

    @Test
    @DisplayName("Order without a care plan -> CAREPLAN_NOT_FOUND validation error")
    void rejectsAnOrderThatHasNoCarePlan() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(carePlanRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> orderService.getOrderById(ORDER_ID));

        assertThat(thrown).isInstanceOf(ValidationError.class);
        assertThat(((ValidationError) thrown).getCode()).isEqualTo("CAREPLAN_NOT_FOUND");
    }

    // ----- getOrders -----

    @Test
    @DisplayName("page below 1 -> INVALID_PAGE and the repository is never queried")
    void rejectsPageZero() {
        Throwable thrown = catchThrowable(() -> orderService.getOrders(0, 10, null, null, null, null));

        assertThat(thrown).isInstanceOf(ValidationError.class);
        assertThat(((ValidationError) thrown).getCode()).isEqualTo("INVALID_PAGE");
        verifyNoInteractions(orderRepository);
    }

    @Test
    @DisplayName("page_size below 1 -> INVALID_PAGE_SIZE and the repository is never queried")
    void rejectsPageSizeZero() {
        Throwable thrown = catchThrowable(() -> orderService.getOrders(1, 0, null, null, null, null));

        assertThat(thrown).isInstanceOf(ValidationError.class);
        assertThat(((ValidationError) thrown).getCode()).isEqualTo("INVALID_PAGE_SIZE");
        verifyNoInteractions(orderRepository);
    }

    @Test
    @DisplayName("Page 2 of size 5 -> zero-based page index, newest first, total count from the repository")
    void translatesThePageParametersIntoAnOrderedPageRequest() {
        order.setCreatedAt(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        when(orderRepository.findByFilters(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(order), PageRequest.of(1, 5), 11));
        when(carePlanRepository.findByOrderId(ORDER_ID))
                .thenReturn(Optional.of(carePlan(order, CarePlan.Status.COMPLETED, "plan")));

        PagedOrderResponse response = orderService.getOrders(2, 5, null, null, null, null);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findByFilters(any(), any(), any(), any(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        assertThat(pageable.getValue().getSort().stream().map(o -> o.getProperty() + ":" + o.getDirection()))
                .containsExactly("createdAt:" + Sort.Direction.DESC, "id:" + Sort.Direction.DESC);

        assertThat(response.getCount()).isEqualTo(11);
        assertThat(response.getPage()).isEqualTo(2);
        assertThat(response.getPageSize()).isEqualTo(5);
        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getPatientName()).isEqualTo("Alice Wong");
        assertThat(response.getResults().get(0).getStatus()).isEqualTo("completed");
    }

    @Test
    @DisplayName("An order that has no care plan row is listed as pending")
    void listsAnOrderWithoutACarePlanAsPending() {
        order.setCreatedAt(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        when(orderRepository.findByFilters(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(order)));
        when(carePlanRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        PagedOrderResponse response = orderService.getOrders(1, 10, null, null, null, null);

        assertThat(response.getResults()).extracting("status").containsExactly("pending");
    }

    @Test
    @DisplayName("Patient name filter is trimmed and lower-cased into a LIKE pattern; blank means no filter")
    void buildsThePatientNamePatternFromTheFilter() {
        when(orderRepository.findByFilters(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        orderService.getOrders(1, 10, null, null, null, "  SmItH ");
        orderService.getOrders(1, 10, null, null, null, "   ");
        orderService.getOrders(1, 10, null, null, null, null);

        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
        verify(orderRepository, times(3))
                .findByFilters(any(), any(), any(), pattern.capture(), any(Pageable.class));
        assertThat(pattern.getAllValues()).containsExactly("%smith%", null, null);
    }

    // ----- searchOrders -----

    @Test
    @DisplayName("Search by MRN -> that patient's orders; a missing care plan is reported as PENDING")
    void searchesOrdersByMrn() {
        Patient alice = patient(1L, "Alice", "Wong", "123456");
        order.setPatient(alice);
        when(patientRepository.findByMrn("123456")).thenReturn(Optional.of(alice));
        when(orderRepository.findByPatient(alice)).thenReturn(List.of(order));
        when(carePlanRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        List<OrderResponse> results = orderService.searchOrders(null, "123456");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo(ORDER_ID);
        assertThat(results.get(0).getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("Search by an unknown MRN -> empty list, and no order query is made")
    void returnsNothingForAnUnknownMrn() {
        when(patientRepository.findByMrn("999999")).thenReturn(Optional.empty());

        List<OrderResponse> results = orderService.searchOrders(null, "999999");

        assertThat(results).isEmpty();
        verify(orderRepository, never()).findByPatient(any());
        verify(orderRepository, never()).findAll();
    }

    @Test
    @DisplayName("MRN takes precedence when both an MRN and a name are given")
    void mrnWinsOverName() {
        when(patientRepository.findByMrn("999999")).thenReturn(Optional.empty());

        orderService.searchOrders("Alice", "999999");

        verify(patientRepository, never())
                .findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(any(String.class), any(String.class));
    }

    @Test
    @DisplayName("Search by name -> orders of every matching patient, in patient order")
    void searchesOrdersByPartOfTheName() {
        Patient alice = patient(1L, "Alice", "Wong", "123456");
        Patient alicia = patient(2L, "Alicia", "Chan", "654321");
        Order first = order(10L, alice);
        Order second = order(11L, alicia);
        Order third = order(12L, alicia);
        when(patientRepository.findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase("ali", "ali"))
                .thenReturn(List.of(alice, alicia));
        when(orderRepository.findByPatient(alice)).thenReturn(List.of(first));
        when(orderRepository.findByPatient(alicia)).thenReturn(List.of(second, third));
        when(carePlanRepository.findByOrderId(any()))
                .thenAnswer(inv -> Optional.of(carePlan(order, CarePlan.Status.PENDING, null)));

        List<OrderResponse> results = orderService.searchOrders("ali", null);

        assertThat(results).extracting(OrderResponse::getId).containsExactly(10L, 11L, 12L);
    }

    @Test
    @DisplayName("An empty MRN is ignored, so a name can still be searched")
    void emptyMrnFallsBackToTheNameSearch() {
        when(patientRepository.findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase("Wong", "Wong"))
                .thenReturn(List.of());

        List<OrderResponse> results = orderService.searchOrders("Wong", "");

        assertThat(results).isEmpty();
        verify(patientRepository, never()).findByMrn(any());
    }

    @Test
    @DisplayName("No criteria (null or empty) -> every order is returned")
    void returnsEveryOrderWhenThereAreNoCriteria() {
        when(orderRepository.findAll()).thenReturn(List.of(order));
        when(carePlanRepository.findByOrderId(ORDER_ID))
                .thenReturn(Optional.of(carePlan(order, CarePlan.Status.FAILED, null)));

        assertThat(orderService.searchOrders(null, null)).extracting(OrderResponse::getId).containsExactly(ORDER_ID);
        assertThat(orderService.searchOrders("", "")).extracting(OrderResponse::getId).containsExactly(ORDER_ID);
        verify(patientRepository, never()).findByMrn(any());
    }

    // ----- getCarePlanStatus -----

    @Test
    @DisplayName("Completed care plan of exactly 300 characters -> preview is the full text")
    void keepsAPreviewThatFitsTheLimit() {
        String content = "x".repeat(300);
        givenCarePlan(CarePlan.Status.COMPLETED, content, null);

        CarePlanStatusResponse response = orderService.getCarePlanStatus(ORDER_ID);

        assertThat(response.getStatus()).isEqualTo("completed");
        assertThat(response.getCarePlanPreview()).isEqualTo(content);
    }

    @Test
    @DisplayName("Completed care plan of 301 characters -> preview is cut to 300 characters plus an ellipsis")
    void truncatesALongPreview() {
        String content = "a".repeat(300) + "b";
        givenCarePlan(CarePlan.Status.COMPLETED, content, null);

        CarePlanStatusResponse response = orderService.getCarePlanStatus(ORDER_ID);

        assertThat(response.getCarePlanPreview()).isEqualTo("a".repeat(300) + "...");
    }

    @Test
    @DisplayName("Completed care plan without content -> no preview, no error")
    void hasNoPreviewWhenThereIsNoContent() {
        givenCarePlan(CarePlan.Status.COMPLETED, null, null);

        CarePlanStatusResponse response = orderService.getCarePlanStatus(ORDER_ID);

        assertThat(response.getStatus()).isEqualTo("completed");
        assertThat(response.getCarePlanPreview()).isNull();
        assertThat(response.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("Failed care plan -> only the safe error message is exposed, never a preview")
    void exposesOnlyTheStoredErrorMessageForAFailedCarePlan() {
        givenCarePlan(CarePlan.Status.FAILED, "stale content", "Care plan generation failed");

        CarePlanStatusResponse response = orderService.getCarePlanStatus(ORDER_ID);

        assertThat(response.getStatus()).isEqualTo("failed");
        assertThat(response.getErrorMessage()).isEqualTo("Care plan generation failed");
        assertThat(response.getCarePlanPreview()).isNull();
    }

    @Test
    @DisplayName("Pending or processing care plan -> status only")
    void reportsOnlyTheStatusWhileTheCarePlanIsStillRunning() {
        givenCarePlan(CarePlan.Status.PROCESSING, "partial", "ignored");

        CarePlanStatusResponse response = orderService.getCarePlanStatus(ORDER_ID);

        assertThat(response.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(response.getStatus()).isEqualTo("processing");
        assertThat(response.getCarePlanPreview()).isNull();
        assertThat(response.getErrorMessage()).isNull();
    }

    // ----- downloadCarePlan -----

    @Test
    @DisplayName("Completed care plan -> file named after the order, content encoded as UTF-8")
    void downloadsACompletedCarePlan() {
        givenCarePlan(CarePlan.Status.COMPLETED, "护理计划", null);

        CarePlanDownload download = orderService.downloadCarePlan(ORDER_ID);

        assertThat(download.filename()).isEqualTo("careplan_order_7.txt");
        assertThat(new String(download.content(), StandardCharsets.UTF_8)).isEqualTo("护理计划");
    }

    @Test
    @DisplayName("Care plan that is not completed, has no content or does not exist -> CarePlanNotReadyException")
    void refusesToDownloadACarePlanThatIsNotReady() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(carePlanRepository.findByOrderId(ORDER_ID)).thenReturn(
                Optional.of(carePlan(order, CarePlan.Status.PENDING, null)),
                Optional.of(carePlan(order, CarePlan.Status.COMPLETED, null)),
                Optional.empty());

        assertThat(catchThrowable(() -> orderService.downloadCarePlan(ORDER_ID)))
                .as("pending care plan").isInstanceOf(CarePlanNotReadyException.class);
        assertThat(catchThrowable(() -> orderService.downloadCarePlan(ORDER_ID)))
                .as("completed but empty care plan").isInstanceOf(CarePlanNotReadyException.class);
        assertThat(catchThrowable(() -> orderService.downloadCarePlan(ORDER_ID)))
                .as("no care plan row").isInstanceOf(CarePlanNotReadyException.class);
    }

    @Test
    @DisplayName("Download of an unknown order -> OrderNotFoundException")
    void refusesToDownloadForAnUnknownOrder() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThat(catchThrowable(() -> orderService.downloadCarePlan(ORDER_ID)))
                .isInstanceOf(OrderNotFoundException.class);
        verifyNoInteractions(carePlanRepository);
    }

    // ----- helpers -----

    private void givenCarePlan(CarePlan.Status status, String content, String errorMessage) {
        CarePlan carePlan = carePlan(order, status, content);
        carePlan.setErrorMessage(errorMessage);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(carePlanRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(carePlan));
    }

    private static Patient patient(Long id, String firstName, String lastName, String mrn) {
        Patient patient = new Patient();
        patient.setId(id);
        patient.setFirstName(firstName);
        patient.setLastName(lastName);
        patient.setMrn(mrn);
        return patient;
    }

    private static Order order(Long id, Patient patient) {
        Provider provider = new Provider();
        provider.setId(2L);
        provider.setName("Dr. Green");
        provider.setNpi("1111111111");
        Order order = new Order();
        order.setId(id);
        order.setPatient(patient);
        order.setProvider(provider);
        order.setMedicationName("IVIG");
        order.setCreatedAt(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        return order;
    }

    private static CarePlan carePlan(Order order, CarePlan.Status status, String content) {
        CarePlan carePlan = new CarePlan();
        carePlan.setId(30L);
        carePlan.setOrder(order);
        carePlan.setStatus(status);
        carePlan.setContent(content);
        return carePlan;
    }
}

package com.page24.backend.service;

import com.page24.backend.dto.OrderMapper;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.Order;
import com.page24.backend.entity.Patient;
import com.page24.backend.entity.Provider;
import com.page24.backend.exception.BlockError;
import com.page24.backend.exception.OrderNotFoundException;
import com.page24.backend.exception.ValidationError;
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
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OrderService#uploadCarePlan}: exactly one of pasted text or a file must be supplied, the content replaces
 * the care plan, and nothing is written when the request is rejected.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceUploadUnitTest {

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
        order = order(ORDER_ID);
        carePlan = carePlan(order, CarePlan.Status.FAILED);
        carePlan.setContent("old content");
        carePlan.setErrorMessage("Care plan generation failed");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Pasted text only -> care plan becomes COMPLETED, uploaded, error cleared, both entities saved")
    void replacesTheCarePlanWithPastedText() {
        givenOrderAndCarePlanExist();

        OrderResponse response = orderService.uploadCarePlan(ORDER_ID, "Reviewed care plan", null);

        assertThat(carePlan.getContent()).isEqualTo("Reviewed care plan");
        assertThat(carePlan.getStatus()).isEqualTo(CarePlan.Status.COMPLETED);
        assertThat(carePlan.isUploaded()).isTrue();
        assertThat(carePlan.getErrorMessage()).isNull();
        assertThat(order.getUpdatedBySubject()).isEqualTo("system");
        verify(carePlanRepository).save(carePlan);
        verify(orderRepository).save(order);
        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        assertThat(response.getCarePlanContent()).isEqualTo("Reviewed care plan");
        // An upload never goes back through the generation queue.
        verify(carePlanQueue, never()).enqueue(any());
    }

    @Test
    @DisplayName("File only -> its bytes are decoded as UTF-8")
    void readsTheUploadedFileAsUtf8() {
        givenOrderAndCarePlanExist();
        String text = "护理计划: 每日一次, 10mg";
        MultipartFile file = new MockMultipartFile(
                "file", "careplan.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8));

        orderService.uploadCarePlan(ORDER_ID, null, file);

        assertThat(carePlan.getContent()).isEqualTo(text);
        assertThat(carePlan.getStatus()).isEqualTo(CarePlan.Status.COMPLETED);
        assertThat(carePlan.isUploaded()).isTrue();
        verify(carePlanRepository).save(carePlan);
    }

    @Test
    @DisplayName("Blank pasted text + a file -> the file is used")
    void blankTextDoesNotCountAsPastedText() {
        givenOrderAndCarePlanExist();
        MultipartFile file = new MockMultipartFile(
                "file", "careplan.txt", "text/plain", "from file".getBytes(StandardCharsets.UTF_8));

        orderService.uploadCarePlan(ORDER_ID, "   ", file);

        assertThat(carePlan.getContent()).isEqualTo("from file");
    }

    @Test
    @DisplayName("Pasted text + an empty file -> the text is used")
    void emptyFileDoesNotCountAsAFile() {
        givenOrderAndCarePlanExist();
        MultipartFile emptyFile = new MockMultipartFile("file", "careplan.txt", "text/plain", new byte[0]);

        orderService.uploadCarePlan(ORDER_ID, "from text", emptyFile);

        assertThat(carePlan.getContent()).isEqualTo("from text");
    }

    @Test
    @DisplayName("Neither text nor file -> CAREPLAN_UPLOAD_INVALID (400), care plan untouched, nothing saved")
    void rejectsARequestWithNoContent() {
        givenOrderAndCarePlanExist();

        assertRejectedAsInvalidUpload(null, null);
    }

    @Test
    @DisplayName("Both text and file -> CAREPLAN_UPLOAD_INVALID (400), care plan untouched, nothing saved")
    void rejectsARequestWithTextAndFile() {
        givenOrderAndCarePlanExist();
        MultipartFile file = new MockMultipartFile(
                "file", "careplan.txt", "text/plain", "from file".getBytes(StandardCharsets.UTF_8));

        assertRejectedAsInvalidUpload("from text", file);
    }

    @Test
    @DisplayName("Blank text and an empty file -> CAREPLAN_UPLOAD_INVALID (400), nothing saved")
    void rejectsBlankTextWithAnEmptyFile() {
        givenOrderAndCarePlanExist();
        MultipartFile emptyFile = new MockMultipartFile("file", "careplan.txt", "text/plain", new byte[0]);

        assertRejectedAsInvalidUpload("  ", emptyFile);
    }

    @Test
    @DisplayName("File that cannot be read -> CAREPLAN_UPLOAD_UNREADABLE (400), care plan untouched, nothing saved")
    void rejectsAnUnreadableFile() throws IOException {
        givenOrderAndCarePlanExist();
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getBytes()).thenThrow(new IOException("disk error"));

        Throwable thrown = catchThrowable(() -> orderService.uploadCarePlan(ORDER_ID, null, file));

        assertThat(thrown).isInstanceOf(ValidationError.class);
        ValidationError error = (ValidationError) thrown;
        assertThat(error.getCode()).isEqualTo("CAREPLAN_UPLOAD_UNREADABLE");
        assertThat(error.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertCarePlanUntouchedAndNothingSaved();
    }

    @Test
    @DisplayName("Generation in progress -> BlockError 409 CAREPLAN_ALREADY_PROCESSING, nothing saved")
    void rejectsAnUploadWhileGenerationIsRunning() {
        carePlan.setStatus(CarePlan.Status.PROCESSING);
        givenOrderAndCarePlanExist();

        Throwable thrown = catchThrowable(() -> orderService.uploadCarePlan(ORDER_ID, "late upload", null));

        assertThat(thrown).isInstanceOf(BlockError.class);
        BlockError error = (BlockError) thrown;
        assertThat(error.getCode()).isEqualTo("CAREPLAN_ALREADY_PROCESSING");
        assertThat(error.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(carePlan.getStatus()).isEqualTo(CarePlan.Status.PROCESSING);
        assertThat(carePlan.getContent()).isEqualTo("old content");
        verify(carePlanRepository, never()).save(any(CarePlan.class));
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("Unknown order -> OrderNotFoundException (404), care plan never looked up")
    void rejectsAnUploadForAnUnknownOrder() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> orderService.uploadCarePlan(ORDER_ID, "text", null));

        assertThat(thrown).isInstanceOf(OrderNotFoundException.class);
        assertThat(((OrderNotFoundException) thrown).getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(carePlanRepository, never()).findByOrderId(any());
        verify(carePlanRepository, never()).save(any(CarePlan.class));
    }

    @Test
    @DisplayName("Order without a care plan -> CAREPLAN_NOT_FOUND (400), nothing saved")
    void rejectsAnUploadWhenTheOrderHasNoCarePlan() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(carePlanRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> orderService.uploadCarePlan(ORDER_ID, "text", null));

        assertThat(thrown).isInstanceOf(ValidationError.class);
        assertThat(((ValidationError) thrown).getCode()).isEqualTo("CAREPLAN_NOT_FOUND");
        verify(orderRepository, never()).save(any(Order.class));
        verify(carePlanRepository, never()).save(any(CarePlan.class));
    }

    // ----- helpers -----

    private void givenOrderAndCarePlanExist() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(carePlanRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(carePlan));
    }

    private void assertRejectedAsInvalidUpload(String content, MultipartFile file) {
        Throwable thrown = catchThrowable(() -> orderService.uploadCarePlan(ORDER_ID, content, file));

        assertThat(thrown).isInstanceOf(ValidationError.class);
        ValidationError error = (ValidationError) thrown;
        assertThat(error.getCode()).isEqualTo("CAREPLAN_UPLOAD_INVALID");
        assertThat(error.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertCarePlanUntouchedAndNothingSaved();
    }

    private void assertCarePlanUntouchedAndNothingSaved() {
        assertThat(carePlan.getContent()).isEqualTo("old content");
        assertThat(carePlan.getStatus()).isEqualTo(CarePlan.Status.FAILED);
        assertThat(carePlan.isUploaded()).isFalse();
        assertThat(carePlan.getErrorMessage()).isEqualTo("Care plan generation failed");
        verify(carePlanRepository, never()).save(any(CarePlan.class));
        verify(orderRepository, never()).save(any(Order.class));
    }

    private static Order order(Long id) {
        Patient patient = new Patient();
        patient.setId(1L);
        patient.setFirstName("Alice");
        patient.setLastName("Wong");
        patient.setMrn("123456");
        Provider provider = new Provider();
        provider.setId(2L);
        provider.setName("Dr. Green");
        provider.setNpi("1111111111");
        Order order = new Order();
        order.setId(id);
        order.setPatient(patient);
        order.setProvider(provider);
        order.setMedicationName("IVIG");
        return order;
    }

    private static CarePlan carePlan(Order order, CarePlan.Status status) {
        CarePlan carePlan = new CarePlan();
        carePlan.setId(30L);
        carePlan.setOrder(order);
        carePlan.setStatus(status);
        return carePlan;
    }
}

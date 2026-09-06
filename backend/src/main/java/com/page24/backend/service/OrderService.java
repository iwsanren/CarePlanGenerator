package com.page24.backend.service;

import com.page24.backend.dto.CreateOrderRequest;
import com.page24.backend.dto.CarePlanStatusResponse;
import com.page24.backend.dto.CarePlanDownload;
import com.page24.backend.dto.OrderMapper;
import com.page24.backend.dto.OrderListItemResponse;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.dto.PagedOrderResponse;
import com.page24.backend.entity.*;
import com.page24.backend.exception.BlockError;
import com.page24.backend.exception.CarePlanNotReadyException;
import com.page24.backend.exception.OrderNotFoundException;
import com.page24.backend.exception.ValidationError;
import com.page24.backend.exception.WarningException;
import com.page24.backend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * OrderService - business logic layer.
 *
 * Owns all business rules and operations, including:
 *   - Finding or creating patients and providers.
 *   - Creating orders and Care Plans.
 *   - Enqueuing work in Redis.
 *   - Searching orders.
 *   - Building downloadable Care Plan content.
 *
 * These responsibilities previously lived in OrderController.
 * The controller now delegates to this service and returns its result to the frontend.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private static final int CARE_PLAN_PREVIEW_LENGTH = 300;

    private final PatientRepository patientRepository;
    private final ProviderRepository providerRepository;
    private final OrderRepository orderRepository;
    private final CarePlanRepository carePlanRepository;
    private final CarePlanQueue carePlanQueue;
    private final OrderMapper orderMapper;

    /**
     * Creates an order by finding or creating the patient and provider, creating the
     * Order and CarePlan, then enqueueing the CarePlan for processing.
     *
     * This logic previously lived in OrderController's createOrder() method.
     */
    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        List<String> warnings = new ArrayList<>();

        // 1) Detect duplicate providers.
        Provider provider = providerRepository.findByNpi(request.getProviderNpi())
                .map(existingProvider -> {
                    if (!sameText(existingProvider.getName(), request.getProviderName())) {
                        throw new BlockError(
                                "DUPLICATE_NPI_NAME_MISMATCH",
                                "Provider conflict: same NPI with different provider name"
                        );
                    }
                    return existingProvider;
                })
                .orElseGet(() -> {
                    Provider newProvider = new Provider();
                    newProvider.setName(request.getProviderName());
                    newProvider.setNpi(request.getProviderNpi());
                    return providerRepository.save(newProvider);
                });

        // 2) Detect duplicate patients.
        Patient patient;
        Optional<Patient> existingByMrn = patientRepository.findByMrn(request.getPatientMrn());

        if (existingByMrn.isPresent()) {
            // existing patient
            Patient matched = existingByMrn.get();
            boolean sameName = sameText(matched.getFirstName(), request.getPatientFirstName())
                    && sameText(matched.getLastName(), request.getPatientLastName());
            boolean sameDob = request.getPatientDateOfBirth() == null
                    || (matched.getDateOfBirth() != null
                    && matched.getDateOfBirth().equals(request.getPatientDateOfBirth()));

            if (!sameName || !sameDob) {
                warnings.add("Patient warning: MRN exists but name or DOB is different");
            }
            patient = matched;
        } else {
            // create a new patient
            Optional<Patient> existingByNameDob = request.getPatientDateOfBirth() == null
                    ? Optional.empty()
                    : patientRepository.findFirstByFirstNameIgnoreCaseAndLastNameIgnoreCaseAndDateOfBirth(
                    request.getPatientFirstName(),
                    request.getPatientLastName(),
                    request.getPatientDateOfBirth()
            );

            existingByNameDob
                    .filter(p -> !sameText(p.getMrn(), request.getPatientMrn()))
                    .ifPresent(p -> warnings.add("Patient warning: same name + DOB exists with different MRN"));

            Patient newPatient = new Patient();
            newPatient.setFirstName(request.getPatientFirstName());
            newPatient.setLastName(request.getPatientLastName());
            newPatient.setMrn(request.getPatientMrn());
            newPatient.setDateOfBirth(request.getPatientDateOfBirth());
            newPatient.setSex(request.getPatientSex());
            newPatient.setWeightKg(request.getPatientWeightKg());
            newPatient.setAllergies(request.getPatientAllergies());
            patient = patientRepository.save(newPatient);
        }

        // 3) Detect duplicate orders.
        LocalDate today = LocalDate.now();
        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime nextDayStart = today.plusDays(1).atStartOfDay();

        boolean samePatientMedicationSameDay = orderRepository.existsByPatientAndMedicationNameIgnoreCaseAndCreatedAtBetween(
                patient,
                request.getMedicationName(),
                startOfDay,
                nextDayStart
        );

        if (samePatientMedicationSameDay) {
            throw new BlockError(
                    "DUPLICATE_ORDER_SAME_DAY",
                    "Duplicate order: same patient + same medication + same day"
            );
        }

        Optional<Order> previousSameMedicationOrder = orderRepository
                .findFirstByPatientAndMedicationNameIgnoreCaseOrderByCreatedAtDesc(
                        patient,
                        request.getMedicationName()
                );

        if (previousSameMedicationOrder.isPresent()) {
            warnings.add("Order warning: same patient + same medication exists on a different day");
            if (!Boolean.TRUE.equals(request.getConfirm())) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("requiresConfirm", true);
                detail.put("warnings", warnings);
                throw new WarningException(
                        "POTENTIAL_DUPLICATE_ORDER_CROSS_DAY",
                        "Potential duplicate order detected. Resubmit with confirm=true to continue.",
                        detail
                );
            }
        }

        // 4) Create the order.
        Order order = new Order();
        order.setPatient(patient);
        order.setProvider(provider);
        order.setMedicationName(request.getMedicationName());
        order.setPrimaryDiagnosis(request.getPrimaryDiagnosis());
        order.setAdditionalDiagnosis(request.getAdditionalDiagnosis());
        order.setMedicationHistory(request.getMedicationHistory());
        order.setPatientRecords(request.getPatientRecords());
        order = orderRepository.save(order);

        // 5) Create a CarePlan in the PENDING state.
        CarePlan carePlan = new CarePlan();
        carePlan.setOrder(order);
        carePlan.setStatus(CarePlan.Status.PENDING);
        carePlan = carePlanRepository.save(carePlan);

        // 6) Enqueue the task. Redis is used locally; the AWS Lambda profile uses SQS.
        carePlanQueue.enqueue(carePlan.getId());

        OrderResponse response = orderMapper.toResponse(order, carePlan);
        if (!warnings.isEmpty()) {
            response.setMessage("Order created with warnings");
            response.setWarnings(warnings);
        }
        return response;
    }

    private boolean sameText(String left, String right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    /**
     * Retrieves an order's status and CarePlan content by order ID.
     *
     * This consolidates the former getCarePlanStatus() and getOrder() methods in
     * OrderController, which had identical logic.
     */
    public OrderResponse getOrderById(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));

        CarePlan carePlan = carePlanRepository.findByOrderId(id)
                .orElseThrow(() -> new ValidationError("CAREPLAN_NOT_FOUND", "CarePlan not found"));

        return orderMapper.toResponse(order, carePlan);
    }

    /** Query orders with pagination and optional filters, which can be combined. */
    public PagedOrderResponse getOrders(
            int page,
            int pageSize,
            String status,
            Long patientId,
            Long providerId,
            String patientName
    ) {
        if (page < 1) {
            throw new ValidationError("INVALID_PAGE", "page must be greater than or equal to 1");
        }
        if (pageSize < 1) {
            throw new ValidationError("INVALID_PAGE_SIZE", "page_size must be greater than or equal to 1");
        }
        validatePositiveId(patientId, "patient_id", "INVALID_PATIENT_ID");
        validatePositiveId(providerId, "provider_id", "INVALID_PROVIDER_ID");

        CarePlan.Status statusFilter = parseStatus(status);
        String patientNamePattern = buildPatientNamePattern(patientName);

        PageRequest pageRequest = PageRequest.of(
                page - 1,
                pageSize,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"))
        );

        Page<Order> orders = orderRepository.findByFilters(
                statusFilter, patientId, providerId, patientNamePattern, pageRequest);
        List<OrderListItemResponse> results = orders.getContent().stream()
                .map(this::toListItemResponse)
                .collect(Collectors.toList());

        return new PagedOrderResponse(orders.getTotalElements(), page, pageSize, results);
    }

    /**
     * Searches orders by patient name or MRN.
     *
     * This logic previously lived in OrderController's searchOrders() method.
     */
    public List<OrderResponse> searchOrders(String patientName, String mrn) {
        List<Order> orders;

        if (mrn != null && !mrn.isEmpty()) {
            // Search by MRN.
            Patient patient = patientRepository.findByMrn(mrn).orElse(null);
            if (patient != null) {
                orders = orderRepository.findByPatient(patient);
            } else {
                orders = List.of();
            }
        } else if (patientName != null && !patientName.isEmpty()) {
            // Search by patient name with partial matching.
            List<Patient> patients = patientRepository
                    .findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(
                            patientName, patientName);
            orders = patients.stream()
                    .flatMap(patient -> orderRepository.findByPatient(patient).stream())
                    .collect(Collectors.toList());
        } else {
            // Return all orders when no search criteria are supplied.
            orders = orderRepository.findAll();
        }

        return orders.stream()
                .map(this::toOrderResponse)
                .collect(Collectors.toList());
    }

    private OrderResponse toOrderResponse(Order order) {
        CarePlan carePlan = carePlanRepository.findByOrderId(order.getId()).orElse(null);
        return orderMapper.toResponse(order, carePlan);
    }

    /**
     * Returns the small response used by the front end while it polls for an
     * asynchronous CarePlan generation result.
     */
    public CarePlanStatusResponse getCarePlanStatus(Long orderId) {
        orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        CarePlan carePlan = carePlanRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ValidationError("CAREPLAN_NOT_FOUND", "CarePlan not found"));

        CarePlanStatusResponse response = new CarePlanStatusResponse();
        response.setOrderId(orderId);
        response.setStatus(carePlan.getStatus().name().toLowerCase(Locale.ROOT));

        if (carePlan.getStatus() == CarePlan.Status.COMPLETED) {
            response.setCarePlanPreview(toPreview(carePlan.getContent()));
        } else if (carePlan.getStatus() == CarePlan.Status.FAILED) {
            response.setErrorMessage(carePlan.getErrorMessage());
        }

        return response;
    }

    private String toPreview(String content) {
        if (content == null || content.length() <= CARE_PLAN_PREVIEW_LENGTH) {
            return content;
        }
        return content.substring(0, CARE_PLAN_PREVIEW_LENGTH) + "...";
    }

    private OrderListItemResponse toListItemResponse(Order order) {
        CarePlan carePlan = carePlanRepository.findByOrderId(order.getId()).orElse(null);
        return orderMapper.toListItemResponse(order, carePlan);
    }

    private void validatePositiveId(Long id, String parameterName, String errorCode) {
        if (id != null && id < 1) {
            throw new ValidationError(errorCode, parameterName + " must be greater than or equal to 1");
        }
    }

    private CarePlan.Status parseStatus(String status) {
        String normalized = normalizeBlankToNull(status);
        if (normalized == null) {
            return null;
        }

        try {
            return CarePlan.Status.valueOf(normalized.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ValidationError(
                    "INVALID_STATUS",
                    "status must be one of: pending, processing, completed, failed"
            );
        }
    }

    private String buildPatientNamePattern(String patientName) {
        String normalized = normalizeBlankToNull(patientName);
        if (normalized == null) {
            return null;
        }
        return "%" + normalized.toLowerCase(Locale.ROOT) + "%";
    }

    private String normalizeBlankToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    /**
     * Prepares downloadable content by validating the CarePlan status and building the file body.
     *
     * This combines the former downloadCarePlan() and buildDownloadContent()
     * methods from OrderController.
     *
     * Returns byte[]; the controller only sets the HTTP response headers.
     */
    public CarePlanDownload downloadCarePlan(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));

        CarePlan carePlan = carePlanRepository.findByOrderId(id)
                .orElseThrow(CarePlanNotReadyException::new);

        if (carePlan.getStatus() != CarePlan.Status.COMPLETED || carePlan.getContent() == null) {
            throw new CarePlanNotReadyException();
        }

        byte[] content = carePlan.getContent().getBytes(StandardCharsets.UTF_8);
        String filename = "careplan_order_" + order.getId() + ".txt";
        return new CarePlanDownload(filename, content);
    }
}


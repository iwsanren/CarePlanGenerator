package com.page24.backend.service;

import com.page24.backend.dto.CreateOrderRequest;
import com.page24.backend.dto.CarePlanStatusResponse;
import com.page24.backend.dto.CarePlanDownload;
import com.page24.backend.dto.OrderMapper;
import com.page24.backend.dto.OrderListItemResponse;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.dto.PagedOrderResponse;
import com.page24.backend.dto.Warning;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
        List<Warning> warnings = new ArrayList<>();

        // ---------- 1) Provider detection ----------
        Optional<Provider> providerByNpi = providerRepository.findByNpi(request.getProviderNpi());
        Provider providerToUse = null;
        if (providerByNpi.isPresent()) {
            Provider matched = providerByNpi.get();
            if (!sameText(matched.getName(), request.getProviderName())) {
                // Same NPI, different name is a hard conflict: block (409).
                throw new BlockError(
                        "DUPLICATE_NPI_NAME_MISMATCH",
                        "Provider conflict: same NPI with different provider name");
            }
            providerToUse = matched;
        } else {
            // New NPI. Look for a provider with the same surname but a different NPI:
            // possibly the same physician entered with a mistyped or changed NPI.
            String surname = lastNameToken(request.getProviderName());
            boolean similarProviderExists = providerRepository
                    .findByNameContainingIgnoreCase(surname).stream()
                    .anyMatch(p -> surname.equalsIgnoreCase(lastNameToken(p.getName())));
            if (similarProviderExists) {
                warnings.add(new Warning(
                        "PROVIDER_SIMILAR_NAME",
                        "A provider with a similar name but a different NPI already exists",
                        false));
            }
        }

        // ---------- 2) Patient detection ----------
        Optional<Patient> patientByMrn = patientRepository.findByMrn(request.getPatientMrn());
        Patient patientToUse = null;
        if (patientByMrn.isPresent()) {
            Patient matched = patientByMrn.get();
            boolean sameName = sameText(matched.getFirstName(), request.getPatientFirstName())
                    && sameText(matched.getLastName(), request.getPatientLastName());
            // A request without a DOB is treated as "matches"
            boolean sameDob = request.getPatientDateOfBirth() == null
                    || (matched.getDateOfBirth() != null
                        && matched.getDateOfBirth().equals(request.getPatientDateOfBirth()));
            if (!sameName || !sameDob) {
                warnings.add(new Warning(
                        "PATIENT_MRN_CONFLICT",
                        "This MRN exists but the name or date of birth does not match",
                        true));
            }
            patientToUse = matched;
        } else {
            boolean possibleDuplicateAdded = false;
            if (request.getPatientDateOfBirth() != null) {
                boolean sameNameDobDifferentMrn = patientRepository
                        .findFirstByFirstNameIgnoreCaseAndLastNameIgnoreCaseAndDateOfBirth(
                                request.getPatientFirstName(),
                                request.getPatientLastName(),
                                request.getPatientDateOfBirth())
                        .filter(p -> !sameText(p.getMrn(), request.getPatientMrn()))
                        .isPresent();
                if (sameNameDobDifferentMrn) {
                    warnings.add(new Warning(
                            "PATIENT_POSSIBLE_DUPLICATE",
                            "A patient with the same name and date of birth but a different MRN already exists",
                            true));
                    possibleDuplicateAdded = true;
                }
            }
            if (!possibleDuplicateAdded) {
                boolean sameNameDifferentMrn = patientRepository
                        .findByFirstNameIgnoreCaseAndLastNameIgnoreCase(
                                request.getPatientFirstName(), request.getPatientLastName())
                        .stream()
                        .anyMatch(p -> !sameText(p.getMrn(), request.getPatientMrn()));
                if (sameNameDifferentMrn) {
                    warnings.add(new Warning(
                            "PATIENT_SIMILAR_NAME",
                            "A patient with the same name but a different MRN already exists",
                            false));
                }
            }
        }

        // ---------- 3) Order detection (only an existing patient can have prior orders) ----------
        if (patientToUse != null) {
            LocalDate today = LocalDate.now();
            boolean sameDay = orderRepository
                    .existsByPatientAndMedicationNameIgnoreCaseAndCreatedAtBetween(
                            patientToUse, request.getMedicationName(),
                            today.atStartOfDay(), today.plusDays(1).atStartOfDay());
            if (sameDay) {
                // Same patient + same medication + same day is a hard conflict: block (409).
                throw new BlockError(
                        "DUPLICATE_ORDER_SAME_DAY",
                        "Duplicate order: same patient + same medication + same day");
            }
            boolean crossDay = orderRepository
                    .findFirstByPatientAndMedicationNameIgnoreCaseOrderByCreatedAtDesc(
                            patientToUse, request.getMedicationName())
                    .isPresent();
            if (crossDay) {
                warnings.add(new Warning(
                        "ORDER_CROSS_DAY_DUPLICATE",
                        "The same patient already has an order for this medication on a different day",
                        true));
            }
        }

        // ---------- 4) Confirmation gate ----------
        // If any warning needs action and the caller has not confirmed, stop here.
        boolean requiresConfirm = warnings.stream().anyMatch(Warning::actionRequired);
        if (requiresConfirm && !Boolean.TRUE.equals(request.getConfirm())) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("requiresConfirm", true);
            detail.put("warnings", warnings);
            throw new WarningException(
                    "CONFIRMATION_REQUIRED",
                    "Potential duplicate detected. Resubmit with confirm=true to continue.",
                    detail);
        }

        // ---------- 5) Persist ----------
        Provider provider = (providerToUse != null)
                ? providerToUse
                : providerRepository.save(newProvider(request));
        Patient patient = (patientToUse != null)
                ? patientToUse
                : patientRepository.save(newPatient(request));

        Order order = new Order();
        order.setPatient(patient);
        order.setProvider(provider);
        order.setMedicationName(request.getMedicationName());
        order.setPrimaryDiagnosis(request.getPrimaryDiagnosis());
        order.setAdditionalDiagnoses(request.getAdditionalDiagnoses());
        order.setMedicationHistory(request.getMedicationHistory());
        order.setPatientRecords(request.getPatientRecords());
        // Audit flag: true only when a human confirmed past an action-required warning.
        order.setConfirmedNotDuplicate(requiresConfirm && Boolean.TRUE.equals(request.getConfirm()));
        order = orderRepository.save(order);

        CarePlan carePlan = new CarePlan();
        carePlan.setOrder(order);
        carePlan.setStatus(CarePlan.Status.PENDING);
        carePlan = carePlanRepository.save(carePlan);

        // Redis locally; the AWS Lambda profile uses SQS.
        carePlanQueue.enqueue(carePlan.getId());

        // ---------- 6) Build response ----------
        OrderResponse response = orderMapper.toResponse(order, carePlan);
        if (!warnings.isEmpty()) {
            response.setResultType("WARNING");
            response.setMessage("Order created with warnings");
            response.setWarnings(warnings);
        }
        response.setRequiresConfirm(false);
        return response;
    }

    /** Blocks any manual mutation (regenerate/upload) while a generation is already in flight. */
    private void ensureCarePlanNotBusy(CarePlan carePlan) {
        if (carePlan.getStatus() == CarePlan.Status.PROCESSING) {
            throw new BlockError(
                    "CAREPLAN_ALREADY_PROCESSING",
                    "A care plan generation is already in progress for this order");
        }
    }

    /**
     * Resets a completed/failed CarePlan back to PENDING and re-enqueues it.
     * Reuses the Phase 5 event-driven pipeline unchanged: enqueue() publishes
     * CarePlanQueuedEvent, and CarePlanWorker picks it up right after this
     * transaction commits.
     */
    @Transactional
    public OrderResponse regenerateCarePlan(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        CarePlan carePlan = carePlanRepository.findByOrderId(id)
                .orElseThrow(() -> new ValidationError("CAREPLAN_NOT_FOUND", "CarePlan not found"));

        ensureCarePlanNotBusy(carePlan);
        if (carePlan.getStatus() == CarePlan.Status.PENDING) {
            // Nothing has run yet — "regenerate" implies there was a first attempt to redo.
            throw new BlockError(
                    "CAREPLAN_NOT_READY_FOR_REGENERATION",
                    "This order has not finished its first generation yet");
        }

        carePlan.setStatus(CarePlan.Status.PENDING);
        carePlan.setContent(null);
        carePlan.setErrorMessage(null);
        carePlan.setUploaded(false);   // a fresh LLM run replaces any prior manual upload
        carePlanRepository.save(carePlan);

        carePlanQueue.enqueue(carePlan.getId());

        return orderMapper.toResponse(order, carePlan);
    }

    /**
     * Replaces a CarePlan's content with manually supplied text, marking it uploaded.
     * Exactly one of `content` (pasted text) or `file` must be provided.
     */
    @Transactional
    public OrderResponse uploadCarePlan(Long id, String content, MultipartFile file) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        CarePlan carePlan = carePlanRepository.findByOrderId(id)
                .orElseThrow(() -> new ValidationError("CAREPLAN_NOT_FOUND", "CarePlan not found"));

        ensureCarePlanNotBusy(carePlan);

        String effectiveContent = extractUploadedContent(content, file);

        carePlan.setContent(effectiveContent);
        carePlan.setStatus(CarePlan.Status.COMPLETED);
        carePlan.setErrorMessage(null);
        carePlan.setUploaded(true);
        carePlanRepository.save(carePlan);

        return orderMapper.toResponse(order, carePlan);
    }

    private String extractUploadedContent(String content, MultipartFile file) {
        boolean hasText = content != null && !content.isBlank();
        boolean hasFile = file != null && !file.isEmpty();

        if (hasText == hasFile) {
            // Both true (ambiguous) or both false (nothing provided) — either way, reject.
            throw new ValidationError(
                    "CAREPLAN_UPLOAD_INVALID",
                    "Provide either pasted text (content) or a file, not both or neither");
        }

        if (hasFile) {
            try {
                return new String(file.getBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new ValidationError("CAREPLAN_UPLOAD_UNREADABLE", "Could not read the uploaded file");
            }
        }
        return content;
    }

    private boolean sameText(String left, String right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    /** Last whitespace-delimited token of a name, used as the surname for similar-name matching. */
    private static String lastNameToken(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String[] parts = name.trim().split("\\s+");
        return parts[parts.length - 1];
    }

    private Provider newProvider(CreateOrderRequest request) {
        Provider provider = new Provider();
        provider.setName(request.getProviderName());
        provider.setNpi(request.getProviderNpi());
        return provider;
    }

    private Patient newPatient(CreateOrderRequest request) {
        Patient patient = new Patient();
        patient.setFirstName(request.getPatientFirstName());
        patient.setLastName(request.getPatientLastName());
        patient.setMrn(request.getPatientMrn());
        patient.setDateOfBirth(request.getPatientDateOfBirth());
        patient.setSex(request.getPatientSex());
        patient.setWeightKg(request.getPatientWeightKg());
        patient.setAllergies(request.getPatientAllergies());
        return patient;
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


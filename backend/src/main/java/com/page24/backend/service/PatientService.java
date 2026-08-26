package com.page24.backend.service;

import com.page24.backend.dto.CreatePatientRequest;
import com.page24.backend.dto.PatientMapper;
import com.page24.backend.dto.PatientDetailMapper;
import com.page24.backend.dto.PatientDetailResponse;
import com.page24.backend.dto.PatientHistoryOrderResponse;
import com.page24.backend.dto.PagedPatientResponse;
import com.page24.backend.dto.PatientOrdersResponse;
import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.Order;
import com.page24.backend.dto.PatientResponse;
import com.page24.backend.dto.UpdatePatientRequest;
import com.page24.backend.dto.UpdatePatientResponse;
import com.page24.backend.entity.Patient;
import com.page24.backend.exception.PatientDuplicateException;
import com.page24.backend.exception.PatientMrnNotFoundException;
import com.page24.backend.exception.PatientNotFoundException;
import com.page24.backend.exception.PatientMrnModificationException;
import com.page24.backend.exception.PatientHasActiveOrdersException;
import com.page24.backend.exception.ValidationError;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.repository.OrderRepository;
import com.page24.backend.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PatientService {

    private final PatientRepository patientRepository;
    private final PatientMapper patientMapper;
    private final OrderRepository orderRepository;
    private final CarePlanRepository carePlanRepository;
    private final PatientDetailMapper patientDetailMapper;

    @Transactional
    public PatientResponse createPatient(CreatePatientRequest request) {
        patientRepository.findFirstByFirstNameIgnoreCaseAndLastNameIgnoreCaseAndDateOfBirth(
                        request.getFirstName(),
                        request.getLastName(),
                        request.getDateOfBirth()
                )
                .ifPresent(existingPatient -> {
                    throw new PatientDuplicateException(
                            "A patient with the same name and date of birth already exists",
                            existingPatient.getId()
                    );
                });

        patientRepository.findByMrn(request.getMrn())
                .ifPresent(existingPatient -> {
                    throw new PatientDuplicateException(
                            "A patient with the same MRN already exists",
                            existingPatient.getId()
                    );
                });

        Patient patient = new Patient();
        patient.setFirstName(request.getFirstName());
        patient.setLastName(request.getLastName());
        patient.setMrn(request.getMrn());
        patient.setDateOfBirth(request.getDateOfBirth());
        patient.setSex(request.getSex());
        patient.setWeightKg(request.getWeightKg());
        patient.setAllergies(request.getAllergies());
        patient.setPrimaryDiagnosis(request.getPrimaryDiagnosis());
        patient.setAdditionalDiagnoses(
                request.getAdditionalDiagnoses() == null
                        ? new ArrayList<>()
                        : new ArrayList<>(request.getAdditionalDiagnoses())
        );

        Patient savedPatient = patientRepository.save(patient);
        return patientMapper.toResponse(savedPatient);
    }

    @Transactional(readOnly = true)
    public PagedPatientResponse getPatients(int page, int pageSize, String search) {
        if (page < 1) {
            throw new ValidationError("INVALID_PAGE", "page must be greater than or equal to 1");
        }
        if (pageSize < 1) {
            throw new ValidationError("INVALID_PAGE_SIZE", "page_size must be greater than or equal to 1");
        }

        PageRequest pageRequest = PageRequest.of(
                page - 1,
                pageSize,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"))
        );
        String normalizedSearch = normalizeBlankToNull(search);
        Page<Patient> patients = normalizedSearch == null
                ? patientRepository.findAll(pageRequest)
                : patientRepository.findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(
                        normalizedSearch,
                        normalizedSearch,
                        pageRequest
                );

        return new PagedPatientResponse(
                patients.getTotalElements(),
                page,
                pageSize,
                patients.getContent().stream().map(patientMapper::toListItemResponse).toList()
        );
    }

    @Transactional
    public UpdatePatientResponse updatePatient(Long id, UpdatePatientRequest request) {
        Patient patient = patientRepository.findById(id)
                .orElseThrow(PatientNotFoundException::new);

        if (request.isMrnProvided()) {
            throw new PatientMrnModificationException();
        }

        if (request.getFirstName() != null) {
            patient.setFirstName(request.getFirstName());
        }
        if (request.getLastName() != null) {
            patient.setLastName(request.getLastName());
        }
        if (request.getDateOfBirth() != null) {
            patient.setDateOfBirth(request.getDateOfBirth());
        }
        if (request.getSex() != null) {
            patient.setSex(request.getSex());
        }
        if (request.getWeightKg() != null) {
            patient.setWeightKg(request.getWeightKg());
        }
        if (request.getAllergies() != null) {
            patient.setAllergies(request.getAllergies());
        }
        if (request.getPrimaryDiagnosis() != null) {
            patient.setPrimaryDiagnosis(request.getPrimaryDiagnosis());
        }
        if (request.getAdditionalDiagnoses() != null) {
            patient.setAdditionalDiagnoses(new ArrayList<>(request.getAdditionalDiagnoses()));
        }

        Patient updatedPatient = patientRepository.saveAndFlush(patient);
        return patientMapper.toUpdateResponse(updatedPatient);
    }

    @Transactional
    public void deletePatient(Long id) {
        Patient patient = patientRepository.findById(id)
                .orElseThrow(PatientNotFoundException::new);

        List<Long> activeOrderIds = carePlanRepository.findOrderIdsByPatientIdAndStatusIn(
                id,
                List.of(CarePlan.Status.PENDING, CarePlan.Status.PROCESSING)
        );
        if (!activeOrderIds.isEmpty()) {
            throw new PatientHasActiveOrdersException(activeOrderIds);
        }

        // A Patient is referenced by Order, and an Order is referenced by CarePlan.
        // Delete from the dependent tables first to preserve foreign-key integrity.
        List<Order> orders = orderRepository.findByPatient(patient);
        if (!orders.isEmpty()) {
            carePlanRepository.deleteAll(carePlanRepository.findByOrderIn(orders));
            carePlanRepository.flush();
            orderRepository.deleteAll(orders);
            orderRepository.flush();
        }

        patientRepository.delete(patient);
    }

    @Transactional(readOnly = true)
    public PatientOrdersResponse getPatientOrders(Long id) {
        Patient patient = patientRepository.findById(id)
                .orElseThrow(PatientNotFoundException::new);

        List<Order> orders = orderRepository.findByPatient(patient).stream()
                .sorted(Comparator.comparing(Order::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        Map<Long, CarePlan> carePlansByOrderId = findCarePlansByOrderId(orders);

        List<com.page24.backend.dto.PatientOrderSummaryResponse> orderSummaries = orders.stream()
                .map(order -> patientDetailMapper.toOrderSummary(order, carePlansByOrderId.get(order.getId())))
                .toList();
        String patientName = String.format("%s %s", patient.getFirstName(), patient.getLastName()).trim();

        return new PatientOrdersResponse(patient.getId(), patientName, orderSummaries);
    }

    @Transactional(readOnly = true)
    public List<PatientHistoryOrderResponse> getPatientHistory(Long id) {
        Patient patient = patientRepository.findById(id)
                .orElseThrow(PatientNotFoundException::new);

        List<Order> orders = orderRepository.findByPatientOrderByCreatedAtDesc(patient);
        Map<Long, CarePlan> carePlansByOrderId = findCarePlansByOrderId(orders);

        return orders.stream()
                .map(order -> patientDetailMapper.toHistoryResponse(order, carePlansByOrderId.get(order.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public PatientDetailResponse getPatientById(Long id) {
        Patient patient = patientRepository.findById(id)
                .orElseThrow(PatientNotFoundException::new);

        return toPatientDetailResponse(patient);
    }

    @Transactional(readOnly = true)
    public PatientDetailResponse getPatientByMrn(String mrn) {
        Patient patient = patientRepository.findByMrn(mrn)
                .orElseThrow(PatientMrnNotFoundException::new);

        return toPatientDetailResponse(patient);
    }

    private PatientDetailResponse toPatientDetailResponse(Patient patient) {

        List<Order> orders = orderRepository.findByPatient(patient).stream()
                .sorted(Comparator.comparing(Order::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        Map<Long, CarePlan> carePlansByOrderId = findCarePlansByOrderId(orders);

        List<String> medicationHistory = orders.stream()
                .map(Order::getMedicationHistory)
                .filter(history -> history != null && !history.isBlank())
                .flatMap(history -> history.lines())
                .map(String::trim)
                .filter(history -> !history.isEmpty())
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(LinkedHashSet::new),
                        ArrayList::new
                ));

        return patientDetailMapper.toResponse(patient, medicationHistory, orders, carePlansByOrderId);
    }

    private String normalizeBlankToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private Map<Long, CarePlan> findCarePlansByOrderId(List<Order> orders) {
        if (orders.isEmpty()) {
            return Map.of();
        }
        return carePlanRepository.findByOrderIn(orders).stream()
                .collect(Collectors.toMap(carePlan -> carePlan.getOrder().getId(), carePlan -> carePlan));
    }
}

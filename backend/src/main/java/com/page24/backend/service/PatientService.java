package com.page24.backend.service;

import com.page24.backend.dto.CreatePatientRequest;
import com.page24.backend.dto.PatientMapper;
import com.page24.backend.dto.PatientDetailMapper;
import com.page24.backend.dto.PatientDetailResponse;
import com.page24.backend.dto.PatientHistoryOrderResponse;
import com.page24.backend.dto.PagedPatientResponse;
import com.page24.backend.dto.PatientOrdersResponse;
import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.MedicationHistory;
import com.page24.backend.entity.Order;
import com.page24.backend.dto.PatientResponse;
import com.page24.backend.dto.UpdatePatientRequest;
import com.page24.backend.dto.UpdatePatientResponse;
import com.page24.backend.entity.Patient;
import com.page24.backend.entity.PatientDiagnosis;
import com.page24.backend.exception.PatientDuplicateException;
import com.page24.backend.exception.PatientMrnNotFoundException;
import com.page24.backend.exception.PatientNotFoundException;
import com.page24.backend.exception.PatientMrnModificationException;
import com.page24.backend.exception.PatientHasActiveOrdersException;
import com.page24.backend.exception.PatientListPageNotFoundException;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.repository.MedicationHistoryRepository;
import com.page24.backend.repository.OrderRepository;
import com.page24.backend.repository.PatientDiagnosisRepository;
import com.page24.backend.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PatientService {

    private static final int PATIENT_LIST_PAGE_SIZE = 20;

    private final PatientRepository patientRepository;
    private final PatientMapper patientMapper;
    private final OrderRepository orderRepository;
    private final CarePlanRepository carePlanRepository;
    private final PatientDetailMapper patientDetailMapper;
    private final PatientDiagnosisRepository patientDiagnosisRepository;
    private final MedicationHistoryRepository medicationHistoryRepository;

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
        patient.setPrimaryDiagnosisDescription(request.getPrimaryDiagnosisDescription());
        patient.setAdditionalDiagnoses(
                request.getAdditionalDiagnoses() == null
                        ? new ArrayList<>()
                        : new ArrayList<>(request.getAdditionalDiagnoses())
        );

        Patient savedPatient = patientRepository.save(patient);
        return patientMapper.toResponse(savedPatient);
    }

    @Transactional(readOnly = true)
    public PagedPatientResponse getPatients(String requestedPage, String baseUrl) {
        int page = "last".equals(requestedPage.trim()) ? lastPage() : parsePage(requestedPage);

        PageRequest pageRequest = PageRequest.of(
                page - 1,
                PATIENT_LIST_PAGE_SIZE,
                Sort.by(Sort.Direction.ASC, "lastName").and(Sort.by(Sort.Direction.ASC, "firstName"))
        );
        Page<Patient> patients = patientRepository.findAll(pageRequest);

        if (page > 1 && patients.isEmpty()) {
            throw new PatientListPageNotFoundException();
        }

        return new PagedPatientResponse(
                patients.getTotalElements(),
                patients.hasNext() ? pageUrl(baseUrl, page + 1) : null,
                patients.hasPrevious() ? pageUrl(baseUrl, page - 1) : null,
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

        patientDiagnosisRepository.deleteByPatient(patient);
        medicationHistoryRepository.deleteByPatient(patient);

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
        List<PatientDiagnosis> diagnoses = patientDiagnosisRepository.findByPatientOrderByCreatedAtAsc(patient);
        List<MedicationHistory> medicationHistory = medicationHistoryRepository.findByPatientOrderByCreatedAtAsc(patient);
        return patientDetailMapper.toResponse(patient, diagnoses, medicationHistory);
    }

    private int parsePage(String requestedPage) {
        try {
            int page = Integer.parseInt(requestedPage.trim());
            if (page < 1) {
                throw new PatientListPageNotFoundException();
            }
            return page;
        } catch (NumberFormatException ex) {
            throw new PatientListPageNotFoundException();
        }
    }

    private String pageUrl(String baseUrl, int page) {
        return baseUrl + "?page=" + page;
    }

    private int lastPage() {
        long totalPatients = patientRepository.count();
        return Math.max(1, Math.toIntExact(
                (totalPatients + PATIENT_LIST_PAGE_SIZE - 1) / PATIENT_LIST_PAGE_SIZE
        ));
    }

    private Map<Long, CarePlan> findCarePlansByOrderId(List<Order> orders) {
        if (orders.isEmpty()) {
            return Map.of();
        }
        return carePlanRepository.findByOrderIn(orders).stream()
                .collect(Collectors.toMap(carePlan -> carePlan.getOrder().getId(), carePlan -> carePlan));
    }
}

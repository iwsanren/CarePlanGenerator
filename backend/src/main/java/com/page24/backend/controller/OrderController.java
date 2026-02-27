package com.page24.backend.controller;

import com.page24.backend.dto.CreateOrderRequest;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.entity.*;
import com.page24.backend.repository.*;
import com.page24.backend.service.LLMService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

@RestController // 处理 REST API， 确保返回将对象序列化成json
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final PatientRepository patientRepository;
    private final ProviderRepository providerRepository;
    private final OrderRepository orderRepository;
    private final CarePlanRepository carePlanRepository;
    private final LLMService llmService;

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@RequestBody CreateOrderRequest request) {
        // 1. 找到或创建 Patient
        Patient patient = patientRepository.findByMrn(request.getPatientMrn())
                .orElseGet(() -> {
                    Patient newPatient = new Patient();
                    newPatient.setFirstName(request.getPatientFirstName());
                    newPatient.setLastName(request.getPatientLastName());
                    newPatient.setMrn(request.getPatientMrn());
                    newPatient.setDateOfBirth(request.getPatientDateOfBirth());
                    return patientRepository.save(newPatient);
                });

        // 2. 找到或创建 Provider
        Provider provider = providerRepository.findByNpi(request.getProviderNpi())
                .orElseGet(() -> {
                    Provider newProvider = new Provider();
                    newProvider.setName(request.getProviderName());
                    newProvider.setNpi(request.getProviderNpi());
                    return providerRepository.save(newProvider);
                });

        // 3. 创建 Order
        Order order = new Order();
        order.setPatient(patient);
        order.setProvider(provider);
        order.setMedicationName(request.getMedicationName());
        order.setPrimaryDiagnosis(request.getPrimaryDiagnosis());
        order.setAdditionalDiagnosis(request.getAdditionalDiagnosis());
        order.setMedicationHistory(request.getMedicationHistory());
        order.setPatientRecords(request.getPatientRecords());
        order = orderRepository.save(order);

        // 4. 创建 CarePlan，状态为 PENDING
        CarePlan carePlan = new CarePlan();
        carePlan.setOrder(order);
        carePlan.setStatus(CarePlan.Status.PENDING);
        carePlan = carePlanRepository.save(carePlan);

        // 5. 同步调用 LLM 生成 care plan（这里会等待，用户会感觉到慢）
        try {
            carePlan.setStatus(CarePlan.Status.PROCESSING);
            carePlan = carePlanRepository.save(carePlan);

            // 构建患者信息
            String patientInfo = buildPatientInfo(patient, order);

            // 调用 LLM（这里会阻塞 10-30 秒）
            String carePlanContent = llmService.generateCarePlan(patientInfo);

            carePlan.setContent(carePlanContent);
            carePlan.setStatus(CarePlan.Status.COMPLETED);
            carePlan = carePlanRepository.save(carePlan);

        } catch (Exception e) {
            carePlan.setStatus(CarePlan.Status.FAILED);
            carePlan.setContent("Error: " + e.getMessage());
            carePlan = carePlanRepository.save(carePlan);
        }

        // 6. 返回结果
        return ResponseEntity.ok(toResponse(order, carePlan));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        CarePlan carePlan = carePlanRepository.findByOrderId(id)
                .orElseThrow(() -> new RuntimeException("CarePlan not found"));

        return ResponseEntity.ok(toResponse(order, carePlan));
    }

    @GetMapping
    public ResponseEntity<List<OrderResponse>> getAllOrders() {
        List<Order> orders = orderRepository.findAll();
        List<OrderResponse> responses = orders.stream()
                .map(order -> {
                    CarePlan carePlan = carePlanRepository.findByOrderId(order.getId()).orElse(null);
                    return toResponse(order, carePlan);
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    // 搜索功能：根据患者名字或 MRN 搜索
    @GetMapping("/search")
    public ResponseEntity<List<OrderResponse>> searchOrders(
            @RequestParam(required = false) String patientName,
            @RequestParam(required = false) String mrn) {

        List<Order> orders;

        if (mrn != null && !mrn.isEmpty()) {
            // 根据 MRN 搜索
            Patient patient = patientRepository.findByMrn(mrn).orElse(null);
            if (patient != null) {
                orders = orderRepository.findByPatient(patient);
            } else {
                orders = List.of();
            }
        } else if (patientName != null && !patientName.isEmpty()) {
            // 根据患者名字搜索（支持模糊搜索）
            List<Patient> patients = patientRepository.findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(
                    patientName, patientName);
            orders = patients.stream()
                    .flatMap(patient -> orderRepository.findByPatient(patient).stream())
                    .collect(Collectors.toList());
        } else {
            // 如果没有提供搜索条件，返回所有订单
            orders = orderRepository.findAll();
        }

        List<OrderResponse> responses = orders.stream()
                .map(order -> {
                    CarePlan carePlan = carePlanRepository.findByOrderId(order.getId()).orElse(null);
                    return toResponse(order, carePlan);
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    // 下载功能：下载 CarePlan 为文本文件
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> downloadCarePlan(@PathVariable Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        CarePlan carePlan = carePlanRepository.findByOrderId(id)
                .orElseThrow(() -> new RuntimeException("CarePlan not found"));

        if (carePlan.getStatus() != CarePlan.Status.COMPLETED) {
            throw new RuntimeException("CarePlan is not completed yet");
        }

        // 构建文件内容
        String content = buildDownloadContent(order, carePlan);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        // 设置文件名：CarePlan_PatientName_OrderId.txt
        String filename = String.format("CarePlan_%s_%s_%d.txt",
                order.getPatient().getFirstName(),
                order.getPatient().getLastName(),
                order.getId());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.TEXT_PLAIN)
                .contentLength(bytes.length)
                .body(bytes);
    }

    private String buildPatientInfo(Patient patient, Order order) {
        return String.format("""
                Name: %s %s
                MRN: %s
                DOB: %s
                Medication: %s
                Primary Diagnosis: %s
                Additional Diagnoses: %s
                Medication History: %s
                Patient Records: %s
                """,
                patient.getFirstName(),
                patient.getLastName(),
                patient.getMrn(),
                patient.getDateOfBirth(),
                order.getMedicationName(),
                order.getPrimaryDiagnosis(),
                order.getAdditionalDiagnosis(),
                order.getMedicationHistory(),
                order.getPatientRecords()
        );
    }

    private OrderResponse toResponse(Order order, CarePlan carePlan) {
        OrderResponse response = new OrderResponse();
        response.setId(order.getId());
        response.setPatientId(order.getPatient().getId());
        response.setProviderId(order.getProvider().getId());
        response.setMedicationName(order.getMedicationName());

        if (carePlan != null) {
            response.setStatus(carePlan.getStatus().name());
            // 只有在 COMPLETED 状态时才返回内容
            if (carePlan.getStatus() == CarePlan.Status.COMPLETED) {
                response.setCarePlanContent(carePlan.getContent());
            }
        } else {
            response.setStatus("PENDING");
        }

        return response;
    }

    private String buildDownloadContent(Order order, CarePlan carePlan) {
        Patient patient = order.getPatient();
        Provider provider = order.getProvider();

        return String.format("""
                ================================================================================
                                            CARE PLAN
                ================================================================================
                
                PATIENT INFORMATION
                --------------------------------------------------------------------------------
                Name: %s %s
                MRN: %s
                Date of Birth: %s
                
                PROVIDER INFORMATION
                --------------------------------------------------------------------------------
                Provider: %s
                NPI: %s
                
                ORDER INFORMATION
                --------------------------------------------------------------------------------
                Order ID: %d
                Medication: %s
                Primary Diagnosis: %s
                Additional Diagnoses: %s
                
                CARE PLAN CONTENT
                ================================================================================
                %s
                
                ================================================================================
                Generated on: %s
                Status: %s
                ================================================================================
                """,
                patient.getFirstName(),
                patient.getLastName(),
                patient.getMrn(),
                patient.getDateOfBirth(),
                provider.getName(),
                provider.getNpi(),
                order.getId(),
                order.getMedicationName(),
                order.getPrimaryDiagnosis(),
                order.getAdditionalDiagnosis(),
                carePlan.getContent(),
                carePlan.getUpdatedAt(),
                carePlan.getStatus()
        );
    }
}


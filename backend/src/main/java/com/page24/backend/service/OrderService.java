package com.page24.backend.service;

import com.page24.backend.dto.CreateOrderRequest;
import com.page24.backend.dto.OrderMapper;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.entity.*;
import com.page24.backend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * OrderService - 业务逻辑层
 *
 * 职责：所有和"业务"相关的判断和操作
 *   - 找或创建 Patient / Provider
 *   - 创建 Order、创建 CarePlan
 *   - 把任务放进 Redis 队列
 *   - 搜索订单
 *   - 拼装下载文件内容
 *
 * 原来这些逻辑全部在 OrderController.java 里，现在搬到这里。
 * Controller 只需要调用 Service 的方法，拿到结果返回给前端。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final PatientRepository patientRepository;
    private final ProviderRepository providerRepository;
    private final OrderRepository orderRepository;
    private final CarePlanRepository carePlanRepository;
    private final QueueService queueService;
    private final OrderMapper orderMapper;

    /**
     * 创建订单：找或创建 Patient/Provider，创建 Order 和 CarePlan，放入队列
     *
     * 原来在 OrderController 第 28-72 行的 createOrder() 方法体
     */
    public OrderResponse createOrder(CreateOrderRequest request) {
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

        // 5. 把任务放进 Redis 队列（Day 4 异步改进）
        queueService.enqueue(carePlan.getId());

        return orderMapper.toResponse(order, carePlan);
    }

    /**
     * 根据订单 ID 查询订单状态和 CarePlan 内容
     *
     * 原来在 OrderController 第 77-86 行（getCarePlanStatus）
     * 和第 88-97 行（getOrder）——两个方法逻辑完全一样，合并成一个
     */
    public OrderResponse getOrderById(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        CarePlan carePlan = carePlanRepository.findByOrderId(id)
                .orElseThrow(() -> new RuntimeException("CarePlan not found"));

        return orderMapper.toResponse(order, carePlan);
    }

    /**
     * 查询所有订单
     *
     * 原来在 OrderController 第 99-109 行的 getAllOrders() 方法体
     */
    public List<OrderResponse> getAllOrders() {
        List<Order> orders = orderRepository.findAll();
        return orders.stream()
                .map(order -> {
                    CarePlan carePlan = carePlanRepository.findByOrderId(order.getId()).orElse(null);
                    return orderMapper.toResponse(order, carePlan);
                })
                .collect(Collectors.toList());
    }

    /**
     * 根据患者名字或 MRN 搜索订单
     *
     * 原来在 OrderController 第 112-145 行的 searchOrders() 方法体
     */
    public List<OrderResponse> searchOrders(String patientName, String mrn) {
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
            List<Patient> patients = patientRepository
                    .findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(
                            patientName, patientName);
            orders = patients.stream()
                    .flatMap(patient -> orderRepository.findByPatient(patient).stream())
                    .collect(Collectors.toList());
        } else {
            // 没有搜索条件，返回所有订单
            orders = orderRepository.findAll();
        }

        return orders.stream()
                .map(order -> {
                    CarePlan carePlan = carePlanRepository.findByOrderId(order.getId()).orElse(null);
                    return orderMapper.toResponse(order, carePlan);
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取下载内容：验证状态 + 拼装文件文字
     *
     * 原来在 OrderController 第 148-176 行（downloadCarePlan 方法体）
     * 和第 226-277 行（buildDownloadContent 私有方法）
     *
     * 返回 byte[]，Controller 只负责设置 HTTP 响应头
     */
    public byte[] getDownloadBytes(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        CarePlan carePlan = carePlanRepository.findByOrderId(id)
                .orElseThrow(() -> new RuntimeException("CarePlan not found"));

        if (carePlan.getStatus() != CarePlan.Status.COMPLETED) {
            throw new RuntimeException("CarePlan is not completed yet");
        }

        String content = buildDownloadContent(order, carePlan);
        return content.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 根据订单 ID 获取文件名（供 Controller 设置 Content-Disposition 头用）
     */
    public String getDownloadFilename(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        return String.format("CarePlan_%s_%s_%d.txt",
                order.getPatient().getFirstName(),
                order.getPatient().getLastName(),
                order.getId());
    }

    /**
     * 拼装 CarePlan 下载文件的文字内容
     *
     * 原来在 OrderController 第 226-277 行的 buildDownloadContent() 私有方法
     */
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


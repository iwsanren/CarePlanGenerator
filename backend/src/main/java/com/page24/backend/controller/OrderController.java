package com.page24.backend.controller;

import com.page24.backend.dto.CreateOrderRequest;
import com.page24.backend.dto.CarePlanStatusResponse;
import com.page24.backend.dto.CarePlanDownload;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.dto.PagedOrderResponse;
import com.page24.backend.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.nio.charset.StandardCharsets;

/**
 * OrderController - HTTP 请求/响应层
 *
 * 职责：只管"收"和"发"
 *   - 读取请求参数
 *   - 调用 OrderService 拿结果
 *   - 设置 HTTP 状态码和响应头，返回给前端
 *
 * 不做任何业务判断，不直接操作数据库，不知道 Redis 是什么。
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        OrderResponse response = orderService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<CarePlanStatusResponse> getCarePlanStatus(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getCarePlanStatus(id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getOrderById(id));
    }

    @GetMapping({"", "/"})
    public ResponseEntity<PagedOrderResponse> getOrders(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(name = "patient_id", required = false) Long patientId,
            @RequestParam(name = "provider_id", required = false) Long providerId,
            @RequestParam(name = "patient_name", required = false) String patientName) {
        return ResponseEntity.ok(orderService.getOrders(
                page, pageSize, status, patientId, providerId, patientName));
    }

    @GetMapping("/search")
    public ResponseEntity<List<OrderResponse>> searchOrders(
            @RequestParam(required = false) String patientName,
            @RequestParam(required = false) String mrn) {
        return ResponseEntity.ok(orderService.searchOrders(patientName, mrn));
    }

    @GetMapping("/{id}/careplan/download")
    public ResponseEntity<byte[]> downloadCarePlan(@PathVariable Long id) {
        CarePlanDownload download = orderService.downloadCarePlan(id);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.filename() + "\"")
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .contentLength(download.content().length)
                .body(download.content());
    }
}

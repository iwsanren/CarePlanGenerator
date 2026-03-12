package com.page24.backend.controller;

import com.page24.backend.dto.CreateOrderRequest;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<?> createOrder(@RequestBody CreateOrderRequest request) {
        try {
            OrderResponse response = orderService.createOrder(request);

            if ("WARNING".equals(response.getResultType()) && Boolean.TRUE.equals(response.getRequiresConfirm())) {
                return ResponseEntity.ok(response);
            }

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (ResponseStatusException ex) {
            Map<String, Object> body = new HashMap<>();
            body.put("status", ex.getStatusCode().value());
            body.put("error", ex.getReason());
            return ResponseEntity.status(ex.getStatusCode()).body(body);
        }
    }

    // Day 6: Polling 状态查询 API
    @GetMapping("/{id}/status")
    public ResponseEntity<OrderResponse> getCarePlanStatus(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getOrderById(id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getOrderById(id));
    }

    @GetMapping
    public ResponseEntity<List<OrderResponse>> getAllOrders() {
        return ResponseEntity.ok(orderService.getAllOrders());
    }

    @GetMapping("/search")
    public ResponseEntity<List<OrderResponse>> searchOrders(
            @RequestParam(required = false) String patientName,
            @RequestParam(required = false) String mrn) {
        return ResponseEntity.ok(orderService.searchOrders(patientName, mrn));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> downloadCarePlan(@PathVariable Long id) {
        byte[] bytes = orderService.getDownloadBytes(id);
        String filename = orderService.getDownloadFilename(id);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.TEXT_PLAIN)
                .contentLength(bytes.length)
                .body(bytes);
    }
}

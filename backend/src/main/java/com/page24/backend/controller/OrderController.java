package com.page24.backend.controller;

import com.page24.backend.dto.CreateOrderRequest;
import com.page24.backend.dto.CarePlanStatusResponse;
import com.page24.backend.dto.CarePlanDownload;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.dto.PagedOrderResponse;
import com.page24.backend.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.nio.charset.StandardCharsets;

/**
 * OrderController - HTTP request and response layer.
 *
 * Responsibilities are limited to HTTP coordination:
 *   - Read request parameters.
 *   - Delegate work to OrderService.
 *   - Set HTTP status codes and headers before returning the response.
 *
 * It contains no business rules, does not access the database directly, and is unaware of Redis.
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Create orders and manage their care plans")
public class OrderController {

    private final OrderService orderService;

    @Operation(summary = "Create an order",
            description = "Validates input, runs duplicate detection and queues care plan generation. "
                    + "Possible duplicates return a warning and must be resubmitted with \"confirm\": true in the request body.")
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        OrderResponse response = orderService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Get care plan generation status", description = "Lightweight endpoint intended for polling.")
    @GetMapping("/{id}/status")
    public ResponseEntity<CarePlanStatusResponse> getCarePlanStatus(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getCarePlanStatus(id));
    }

    @Operation(summary = "Get order details")
    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getOrderById(id));
    }

    @Operation(summary = "List orders (paginated)",
            description = "Optional filters: status, patient_id, provider_id, patient_name.")
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

    @Operation(summary = "Search orders by patient name or MRN")
    @GetMapping("/search")
    public ResponseEntity<List<OrderResponse>> searchOrders(
            @RequestParam(required = false) String patientName,
            @RequestParam(required = false) String mrn) {
        return ResponseEntity.ok(orderService.searchOrders(patientName, mrn));
    }

    @Operation(summary = "Regenerate a care plan",
            description = "PHARMACIST only. Returns 409 while a generation is already PROCESSING.")
    @PostMapping("/{id}/regenerate")
    public ResponseEntity<OrderResponse> regenerateCarePlan(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.regenerateCarePlan(id));
    }

    @Operation(summary = "Upload a care plan manually",
            description = "PHARMACIST only. Multipart form: supply text in 'content' or a file in 'file'.")
    @PostMapping(value = "/{id}/careplan/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<OrderResponse> uploadCarePlan(
            @PathVariable Long id,
            @RequestParam(required = false) String content,
            @RequestParam(required = false) MultipartFile file) {
        return ResponseEntity.ok(orderService.uploadCarePlan(id, content, file));
    }

    @Operation(summary = "Download a completed care plan",
            description = "Returns the care plan as a plain-text file download "
                    + "(Content-Disposition: attachment). Fails unless the care plan is COMPLETED.",
            responses = @ApiResponse(responseCode = "200", description = "Care plan text file",
                    content = @Content(mediaType = "text/plain", schema = @Schema(type = "string", format = "binary"))))
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

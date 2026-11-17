package com.page24.backend.controller;

import com.page24.backend.dto.CreateOrderRequest;
import com.page24.backend.dto.CarePlanStatusResponse;
import com.page24.backend.dto.CarePlanDownload;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.dto.PagedOrderResponse;
import com.page24.backend.exception.ApiErrorResponse;
import com.page24.backend.service.OrderListParams;
import com.page24.backend.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.Explode;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
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
                    + "Possible duplicates return a warning and must be resubmitted with "
                    + "\"confirm\": true in the request body.")
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

    @Operation(summary = "List orders (filter, sort, paginate)",
            description = "Every filter is optional and all given filters must match (AND). Text filters ignore "
                    + "case and look for the text anywhere in the value; % and _ are ordinary characters, not "
                    + "wildcards. Dates are UTC calendar days. Without sort the newest orders come first. "
                    + "A bad parameter returns 400 with an error code (for example INVALID_SORT, "
                    + "INVALID_DATE_RANGE, INVALID_PAGE_SIZE); the message never repeats the value sent.",
            parameters = @Parameter(name = "sort", in = ParameterIn.QUERY,
                    description = "Sort criterion \"field\" or \"field,asc\" or \"field,desc\"; repeat the parameter "
                            + "for more than one, the first has the highest priority, each field at most once. "
                            + "Fields: id, created_at, medication, patient_name (last name, then first name), "
                            + "provider_name, status (alphabetical). Rows that tie are ordered by id, newest "
                            + "first, so paging is stable.",
                    array = @ArraySchema(schema = @Schema(type = "string",
                            example = "created_at,desc")),
                    explode = Explode.TRUE),
            responses = {
                    @ApiResponse(responseCode = "200", description = "One page of orders; count is the total "
                            + "over all pages and a page past the end has empty results"),
                    @ApiResponse(responseCode = "400", description = "Invalid parameter (validation error)",
                            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
            })
    @GetMapping({"", "/"})
    public ResponseEntity<PagedOrderResponse> getOrders(
            @Parameter(description = "Page number, starting at 1", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "Rows per page, 1 to 100", example = "20")
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @Parameter(description = "Care plan status", schema = @Schema(
                    allowableValues = {"pending", "processing", "completed", "failed"}))
            @RequestParam(required = false) String status,
            @Parameter(description = "Patient id")
            @RequestParam(name = "patient_id", required = false) Long patientId,
            @Parameter(description = "Provider id")
            @RequestParam(name = "provider_id", required = false) Long providerId,
            @Parameter(description = "Part of the patient's first name, last name or \"first last\"; at most 100 "
                    + "characters")
            @RequestParam(name = "patient_name", required = false) String patientName,
            @Parameter(description = "Patient MRN, exact match")
            @RequestParam(required = false) String mrn,
            @Parameter(description = "Part of the medication name; at most 100 characters")
            @RequestParam(required = false) String medication,
            @Parameter(description = "Part of the provider's name; at most 100 characters")
            @RequestParam(name = "provider_name", required = false) String providerName,
            @Parameter(description = "First day to include (yyyy-MM-dd, UTC)", example = "2026-11-01")
            @RequestParam(name = "created_from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @Parameter(description = "Last day to include (yyyy-MM-dd, UTC); not before created_from",
                    example = "2026-11-30")
            @RequestParam(name = "created_to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            HttpServletRequest request) {
        // The raw values are read from the request on purpose: Spring would split "created_at,desc" at the comma
        // into two list elements, and the field and its direction belong together.
        String[] sortValues = request.getParameterValues("sort");
        OrderListParams params = OrderListParams.builder()
                .page(page)
                .pageSize(pageSize)
                .status(status)
                .patientId(patientId)
                .providerId(providerId)
                .patientName(patientName)
                .mrn(mrn)
                .medication(medication)
                .providerName(providerName)
                .createdFrom(createdFrom)
                .createdTo(createdTo)
                .sort(sortValues == null ? null : List.of(sortValues))
                .build();
        return ResponseEntity.ok(orderService.getOrders(params));
    }

    @Operation(summary = "Search orders by patient name or MRN (deprecated)",
            deprecated = true,
            description = "DEPRECATED: use GET /api/v1/orders with patient_name or mrn, which is paginated, "
                    + "sortable and combinable with other filters. This endpoint is unchanged for existing callers: "
                    + "it is not paginated and returns every match with its care plan. Filters: patient_name "
                    + "(part of a first or last name, case-insensitive) or mrn; mrn wins when both are given. "
                    + "patientName is the old spelling of patient_name and is kept for existing callers; "
                    + "patient_name wins when both are given.")
    @GetMapping("/search")
    public ResponseEntity<List<OrderResponse>> searchOrders(
            @RequestParam(name = "patient_name", required = false) String patientName,
            @Parameter(deprecated = true, description = "Old spelling of patient_name")
            @RequestParam(name = "patientName", required = false) String legacyPatientName,
            @RequestParam(required = false) String mrn) {
        String nameToSearch = patientName != null && !patientName.isBlank() ? patientName : legacyPatientName;
        return ResponseEntity.ok(orderService.searchOrders(nameToSearch, mrn));
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

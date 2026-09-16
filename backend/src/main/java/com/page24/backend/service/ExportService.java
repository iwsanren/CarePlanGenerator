package com.page24.backend.service;

import com.page24.backend.dto.ReportFile;
import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.Order;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds the single full-content export: every order, every column, including
 * the generated care plan text, no filters. This is a system-wide dump, which
 * is why it lives outside ReportController's filtered /reports/* endpoints and
 * instead matches the reference implementation's dedicated GET /export/.
 */
@Service
@RequiredArgsConstructor
public class ExportService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FILE_NAME_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    // Column order and names mirror the reference project's orders_export.csv sample exactly.
    private static final List<String> HEADER = List.of(
            "order_id",
            "order_date",
            "patient_mrn",
            "patient_first_name",
            "patient_last_name",
            "patient_date_of_birth",
            "provider_npi",
            "provider_name",
            "medication_name",
            "primary_diagnosis_code",
            "care_plan_status",
            "care_plan_content"
    );

    private final OrderRepository orderRepository;
    private final CarePlanRepository carePlanRepository;

    @Transactional(readOnly = true)
    public ReportFile exportAll() {
        List<Order> orders = orderRepository.findAll();
        Map<Long, CarePlan> carePlansByOrderId = carePlanRepository.findByOrderIn(orders).stream()
                .collect(Collectors.toMap(carePlan -> carePlan.getOrder().getId(), carePlan -> carePlan));

        StringBuilder csv = new StringBuilder();
        CsvWriter.appendRow(csv, HEADER);

        orders.stream()
                .sorted(Comparator.comparing(Order::getCreatedAt, Comparator.reverseOrder())
                        .thenComparing(Order::getId, Comparator.reverseOrder()))
                .forEach(order -> CsvWriter.appendRow(csv, toRow(order, carePlansByOrderId.get(order.getId()))));

        String filename = "orders_export_" + FILE_NAME_TIMESTAMP.format(LocalDateTime.now()) + ".csv";
        return new ReportFile(filename, csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private List<String> toRow(Order order, CarePlan carePlan) {
        return List.of(
                value(order.getId()),
                date(order.getCreatedAt().toLocalDate()),
                value(order.getPatient().getMrn()),
                value(order.getPatient().getFirstName()),
                value(order.getPatient().getLastName()),
                date(order.getPatient().getDateOfBirth()),   // optional since Phase 2 — may be blank
                value(order.getProvider().getNpi()),
                value(order.getProvider().getName()),
                value(order.getMedicationName()),
                value(order.getPrimaryDiagnosis()),
                carePlan == null ? "" : carePlan.getStatus().name().toLowerCase(Locale.ROOT),
                carePlan == null ? "" : value(carePlan.getContent())
        );
    }

    private String date(LocalDate value) {
        return value == null ? "" : DATE_FORMAT.format(value);
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }
}

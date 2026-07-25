package com.page24.backend.aws.lambda;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.page24.backend.entity.CarePlan;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.service.CarePlanGenerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQS Lambda handler for generating care plans.
 *
 * Handler setting in AWS Lambda:
 *   com.page24.backend.aws.lambda.GenerateCarePlanHandler::handleRequest
 *
 * Expected SQS message body:
 *   {"carePlanId": 123}
 */
@Slf4j
public class GenerateCarePlanHandler {

    private final ObjectMapper objectMapper;
    private final CarePlanGenerationService carePlanGenerationService;
    private final CarePlanRepository carePlanRepository;

    public GenerateCarePlanHandler() {
        ConfigurableApplicationContext context = LambdaSpringContext.getContext();
        this.objectMapper = context.getBean(ObjectMapper.class);
        this.carePlanGenerationService = context.getBean(CarePlanGenerationService.class);
        this.carePlanRepository = context.getBean(CarePlanRepository.class);
    }

    GenerateCarePlanHandler(ObjectMapper objectMapper,
                            CarePlanGenerationService carePlanGenerationService,
                            CarePlanRepository carePlanRepository) {
        this.objectMapper = objectMapper;
        this.carePlanGenerationService = carePlanGenerationService;
        this.carePlanRepository = carePlanRepository;
    }

    public Map<String, Object> handleRequest(Map<String, Object> event) {
        List<Map<String, String>> batchItemFailures = new ArrayList<>();

        for (Map<String, Object> record : extractRecords(event)) {
            String messageId = text(record, "messageId");

            try {
                Long carePlanId = extractCarePlanId(record);
                processCarePlan(carePlanId, messageId);
            } catch (Exception ex) {
                log.error(
                        "Failed to process SQS care plan message: messageId={}, errorType={}, errorMessage={}",
                        messageId,
                        ex.getClass().getSimpleName(),
                        ex.getMessage()
                );

                if (messageId == null || messageId.isBlank()) {
                    throw new IllegalArgumentException("SQS record is missing messageId", ex);
                }

                batchItemFailures.add(Map.of("itemIdentifier", messageId));
            }
        }

        return Map.of("batchItemFailures", batchItemFailures);
    }

    private void processCarePlan(Long carePlanId, String messageId) {
        CarePlan carePlan = carePlanRepository.findById(carePlanId)
                .orElseThrow(() -> new IllegalArgumentException("CarePlan not found: " + carePlanId));

        if (carePlan.getStatus() == CarePlan.Status.COMPLETED) {
            log.info("Skipping duplicate SQS message for completed CarePlan: carePlanId={}, messageId={}",
                    carePlanId, messageId);
            return;
        }

        carePlan.setStatus(CarePlan.Status.PROCESSING);
        carePlanRepository.save(carePlan);
        log.info("Started SQS care plan generation: carePlanId={}, messageId={}", carePlanId, messageId);

        carePlanGenerationService.generateWithRetry(carePlanId);

        CarePlan updatedCarePlan = carePlanRepository.findById(carePlanId)
                .orElseThrow(() -> new IllegalArgumentException("CarePlan not found after generation: " + carePlanId));

        if (updatedCarePlan.getStatus() == CarePlan.Status.FAILED) {
            throw new IllegalStateException("CarePlan generation failed after retries: " + carePlanId);
        }

        log.info("Finished SQS care plan generation: carePlanId={}, messageId={}", carePlanId, messageId);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractRecords(Map<String, Object> event) {
        Object recordsObject = event.get("Records");
        if (!(recordsObject instanceof List<?> rawRecords)) {
            throw new IllegalArgumentException("SQS event must contain a Records list");
        }

        List<Map<String, Object>> records = new ArrayList<>();
        for (Object rawRecord : rawRecords) {
            if (!(rawRecord instanceof Map<?, ?> rawRecordMap)) {
                throw new IllegalArgumentException("Each SQS record must be an object");
            }
            records.add((Map<String, Object>) rawRecordMap);
        }
        return records;
    }

    private Long extractCarePlanId(Map<String, Object> record) {
        String body = text(record, "body");
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("SQS message body is required");
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode carePlanIdNode = root.get("carePlanId");
            if (carePlanIdNode == null || carePlanIdNode.isNull()) {
                throw new IllegalArgumentException("SQS message body must contain carePlanId");
            }

            Long carePlanId = parsePositiveLong(carePlanIdNode, "carePlanId");
            log.debug("Parsed SQS care plan task: carePlanId={}", carePlanId);
            return carePlanId;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("SQS message body must be valid JSON");
        }
    }

    private Long parsePositiveLong(JsonNode node, String fieldName) {
        Long value;
        if (node.isIntegralNumber()) {
            value = node.longValue();
        } else if (node.isTextual()) {
            try {
                value = Long.parseLong(node.asText());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(fieldName + " must be a number");
            }
        } else {
            throw new IllegalArgumentException(fieldName + " must be a number");
        }

        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private String text(Map<String, Object> source, String fieldName) {
        Object value = source.get(fieldName);
        return value == null ? null : value.toString();
    }
}

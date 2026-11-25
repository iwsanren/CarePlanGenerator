package com.page24.backend.config;

import com.page24.backend.dto.CreateOrderRequest;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.entity.CarePlan;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.service.CarePlanQueue;
import com.page24.backend.service.CarePlanWorker;
import com.page24.backend.service.KafkaCarePlanQueue;
import com.page24.backend.service.OrderService;
import com.page24.backend.service.QueueService;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole application in kafka mode against a real broker, started eagerly as in production (the test profile's
 * lazy initialization is switched off, so a missing bean would fail the start). Needs Docker; skipped without it.
 */
@SpringBootTest(properties = {
        "careplan.queue.type=kafka",
        "spring.main.lazy-initialization=false",
        // the test profile excludes Redis auto-configuration; the real application has it, so allow it here
        "spring.autoconfigure.exclude=",
        "spring.datasource.url=jdbc:h2:mem:careplan_kafka_mode;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class QueueTypeKafkaStartupTest {

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private OrderService orderService;

    @Autowired
    private CarePlanRepository carePlanRepository;

    @Test
    @DisplayName("kafka mode creates the request and dead-letter topics with 3 partitions and 1 replica, and no others")
    void createsExactlyTheTwoTopics() throws Exception {
        try (Admin admin = Admin.create(Map.<String, Object>of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            Set<String> topics = admin.listTopics().names().get(30, TimeUnit.SECONDS);
            assertThat(topics).containsExactlyInAnyOrder("careplan.requested", "careplan.requested.dlt");

            Map<String, TopicDescription> described =
                    admin.describeTopics(topics).allTopicNames().get(30, TimeUnit.SECONDS);
            assertThat(described.values()).allSatisfy(topic -> {
                assertThat(topic.partitions()).hasSize(3);
                assertThat(topic.partitions()).allSatisfy(partition -> assertThat(partition.replicas()).hasSize(1));
            });
        }
    }

    @Test
    @DisplayName("kafka mode replaces the Redis queue and worker with the placeholder")
    void usesThePlaceholderInsteadOfTheRedisQueueAndWorker() {
        assertThat(context.getBean(CarePlanQueue.class)).isInstanceOf(KafkaCarePlanQueue.class);
        assertThat(context.getBeanNamesForType(QueueService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CarePlanWorker.class)).isEmpty();
    }

    @Test
    @WithMockUser(roles = "ASSISTANT")
    @DisplayName("kafka mode: an order is accepted and its care plan stays PENDING, because nothing processes it yet")
    void orderIsAcceptedButNotProcessed() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setPatientFirstName("Kafka");
        request.setPatientLastName("Startup");
        request.setPatientMrn("900001");
        request.setPatientDateOfBirth(LocalDate.of(1980, 1, 1));
        request.setProviderName("Dr Topic");
        request.setProviderNpi("1900000001");
        request.setMedicationName("IVIG");
        request.setPrimaryDiagnosis("G70.00");

        OrderResponse order = orderService.createOrder(request);

        List<CarePlan.Status> statuses = carePlanRepository.findByOrderId(order.getId()).stream()
                .map(CarePlan::getStatus)
                .toList();
        assertThat(statuses).containsExactly(CarePlan.Status.PENDING);
    }
}

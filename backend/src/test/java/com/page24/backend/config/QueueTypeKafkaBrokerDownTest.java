package com.page24.backend.config;

import com.page24.backend.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * kafka mode with no broker at all: topic creation fails and is logged, the application still starts. Nothing
 * listens on port 1; the short admin timeouts only keep the test quick.
 */
@SpringBootTest(properties = {
        "careplan.queue.type=kafka",
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.admin.properties[request.timeout.ms]=2000",
        "spring.kafka.admin.properties[default.api.timeout.ms]=3000",
        "spring.main.lazy-initialization=false",
        "spring.autoconfigure.exclude=",
        "spring.datasource.url=jdbc:h2:mem:careplan_kafka_down;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class QueueTypeKafkaBrokerDownTest {

    @Autowired
    private OrderService orderService;

    @Test
    @DisplayName("an unreachable broker does not stop the application from starting")
    void applicationStartsWithoutABroker() {
        assertThat(orderService).isNotNull();
    }
}

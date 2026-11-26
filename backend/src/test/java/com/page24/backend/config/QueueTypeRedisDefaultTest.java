package com.page24.backend.config;

import com.page24.backend.outbox.OutboxCleanup;
import com.page24.backend.outbox.OutboxRelay;
import com.page24.backend.outbox.OutboxRepository;
import com.page24.backend.outbox.OutboxWakeUpListener;
import com.page24.backend.service.CarePlanWorker;
import com.page24.backend.service.KafkaCarePlanQueue;
import com.page24.backend.service.CarePlanKafkaConsumer;
import com.page24.backend.service.CarePlanProcessor;
import com.page24.backend.service.CarePlanLlmCaller;
import com.page24.backend.service.CarePlanEventParser;
import com.page24.backend.service.CarePlanClaimRepository;
import com.page24.backend.service.CarePlanDeadLetterRecoverer;
import com.page24.backend.service.CarePlanSweeper;
import org.springframework.kafka.listener.DefaultErrorHandler;
import com.page24.backend.service.QueueService;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole application with careplan.queue.type left at its default: it must be wired as before Kafka was added.
 * Neither Redis nor Kafka is needed.
 */
@SpringBootTest(properties = "careplan.worker.enabled=true")
@ActiveProfiles("test")
class QueueTypeRedisDefaultTest {

    /**
     * enforceOverride: the test fails to start unless the application really defines a QueueService. The mock also
     * keeps the real worker, which this context enables, away from any Redis server.
     */
    @MockitoBean(enforceOverride = true)
    private QueueService queueService;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("default queue type -> the Redis queue and its worker, and not a single Kafka bean")
    void redisIsTheDefaultAndCreatesNoKafkaBean() {
        assertThat(context.getEnvironment().getProperty("careplan.queue.type")).isEqualTo("redis");
        assertThat(context.getBeanNamesForType(CarePlanWorker.class)).isNotEmpty();

        assertThat(context.getBeanNamesForType(KafkaCarePlanQueue.class)).isEmpty();
        assertThat(context.getBeanNamesForType(NewTopic.class)).isEmpty();
        assertThat(context.getBeanNamesForType(KafkaTemplate.class)).isEmpty();
        assertThat(context.getBeanNamesForType(KafkaAdmin.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ProducerFactory.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ConsumerFactory.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ProducerListener.class)).isEmpty();

        // nothing of the outbox either: no sender polling the table, no wake-up listener, no clean-up job
        assertThat(context.getBeanNamesForType(OutboxRepository.class)).isEmpty();
        assertThat(context.getBeanNamesForType(OutboxRelay.class)).isEmpty();
        assertThat(context.getBeanNamesForType(OutboxWakeUpListener.class)).isEmpty();
        assertThat(context.getBeanNamesForType(OutboxCleanup.class)).isEmpty();

        // and nothing of the consuming side
        assertThat(context.getBeanNamesForType(CarePlanKafkaConsumer.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CarePlanProcessor.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CarePlanLlmCaller.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CarePlanEventParser.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CarePlanClaimRepository.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CarePlanDeadLetterRecoverer.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CarePlanSweeper.class)).isEmpty();
        assertThat(context.getBeanNamesForType(DefaultErrorHandler.class)).isEmpty();
    }
}

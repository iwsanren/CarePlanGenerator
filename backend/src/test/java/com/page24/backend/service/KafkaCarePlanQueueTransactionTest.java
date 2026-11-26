package com.page24.backend.service;

import com.page24.backend.outbox.OutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The MANDATORY rule, checked through Spring's real transaction proxy (a plain {@code new} would ignore the
 * annotation). H2 only provides something to open a transaction on.
 */
class KafkaCarePlanQueueTransactionTest {

    private AnnotationConfigApplicationContext context;
    private CarePlanQueue queue;
    private OutboxRepository outbox;

    @BeforeEach
    void startContext() {
        context = new AnnotationConfigApplicationContext(TransactionalSetup.class);
        queue = context.getBean(CarePlanQueue.class);
        outbox = context.getBean(OutboxRepository.class);
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    @DisplayName("enqueue without a transaction is an error and writes nothing")
    void failsWithoutATransaction() {
        assertThatThrownBy(() -> queue.enqueue(1L)).isInstanceOf(IllegalTransactionStateException.class);

        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("enqueue inside the caller's transaction writes the row")
    void joinsTheCallersTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

        transaction.executeWithoutResult(status -> {
            assertThat(status.isNewTransaction()).isTrue();
            queue.enqueue(1L);
        });

        verify(outbox).insert(any(), anyLong(), anyString(), anyString(), anyString());
    }

    @Configuration
    @EnableTransactionManagement
    static class TransactionalSetup {

        @Bean
        PlatformTransactionManager transactionManager() {
            return new DataSourceTransactionManager(
                    new DriverManagerDataSource("jdbc:h2:mem:mandatory_rule", "sa", ""));
        }

        @Bean
        OutboxRepository outboxRepository() {
            return mock(OutboxRepository.class);
        }

        @Bean
        CarePlanQueue carePlanQueue(OutboxRepository outbox,
                                    org.springframework.context.ApplicationEventPublisher events) {
            return new KafkaCarePlanQueue(outbox, events, JsonMapper.builder().build(), "careplan.requested");
        }
    }
}

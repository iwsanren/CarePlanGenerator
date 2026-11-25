package com.page24.backend.aws.lambda;

import com.page24.backend.service.KafkaCarePlanQueue;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Lambda profile uses SQS whatever careplan.queue.type says: no Kafka bean may appear there. The context is
 * started like in {@link LambdaBatchExclusionTest}.
 */
class LambdaKafkaExclusionTest {

    private ConfigurableApplicationContext context;
    private String previousRegion;

    @BeforeEach
    void startLambdaContextAskingForKafka() {
        previousRegion = System.getProperty("aws.region");
        System.setProperty("aws.region", "us-east-1");

        context = new SpringApplicationBuilder(LambdaApplication.class)
                .profiles("lambda")
                .web(WebApplicationType.NONE)
                .run(
                        "--careplan.queue.type=kafka",
                        "--spring.datasource.url=jdbc:h2:mem:lambda_kafka_exclusion;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "--spring.datasource.driver-class-name=org.h2.Driver",
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--spring.jpa.hibernate.ddl-auto=create-drop",
                        "--spring.jpa.show-sql=false",
                        "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                        "--spring.flyway.enabled=false",
                        "--spring.main.lazy-initialization=true");
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
        if (previousRegion == null) {
            System.clearProperty("aws.region");
        } else {
            System.setProperty("aws.region", previousRegion);
        }
    }

    @Test
    @DisplayName("the lambda profile keeps SQS and gets no Kafka bean, even with careplan.queue.type=kafka")
    void lambdaProfileIgnoresKafka() {
        assertThat(context.getBeanNamesForType(SqsCarePlanQueue.class)).isNotEmpty();
        assertThat(context.getBeanNamesForType(KafkaCarePlanQueue.class)).isEmpty();
        assertThat(context.getBeanNamesForType(NewTopic.class)).isEmpty();
        assertThat(context.getBeanNamesForType(KafkaTemplate.class)).isEmpty();
        assertThat(context.getBeanNamesForType(KafkaAdmin.class)).isEmpty();
    }
}

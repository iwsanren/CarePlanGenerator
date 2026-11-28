package com.page24.backend.outbox;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reads what is really on a topic, with a consumer of its own, independently of the application. */
public final class KafkaTestReader {

    private KafkaTestReader() {
    }

    /** Every record on the topic at this moment, from the very beginning of each partition. */
    public static List<ConsumerRecord<String, String>> readAll(String bootstrapServers, String topic) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic, Duration.ofSeconds(30)).stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions, Duration.ofSeconds(30));

            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("Could not read " + topic + " to its end");
                }
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
            return records;
        }
    }
}

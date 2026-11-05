package com.msa.orderquery.domain.projection.consumer;

import com.msa.orderquery.domain.projection.service.ProjectionService;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** 바이트 원문을 출력하지 않으며 DB 커밋에 실패하면 Kafka offset을 진행하지 않습니다. */
@Component
@ConditionalOnProperty(name = "query.events.enabled", havingValue = "true")
public class ProjectionConsumer {
    private final ProjectionService service;

    public ProjectionConsumer(ProjectionService service) {
        this.service = service;
    }

    @KafkaListener(
            id = "order-query-projection",
            idIsGroup = false,
            topics = {"${query.events.order-topic}", "${query.events.inquiry-topic}"})
    public void consume(ConsumerRecord<String, byte[]> record) {
        service.apply(record.topic(), record.key(), record.value());
    }
}

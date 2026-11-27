package com.msa.orderquery.domain.projection.consumer;

import com.msa.orderquery.domain.projection.exception.*;
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
        try {
            service.apply(record.topic(), record.key(), record.value());
        } catch (InvalidProjectionEventException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // 오류 처리기 로그에 SQL 원문이 남지 않도록 클래스 이름만 전달하고 재시도합니다.
            throw new ProjectionRetryException(exception);
        }
    }
}

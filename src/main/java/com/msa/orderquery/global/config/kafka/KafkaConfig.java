package com.msa.orderquery.global.config.kafka;

import com.msa.orderquery.domain.projection.exception.InvalidProjectionEventException;

import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

/** 영구 계약 오류만 DLT로 보내고 DB 장애는 복구될 때까지 재시도합니다. */
@Configuration
@EnableConfigurationProperties(ProjectionProperties.class)
public class KafkaConfig {
    @Bean
    @ConditionalOnProperty(name = "query.events.enabled", havingValue = "true")
    public DefaultErrorHandler projectionErrorHandler(KafkaTemplate<String, byte[]> template) {
        var recoverer =
                new DeadLetterPublishingRecoverer(
                        template,
                        (record, exception) ->
                                new TopicPartition(record.topic() + ".DLT", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(10));
        // 예외 헤더에 SQL·주소·문의 원문을 넣지 않습니다. DLT 본문 자체도 비공개 ACL 대상입니다.
        recoverer.setExceptionHeadersCreator((headers, exception, isKey, headerNames) -> {});
        var handler =
                new DefaultErrorHandler(
                        recoverer, new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.addNotRetryableExceptions(InvalidProjectionEventException.class);
        return handler;
    }
}

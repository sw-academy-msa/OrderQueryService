package com.msa.orderquery.global.config.kafka;

import com.msa.orderquery.domain.projection.exception.InvalidProjectionEventException;
import com.msa.orderquery.domain.projection.exception.ProjectionRetryException;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 영구 계약 오류만 DLT로 보내고 DB 장애는 복구될 때까지 재시도합니다. */
@Configuration
@EnableConfigurationProperties(ProjectionProperties.class)
public class KafkaConfig {
    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Bean
    @ConditionalOnProperty(name = "query.events.enabled", havingValue = "true")
    public DefaultErrorHandler projectionErrorHandler(KafkaTemplate<String, byte[]> template) {
        var recoverer =
                new DeadLetterPublishingRecoverer(
                        template,
                        (record, exception) -> {
                            var cause = invalidCause(exception);
                            // 원인을 남기되 원문 JSON과 검증 전 key는 출력하지 않고 위치로 레코드를 추적합니다.
                            log.warn(
                                    "주문 조회 이벤트 DLT 전송: topic={}, partition={}, offset={}, exception={}, reason={}",
                                    record.topic(),
                                    record.partition(),
                                    record.offset(),
                                    cause == null
                                            ? exception.getClass().getName()
                                            : cause.getClass().getName(),
                                    cause == null ? "UNCLASSIFIED" : cause.reason());
                            return new TopicPartition(record.topic() + ".DLT", record.partition());
                        });
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(10));
        // 스택 트레이스와 외부 예외 메시지(SQL, 주소, 문의 원문 가능)는 넣지 않고 예외 클래스와 고정 사유 코드만 남깁니다.
        recoverer.setExceptionHeadersCreator(
                (headers, exception, isKey, headerNames) -> {
                    var cause = invalidCause(exception);
                    headers.add(
                            KafkaHeaders.DLT_EXCEPTION_FQCN,
                            (cause == null ? exception : cause)
                                    .getClass()
                                    .getName()
                                    .getBytes(StandardCharsets.UTF_8));
                    headers.add(
                            KafkaHeaders.DLT_EXCEPTION_MESSAGE,
                            (cause == null ? "UNCLASSIFIED" : cause.getMessage())
                                    .getBytes(StandardCharsets.UTF_8));
                });
        var handler =
                new DefaultErrorHandler(
                        recoverer, new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.addNotRetryableExceptions(InvalidProjectionEventException.class);
        // 무제한 재시도가 조용히 멈춰 있지 않도록 첫 실패와 이후 1분 간격으로 원문 없는 원인을 남깁니다.
        handler.setRetryListeners(
                (record, exception, attempt) -> {
                    // 계약 오류는 곧바로 DLT 로그를 남기므로 재시도 로그에서 제외합니다.
                    if (invalidCause(exception) == null && (attempt == 1 || attempt % 60 == 0))
                        log.warn(
                                "주문 조회 이벤트 재시도: topic={}, partition={}, offset={}, attempt={}, cause={}",
                                record.topic(),
                                record.partition(),
                                record.offset(),
                                attempt,
                                retryCause(exception));
                });
        return handler;
    }

    /** 소비자가 감싼 원문 없는 재시도 예외의 메시지만 사용하고, 그 밖의 예외는 클래스 이름만 남깁니다. */
    static String retryCause(Throwable exception) {
        for (Throwable t = exception; t != null; t = t.getCause()) {
            if (t instanceof ProjectionRetryException retry) return retry.getMessage();
            if (t.getCause() == t) break;
        }
        return exception.getClass().getName();
    }

    /** listener 실행 예외로 감싸진 계약 오류를 찾습니다. */
    static InvalidProjectionEventException invalidCause(Throwable exception) {
        for (Throwable t = exception; t != null; t = t.getCause()) {
            if (t instanceof InvalidProjectionEventException invalid) return invalid;
            if (t.getCause() == t) break;
        }
        return null;
    }
}

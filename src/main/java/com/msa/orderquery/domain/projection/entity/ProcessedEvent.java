package com.msa.orderquery.domain.projection.entity;

import jakarta.persistence.*;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/** 조회 모델 반영과 같은 트랜잭션으로 저장하는 이벤트 처리 이력입니다. */
@Entity
@Table(name = "processed_events", schema = "orders_query")
public class ProcessedEvent {
    @Id public UUID eventId;
    // UUID가 있어도 신규 이벤트는 INSERT하며 기존 이력을 merge로 덮어쓰지 않습니다.
    @Version public Long lockVersion;

    @Column(nullable = false, length = 64)
    public String eventHash;

    public Instant processedAt;

    protected ProcessedEvent() {}

    @Builder
    public ProcessedEvent(UUID eventId, String eventHash, Instant processedAt) {
        this.eventId = eventId;
        this.eventHash = eventHash;
        this.processedAt = processedAt;
    }

    public static class ProcessedEventBuilder {
        @Override
        public String toString() {
            return "ProcessedEventBuilder[REDACTED]";
        }
    }
}

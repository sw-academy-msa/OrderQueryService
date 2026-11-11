package com.msa.orderquery.domain.projection.event;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/** 두 비공개 토픽의 공통 봉투입니다. 스냅샷은 type에 맞는 하나만 있어야 합니다. */
@Builder
public record ProjectionEvent(
        @NotNull UUID eventId,
        @Min(1) @Max(1) int schemaVersion,
        @NotNull Type type,
        @NotNull UUID aggregateId,
        @Positive long aggregateVersion,
        @NotNull Instant occurredAt,
        @Valid OrderSnapshot order,
        @Valid InquirySnapshot inquiry) {
    public enum Type {
        ORDER_UPSERT,
        ORDER_INQUIRY_UPSERT
    }

    public static class ProjectionEventBuilder {
        @Override
        public String toString() {
            return "ProjectionEventBuilder[REDACTED]";
        }
    }

    @Override
    public String toString() {
        return "ProjectionEvent[eventId=" + eventId + ",type=" + type + "]";
    }
}

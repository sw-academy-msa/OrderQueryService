package com.msa.orderquery.domain.order.query.dto.response;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/** 전체 문의함의 HTTP 카드입니다. 본문 전체는 기존 주문별 문의 상세 API로 조회합니다. */
@Builder
public record InquiryInboxItemResponse(
        UUID id,
        UUID orderId,
        UUID productId,
        String productName,
        String category,
        String lastMessage,
        Instant lastMessageAt,
        boolean awaitingSeller,
        Instant createdAt,
        long sourceVersion,
        Instant sourceOccurredAt,
        Instant projectedAt) {
    @Override
    public String toString() {
        return "InquiryInboxItemResponse[REDACTED]";
    }

    public static class InquiryInboxItemResponseBuilder {
        @Override
        public String toString() {
            return "InquiryInboxItemResponseBuilder[REDACTED]";
        }
    }
}

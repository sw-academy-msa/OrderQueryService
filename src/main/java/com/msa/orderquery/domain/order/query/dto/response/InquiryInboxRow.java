package com.msa.orderquery.domain.order.query.dto.response;

import java.time.Instant;
import java.util.UUID;

/** 로컬 조회 DB의 문의함 전용 내부 반환 projection입니다. 전체 메시지·S3 키를 읽지 않습니다. */
public interface InquiryInboxRow {
    UUID getId();

    UUID getOrderId();

    UUID getProductId();

    String getProductName();

    String getCategory();

    String getLastMessage();

    String getLastMessageAt();

    Boolean getAwaitingSeller();

    Instant getCreatedAt();

    long getSourceVersion();

    Instant getSourceOccurredAt();

    Instant getProjectedAt();
}

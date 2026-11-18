package com.msa.orderquery.domain.projection.entity;

import jakarta.persistence.*;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/** 문의 토픽이 주문 토픽보다 먼저 도착해도 저장할 수 있도록 서비스 내부 FK를 강제하지 않습니다. */
@Entity
@Table(
        name = "inquiries",
        schema = "orders_query",
        indexes = @Index(name = "idx_query_inquiry_order", columnList = "orderId,createdAt,id"))
public class InquiryProjection {
    @Id public UUID id;
    public UUID orderId;
    public UUID buyerId;
    public UUID sellerId;
    public Instant createdAt;
    public long sourceVersion = -1;

    @Column(length = 64)
    public String payloadHash;

    public Instant sourceOccurredAt;
    public Instant projectedAt;

    @Column(columnDefinition = "text", nullable = false)
    public String snapshot;

    protected InquiryProjection() {}

    @Builder
    public InquiryProjection(UUID id) {
        this.id = id;
    }

    public static class InquiryProjectionBuilder {
        @Override
        public String toString() {
            return "InquiryProjectionBuilder[REDACTED]";
        }
    }
}

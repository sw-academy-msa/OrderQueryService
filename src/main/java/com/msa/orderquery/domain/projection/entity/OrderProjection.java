package com.msa.orderquery.domain.projection.entity;

import com.msa.orderquery.domain.projection.event.*;

import jakarta.persistence.*;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/** RDS orders_query의 조회 원본입니다. 주문 Command 테이블과 FK·DB JOIN을 만들지 않습니다. */
@Entity
@Table(
        name = "orders",
        schema = "orders_query",
        indexes = {
            @Index(name = "idx_query_buyer_created", columnList = "buyerId,createdAt,id"),
            @Index(name = "idx_query_seller_created", columnList = "sellerId,createdAt,id")
        })
public class OrderProjection {
    @Id public UUID id;
    public UUID buyerId;
    public UUID sellerId;
    public UUID productId;

    @Column(length = 200)
    public String productName;

    @Column(length = 200)
    public String brandOrStore;

    @Enumerated(EnumType.STRING)
    public OrderState state;

    @Enumerated(EnumType.STRING)
    public DeliveryState delivery;

    public Long totalAmount;
    public Instant createdAt;
    public long sourceVersion = -1;

    @Column(length = 64)
    public String payloadHash;

    public Instant sourceOccurredAt;
    public Instant projectedAt;

    // 배송지 등 비공개 값이 들어 있으므로 모델·SQL 오류 원문을 로그에 남기지 않습니다.
    @Column(columnDefinition = "text", nullable = false)
    public String snapshot;

    protected OrderProjection() {}

    @Builder
    public OrderProjection(UUID id) {
        this.id = id;
    }

    public static class OrderProjectionBuilder {
        @Override
        public String toString() {
            return "OrderProjectionBuilder[REDACTED]";
        }
    }
}

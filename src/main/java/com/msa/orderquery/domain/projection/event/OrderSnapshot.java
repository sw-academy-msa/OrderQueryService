package com.msa.orderquery.domain.projection.event;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import lombok.Builder;

import java.time.Instant;
import java.util.*;

/** 현재 OrderJournalImpl의 v1 스냅샷입니다. 초기 확보 중에는 판매자·가격·배송지가 null일 수 있습니다. */
@Builder
public record OrderSnapshot(
        @NotNull UUID orderId,
        @NotNull UUID buyerId,
        UUID sellerId,
        @NotNull UUID productId,
        @Size(max = 200) String productName,
        @Size(max = 200) String brandOrStore,
        @NotNull @Size(min = 1) List<@Valid Item> items,
        @NotNull OrderState state,
        DeliveryState delivery,
        @Valid Address address,
        @PositiveOrZero Long unitPrice,
        @PositiveOrZero Long shippingFee,
        @PositiveOrZero Long totalAmount,
        @PositiveOrZero Integer dispatchBusinessDays,
        Instant expiresAt,
        @NotNull Instant createdAt,
        Instant confirmedAt,
        @Size(max = 100) String carrier,
        @Size(max = 100) String trackingNumber,
        Instant shippedAt,
        Instant deliveredAt) {
    public OrderSnapshot {
        if (items != null) items = List.copyOf(items);
    }

    public static class OrderSnapshotBuilder {
        @Override
        public String toString() {
            return "OrderSnapshotBuilder[REDACTED]";
        }
    }

    @Override
    public String toString() {
        return "OrderSnapshot[REDACTED]";
    }

    /** Command의 JPA 옵션 객체를 이벤트 계약 값으로만 수신합니다. */
    @Builder
    public record Item(
            @NotNull UUID optionId, @Positive int quantity, @Size(max = 500) String label) {
        public static class ItemBuilder {
            @Override
            public String toString() {
                return "ItemBuilder[REDACTED]";
            }
        }

        @Override
        public String toString() {
            return "Item[REDACTED]";
        }
    }

    /** 주문 시점 배송지입니다. Account 주소록 조회나 서비스 간 FK를 만들지 않습니다. */
    @Builder
    public record Address(
            @Size(max = 100) String recipientName,
            @Size(max = 30) String recipientPhone,
            @Size(max = 20) String postalCode,
            @Size(max = 500) String roadAddress,
            @Size(max = 500) String detailAddress,
            @Size(max = 1000) String deliveryMemo) {
        public static class AddressBuilder {
            @Override
            public String toString() {
                return "AddressBuilder[REDACTED]";
            }
        }

        @Override
        public String toString() {
            return "Address[REDACTED]";
        }
    }
}

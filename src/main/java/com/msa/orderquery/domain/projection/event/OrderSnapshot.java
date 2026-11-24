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
        Instant deliveredAt,
        // 구매 확정 시각(구매자 확정 또는 배송 완료 7일 뒤 자동). 이전 스냅샷에는 없다.
        // null이면 직렬화에서 빼서, 이 필드가 생기기 전 이벤트를 다시 받아도 중복 판정 해시가 예전과 같다
        @com.fasterxml.jackson.annotation.JsonInclude(
                        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                Instant purchaseConfirmedAt,
        @com.fasterxml.jackson.annotation.JsonInclude(
                        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                @PositiveOrZero
                Long originalShippingFee,
        @com.fasterxml.jackson.annotation.JsonInclude(
                        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                @PositiveOrZero
                Long membershipShippingDiscount,
        @com.fasterxml.jackson.annotation.JsonInclude(
                        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                @PositiveOrZero
                Long effectiveShippingFee) {
    public OrderSnapshot {
        if (items != null) items = List.copyOf(items);
    }

    /** 과거 이벤트는 배송비 원본만 있으며 새 필드의 null은 해시에 추가하지 않습니다. */
    public long originalShippingAmount() {
        return originalShippingFee == null
                ? (shippingFee == null ? 0 : shippingFee)
                : originalShippingFee;
    }

    public long shippingDiscountAmount() {
        return membershipShippingDiscount == null ? 0 : membershipShippingDiscount;
    }

    public long chargedShippingAmount() {
        return Math.subtractExact(originalShippingAmount(), shippingDiscountAmount());
    }

    /** 플랫폼 배송비 지원을 상품 매출에서 차감하지 않습니다. */
    public long merchandiseAmount() {
        return Math.max(
                0,
                Math.subtractExact(totalAmount == null ? 0 : totalAmount, chargedShippingAmount()));
    }

    @AssertTrue
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isMembershipShippingValid() {
        if (originalShippingFee == null
                && membershipShippingDiscount == null
                && effectiveShippingFee == null) return true;
        return originalShippingFee != null
                && membershipShippingDiscount != null
                && effectiveShippingFee != null
                && shippingFee != null
                && originalShippingFee.equals(shippingFee)
                && originalShippingFee >= 0
                && membershipShippingDiscount >= 0
                && membershipShippingDiscount <= originalShippingFee
                && effectiveShippingFee == originalShippingFee - membershipShippingDiscount
                && (totalAmount == null || totalAmount >= effectiveShippingFee);
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

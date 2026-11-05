package com.msa.orderquery.domain.order.query.dto.response;

import com.msa.orderquery.domain.projection.event.*;

import lombok.Builder;

import java.time.Instant;
import java.util.*;

/** HTTP 전용 DTO입니다. JPA 엔티티·Kafka 이벤트·S3 객체 키를 응답으로 내보내지 않습니다. */
public final class OrderResponses {
    private OrderResponses() {}

    /** 목록의 불변 복사본이며 실제 결제 판정은 Command checkout 상태를 사용해야 합니다. */
    @Builder
    public record Page<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public Page {
            content = List.copyOf(content);
        }

        public static class PageBuilder<T> {
            @Override
            public String toString() {
                return "PageBuilder[REDACTED]";
            }
        }

        @Override
        public String toString() {
            return "Page[REDACTED]";
        }
    }

    /** 카드에는 배송지·연락처·다른 당사자 ID를 포함하지 않습니다. */
    @Builder
    public record Summary(
            UUID orderId,
            String orderNumber,
            UUID productId,
            String productName,
            String brandOrStore,
            List<Item> items,
            OrderState state,
            DeliveryState delivery,
            Long totalAmount,
            Instant createdAt,
            long sourceVersion,
            Instant sourceOccurredAt,
            Instant projectedAt) {
        public Summary {
            items = List.copyOf(items);
        }

        public static class SummaryBuilder {
            @Override
            public String toString() {
                return "SummaryBuilder[REDACTED]";
            }
        }

        @Override
        public String toString() {
            return "Summary[REDACTED]";
        }
    }

    /** 주문 당사자 소유권 확인 후에만 배송지와 배송 안내를 제공하는 상세 응답입니다. */
    @Builder
    public record Detail(
            UUID orderId,
            String orderNumber,
            UUID productId,
            String productName,
            String brandOrStore,
            List<Item> items,
            OrderState state,
            DeliveryState delivery,
            Address address,
            Long unitPrice,
            Long shippingFee,
            Long totalAmount,
            Integer dispatchBusinessDays,
            Instant expiresAt,
            Instant createdAt,
            Instant confirmedAt,
            String carrier,
            String trackingNumber,
            Instant shippedAt,
            Instant deliveredAt,
            long sourceVersion,
            Instant sourceOccurredAt,
            Instant projectedAt) {
        public Detail {
            items = List.copyOf(items);
        }

        public static class DetailBuilder {
            @Override
            public String toString() {
                return "DetailBuilder[REDACTED]";
            }
        }

        @Override
        public String toString() {
            return "Detail[REDACTED]";
        }
    }

    /** 옵션 정보는 주문 시점 스냅샷이며 Product의 현재 옵션 조회를 대체하지 않습니다. */
    @Builder
    public record Item(UUID optionId, String label, int quantity) {
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

    /** 주소 엔티티와 분리하여 반환하는 배송지 값입니다. */
    @Builder
    public record Address(
            String recipientName,
            String recipientPhone,
            String postalCode,
            String roadAddress,
            String detailAddress,
            String deliveryMemo) {
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

    /** 문의의 사진 ID로 기존 Command 다운로드 권한 API를 호출합니다. */
    @Builder
    public record Inquiry(
            UUID id,
            UUID orderId,
            String category,
            List<Message> messages,
            List<UUID> imageIds,
            Instant createdAt,
            long sourceVersion,
            Instant sourceOccurredAt,
            Instant projectedAt) {
        public Inquiry {
            messages = List.copyOf(messages);
            imageIds = List.copyOf(imageIds);
        }

        public static class InquiryBuilder {
            @Override
            public String toString() {
                return "InquiryBuilder[REDACTED]";
            }
        }

        @Override
        public String toString() {
            return "Inquiry[REDACTED]";
        }
    }

    /** 판매자 답변인지 구매자 메시지인지 원본 구분을 유지합니다. */
    @Builder
    public record Message(boolean seller, String body, Instant createdAt) {
        public static class MessageBuilder {
            @Override
            public String toString() {
                return "MessageBuilder[REDACTED]";
            }
        }

        @Override
        public String toString() {
            return "Message[REDACTED]";
        }
    }
}

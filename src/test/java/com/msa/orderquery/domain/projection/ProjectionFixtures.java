package com.msa.orderquery.domain.projection;

import com.msa.orderquery.domain.projection.event.*;

import java.time.Instant;
import java.util.*;

/** 실제 Command의 필드 계약에 맞춘 합성 데이터입니다. 실사용 주문·주소·결제키를 읽지 않습니다. */
public final class ProjectionFixtures {
    private ProjectionFixtures() {}

    public static final String ORDER_TOPIC = "stockdrop.orders.private.v1";
    public static final String INQUIRY_TOPIC = "stockdrop.orders.inquiries.private.v1";
    public static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    public static final UUID BUYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID SELLER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    public static final UUID PRODUCT = UUID.fromString("00000000-0000-0000-0000-000000000003");
    public static final UUID OPTION = UUID.fromString("00000000-0000-0000-0000-000000000004");

    public static ProjectionEvent order(
            UUID id, long version, UUID buyer, UUID seller, OrderState state) {
        var snapshot =
                OrderSnapshot.builder()
                        .orderId(id)
                        .buyerId(buyer)
                        .sellerId(seller)
                        .productId(PRODUCT)
                        .productName("합성 주문 상품")
                        .brandOrStore("합성 판매처")
                        .items(
                                List.of(
                                        OrderSnapshot.Item.builder()
                                                .optionId(OPTION)
                                                .quantity(2)
                                                .label("기본 옵션")
                                                .build()))
                        .state(state)
                        .delivery(state == OrderState.CONFIRMED ? DeliveryState.PREPARING : null)
                        .address(
                                OrderSnapshot.Address.builder()
                                        .recipientName("합성 수령인")
                                        .recipientPhone("synthetic-phone")
                                        .postalCode("00000")
                                        .roadAddress("합성 도로")
                                        .detailAddress("테스트 전용")
                                        .deliveryMemo("합성 메모")
                                        .build())
                        .unitPrice(1000L)
                        .shippingFee(3000L)
                        .totalAmount(5000L)
                        .dispatchBusinessDays(2)
                        .expiresAt(NOW.plusSeconds(300))
                        .createdAt(NOW)
                        .confirmedAt(state == OrderState.CONFIRMED ? NOW.plusSeconds(10) : null)
                        .build();
        return ProjectionEvent.builder()
                .eventId(UUID.randomUUID())
                .schemaVersion(1)
                .type(ProjectionEvent.Type.ORDER_UPSERT)
                .aggregateId(id)
                .aggregateVersion(version)
                .occurredAt(NOW.plusSeconds(version))
                .order(snapshot)
                .build();
    }

    public static ProjectionEvent order(UUID id, long version) {
        return order(id, version, BUYER, SELLER, OrderState.CONFIRMED);
    }

    public static ProjectionEvent inquiry(UUID id, UUID order, int version) {
        var messages = new ArrayList<InquirySnapshot.Message>();
        for (int index = 0; index < version; index++) {
            messages.add(
                    InquirySnapshot.Message.builder()
                            .requestKey(
                                    UUID.nameUUIDFromBytes(
                                            (id + ":" + index)
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8)))
                            .seller(index % 2 == 1)
                            .body(index % 2 == 1 ? "판매자 합성 답변" : "구매자 합성 문의")
                            .createdAt(NOW.plusSeconds(index))
                            .build());
        }
        var snapshot =
                InquirySnapshot.builder()
                        .id(id)
                        .orderId(order)
                        .buyerId(BUYER)
                        .sellerId(SELLER)
                        .category("배송")
                        .createdAt(NOW)
                        .messages(messages)
                        .images(
                                List.of(
                                        InquirySnapshot.Image.builder()
                                                .id(
                                                        UUID.nameUUIDFromBytes(
                                                                (id + ":image")
                                                                        .getBytes(
                                                                                java.nio.charset
                                                                                        .StandardCharsets
                                                                                        .UTF_8)))
                                                .objectKey("private-order-inquiries/synthetic-only")
                                                .contentType("image/png")
                                                .sizeBytes(100)
                                                .build()))
                        .build();
        return ProjectionEvent.builder()
                .eventId(UUID.randomUUID())
                .schemaVersion(1)
                .type(ProjectionEvent.Type.ORDER_INQUIRY_UPSERT)
                .aggregateId(id)
                .aggregateVersion(version)
                .occurredAt(NOW.plusSeconds(version))
                .inquiry(snapshot)
                .build();
    }
}

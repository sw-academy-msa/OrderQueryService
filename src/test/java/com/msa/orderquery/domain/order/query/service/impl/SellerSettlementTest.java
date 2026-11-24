package com.msa.orderquery.domain.order.query.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.msa.orderquery.domain.projection.ProjectionFixtures;
import com.msa.orderquery.domain.projection.entity.OrderProjection;
import com.msa.orderquery.domain.projection.event.*;
import com.msa.orderquery.domain.projection.repository.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.YearMonth;
import java.util.*;

/** 정산은 배송 완료 주문 중 해당 월(한국 시간)에 구매 확정된 상품 금액(배송비 제외)만 합산합니다. */
class SellerSettlementTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final OrderProjectionRepository orders = mock(OrderProjectionRepository.class);
    private final OrderQueryServiceImpl service =
            new OrderQueryServiceImpl(orders, mock(InquiryProjectionRepository.class), json);

    private OrderProjection row(Instant purchaseConfirmedAt) {
        return row(purchaseConfirmedAt, null);
    }

    private OrderProjection row(Instant purchaseConfirmedAt, Long discount) {
        var id = UUID.randomUUID();
        var base = ProjectionFixtures.order(id, 1).order();
        var snapshot =
                OrderSnapshot.builder()
                        .orderId(id)
                        .buyerId(base.buyerId())
                        .sellerId(base.sellerId())
                        .productId(base.productId())
                        .productName(base.productName())
                        .items(base.items())
                        .state(OrderState.CONFIRMED)
                        .delivery(DeliveryState.DELIVERED)
                        .unitPrice(1000L)
                        .shippingFee(3000L)
                        .totalAmount(5000L - (discount == null ? 0 : discount))
                        .originalShippingFee(discount == null ? null : 3000L)
                        .membershipShippingDiscount(discount)
                        .effectiveShippingFee(discount == null ? null : 3000L - discount)
                        .createdAt(base.createdAt())
                        .purchaseConfirmedAt(purchaseConfirmedAt)
                        .build();
        var r = new OrderProjection(id);
        r.snapshot = json.writeValueAsString(snapshot);
        return r;
    }

    @Test
    void snapshotWithoutPurchaseConfirmationSerializesLikeBeforeTheField() {
        // 이 필드가 생기기 전 이벤트를 다시 받아도 중복 판정 해시가 같아야 한다
        var json =
                this.json.writeValueAsString(
                        ProjectionFixtures.order(UUID.randomUUID(), 1).order());
        assertThat(json)
                .doesNotContain(
                        "purchaseConfirmedAt",
                        "originalShippingFee",
                        "membershipShippingDiscount",
                        "effectiveShippingFee",
                        "membershipShippingValid");
    }

    @Test
    void freeShippingDoesNotReduceSellerMerchandiseSettlement() {
        when(orders.findBySellerIdAndStateAndDelivery(
                        ProjectionFixtures.SELLER, OrderState.CONFIRMED, DeliveryState.DELIVERED))
                .thenReturn(
                        List.of(
                                row(Instant.parse("2026-11-10T00:00:00Z"), 3000L),
                                row(Instant.parse("2026-11-11T00:00:00Z"), 1500L),
                                row(null, 3000L)));
        var result = service.settlement(ProjectionFixtures.SELLER, YearMonth.of(2026, 11));
        assertThat(result.confirmedAmount()).isEqualTo(4000);
        assertThat(result.awaitingAmount()).isEqualTo(2000);
    }

    @Test
    void sumsPurchaseConfirmedAmountInKoreanMonthAndAwaitingSeparately() {
        when(orders.findBySellerIdAndStateAndDelivery(
                        ProjectionFixtures.SELLER, OrderState.CONFIRMED, DeliveryState.DELIVERED))
                .thenReturn(
                        List.of(
                                // 11/30 15:30 UTC = 12/1 00:30 KST → 12월
                                row(Instant.parse("2026-11-30T15:30:00Z")),
                                row(Instant.parse("2026-11-10T00:00:00Z")),
                                row(Instant.parse("2026-11-20T00:00:00Z")),
                                row(null)));

        var r = service.settlement(ProjectionFixtures.SELLER, YearMonth.of(2026, 11));

        assertThat(r.confirmedOrders()).isEqualTo(2);
        assertThat(r.confirmedAmount()).isEqualTo(4000);
        assertThat(r.awaitingOrders()).isEqualTo(1);
        assertThat(r.awaitingAmount()).isEqualTo(2000);
        assertThat(r.products())
                .singleElement()
                .satisfies(
                        p -> {
                            assertThat(p.orders()).isEqualTo(2);
                            assertThat(p.quantity()).isEqualTo(4);
                            assertThat(p.amount()).isEqualTo(4000);
                        });
    }
}

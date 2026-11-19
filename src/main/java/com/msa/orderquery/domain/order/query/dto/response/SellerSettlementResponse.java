package com.msa.orderquery.domain.order.query.dto.response;

import lombok.Builder;

import java.util.*;

/**
 * 판매자 정산 금액 집계입니다(송금하지 않는 조회용). 금액은 상품 금액(배송비 제외)입니다.
 * confirmed: 해당 월(한국 시간)에 구매 확정된 주문, awaiting: 배송 완료 후 아직 구매 확정 전인 주문.
 * 수수료 규칙(상품별 한정 수량 판매율)은 상품 서비스 수량이 필요해 화면에서 적용합니다.
 */
@Builder
public record SellerSettlementResponse(
        String month,
        long confirmedOrders,
        long confirmedAmount,
        long awaitingOrders,
        long awaitingAmount,
        List<Product> products) {
    public SellerSettlementResponse {
        products = List.copyOf(products);
    }

    @Builder
    public record Product(
            UUID productId, String productName, long orders, long quantity, long amount) {}
}

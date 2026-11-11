package com.msa.orderquery.domain.order.query.dto.response;

import lombok.Builder;

/** 지연 가능한 조회 모델의 집계 HTTP 응답입니다. 매출·수수료·정산 금액을 계산하지 않습니다. */
@Builder
public record SellerOrderSummaryResponse(
        long totalOrders,
        long confirmedOrders,
        long preparingOrders,
        long shippingOrders,
        long deliveredOrders,
        long confirmedQuantity,
        boolean approximate) {}

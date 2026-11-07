package com.msa.orderquery.domain.order.query.dto.response;

/** 저장소 집계의 내부 반환 projection이며 HTTP 응답으로 직접 내보내지 않습니다. */
public interface SellerOrderSummaryRow {
    long getTotalOrders();

    long getConfirmedOrders();

    long getPreparingOrders();

    long getShippingOrders();

    long getDeliveredOrders();

    long getConfirmedQuantity();
}

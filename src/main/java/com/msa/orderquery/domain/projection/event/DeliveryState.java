package com.msa.orderquery.domain.projection.event;

/** 출고·배송 상태의 복사본이며 실제 상태 변경은 Order Command가 수행합니다. */
public enum DeliveryState {
    PREPARING,
    SHIPPING,
    DELIVERED
}

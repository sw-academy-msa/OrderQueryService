package com.msa.orderquery.domain.projection.event;

/** 현재 Order Command가 발행하는 상태 값만 표현하며 Query가 상태를 전이시키지 않습니다. */
public enum OrderState {
    RESERVING,
    HELD,
    STARTING_PAYMENT,
    APPROVING_PAYMENT,
    VERIFYING_PAYMENT,
    CONFIRMING_STOCK,
    COMPENSATING_PAYMENT,
    RELEASING_STOCK,
    CONFIRMED,
    FAILED,
    EXPIRED
}

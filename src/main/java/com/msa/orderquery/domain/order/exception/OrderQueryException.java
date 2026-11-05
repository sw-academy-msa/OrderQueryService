package com.msa.orderquery.domain.order.exception;

import com.msa.orderquery.global.error.BusinessException;

/** Account와 같은 공통 예외 처리기에 주문 조회의 업무 오류만 전달합니다. */
public class OrderQueryException extends BusinessException {
    public OrderQueryException(OrderQueryErrorCode code) {
        super(code);
    }
}

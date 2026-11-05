package com.msa.orderquery.domain.order.exception;

import com.msa.orderquery.global.error.ErrorCode;

import org.springframework.http.HttpStatus;

/** 비공개 주문은 타인 소유 여부도 노출하지 않고 같은 404로 처리합니다. */
public enum OrderQueryErrorCode implements ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER_QUERY_NOT_FOUND", "주문 조회 정보를 찾을 수 없습니다."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "ORDER_QUERY_INVALID_REQUEST", "주문 조회 조건이 올바르지 않습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    OrderQueryErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}

package com.msa.orderquery.domain.projection.exception;

import com.msa.orderquery.global.error.ErrorCode;

import org.springframework.http.HttpStatus;

/** 계약 위반은 재시도로 해결되지 않으며 원문 대신 고정 오류 정보만 보관합니다. */
public enum ProjectionErrorCode implements ErrorCode {
    INVALID_EVENT;

    public HttpStatus status() {
        return HttpStatus.BAD_REQUEST;
    }

    public String code() {
        return "ORDER_PROJECTION_INVALID_EVENT";
    }

    public String message() {
        return "주문 조회 이벤트 계약이 올바르지 않습니다.";
    }
}

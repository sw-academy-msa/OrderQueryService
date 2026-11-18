package com.msa.orderquery.domain.projection.exception;

import com.msa.orderquery.global.error.BusinessException;

/** 이 타입만 DLT 대상이며 DB 장애나 예상하지 못한 내부 오류는 계속 재시도합니다. */
public class InvalidProjectionEventException extends BusinessException {
    public InvalidProjectionEventException() {
        super(ProjectionErrorCode.INVALID_EVENT);
    }
}

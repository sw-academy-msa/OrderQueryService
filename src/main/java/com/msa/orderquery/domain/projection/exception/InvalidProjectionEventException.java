package com.msa.orderquery.domain.projection.exception;

import com.msa.orderquery.global.error.BusinessException;

/**
 * 이 타입만 DLT 대상이며 DB 장애나 예상하지 못한 내부 오류는 계속 재시도합니다.
 *
 * <p>사유는 고정 코드와 필드 이름만 담고 원문 JSON, 주소, 문의 내용은 담지 않습니다. 로그와 DLT 헤더에 그대로 남깁니다.
 */
public class InvalidProjectionEventException extends BusinessException {
    private final String reason;

    public InvalidProjectionEventException(String reason) {
        super(ProjectionErrorCode.INVALID_EVENT);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }

    @Override
    public String getMessage() {
        return super.getMessage() + " reason=" + reason;
    }
}

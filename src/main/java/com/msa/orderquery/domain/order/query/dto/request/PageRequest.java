package com.msa.orderquery.domain.order.query.dto.request;

import jakarta.validation.constraints.*;

/** 문의 목록의 페이지 조건입니다. JPA 오프셋 상한도 서비스에서 다시 검증합니다. */
public record PageRequest(@PositiveOrZero Integer page, @Min(1) @Max(100) Integer size) {
    public PageRequest {
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
    }
}

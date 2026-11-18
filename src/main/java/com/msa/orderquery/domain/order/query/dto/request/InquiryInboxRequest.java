package com.msa.orderquery.domain.order.query.dto.request;

import jakarta.validation.constraints.*;

import java.util.UUID;

/** 주문별로 진입하지 않고 본인 전체 주문 문의를 조회합니다. 당사자 ID를 입력받지 않습니다. */
public record InquiryInboxRequest(
        UUID productId,
        Boolean unansweredOnly,
        @PositiveOrZero Integer page,
        @Min(1) @Max(100) Integer size) {
    public InquiryInboxRequest {
        unansweredOnly = Boolean.TRUE.equals(unansweredOnly);
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
    }
}

package com.msa.orderquery.domain.order.query.dto.request;

import com.msa.orderquery.domain.projection.event.*;

import jakarta.validation.constraints.*;

import java.time.Instant;

/** 목록은 최신 주문순이며 날짜는 UTC Instant, createdTo는 미포함 경계입니다. */
public record OrderSearchRequest(
        @Size(max = 100) String keyword,
        OrderState state,
        DeliveryState delivery,
        Instant createdFrom,
        Instant createdTo,
        @PositiveOrZero Integer page,
        @Min(1) @Max(100) Integer size) {
    public OrderSearchRequest {
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
    }
}

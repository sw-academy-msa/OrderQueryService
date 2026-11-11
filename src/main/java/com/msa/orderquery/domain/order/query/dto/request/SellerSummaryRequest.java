package com.msa.orderquery.domain.order.query.dto.request;

import java.time.Instant;
import java.util.UUID;

/** 상품과 주문 생성 시각 범위만 집계하며 판매자 ID는 JWT에서 결정합니다. 끝 시각은 미포함입니다. */
public record SellerSummaryRequest(UUID productId, Instant createdFrom, Instant createdTo) {}

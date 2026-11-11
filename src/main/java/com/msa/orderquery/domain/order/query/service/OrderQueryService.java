package com.msa.orderquery.domain.order.query.service;

import com.msa.orderquery.domain.order.query.dto.request.*;
import com.msa.orderquery.domain.order.query.dto.response.OrderResponses.*;

import java.util.UUID;

/** actor는 검증된 JWT sub이며 seller는 컨트롤러가 고정한 역할 맥락입니다. 클라이언트 ID를 신뢰하지 않습니다. */
public interface OrderQueryService {
    Page<Summary> list(UUID actor, boolean seller, OrderSearchRequest request);

    Detail detail(UUID actor, boolean seller, UUID orderId);

    Page<Inquiry> inquiries(UUID actor, boolean seller, UUID orderId, PageRequest request);

    Inquiry inquiry(UUID actor, boolean seller, UUID orderId, UUID inquiryId);

    /** 계정별 전체 주문 문의함이며 주문당 문의 상세는 기존 계약을 유지합니다. */
    Page<com.msa.orderquery.domain.order.query.dto.response.InquiryInboxItemResponse> inbox(
            UUID actor, boolean seller, InquiryInboxRequest request);

    com.msa.orderquery.domain.order.query.dto.response.SellerOrderSummaryResponse sellerSummary(
            UUID seller, SellerSummaryRequest request);
}

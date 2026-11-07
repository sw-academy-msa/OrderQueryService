package com.msa.orderquery.domain.order.query.controller;

import com.msa.orderquery.domain.order.query.dto.request.*;
import com.msa.orderquery.domain.order.query.dto.response.OrderResponses.*;
import com.msa.orderquery.domain.order.query.service.OrderQueryService;
import com.msa.orderquery.global.response.*;

import jakarta.validation.Valid;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 검증된 구매자 sub로만 조회하며 sellerId/buyerId 요청 헤더나 파라미터를 사용하지 않습니다. */
@RestController
@RequestMapping(value = "/api/v1/orders", produces = "application/json")
@PreAuthorize("hasRole('BUYER')")
public class BuyerOrderQueryController {
    private final OrderQueryService service;

    public BuyerOrderQueryController(OrderQueryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<Page<Summary>> list(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @ParameterObject @ModelAttribute OrderSearchRequest request) {
        Page<Summary> response = service.list(UUID.fromString(jwt.getSubject()), false, request);
        return ApiResponse.onSuccess(response, SuccessCode.OK);
    }

    /** 여러 주문의 문의 카드만 반환하며 작성·답변은 Command API를 사용합니다. */
    @GetMapping("/inquiries")
    public ApiResponse<
                    Page<
                            com.msa.orderquery.domain.order.query.dto.response
                                    .InquiryInboxItemResponse>>
            inbox(
                    @AuthenticationPrincipal Jwt jwt,
                    @Valid @ParameterObject @ModelAttribute InquiryInboxRequest request) {
        var response = service.inbox(UUID.fromString(jwt.getSubject()), false, request);
        return ApiResponse.onSuccess(response, SuccessCode.OK);
    }

    @GetMapping("/{id}")
    public ApiResponse<Detail> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Detail response = service.detail(UUID.fromString(jwt.getSubject()), false, id);
        return ApiResponse.onSuccess(response, SuccessCode.OK);
    }

    @GetMapping("/{id}/inquiries")
    public ApiResponse<Page<Inquiry>> inquiries(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @ParameterObject @ModelAttribute PageRequest request) {
        Page<Inquiry> response =
                service.inquiries(UUID.fromString(jwt.getSubject()), false, id, request);
        return ApiResponse.onSuccess(response, SuccessCode.OK);
    }

    @GetMapping("/{id}/inquiries/{inquiryId}")
    public ApiResponse<Inquiry> inquiry(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID inquiryId) {
        Inquiry response = service.inquiry(UUID.fromString(jwt.getSubject()), false, id, inquiryId);
        return ApiResponse.onSuccess(response, SuccessCode.OK);
    }
}

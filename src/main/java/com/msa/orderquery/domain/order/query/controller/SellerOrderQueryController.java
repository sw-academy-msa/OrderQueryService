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

/** SELLER 역할과 해당 주문의 판매자 소유권을 모두 요구하는 조회 경로입니다. */
@RestController
@RequestMapping(value = "/api/v1/seller/orders", produces = "application/json")
@PreAuthorize("hasRole('SELLER')")
public class SellerOrderQueryController {
    private final OrderQueryService service;

    public SellerOrderQueryController(OrderQueryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<Page<Summary>> list(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @ParameterObject @ModelAttribute OrderSearchRequest request) {
        Page<Summary> response = service.list(UUID.fromString(jwt.getSubject()), true, request);
        return ApiResponse.onSuccess(response, SuccessCode.OK);
    }

    @GetMapping("/{id}")
    public ApiResponse<Detail> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Detail response = service.detail(UUID.fromString(jwt.getSubject()), true, id);
        return ApiResponse.onSuccess(response, SuccessCode.OK);
    }

    @GetMapping("/{id}/inquiries")
    public ApiResponse<Page<Inquiry>> inquiries(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @ParameterObject @ModelAttribute PageRequest request) {
        Page<Inquiry> response =
                service.inquiries(UUID.fromString(jwt.getSubject()), true, id, request);
        return ApiResponse.onSuccess(response, SuccessCode.OK);
    }

    @GetMapping("/{id}/inquiries/{inquiryId}")
    public ApiResponse<Inquiry> inquiry(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID inquiryId) {
        Inquiry response = service.inquiry(UUID.fromString(jwt.getSubject()), true, id, inquiryId);
        return ApiResponse.onSuccess(response, SuccessCode.OK);
    }
}

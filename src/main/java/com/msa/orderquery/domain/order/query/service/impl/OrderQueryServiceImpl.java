package com.msa.orderquery.domain.order.query.service.impl;

import com.msa.orderquery.domain.order.exception.*;
import com.msa.orderquery.domain.order.query.dto.request.OrderSearchRequest;
import com.msa.orderquery.domain.order.query.dto.response.OrderResponses.*;
import com.msa.orderquery.domain.order.query.service.OrderQueryService;
import com.msa.orderquery.domain.projection.entity.*;
import com.msa.orderquery.domain.projection.event.*;
import com.msa.orderquery.domain.projection.repository.*;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** Query DB의 JPA 저장소만 사용하며 Account/Order/Product로 HTTP나 SQL 조회를 우회하지 않습니다. */
@Service
@Transactional(readOnly = true)
public class OrderQueryServiceImpl implements OrderQueryService {
    private final OrderProjectionRepository orders;
    private final InquiryProjectionRepository inquiries;
    private final ObjectMapper mapper;

    public OrderQueryServiceImpl(
            OrderProjectionRepository orders,
            InquiryProjectionRepository inquiries,
            ObjectMapper mapper) {
        this.orders = orders;
        this.inquiries = inquiries;
        this.mapper = mapper;
    }

    public Page<Summary> list(UUID actor, boolean seller, OrderSearchRequest request) {
        var pageable = paging(request.page(), request.size());
        if (request.createdFrom() != null
                && request.createdTo() != null
                && !request.createdFrom().isBefore(request.createdTo())) invalid();
        String keyword = request.keyword();
        if (keyword != null) {
            if (keyword.length() > 100) invalid();
            keyword = keyword.trim().toLowerCase(Locale.ROOT);
            keyword =
                    keyword.isEmpty()
                            ? null
                            : "%"
                                    + keyword.replace("!", "!!")
                                            .replace("%", "!%")
                                            .replace("_", "!_")
                                    + "%";
        }
        var result =
                orders.search(
                        actor,
                        seller,
                        request.state(),
                        request.delivery(),
                        request.createdFrom(),
                        request.createdTo(),
                        keyword,
                        pageable);
        return page(result.map(this::summary));
    }

    public Detail detail(UUID actor, boolean seller, UUID orderId) {
        var row = owned(actor, seller, orderId);
        var o = mapper.readValue(row.snapshot, OrderSnapshot.class);
        return Detail.builder()
                .orderId(row.id)
                .orderNumber("SD-" + row.id)
                .productId(o.productId())
                .productName(o.productName())
                .brandOrStore(o.brandOrStore())
                .items(items(o))
                .state(o.state())
                .delivery(o.delivery())
                .address(address(o.address()))
                .unitPrice(o.unitPrice())
                .shippingFee(o.shippingFee())
                .totalAmount(o.totalAmount())
                .dispatchBusinessDays(o.dispatchBusinessDays())
                .expiresAt(o.expiresAt())
                .createdAt(o.createdAt())
                .confirmedAt(o.confirmedAt())
                .carrier(o.carrier())
                .trackingNumber(o.trackingNumber())
                .shippedAt(o.shippedAt())
                .deliveredAt(o.deliveredAt())
                .sourceVersion(row.sourceVersion)
                .sourceOccurredAt(row.sourceOccurredAt)
                .projectedAt(row.projectedAt)
                .build();
    }

    public Page<Inquiry> inquiries(
            UUID actor,
            boolean seller,
            UUID orderId,
            com.msa.orderquery.domain.order.query.dto.request.PageRequest request) {
        var pageable = paging(request.page(), request.size());
        var row = owned(actor, seller, orderId);
        return page(
                inquiries
                        .forOrder(orderId, row.buyerId, row.sellerId, pageable)
                        .map(this::response));
    }

    public Inquiry inquiry(UUID actor, boolean seller, UUID orderId, UUID inquiryId) {
        var row = owned(actor, seller, orderId);
        return response(
                inquiries
                        .forOrder(inquiryId, orderId, row.buyerId, row.sellerId)
                        .orElseThrow(this::notFound));
    }

    private OrderProjection owned(UUID actor, boolean seller, UUID orderId) {
        // 존재 여부와 소유권을 같은 조건으로 검사하여 타인의 주문 식별자를 노출하지 않습니다.
        return orders.owned(orderId, actor, seller).orElseThrow(this::notFound);
    }

    private Summary summary(OrderProjection row) {
        var o = mapper.readValue(row.snapshot, OrderSnapshot.class);
        return Summary.builder()
                .orderId(row.id)
                .orderNumber("SD-" + row.id)
                .productId(o.productId())
                .productName(o.productName())
                .brandOrStore(o.brandOrStore())
                .items(items(o))
                .state(row.state)
                .delivery(row.delivery)
                .totalAmount(row.totalAmount)
                .createdAt(row.createdAt)
                .sourceVersion(row.sourceVersion)
                .sourceOccurredAt(row.sourceOccurredAt)
                .projectedAt(row.projectedAt)
                .build();
    }

    private List<Item> items(OrderSnapshot o) {
        return o.items().stream()
                .map(
                        i ->
                                Item.builder()
                                        .optionId(i.optionId())
                                        .label(i.label())
                                        .quantity(i.quantity())
                                        .build())
                .toList();
    }

    private Address address(OrderSnapshot.Address a) {
        return a == null
                ? null
                : Address.builder()
                        .recipientName(a.recipientName())
                        .recipientPhone(a.recipientPhone())
                        .postalCode(a.postalCode())
                        .roadAddress(a.roadAddress())
                        .detailAddress(a.detailAddress())
                        .deliveryMemo(a.deliveryMemo())
                        .build();
    }

    private Inquiry response(InquiryProjection row) {
        var i = mapper.readValue(row.snapshot, InquirySnapshot.class);
        return Inquiry.builder()
                .id(row.id)
                .orderId(row.orderId)
                .category(i.category())
                .messages(
                        i.messages().stream()
                                .map(
                                        m ->
                                                Message.builder()
                                                        .seller(m.seller())
                                                        .body(m.body())
                                                        .createdAt(m.createdAt())
                                                        .build())
                                .toList())
                .imageIds(i.images().stream().map(InquirySnapshot.Image::id).toList())
                .createdAt(row.createdAt)
                .sourceVersion(row.sourceVersion)
                .sourceOccurredAt(row.sourceOccurredAt)
                .projectedAt(row.projectedAt)
                .build();
    }

    private <T> Page<T> page(org.springframework.data.domain.Page<T> source) {
        return Page.<T>builder()
                .content(source.getContent())
                .page(source.getNumber())
                .size(source.getSize())
                .totalElements(source.getTotalElements())
                .totalPages(source.getTotalPages())
                .build();
    }

    private Pageable paging(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) invalid();
        return PageRequest.of(
                page, size, Sort.by("createdAt").descending().and(Sort.by("id").descending()));
    }

    private OrderQueryException notFound() {
        return new OrderQueryException(OrderQueryErrorCode.NOT_FOUND);
    }

    private void invalid() {
        throw new OrderQueryException(OrderQueryErrorCode.INVALID_REQUEST);
    }
}

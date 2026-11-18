package com.msa.orderquery.domain.order.query.controller;

import static com.msa.orderquery.domain.projection.ProjectionFixtures.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.msa.orderquery.domain.projection.event.*;
import com.msa.orderquery.domain.projection.repository.*;
import com.msa.orderquery.domain.projection.service.ProjectionService;
import com.msa.orderquery.global.config.database.QueryDatabaseTestSupport;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 실제 PostgreSQL·MVC로 상품 필터·집계·문의함의 권한과 Query 데이터만의 JOIN을 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderManagementQueryControllerTest extends QueryDatabaseTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ProjectionService projection;
    @Autowired ObjectMapper mapper;
    @Autowired OrderProjectionRepository orders;
    @Autowired InquiryProjectionRepository inquiries;
    @Autowired ProcessedEventRepository receipts;
    UUID secondProduct = UUID.randomUUID();

    @BeforeEach
    void setup() {
        receipts.deleteAll();
        inquiries.deleteAll();
        orders.deleteAll();
    }

    RequestPostProcessor buyer() {
        return jwt().jwt(j -> j.subject(BUYER.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_BUYER"));
    }

    RequestPostProcessor seller() {
        return jwt().jwt(j -> j.subject(SELLER.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_SELLER"));
    }

    UUID addOrder(
            UUID product,
            UUID buyer,
            UUID seller,
            OrderState state,
            DeliveryState delivery,
            int quantity) {
        UUID id = UUID.randomUUID();
        var tree = mapper.valueToTree(order(id, 1, buyer, seller, state));
        var snapshot = (ObjectNode) tree.path("order");
        snapshot.put("productId", product.toString());
        snapshot.put("totalAmount", quantity * 1000L + 3000L);
        if (delivery != null) snapshot.put("delivery", delivery.name());
        ((ObjectNode) snapshot.path("items").get(0)).put("quantity", quantity);
        projection.apply(ORDER_TOPIC, id.toString(), mapper.writeValueAsBytes(tree));
        return id;
    }

    UUID addInquiry(UUID order, int version) {
        UUID id = UUID.randomUUID();
        updateInquiry(id, order, version);
        return id;
    }

    void updateInquiry(UUID id, UUID order, int version) {
        projection.apply(
                INQUIRY_TOPIC,
                order.toString(),
                mapper.writeValueAsBytes(inquiry(id, order, version)));
    }

    @Test
    void exactProductFilterIsOptionalAndCannotExposeOtherAccounts() throws Exception {
        UUID own =
                addOrder(PRODUCT, BUYER, SELLER, OrderState.CONFIRMED, DeliveryState.PREPARING, 2);
        addOrder(secondProduct, BUYER, SELLER, OrderState.CONFIRMED, DeliveryState.PREPARING, 2);
        addOrder(
                PRODUCT,
                UUID.randomUUID(),
                UUID.randomUUID(),
                OrderState.CONFIRMED,
                DeliveryState.PREPARING,
                2);
        for (String base : List.of("/api/v1/orders", "/api/v1/seller/orders")) {
            var auth = base.contains("seller") ? seller() : buyer();
            mvc.perform(get(base).with(auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(2));
            mvc.perform(get(base).param("productId", PRODUCT.toString()).with(auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].orderId").value(own.toString()));
        }
    }

    @Test
    void multiOptionOrderIsCountedOnceAndAllOptionQuantitiesAreSummed() throws Exception {
        UUID id =
                addOrder(PRODUCT, BUYER, SELLER, OrderState.CONFIRMED, DeliveryState.PREPARING, 2);
        var tree = mapper.valueToTree(order(id, 2));
        var snapshot = (ObjectNode) tree.path("order");
        ((tools.jackson.databind.node.ArrayNode) snapshot.path("items"))
                .addObject()
                .put("optionId", UUID.randomUUID().toString())
                .put("label", "다른 옵션")
                .put("quantity", 3);
        snapshot.put("totalAmount", 8000L);
        projection.apply(ORDER_TOPIC, id.toString(), mapper.writeValueAsBytes(tree));
        mvc.perform(get("/api/v1/seller/orders/summary").with(seller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmedOrders").value(1))
                .andExpect(jsonPath("$.data.confirmedQuantity").value(5));
    }

    @Test
    void sellerSummaryCountsOnlyConfirmedSalesAndSeparatesFulfillmentStates() throws Exception {
        addOrder(PRODUCT, BUYER, SELLER, OrderState.CONFIRMED, DeliveryState.PREPARING, 1);
        addOrder(PRODUCT, BUYER, SELLER, OrderState.CONFIRMED, DeliveryState.SHIPPING, 3);
        addOrder(secondProduct, BUYER, SELLER, OrderState.CONFIRMED, DeliveryState.DELIVERED, 5);
        addOrder(PRODUCT, BUYER, SELLER, OrderState.HELD, null, 7);
        addOrder(
                PRODUCT,
                BUYER,
                UUID.randomUUID(),
                OrderState.CONFIRMED,
                DeliveryState.PREPARING,
                20);
        mvc.perform(get("/api/v1/seller/orders/summary").with(seller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalOrders").value(4))
                .andExpect(jsonPath("$.data.confirmedOrders").value(3))
                .andExpect(jsonPath("$.data.preparingOrders").value(1))
                .andExpect(jsonPath("$.data.shippingOrders").value(1))
                .andExpect(jsonPath("$.data.deliveredOrders").value(1))
                .andExpect(jsonPath("$.data.confirmedQuantity").value(9))
                .andExpect(jsonPath("$.data.approximate").value(true));
        mvc.perform(
                        get("/api/v1/seller/orders/summary")
                                .param("productId", PRODUCT.toString())
                                .with(seller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmedOrders").value(2))
                .andExpect(jsonPath("$.data.confirmedQuantity").value(4));
    }

    @Test
    void summaryEmptyRangeAndUnknownProductReturnZeroAndEndBoundaryIsExclusive() throws Exception {
        addOrder(PRODUCT, BUYER, SELLER, OrderState.CONFIRMED, DeliveryState.PREPARING, 2);
        mvc.perform(
                        get("/api/v1/seller/orders/summary")
                                .param("createdTo", NOW.toString())
                                .with(seller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalOrders").value(0))
                .andExpect(jsonPath("$.data.confirmedQuantity").value(0));
        mvc.perform(
                        get("/api/v1/seller/orders/summary")
                                .param("productId", UUID.randomUUID().toString())
                                .with(seller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmedQuantity").value(0));
        mvc.perform(
                        get("/api/v1/seller/orders/summary")
                                .param("createdFrom", NOW.toString())
                                .param("createdTo", NOW.toString())
                                .with(seller()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void inboxCombinesOrdersAndFiltersProductWithoutExposingAddressOrStorageKeys()
            throws Exception {
        UUID first =
                addOrder(PRODUCT, BUYER, SELLER, OrderState.CONFIRMED, DeliveryState.PREPARING, 2);
        UUID second =
                addOrder(
                        secondProduct,
                        BUYER,
                        SELLER,
                        OrderState.CONFIRMED,
                        DeliveryState.SHIPPING,
                        2);
        addInquiry(first, 1);
        addInquiry(second, 2);
        for (String base : List.of("/api/v1/orders/inquiries", "/api/v1/seller/orders/inquiries")) {
            var auth = base.contains("seller") ? seller() : buyer();
            String body =
                    mvc.perform(get(base).with(auth))
                            .andExpect(status().isOk())
                            .andExpect(header().string("Cache-Control", "no-store"))
                            .andExpect(jsonPath("$.data.totalElements").value(2))
                            .andExpect(jsonPath("$.data.content[0].lastMessageAt").exists())
                            .andReturn()
                            .getResponse()
                            .getContentAsString(StandardCharsets.UTF_8);
            assertThat(body)
                    .doesNotContain(
                            "recipientPhone", "private-order-inquiries", "requestKey", "objectKey");
            mvc.perform(get(base).with(auth).param("productId", PRODUCT.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].orderId").value(first.toString()));
        }
    }

    @Test
    void latestBuyerFollowupRequiresReplyEvenWhenEarlierSellerAnswerExists() throws Exception {
        UUID
                order =
                        addOrder(
                                PRODUCT,
                                BUYER,
                                SELLER,
                                OrderState.CONFIRMED,
                                DeliveryState.PREPARING,
                                2),
                inquiry = addInquiry(order, 1);
        mvc.perform(
                        get("/api/v1/seller/orders/inquiries")
                                .with(seller())
                                .param("unansweredOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
        updateInquiry(inquiry, order, 2);
        mvc.perform(
                        get("/api/v1/seller/orders/inquiries")
                                .with(seller())
                                .param("unansweredOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
        updateInquiry(inquiry, order, 3);
        mvc.perform(
                        get("/api/v1/seller/orders/inquiries")
                                .with(seller())
                                .param("unansweredOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].awaitingSeller").value(true));
    }

    @Test
    void orphanAndMismatchedInquiriesStayHiddenAndPaginationHasStableCount() throws Exception {
        UUID own =
                addOrder(PRODUCT, BUYER, SELLER, OrderState.CONFIRMED, DeliveryState.PREPARING, 2);
        addInquiry(own, 1);
        addInquiry(own, 2);
        addInquiry(UUID.randomUUID(), 1);
        UUID foreign =
                addOrder(
                        PRODUCT,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        OrderState.CONFIRMED,
                        DeliveryState.PREPARING,
                        2);
        addInquiry(foreign, 1);
        var first =
                mvc.perform(get("/api/v1/orders/inquiries").with(buyer()).param("size", "1"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.totalElements").value(2))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        var second =
                mvc.perform(
                                get("/api/v1/orders/inquiries")
                                        .with(buyer())
                                        .param("size", "1")
                                        .param("page", "1"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.totalElements").value(2))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        assertThat(mapper.readTree(first).path("data").path("content").get(0).path("id").asText())
                .isNotEqualTo(
                        mapper.readTree(second)
                                .path("data")
                                .path("content")
                                .get(0)
                                .path("id")
                                .asText());
    }

    @Test
    void rolesMethodsAndInvalidInputsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/orders/inquiries")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/orders/inquiries").with(seller()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/seller/orders/inquiries").with(buyer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/seller/orders/summary").with(buyer()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/v1/orders/inquiries")
                                .with(buyer())
                                .param("page", "2147483647")
                                .param("size", "100"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/orders").with(buyer()).param("productId", "invalid"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/seller/orders/summary").with(seller()))
                .andExpect(status().isForbidden());
    }
}

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
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** MVC 응답, 역할/소유권, 필터 조건, Query 지연 경계와 실제 OpenAPI 계약을 검증합니다. */
@SpringBootTest(properties = "springdoc.api-docs.enabled=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(OrderQueryControllerTest.DocumentAccess.class)
class OrderQueryControllerTest extends QueryDatabaseTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ProjectionService projection;
    @Autowired OrderProjectionRepository orders;
    @Autowired InquiryProjectionRepository inquiries;
    @Autowired ProcessedEventRepository receipts;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.core.env.ConfigurableEnvironment environment;
    UUID id, inquiryId;

    @TestConfiguration
    static class DocumentAccess {
        /** 문서 생성 테스트만 접근을 허용하며 실제 업무 보안 체인은 그대로 사용합니다. */
        @Bean
        @Order(0)
        SecurityFilterChain documentation(HttpSecurity http) throws Exception {
            return http.securityMatcher("/v3/api-docs")
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .build();
        }
    }

    @BeforeEach
    void prepare() {
        receipts.deleteAll();
        inquiries.deleteAll();
        orders.deleteAll();
        id = UUID.randomUUID();
        inquiryId = UUID.randomUUID();
        var order = order(id, 2);
        projection.apply(ORDER_TOPIC, id.toString(), mapper.writeValueAsBytes(order));
        var inquiry = inquiry(inquiryId, id, 2);
        projection.apply(INQUIRY_TOPIC, id.toString(), mapper.writeValueAsBytes(inquiry));
    }

    RequestPostProcessor actor(UUID id, String role) {
        return jwt().jwt(j -> j.subject(id.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void buyerAndSellerReadOwnOrderWithAccountEnvelope() throws Exception {
        for (boolean seller : new boolean[] {false, true}) {
            String base = seller ? "/api/v1/seller/orders" : "/api/v1/orders";
            var auth = actor(seller ? SELLER : BUYER, seller ? "SELLER" : "BUYER");
            mvc.perform(get(base).with(auth))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value("COMMON_SUCCESS"))
                    .andExpect(jsonPath("$.message").value("요청이 성공했습니다."))
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].orderId").value(id.toString()))
                    .andExpect(jsonPath("$.data.content[0].address").doesNotExist());
            mvc.perform(get(base + "/" + id).with(auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.address.recipientName").value("합성 수령인"))
                    .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                    .andExpect(jsonPath("$.data.orderNumber").value("SD-" + id))
                    .andExpect(jsonPath("$.data.paymentKey").doesNotExist());
        }
    }

    @Test
    void ownInquiryPagesAndDetailHideObjectAndRequestKeys() throws Exception {
        for (boolean seller : new boolean[] {false, true}) {
            String base =
                    (seller ? "/api/v1/seller/orders/" : "/api/v1/orders/") + id + "/inquiries";
            var auth = actor(seller ? SELLER : BUYER, seller ? "SELLER" : "BUYER");
            mvc.perform(get(base).with(auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1));
            mvc.perform(get(base + "/" + inquiryId).with(auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.messages[1].seller").value(true))
                    .andExpect(jsonPath("$.data.messages[1].body").value("판매자 합성 답변"))
                    .andExpect(jsonPath("$.data.imageIds.length()").value(1))
                    .andExpect(jsonPath("$.data.messages[0].requestKey").doesNotExist())
                    .andExpect(
                            content()
                                    .string(
                                            org.hamcrest.Matchers.not(
                                                    org.hamcrest.Matchers.containsString(
                                                            "private-order-inquiries"))));
        }
    }

    @Test
    void rolesAuthenticationAndMutationBoundaryAreEnforced() throws Exception {
        mvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/orders").with(actor(SELLER, "SELLER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/seller/orders").with(actor(BUYER, "BUYER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/orders").with(actor(BUYER, "BUYER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/orders/" + id + "/checkout").with(actor(BUYER, "BUYER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/env").with(actor(BUYER, "BUYER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void foreignAndNotYetProjectedOrdersAreIndistinguishable() throws Exception {
        for (UUID orderId : List.of(id, UUID.randomUUID())) {
            mvc.perform(get("/api/v1/orders/" + orderId).with(actor(UUID.randomUUID(), "BUYER")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ORDER_QUERY_NOT_FOUND"));
        }
        mvc.perform(get("/api/v1/seller/orders/" + id).with(actor(UUID.randomUUID(), "SELLER")))
                .andExpect(status().isNotFound());
        mvc.perform(
                        get("/api/v1/orders/" + id + "/inquiries/" + inquiryId)
                                .with(actor(UUID.randomUUID(), "BUYER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void listFiltersAreOwnedAndDateBoundariesAreExclusiveAtEnd() throws Exception {
        var foreign = order(UUID.randomUUID(), 1, UUID.randomUUID(), SELLER, OrderState.CONFIRMED);
        projection.apply(
                ORDER_TOPIC, foreign.aggregateId().toString(), mapper.writeValueAsBytes(foreign));
        mvc.perform(
                        get("/api/v1/orders")
                                .with(actor(BUYER, "BUYER"))
                                .param("keyword", "합성")
                                .param("state", "CONFIRMED")
                                .param("delivery", "PREPARING")
                                .param("createdFrom", NOW.toString())
                                .param("createdTo", NOW.plusSeconds(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(
                        get("/api/v1/orders")
                                .with(actor(BUYER, "BUYER"))
                                .param("createdTo", NOW.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get("/api/v1/orders").with(actor(BUYER, "BUYER")).param("keyword", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void invalidInputsAndUnsupportedAcceptUseErrorDto() throws Exception {
        for (String[] pair :
                List.of(
                        new String[] {"page", "-1"},
                        new String[] {"size", "101"},
                        new String[] {"state", "BAD"})) {
            mvc.perform(get("/api/v1/orders").with(actor(BUYER, "BUYER")).param(pair[0], pair[1]))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(
                        get("/api/v1/orders")
                                .with(actor(BUYER, "BUYER"))
                                .param("page", String.valueOf(Integer.MAX_VALUE))
                                .param("size", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ORDER_QUERY_INVALID_REQUEST"));
        mvc.perform(get("/api/v1/orders").with(actor(BUYER, "BUYER")).accept("application/xml"))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    @Test
    void testProfileDoesNotReadLocalEnv() {
        assertThat(
                        environment.getPropertySources().stream()
                                .map(org.springframework.core.env.PropertySource::getName))
                .noneMatch(name -> name.contains(".env"));
    }

    @Test
    void generatedOpenApiMatchesSnapshot() throws Exception {
        String generated =
                mvc.perform(get("/v3/api-docs"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        Path path = Path.of("build/generated/order-query.openapi.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, generated, StandardCharsets.UTF_8);
        var tree = mapper.readTree(generated);
        assertThat(tree.path("paths").size()).isEqualTo(11);
        assertThat(tree)
                .isEqualTo(
                        mapper.readTree(
                                Files.readString(
                                        Path.of("openapi/order-query.openapi.json"),
                                        StandardCharsets.UTF_8)));
    }
}

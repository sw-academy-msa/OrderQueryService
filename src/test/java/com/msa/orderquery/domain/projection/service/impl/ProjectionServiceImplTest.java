package com.msa.orderquery.domain.projection.service.impl;

import static com.msa.orderquery.domain.projection.ProjectionFixtures.*;

import static org.assertj.core.api.Assertions.*;

import com.msa.orderquery.domain.projection.event.*;
import com.msa.orderquery.domain.projection.exception.InvalidProjectionEventException;
import com.msa.orderquery.domain.projection.repository.*;
import com.msa.orderquery.domain.projection.service.ProjectionService;
import com.msa.orderquery.global.config.database.QueryDatabaseTestSupport;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.*;

/** DB 커밋·중복·버전 역전·동시 최초 INSERT와 잘못된 이벤트의 롤백을 실제 PostgreSQL로 검증합니다. */
@SpringBootTest
@ActiveProfiles("test")
class ProjectionServiceImplTest extends QueryDatabaseTestSupport {
    @Autowired ProjectionService service;
    @Autowired OrderProjectionRepository orders;
    @Autowired InquiryProjectionRepository inquiries;
    @Autowired ProcessedEventRepository receipts;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void clean() {
        receipts.deleteAll();
        inquiries.deleteAll();
        orders.deleteAll();
    }

    void apply(ProjectionEvent event) {
        service.apply(ORDER_TOPIC, event.aggregateId().toString(), mapper.writeValueAsBytes(event));
    }

    @Test
    void duplicatesAndOlderInitialSnapshotCannotOverwriteNewest() {
        UUID id = UUID.randomUUID();
        var newest = order(id, 3);
        apply(newest);
        apply(newest);
        apply(order(id, 1, BUYER, null, OrderState.RESERVING));
        assertThat(orders.findById(id).orElseThrow().sourceVersion).isEqualTo(3);
        assertThat(orders.findById(id).orElseThrow().sellerId).isEqualTo(SELLER);
        assertThat(receipts.count()).isEqualTo(2);
    }

    /** 원본 저장 전 나노초와 PostgreSQL 저장 후 마이크로초 차이는 소유권 변경이 아닙니다. */
    @Test
    void databaseTimestampPrecisionDoesNotRejectOlderInitialSnapshot() {
        UUID id = UUID.randomUUID();
        var newest = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(order(id, 3));
        ((tools.jackson.databind.node.ObjectNode) newest.path("order"))
                .put("createdAt", NOW.plusNanos(123457000).toString());
        service.apply(ORDER_TOPIC, id.toString(), mapper.writeValueAsBytes(newest));
        var original =
                (tools.jackson.databind.node.ObjectNode)
                        mapper.valueToTree(order(id, 1, BUYER, null, OrderState.RESERVING));
        ((tools.jackson.databind.node.ObjectNode) original.path("order"))
                .put("createdAt", NOW.plusNanos(123456789).toString());
        service.apply(ORDER_TOPIC, id.toString(), mapper.writeValueAsBytes(original));
        assertThat(orders.findById(id).orElseThrow().sourceVersion).isEqualTo(3);
        assertThat(receipts.count()).isEqualTo(2);
    }

    @Test
    void reusedEventIdAndConflictingVersionRollbackReceiptAndProjection() {
        UUID id = UUID.randomUUID();
        var first = order(id, 1);
        apply(first);
        var differentVersion = order(id, 2);
        var reused =
                ProjectionEvent.builder()
                        .eventId(first.eventId())
                        .schemaVersion(1)
                        .type(first.type())
                        .aggregateId(id)
                        .aggregateVersion(2)
                        .occurredAt(differentVersion.occurredAt())
                        .order(differentVersion.order())
                        .build();
        assertThatThrownBy(() -> apply(reused)).isInstanceOf(InvalidProjectionEventException.class);
        assertThatThrownBy(() -> apply(order(id, 1, BUYER, SELLER, OrderState.HELD)))
                .isInstanceOf(InvalidProjectionEventException.class);
        assertThat(orders.findById(id).orElseThrow().state).isEqualTo(OrderState.CONFIRMED);
        assertThat(receipts.count()).isEqualTo(1);
    }

    @Test
    void mismatchedKeyAndOwnerChangeArePermanentErrors() {
        UUID id = UUID.randomUUID();
        var valid = order(id, 1);
        assertThatThrownBy(
                        () ->
                                service.apply(
                                        ORDER_TOPIC,
                                        UUID.randomUUID().toString(),
                                        mapper.writeValueAsBytes(valid)))
                .isInstanceOf(InvalidProjectionEventException.class);
        assertThat(receipts.count()).isZero();
        apply(valid);
        assertThatThrownBy(
                        () -> apply(order(id, 2, UUID.randomUUID(), SELLER, OrderState.CONFIRMED)))
                .isInstanceOf(InvalidProjectionEventException.class);
        assertThat(receipts.count()).isEqualTo(1);
    }

    @Test
    void inquiryCanArriveBeforeParentAndOlderAnswerCannotOverwriteIt() {
        UUID id = UUID.randomUUID(), parent = UUID.randomUUID();
        var newest = inquiry(id, parent, 2);
        service.apply(INQUIRY_TOPIC, parent.toString(), mapper.writeValueAsBytes(newest));
        service.apply(
                INQUIRY_TOPIC, parent.toString(), mapper.writeValueAsBytes(inquiry(id, parent, 1)));
        assertThat(orders.count()).isZero();
        assertThat(inquiries.findById(id).orElseThrow().sourceVersion).isEqualTo(2);
        apply(order(parent, 1));
        assertThat(inquiries.count()).isEqualTo(1);
    }

    @Test
    void concurrentFirstInsertAndDuplicateDeliveryProduceOneProjection() throws Exception {
        UUID id = UUID.randomUUID();
        var first = order(id, 1);
        var pool = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);
        try {
            var tasks = new ArrayList<Callable<Void>>();
            for (int index = 0; index < 8; index++)
                tasks.add(
                        () -> {
                            start.await();
                            apply(first);
                            return null;
                        });
            var futures = new ArrayList<Future<Void>>();
            tasks.forEach(task -> futures.add(pool.submit(task)));
            start.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(orders.count()).isEqualTo(1);
        assertThat(receipts.count()).isEqualTo(1);
    }

    @Test
    void unknownFieldsAndMalformedJsonAreRejectedWithoutPayloadInError() {
        var payload =
                "{\"address\":\"synthetic-sensitive-value\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.apply(ORDER_TOPIC, "bad-key", payload))
                .isInstanceOf(InvalidProjectionEventException.class)
                .hasMessageNotContaining("synthetic-sensitive-value")
                .hasCause(null);
        assertThat(receipts.count()).isZero();
    }
}

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
    void acceptsMembershipShippingAndRejectsDiscountAboveOriginalWithoutSavingReceipt() {
        UUID id = UUID.randomUUID();
        var event = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(order(id, 1));
        var snapshot = (tools.jackson.databind.node.ObjectNode) event.path("order");
        snapshot.put("shippingFee", 3000L);
        snapshot.put("originalShippingFee", 3000L);
        snapshot.put("membershipShippingDiscount", 3001L);
        snapshot.put("effectiveShippingFee", 0L);
        assertThatThrownBy(
                        () ->
                                service.apply(
                                        ORDER_TOPIC,
                                        id.toString(),
                                        mapper.writeValueAsBytes(event)))
                .isInstanceOf(InvalidProjectionEventException.class);
        assertThat(receipts.count()).isZero();
        snapshot.put("membershipShippingDiscount", 3000L);
        service.apply(ORDER_TOPIC, id.toString(), mapper.writeValueAsBytes(event));
        assertThat(receipts.count()).isEqualTo(1);
        assertThat(orders.findById(id).orElseThrow().snapshot)
                .contains("membershipShippingDiscount");
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
        assertThatThrownBy(() -> apply(reused))
                .isInstanceOf(InvalidProjectionEventException.class)
                .hasMessageContaining("reason=EVENT_ID_REUSED");
        // 같은 버전의 다른 내용이라도 더 늦게 발생하지 않았다면 DLT가 아니라 건너뜁니다.
        apply(order(id, 1, BUYER, SELLER, OrderState.HELD));
        assertThat(orders.findById(id).orElseThrow().state).isEqualTo(OrderState.CONFIRMED);
        assertThat(receipts.count()).isEqualTo(2);
    }

    /** 운영 DLT 재현: Command가 생성 이벤트(RESERVING)와 재고 확보 실패(FAILED)를 같은 버전 1로 발행합니다. */
    @Test
    void reservationFailureWithSameVersionAsCreationIsAppliedNotDeadLettered() {
        UUID id = UUID.randomUUID();
        commandEvent(id, 1, NOW.plusNanos(612400000), commandSnapshot(id, "RESERVING", false));
        commandEvent(id, 1, NOW.plusNanos(681955000), commandSnapshot(id, "FAILED", false));
        var row = orders.findById(id).orElseThrow();
        assertThat(row.state).isEqualTo(OrderState.FAILED);
        assertThat(row.totalAmount).isNull();
        assertThat(row.sellerId).isNull();
        assertThat(receipts.count()).isEqualTo(2);
    }

    /** 운영 DLT 재현: 생성 이벤트와 같은 버전 1의 HELD(회원 배송비와 쿠폰 금액 포함)가 최신 상태로 반영됩니다. */
    @Test
    void heldWithSameVersionAsCreationIsAppliedAndLateCreationIsSkipped() {
        UUID id = UUID.randomUUID();
        var created = commandSnapshot(id, "RESERVING", false);
        commandEvent(id, 1, NOW.plusNanos(36895000), created);
        commandEvent(id, 1, NOW.plusNanos(381624000), commandSnapshot(id, "HELD", true));
        var row = orders.findById(id).orElseThrow();
        assertThat(row.state).isEqualTo(OrderState.HELD);
        assertThat(row.sellerId).isEqualTo(SELLER);
        assertThat(row.totalAmount).isEqualTo(4000L);
        // 다른 복제본이 늦게 전송한 생성 이벤트와 오래된 버전은 최신 모델을 덮어쓰지 않습니다.
        commandEvent(id, 1, NOW.plusNanos(36895000), created);
        commandEvent(id, 2, NOW.plusSeconds(5), commandSnapshot(id, "HELD", true));
        commandEvent(id, 1, NOW.plusNanos(381624000), commandSnapshot(id, "HELD", true));
        row = orders.findById(id).orElseThrow();
        assertThat(row.state).isEqualTo(OrderState.HELD);
        assertThat(row.sourceVersion).isEqualTo(2);
        assertThat(receipts.count()).isEqualTo(5);
    }

    /** OrderJournalImpl처럼 null 필드를 그대로 포함한 Map 스냅샷입니다. */
    Map<String, Object> commandSnapshot(UUID id, String state, boolean reserved) {
        var s = new LinkedHashMap<String, Object>();
        s.put("orderId", id);
        s.put("buyerId", BUYER);
        s.put("sellerId", reserved ? SELLER : null);
        s.put("productId", PRODUCT);
        s.put("productName", reserved ? "합성 주문 상품" : null);
        s.put("brandOrStore", reserved ? "합성 판매처" : null);
        var item = new LinkedHashMap<String, Object>();
        item.put("optionId", OPTION);
        item.put("quantity", 1);
        item.put("label", reserved ? "기본 옵션" : null);
        s.put("items", List.of(item));
        s.put("state", state);
        s.put("delivery", null);
        var address = new LinkedHashMap<String, Object>();
        address.put("recipientName", "합성 수령인");
        address.put("recipientPhone", "synthetic-phone");
        address.put("postalCode", "00000");
        address.put("roadAddress", "합성 도로");
        address.put("detailAddress", "테스트 전용");
        address.put("deliveryMemo", null);
        s.put("address", address);
        s.put("unitPrice", reserved ? 1000L : null);
        s.put("shippingFee", reserved ? 3000L : null);
        if (reserved) {
            s.put("originalShippingFee", 3000L);
            s.put("membershipShippingDiscount", 0L);
            s.put("effectiveShippingFee", 3000L);
            s.put("itemsAmount", 1000L);
            s.put("couponIssueId", null);
            s.put("couponDiscount", 0L);
            s.put("couponState", "NONE");
        }
        s.put("totalAmount", reserved ? 4000L : null);
        s.put("dispatchBusinessDays", reserved ? 2 : null);
        s.put("expiresAt", reserved ? NOW.plusSeconds(600) : null);
        s.put("createdAt", NOW);
        s.put("confirmedAt", null);
        s.put("carrier", null);
        s.put("trackingNumber", null);
        s.put("shippedAt", null);
        s.put("deliveredAt", null);
        s.put("purchaseConfirmedAt", null);
        return s;
    }

    void commandEvent(UUID id, long version, java.time.Instant occurredAt, Map<String, Object> s) {
        var event = new LinkedHashMap<String, Object>();
        event.put("eventId", UUID.randomUUID());
        event.put("schemaVersion", 1);
        event.put("type", "ORDER_UPSERT");
        event.put("aggregateId", id);
        event.put("aggregateVersion", version);
        event.put("occurredAt", occurredAt);
        event.put("order", s);
        service.apply(ORDER_TOPIC, id.toString(), mapper.writeValueAsBytes(event));
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
        UUID id = UUID.randomUUID();
        var event = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(order(id, 1));
        ((tools.jackson.databind.node.ObjectNode) event.path("order"))
                .put("futureField", "synthetic-sensitive-value");
        assertThatThrownBy(
                        () ->
                                service.apply(
                                        ORDER_TOPIC, id.toString(), mapper.writeValueAsBytes(event)))
                .isInstanceOf(InvalidProjectionEventException.class)
                .hasMessageContaining("reason=UNKNOWN_FIELD:order.futureField")
                .hasMessageNotContaining("synthetic-sensitive-value");
        ((tools.jackson.databind.node.ObjectNode) event.path("order")).remove("futureField");
        ((tools.jackson.databind.node.ObjectNode) event.path("order")).put("shippingFee", -1);
        assertThatThrownBy(
                        () ->
                                service.apply(
                                        ORDER_TOPIC, id.toString(), mapper.writeValueAsBytes(event)))
                .hasMessageContaining("reason=CONSTRAINT:order.shippingFee");
        assertThat(receipts.count()).isZero();
    }
}

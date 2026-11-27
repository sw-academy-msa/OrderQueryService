package com.msa.orderquery.domain.projection.service.impl;

import com.msa.orderquery.domain.projection.entity.*;
import com.msa.orderquery.domain.projection.event.*;
import com.msa.orderquery.domain.projection.exception.InvalidProjectionEventException;
import com.msa.orderquery.domain.projection.repository.*;
import com.msa.orderquery.domain.projection.service.ProjectionService;
import com.msa.orderquery.global.config.kafka.ProjectionProperties;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.*;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/** 같은 조회 트랜잭션에서 모델과 처리 이력을 저장하고, 커밋 후에만 Kafka listener가 성공합니다. */
@Service
public class ProjectionServiceImpl implements ProjectionService {
    private static final Logger log = LoggerFactory.getLogger(ProjectionServiceImpl.class);

    /** 같은 집계에 대해 이미 반영된 상태와 비교한 결과입니다. */
    private enum Decision {
        APPLY,
        DUPLICATE,
        STALE
    }

    private final OrderProjectionRepository orders;
    private final InquiryProjectionRepository inquiries;
    private final ProcessedEventRepository processed;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final Clock clock;
    private final ProjectionProperties properties;

    public ProjectionServiceImpl(
            OrderProjectionRepository orders,
            InquiryProjectionRepository inquiries,
            ProcessedEventRepository processed,
            ObjectMapper mapper,
            Validator validator,
            Clock clock,
            ProjectionProperties properties) {
        this.orders = orders;
        this.inquiries = inquiries;
        this.processed = processed;
        this.mapper = mapper;
        this.validator = validator;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    @Transactional(timeout = 10)
    public void apply(String topic, String key, byte[] value) {
        ProjectionEvent event = parse(value);
        boolean inquiry = event.type() == ProjectionEvent.Type.ORDER_INQUIRY_UPSERT;
        validate(event, topic, key, inquiry);
        // 없는 집계의 INSERT와 서로 다른 집계의 동일 eventId 재사용도 같은 순서로 잠급니다.
        var locks =
                new TreeSet<>(
                        List.of(
                                "event:" + event.eventId(),
                                (inquiry ? "inquiry:" : "order:") + event.aggregateId()));
        locks.forEach(processed::lock);
        String eventHash = hash(topic + ":" + key + ":" + json(event));
        var old = processed.findById(event.eventId());
        if (old.isPresent()) {
            if (!old.get().eventHash.equals(eventHash)) invalid("EVENT_ID_REUSED");
            log.debug("주문 조회 이벤트 재전달 무시: eventId={}", event.eventId());
            return;
        }
        String payloadHash = hash(json(inquiry ? event.inquiry() : event.order()));
        if (inquiry) applyInquiry(event, payloadHash);
        else applyOrder(event, payloadHash);
        processed.saveAndFlush(
                ProcessedEvent.builder()
                        .eventId(event.eventId())
                        .eventHash(eventHash)
                        .processedAt(clock.instant())
                        .build());
    }

    private ProjectionEvent parse(byte[] value) {
        // 기존 토픽의 레코드 예산과 맞추며 새 이미지/문의 정책을 정의하지 않습니다.
        if (value == null) invalid("EMPTY_VALUE");
        if (value.length > 4 * 1024 * 1024) invalid("TOO_LARGE");
        Object event;
        try {
            event =
                    mapper.readerFor(ProjectionEvent.class)
                            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                            .readValue(value);
        } catch (UnrecognizedPropertyException exception) {
            // 원문 JSON, 주소, 문의 내용을 예외 원인이나 로그로 전달하지 않고 필드 경로만 남깁니다.
            throw new InvalidProjectionEventException("UNKNOWN_FIELD:" + path(exception));
        } catch (DatabindException exception) {
            throw new InvalidProjectionEventException(
                    "UNREADABLE:" + exception.getClass().getSimpleName() + ":" + path(exception));
        } catch (RuntimeException exception) {
            throw new InvalidProjectionEventException(
                    "MALFORMED:" + exception.getClass().getSimpleName());
        }
        if (!(event instanceof ProjectionEvent result)) {
            throw new InvalidProjectionEventException("NOT_AN_EVENT");
        }
        var violations = validator.validate(result);
        if (!violations.isEmpty()) {
            // 값은 남기지 않고 위반한 필드 경로만 남깁니다.
            invalid(
                    "CONSTRAINT:"
                            + violations.stream()
                                    .map(ConstraintViolation::getPropertyPath)
                                    .map(Object::toString)
                                    .sorted()
                                    .distinct()
                                    .collect(Collectors.joining(",")));
        }
        return result;
    }

    private static String path(DatabindException exception) {
        return exception.getPath().stream()
                .map(r -> r.getPropertyName() == null ? "[" + r.getIndex() + "]" : r.getPropertyName())
                .collect(Collectors.joining("."));
    }

    private void validate(ProjectionEvent event, String topic, String key, boolean inquiry) {
        if (inquiry) {
            var i = event.inquiry();
            if (!properties.inquiryTopic().equals(topic)) invalid("TOPIC_MISMATCH");
            if (i == null || event.order() != null) invalid("SNAPSHOT_TYPE_MISMATCH");
            if (!event.aggregateId().equals(i.id())) invalid("AGGREGATE_ID_MISMATCH");
            if (!i.orderId().toString().equals(key)) invalid("KEY_MISMATCH");
            if (event.aggregateVersion() != i.messages().size()) invalid("VERSION_MESSAGE_COUNT_MISMATCH");
            if (i.messages().stream().map(InquirySnapshot.Message::requestKey).distinct().count()
                    != i.messages().size()) invalid("DUPLICATE_MESSAGE_KEY");
            if (i.images().stream().map(InquirySnapshot.Image::id).distinct().count()
                    != i.images().size()) invalid("DUPLICATE_IMAGE_ID");
        } else {
            var o = event.order();
            if (!properties.orderTopic().equals(topic)) invalid("TOPIC_MISMATCH");
            if (o == null || event.inquiry() != null) invalid("SNAPSHOT_TYPE_MISMATCH");
            if (!event.aggregateId().equals(o.orderId())) invalid("AGGREGATE_ID_MISMATCH");
            if (!event.aggregateId().toString().equals(key)) invalid("KEY_MISMATCH");
            if (o.items().stream().map(OrderSnapshot.Item::optionId).distinct().count()
                    != o.items().size()) invalid("DUPLICATE_OPTION");
            // 재고 확보 실패(FAILED)처럼 판매자와 금액이 없는 상태는 계약상 정상이며 확정 주문만 값을 요구합니다.
            if (o.state() == OrderState.CONFIRMED
                    && (o.sellerId() == null || o.confirmedAt() == null || o.totalAmount() == null))
                invalid("CONFIRMED_WITHOUT_SETTLEMENT");
        }
    }

    private void applyOrder(ProjectionEvent event, String hash) {
        var row =
                orders.findById(event.aggregateId())
                        .orElseGet(() -> OrderProjection.builder().id(event.aggregateId()).build());
        var o = event.order();
        // 구매자·상품은 불변이며 확보 전 null인 판매자만 최초 스냅샷에서 채울 수 있습니다.
        // 생성 시각은 DB 저장 전후 정밀도가 달라질 수 있어 순서 판정에는 집계 버전과 발생 시각만 사용합니다.
        if (row.buyerId != null
                && (!row.buyerId.equals(o.buyerId()) || !row.productId.equals(o.productId())))
            invalid("OWNER_CHANGED");
        if (row.sellerId != null && o.sellerId() != null && !row.sellerId.equals(o.sellerId()))
            invalid("SELLER_CHANGED");
        var decision = decide(event, row.sourceVersion, row.sourceOccurredAt, hash, row.payloadHash);
        if (decision != Decision.APPLY) {
            if (decision == Decision.DUPLICATE && later(event.occurredAt(), row.sourceOccurredAt)) {
                // 같은 내용이 더 늦게 다시 발행되면 발생 시각만 당겨 이후 같은 버전 판정이 도착 순서에 흔들리지 않게 합니다.
                row.sourceOccurredAt = event.occurredAt();
                orders.save(row);
            }
            skipped(event, decision, row.sourceVersion);
            return;
        }
        if (row.sellerId != null && o.sellerId() == null) invalid("SELLER_REMOVED");
        row.buyerId = o.buyerId();
        row.sellerId = o.sellerId();
        row.productId = o.productId();
        row.productName = o.productName();
        row.brandOrStore = o.brandOrStore();
        row.state = o.state();
        row.delivery = o.delivery();
        row.totalAmount = o.totalAmount();
        row.createdAt = o.createdAt();
        row.sourceVersion = event.aggregateVersion();
        row.payloadHash = hash;
        row.snapshot = json(o);
        row.sourceOccurredAt = event.occurredAt();
        row.projectedAt = clock.instant();
        orders.save(row);
    }

    private void applyInquiry(ProjectionEvent event, String hash) {
        var row =
                inquiries
                        .findById(event.aggregateId())
                        .orElseGet(
                                () -> InquiryProjection.builder().id(event.aggregateId()).build());
        var i = event.inquiry();
        if (row.orderId != null
                && (!row.orderId.equals(i.orderId())
                        || !row.buyerId.equals(i.buyerId())
                        || !row.sellerId.equals(i.sellerId()))) invalid("OWNER_CHANGED");
        var decision = decide(event, row.sourceVersion, row.sourceOccurredAt, hash, row.payloadHash);
        if (decision != Decision.APPLY) {
            if (decision == Decision.DUPLICATE && later(event.occurredAt(), row.sourceOccurredAt)) {
                // 같은 내용이 더 늦게 다시 발행되면 발생 시각만 당겨 이후 같은 버전 판정이 도착 순서에 흔들리지 않게 합니다.
                row.sourceOccurredAt = event.occurredAt();
                inquiries.save(row);
            }
            skipped(event, decision, row.sourceVersion);
            return;
        }
        row.orderId = i.orderId();
        row.buyerId = i.buyerId();
        row.sellerId = i.sellerId();
        row.createdAt = i.createdAt();
        row.sourceVersion = event.aggregateVersion();
        row.payloadHash = hash;
        row.snapshot = json(i);
        row.sourceOccurredAt = event.occurredAt();
        row.projectedAt = clock.instant();
        inquiries.save(row);
    }

    /**
     * 높은 버전은 반영하고 낮은 버전은 건너뜁니다. Command가 생성 직후 첫 전이를 생성 이벤트와 같은 버전으로 발행한 사례가 있어,
     * 같은 버전의 다른 내용은 DLT로 보내지 않고 더 늦게 발생한 스냅샷을 최신으로 봅니다. 저장된 발생 시각은 마이크로초라 같은 단위로 비교합니다.
     */
    private Decision decide(
            ProjectionEvent event, long current, Instant currentOccurredAt, String hash, String oldHash) {
        if (event.aggregateVersion() > current) return Decision.APPLY;
        if (event.aggregateVersion() < current) return Decision.STALE;
        if (Objects.equals(hash, oldHash)) return Decision.DUPLICATE;
        return later(event.occurredAt(), currentOccurredAt) ? Decision.APPLY : Decision.STALE;
    }

    private static boolean later(Instant incoming, Instant current) {
        return current != null
                && incoming.truncatedTo(ChronoUnit.MICROS)
                        .isAfter(current.truncatedTo(ChronoUnit.MICROS));
    }

    private void skipped(ProjectionEvent event, Decision decision, long current) {
        // 순서 역전과 중복은 정상 흐름이므로 처리 이력만 남기고 최신 모델을 덮어쓰지 않습니다.
        log.info(
                "주문 조회 이벤트 건너뜀: reason={}, type={}, eventId={}, aggregateId={}, version={}, currentVersion={}",
                decision,
                event.type(),
                event.eventId(),
                event.aggregateId(),
                event.aggregateVersion(),
                current);
    }

    private String json(Object value) {
        return mapper.writeValueAsString(value);
    }

    private String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 지원이 필요합니다.");
        }
    }

    private void invalid(String reason) {
        throw new InvalidProjectionEventException(reason);
    }
}

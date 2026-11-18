package com.msa.orderquery.domain.projection.service.impl;

import com.msa.orderquery.domain.projection.entity.*;
import com.msa.orderquery.domain.projection.event.*;
import com.msa.orderquery.domain.projection.exception.InvalidProjectionEventException;
import com.msa.orderquery.domain.projection.repository.*;
import com.msa.orderquery.domain.projection.service.ProjectionService;
import com.msa.orderquery.global.config.kafka.ProjectionProperties;

import jakarta.validation.Validator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;

/** 같은 조회 트랜잭션에서 모델과 처리 이력을 저장하고, 커밋 후에만 Kafka listener가 성공합니다. */
@Service
public class ProjectionServiceImpl implements ProjectionService {
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
            if (!old.get().eventHash.equals(eventHash)) invalid();
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
        try {
            // 기존 토픽의 레코드 예산과 맞추며 새 이미지/문의 정책을 정의하지 않습니다.
            if (value == null || value.length > 4 * 1024 * 1024) invalid();
            var event =
                    mapper.readerFor(ProjectionEvent.class)
                            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                            .readValue(value);
            if (!(event instanceof ProjectionEvent result)
                    || !validator.validate(result).isEmpty()) {
                throw new InvalidProjectionEventException();
            }
            return result;
        } catch (RuntimeException exception) {
            // 원문 JSON, 주소, 문의 내용을 예외 원인이나 로그로 전달하지 않습니다.
            throw new InvalidProjectionEventException();
        }
    }

    private void validate(ProjectionEvent event, String topic, String key, boolean inquiry) {
        if (inquiry) {
            var i = event.inquiry();
            if (!properties.inquiryTopic().equals(topic)
                    || i == null
                    || event.order() != null
                    || !event.aggregateId().equals(i.id())
                    || !i.orderId().toString().equals(key)
                    || event.aggregateVersion() != i.messages().size()
                    || i.messages().stream()
                                    .map(InquirySnapshot.Message::requestKey)
                                    .distinct()
                                    .count()
                            != i.messages().size()
                    || i.images().stream().map(InquirySnapshot.Image::id).distinct().count()
                            != i.images().size()) invalid();
        } else {
            var o = event.order();
            if (!properties.orderTopic().equals(topic)
                    || o == null
                    || event.inquiry() != null
                    || !event.aggregateId().equals(o.orderId())
                    || !event.aggregateId().toString().equals(key)
                    || o.items().stream().map(OrderSnapshot.Item::optionId).distinct().count()
                            != o.items().size()) invalid();
            if (o.state() == OrderState.CONFIRMED
                    && (o.sellerId() == null || o.confirmedAt() == null || o.totalAmount() == null))
                invalid();
        }
    }

    private void applyOrder(ProjectionEvent event, String hash) {
        var row =
                orders.findById(event.aggregateId())
                        .orElseGet(() -> OrderProjection.builder().id(event.aggregateId()).build());
        var o = event.order();
        // 구매자·상품은 불변이며 확보 전 null인 판매자만 최초 스냅샷에서 채울 수 있습니다.
        // 생성 시각은 DB 저장 전후 정밀도가 달라질 수 있어 순서 판정에는 집계 버전만 사용합니다.
        if (row.buyerId != null
                && (!row.buyerId.equals(o.buyerId())
                        || !row.productId.equals(o.productId())
                        || row.sellerId != null
                                && o.sellerId() != null
                                && !row.sellerId.equals(o.sellerId())
                        || row.sellerId != null
                                && o.sellerId() == null
                                && event.aggregateVersion() > row.sourceVersion)) invalid();
        if (!newer(event.aggregateVersion(), row.sourceVersion, hash, row.payloadHash)) return;
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
                        || !row.sellerId.equals(i.sellerId()))) invalid();
        if (!newer(event.aggregateVersion(), row.sourceVersion, hash, row.payloadHash)) return;
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

    private boolean newer(long incoming, long current, String hash, String oldHash) {
        if (incoming == current && !Objects.equals(hash, oldHash)) invalid();
        return incoming > current;
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

    private void invalid() {
        throw new InvalidProjectionEventException();
    }
}

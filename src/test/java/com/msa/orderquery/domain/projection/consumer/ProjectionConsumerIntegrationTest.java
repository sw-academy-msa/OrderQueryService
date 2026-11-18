package com.msa.orderquery.domain.projection.consumer;

import static com.msa.orderquery.domain.projection.ProjectionFixtures.*;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.msa.orderquery.domain.projection.event.OrderState;
import com.msa.orderquery.domain.projection.repository.*;
import com.msa.orderquery.global.config.database.QueryDatabaseTestSupport;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.context.*;
import org.testcontainers.kafka.KafkaContainer;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 실제 격리 Kafka/PostgreSQL로 재시작·중복·역순·DLT와 DB 장애 후 재처리를 검증합니다. */
@SpringBootTest(
        properties = {"query.events.enabled=true", "spring.kafka.listener.auto-startup=true"})
@ActiveProfiles("test")
class ProjectionConsumerIntegrationTest extends QueryDatabaseTestSupport {
    static final KafkaContainer KAFKA =
            new KafkaContainer("apache/kafka:4.1.0")
                    .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx256m");

    static {
        KAFKA.start();
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(
                            List.of(
                                    new NewTopic(ORDER_TOPIC, 2, (short) 1),
                                    new NewTopic(INQUIRY_TOPIC, 2, (short) 1),
                                    new NewTopic(ORDER_TOPIC + ".DLT", 2, (short) 1),
                                    new NewTopic(INQUIRY_TOPIC + ".DLT", 2, (short) 1)))
                    .all()
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> "order-query-test");
    }

    @Autowired KafkaTemplate<String, byte[]> kafka;
    @Autowired OrderProjectionRepository orders;
    @Autowired InquiryProjectionRepository inquiries;
    @Autowired ProcessedEventRepository receipts;
    @Autowired ObjectMapper mapper;
    @Autowired KafkaListenerEndpointRegistry registry;
    @Autowired JdbcTemplate jdbc;
    @Autowired DefaultErrorHandler errors;

    @BeforeEach
    void clean() {
        registry.getListenerContainer("order-query-projection").stop();
        receipts.deleteAll();
        inquiries.deleteAll();
        orders.deleteAll();
        registry.getListenerContainer("order-query-projection").start();
    }

    @Test
    void restartDuplicatesAndOlderEventsConvergeToLatestOrderAndInquiry() throws Exception {
        registry.getListenerContainer("order-query-projection").stop();
        UUID id = UUID.randomUUID(), inquiryId = UUID.randomUUID();
        var newest = order(id, 3);
        byte[] payload = mapper.writeValueAsBytes(newest);
        kafka.send(ORDER_TOPIC, 0, id.toString(), payload).get(10, TimeUnit.SECONDS);
        kafka.send(ORDER_TOPIC, 0, id.toString(), payload).get(10, TimeUnit.SECONDS);
        kafka.send(
                        ORDER_TOPIC,
                        0,
                        id.toString(),
                        mapper.writeValueAsBytes(order(id, 1, BUYER, null, OrderState.RESERVING)))
                .get(10, TimeUnit.SECONDS);
        kafka.send(
                        INQUIRY_TOPIC,
                        id.toString(),
                        mapper.writeValueAsBytes(inquiry(inquiryId, id, 2)))
                .get(10, TimeUnit.SECONDS);
        assertThat(orders.count()).isZero();
        registry.getListenerContainer("order-query-projection").start();
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {
                            // 비동기 반영 전 빈 결과도 assertion으로 재시도합니다.
                            assertThat(orders.findById(id)).isPresent();
                            assertThat(inquiries.findById(inquiryId)).isPresent();
                            assertThat(orders.findById(id).orElseThrow().sourceVersion)
                                    .isEqualTo(3);
                            assertThat(inquiries.findById(inquiryId).orElseThrow().sourceVersion)
                                    .isEqualTo(2);
                            assertThat(receipts.count()).isEqualTo(3);
                        });
    }

    @Test
    void invalidRecordReachesPrivateDltAndDoesNotBlockFollowingRecord() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] invalid =
                "{\"private\":\"synthetic-only\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        kafka.send(ORDER_TOPIC, 0, id.toString(), invalid).get(10, TimeUnit.SECONDS);
        kafka.send(ORDER_TOPIC, 0, id.toString(), mapper.writeValueAsBytes(order(id, 1)))
                .get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(orders.findById(id)).isPresent());
        try (var consumer =
                new KafkaConsumer<String, byte[]>(
                        Map.of(
                                "bootstrap.servers",
                                KAFKA.getBootstrapServers(),
                                "group.id",
                                "dlt-probe-" + UUID.randomUUID(),
                                "auto.offset.reset",
                                "earliest"),
                        new StringDeserializer(),
                        new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(ORDER_TOPIC + ".DLT"));
            var found = new AtomicInteger();
            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(
                            () -> {
                                for (var record : consumer.poll(Duration.ofMillis(500)))
                                    if (record.key().equals(id.toString())) {
                                        assertThat(record.value()).isEqualTo(invalid);
                                        assertThat(
                                                        record.headers()
                                                                .lastHeader(
                                                                        "kafka_dlt-exception-message"))
                                                .isNull();
                                        assertThat(
                                                        record.headers()
                                                                .lastHeader(
                                                                        "kafka_dlt-exception-stacktrace"))
                                                .isNull();
                                        found.incrementAndGet();
                                    }
                                assertThat(found.get()).isEqualTo(1);
                            });
        }
    }

    @Test
    void databaseFailureRetriesWithoutRecordingSuccessUntilRecovery() throws Exception {
        UUID id = UUID.randomUUID();
        var retries = new AtomicInteger();
        errors.setRetryListeners((record, exception, attempt) -> retries.incrementAndGet());
        jdbc.execute(
                "alter table orders_query.processed_events rename to processed_events_unavailable");
        try {
            kafka.send(ORDER_TOPIC, id.toString(), mapper.writeValueAsBytes(order(id, 1)))
                    .get(10, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(20)).until(() -> retries.get() > 0);
            assertThat(orders.findById(id)).isEmpty();
        } finally {
            jdbc.execute(
                    "alter table orders_query.processed_events_unavailable rename to"
                            + " processed_events");
        }
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {
                            assertThat(orders.findById(id)).isPresent();
                            assertThat(receipts.count()).isEqualTo(1);
                        });
    }
}

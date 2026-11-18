package com.msa.orderquery.domain.projection.service;

/** Kafka 메시지를 자기 조회 DB에 멱등 반영하며 다른 서비스 DB에 접근하지 않습니다. */
public interface ProjectionService {
    void apply(String topic, String key, byte[] value);
}

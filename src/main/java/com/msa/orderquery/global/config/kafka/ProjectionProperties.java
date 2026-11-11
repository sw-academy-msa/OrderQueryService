package com.msa.orderquery.global.config.kafka;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Order/문의 비공개 토픽과 소비 활성 여부만 보유하며 Kafka 서버를 새로 생성하지 않습니다. */
@Validated
@ConfigurationProperties("query.events")
public record ProjectionProperties(
        boolean enabled, @NotBlank String orderTopic, @NotBlank String inquiryTopic) {}

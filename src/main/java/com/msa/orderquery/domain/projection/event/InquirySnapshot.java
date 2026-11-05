package com.msa.orderquery.domain.projection.event;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import lombok.Builder;

import java.time.Instant;
import java.util.*;

/** 주문 당사자만 읽는 문의 이벤트입니다. requestKey와 S3 키는 HTTP DTO로 전달하지 않습니다. */
@Builder
public record InquirySnapshot(
        @NotNull UUID id,
        @NotNull UUID orderId,
        @NotNull UUID buyerId,
        @NotNull UUID sellerId,
        @NotBlank @Size(max = 100) String category,
        @NotNull @Size(min = 1) List<@Valid Message> messages,
        @NotNull @Size(max = 5) List<@Valid Image> images,
        @NotNull Instant createdAt) {
    public InquirySnapshot {
        if (messages != null) messages = List.copyOf(messages);
        if (images != null) images = List.copyOf(images);
    }

    public static class InquirySnapshotBuilder {
        @Override
        public String toString() {
            return "InquirySnapshotBuilder[REDACTED]";
        }
    }

    @Override
    public String toString() {
        return "InquirySnapshot[REDACTED]";
    }

    /** 명령의 요청 키는 이벤트 검증에만 사용하고 구매자 응답에서 제외합니다. */
    @Builder
    public record Message(
            @NotNull UUID requestKey,
            boolean seller,
            @NotBlank @Size(max = 1000) String body,
            @NotNull Instant createdAt) {
        public static class MessageBuilder {
            @Override
            public String toString() {
                return "MessageBuilder[REDACTED]";
            }
        }

        @Override
        public String toString() {
            return "Message[REDACTED]";
        }
    }

    /** 사진 다운로드 권한 발급은 기존 Command API가 담당하며 객체 키를 공개하지 않습니다. */
    @Builder
    public record Image(
            @NotNull UUID id,
            @NotBlank String objectKey,
            @NotBlank String contentType,
            @Positive long sizeBytes) {
        public static class ImageBuilder {
            @Override
            public String toString() {
                return "ImageBuilder[REDACTED]";
            }
        }

        @Override
        public String toString() {
            return "Image[REDACTED]";
        }
    }
}

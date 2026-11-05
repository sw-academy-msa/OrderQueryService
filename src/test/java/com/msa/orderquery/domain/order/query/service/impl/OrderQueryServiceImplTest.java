package com.msa.orderquery.domain.order.query.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.msa.orderquery.domain.order.exception.*;
import com.msa.orderquery.domain.order.query.dto.request.OrderSearchRequest;
import com.msa.orderquery.domain.projection.repository.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/** 불가능한 페이징·날짜 조건은 DB 접근 전에 도메인 예외로 거절합니다. */
class OrderQueryServiceImplTest {
    private final OrderProjectionRepository orders = mock(OrderProjectionRepository.class);
    private final InquiryProjectionRepository inquiries = mock(InquiryProjectionRepository.class);
    private final OrderQueryServiceImpl service =
            new OrderQueryServiceImpl(orders, inquiries, mock(ObjectMapper.class));

    @Test
    void rejectsOffsetOverflowAndInvalidPaging() {
        for (int page : new int[] {-1, Integer.MAX_VALUE}) {
            assertThatThrownBy(
                            () ->
                                    service.list(
                                            UUID.randomUUID(),
                                            false,
                                            new OrderSearchRequest(
                                                    null, null, null, null, null, page, 100)))
                    .isInstanceOfSatisfying(
                            OrderQueryException.class,
                            e ->
                                    assertThat(e.getErrorCode())
                                            .isEqualTo(OrderQueryErrorCode.INVALID_REQUEST));
        }
        verifyNoInteractions(orders, inquiries);
    }

    @Test
    void rejectsEmptyDateRangeBeforeRepositoryAccess() {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        assertThatThrownBy(
                        () ->
                                service.list(
                                        UUID.randomUUID(),
                                        false,
                                        new OrderSearchRequest(null, null, null, now, now, 0, 20)))
                .isInstanceOf(OrderQueryException.class);
        verifyNoInteractions(orders, inquiries);
    }
}

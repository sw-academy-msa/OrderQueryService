package com.msa.orderquery.domain.order.query.dto.response;

import static org.assertj.core.api.Assertions.*;

import com.msa.orderquery.domain.order.query.dto.response.OrderResponses.*;
import com.msa.orderquery.global.response.*;

import org.junit.jupiter.api.Test;

import java.util.*;

/** DTO는 목록을 복사하며 개인정보를 빌더·완성 객체·공통 응답의 문자열로 노출하지 않습니다. */
class OrderResponsesTest {
    @Test
    void responseCollectionsCannotBeModifiedThroughOriginalList() {
        var source = new ArrayList<String>(List.of("synthetic"));
        var page = Page.<String>builder().content(source).page(0).size(20).build();
        source.clear();
        assertThat(page.content()).containsExactly("synthetic");
        assertThatThrownBy(() -> page.content().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void buildersAndEnvelopeDoNotLogPrivateFields() {
        var builder = Address.builder().recipientPhone("synthetic-private-phone");
        assertThat(builder.toString()).doesNotContain("synthetic-private-phone");
        var response = builder.build();
        assertThat(response.toString()).doesNotContain("synthetic-private-phone");
        assertThat(ApiResponse.onSuccess(response, SuccessCode.OK).toString())
                .doesNotContain("synthetic-private-phone");
    }
}

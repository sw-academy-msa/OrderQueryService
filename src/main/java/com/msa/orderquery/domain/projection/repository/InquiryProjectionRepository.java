package com.msa.orderquery.domain.projection.repository;

import com.msa.orderquery.domain.projection.entity.InquiryProjection;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.*;

/** 문의도 부모 주문의 구매자·판매자와 일치하는 로컬 조회 데이터만 반환합니다. */
public interface InquiryProjectionRepository extends JpaRepository<InquiryProjection, UUID> {
    @Query(
            """
            select i from InquiryProjection i where i.orderId = :orderId
            and i.buyerId = :buyer and i.sellerId = :seller
            """)
    Page<InquiryProjection> forOrder(
            @Param("orderId") UUID orderId,
            @Param("buyer") UUID buyer,
            @Param("seller") UUID seller,
            Pageable pageable);

    @Query(
            """
            select i from InquiryProjection i where i.id = :id and i.orderId = :orderId
            and i.buyerId = :buyer and i.sellerId = :seller
            """)
    Optional<InquiryProjection> forOrder(
            @Param("id") UUID id,
            @Param("orderId") UUID orderId,
            @Param("buyer") UUID buyer,
            @Param("seller") UUID seller);
}

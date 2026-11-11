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

    /**
     * 두 조회 모델의 당사자가 일치하는 문의만 반환합니다. 다른 서비스 DB JOIN이 아닙니다. JSON 마지막 메시지의 발신자는 기존 스냅샷에서 읽어 별도 상태 컬럼과
     * 백필을 만들지 않습니다.
     */
    @Query(
            value =
                    """
select i.id as "id",i.order_id as "orderId",o.product_id as "productId",o.product_name as "productName",
  cast(i.snapshot as jsonb)->>'category' as "category",
  cast(i.snapshot as jsonb)->'messages'->-1->>'body' as "lastMessage",
  cast(i.snapshot as jsonb)->'messages'->-1->>'createdAt' as "lastMessageAt",
  not cast(cast(i.snapshot as jsonb)->'messages'->-1->>'seller' as boolean) as "awaitingSeller",
  i.created_at as "createdAt",i.source_version as "sourceVersion",
  i.source_occurred_at as "sourceOccurredAt",i.projected_at as "projectedAt"
from orders_query.inquiries i join orders_query.orders o
  on o.id=i.order_id and o.buyer_id=i.buyer_id and o.seller_id=i.seller_id
where ((:seller=false and o.buyer_id=:actor) or (:seller=true and o.seller_id=:actor))
  and (cast(:productId as uuid) is null or o.product_id=:productId)
  and (:unansweredOnly=false or cast(cast(i.snapshot as jsonb)->'messages'->-1->>'seller' as boolean)=false)
order by i.created_at desc,i.id desc
""",
            countQuery =
                    """
select count(*) from orders_query.inquiries i join orders_query.orders o
  on o.id=i.order_id and o.buyer_id=i.buyer_id and o.seller_id=i.seller_id
where ((:seller=false and o.buyer_id=:actor) or (:seller=true and o.seller_id=:actor))
  and (cast(:productId as uuid) is null or o.product_id=:productId)
  and (:unansweredOnly=false or cast(cast(i.snapshot as jsonb)->'messages'->-1->>'seller' as boolean)=false)
""",
            nativeQuery = true)
    Page<com.msa.orderquery.domain.order.query.dto.response.InquiryInboxRow> inbox(
            @Param("actor") UUID actor,
            @Param("seller") boolean seller,
            @Param("productId") UUID productId,
            @Param("unansweredOnly") boolean unansweredOnly,
            Pageable pageable);
}

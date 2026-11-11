package com.msa.orderquery.domain.projection.repository;

import com.msa.orderquery.domain.projection.entity.OrderProjection;
import com.msa.orderquery.domain.projection.event.*;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.*;

/** 조회 필터와 소유권 조건을 JPA/JPQL에 함께 적용합니다. 서비스에 SQL을 두지 않습니다. */
public interface OrderProjectionRepository extends JpaRepository<OrderProjection, UUID> {
    @Query(
            """
select o from OrderProjection o
where ((:seller = false and o.buyerId = :actor) or (:seller = true and o.sellerId = :actor))
  and (:productId is null or o.productId = :productId)
  and (:state is null or o.state = :state)
  and (:delivery is null or o.delivery = :delivery)
  and (cast(:createdFrom as timestamp) is null or o.createdAt >= :createdFrom)
  and (cast(:createdTo as timestamp) is null or o.createdAt < :createdTo)
  and (:keyword is null or lower(o.productName) like :keyword escape '!'
       or lower(o.brandOrStore) like :keyword escape '!')
""")
    Page<OrderProjection> search(
            @Param("actor") UUID actor,
            @Param("seller") boolean seller,
            @Param("productId") UUID productId,
            @Param("state") OrderState state,
            @Param("delivery") DeliveryState delivery,
            @Param("createdFrom") Instant createdFrom,
            @Param("createdTo") Instant createdTo,
            @Param("keyword") String keyword,
            Pageable pageable);

    @Query(
            """
select o from OrderProjection o where o.id = :id
and ((:seller = false and o.buyerId = :actor) or (:seller = true and o.sellerId = :actor))
""")
    Optional<OrderProjection> owned(
            @Param("id") UUID id, @Param("actor") UUID actor, @Param("seller") boolean seller);

    /**
     * 옵션 수량은 기존 JSON 스냅샷 안에 있으므로 PostgreSQL JSONB 집계만 native @Query로 캡슐화합니다. 새 집계 컬럼이나 백필 없이 기존
     * 데이터도 포함하며 서비스에는 SQL을 두지 않습니다.
     */
    @Query(
            value =
                    """
select count(*) as "totalOrders",
  count(*) filter (where o.state='CONFIRMED') as "confirmedOrders",
  count(*) filter (where o.state='CONFIRMED' and o.delivery='PREPARING') as "preparingOrders",
  count(*) filter (where o.state='CONFIRMED' and o.delivery='SHIPPING') as "shippingOrders",
  count(*) filter (where o.state='CONFIRMED' and o.delivery='DELIVERED') as "deliveredOrders",
  coalesce(sum(case when o.state='CONFIRMED' then
    (select sum(cast(item->>'quantity' as bigint)) from jsonb_array_elements(cast(o.snapshot as jsonb)->'items') item)
    else 0 end),0) as "confirmedQuantity"
from orders_query.orders o where o.seller_id=:seller
  and (cast(:productId as uuid) is null or o.product_id=:productId)
  and (cast(:from as timestamptz) is null or o.created_at>=:from)
  and (cast(:to as timestamptz) is null or o.created_at<:to)
""",
            nativeQuery = true)
    com.msa.orderquery.domain.order.query.dto.response.SellerOrderSummaryRow sellerSummary(
            @Param("seller") UUID seller,
            @Param("productId") UUID productId,
            @Param("from") Instant from,
            @Param("to") Instant to);
}

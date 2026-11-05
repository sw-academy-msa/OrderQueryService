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
}

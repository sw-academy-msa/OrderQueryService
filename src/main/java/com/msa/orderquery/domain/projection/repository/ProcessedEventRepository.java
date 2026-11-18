package com.msa.orderquery.domain.projection.repository;

import com.msa.orderquery.domain.projection.entity.ProcessedEvent;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.*;

import java.util.UUID;

/** 최초 INSERT와 eventId 재사용을 복제본 사이에서 직렬화합니다. PostgreSQL 잠금 SQL은 이 저장소에만 둡니다. */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(
            value =
                    "select true from pg_advisory_xact_lock(hashtextextended(cast(:key as"
                        + " text),729))",
            nativeQuery = true)
    boolean lock(@Param("key") String key);
}

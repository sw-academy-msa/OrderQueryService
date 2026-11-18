-- 주문 조회 서비스의 최초 테이블입니다. 엔티티 enum 값은 애플리케이션이 검증하므로 DB CHECK로 고정하지 않습니다.

create table orders_query.inquiries (
    created_at timestamp(6) with time zone,
    projected_at timestamp(6) with time zone,
    source_occurred_at timestamp(6) with time zone,
    source_version bigint not null,
    buyer_id uuid,
    id uuid not null,
    order_id uuid,
    seller_id uuid,
    payload_hash varchar(64),
    snapshot text not null,
    primary key (id)
);

create table orders_query.orders (
    created_at timestamp(6) with time zone,
    projected_at timestamp(6) with time zone,
    source_occurred_at timestamp(6) with time zone,
    source_version bigint not null,
    total_amount bigint,
    buyer_id uuid,
    id uuid not null,
    product_id uuid,
    seller_id uuid,
    payload_hash varchar(64),
    brand_or_store varchar(200),
    product_name varchar(200),
    delivery varchar(255),
    snapshot text not null,
    state varchar(255),
    primary key (id)
);

create table orders_query.processed_events (
    lock_version bigint not null,
    processed_at timestamp(6) with time zone,
    event_id uuid not null,
    event_hash varchar(64) not null,
    primary key (event_id)
);

create index idx_query_inquiry_order
   on orders_query.inquiries (order_id, created_at, id);

create index idx_query_buyer_created
   on orders_query.orders (buyer_id, created_at, id);

create index idx_query_seller_created
   on orders_query.orders (seller_id, created_at, id);

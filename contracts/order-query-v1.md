# Order Query v1

Order Command의 `OrderJournalImpl`, `OrderInquiryServiceImpl.emit`에 존재하는 계약을 소비합니다. 별도 주문 상태 변경, Saga, 토스 호출을 만들지 않습니다.

| 비공개 토픽 | key | type | aggregateId | aggregateVersion |
|---|---|---|---|---|
| stockdrop.orders.private.v1 | orderId | ORDER_UPSERT | orderId | projectionVersion |
| stockdrop.orders.inquiries.private.v1 | orderId | ORDER_INQUIRY_UPSERT | inquiryId | messages.size |

공통 필드는 eventId(UUID), schemaVersion=1, occurredAt(UTC Instant)입니다. type에 맞는 `order` 또는 `inquiry` 스냅샷만 존재합니다. `order`는 Order Command가 현재 발행하는 상품/옵션/가격/배송지/상태/배송 시각 스냅샷이며 `inquiry`는 당사자 ID/메시지/첨부 메타데이터를 포함합니다. 결제 키/작업 임대/개인키는 이벤트에 없습니다. 연락처와 문의는 비공개 토픽이므로 운영 ACL, TLS/SASL, 보존과 삭제 정책이 필요합니다.

eventId와 정규화한 내용 해시로 중복을 확인하고, 같은 eventId의 내용 변경은 DLT 대상입니다. 같은 집계 버전의 다른 내용도 거부합니다. 오래된 버전은 이력만 기록하고 최신 모델을 덮어쓰지 않습니다. 최초 INSERT와 같은 eventId의 동시 처리는 JPA 저장소의 PostgreSQL advisory transaction lock으로 직렬화합니다. 스냅샷과 처리 이력은 하나의 Query DB 트랜잭션이며 commit 이후에만 offset을 확인합니다.

문의가 먼저 도착해도 저장하며 부모 주문의 조회 모델이 있어야 API에서 읽을 수 있습니다. 문의의 당사자 ID도 부모 주문과 일치해야 합니다. 타인 주문과 없는 주문은 같은 404로 응답합니다. Query 모델 전체는 `orders_query` DB/계정/스키마만 사용합니다. 원본 DB를 읽거나 Account 테이블을 JOIN하지 않습니다.

| 역할 | 메서드 | 경로 |
|---|---|---|
| BUYER | GET | /api/v1/orders |
| BUYER | GET | /api/v1/orders/{id} |
| BUYER | GET | /api/v1/orders/{id}/inquiries |
| BUYER | GET | /api/v1/orders/{id}/inquiries/{inquiryId} |
| SELLER | GET | /api/v1/seller/orders |
| SELLER | GET | /api/v1/seller/orders/{id} |
| SELLER | GET | /api/v1/seller/orders/{id}/inquiries |
| SELLER | GET | /api/v1/seller/orders/{id}/inquiries/{inquiryId} |

목록은 keyword(상품명/판매처), state, delivery, createdFrom, createdTo, page, size를 선택적으로 받습니다. 최신 createdAt/id 순이며 createdFrom 포함, createdTo 미포함입니다. Instant는 UTC `Z` 또는 명시적 offset 입력을 허용하고 응답은 UTC입니다. 문의 목록은 page/size를 받습니다. HTTP 응답 DTO에 이벤트 객체나 JPA 엔티티를 직접 노출하지 않습니다.

문의 사진은 imageIds만 반환합니다. 기존 PurchaseGateway의 `/api/v1/orders/{order}/inquiry-images/{image}/download` 또는 판매자 대응 경로를 호출합니다. 다운로드 권한, 객체 상태 및 S3 서명은 기존 Command에서 다시 확인합니다. 편집/checkout/배송 변경도 기존 Command API를 사용합니다.

추가 화면 정책은 확정하지 않습니다. 정산, 취소/반품 상태, 도착 예정일, 순번은 현재 원본에 없는 값을 임의 생성하지 않습니다. 이벤트 소비 완료와 Outbox 발행 완료는 다르며 Kafka 보존 기간이 지난 모델 재구축은 별도 운영 절차가 필요합니다.

# 상품별 주문 관리와 전체 주문 문의함

## HTTP 계약

모든 API는 일반 Gateway(로컬 8000)에서 Order Query로 연결한다. 기존 ApiResponse와 data.content 페이지 구조를 유지한다. actor는 검증된 JWT sub이며 요청에서 구매자·판매자 ID를 받지 않는다.

| 메서드와 경로 | 권한 | 목적과 선택 조건 |
| --- | --- | --- |
| GET /api/v1/orders | BUYER | 기존 목록에 productId UUID 선택 필터 추가 |
| GET /api/v1/seller/orders | SELLER | 기존 목록에 productId UUID 선택 필터 추가 |
| GET /api/v1/seller/orders/summary | SELLER | productId, createdFrom, createdTo로 본인 주문 집계 |
| GET /api/v1/orders/inquiries | BUYER | 전체 본인 주문 문의함, productId, unansweredOnly, page, size |
| GET /api/v1/seller/orders/inquiries | SELLER | 전체 본인 판매 주문 문의함, 같은 선택 조건 |

기존 일반 Gateway의 명시적 Query GET /orders/* 및 /seller/orders/* 경로가 새 정적 경로도 포함한다. 라우트·보안 코드를 추가로 넓히지 않고 실제 서명 JWT와 HTTP fixture로 경로와 역할을 검증한다. PurchaseGateway의 문의 편집·작성·사진 다운로드 경로는 그대로다. ALB 분기를 운영에 적용할 때 새 정적 GET도 일반 대상 그룹으로 보낸다.

## 집계 의미

- totalOrders는 필터 범위 내 본인 판매 주문 전체 수이며, confirmedOrders는 state=CONFIRMED인 주문 수다.
- preparingOrders, shippingOrders, deliveredOrders는 확정 주문의 각 배송 상태 건수다.
- confirmedQuantity는 확정 주문의 모든 옵션 수량 합계다. 한 주문의 여러 옵션을 여러 주문으로 세지 않는다. HELD나 결제 진행 중 수량은 확정 판매량에 포함하지 않는다.
- 날짜는 주문 생성 시각 기준 UTC Instant이며 createdFrom 포함, createdTo 미포함이다. 비어 있거나 역전된 구간은 400이다. 월 필터가 필요하면 프런트가 KST 월 경계를 UTC로 변환한다.
- 없는 상품이나 주문이 없는 범위는 0을 반환한다. 매출·취소/반품 제외 최종 판매율·수수료·정산을 계산하지 않는다.
- approximate=true는 Kafka 조회 반영 지연이 가능함을 뜻한다. 결제 성공과 재고 확보 판정은 Command 원본을 사용한다.

## 문의함 의미

- 마지막 메시지가 구매자 메시지이면 awaitingSeller=true다. 판매자가 답변하면 false, 구매자가 추가 문의하면 다시 true다. unansweredOnly=true는 이 조건만 조회한다.
- 목록은 문의 생성 시각과 ID의 내림차순이다. page는 0부터, size는 1~100이다. 기존 페이징의 정수 offset 상한도 적용한다.
- 카드는 문의 ID, 주문 ID, 상품 ID와 이름, 분류, 마지막 메시지와 시각, 대기 여부, 반영 메타데이터를 제공한다. 카드 클릭 시 기존 주문별 문의 상세 API를 호출한다.
- 문의와 부모 주문 조회 모델의 구매자·판매자 ID가 모두 일치해야 노출한다. 문의가 먼저 도착했거나 당사자가 불일치하면 전체 문의함에서도 숨긴다. count 쿼리에도 동일 조건을 적용한다.
- 다른 사용자, 배송지·연락처, S3 객체 키와 명령 requestKey를 노출하지 않는다. 모든 응답은 no-store다. 플랫폼 운영자 고객센터 문의와 합치지 않는다.

## 구현 경계

자기 orders_query DB의 두 조회 모델만 JOIN한다. Command DB나 Account를 읽지 않는다. 기존 JSON 스냅샷의 옵션 수량과 마지막 메시지를 즉시 활용하므로 새 테이블·컬럼, 데이터 백필, Kafka 계약 변경은 없다.

일반 목록 필터는 기존 JPA/JPQL @Query를 사용한다. JSON 배열 수량 합계와 마지막 메시지 필터는 PostgreSQL JSONB 기능이 필요하여 저장소의 native @Query에 한정한다. 서비스 코드에 SQL을 넣거나 모든 스냅샷을 메모리로 읽어 집계하지 않는다. 저장소 내부 반환 projection과 HTTP DTO를 구분한다. [PostgreSQL 17 JSON 연산](https://www.postgresql.org/docs/17/functions-json.html)을 기준으로 한다.

테스트는 격리 PostgreSQL의 실제 SQL, 기존 Kafka 이벤트 반영, MVC 권한·소유권, 기존 데이터 조회, OpenAPI 스냅샷을 검증한다. 실제 AWS·ALB 적용과 서비스 통합 부하는 별도다. Git add/commit/push는 실행하지 않는다.

# OrderQueryService

STOCKDROP 주문 조회 서비스입니다. Java 21, Spring Boot 4.1.1, Spring Cloud 2025.1.4를 사용합니다. Account의 빌더, ApiResponse, 도메인 예외 구조를 따르며 JPA 엔티티와 HTTP DTO를 분리합니다. Mapper 클래스를 추가하지 않습니다.

1. 기존 일반 PostgreSQL `stockdrop-account-postgres`(5433)를 실행합니다. Command PostgreSQL(5434)은 읽지 않습니다.
2. `scripts/Initialize-LocalOrderQueryDatabase.ps1`, `scripts/Test-LocalOrderQueryDatabase.ps1`로 `orders_query` DB, 계정, 스키마를 준비합니다. 기존 역할의 비밀번호를 덮어쓰지 않으며 TCP 인증이 실패하면 완료로 처리하지 않습니다.
3. 공용 Kafka를 실행하고 `../OrderCommandService/scripts/Initialize-LocalOrderKafkaTopics.ps1`로 기존 주문 토픽을 준비합니다. 이 프로젝트에 Kafka 서버를 중복 생성하지 않습니다.
4. Order Command의 `ORDER_OUTBOX_PUBLISHER_ENABLED=true`로 발행을 활성화합니다. 이 서비스는 `.env`의 `ORDER_QUERY_EVENTS_ENABLED=true`로 소비를 활성화하고 Java 21로 `./gradlew.bat bootRun`을 실행합니다. 기본 포트 8085, 서비스 이름 `order-query-service`, 로컬 Swagger `/swagger-ui.html`, health `/actuator/health`입니다.
5. 일반 Gateway(8000)를 재시작하면 주문 조회 GET이 Eureka와 LoadBalancer를 통해 연결됩니다. PurchaseGateway의 checkout, 배송 변경, 문의 편집, 사진 다운로드는 기존 Command 경로를 유지합니다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot'
.\gradlew.bat test bootJar
```

기본 소비 그룹은 `order-query-v1`입니다. 동일 Query 복제본만 그룹을 공유하고 AI/Notification과는 독립 그룹을 사용합니다. 업무 변경과 Outbox는 기존 Order Command 책임이며 Query는 이벤트 처리 이력과 조회 모델만 같은 로컬 트랜잭션으로 저장합니다. 중복/역순/동시 INSERT를 제어하며 DB 장애 중에는 offset을 진행하지 않습니다. 영구 계약 오류는 비공개 DLT 발행 확인 후 건너뜁니다.

JWT는 Account의 RS256/JWKS 공개키로 독립 검증합니다. `roles`를 `ROLE_`로 변환하고 본인 주문/문의 소유권을 조회 조건으로 검사합니다. API 응답은 `success/code/message/data`, 오류는 Account의 `code/message/traceId/violations` 형식입니다. 모든 개인정보 응답은 no-store이며 사진 객체 키, 문의 요청 키, 토큰, 결제 키를 반환하지 않습니다. 개인키는 보유하지 않습니다.

Query는 최신 원본을 보장하지 않습니다. 결제 성공 여부는 Order Command의 checkout에서 확인하며, 조회 모델에 주문이 없다는 사실로 결제 실패를 판단하지 않습니다. 순번 보장, 도착 예정일, 취소/반품, 수수료 계산, Redis 캐시, 조회 모델 전체 재구축은 이번 범위에 추가하지 않습니다. 소비 재시작은 보존된 Kafka 레코드 범위 내 복구이며 디스크 유실의 백업이 아닙니다.

- [이벤트 및 API 경계](contracts/order-query-v1.md)
- [실행과 운영 준비](docs/operations.md)
- [실제 컨트롤러 OpenAPI](openapi/order-query.openapi.json)

개발 ddl-auto=update, 운영은 별도 스키마 준비 후 validate입니다. 실제 AWS/RDS 변경이나 ARM64 이미지 검증은 수행한 것으로 간주하지 않습니다. 운영 OpenAPI Gateway 주소는 `query.openapi.gateway-base-url`로 주입합니다. `.env`는 local에서만 읽으며 Git add/commit/push는 사용자가 수행합니다. README와 계약은 추적 대상으로 두고 상세 docs와 AGENTS.md는 기존 Query 프로젝트처럼 로컬 관리합니다.

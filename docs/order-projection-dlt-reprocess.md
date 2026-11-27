# 주문 조회 DLT 40건 재처리 방안 (2026-11-27 조사, 실행하지 않음)

## 원인

운영 DLT stockdrop.orders.private.v1.DLT 40건은 모두 aggregateVersion=1 입니다. Command outbox를 대조하면 같은 주문에 버전 1 이벤트가 두 건씩 있습니다. 하나는 주문 생성(ORDER_CREATED, state=RESERVING)이고, 다른 하나는 바로 뒤의 첫 Saga 전이(HELD, FAILED, 또는 RETRY로 인한 RESERVING)입니다.

Query는 먼저 도착한 RESERVING v1을 반영한 뒤 "같은 버전, 다른 내용"을 계약 위반(InvalidProjectionEventException)으로 보고 DLT로 보냈습니다. 금액 null(FAILED)이나 HELD 금액 필드 자체는 원인이 아닙니다. 그 결과 재고 확보 실패 주문은 Query에 RESERVING v1로 멈춰 있습니다. 뒤에 v2 이상 이벤트가 온 주문은 이미 최신 상태로 수렴했습니다.

Command 쪽 버전 중복(OrderWriteStoreImpl.create 직후 saga.advance의 첫 전이가 projectionVersion 1을 다시 사용)은 Order Command에서 따로 고쳐야 합니다. Query는 이번 수정으로 같은 버전이면 occurredAt이 더 늦은 스냅샷을 반영하고, 늦지 않으면 INFO 로그를 남기고 건너뜁니다.

## 재소비가 안전한 이유

- DLT로 간 이벤트는 처리 트랜잭션이 롤백되어 processed_events에 eventId가 없습니다. 재처리 때 처음 처리하는 이벤트로 판정됩니다.
- 판정은 도착 순서가 아니라 버전과 occurredAt으로 합니다. 현재 Query 행 버전이 2 이상이면 STALE로 건너뛰고 처리 이력만 남깁니다. 현재 행이 RESERVING v1이고 DLT 이벤트가 더 늦게 발생했다면 APPLY되어 FAILED 또는 HELD로 바로잡힙니다.
- 같은 레코드를 두 번 넣어도 두 번째는 eventId와 해시가 같아 무시됩니다(멱등).
- stockdrop.orders.private.v1을 구독하는 그룹은 order-query-v1뿐입니다(2026-11-27 kafka-consumer-groups 확인). 원래 토픽에 다시 발행해도 다른 서비스에 중복이 생기지 않습니다.
- key(orderId)를 그대로 쓰면 기본 파티셔너가 원래 파티션으로 보냅니다.

## 순서

1. 이 수정이 들어간 Query 이미지가 운영 query 두 대에 모두 배포되었는지 확인합니다. 구버전이 남아 있으면 다시 DLT로 갑니다.
2. 재처리 전 상태를 기록합니다(읽기). DLT key 목록으로 orders_query.orders의 state와 source_version을 조회합니다.
3. 실행 전 DLT 파티션별 end offset(kafka-get-offsets.sh --topic stockdrop.orders.private.v1.DLT)과 원래 토픽 end offset을 기록합니다.
4. Kafka 호스트에서 DLT를 key와 함께 처음부터 읽어 원래 토픽으로 다시 발행합니다. 값은 한 줄 JSON이라 탭 구분이 안전합니다. 원문을 화면이나 파일에 남기지 않도록 파이프로만 넘깁니다. 고정 소비 그룹을 쓰지 않아(콘솔 소비자는 매번 새 임시 그룹) 항상 처음부터 읽으므로, 실패하면 같은 명령을 처음부터 다시 실행하면 됩니다.

```sh
docker exec -e KAFKA_OPTS= stockdrop-kafka sh -c '
/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server 127.0.0.1:9092 \
  --topic stockdrop.orders.private.v1.DLT --from-beginning --max-messages 40 --timeout-ms 30000 \
  --property print.key=true --property key.separator="	" \
| /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server 127.0.0.1:9092 \
  --topic stockdrop.orders.private.v1 --producer-property acks=all \
  --property parse.key=true --property key.separator="	"'
echo "exit=$?"
```

  - 재실행 안전성은 읽은 위치가 아니라 소비자 멱등성에 기대어 있습니다. 일부만 발행된 뒤 다시 전체를 보내도, 이미 반영된 eventId는 해시가 같아 무시되고 나머지만 처리됩니다.
  - 완료 판정은 원래 토픽 end offset 증가량이 DLT 건수(40)와 같고 종료 코드가 0인지로 합니다. 다르면 같은 명령을 다시 실행합니다(초과분은 중복으로 무시됨).
  - 실행 시점의 DLT 건수가 40건이 아니면 --max-messages를 그 건수에 맞춥니다.
5. 확인(읽기)
  - DLT 새 레코드 0건: 재처리 뒤 DLT end offset이 늘지 않았는지 확인합니다.
  - Query 로그에 "주문 조회 이벤트 건너뜀: reason=STALE" 또는 반영 결과만 있고 "DLT 전송"이 없어야 합니다.
  - 2단계 목록에서 RESERVING v1이던 주문이 Command의 purchase_orders.state와 같아졌는지 확인합니다.
  - order-query-v1 lag이 0으로 돌아왔는지 확인합니다.
6. DLT 원본은 보존 기간 동안 그대로 둡니다(삭제 불필요).

## 하지 않는 것

- DLT 레코드 헤더(kafka_dlt-*)는 재발행하지 않습니다. 콘솔 프로듀서는 값과 key만 보냅니다.
- Query DB를 직접 수정하지 않습니다.

# 쿠폰 주문 정산

새 ORDER_UPSERT의 nullable itemsAmount, couponIssueId, couponDiscount, couponState를 수용하고 주문 상세에 표시합니다. 판매자 정산은 itemsAmount가 있으면 할인 전 상품 금액을 사용합니다. 플랫폼 쿠폰과 회원 배송비 지원이 판매자 정산을 줄이지 않습니다. 과거 이벤트는 nullable 필드를 직렬화에서 제외하여 중복 판정 해시를 유지하고 기존 상품 금액 보정식으로 계산합니다.

추가 DB 컬럼은 없으며 기존 스냅샷 JSON에 저장합니다. OrderCommand 쿠폰 기능을 켜기 전에 이 버전을 먼저 배포합니다. 쿠폰 주문이 생긴 뒤에는 itemsAmount를 이해하는 이 버전이 되돌리기 하한이며 스냅샷과 이벤트를 삭제하지 않습니다. 운영 배포는 별도 승인입니다.

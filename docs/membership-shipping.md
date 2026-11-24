# 멤버십 배송비 스냅샷

Order Command가 확정한 원 배송비와 플랫폼 지원액을 조회합니다. `shippingFee`는 기존과 같은 할인 전 배송비입니다. `originalShippingFee`도 원 배송비이며 `membershipShippingDiscount`는 플랫폼 부담액, `effectiveShippingFee`는 고객이 실제 낸 배송비입니다.

회원이 상품 20,000원과 배송비 3,000원 주문에서 무료배송을 받으면 `totalAmount=20000`, `shippingFee=3000`, `membershipShippingDiscount=3000`, `effectiveShippingFee=0`입니다. 상품 정산은 `totalAmount-effectiveShippingFee`로 20,000원을 유지합니다. 이 서비스는 실제 배송비 송금을 수행하지 않습니다.

새 세 필드가 모두 없는 과거 이벤트는 할인 0원으로 읽습니다. 새 필드는 null일 때 직렬화하지 않아 과거 이벤트의 처리 해시를 유지합니다. 일부만 있거나 할인액이 원 배송비를 넘는 스냅샷은 영구 계약 오류로 거절합니다. Command가 새 필드를 발행하기 전에 이 consumer를 배포해야 합니다.

할인 주문이 생긴 뒤에는 상품 정산을 `totalAmount-shippingFee`로 계산하는 과거 이미지로 되돌리지 않습니다. 멤버십 할인 지원을 이해하는 호환 이미지가 롤백 기준입니다.

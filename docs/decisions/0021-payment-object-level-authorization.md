# 0021. 결제 조회는 소유자 또는 ADMIN만 허용하고 타인 결제는 404로 숨긴다

- 상태: 제안 (SA 변경 요청 필요. 2026-10-28 개정)
- 날짜: 2026-10-27
- 결정자: 안준현
- 관련 AI 판단: [결제 상세 설계 빈틈 결정](../ai-judgments/junhyun/2026-10-27-payment-design-gaps.md)
- 관련 결정: [0006](0006-gateway-single-access-policy.md), [0008](0008-internal-trust-and-auth-design.md), [0019](0019-payment-new-key-existing-payment.md)

## 맥락

SA 11-4는 `GET /api/v1/payments/{paymentId}`를 "인증"만 요구한다. 소유자 검사가 없고 `paymentId`는 순차 BIGINT라 로그인한 누구든 ID를 바꿔 가며 다른 사용자의 결제(금액, 예매 ID, 승인·환불 시각)를 볼 수 있다. 객체 단위 인가 누락(BOLA/IDOR)이다. 결제는 가장 민감한 데이터라 MVP에서도 막는다.

신원 전달 방식은 SA 그대로다. Gateway가 JWT를 검증하고 `X-User-Id`·`X-User-Role`을 붙이며 클라이언트가 보낸 같은 이름의 헤더는 제거한다. Payment는 공통 모듈의 `GatewayIdentityFilter`로 이 값을 요청 속성에 올려 쓴다([0006](0006-gateway-single-access-policy.md)). 서비스 사이 신뢰 모델의 보강 여부는 [0008](0008-internal-trust-and-auth-design.md)에서 따로 설계 중이며, 이 결정은 그 결과를 따른다.

## 결정

1. **일반 사용자**는 자기 결제만 조회한다. 조회 쿼리는 `paymentId`와 `userId`를 함께 조건으로 쓴다(`WHERE id = :id AND user_id = :userId`). 먼저 조회하고 나중에 비교하는 방식은 쓰지 않는다.
2. **ADMIN**은 모든 결제를 조회한다. ADMIN 여부는 Gateway가 검증한 인증 정보(`X-User-Role`을 `GatewayIdentityFilter`가 올린 값)로만 판단한다. 요청 본문이나 쿼리 값으로 역할을 받지 않는다.
3. **타인의 결제는 존재하지 않는 결제와 같은 404 `NOT_FOUND`**로 응답한다. 상태 코드, 에러 코드, 메시지, 본문 구조가 같아야 하고, 응답에 결제 ID가 들어가지 않는다.
4. `X-User-Id`는 클라이언트 입력을 직접 신뢰하지 않는다. Gateway(신뢰된 인증 계층)가 검증해 넣은 값만 쓴다. Payment로 가는 직접 접근과 헤더 위조는 SA 9-2의 네트워크 규칙으로 막는다(서비스 포트는 compose 내부 네트워크 전용, 호스트 포트는 loopback 바인딩, 외부 노출은 Gateway뿐).
5. **`paymentId`를 받는 모든 API**에 같은 규칙을 적용한다. 컨트롤러는 소유자 조건이 들어간 조회 메서드(`findByIdAndUserId` 등)만 쓰고, 조건 없는 `findById`를 외부 요청 경로에서 부르지 않는다. 앞으로 추가될 환불 조회·취소 API도 같다.
   - 승인 `POST /payments`: 토큰의 `sub`가 `X-User-Id`와 같아야 하고(SA 7-3), 멱등 기록은 `(user_id, idem_key)`로 조회한다(0017). 기존 결제 연결은 사용자 일치를 확인한다(0019).
   - 이벤트로 시작하는 환불(`RefundRequested`)은 사용자 요청이 아니라 서비스 사이 이벤트라 이 규칙의 대상이 아니다. 이벤트 위조는 Kafka 접근 통제(내부망), Kafka-UI READONLY, 소비자의 교차 검증([0020](0020-payment-refund-dlq-manual-check.md), [0027](0027-reservation-payment-event-contract.md))으로 막고, 막지 못한 건은 대사([0029](0029-payment-reservation-reconciliation.md))가 찾는다.
   - 운영자 복구는 HTTP API가 아니라 Ops 명령이라 이 규칙의 대상이 아니다. 접근 통제는 [0028](0028-payment-ops-commands.md)을 따른다.
6. 타인 결제 접근 시도는 외부 응답은 같게 하되 내부 로그에는 WARN으로 남긴다(요청 사용자 ID, 대상 `paymentId`, `traceId`). 응답으로는 구분할 수 없어도 운영자는 추적할 수 있어야 한다.

MVP에서는 `paymentId`를 BIGINT로 유지한다. 추측하기 어려운 외부 식별자(`payment_no`나 UUID)로 바꾸는 일은 별도 개선 사항으로 관리한다. 소유자 검사가 있으므로 ID 추측만으로는 정보가 새지 않는다.

## 검토한 대안

- **타인 결제에 403**: 의미는 정확하지만 "그 ID의 결제가 존재한다"를 알려 준다. ID 추측으로 존재 여부를 알아내는 열거 공격을 막지 못한다.
- **외부 식별자(`payment_no`) 경로 사용**: 추측이 어려워 방어가 한 겹 더 생긴다. SA의 API 경로·응답(`paymentId`)과 `Location` 헤더, 클라이언트 연동이 모두 바뀐다. 소유자 검사로 충분히 막히고 변경 범위가 커서 후속 과제로 둔다.

## 트레이드오프

| | 얻는 것 | 감수하는 것 |
|---|---|---|
| 쿼리에 userId 조건 | 인가 누락 실수가 구조적으로 줄고 다른 사용자의 데이터가 메모리에 올라오지 않는다 | ADMIN과 일반 사용자의 조회 메서드가 갈라진다 |
| 타인에게 404 | 존재 여부가 노출되지 않는다 | "왜 안 보이지"를 응답만으로는 구분할 수 없어 고객 문의 때 내부 로그를 봐야 한다 |
| 모든 paymentId API에 같은 규칙 | 새 API가 규칙을 빠뜨리기 어렵다 | 규칙을 테스트와 코드 리뷰 기준으로 유지해야 한다 |

## 결과

- SA 변경 요청: 11-4 `GET /payments/{paymentId}`의 권한을 "인증 · 소유자 또는 ADMIN"으로 바꾸고 11-3에 타인 결제는 `NOT_FOUND`임을 적는다.
- `api.md`에 조회 규칙과 내부 로그 항목을 쓴다.
- 테스트(매트릭스): 소유자 200, 타인 404, 미존재 404(두 응답 본문이 `traceId`를 제외하고 같다), ADMIN 200, 인증 헤더 없음 401, 위조 `X-User-Id`를 Gateway가 제거해 막힘, 직접 접근 불가(통합 환경).
- 후속: 외부 식별자 분리는 별도 개선 사항이다. 서비스 간 신뢰 모델 보강은 [0008](0008-internal-trust-and-auth-design.md) 결과를 따른다.
- SA 12-1은 "순번 PK를 밖에 노출하지 않는다"고 적지만 11-5는 `paymentId`(BIGINT)를 응답에 쓴다. 이 결정은 소유자 조건으로 정보 노출을 막고 BIGINT를 유지하므로, SA의 두 서술이 어긋난다는 정정을 S-25로 올린다.

## 개정 이력

- 2026-10-28: 조회 API 구현(ISE-36) 반영. 결정 6의 구분 로그를 위해 소유자 조회가 빈 경우에 한해 `existsById`를 로그 전용으로 부른다(응답에는 쓰지 않는다). 결정 내용은 바뀌지 않았다.
- 2026-10-28: 결정 5에 이벤트 위조 방어 수단(Kafka-UI READONLY, 소비자 교차 검증, 대사)과 Ops 명령의 접근 통제 위치를 더했다. SA 12-1과 11-5의 불일치(검토 L7)를 결과에 적었다. 결정 내용은 바뀌지 않았다.

## 면접에서 설명할 한 줄

순차 ID로 남의 결제가 보이는 IDOR를 소유자 조건 쿼리와 "타인은 404" 응답으로 막았고, 인증 정보는 Gateway가 검증한 값만 믿으며, 앞으로 생길 모든 결제 ID API에 같은 규칙을 적용하게 했다.

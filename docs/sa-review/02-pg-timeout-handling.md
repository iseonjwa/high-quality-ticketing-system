# PG 승인 타임아웃 처리 방식

> 2026-10-23 · 질문: "원래 PG사의 타임아웃은 어떻게 처리돼?"
> 관련 항목: [01-sa-1st-review.md](01-sa-1st-review.md)의 C1 (PG 타임아웃은 실패가 아니라 "결과 모름")

실제 PG 연동에서는 승인 타임아웃을 실패로 보지 않고 **"결과를 모르는 상태"**로 다룬다. 이 상태를 정리하는 방법은 PG사마다 크게 두 가지다.

## 1. 망취소: 모르면 일단 취소 (나이스페이·이니시스 등 국내 전통 PG)

- **규칙**: 승인 API를 호출하다 read-timeout이 나면 망취소 API를 호출해야 한다. 나이스페이 문서: "`Read-timeout`이 발생된 경우 반드시 망 취소를 요청하여 결제 정보 불일치를 방지 해주세요."
- **동작**: 승인이 실제로 됐든 안 됐든, 그 거래를 취소 상태로 정리한다. 가맹점은 이 결제를 "실패"로 확정할 수 있다.
- **나이스페이 기준값**
  - 타임아웃: connect 5초, read 30초
  - 망취소 호출: `POST /v1/payments/netcancel`에 가맹점 주문번호(`orderId`)를 보낸다
  - 승인 요청 후 1시간 안에만 할 수 있다
  - `orderId`가 고유할 때만 정상 처리된다
- **일반 취소와의 구분**: 망취소는 승인 결과를 모를 때만 쓴다. 사용자가 거래를 취소하는 경우에는 일반 취소 API를 쓴다.

## 2. 멱등 재시도 + 조회: 모르면 다시 물어봄 (토스페이먼츠)

- **같은 요청 다시 보내기**: 승인 요청에 `Idempotency-Key` 헤더를 붙여 두면, 타임아웃 뒤 같은 키로 다시 보내도 안전하다. 결제가 두 번 일어나지 않고 첫 요청의 응답이 그대로 돌아온다.
  - 첫 요청이 아직 처리 중이면 `409 IDEMPOTENT_REQUEST_PROCESSING`이 돌아온다. 잠시 뒤 다시 요청하면 된다.
  - 멱등키는 15일간 유효하다.
  - 에러를 받았을 때 키를 바꿔서 재시도하는 것은 위험하다고 문서가 경고한다.
- **상태 조회**: `GET /v1/payments/orders/{orderId}`로 결제 상태를 직접 확인할 수도 있다. `DONE`이면 승인, `ABORTED`면 실패다.
- **웹훅**: 토스는 "결제의 결과를 받으려면 반드시 웹훅을 연동해야 됩니다"라고 안내한다. 응답을 놓쳐도 PG가 결과를 따로 알려 준다.
- 참고: 토스 문서에는 결제 인증 후 승인 기한이 한 곳에는 10분, 다른 곳에는 30분으로 적혀 있어 정확한 값은 확인하지 못했다.

## 두 방식의 공통점

1. **가맹점 주문번호가 고유해야 한다.** 망취소도, 조회도, 멱등키도 모두 이 번호로 "어떤 거래인지"를 찾는다.
2. **최후 안전망은 정산 대사다.** 위 방법으로도 놓친 건은 PG의 정산 데이터와 우리 DB를 주기적으로 맞춰 보며 잡는다. (문서로 확인한 내용이 아니라 업계 일반 관행)

## 우리 프로젝트에 적용한다면

Mock PG에 아래 API 세 개를 둔다.

- 승인 (`orderId` 기준 멱등)
- 조회
- 망취소

Payment 서비스 처리 순서:

1. 승인 요청에서 read-timeout이 나면 **망취소를 시도**한다.
2. 망취소가 성공하면 결제 실패로 확정하고, 좌석 반환 보상을 진행한다.
3. 망취소도 실패하거나 타임아웃이 나면 결제를 **UNKNOWN 상태로 둔다**. 좌석은 그대로 잡아 두고, 스케줄러가 조회 API로 결과를 확정한다.
4. Circuit Breaker의 fallback에서 "실패 확정"으로 처리하지 않는다. "보류(UNKNOWN) 기록"까지만 한다.

티켓팅은 좌석이 걸려 있어서 결과를 빨리 확정하는 것이 중요하다. 그래서 "모르면 취소"하는 망취소를 기본으로 두고, 조회를 보조로 쓰는 쪽이 잘 맞는다. 면접에서도 "국내 PG의 망취소 관행을 Mock PG에 재현했다"는 이야기로 설명하기 좋다.

## 출처

- [토스페이먼츠 멱등키 문서](https://docs.tosspayments.com/reference/using-api/idempotency-key)
- [토스페이먼츠 API 레퍼런스 (결제 조회·상태)](https://docs.tosspayments.com/reference)
- [토스페이먼츠 결제 흐름 (웹훅)](https://docs.tosspayments.com/guides/v2/get-started/payment-flow)
- [나이스페이 매뉴얼: 취소·망취소](https://github.com/nicepayments/nicepay-manual/blob/main/api/cancel.md)
- [나이스페이 매뉴얼: 타임아웃 정보](https://github.com/nicepayments/nicepay-manual/blob/main/common/preparations.md)
- [나이스페이 매뉴얼: 결제창 승인](https://github.com/nicepayments/nicepay-manual/blob/main/api/payment-window-server.md)

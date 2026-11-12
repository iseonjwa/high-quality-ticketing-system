---
title: F1-a에서 결제 서버를 죽여도 NOT_SENT가 거의 안 생기는 이유와 테스트로 대신 보여 주는 근거
date: 2026-11-10
updated: 2026-11-10
asker: kangmin
level: 중급
tags: [fault-injection, payment, saga, not-sent, recheck, test]
---

# F1-a에서 결제 서버를 죽여도 NOT_SENT가 거의 안 생기는 이유와 테스트로 대신 보여 주는 근거

## 질문
F1 결정 1("F1-a에서 `NOT_SENT`가 0건이면 통합 테스트로 대신 보여 줄지")이 무슨 뜻인지 이해가 안 된다. 처음부터 설명해 달라.

## 짧은 답
- 자동결제 한 건은 "DB에 `REQUESTED` 저장(TX1) → PG 호출 → 결과 저장(TX2)" 순서다.
- 결제 서버가 죽은 순간이 어디냐에 따라 남는 상태가 다르다. `NOT_SENT`는 TX1과 PG 호출 사이에 죽었을 때만 생긴다.
- 그 사이는 코드상 빌링키 복호화와 요청 객체 만들기뿐이라 매우 짧다. 대부분의 시간은 PG 응답 대기(300ms)라, `docker kill`은 거의 "PG가 이미 받은 뒤"에 걸린다.
- 그래서 서버에서는 `APPROVED` 수렴과 이중 결제 없음을 보이고, `NOT_SENT` 경로는 그 순간을 강제로 만드는 통합 테스트로 보인다.

## 자세히

### 0. 용어
| 줄임말 | 풀어 쓴 말 | 뜻 |
|---|---|---|
| PG | Payment Gateway, 결제대행사 | 카드사와 가맹점 사이에서 승인을 대신 처리하는 회사(토스페이먼츠 등). 이 프로젝트의 측정·F1은 가짜 PG인 Mock PG를 쓴다 |
| TX | Transaction, 데이터베이스 트랜잭션 | 여러 DB 쓰기를 "전부 반영 또는 전부 취소"로 묶는 단위. 커밋하면 확정된다 |
| TX1 | 첫 번째 트랜잭션 | PG를 부르기 전에 결제 행을 `REQUESTED`로 저장하고 커밋한다(ADR 0018 7단계) |
| TX2 | 두 번째 트랜잭션 | PG 결과를 받은 뒤 `APPROVED`·`DECLINED`와 후속 이벤트를 저장한다(ADR 0018 10단계) |
| `REQUESTED` | 요청됨 | "PG에 승인을 요청하려고 한다"는 중간 상태. 최종 결과가 아니다 |
| `APPROVED` / `DECLINED` | 승인 / 거절 | 결제의 최종 상태 |
| `NOT_SENT` | 보내지 않음 | `DECLINED`의 사유 코드. "PG에 요청이 닿지 않아 돈이 나가지 않았다"는 뜻이라 추첨은 다시 결제를 시도한다 |
| 망취소 | 네트워크 취소(늦은 승인 방지) | PG에 "이 주문 번호로 나중에 승인 요청이 와도 받지 마라"고 미리 막는 요청. 코드 이름은 `preventLateApproval` |
| 재확인 스케줄러 | `PaymentRecheckService` | 일정 간격으로 `REQUESTED`로 남은 결제를 찾아 PG에 상태를 물어 최종 상태로 맞추는 작업 |
| `pg-approve` 허가 | 벌크헤드(동시 호출 수 제한) 자리 | PG 승인을 동시에 10건까지만 부르게 하는 표. 자리를 못 얻으면 바로 503 |
| SIGKILL | 강제 종료 신호(`kill -9`) | 프로세스가 정리할 틈 없이 즉시 죽는다. `docker kill`의 기본값이다. `docker stop`은 정상 종료 신호(SIGTERM)를 먼저 보낸다 |
| F1-a | 장애 주입 시험 1번의 a 항목 | 자동결제 중 결제 서버를 강제 종료하는 시험(`f1-fault-injection.md`) |

### 왜 트랜잭션을 둘로 나누나
PG 응답은 수백 ms에서 몇 초까지 걸린다. 하나의 트랜잭션 안에서 PG를 부르면 그동안 DB 연결을 계속 붙잡아 연결 풀이 바닥난다. 그래서 PG 호출은 트랜잭션 밖에서 하고, 앞뒤를 TX1·TX2로 나눈다. 대가는 TX1 커밋과 PG 호출 사이에 죽으면 "PG는 모르는 `REQUESTED`"가 남는다는 점이고, 이것을 재확인 스케줄러가 `NOT_SENT`로 정리한다(ADR 0018 "결과" 표).

### 1. 결제 한 건의 순서
`BillingPaymentService.approve`(59~81행):

| 단계 | 코드 | DB·PG 상태 |
|---|---|---|
| ① 허가 획득 | `approvalGate.acquire()` | 아무것도 없음 |
| ② TX1 | `tx.createBillingRequested` | `payments`에 `REQUESTED` 커밋 |
| ③ 사이 | `callPg` 안의 `encryptor.decrypt`, `ApproveCommand` 생성 | 그대로 |
| ④ PG 호출 | `pgClients.billing().approveBilling` | PG 원장에 주문 생김, 약 300ms 대기 |
| ⑤ TX2 | `settle` | `APPROVED` 또는 `DECLINED` |

### 2. 죽은 위치별 결과
| 죽은 위치 | 재시작 뒤 재확인 스케줄러가 보는 것 | 최종 |
|---|---|---|
| ① 전 | 결제 행 없음. Kafka 메시지는 커밋 전이라 다시 소비 | 정상 결제 |
| ②~④ 직전(③) | `REQUESTED`인데 PG에 주문 없음 → 늦은 승인 방지(망취소) | `DECLINED(NOT_SENT)` → 추첨이 새 예매로 1회 재시도 |
| ④ 중·뒤 | `REQUESTED`이고 PG에 주문 있음 → PG 결과 조회 | `APPROVED`(또는 PG가 거절했으면 `DECLINED`) |
| ⑤ 뒤 | 이미 확정 | 그대로 |

F1-a가 보고 싶은 것은 두 번째 줄이다. 실제로 걸리기 쉬운 것은 세 번째 줄이다.

### 3. 왜 두 번째 줄에 걸리기 어려운가
- 11/10 측정에서 결제 소비자는 1개였고 처리 속도는 초당 약 3.2건이었다. 한 순간에 진행 중인 자동결제는 대개 1건이다.
- 한 건이 약 315ms 걸린다면 그중 약 300ms는 ④ PG 대기다. ③은 복호화 한 번과 객체 생성뿐이다(걸리는 시간은 재지 못했다).
- 사람이 `docker kill`을 누르는 순간은 무작위라, ③에 걸릴 확률은 ③이 차지하는 시간 비율만큼으로 매우 낮다.

### 4. 선택지
| 선택지 | 얻는 것 | 감수할 점 |
|---|---|---|
| A. 0건이면 테스트로 대신 보임 | 서버는 "죽어도 수렴·이중 결제 없음", 테스트는 "NOT_SENT 경로"를 각각 증명 | 서버에서 NOT_SENT를 직접 재현하지 못한다고 발표에서 밝혀야 함 |
| B. 0건이면 반복 | 운이 좋으면 재현 | 회마다 추첨 준비, 결제 서버 종료, 일반 결제 503. 여전히 0건일 수 있음 |
| C. ③을 넓히는 코드 추가 | 확실히 재현 | 운영 코드에 시험용 지연. 범위 밖 |

## 이 프로젝트에서는
- 대신 보일 테스트:
  - 결제: `Ft3RestartScenarioTest.a_TX1_커밋_뒤_PG_호출_전에_죽으면_...` — `CrashPoint.BEFORE_PG_CALL`로 ③에서 강제로 멈추고, PG에 주문 없음 → 재확인 → `DECLINED(NOT_SENT)`, PG 주문 `CANCELED`·승인 0회를 확인한다. 이 테스트는 일반 승인 API 경로다. 자동결제는 같은 TX1 → PG → TX2 구조와 같은 재확인 스케줄러를 쓴다(`BillingPaymentService` 클래스 주석). 자동결제 경로 자체를 ③에서 멈추는 테스트는 찾지 못했다.
  - 추첨: `LotteryScenarioTest.S3_NOT_SENT면_같은_당첨자가_새_예매로_다시_결제한다`, `LotteryAssignmentTest.NOT_SENT는_새_예매로_한_번_다시_시도하고_두_번째면_FAILED다`
- 테스트 주석(`Ft3RestartScenarioTest` 30~31행)도 실제 프로세스 강제 종료는 테스트가 아니라 수동 확인 몫이라고 적어 두었다. F1-a가 그 수동 확인이다.

## 면접 포인트
- "프로세스가 죽으면 결제는 어떻게 되나": 죽은 위치별 표(2절)로 답한다. 핵심은 TX1에서 `REQUESTED`를 먼저 남기고, PG 조회로 상태를 맞춘다는 것이다.
- "장애 주입에서 그 경로를 재현했나": 타이밍을 맞출 수 없어 서버에서는 수렴을, 테스트에서는 죽는 지점을 고정해 NOT_SENT를 증명했다고 답한다. 재현하지 못한 것을 재현했다고 말하지 않는다.

## 참고
- `payment-service/src/main/java/com/iseonjwa/payment/payment/application/BillingPaymentService.java` 59~118행
- `payment-service/src/test/java/com/iseonjwa/payment/scenario/Ft3RestartScenarioTest.java` 30~70행
- `docs/design/lottery/f1-fault-injection.md` 4절, ADR 0018
- `docs/design/lottery/numbers-and-measurement.md` 3-3·3-4절(소비자 1개, 초당 약 3.2건)

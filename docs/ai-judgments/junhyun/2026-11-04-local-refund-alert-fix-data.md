---
title: 로컬 테스트 환불의 반복 알림을 무음으로 끈 AI 처리를 거두고, 로컬 데이터를 한 번 정상화하게 함
date: 2026-11-04
updated: 2026-11-04
judge: Junhyun An
ai: Claude Code
verdict: 거부
area: 결제
presentation: 아님
shared: 아니오
---

# 로컬 테스트 환불의 반복 알림을 무음으로 끈 AI 처리를 거두고, 로컬 데이터를 한 번 정상화하게 함

## 맥락
- 해결하려던 작업: 팀 슬랙에 한 시간마다 오는 "확인이 필요한 환불이 1건 있습니다(NEEDS_MANUAL_CHECK)" 알림(env 로컬)을 없애기.
- AI 활용 방식: "이거 한시간마다 알림오는것좀 해소해줘"라고 맡겼다.

## AI 제안
- `ops inspect --with-pg`로 확인하니 11/2 로컬 테스트 환불 1번이었다. Mock PG 재시작으로 원장이 비어 PG에 주문·환불 기록이 없고, `refund resolve`(NotFound 거부)와 `refund retry`(다시 거절)로 닫을 수 없었다.
- 대응 문서의 원칙("알림을 끄려고 NMC를 닫지 않는다", "상태를 바꾸는 SQL을 쓰지 않는다")을 따라 데이터는 두고 로컬 Alertmanager에 7일 무음을 걸었다.

## 판단
- 결과: 거부(무음 대신 데이터 정상화)
- 판단자: Junhyun An
- 근거: "아 로컬 테스트 데이터니까 지금 한번만 그냥 데이터를 정상화시켜줘"
- 검토한 대안: 무음 유지(데이터는 계속 NMC로 남고 무음이 끝나면 다시 울린다), 로컬 결제 DB 초기화(다른 테스트 데이터까지 사라진다)

## 반영
- `ops refund resolve --close-failed`의 전이를 SQL 한 문장으로 똑같이 했다. 환불 1번은 `FAILED`가 됐고, 상태 이력 한 줄(사유 `LOCAL_TEST_DATA_SQL_CLOSE`, actor `OPS:junhyun`)을 남겼다. 이벤트는 없고 결제 상태는 그대로다.
- 무음은 지웠다. 기록: `docs/troubleshooting.md` TS-32.

## 검증
`refund_manual_check_count`가 0이 되고 Prometheus·Alertmanager에서 환불 알림이 사라졌다(2026-11-04). 확인이 필요한 환불·결제 수는 모두 0이다.

## 판단 이력
- 2026-11-04 거부: 무음 대신 로컬 데이터를 한 번 정상화

## 발표·면접 포인트
- 운영 원칙(상태를 바꾸는 SQL 금지)은 운영 서버의 규칙이다. 로컬 테스트 데이터라도 고칠 때는 운영 명령과 같은 전이와 이력을 남기고, SQL로 고쳤다는 사실을 사유에 드러냈다.

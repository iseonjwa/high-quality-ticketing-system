---
title: 배포 서버 단계 측정(ISE-296)에서 게이트웨이 동시 처리 상한을 끄고 S0부터 다시 재기로 함
date: 2026-11-16
updated: 2026-11-16
judge: kwonwoo
ai: Claude Code(측정 실행 중 결과 분석과 선택지 제시)
verdict: 수용
area: 동시성
presentation: 아님
shared: 예
---

# 배포 서버 단계 측정(ISE-296)에서 게이트웨이 동시 처리 상한을 끄고 S0부터 다시 재기로 함

## 맥락
- 해결하려던 작업: 배포 서버에서 좌석 선점 방식별 단계 측정(S0 → S1 → S2 → O → D → S2', 실행표 `docs/design/reservation/deploy-seathold-steps-run-sheet.md`)을 진행했다.
- AI 활용 방식: 에이전트가 S0 단계를 실행하고 결과를 분석한 뒤 다음 단계를 어떤 조건으로 잴지 선택지를 냈다.

## AI 제안
- 관찰: S0의 A0 10회가 모두 성공률 43~54%로 불합격이었다. 실패는 전부 503이었고, 응답 시간이 0.2ms 수준이었다. reservation-service의 5xx는 1건뿐이었고, `gateway_in_flight_rejected_total`이 5,693 늘었다. 즉 게이트웨이 맨 앞 동시 처리 상한(운영값 64, ISE-230)이 거절한 것이다. A0는 발제 기준선(동시 100명 × 10회)이라 동시 수가 64를 넘는다. 같은 단계의 K1-H 램프는 실패 0이었다.
- 선택지:
  1. 모든 단계에 `loadtest/deploy/compose.gateway-inflight.yml`(INFLIGHT_ENABLED=false)을 더하고 S0부터 다시 잰다(추천). Gateway 메모리와 OOM을 같이 본다.
  2. INFLIGHT_MAX=256으로 상한만 키우고 S0부터 다시 잰다.
  3. 운영값 64 그대로 S1부터 이어 간다. A0에 게이트웨이 503이 섞인 결과를 그대로 기록한다.
  4. 측정을 멈추고 팀과 조건을 정한다.
- 추천 이유: 이 측정은 선점 경로 방식끼리 비교하는 것이 목적이다. 앞단의 거절이 섞이면 단계별 차이가 가려진다.

## 판단
- 결과: 수용(1안 선택)
- 판단자: kwonwoo
- 근거: 미기재
- 검토한 대안: 2·3·4안

## 반영
- 서버 측정 모드를 `INFLIGHT_ENABLED=false`로 다시 켰다. 덮어쓰기 파일은 `loadtest/k6/compose.seat-hold-prereject-off.yml`과 `loadtest/deploy/compose.gateway-inflight.yml`이다. 남은 단계에도 같은 파일을 함께 준다.
- 운영값 64로 잰 첫 S0 결과는 발생기 `~/kwonwoo/ise296-runs.tsv`에 `S0-gw64`라는 이름으로 따로 남겼다.
- config-repo의 운영 상한은 바꾸지 않았다.

## 검증
- 다시 켠 뒤 `gateway_in_flight_limit` 시계열이 사라진 것을 확인했다(상한 빈이 만들어지지 않음).
- 측정 결과는 ISE-296 정리 문서에 남긴다.

## 판단 이력
- 2026-11-16 수용: A0가 게이트웨이 상한 64에 막히는 것을 보고 상한을 끈 조건으로 S0부터 다시 재기로 했다.

## 발표·면접 포인트
- 발제 기준선(동시 100명)은 운영 보호 장치(게이트웨이 동시 처리 상한 64)와 조건이 맞지 않는다. 그래서 배포 서버에서 선점 방식을 비교할 때는 앞단 상한을 끄고 쟀다. 운영값으로 잰 결과는 "상한이 실제로 거절하는 모습"으로 따로 남겼다.

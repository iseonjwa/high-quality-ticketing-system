---
title: ISE-230 남은 확인 세 건은 답을 받은 뒤 마무리하자는 AI 순서를 거부하고, Retry-After 분리(A안)와 함께 ADR 0050·0052 채택과 대시보드·경보를 선조치 후보고로 진행함
date: 2026-11-13
updated: 2026-11-13
judge: kangmin
ai: Claude Code
verdict: 거부
area: 범위·일정
presentation: 후보
shared: 예
---

# ISE-230 남은 확인 세 건은 답을 받은 뒤 마무리하자는 AI 순서를 거부하고, Retry-After 분리(A안)와 함께 ADR 0050·0052 채택과 대시보드·경보를 선조치 후보고로 진행함

## 맥락
- 해결하려던 작업: ISE-230(contract, queue→gateway)을 닫기 위해 남은 일을 정리했다. 처음 부탁받은 세 가지(실패 원인 기록, 차단기 상태 전환 기록, bulkhead 끄는 설정 확인)는 끝났고, 팀원 답을 기다리는 확인 세 건과 `Retry-After` 분리가 남아 있었다.
- AI 활용 방식: Claude Code에 이슈를 어떻게 처리하면 되는지 물었다. Claude Code가 Linear 이슈·댓글과 저장소 코드를 읽고 남은 일과 순서를 냈다.

## AI 제안
1. `Retry-After` 분리를 먼저 정한다. A안은 지금 분리(차단기 열림 거절은 열림 시간, 한 번 실패는 3초), B안은 별도 이슈로 빼기. A안을 추천했다.
2. 확인 세 건(ADR 0050 대기열 담당, ADR 0052 사후 확인, 동시 처리 수 상한 대시보드·경보를 상한 값 확정과 떼어 올릴지)은 답을 받는다. 오늘 답이 없으면 Slack으로 한 번 더 알린다.
3. 답이 오면 ADR 상태를 채택으로 바꾸고, 대시보드·경보 PR을 올리고, 완료 기준을 체크한 뒤 Done으로 옮긴다.

## 판단
- 결과: 거부(2·3번 순서). 1번 A안은 수용했다.
- 판단자: kangmin
- 근거: "1~3 답을 받았다고 가정하고 진행할게", "일정이 빠듯하여 시간관계상 선조치 후보고가 유리할 것 같아"
- 검토한 대안: 답을 기다린 뒤 마무리(AI 순서). 기다리는 동안 ADR이 제안 상태로 남고, 대시보드·경보도 상한 값 확정 PR에 묶여 계속 밀린다.
- 감수한 점: 대기열 담당·결제 담당의 확인 전에 ADR 0050·0052가 채택 상태가 된다. 그래서 ADR 상태 줄에 선조치 후보고임을 적고, 이견이 나오면 제안으로 되돌린다고 적었다. 경보 기준 1%는 측정 근거 없는 `[가정]`이다.

## 반영
- 코드: `AuthRedisRetryAfter`(원인별 `Retry-After`), `GatewayAuthenticationFilter`, `AuthRedisFailures.record`가 원인을 돌려줌. 설정 `auth.redis-retry-after`(기본 3초)
- 결정: ADR 0050·0052 상태를 채택으로 바꿈(선조치 후보고), `docs/decisions/README.md` 색인
- 관측: `infra/grafana/dashboards/bootstrap.json` 패널 2개(처리 중 수·상한, 상한 거절률), `infra/prometheus/rules/gateway-alerts.yml` 경보 2개(`GatewayInFlightRejecting` 5분 1%, `GatewayInFlightRejectingPeak` 1분 1%)
- 같은 PR에서 동시 처리 수 상한 Retry-After도 2초에서 3초로 맞췄다([Retry-After 기준 노트](../../learning/kangmin/qna/2026-11-13-retry-after-value-criteria.md))
- 커밋·PR·Linear 보고는 사용자가 맡겨 브랜치 `feat/ISE-230-retry-after-by-cause`로 올렸다

## 검증
- Gateway 테스트 131개 → 142개, 실패 0. 추가 11개(필터 열림 거절 1, 운영 설정 컨텍스트 1, `AuthRedisRetryAfterTest` 9)
- 변이 테스트: 열림 구분을 없애면 4개 실패, 올림을 내림으로 바꾸면 1개 실패. 경보 기준을 1%에서 5%로 바꾸면 promtool 테스트 실패
- `script/check-prometheus-rules.sh`: 규칙 검사·단위 테스트 통과. 스크립트 끝의 Alertmanager 템플릿 그리기는 이 Windows 환경에서 `/data/event.json`을 열지 못해 실패했다(이번 변경과 무관한 단계, CI에서 확인 필요)
- 배포 환경에서 실제 503 헤더는 확인 못 함

## 판단 이력
- 2026-11-13 거부: 답을 기다리지 않고 선조치 후보고로 채택·대시보드·경보까지 진행. A안은 수용

## 발표·면접 포인트
- 마감 일정 때문에 합의 순서를 바꿀 때 무엇을 남겼는지: 상태 줄의 선조치 후보고 표시, 되돌리는 조건, 사후 확인 요청.

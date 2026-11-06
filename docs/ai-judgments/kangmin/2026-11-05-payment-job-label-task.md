---
title: 결제 작업 지표의 job 라벨 충돌을 규칙 수정이 아니라 태그를 task로 바꿔 풀기로 함
date: 2026-11-05
updated: 2026-11-05
judge: kangmin
ai: Claude Code
verdict: 수용
area: 결제
presentation: 후보
shared: 예
---

# 결제 작업 지표의 job 라벨 충돌을 규칙 수정이 아니라 태그를 task로 바꿔 풀기로 함

## 맥락
- 해결하려던 작업: 추첨 지표(ISE-94)를 만들다가 Micrometer 태그 `job`이 Prometheus에서 `exported_job`으로 바뀌는 것을 발견했다. 결제 경보 `PaymentJobStalled`도 같은 이유로 시계열을 찾지 못해 울리지 않았다(ISE-195).
- AI 활용 방식: 선택지 비교와 결제 담당에게 보낼 이슈 초안을 Claude Code에 맡겼다.

## AI 제안
세 가지를 비교하고 B를 추천했다.
- A. 규칙·대시보드·테스트를 `exported_job`으로 고친다. 서비스 재배포가 필요 없지만 충돌을 피하려고 붙은 이름에 규칙이 의존하고 슬랙에도 그 이름이 보인다.
- B. `PaymentMetrics`의 태그를 `task`로 바꾼다. payment-service를 재배포해야 하고 라벨 이력이 끊기지만, 코드의 이름으로 그대로 조회할 수 있고 추첨과 이름이 같아진다.
- C. `honor_labels: true`를 켠다. 코드와 규칙을 그대로 두지만 수집 대상 전체가 보낸 라벨을 믿게 되어 영향이 결제 지표를 넘어선다.

## 판단
- 결과: 수용
- 판단자: kangmin
- 근거: 코드의 이름으로 그대로 조회할 수 있고 추첨(ISE-94)과 이름이 같아진다. `exported_job`은 충돌을 피하려고 붙은 이름에 의존하고, `honor_labels`는 영향이 수집 대상 전체로 넓어진다.
- 검토한 대안: A(`exported_job`), C(`honor_labels`)

## 반영
- 이슈: ISE-195(결제 담당 배정·멘션)
- 코드: `PaymentMetrics`, `PrometheusNamesTest`, `BackgroundJobSchedulerTest`
- 설정: `infra/prometheus/rules/payment-alerts.yml`, `infra/prometheus/tests/payment-alerts.test.yml`, `infra/grafana/dashboards/payment.json`, `infra/alertmanager/templates/slack.tmpl`
- 문서: `docs/design/payment/`, `docs/design/infra/observability.md`, [TS-37](../../troubleshooting.md)

## 검증
- 수정본 payment-service와 임시 Prometheus에서 규칙의 네 항목이 작업 7개를 모두 찾았다.
- 임시 DB를 멈추자 `gauge-refresh`는 72초, `recheck`·`refund-worker`는 145초에 firing이 됐고, 되살리자 26초 안에 inactive로 돌아왔다.
- 규칙을 예전 라벨로 되돌리면 promtool 테스트가, 태그를 `job`으로 되돌리면 `PrometheusNamesTest`가 실패했다.

## 판단 이력
- 2026-11-05 수용: B안으로 이슈를 올리고 구현했다.

## 발표·면접 포인트
- 규칙 단위 테스트가 입력에 라벨을 직접 넣으면 수집 과정의 라벨 변환을 건너뛰어, 테스트는 통과하는데 실제 경보는 울리지 않는다. 테스트 입력은 실제 수집 결과와 같은 형태여야 하고, 경보는 실제로 장애를 내서 확인해야 한다.

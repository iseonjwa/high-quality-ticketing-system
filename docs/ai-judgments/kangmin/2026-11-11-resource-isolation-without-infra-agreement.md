---
title: 한 서버 자원 격리 개선(M1 메모리 예산 등)을 contract 이슈로 인프라 담당과 먼저 합의하자는 AI 추천을 거부하고, 측정과 함께 순서대로 바로 진행함
date: 2026-11-11
updated: 2026-11-11
judge: kangmin
ai: Claude Code (메인 세션, 설계 메모 single-host-msa-resource-isolation.md 5절 추천 순서)
verdict: 거부
area: 협업
presentation: 아님
shared: 예
---

# 한 서버 자원 격리 개선(M1 메모리 예산 등)을 contract 이슈로 인프라 담당과 먼저 합의하자는 AI 추천을 거부하고, 측정과 함께 순서대로 바로 진행함

## 맥락
- 해결하려던 작업: 배포 서버 5차에서 Gateway가 컨테이너 OOM으로 세 번 내려갔고, 서버 CPU는 1,500/s에서 이미 93%였다. 한 서버 안에서 자원을 나누는 방법을 [설계 메모](../../design/infra/single-host-msa-resource-isolation.md)로 정리했다.
- AI 활용 방식: 방법별 효과·비용과 추천 순서를 물었다.

## AI 제안
- 순서: M1 Gateway 메모리 예산(1순위) → A1 Gateway 맨 앞 동시 수 상한 → 고정 비용 정체 찾기(C1 요청 로그 등) → M2 `cpu_shares` → C2 추적 샘플링.
- M1·M2·C2는 `compose.deploy.yml`과 시연 설계를 바꾸므로, 대전제 11에 따라 contract 이슈로 인프라 담당과 먼저 합의하자고 했다.

## 판단
- 결과: 거부(합의를 먼저 받는 부분). 순서는 받아들였다.
- 판단자: kangmin
- 근거: "인프라 합의 필요없으니 쭉쭉 진행해 보자. 우선도 파악하고 하나씩 진행해 보자"
- 검토한 대안: contract 이슈를 먼저 올리고 답을 기다린 뒤 측정

## 반영
- 측정 덮어쓰기로만 바꿔 배포 서버에서 순서대로 잰다. 한 회차에 하나만 바꾸고 누적 표에 남긴다([open-peak-scenarios.md](../../design/infra/open-peak-scenarios.md) "배포 서버 5차").
- 운영 반영(`compose.deploy.yml` 등)은 측정으로 확인한 뒤 PR로 한다. 그때 팀 규칙(대전제 11)을 지키는 최소 절차로 ISE-230에 댓글 한 줄을 남겨 인프라 담당에게 알린다.

## 검증
진행 중

## 판단 이력
- 2026-11-11 거부: 사전 합의 없이 측정과 함께 진행, 운영 반영 PR 때 ISE-230 댓글로 알림

## 발표·면접 포인트
- 측정 덮어쓰기는 운영 설정을 바꾸지 않아 먼저 해 볼 수 있고, 운영 반영 시점에만 팀에 알리는 식으로 합의 비용과 속도를 나눴다.

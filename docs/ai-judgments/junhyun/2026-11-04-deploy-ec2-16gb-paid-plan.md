---
title: 리허설 일정에 맞춰 측정 전에 배포 EC2를 16GB급으로 정하고 AWS Paid plan으로 바꿈
date: 2026-11-04
updated: 2026-11-04
judge: Junhyun An
ai: Claude Code
verdict: 수용
area: 범위·일정
presentation: 아님
shared: 예
---

# 리허설 일정에 맞춰 측정 전에 배포 EC2를 16GB급으로 정하고 AWS Paid plan으로 바꿈

## 맥락
- 해결하려던 작업: 금요일(11/6) 리허설 전에 배포를 끝내기 위해 일정을 이틀로 줄이기. 원래 계획은 로컬 부하 중 메모리를 잰 뒤(ISE-176) 8GB급과 16GB급 중 고르는 것이었다(SA U10, `docs/design/infra/deployment.md` 6절).
- AI 활용 방식: "금요일이 리허설이라, 오늘 내일 해서 다 끝내면 좋겠는데, 일정좀 다시 잡아줘"라고 했다.

## AI 제안
- 측정을 크기 결정의 선행 조건에서 뺀다. SA 9-3 합계 11,392m에 관측 추가분(loki·alloy·alertmanager 896m)을 더하면 약 12GB라 16GB급이 사실상 정해져 있다. 측정을 기다리면 EC2 생성이 하루 밀린다.
- 8GB급(Free plan 유지)은 컨테이너 상한을 크게 줄여야 해서 시연 중 메모리 부족 위험이 있다. 16GB급은 Free plan에서 고를 수 없어 Paid plan으로 바꿔야 하고, 크레딧이 먼저 쓰이며 지출 한도를 걸 수 있다.
- 측정(ISE-176)은 배포 서버의 실제 `docker stats`로 대신한다.

## 판단
- 결과: 수용
- 판단자: Junhyun An
- 근거: 미기재("1. 페이드플랜으로 전환했고")
- 검토한 대안: 측정 뒤 결정(하루 늦어짐), 8GB급 Free plan 유지(상한 축소, 메모리 부족 위험)

## 반영
- `docs/design/infra/deployment.md` 6절과 7절 결정 표에 16GB급·Paid plan을 적었다.
- `compose.deploy.yml`의 상한은 SA 9-3 표와 관측 추가분을 따른다(합계 약 12.1GB, 16GB의 약 77%).

## 검증
구현 전. 배포 뒤 서버 `docker stats`로 실제 사용량을 잰다(ISE-176).

## 판단 이력
- 2026-11-04 수용: 16GB급, Paid plan 전환(사용자가 콘솔에서 바꿈)

## 발표·면접 포인트
- 측정이 결정을 바꾸지 못할 정도로 근거가 한쪽에 쏠려 있으면, 일정이 급할 때는 결정을 먼저 하고 측정은 실제 환경에서 검증으로 돌린다. 이때 결정의 근거(설계 합계와 75% 규칙)를 남겨 둔다.

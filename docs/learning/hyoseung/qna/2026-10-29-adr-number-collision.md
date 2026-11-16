---
title: ADR은 무엇이고, 번호가 두 개 겹치면 왜 문제인가
date: 2026-10-29
updated: 2026-10-29
asker: hyos1
level: 기초
tags: [adr, decisions, collaboration]
---

# ADR은 무엇이고, 번호가 두 개 겹치면 왜 문제인가

## 질문
"ADR 0031 번호가 두 개 생겼다"는 말이 무슨 뜻인가.

## 답
### ADR
ADR(Architecture Decision Record)은 되돌리기 어려운 결정을 "무엇을, 왜, 다른 안은 무엇이었나"로 남기는 문서다(AGENTS.md 대전제 4). 이 저장소는 `docs/decisions/<4자리 번호>-<제목>.md`로 두고, 번호를 문서와 커밋·PR·SA에서 "ADR 0026"처럼 부른다. 번호가 그 결정의 이름이다.

### 10/29에 생긴 일
두 사람이 각자 브랜치에서 "마지막 번호가 0030이니 다음은 0031"로 정해 동시에 PR을 올렸고, 둘 다 머지됐다.
- `0031-queue-token-api-and-ready-status.md` (권영은, 대기열 입장 토큰 API)
- `0031-seat-hold-result-and-idempotency-contract.md` (최권우, 좌석 선점 결과 계약)

파일 이름이 달라 Git 충돌은 나지 않았다. 하지만 SA에 "ADR 0031"이라고 쓰면 어느 결정인지 알 수 없다. 늦게 들어온 쪽 번호를 0032로 바꾸고, 그 번호를 적은 곳(SA, 계약 문서, 판단 기록)을 함께 고치면 해결된다.

### 예방
PR을 올리기 직전에 dev를 받아 마지막 번호를 다시 확인한다. Flyway 버전 번호를 날짜 기반으로 정한 것도 같은 이유(여러 사람이 같은 번호를 집는 충돌)다.

## 핵심 정리
- ADR 번호는 결정의 이름이라 겹치면 참조가 모호해진다. 파일 이름이 달라 Git이 잡아 주지 않는다.

## 참고
- `docs/decisions/README.md`

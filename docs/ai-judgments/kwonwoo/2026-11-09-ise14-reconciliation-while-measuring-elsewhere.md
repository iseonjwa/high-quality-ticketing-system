---
title: k6 측정을 다른 기기로 옮긴 뒤, 이 기기에서는 AI가 추천한 정합성 대사(ISE-14)를 먼저 구현하기로 함
date: 2026-11-09
updated: 2026-11-09
judge: KWON-WOO
ai: Claude Code (Linear 이슈·저장소 문서 검토 후 다음 할 일 제안)
verdict: 수용
area: 범위·일정
presentation: 아님
shared: 아니오
---

# k6 측정을 다른 기기로 옮긴 뒤, 이 기기에서는 AI가 추천한 정합성 대사(ISE-14)를 먼저 구현하기로 함

## 맥락
- 해결하려던 작업: 원래 노트북 메모리가 모자라 ISE-79 k6 단계 측정을 다른 기기로 옮기기로 했다. 이 기기에서 할 일을 정해야 했다.
- AI 활용 방식: Linear 배정 이슈, 열린 contract 이슈, 핸드오프 메모, 팀원 판단 기록(2026-11-08 Linear 정리)을 확인하고 다음 할 일을 제시해 달라고 했다.

## AI 제안
- 급한 순서: ISE-189 일정 합의 댓글(반효승과 함께), ISE-8 닫기 확인, ISE-192 시나리오 검토(마감 11/10), 접수형 선점 ISE-15~18 범위를 팀과 정하기(11/12 중간 점검 전, "최소안" 추천).
- 이 기기에서 할 구현은 ISE-14(좌석·보유 매수 정합성 대사)를 추천했다. 담당 영역(seathold) 안이고, 측정 기기 없이 테스트 DB로 검증할 수 있으며, k6 측정의 "대사 위반 0" 판정과 ISE-192 R1에도 쓰인다는 이유였다.
- S1~S4 PR은 측정과 무관한 정합성 수정이라 먼저 올릴 수 있다고 했다. 다만 핸드오프 메모가 "측정 뒤"로 정해 두었으니 사용자에게 결정을 물었다.

## 판단
- 결과: 수용
- 판단자: KWON-WOO
- 근거: 미기재
- 검토한 대안: S1~S4 PR 먼저 올리기, 접수형 선점(ISE-16·17) 착수. 고르지 않았다.

## 반영
- 브랜치 `feat/ISE-14-seat-hold-reconciliation`(dev 기준).
- 설계와 사용법: [`docs/design/reservation/seat-ledger-reconciliation.md`](../../design/reservation/seat-ledger-reconciliation.md).
- 코드: `reservation-service`의 `seathold` 모듈(대사 서비스, 조회·보정 쿼리, 내부 API).

## 검증
- `SeatLedgerReconciliationTest` 7건 통과(실제 PostgreSQL).
- `:reservation-service:test` 전체: 처음에는 시각을 비교하는 다른 테스트 4건이 실패했다. 이 기기의 Windows 시계가 실제보다 약 7초 늦었기 때문이다(TS-48). 시계를 맞춘 뒤 374건 중 실패 0건, 건너뜀 11건이었다.

## 판단 이력
- 2026-11-09 수용: 이 기기에서는 ISE-14를 먼저 구현한다.

## 발표·면접 포인트
- 측정 환경이 막혔을 때, 같은 담당 영역에서 측정 기기 없이 끝낼 수 있고 측정 판정에도 다시 쓰이는 작업을 골랐다.

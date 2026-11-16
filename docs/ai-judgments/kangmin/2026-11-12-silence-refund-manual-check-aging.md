---
title: Mock PG 원장 유실로 닫을 수 없는 환불 NMC 알림을 직접 처리하지 않고 결제 담당에게 맡긴다
date: 2026-11-12
updated: 2026-11-12
judge: kangmin
ai: Claude Code (배포 서버 읽기 전용 조회와 manual-recovery.md 대조)
verdict: 보류
area: 결제
presentation: 아님
shared: 예
---

# Mock PG 원장 유실로 닫을 수 없는 환불 NMC 알림을 직접 처리하지 않고 결제 담당에게 맡긴다

## 맥락
- 해결하려던 작업: 배포 서버에서 `RefundManualCheckAging`(운영자 확인 환불이 30분 넘게 남음) 알림이 울려 대응 방법을 찾았다.
- AI 활용 방식: 알림 화면을 주고 처리 방법을 물었고, AI가 배포 서버에서 읽기 전용 SQL과 `ops inspect --with-pg`로 대상을 조회했다.

## AI 제안
- 조회 결과: 환불 3(결제 137870, 200만 원, 사유 `CANCELED`)이 `REFUND_REJECTED`로 `NEEDS_MANUAL_CHECK`가 됐다. Mock PG에 결제 주문과 환불 기록이 모두 없다. Mock PG가 다시 떠서 메모리 원장을 잃었고, 승인 거래 ID가 ISE-253(서명 넣은 거래 ID) 이전의 서명 없는 형식이라 되살릴 수 없다고 봤다. 재시작 시각은 로그가 없어 확인하지 못했다.
- 지금 있는 Ops 명령으로는 닫을 수 없다고 봤다. `refund resolve`는 PG `NotFound`에서 거부하고 `--close-failed`는 `RefundRejected`에만 허용된다. `refund retry`는 적용되지만 같은 거래 ID로 다시 거절될 것으로 예상했다(실행하지 않음).
- 추천: 결제 담당에게 "PG에 기록이 없는 환불을 닫는 경로가 없다"는 점과 배포 서버의 Ops 실행 방법이 문서(`local,ops`)와 다르다는 점(`prod,ops`, compose 파일 두 개, `IMAGE_TAG`)을 알린다. 대안: Alertmanager에서 이 알림만 기간을 정해 묵음 처리한다.

## 판단
- 결과: 보류(처리를 결제 담당에게 넘김)
- 판단자: kangmin
- 근거: 결제 담당이 처리한다
- 검토한 대안: 결제 담당에게 알릴 글 작성(AI 추천), 알림 묵음(처음 골랐다가 철회)

## 반영
미반영. 묵음은 만들지 않았고 환불 3은 `NEEDS_MANUAL_CHECK` 그대로 둔다. 처리는 결제 담당이 한다.

## 검증
조회 시점(2026-11-12 21:48 KST 무렵) Alertmanager에 `RefundManualCheckAging` warning 1건이 active였다. 결제 담당이 같은 환경의 `RefundNeedsManualCheck`를 11/20 09:00 KST까지 이미 묵음 처리해 둔 것을 확인했다.

## 판단 이력
- 2026-11-12 거부: 결제 담당 공유 대신 알림 묵음을 골랐다.
- 2026-11-12 보류: 묵음을 만들기 전에 철회하고 결제 담당이 처리하기로 했다.

## 발표·면접 포인트
- 운영 절차로 닫을 수 없는 환불을 감사 기록 없는 SQL이나 알림 묵음으로 덮지 않고, 원인 조사 결과와 함께 도메인 담당에게 넘겼다.

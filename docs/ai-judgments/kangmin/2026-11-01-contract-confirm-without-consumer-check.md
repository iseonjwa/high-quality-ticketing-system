---
title: 계약 ② 확정과 머지된 브랜치 삭제를 상대 담당 답을 기다리지 않고 진행
date: 2026-11-01
updated: 2026-11-01
judge: 이강민
ai: Claude Code (ISE-19·ISE-21 계약 이슈 정리 후속 처리 방식 추천)
verdict: 거부
area: 협업
presentation: 후보
shared: 예
---

# 계약 ② 확정과 머지된 브랜치 삭제를 상대 담당 답을 기다리지 않고 진행

## 맥락
- 해결하려던 작업: 좌석 선점 담당이 ISE-21(계약 ② 좌석 선점 결과) 작업을 `docs/ISE-19-seat-hold-result` 브랜치로 올려, PR `#93` 머지 때 Linear가 상위 ISE-19를 자동으로 닫았다. 이 어긋남을 정리하는 단계였다.
- 남은 일:
  - ISE-21의 완료 기준 중 "예매 흐름 담당 확인"이 기록되지 않았다(`contracts.md` 5-3절 4항목). 그래서 `contracts.md`와 ADR 0032가 아직 "제안" 상태였다.
  - 머지된 원격 브랜치가 남아 있었다.

## AI 제안
- 예매 흐름 담당의 답을 받은 뒤 문서를 확정으로 바꾸자고 했다.
- 원격 브랜치는 소유자(좌석 선점 담당)가 동의한 뒤 지우자고 했다(D17).
- 근거:
  - 답 없이 확정하면 "확인했다"는 사실과 다른 기록이 남는다.
  - 브랜치는 소유자 확인 후 삭제한다는 팀 규칙이 있다.

## 판단
- 결과: 거부
- 판단자: 이강민
- 근거: 미기재("동의없이 진행해")
- 검토한 대안: 답과 동의를 기다린 뒤 처리(AI 추천)

## 반영
- `docs/design/reservation/contracts.md` 상태 줄, ADR 0032, `docs/decisions/README.md` 0032 행을 확정·채택으로 바꿨다. 문구에는 "예매 흐름 담당 확인 없이 통합 담당이 확정"했다고 그대로 적었다.
- 원격 브랜치 `docs/ISE-19-seat-hold-result`를 삭제했다.
- ISE-21에 후속 코멘트를 남기고, ISE-19 본문의 마감 정리 표를 갱신했다.
- 저장소 변경은 `docs/ISE-19-contract-status-sync` 브랜치에 있다(미커밋).

## 검증
- 삭제 전에 브랜치 마지막 커밋 `f35d44d`가 `origin/dev`에 포함되는지 `git merge-base --is-ancestor`로 확인했다.
- 선점 API와 `PaymentTokenIssuer`는 10/30에 5-1절 제안 시그니처 그대로 구현돼 있다(`reservation-service/.../seathold/application/PaymentTokenIssuer.java`).
- 5-3절 3번 `RESERVATION_NOT_PAYABLE`과 멱등 재요청은 아직 코드에 없다. ISE-7 범위다.
- `script/records-check.sh` OK.

## 판단 이력
- 2026-11-01 거부: 답과 동의를 기다리지 않고 확정·삭제를 진행했다.

## 발표·면접 포인트
- 생산자가 정한 계약을 소비자 확인 없이 확정할 때는 그 사실을 문서 상태에 남기고, 바뀌면 고칠 경로(이슈 코멘트 → 형식 소유 담당과 조정)를 함께 적어 둔다.
- 마감 일정과 확인 절차가 부딪힐 때 무엇을 감수했는지 보여 주는 사례다.

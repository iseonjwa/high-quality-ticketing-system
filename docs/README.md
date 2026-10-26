# docs

프로젝트 진행 중 결정, 학습, AI 판단, 설계 검토를 모아 두는 곳이다. (첫날 회의: "결정 기록 등 개발상 공유되는 정보 → 프로젝트 내부 docs로") 에이전트 규칙의 정본은 루트 [`AGENTS.md`](../AGENTS.md)다.

## 문서 지도

| 무엇을 | 어디에 | 작성 방법 |
|---|---|---|
| AI 응답·제안에 대한 판단(수용·거부·보류·선택) | [`ai-judgments/`](ai-judgments/README.md) | `ai-judgment` 스킬 |
| 개인 질문 노트, 하루 정리 | [`learning/<이름>/`](learning/README.md) | `learning-note` 스킬 |
| 되돌리기 어려운 결정 | [`decisions/`](decisions/README.md) | `adr` 스킬 |
| 해결한 문제 | [`troubleshooting.md`](troubleshooting.md) | `troubleshooting` 스킬 |
| 설계 검토 결과 | [`sa-review/`](#sa-검토-2026-10-23) | 아래 목록 |
| 에이전트용 학습 운영 규칙 | [`agents/learning-guide.md`](agents/learning-guide.md) | |

스킬 정본은 [`.agents/skills/`](../.agents/skills/README.md)에 있다.

## SA 검토 (2026-10-23)

| 문서 | 내용 |
| --- | --- |
| [sa-review/01-sa-1st-review.md](sa-review/01-sa-1st-review.md) | SA MASTER 1차 설계 검토 종합 (codex + Claude 에이전트 교차 검토). 서비스별 판정, Critical·Major 발견 사항, 발제 대비 누락, 팀 논의 목록 |
| [sa-review/02-pg-timeout-handling.md](sa-review/02-pg-timeout-handling.md) | 실제 PG사의 승인 타임아웃 처리 방식(망취소, 멱등 재시도·조회)과 Mock PG 적용안 |
| [sa-review/03-seat-hold-kafka-design.md](sa-review/03-seat-hold-kafka-design.md) | M1 상세: Kafka 파티션 키, 결과 전달, 발신 유실, Redis + Kafka 혼합형 좌석 선점 |
| [sa-review/assets/seat-hold-kafka.html](sa-review/assets/seat-hold-kafka.html) | 03 문서의 시각화 페이지 로컬 사본 (브라우저로 열기) |
| [sa-review/raw/codex-review.md](sa-review/raw/codex-review.md) | codex 리뷰 원문 |

## 외부 원본

- Notion [SA MASTER](https://app.notion.com/p/4027bd823b75808bb67efd7dbd1f7c53) — 설계 정본
- Notion [1차 SA 피드백](https://app.notion.com/p/4027bd823b758034966df87b7622c9b1) — sa-review/01과 같은 내용
- Notion [발제문 원본](https://app.notion.com/p/4027bd823b758019a04dda70d6ae39ab), [첫날 회의](https://app.notion.com/p/4027bd823b7580b19317f4c4c8df4a08)
- 시각화 [좌석 선점 Kafka 설계 (Artifact)](https://claude.ai/artifact/3dWEW9UCxs2opzgLqFwsHH) — 비공개 상태라 팀원에게는 공유 설정이 필요하다

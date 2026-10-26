# docs

프로젝트 진행 중 결정, 학습, AI 판단, 설계 검토를 모아 두는 곳이다. (첫날 회의: "결정 기록 등 개발상 공유되는 정보 → 프로젝트 내부 docs로") 에이전트 규칙의 정본은 루트 [`AGENTS.md`](../AGENTS.md)다.

## 문서 지도

| 무엇을 | 어디에 | 작성 방법 |
|---|---|---|
| AI 응답·제안에 대한 판단(수용·거부·보류·선택). 사람별 폴더, 공통 판단은 중앙 표 | [`ai-judgments/`](ai-judgments/README.md) | `ai-judgment` 스킬 |
| 개인 질문 노트, 하루 정리 | [`learning/<이름>/`](learning/README.md) | `learning-note` 스킬 |
| 되돌리기 어려운 결정 | [`decisions/`](decisions/README.md) | `adr` 스킬 |
| 해결한 문제 | [`troubleshooting.md`](troubleshooting.md) | `troubleshooting` 스킬 |
| 설계 정본 사본(SA MASTER), 마일스톤 | [`core/`](core/) | [`core/milestones.md`](core/milestones.md)는 Notion 일정관리와 함께 고친다 |
| 설계 검토 결과 | [`sa-review/`](#sa-검토) | 아래 목록 |
| 에이전트용 학습 운영 규칙 | [`agents/learning-guide.md`](agents/learning-guide.md) | |
| 문서·커밋 작성 규칙 | [`guides/writing.md`](guides/writing.md), [`guides/git-workflow.md`](guides/git-workflow.md) | |
| 완료 기준, 작업 흐름, 디버깅 | [`agents/quality-gates.md`](agents/quality-gates.md) | |

스킬 정본은 [`.agents/skills/`](../.agents/skills/README.md)에 있다.

## SA 검토

| 문서 | 내용 |
| --- | --- |
| [sa-review/01-sa-1st-review.md](sa-review/01-sa-1st-review.md) | SA MASTER 1차 설계 검토 종합 (codex + Claude 에이전트 교차 검토). 서비스별 판정, Critical·Major 발견 사항, 발제 대비 누락, 팀 논의 목록 |
| [sa-review/02-pg-timeout-handling.md](sa-review/02-pg-timeout-handling.md) | 실제 PG사의 승인 타임아웃 처리 방식(망취소, 멱등 재시도·조회)과 Mock PG 적용안 |
| [sa-review/03-seat-hold-kafka-design.md](sa-review/03-seat-hold-kafka-design.md) | M1 상세: Kafka 파티션 키, 결과 전달, 발신 유실, Redis + Kafka 혼합형 좌석 선점 |
| [sa-review/04-mentor-feedback-1026.md](sa-review/04-mentor-feedback-1026.md) | SA 1차 피드백(10/26) 28건 정리(피드백 메모 + SA 문서 코멘트 19개)와 팀 논의 사항 12건. v2 상태 저장, 환불 상태 모델, 토큰 키 분리, Redis 장애 정책, 보안그룹, 숫자 근거 |
| [sa-review/05-feedback-improvement-proposal.md](sa-review/05-feedback-improvement-proposal.md) | 04의 논의 사항을 SA 문서에 반영하는 구체 개선안. 토큰 규칙, v2 접수 요청 테이블, 환불 모델과 재확인 스케줄러, Redis 정책, 보안그룹, 메모리 예산, 새 테스트 케이스(E11~E16) |
| [sa-review/assets/seat-hold-kafka.html](sa-review/assets/seat-hold-kafka.html) | 03 문서의 시각화 페이지 로컬 사본 (브라우저로 열기) |
| [sa-review/raw/codex-review.md](sa-review/raw/codex-review.md) | codex 리뷰 원문 |

## 외부 원본

- Notion [SA MASTER v3 (통합)](https://app.notion.com/p/4057bd823b75816fa131f478bd7faacb) — 설계 정본 (10/26 SA v2·SA MASTER·[5조] SA 문서·피드백 모음·개선안을 통합, 충돌은 최신 우선). 저장소 사본 [`core/SA MASTER.md`](core/SA%20MASTER.md)는 v3.1로, 10/26 결정 기록 수정을 반영해 `🆕 제안` 표시를 없애고 결정 기록 번호를 붙였다. 각 결정의 확정 여부는 Notion 「SA 기획·설계 결정 기록」 DB 상태를 따른다
- Notion [SA MASTER](https://app.notion.com/p/4027bd823b75808bb67efd7dbd1f7c53) — 10/26 이전 정본, 근거 보관용
- Notion [1차 SA 피드백](https://app.notion.com/p/4027bd823b758034966df87b7622c9b1) — sa-review/01과 같은 내용
- Notion [발제문 원본](https://app.notion.com/p/4027bd823b758019a04dda70d6ae39ab), [첫날 회의](https://app.notion.com/p/4027bd823b7580b19317f4c4c8df4a08)
- 시각화 [좌석 선점 Kafka 설계 (Artifact)](https://claude.ai/artifact/3dWEW9UCxs2opzgLqFwsHH) — 비공개 상태라 팀원에게는 공유 설정이 필요하다

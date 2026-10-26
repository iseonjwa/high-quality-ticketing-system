# AGENTS.md

콘서트 티켓팅 MSA 부트캠프 최종 프로젝트(5조 이선좌). 이 파일은 모든 에이전트(Claude Code, Codex 등)의 공통 진입점이며 대전제만 둔다. 세부 절차는 아래 표의 문서와 공용 스킬에 있다.

## 프로젝트
- 주제: 티켓 오픈 순간의 폭주 트래픽을 견디는 콘서트 예매 서버. 대기열, 좌석 선점, 예매↔결제 SAGA, 추첨 자동결제 예매.
- 기술: 발제 기준 Spring Boot·Spring Cloud MSA, Kafka, Redis, PostgreSQL, Prometheus·Grafana, docker-compose. 버전과 모듈 구성은 2차 SA에서 확정한다.
- 설계 정본: Notion [SA MASTER](https://app.notion.com/p/4027bd823b75808bb67efd7dbd1f7c53). 설계와 다르게 구현해야 하면 먼저 사용자에게 확인하고 `docs/decisions/`에 기록한다.
- 팀 공유 저장소다. 팀 합의(SA 문서, 결정 기록)를 개인 판단으로 덮어쓰지 않는다.

## 대전제
1. **완성과 학습을 함께 추구한다.** 사용자는 백엔드 취업을 준비 중이다. "무엇을"과 함께 "왜"를 남긴다.
2. **질문은 기록한다.** 사용자가 지식이나 방법을 물으면 답한 뒤 `docs/learning/<이름>/qna/`에 날짜와 함께 저장한다(`<이름>`은 팀원 영문 이름, 예: junhyun).
3. **AI 판단은 모두 기록한다.** 사용자가 AI(Claude Code, codex, ChatGPT 등)의 응답·제안·리뷰·코드를 받고 수용, 부분 수용, 거부, 보류, 선택지 고르기 같은 판단을 내리면 그 순간을 `docs/ai-judgments/<이름>/`에 남긴다. 팀이 함께 알아야 할 판단은 `shared: 예`로 표시해 `docs/ai-judgments/README.md`에 모은다. 발제 필수 "AI 설계 검증 로그"(3건 이상, 거부 1건 이상)와 발표 대표 사례(2건 이상)의 원천이다. 절차는 `ai-judgment` 스킬이다.
4. **결정은 이유와 함께 남긴다.** 버전, 구조, 라이브러리, 서비스 경계처럼 되돌리기 어려운 선택은 `docs/decisions/`에 기록한다.
5. **품질은 작업량과 무관하게 유지한다.** 검증 근거 없이 완료라고 말하지 않는다.
6. **구현 주체는 작업마다 다르다.** 기본은 에이전트가 구현하고, 사용자가 "내가 할게"라고 하면 단계 안내와 리뷰만 한다.
7. **톤**: 코드 주석, 문서, 커밋은 한국어 평서체로 쓴다. AI 말투(과한 공감, 감탄, 장황한 서두)와 이모지를 쓰지 않는다.
8. **비밀값 금지**: 토큰, 실제 비밀번호, `.env`는 커밋하지 않는다.

## 작업별 필수 문서
| 언제 | 읽을 문서 |
|---|---|
| 문서를 어디에 둘지 모를 때 | `docs/README.md` (문서 지도) |
| 사용자 질문에 답할 때, 개념을 설명할 때 | `docs/agents/learning-guide.md` |
| 사용자가 AI 응답·제안에 판단을 내렸을 때 | `.agents/skills/ai-judgment/SKILL.md`, `docs/ai-judgments/README.md` |
| PR을 만들 때 | `.github/pull_request_template.md` (STAR, AI 기록 링크 칸) |
| 결정을 기록할 때 | `docs/decisions/README.md` |
| 설계 검토 결과를 볼 때 | `docs/sa-review/` |

## 공용 스킬 (`.agents/skills/`)
| 스킬 | 언제 | 기록 위치 |
|---|---|---|
| `ai-judgment` | 사용자가 AI의 응답·제안·리뷰를 수용·거부·보류·선택했을 때 | `docs/ai-judgments/<이름>/` |
| `learning-note` | 개념·방법 질문에 답했을 때. 하루 정리는 사용자가 원할 때만 | `docs/learning/<이름>/` |
| `adr` | 되돌리기 어려운 결정을 했을 때 | `docs/decisions/` |
| `troubleshooting` | 막혔던 문제를 해결했을 때 | `docs/troubleshooting.md` |

- **에이전트는 기록을 알아서 남긴다.** 위 표의 상황이 되면 동의를 기다리지 않고 해당 위치에 쓰고, 답 끝에 `기록: <경로>`를 남긴다. 한 메시지가 질문이면서 판단이면 둘 다 기록한다. 사람은 PR에서 기록을 검토하고 고친다.
- 스킬 목록은 `.agents/skills/README.md`에 있다. Codex는 `.agents/skills`를 직접 읽는다. Claude Code는 `script/setup-agents.sh`를 실행하면 `.claude/skills`에서 찾는다. 스킬을 자동으로 불러오지 못하는 에이전트는 해당 `SKILL.md`를 직접 읽고 따른다.

## 개인 지침
팀 규칙이 맞지 않는 부분은 저장소를 고치지 않고 개인 지침으로 보탠다. 팀 규칙과 충돌하면 팀 규칙이 우선이다.
- Claude Code: 루트 `CLAUDE.local.md`(`.gitignore`에 있다) 또는 `~/.claude/CLAUDE.md`.
- Codex: `~/.codex/AGENTS.md`.

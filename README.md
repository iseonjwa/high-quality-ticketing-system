# high-quality-ticketing-system

콘서트 티켓팅 MSA 부트캠프 최종 프로젝트(5조 이선좌). 티켓 오픈 순간의 폭주 트래픽을 견디는 예매 서버를 만든다. 대기열, 좌석 선점, 예매↔결제 SAGA, 추첨 자동결제 예매가 범위다. 설계 정본은 Notion [SA MASTER v3 (통합)](https://app.notion.com/p/4057bd823b75816fa131f478bd7faacb)다.

## 처음 시작하기

clone한 뒤 아래 세 가지를 한다. 3분이면 끝난다.

1. Claude Code를 쓰면 스킬을 연결한다. 저장소 루트에서 `script/setup-agents.sh`를 실행한다. 공용 스킬(`.agents/skills/`)이 `.claude/skills/`에 연결된다. pull로 스킬이 바뀌면 다시 실행한다. Windows는 Git Bash에서 실행한다. 심볼릭 링크를 못 만들면 복사로 대신하며, 이때는 원본이 바뀔 때마다 다시 실행해야 한다.
2. 내 폴더는 에이전트가 만든다. 처음 질문하거나 AI 판단을 내리면 에이전트가 `docs/learning/<이름>/`과 `docs/ai-judgments/<이름>/`을 만들어 준다. `<이름>`은 팀원 영문 이름(예: junhyun)이다. 다른 사람 폴더는 고치지 않는다.
3. Claude Code는 저장소 루트에서 연다. `.claude/settings.json`의 훅이 자동으로 적용된다. 별도 설정은 없다. Codex는 훅이 없고 `AGENTS.md`를 읽고 따른다.

## 기록은 에이전트가 남긴다

| 상황 | 기록 위치 | 스킬 |
|---|---|---|
| 개념·방법을 물었다 | `docs/learning/<이름>/qna/` | `learning-note` |
| AI의 응답·제안을 수용·거부·보류·선택했다 | `docs/ai-judgments/<이름>/` | `ai-judgment` |
| 되돌리기 어려운 결정을 했다 | `docs/decisions/` | `adr` |
| 막혔던 문제를 해결했다 | `docs/troubleshooting.md` | `troubleshooting` |

- 에이전트는 동의를 기다리지 않고 기록하고 답 끝에 `기록: <경로>`를 남긴다. 사람은 PR에서 기록을 검토하고 고친다.
- 팀이 함께 알아야 할 AI 판단은 기록 머리말에 `shared: 예`를 쓰고 [`docs/ai-judgments/README.md`](docs/ai-judgments/README.md)의 "팀 공통 판단" 표에 올린다.
- 판단 근거는 사용자가 말한 대로 적는다. 말하지 않았으면 `미기재`로 남고, 판단자가 나중에 채운다.
- 기록 구조는 `script/records-check.sh`로 확인한다. Claude Code는 종료 전에 자동으로, GitHub는 PR마다 검사한다.
- 비밀값(`.env`, 토큰, 실제 비밀번호)은 커밋하지 않는다.

## 개발 워크스페이스

Java 21, Spring Boot 4.1.2, Spring Cloud 2025.1.4 기반의 앱 7개와 공통 라이브러리를 사용한다. Config Server·Eureka·Gateway·User·Reservation·Payment·Mock PG와 전체 인프라를 준비한다. 검증·개발 도구는 Java·Gradle로 통일하며 Python 설치는 필요 없다.

```bash
./gradlew initLocalEnv
docker compose -f compose.yml -f compose.local.yml up -d --build --wait --wait-timeout 180
./gradlew :system-tests:systemTest -PredisFailure=true --no-daemon
```

전체 검사는 `./gradlew verifyWorkspace --no-daemon`으로 실행한다. 환경·앱 실행은 [워크스페이스 가이드](docs/guides/workspace.md), JUnit 위치와 실행 범위는 [테스트 가이드](docs/guides/testing.md)를 따른다.

## PR

`.github/pull_request_template.md`가 자동으로 채워진다. STAR(상황·과제·행동·결과) 형식이고, 이 PR에 쓴 AI 판단 기록 링크를 적는 칸이 있다.

## 문서 지도

[`docs/README.md`](docs/README.md)에서 시작한다. 에이전트 공통 규칙의 정본은 [`AGENTS.md`](AGENTS.md)이고, Claude Code 전용 기능은 [`CLAUDE.md`](CLAUDE.md)에 있다.

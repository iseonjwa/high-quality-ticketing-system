# Git 작업 가이드

커밋, PR, 올리면 안 되는 것, 에이전트 규칙, 브랜치 전략이다. 브랜치 전략(5절)은 2026-10-27 통합 회의에서 정했다.

## 1. 커밋 메시지
```
type(scope): 요약
```

| type | 언제 |
|---|---|
| `feat` | 기능 추가 |
| `fix` | 버그 수정 |
| `refactor` | 동작이 바뀌지 않는 구조 변경 |
| `test` | 테스트만 |
| `docs` | 문서만 |
| `chore` | 빌드, 설정, 의존성 |

- scope는 서비스나 영역 이름이다: `user`, `queue`, `reservation`, `lottery`, `payment`, `gateway`, `infra`, `ci`, `agents`, `docs`. 예: `docs(agents): 판단 기록을 사람별 폴더로 분리`
- 요약은 한국어 평서형으로 50자 안쪽에 쓰고 마침표를 찍지 않는다.
- 본문은 기본으로 쓰지 않는다. 제목만으로 이유가 안 보일 때만 한두 문장 쓴다. 변경 내용을 불릿으로 나열하지 않는다.
- 한 커밋에 한 가지 변경만 담는다. 빌드가 깨지는 중간 커밋을 만들지 않는다.
- `Co-Authored-By` 같은 AI 공동 작성자 표기와 "Generated with ..." 같은 생성 도구 문구를 넣지 않는다.

## 2. PR
- 제목은 커밋 형식(`type(scope): 요약`)으로 쓴다.
- 본문은 STAR 형식이고 `.github/pull_request_template.md`가 자동으로 채워진다. 상황은 왜 필요한가, 과제는 끝내야 하는 것과 범위 밖인 것, 행동은 무엇을 바꿨나, 결과는 실행한 명령과 결과다. 확인하지 못한 것은 "미확인"으로 적는다.
- 이 PR에 쓴 AI 판단 기록 링크를 "AI 활용 기록" 칸에 적는다.
- 스킬(`.agents/skills/`), 훅, `AGENTS.md`를 바꿨으면 본문에 이유를 쓴다.
- 서비스 간 접점을 바꿨거나 `contract` 이슈를 받아 구현했으면 이슈 번호를 본문에 쓴다([contract-issues.md](contract-issues.md)).

## 3. 올리면 안 되는 것
`.env`, `.env.*`(`.env.example` 제외), 키 파일(`*.pem`, `*.key`, `*.p12`, `*.jks`), IDE 설정(`.idea/`), 빌드 결과, 개인 에이전트 설정(`CLAUDE.local.md`, `.claude/settings.local.json`), `script/setup-agents.sh` 결과물(`.claude/skills/`). `.gitignore`에 있는 것은 모두 해당한다. 나에게만 필요한 제외 경로는 `.git/info/exclude`에 쓴다.

비밀값을 실수로 커밋했다면 되돌리는 것으로 끝나지 않는다. 값을 바로 바꾸고 팀에 알린다.

## 4. 에이전트 규칙
- 사용자가 요청할 때만 커밋하고 푸시한다.
- force push, `reset --hard`, 브랜치 삭제는 사용자 확인 후에만 한다.
- 커밋할 때는 경로를 지정해 `git add`한다. `.env`나 개인 설정이 섞이지 않게 한다.
- 커밋 메시지를 쓰기 전에 이 문서의 1절을 따른다.

## 5. 브랜치 전략

| 항목 | 규칙 |
|---|---|
| 이름 | `{type}/ISE-{번호}-{주제}`. 예: `chore/ISE-1-init-env`. type은 1절의 커밋 type, 번호는 Linear 이슈 번호다. 담당자는 이슈에 있으므로 이름에 사람 이름을 넣지 않는다 |
| 출발점 | 작업 브랜치는 `dev`에서 만든다 |
| 병합 방식 | squash가 기본이다. 커밋 단위 이력을 남겨야 할 때만 merge commit을 쓴다 |
| 리뷰 | 필수 승인은 없다. 작성자가 AI 검증을 거친 뒤 머지하고 팀에 알린다. 다른 사람의 작업을 도왔을 때만 그 사람에게 리뷰를 요청한다 |
| 보호 규칙 | `main`·`dev`에 직접 push·force push·삭제를 하지 않는다. PR로만 반영하고, 필수 승인은 0명이지만 CI(`build`, `records-check`) 통과가 필수다(GitHub 브랜치 보호 설정). 리뷰를 두지 않는 대신 CI를 자동 관문으로 둔다 |
| main 반영 | 마일스톤마다 `dev` → `main` |

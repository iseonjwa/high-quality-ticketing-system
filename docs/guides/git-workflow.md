# Git 작업 가이드

커밋, PR, 올리면 안 되는 것, 에이전트 규칙, 브랜치 전략, 이슈·PR 메타데이터(담당자·라벨·마일스톤, Linear 연동)다. 브랜치 전략(5절)은 2026-10-27 통합 회의에서 정했다. 6절은 2026-11-02 점검(ISE-141) 결과다.

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
| 보호 규칙 | `main`·`dev`에 직접 push·force push·삭제를 하지 않는다. PR로만 반영하고, 필수 승인은 0명이지만 CI(`build`, `records-check`) 통과가 필수다(GitHub 브랜치 보호 설정). 리뷰를 두지 않는 대신 CI를 자동 관문으로 둔다. `build`는 `frontend/`만 바뀐 PR에서 Java 단계만 건너뛰고 성공으로 보고한다(`script/ci-changed-areas.sh`). 필수 검사라 워크플로에 `paths` 필터를 걸지 않는다. 걸면 검사가 보고되지 않아 머지가 막힌다. `frontend` 검사는 `frontend/`가 바뀐 PR에서만 뜨고 필수 검사가 아니다 |
| main 반영 | 마일스톤마다 `dev` → `main` |

## 6. 이슈·PR 메타데이터와 Linear 연동

11/2 점검에서 GitHub PR 대부분에 담당자·라벨·마일스톤이 비어 있었고, GitHub에는 마일스톤이 아예 없었다(ISE-141). 아래는 그때 확인한 동작과 맞춘 기준이다.

### 6-1. 어디서 무엇을 관리하나

| 대상 | 정본 | GitHub에 어떻게 보이나 |
|---|---|---|
| 이슈(할 일·완료 기준) | Linear | Linear 이슈가 GitHub 이슈로 동기화된다. 담당자·라벨은 따라온다. **마일스톤은 따라오지 않는다**(11/2 GitHub 마일스톤을 만든 뒤 생긴 이슈 #214도 비어 있음). GitHub 이슈의 마일스톤이 필요하면 직접 지정한다 |
| PR | GitHub | Linear 이슈에 PR이 첨부되고, `linear-code[bot]`이 PR에 이슈 링크 코멘트를 단다(브랜치 이름이나 제목의 `ISE-번호`로 연결) |
| 담당자·라벨·마일스톤 | 이슈는 Linear, PR은 GitHub에서 직접 | **PR은 자동으로 채워지지 않는다.** PR을 열 때 직접 지정한다 |

### 6-2. PR을 열 때 지정할 것

| 항목 | 기준 |
|---|---|
| 담당자(Assignees) | PR 작성자 |
| 라벨 | 연결한 Linear 이슈의 라벨을 그대로(`Feature`·`Improvement`·`contract`·`queue`·`payment`·`frontend`). 문서만 바꾼 PR은 `documentation`을 더한다. 맞는 라벨이 없으면 비운다 |
| 마일스톤 | 연결한 Linear 이슈의 마일스톤과 같은 이름(`M1. 공통 모듈 + 인증기반 제작` · `M2. 일반 예매 한 흐름 연결` · `M3. MVP 완성·배포` · `M4. 고도화`). 이슈에 마일스톤이 없으면 비운다 |
| 리뷰어 | 다른 담당 영역 코드를 바꿨을 때 그 담당(5절 리뷰) |

명령으로 한 번에 지정할 수 있다.

```bash
gh pr create --base dev --title "feat(lottery): 요약 #ISE-00" --body-file body.md \
  --assignee @me --label Feature --milestone "M2. 일반 예매 한 흐름 연결"
```

이미 연 PR은 `gh pr edit <번호> --add-assignee <아이디> --add-label <라벨> --milestone "<마일스톤>"`으로 고친다. 머지된 PR도 고칠 수 있다.

### 6-3. Linear 상태와 체크리스트

| 시점 | 할 일 |
|---|---|
| 착수 | 상태를 In Progress로 바꾼다 |
| PR을 연 뒤 | In Review로 바꾼다. PR을 연다고 자동으로 바뀌는지는 확인 못 했다 |
| 머지 | 연결된 이슈가 **자동으로 Done**이 된다(11/2 ISE-85~93에서 확인) |
| 머지 직후 | 이슈 본문의 할 일·완료 기준 체크박스를 결과에 맞게 고친다. 끝난 항목은 체크하고 PR 번호를 적는다. 미룬 항목은 이유와 옮긴 이슈를 적는다 |

**부분 완료로 자동 Done이 된 경우**: 머지는 이슈의 남은 항목과 상관없이 Done으로 바꾼다(ISE-88, 이전 ISE-19). 남은 항목이 다른 작업을 기다리는 것이면 이슈를 다시 열지 않고 **후속 이슈**를 만들어 옮긴다. 후속 이슈에 막는 이슈(blocked by)를 걸고, 원래 이슈에는 후속 이슈 링크를 남긴다. 이렇게 해야 "끝낸 것"과 "기다리는 것"이 섞이지 않는다.

### 6-4. 머지 뒤 정리

- 원격 브랜치를 지운다(PR 화면의 Delete branch). 로컬 브랜치도 지운다.
- 쌓아 올린 PR(앞 PR 브랜치에서 만든 브랜치)은 앞 PR이 squash로 머지되면 dev와 충돌할 수 있다. dev를 병합(`git merge origin/dev`)해서 푼다. rebase·force push는 하지 않는다(4절). 충돌 파일이 앞 PR과 같은 내용이면 내 브랜치 쪽을 택하면 된다(dev의 내용이 앞 PR과 같은지 먼저 확인).

---
title: 성능 측정 도중 측정 기기를 바꾸면 결과가 부정확해지는가
date: 2026-11-09
updated: 2026-11-09
asker: KWON-WOO
level: 중급
tags: [performance, k6, measurement, docker, reproducibility]
---

# 성능 측정 도중 측정 기기를 바꾸면 결과가 부정확해지는가

## 질문
이 기기로는 더이상 메모리 확보가 어려울 거 같아서 다른 기기로 하려고 하는데 측정 결과가 부정확할까?

맥락: ISE-79 k6 단계 측정(base → G1 → R1 → P4a → P4b → …)을 노트북(호스트 16GB, Docker VM 약 7.6GiB, 16코어)의 Docker Desktop에서 하던 중이었다. 호스트 메모리가 부족해 base 재측정이 중간에 끊겼다.

## 짧은 답
- 측정 하나하나가 부정확해지는 것은 아니다. 다만 **기기마다 절대 수치가 달라** 다른 기기에서 잰 단계끼리는 비교할 수 없다.
- 단계 측정의 가치는 "같은 환경에서 하나만 바꿨을 때의 차이"다. 기기가 바뀌면 CPU, 메모리, 디스크, Docker VM 설정이 한꺼번에 바뀐다. 그러면 단계 차이와 기기 차이를 가를 수 없다.
- 그래서 기기를 바꾸면 **비교에 쓰는 단계를 새 기기에서 모두 다시 잰다.** 이 노트북에서 잰 결과는 "이전 기기 참고 기록"으로 남긴다.
- 어차피 base·G1·R1을 다시 재기로 했고, 다시 잰 것은 P4a·P4b와 base 일부뿐이다. 그래서 새 기기에서 base부터 P4b까지 다시 재도 추가 비용은 P4a·P4b 4회(약 50분)다.

## 자세히

### 1. 기기에 따라 바뀌는 것
| 요소 | 결과에 주는 영향 |
|---|---|
| CPU 코어 수·세대 | 컨테이너에 CPU 상한이 없어 reservation-service가 최대 5~6코어, PostgreSQL이 4.5코어까지 썼다. 코어가 적거나 느리면 같은 단계라도 처리량이 낮게 나온다 |
| Docker VM 메모리 | 서비스 전체(약 4.5GiB)와 k6(최대 약 2GiB)가 VM 안에서 나눠 쓴다. VM이 작으면 스왑으로 넘어가 측정이 무효가 된다(P4b 2차) |
| 디스크 | PostgreSQL 쓰기와 스왑 속도가 달라진다 |
| OS·Docker 종류 | Windows·macOS의 Docker Desktop은 VM 안에서 돈다. 포트 전달 방식이 달라 내부망 직결을 쓰지 않으면 결과가 크게 달라진다(P4b 4차) |
| CPU 구조 | Apple Silicon(arm64)이면 이미지와 JVM이 arm64로 돌아 성능 특성이 다르다 |

컨테이너 메모리 상한(`compose.deploy-memory.yml`)은 기기와 상관없이 같다. 그래서 GC 동작처럼 메모리에 매인 현상은 비슷하게 재현된다. CPU 상한은 없으므로 CPU에 매인 처리량은 기기를 따라간다.

### 2. 무엇을 다시 재야 하나
- 비교에 쓰는 회는 모두 같은 기기에서 잰다. 남은 단계(P4c 이후)와 비교할 기준선, 곧 base·G1·R1·P4a·P4b를 새 기기에서 다시 잰다.
- 이전 기기 결과는 지우지 않는다. "왜 이 단계를 넣었나"(직면한 병목)의 근거로는 그대로 쓸 수 있다. 병목의 종류(GC → 커넥션 → DB 조인 → Gateway)는 기기가 바뀌어도 대체로 같은 순서로 나타날 것으로 보지만, 새 기기 결과로 확인한다.
- 결과 `meta`에 기기 정보(CPU 모델, 코어 수, 호스트 메모리, Docker VM 메모리, Docker 버전)를 남겨, 나중에 어느 기기에서 잰 회인지 가릴 수 있게 한다.

### 3. 기기를 옮길 때 챙길 것
- 측정 브랜치(`experiment/ISE-79-k6-steps`)와 feat 브랜치 로컬 커밋은 원격에 올라가 있지 않다. 다른 기기로 가져가려면 푸시하거나 번들(`git bundle`)로 옮긴다.
- P4c·P4d 코드는 `ise-79-p4-measure` 워크트리에 **커밋되지 않은 채** 있다. 옮기기 전에 커밋하거나 패치로 뽑는다.
- `.env`는 저장소에 없다. 새 기기에 따로 둔다(커밋하지 않는다).
- 결과 파일(`reservation-service/k6/results/`)은 gitignore라 따라가지 않는다. 이전 기기 결과를 참고로 남기려면 따로 복사한다.

#### 3-1. 브랜치 말고 옮기거나 다시 갖출 것 (2026-11-09 보강)
후속 질문: "핸드오프 옮기는 거 말고 새 기기에서 진행하기 위해 또 옮겨야하는 건 뭐가 있을까?"

| 구분 | 항목 | 이유 |
|---|---|---|
| 옮김 | `.env` | 저장소에 없다. 서명 키와 DB 비밀번호가 있어 메신저·공개 드라이브가 아닌 안전한 경로로 옮긴다 |
| 옮김 | Claude Code 메모리(`~/.claude/projects/<프로젝트 경로>/memory/`) | 에이전트가 "담당 영역 밖은 건드리지 않는다", "측정은 핸드오프부터 읽는다"를 기억하는 파일이다. 새 기기의 폴더 이름은 저장소 경로에 따라 정해지므로, 새 기기에서 Claude Code를 한 번 연 뒤 생긴 폴더에 복사한다 |
| 다시 갖춤 | `script/setup-agents.sh` 실행 | 공용 스킬을 `.claude/skills`에 연결한다. clone한 뒤 한 번 실행한다 |
| 다시 갖춤 | `git config user.name` | 판단·학습 기록의 판단자(`KWON-WOO`)와 같게 둔다 |
| 다시 갖춤 | 도구 | Docker Desktop(VM 메모리 설정 포함), Git(Windows면 Git Bash. 측정 스크립트가 bash다), JDK 21(로컬 테스트와 JFR 분석 `jfr view`), Python 3(측정 도구는 표준 라이브러리만 쓴다), curl. k6는 Docker 이미지라 설치하지 않는다 |
| 선택 | 이전 기기 결과 `reservation-service/k6/results/` | 새 기기에서 다시 재므로 필수는 아니다. 이전 기기 수치를 직접 보고 싶을 때만 복사한다 |
| 옮기지 않음 | Docker 이미지·볼륨 | 새 기기에서 빌드하고, 측정 DB도 새로 만든다. 측정은 회마다 새 회차를 만들어 쓰므로 이전 DB가 필요 없다 |
| 옮기지 않음 | 이전 동시성 측정 워크트리(`ise-79-measure`)의 커밋하지 않은 변경 | S0~S4 측정 때의 도구다. 결과 문서는 이미 저장소에 있고 남은 측정에 쓰지 않는다 |

#### 3-2. 새 기기에서 어느 브랜치로 작업하나 (2026-11-09 보강)
후속 질문: "어느 브랜치에서 진행해야해? 그건 상관 없나?"

상관 있다. `step-run.sh`는 **실행한 작업 트리의 지금 커밋**으로 reservation-service를 빌드해 잰다. 브랜치를 잘못 고르면 다른 코드를 재게 된다. 역할에 따라 세 곳을 나눠 쓴다.

| 위치 | 브랜치 | 하는 일 |
|---|---|---|
| 저장소 루트(기본 체크아웃) | `feat/ISE-79-seat-hold-race-condition` | `.env`와 핸드오프를 두는 곳. 판단 기록·학습 노트·계획 문서를 커밋한다. 측정은 하지 않는다 |
| `.claude/worktrees/ise-79-k6-steps` | `experiment/ISE-79-k6-steps` | **측정을 돌리는 곳.** 단계 커밋(P4c 등)과 `STEPS.md` 기록을 여기에 쌓는다 |
| 같은 워크트리, 임시 브랜치 | `experiment/ISE-79-remeasure-net` 등(측정 브랜치에서 만들고 푸시하지 않음) | base·G1·R1·P4a처럼 src를 앞 단계로 되돌려 다시 잴 때만 쓴다. 끝나면 측정 브랜치로 돌아온다 |
| `.claude/worktrees/ise-79-p4-measure` | `experiment/ISE-79-p4-measure` | P4c·P4d 코드를 떼어 오는 원본. 여기서 재지 않는다 |

- 측정 워크트리에서 브랜치를 바꾸는 동안 측정이 돌고 있으면 안 된다. 다음 회가 바뀐 코드로 빌드된다(원래 기기에서 실제로 일어날 뻔했다).
- Compose 프로젝트 이름이 같아(`iseonjwa`) 스택은 기기에 하나만 뜬다. 측정 워크트리에서 띄운 이미지가 원래 체크아웃의 스택을 대신하므로, 측정 중에는 저장소 루트에서 스택을 따로 띄우지 않는다.
- 측정 결과를 실제 코드에 반영할 때는 이슈마다 dev에서 새 브랜치를 판다(P4 → ISE-189, R1 → ISE-196, G1·P1 → contract 이슈). 측정 브랜치는 머지하지 않는다.

#### 3-3. 긴 측정이 Claude Code에 회수되지 않게 띄우기 (2026-11-09 보강)
후속 질문: "그 환경변수 지정은 어떻게 하고 클로드 코드는 어디서 열어야해? 그냥 윈도우 터미널로 열면 되나?"

새 기기(호스트 32GB, Docker VM 16GB)에서도 base-net H 1회째에 호스트 여유 메모리가 2.1GB로 떨어지자 Claude Code가 백그라운드 측정 반복을 회수했다. Claude Code는 호스트 메모리가 모자라면 대화가 쉬는 동안 백그라운드 셸을 멈춘다. `CLAUDE_CODE_DISABLE_BG_SHELL_PRESSURE_REAP=1`이 이 회수를 끈다.

- **변수는 Claude Code 프로세스가 시작될 때 환경에 있어야 한다.** Claude Code가 띄운 셸 안에서 `export`하면 그 셸과 자식에만 들어가고 Claude Code 자신은 모른다. 그래서 대화 안에서 넣으면 효과가 없다.
- **어디서 열어도 된다.** Windows Terminal, IntelliJ 터미널 모두 PowerShell이면 같은 방법이다. 측정 중에는 IntelliJ를 닫아 메모리를 아끼므로 Windows Terminal이 낫다. IntelliJ 터미널에서 띄운 Claude Code는 IntelliJ를 닫으면 함께 꺼진다.
- **저장소 루트에서 연다.** Claude Code 메모리 폴더와 대화 기록은 연 폴더 경로로 나뉜다(`~/.claude/projects/C--SPARTA-MSA-high-quality-ticketing-system/`). 같은 폴더에서 `claude --continue`로 열면 직전 대화를 이어 간다.

PowerShell에서 이번 창에만 넣고 띄우기:
```powershell
cd C:\SPARTA_MSA\high-quality-ticketing-system
$env:CLAUDE_CODE_DISABLE_BG_SHELL_PRESSURE_REAP = "1"
claude --continue
```

매번 넣기 번거로우면 사용자 환경변수로 고정한다(새로 연 터미널부터 적용된다).
```powershell
[Environment]::SetEnvironmentVariable("CLAUDE_CODE_DISABLE_BG_SHELL_PRESSURE_REAP", "1", "User")
```

- 확인: 새 Claude Code에서 셸로 `echo $CLAUDE_CODE_DISABLE_BG_SHELL_PRESSURE_REAP`를 실행해 `1`이 나오면 Claude Code가 그 값을 갖고 시작했다(셸은 Claude Code의 환경을 물려받는다).
- 회수를 끄면 호스트 메모리가 정말 바닥날 때 Claude Code가 막아 주지 않는다. Docker VM을 `.wslconfig`로 16GB에 묶어 두었으므로 호스트(32GB)에는 나머지가 남는다. 측정 중에는 IntelliJ·브라우저를 줄인다.
- 회수돼도 셸 스크립트 자식은 계속 돌 수 있다(이번에도 H 1회째는 끝까지 돌았다). 끊기면 `step-run.sh` 프로세스가 남았는지 먼저 본다.

### 4. 기기 차이를 줄이는 방법 (선택)
- 컨테이너마다 CPU 상한(`cpus`)을 걸면 기기 의존이 줄어든다. 다만 기준 환경이 바뀌므로 base부터 모두 다시 재야 하고, 상한을 무엇으로 둘지 정해야 한다.
- 새 기기에서 Docker VM 메모리는 지금(약 7.6GiB) 이상으로 둔다. 여유가 있으면 10GiB 이상이 안전하다.

## 이 프로젝트에서는
- 측정 기록: 측정 브랜치 `reservation-service/k6/STEPS.md`
- 기준 환경 정의: 같은 파일 상단 "기준 환경", `tools/step-run.sh`
- 계획: `docs/design/reservation/k6-seathold-bottleneck-plan.md` 1-6절(측정 기준)

## 면접 포인트
- "성능 개선 전후를 어떻게 비교했나": 같은 기기, 같은 기준 환경에서 변경 하나만 넣고 쟀다. 기기를 바꿨을 때는 기준선부터 다시 쟀다. 절대 수치보다 같은 환경 안의 상대 비교가 의미 있다.
- "로컬 측정의 한계는": 부하 생성기와 서버가 한 기기를 나눠 써서 서로 영향을 준다. 그래서 수집 간격, 부하 생성기가 버린 요청, 부하 생성기 메모리로 그 회가 유효한지 먼저 판정했다.

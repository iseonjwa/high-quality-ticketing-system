---
title: git bundle로 브랜치를 옮기는 방법과 push와의 차이
date: 2026-11-09
updated: 2026-11-09
asker: KWON-WOO
level: 기초
tags: [git, bundle, push, workflow]
---

# git bundle로 브랜치를 옮기는 방법과 push와의 차이

## 질문
번들로 옮기는 건 어떻게 하는거고 푸시랑 어떻게 다른거야?

맥락: k6 측정을 다른 기기에서 이어 하려는데, 측정 브랜치와 feat 브랜치의 커밋이 원격(GitHub)에 없고 이 기기에만 있다.

## 짧은 답
- `git bundle`은 커밋과 브랜치 정보를 **파일 하나**로 묶는다. 그 파일을 USB나 클라우드 드라이브로 옮긴 뒤, 새 기기에서 원격 저장소처럼 `git fetch`해서 브랜치를 받는다.
- `git push`는 커밋을 **공유 원격 저장소**에 올린다. 팀원 누구나 보고 받을 수 있고, 원격이 백업도 된다.
- 차이는 "누가 보느냐"와 "어떻게 옮기느냐"다. 번들은 나만 보고 파일로 옮긴다. 푸시는 팀이 보고 네트워크로 옮긴다.
- 둘 다 **커밋된 것만** 옮긴다. 커밋하지 않은 변경, gitignore 파일(`.env`, 측정 결과), 워크트리 구성은 따라가지 않는다.

## 자세히

### 1. 번들 만들기 (지금 기기)
```bash
# 원격의 최신 상태를 받아 둔다. 번들에서 원격에 이미 있는 커밋을 빼기 위해서다
git fetch origin

# 옮길 브랜치들을 넣고, 원격 dev에 이미 있는 커밋은 뺀다(^origin/dev)
git bundle create ise79.bundle \
  experiment/ISE-79-k6-steps \
  feat/ISE-79-seat-hold-race-condition \
  ^origin/dev

# 번들이 온전한지, 받는 쪽에 무엇이 먼저 있어야 하는지 확인한다
git bundle verify ise79.bundle
git bundle list-heads ise79.bundle
```
- `^origin/dev`를 빼면 저장소 전체 이력이 들어가 파일이 커진다. 넣으면 "원격 dev 이후 내 커밋"만 들어간다. 그 대신 받는 쪽에 그 앞 커밋(origin/dev)이 있어야 한다. `verify`가 이 전제 조건(prerequisite)을 보여 준다.
- 번들은 읽기 전용 원격 저장소 파일이라고 보면 된다.

### 2. 번들 받기 (새 기기)
```bash
# 새 기기에서는 평소처럼 GitHub에서 clone한다(전제 조건 커밋이 여기서 생긴다)
git clone <저장소 URL>
cd high-quality-ticketing-system

# 번들에서 브랜치를 같은 이름으로 받는다
git fetch /path/to/ise79.bundle \
  experiment/ISE-79-k6-steps:experiment/ISE-79-k6-steps \
  feat/ISE-79-seat-hold-race-condition:feat/ISE-79-seat-hold-race-condition

git switch experiment/ISE-79-k6-steps
```
- 이미 받은 브랜치를 나중에 다시 옮길 때도 같은 방법으로 번들을 만들고 fetch하면 된다. 브랜치가 앞으로만 나아갔다면 그대로 갱신된다.

### 3. 커밋하지 않은 변경 옮기기
번들은 커밋만 담는다. 워크트리에 커밋하지 않은 코드가 있으면 둘 중 하나를 한다.
- 그 워크트리의 로컬 브랜치에 커밋한 뒤 번들에 그 브랜치를 함께 넣는다. 원격에 올리지 않으므로 팀에는 보이지 않는다.
- `git diff > p4-measure.patch`로 패치 파일을 뽑아 옮기고, 새 기기에서 `git apply p4-measure.patch`로 적용한다. 새 파일(untracked)은 `git add -N`으로 먼저 알려야 diff에 들어간다.

### 4. push와 비교
| | bundle | push |
|---|---|---|
| 옮기는 방법 | 파일을 직접 복사(USB, 드라이브) | 네트워크로 원격 저장소에 올림 |
| 누가 보나 | 나만 | 원격에 접근하는 팀원 모두 |
| 백업 | 파일을 잃으면 끝 | 원격에 남음 |
| 이후 동기화 | 바뀔 때마다 번들을 다시 만들어 옮김 | `pull`/`push`로 이어서 맞춤 |
| 부수 효과 | 없음 | 브랜치 목록에 보임. 저장소 설정에 따라 CI나 이슈 연동(브랜치 이름의 이슈 번호)이 반응할 수 있음 |
| 맞는 경우 | 실험용·임시 브랜치, 팀에 아직 보일 필요가 없는 작업 | 팀이 보거나 리뷰할 작업, 여러 기기에서 계속 이어 할 작업 |

### 5. 무엇을 고를까
- 측정 브랜치는 머지하지 않는 실험 브랜치다. 팀 저장소 브랜치 목록에 남길 이유가 없으면 번들이 맞다.
- feat 브랜치는 어차피 PR로 올릴 작업이다. 지금 푸시해도 되지만, 이 저장소 규칙은 "푸시·PR은 사용자가 요청할 때만"이다. 아직 PR을 낼 때가 아니면 번들로 함께 옮긴다.
- 기기 두 대를 오가며 오래 작업하게 되면 매번 번들을 만드는 것이 번거롭다. 그때는 푸시가 낫다.

## 이 프로젝트에서는
- 옮길 브랜치: `experiment/ISE-79-k6-steps`(측정), `feat/ISE-79-seat-hold-race-condition`(S1~S4 로컬 커밋), `experiment/ISE-79-p4-measure`(P4c·P4d 코드, 커밋하지 않은 변경이 있어 먼저 커밋 필요)
- 따라가지 않는 것: `.env`, `reservation-service/k6/results/`, `.claude/worktrees/` 구성, `HANDOFF-ISE-79-k6.md`(커밋하지 않은 메모)
- 커밋·PR 규칙: `docs/guides/git-workflow.md`

## 면접 포인트
- "인터넷이 안 되거나 원격에 올리기 곤란한 커밋을 옮기는 방법은": `git bundle`로 필요한 범위(`^origin/dev`)만 파일로 묶어 옮기고, 받는 쪽에서 원격처럼 fetch한다. 커밋 해시가 그대로라 나중에 원격과 합칠 때 충돌 없이 이어진다.

## 참고
- Git 공식 문서 `git-bundle`: https://git-scm.com/docs/git-bundle

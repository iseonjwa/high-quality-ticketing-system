---
title: dev→main PR은 왜 충돌했고, 동기화 PR로 어떻게 풀었나(squash 머지와 공통 조상)
date: 2026-11-10
updated: 2026-11-10
asker: kangmin
level: 중급
tags: [git, squash-merge, merge-commit, merge-base, conflict, release-flow, jenkins]
---

# dev→main PR은 왜 충돌했고, 동기화 PR로 어떻게 풀었나(squash 머지와 공통 조상)

## 질문
ISE-233 변경을 main에 반영하려고 올린 dev→main PR(iseonjwa/high-quality-ticketing-system#471)이 왜 충돌이 났는지 물었다. 이어서 머지한 뒤 발생기에서 `git pull`만 하면 측정을 이어 갈 수 있는지, PR을 미리 올려 둬도 되는지도 물었다.

## 짧은 답
- 직전 dev→main 반영(iseonjwa/high-quality-ticketing-system#468)을 **squash 머지**했기 때문이다. main에는 dev의 커밋들을 합친 새 커밋 `3b8b2bc9`(부모 1개)가 생겼지만, dev 이력에는 그 커밋이 없다. 그래서 git이 두 브랜치의 공통 조상(merge base)을 그 전 지점 `11ca26e1`로 잡았다.
- 그 뒤 같은 테스트 파일(`AuthRedisResilienceContextTest.java`)에서 main은 #467 변경(15줄)을, dev는 #467 + #470 변경(75줄)을 가진다. 같은 자리 근처에 dev만 더 붙인 내용이 있어 git이 자동으로 합치지 못했다.
- 내용상으로는 dev가 main을 모두 포함했다(main 대비 dev는 60줄 추가·0줄 삭제). 그래서 main을 dev에 merge commit으로 합치는 동기화 PR(iseonjwa/high-quality-ticketing-system#472)로 이력만 맞췄다.

## 자세히

### 3-way 병합이 충돌을 내는 조건
git은 공통 조상, 내 쪽, 상대 쪽 세 버전을 비교한다(3-way merge).
| 경우 | 결과 |
|---|---|
| 한쪽만 바꿨다 | 바뀐 쪽을 택한다 |
| 양쪽이 같은 자리를 똑같이 바꿨다 | 같은 변경으로 보고 하나만 남긴다 |
| 양쪽이 같은 자리를 다르게 바꿨다 | 충돌 |

이번에는 main 쪽이 X, dev 쪽이 X + Y였다. X 부분은 같지만 같은 덩어리(hunk) 안에 Y가 더 붙어 "다르게 바꿨다"로 보였다.

### squash 머지와 merge commit의 차이
| 방식 | main에 생기는 것 | dev 이력과의 관계 | 다음 반영 |
|---|---|---|---|
| squash | 내용을 합친 새 커밋 1개(부모 1개) | dev 커밋과 다른 커밋이라 dev는 "아직 main에 안 간 변경"으로 본다 | 공통 조상이 갱신되지 않아 이후 같은 파일을 고치면 충돌할 수 있다 |
| merge commit | 부모 2개짜리 커밋(main, dev) | dev 커밋이 그대로 main 이력에 들어간다 | 공통 조상이 dev 끝으로 옮겨 가 다음 반영이 깔끔하다 |

팀 규칙은 "squash가 기본"(`docs/guides/git-workflow.md:49`)이다. 기능 PR(브랜치 → dev)에는 맞지만, 오래 사는 두 브랜치 사이(dev → main)에는 squash가 이력을 갈라 놓는다. 이전 dev→main 반영(iseonjwa/high-quality-ticketing-system#465, iseonjwa/high-quality-ticketing-system#462)은 merge commit이라 이런 일이 없었다.

### 고른 방법과 다른 선택지
| 선택지 | 하는 일 | 감수할 점 |
|---|---|---|
| A. main→dev 동기화 PR(고름) | dev에서 딴 브랜치에서 `git merge --no-ff origin/main`, 충돌은 dev 쪽 선택, dev에 merge commit으로 머지 | PR이 하나 더 생긴다. 이 PR을 squash로 머지하면 같은 문제가 다시 난다 |
| B. main에서 딴 브랜치에 #470만 cherry-pick | #471을 닫고 새 PR | dev와 main 이력이 더 갈라져 다음 반영 때 또 충돌할 수 있다 |
| C. GitHub 충돌 해결 버튼 | #471 화면에서 바로 푼다 | dev 브랜치에 직접 커밋이 들어가 보호 규칙(dev 직접 push 금지)에 어긋난다 |

### 확인한 것
| 확인 | 결과 |
|---|---|
| #468 머지 커밋 부모 수 | 1개(squash) |
| 충돌 예측 | `git merge-tree --write-tree --name-only origin/main origin/dev`가 이 파일 하나를 충돌로 보고 |
| 동기화 커밋 `28c0cad4` | 부모 2개(dev `4bae5e93`, main `3b8b2bc9`), `git diff origin/dev HEAD` 차이 없음 |
| #472 머지 뒤 | `3b8b2bc9`가 dev 이력에 들어감(`git merge-base --is-ancestor`). #471이 `MERGEABLE`이 됨 |

## 이 프로젝트에서는
- 배포는 main 머지로만 일어난다. Jenkins가 2분마다 main을 보고, 새 커밋이면 서버에서 `git checkout --detach <SHA>` 뒤 `deploy.sh`를 돌린다(`Jenkinsfile:125`, `docs/guides/deploy-server-access.md:10,87`). 그래서 dev 머지만으로는 Gateway 이미지도, 서버의 `loadtest/deploy/compose.gateway-bulkhead.yml`도 바뀌지 않는다.
- 발생기 `git pull`은 k6 스크립트가 바뀔 때만 필요하다. ISE-233은 k6를 바꾸지 않았으므로 측정 기록의 발생기 커밋을 맞추는 용도일 뿐이다.
- PR을 여는 것은 배포하지 않는다. 그래서 dev→main PR은 측정 모드가 켜져 있어도 미리 올려 둘 수 있다. 머지만 측정 모드가 꺼진 뒤에 한다(측정 중 main 머지 금지, `deploy-server-access.md:123`).

## 면접 포인트
- squash 머지가 오래 사는 브랜치 사이에서 충돌을 만드는 이유: 공통 조상이 갱신되지 않는다. 3-way 병합의 세 입력을 설명할 수 있으면 답이 된다.
- 충돌을 "내용"과 "이력"으로 나눠 본다. 이번에는 내용 충돌이 아니라 이력이 갈라진 문제라, 내용을 바꾸지 않는 동기화 머지로 풀었다.

## 참고
- `docs/guides/git-workflow.md` 49·51·98행
- iseonjwa/high-quality-ticketing-system#468, iseonjwa/high-quality-ticketing-system#471, iseonjwa/high-quality-ticketing-system#472

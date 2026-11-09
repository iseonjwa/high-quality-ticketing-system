---
title: Anthropic API 키는 어디에 넣고, 배포 때도 따라가나
date: 2026-11-09
updated: 2026-11-09
asker: Junhyun An
level: 초급
tags: [secret, env, deploy, assistant, docker-compose]
---

# Anthropic API 키는 어디에 넣고, 배포 때도 따라가나

## 질문
Anthropic API 키를 발급받았는데 어디에 넣나? 배포할 때도 가져가나?

## 짧은 답
- 로컬: 저장소 루트 `.env`의 `ANTHROPIC_API_KEY=` 뒤에 넣는다. `.env`는 `.gitignore`에 있어 커밋되지 않는다.
- 배포: **자동으로 따라가지 않는다.** 배포 서버의 `~/iseonjwa/.env`에 한 번 직접 넣어야 한다. 그 뒤로는 Jenkins 배포가 매번 그 파일을 읽는다.
- 키를 넣거나 바꾼 뒤에는 `assistant-service` 컨테이너를 다시 만들어야 새 값을 읽는다.

## 자세히

### 1. 바탕 개념: 비밀값은 코드와 따로 다닌다
- 키·비밀번호를 코드나 저장소에 넣으면 저장소를 볼 수 있는 모든 사람(그리고 기록)에 남는다. 그래서 환경마다 파일(`.env`)이나 비밀 저장소에 두고, 실행할 때 환경 변수로 주입한다(12-factor의 Config).
- `docker compose`는 실행한 디렉터리의 `.env`를 자동으로 읽어 `${변수}`를 채운다.

### 2. 이 프로젝트에서 키가 흐르는 길
1. `.env`의 `ANTHROPIC_API_KEY`
2. `compose.yml`의 assistant-service `environment: ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY:-}`
3. `config-repo/assistant-service.yml`의 `anthropic.api-key: ${ANTHROPIC_API_KEY:}`
4. `AnthropicConfiguration`: 키가 비어 있지 않을 때만 Claude 클라이언트를 만든다. 비어 있으면 검색 모드다.

### 3. 배포가 키를 어떻게 다루나
- Jenkins는 배포 서버에 SSH로 들어가 `cd ~/iseonjwa && git checkout <SHA> && script/deploy/deploy.sh <SHA>`를 실행한다(Jenkinsfile).
- `deploy.sh`는 `docker compose -f compose.yml -f compose.deploy.yml up -d`를 그 디렉터리에서 돌리므로, 서버의 `~/iseonjwa/.env`를 읽는다.
- `.env`는 git에 없으니 checkout으로 바뀌지 않는다. 한 번 넣으면 이후 배포가 계속 쓴다.
- `script/deploy/init-env.sh`는 `.env`가 있으면 덮어쓰지 않고 빠진 키만 빈 값으로 더한다. 그래서 넣어 둔 키가 지워지지 않는다.
- 측정 모드(`.env.measure`)는 토큰 서명 키 두 개만 덮어쓰므로 이 키와 관계없다.

### 4. 순서
- 로컬
  1. 편집기로 저장소 루트 `.env`를 열어 `ANTHROPIC_API_KEY=<키>`를 채운다(키를 채팅·커밋에 붙이지 않는다).
  2. `docker compose -f compose.yml -f compose.local.yml up -d --no-deps assistant-service`로 다시 만든다.
  3. `http/assistant.http`로 질문해 응답 `mode`가 `AI`인지, 로그에 `event=ASSISTANT_LLM_DONE`이 찍히는지 본다.
- 배포(PR #404가 main까지 들어간 뒤)
  1. 배포 서버에 SSH로 들어가 `~/iseonjwa/.env`를 편집기로 열어 키를 넣는다.
  2. 바로 반영하려면 `cd ~/iseonjwa && IMAGE_TAG=$(cat .deploy/current) docker compose -f compose.yml -f compose.deploy.yml up -d --no-deps assistant-service`. 다음 Jenkins 배포 때도 반영된다.
  3. 첫 릴리스 뒤 GHCR `assistant-service` 패키지를 공개로 바꿔야 이미지를 받는다.

### 5. 안전하게 쓰기
- Anthropic Console에서 월 사용 한도를 낮게 걸어 둔다. 코드에도 사용자당·전체 일일 상한이 있다.
- 로컬용과 서버용 키를 따로 발급하면, 하나가 새도 그 키만 폐기하면 된다.
- 팀원은 키가 없어도 된다. 키가 없으면 검색 모드로 동작한다.
- 키가 샜다고 의심되면 Console에서 폐기하고 새 키를 `.env`에 넣은 뒤 컨테이너를 다시 만든다.

## 참고
- `compose.yml`(assistant-service), `config-repo/assistant-service.yml`, `script/deploy/deploy.sh`, `script/deploy/init-env.sh`, `Jenkinsfile`
- `docs/guides/deploy-server-access.md`(서버 접속, `.env` 공유 금지)

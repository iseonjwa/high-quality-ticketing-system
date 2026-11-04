---
title: .env 파일과 initLocalEnv는 무엇이고, 통합 뒤 왜 다시 만들어야 하나
date: 2026-10-27
updated: 2026-10-27
asker: GGangMY
level: 기초
tags: [env, docker-compose, secrets, gradle]
---

# .env 파일과 initLocalEnv는 무엇이고, 통합 뒤 왜 다시 만들어야 하나

## 질문
통합 공지의 "기존 .env는 다른 이름으로 바꾸고 ./gradlew initLocalEnv (JWT·ADMIN 키가 새로 필요)"는 무슨 뜻인가.

## 짧은 답
- `.env`는 내 PC에서만 쓰는 비밀값(DB 비밀번호, JWT 서명 키, 운영자 계정)을 적은 파일이다. git에는 올리지 않고, docker compose가 이 값을 읽어 컨테이너에 넣는다.
- `./gradlew initLocalEnv`는 이 `.env`를 무작위 값으로 자동 생성하는 명령이다.
- 이 명령은 `.env`가 이미 있으면 아무것도 하지 않는다. 각자 브랜치 시절의 옛 `.env`에는 통합 코드가 요구하는 키(JWT·ADMIN 등)가 없어서, 옛 파일을 치워야 새 파일이 만들어진다.

## 자세히
### .env와 compose의 관계
`compose.yml`은 값을 직접 쓰지 않고 `${JWT_ACCESS_SECRET:?...}`처럼 변수로 참조한다. compose는 같은 폴더의 `.env`에서 값을 찾는다. `:?` 표시는 "값이 없으면 기동을 멈춰라"라는 뜻이라, 키가 빠지면 컨테이너가 하나도 뜨지 않는다.

통합 코드가 요구하는 키는 `.env.example`에 있다: `DB_USER`, `DB_PASSWORD`, `JWT_ACCESS_SECRET`, `JWT_REFRESH_SECRET`, `ADMIN_EMAIL`, `ADMIN_PASSWORD`, `GRAFANA_ADMIN_PASSWORD`. 예를 들어 강민 브랜치의 옛 `.env`는 `USER_DB_PASSWORD`처럼 서비스별 DB 비밀번호와 `GATEWAY_PORT`를 가졌고, JWT·ADMIN 키가 없었다.

### initLocalEnv가 하는 일
`workspace-tools`의 `LocalEnvironment`가 `.env.example`의 키마다 값을 채운다.
- `DB_USER`는 `iseonjwa`, `ADMIN_EMAIL`은 `admin@iseonjwa.local`로 고정한다.
- 비밀번호와 JWT 키는 `SecureRandom`으로 무작위 생성한다(JWT 키는 32바이트).
- `.env`가 이미 있으면 덮어쓰지 않고 그냥 끝난다. 남의 비밀값을 실수로 지우지 않으려는 설계다.
- 새로 만들 때는 POSIX(맥·리눅스)면 본인만 읽을 수 있는 권한(0600)으로 만든다. Windows는 폴더 권한을 따른다.

### 왜 "다른 이름으로 바꾸라"고 했나
지우지 않고 이름만 바꾸면(예: `.env.old`) 옛 값이 필요할 때 다시 볼 수 있다. `.env.*`도 `.gitignore`에 있어 커밋되지 않는다.

### 함께 지워야 하는 것: Docker 볼륨
PostgreSQL은 볼륨을 **처음 만들 때만** `.env`의 비밀번호로 계정을 만든다. `.env`를 새로 만들면 비밀번호가 바뀌지만, 기존 볼륨은 옛 비밀번호를 가진 채 남는다. 그래서 서비스가 DB 인증에 실패한다. 테스트 데이터만 있는 로컬 볼륨이라면 지우고 다시 띄우면 된다.

```bash
docker volume ls -q --filter name=iseonjwa | xargs docker volume rm
```

## 이 프로젝트에서는
- 생성 코드: `workspace-tools/src/main/java/com/iseonjwa/workspace/LocalEnvironment.java`
- 키 목록: `.env.example`
- 2026-10-27 강민 PC에서 옛 `.env`를 바꾸고 `initLocalEnv` → 볼륨 재생성 → compose 기동 순서로 가입·로그인·내 정보까지 확인했다.
- `compose.yml`의 오류 문구와 `.env.example` 첫 줄이 옛 스크립트(`script/init-local-env.py`)를 안내하고 있었다. 이 스크립트는 ADR 0012로 `./gradlew initLocalEnv`로 바뀌어 같은 날 문구를 고쳤다.

## 면접 포인트
- "비밀값은 어떻게 관리했나?": 저장소에는 키 이름만 있는 `.env.example`을 두고, 실제 값은 각자 PC에서 무작위로 생성해 `.env`에 둔다. compose는 `:?`로 값이 빠지면 기동을 막는다.
- "왜 자동 생성인가?": 팀원마다 같은 약한 비밀번호를 쓰거나 실수로 커밋하는 일을 막고, 처음 받는 사람도 명령 하나로 실행 환경을 만들 수 있게 하려고.

---
title: .env를 지우고 다시 만들면 DB 비밀번호는 어떻게 되고, 로컬 데이터가 필요 없으면 괜찮은가
date: 2026-11-01
updated: 2026-11-01
asker: GGangMY
level: 초급
tags: [docker, postgres, env, local-environment]
---

# .env를 지우고 다시 만들면 DB 비밀번호는 어떻게 되고, 로컬 데이터가 필요 없으면 괜찮은가

## 질문
`.env`를 지우고 `./gradlew initLocalEnv`로 다시 만들면 DB 비밀번호가 바뀐다고, AI가 `.env`가 이미 있으면 그렇게 하지 말라고 했다. 로컬 DB 데이터가 필요 없으면 상관없는가.

## 짧은 답
데이터가 필요 없어도 `.env`만 다시 만들면 DB에 접속할 수 없게 된다. **기존 DB 볼륨까지 같이 지워야** 새 비밀번호로 동작한다.

## 왜 그런가
- `initLocalEnv`(`workspace-tools/.../LocalEnvironment.java`)는 `.env`가 없을 때만 만든다. 이때 `DB_PASSWORD`, JWT·결제 토큰 비밀값, 관리자·Grafana 비밀번호를 모두 `SecureRandom`으로 새로 뽑는다.
- `postgres` 이미지의 `POSTGRES_PASSWORD`는 **데이터 디렉터리가 비어 있을 때(첫 초기화) 한 번만** 쓰인다. 이후 비밀번호는 볼륨 안의 DB에 저장돼 있다.
- `compose.yml`의 DB는 이름 있는 볼륨(`postgres-user`·`postgres-reservation`·`postgres-payment`)을 쓴다. 그래서 컨테이너를 다시 만들어도 옛 비밀번호가 남는다.
- 결과: 앱은 새 `.env`의 비밀번호로 접속하고, DB는 옛 비밀번호를 기억한다. 그래서 `password authentication failed`가 난다. Grafana도 같은 이유로 새 관리자 비밀번호가 먹지 않는다.

## 선택지
| 방법 | 언제 | 감수할 점 |
|---|---|---|
| `.env`를 그대로 두고 빠진 키만 직접 추가 | 기본. 문서(`workspace.md`)가 권하는 방법 | 손으로 넣어야 한다 |
| `.env` 재생성 + 볼륨 삭제(`docker compose -f compose.yml -f compose.local.yml down -v`) | 로컬 데이터가 정말 필요 없을 때 | DB·Redis·Kafka·Prometheus·Grafana 데이터가 모두 사라진다. 이미 발급된 토큰도 모두 무효가 된다 |

- `down -v`는 이 프로젝트 Compose의 볼륨만 지운다. 그래도 지우기 전에 남길 데이터가 없는지 확인한다.
- 테스트(`compose.test.yml`)는 상관없다. 고정 비밀번호 `test-only-password`와 `tmpfs`를 써서 `.env`를 읽지 않는다.

## 관련
- `docs/guides/workspace.md` "전체 Compose 실행"

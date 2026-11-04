---
title: 배포·알림용 슬랙 채널을 만든 뒤 무엇을 설정해야 하나
date: 2026-11-04
updated: 2026-11-04
asker: Junhyun An
level: 기초
tags: [slack, incoming-webhook, alertmanager, jenkins, secret-management, observability]
---

# 배포·알림용 슬랙 채널을 만든 뒤 무엇을 설정해야 하나

## 질문
팀 워크스페이스(디스이스스파르타)에 `#이선좌-배포상태알림`, `#이선좌-성능-모니터링` 채널을 만들었다. 무엇을 설정해야 하나. ADR 0044(Jenkins 배포 결과)·0045(Alertmanager 알림)를 구현하기 전에 물었다.

## 짧은 답
슬랙 앱 하나에 Incoming Webhook을 채널마다 하나씩, 모두 두 개 만든다. 나온 주소 두 개는 비밀값으로 보관한다. 봇 토큰, OAuth 권한 범위, Jenkins Slack 플러그인은 필요 없다.

| 채널 | 보내는 쪽 | 주소 보관 위치 |
|---|---|---|
| `#이선좌-배포상태알림` | Jenkins 배포 결과(ISE-175) | Jenkins Credentials. 로컬 확인용으로 `.env`에도 둔다 |
| `#이선좌-성능-모니터링` | Alertmanager 알림(ISE-179) | 배포 서버와 로컬 `.env` |

## 자세히

### 1. Incoming Webhook이란
- 슬랙이 발급하는 주소(`https://hooks.slack.com/services/...`)로 JSON(`{"text": "..."}`)을 POST하면 정해진 채널에 메시지가 올라간다.
- 주소 하나는 채널 하나에 묶인다. 보내는 쪽에서 채널을 바꿀 수 없다. 그래서 채널이 둘이면 주소도 둘이다.
- 인증은 주소 자체다. 주소를 아는 사람은 누구나 그 채널에 글을 쓸 수 있으므로 비밀번호처럼 다룬다. 슬랙은 공개 저장소에 올라간 주소를 찾아 무효로 만든다.

### 2. 만드는 순서
1. https://api.slack.com/apps 에서 **Create New App**을 누르고 템플릿 없는 빈 앱(화면에 따라 **From scratch** 또는 **Blank app**)을 고른다. AI 에이전트·워크플로·매니페스트 같은 다른 선택지는 고르지 않는다. 이름은 예를 들어 `이선좌 알림`, 워크스페이스는 디스이스스파르타다.
2. 앱 설정의 **Incoming Webhooks**에서 **Activate Incoming Webhooks**를 켠다. 앱을 만든 뒤 나오는 기능 목록(Incoming Webhooks, Interactive Components, Slash Commands, Event Subscriptions, Bots, Permissions)에서도 Incoming Webhooks 하나만 고른다.
3. **Add New Webhook to Workspace**를 누르고 `#이선좌-배포상태알림`을 골라 허용한다. 비공개 채널이면 만드는 사람이 그 채널 멤버여야 한다.
4. 같은 버튼을 한 번 더 눌러 `#이선좌-성능-모니터링`용 주소를 만든다.
5. 앱 이름과 아이콘이 메시지 보낸 이의 이름으로 보인다. 필요하면 **Basic Information → Display Information**에서 바꾼다.

### 3. 워크스페이스가 앱 설치를 막아 둔 경우
- 운영진이 관리하는 워크스페이스는 앱 설치에 관리자 승인을 요구하기도 한다. 3단계에서 "설치 요청"이 뜨면 관리자 승인을 받아야 한다.
- 승인이 늦거나 막히면 팀 전용 무료 워크스페이스를 따로 만드는 방법이 있다. 다만 ADR 0045는 팀 워크스페이스를 전제로 했으므로 바꾸면 기록을 고친다.

### 4. 주소 보관
- 저장소, 채팅, 이슈, PR 본문에 붙이지 않는다(대전제 8).
- 로컬은 `.env`에 둔다. 키 이름은 구현 PR(ISE-179)에서 `.env.example`에 빈 값으로 더하며, 지금은 `SLACK_WEBHOOK_DEPLOY`, `SLACK_WEBHOOK_MONITORING`을 쓴다 `[가정]`.
- Jenkins는 배포용 주소를 Credentials의 "Secret text"로 두고 파이프라인에서 꺼내 쓴다.
- 새어 나가면 앱 설정의 Incoming Webhooks 목록에서 그 주소를 지우고 새로 만든 뒤 보관 위치를 바꾼다.

### 5. 연결 확인
```bash
set -a; . ./.env; set +a
curl -sS -X POST -H 'Content-type: application/json' \
  --data '{"text":"연결 확인"}' "$SLACK_WEBHOOK_DEPLOY"
```
- 성공하면 응답 본문이 `ok`이고 채널에 메시지가 뜬다. 명령 기록에 주소가 남지 않게 변수로 넘긴다.

### 6. 받는 쪽이 이 주소를 쓰는 방법
- **Alertmanager**: `slack_configs`의 `api_url`이나 `api_url_file`에 넣는다. 설정 파일은 환경 변수를 풀어 주지 않으므로, 주소를 파일로 만들어 `api_url_file`로 읽게 하는 방식이 비밀값을 설정 파일 밖에 두기 좋다. 이 프로젝트는 compose `configs`의 `content`로 `.env` 값을 파일로 만들어 마운트하고, 값이 없으면 보내지 않는 자리표시 주소를 넣는다(compose `secrets`의 `environment` 방식은 변수가 없으면 compose 전체가 실패한다). `send_resolved`는 슬랙에서 기본값이 `false`라 해결 알림을 받으려면 `true`로 켠다(ADR 0045).
- **Jenkins**: 플러그인 없이 파이프라인의 `curl` 한 줄로 보낸다. 주소는 `withCredentials`로 꺼낸다.

### 7. 봇 토큰과 앱 토큰은 필요 없다
- **봇 토큰**(`xoxb-`): 봇 이름으로 `chat.postMessage` 같은 Web API를 부를 때 쓴다. 토큰 하나로 여러 채널에 보내기, 스레드 답글, 보낸 메시지 고치기가 된다. 웹훅은 채널마다 주소가 따로 있고 보내기만 되지만, 지금 쓰임새(배포 결과 한 줄, 알림 한 건)에는 이것으로 충분하다.
- **앱 토큰**(`xapp-`): 앱 전체 단위 토큰으로, 주로 Socket Mode(공개 주소 없이 슬랙 이벤트를 받는 연결)에 쓴다. 슬랙에서 오는 이벤트를 받지 않으므로 필요 없다.
- 나중에 "배포 시작 메시지에 결과를 스레드로 달기"처럼 메시지를 잇고 싶어지면 봇 토큰으로 바꾼다. Alertmanager도 `app_token`(봇 토큰)과 `chat.postMessage` 주소를 쓰는 방식을 지원한다.

## 이 프로젝트에서는
- 2026-11-04 사용자가 앱 하나에 두 채널의 웹훅을 발급해 로컬 `.env`에 `SLACK_WEBHOOK_DEPLOY`, `SLACK_WEBHOOK_MONITORING`으로 넣었다. 두 주소로 연결 확인 메시지를 보내 둘 다 `200 ok`를 받았다(주소는 출력하지 않았다).
- 처음에 배포 채널이 웹훅 채널 목록에 안 보였다가 다시 시도해 잡혔다. 원인은 확인하지 못했다. 채널을 만들기 전에 열어 둔 설정 화면이었을 가능성이 있다.
- 채널 이름은 설계 문서에서 `#deploy`, `#alerts`로 가정했던 것을 2026-11-04 사용자가 만든 이름으로 바꾼다. `docs/design/infra/deployment.md`·`observability.md`와 ADR 0044·0045의 `[가정]`은 ISE-175·179 구현 PR에서 고친다.
- `#이선좌-성능-모니터링`에는 성능 알림뿐 아니라 결제 정합성 알림(대사 불일치, 미발행 이벤트 등 ADR 0024·0029 규칙)도 간다. Alertmanager에서 채널을 나누지 않기 때문이다.

## 면접 포인트
- 알림 경로의 비밀값 관리: 웹훅 주소는 그 자체가 자격 증명이라 저장소가 아닌 `.env`·CI 자격 증명 저장소에 두고, 새면 폐기하고 다시 발급한다.
- 알림을 보내는 쪽(Prometheus 규칙 평가)과 전달하는 쪽(Alertmanager의 묶기·반복·해결 알림)을 나눈 이유: 같은 장애로 알림이 쏟아지는 것을 묶고, 풀렸다는 사실도 알린다.

## 참고
- [Sending messages using incoming webhooks (Slack)](https://docs.slack.dev/messaging/sending-messages-using-incoming-webhooks/)
- [Quickstart app settings (Slack)](https://docs.slack.dev/app-management/quickstart-app-settings/)
- [Alertmanager configuration, slack_config](https://prometheus.io/docs/alerting/latest/configuration/#slack_config)
- 위 문서는 2026-11-04에 확인했다. 앱 설치 관리자 승인은 슬랙 문서에서 확인하지 못했고 일반적인 워크스페이스 설정을 적었다.

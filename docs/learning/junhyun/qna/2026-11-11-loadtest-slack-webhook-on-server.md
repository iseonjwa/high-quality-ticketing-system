---
title: 측정 모드 알림용 슬랙 웹훅을 배포 서버에 넣는 법
date: 2026-11-11
updated: 2026-11-11
asker: Junhyun An
level: 입문
tags: [slack, incoming-webhook, env, secret, deploy, loadtest]
---

# 측정 모드 알림용 슬랙 웹훅을 배포 서버에 넣는 법

## 질문
"서버에 웹훅 뭐 넣어야 해?" ISE-224에서 강민 님이 측정 전용 채널의 웹훅을 서버 `.env`에 넣어 달라고 요청한 일이다.

## 짧은 답
- **11/11 확인: 이미 들어가 있다.** 서버 `.env`의 `SLACK_WEBHOOK_LOADTEST`가 채워져 있고(슬랙 웹훅 형식, 성능-모니터링 웹훅과 다른 값, 파일 권한 600), ISE-224도 Done이다. 아래 절차는 웹훅을 바꾸거나 새로 만들 때 쓴다.
- 넣을 것: 슬랙 측정 전용 채널 `#이선좌-부하측정`의 **Incoming Webhook 주소**(`https://hooks.slack.com/services/...`)다.
- 넣을 곳: 배포 서버 `~/iseonjwa/.env`의 `SLACK_WEBHOOK_LOADTEST=` 한 줄이다.
- 컨테이너를 다시 띄울 필요는 없다. 이 값은 `script/loadtest/measure-mode.sh`만 쓰고, 스크립트가 실행될 때마다 `.env`에서 그 키만 읽는다.
- 비워 두면 성능-모니터링 채널(`SLACK_WEBHOOK_MONITORING`)로 간다. 그래서 지금도 알림이 아예 안 가는 것은 아니다.

## 바탕 개념

### Incoming Webhook
슬랙 앱에 "이 채널에 글을 올릴 수 있는 주소"를 하나 발급받는 기능이다. 그 주소로 JSON을 POST하면 채널에 메시지가 올라간다. 주소 자체가 권한이라 비밀번호처럼 다룬다. 주소를 아는 사람은 누구나 그 채널에 글을 쓸 수 있으므로 커밋하거나 출력하지 않는다. 이 프로젝트는 봇 토큰 없이 Incoming Webhook만 쓴다(`docs/design/infra/observability.md`).

### 채널마다 주소가 다르다
웹훅 하나는 채널 하나에 묶인다. 측정 알림을 다른 채널로 보내려면 그 채널용 웹훅을 새로 발급받아야 한다. 같은 앱 안에 채널별로 여러 개를 둘 수 있다.

### 왜 다시 띄울 필요가 없나
Alertmanager의 `SLACK_WEBHOOK_MONITORING`은 컨테이너가 만들어질 때 파일로 읽혀서, 바꾸면 컨테이너를 다시 만들어야 한다. `SLACK_WEBHOOK_LOADTEST`는 셸 스크립트가 실행될 때마다 `.env`에서 읽으므로 넣는 즉시 다음 실행부터 쓰인다(`webhook_from()`).

## 이 프로젝트에서는

1. **웹훅 발급**: 기존 성능-모니터링 웹훅을 발급한 슬랙 앱 설정(api.slack.com/apps) → Incoming Webhooks → Add New Webhook to Workspace → `#이선좌-부하측정` 선택 → 주소 복사. 비공개 채널이면 내가 그 채널에 들어가 있어야 고를 수 있다.
2. **서버에 넣기**: 값이 화면과 셸 기록에 남지 않게 입력받아 넣는다.
   ```bash
   ssh -i ~/.ssh/id_ed25519 ubuntu@52.63.10.174
   cd ~/iseonjwa
   grep -c '^SLACK_WEBHOOK_LOADTEST=' .env   # 키가 이미 있는지(값은 출력하지 않는다)
   read -rsp 'webhook: ' W; echo
   if grep -q '^SLACK_WEBHOOK_LOADTEST=' .env; then
     sed -i "s|^SLACK_WEBHOOK_LOADTEST=.*|SLACK_WEBHOOK_LOADTEST=${W}|" .env
   else
     printf 'SLACK_WEBHOOK_LOADTEST=%s\n' "$W" >> .env
   fi
   unset W; chmod 600 .env
   ```
3. **확인**: 측정 모드를 켜지 않고 시험 메시지 한 건만 보낸다. `ok`가 나오면 된다.
   ```bash
   curl -fsS -H 'Content-Type: application/json' \
     --data '{"text":"[시험] 측정 모드 알림 웹훅 연결 확인(ISE-224)"}' \
     "$(sed -n 's/^SLACK_WEBHOOK_LOADTEST=//p' .env)"; echo
   ```
4. ISE-224에 넣었다고 남긴다. 다음 `measure-mode.sh on/off`부터 알림이 `#이선좌-부하측정`으로 가고, 출력에 `슬랙 알림을 보냈다(SLACK_WEBHOOK_LOADTEST)`가 찍힌다.

## 면접 포인트
- **웹훅 주소를 어떻게 관리했나**: 주소가 곧 권한이라 저장소에 두지 않고 서버 `.env`(권한 600)에만 둔다. 스크립트는 키 이름만 출력하고 주소는 찍지 않는다. 배포 알림용은 Jenkins Credentials에 둔다.
- **설정을 읽는 시점**: 컨테이너가 시작할 때 읽는 값은 바꾸면 다시 만들어야 하고, 스크립트가 실행할 때 읽는 값은 바로 반영된다. 바꾼 뒤 무엇을 다시 띄워야 하는지는 그 값을 누가 언제 읽는지로 정한다.

## 참고
- `script/loadtest/measure-mode.sh`의 `webhook_from()`·`notify()`(LOADTEST → MONITORING 순서), `.env.example`, `docs/guides/loadgen.md` 4·5절, Linear ISE-224

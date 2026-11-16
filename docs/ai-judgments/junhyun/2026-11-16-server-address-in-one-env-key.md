---
title: HTTPS 전환에서 파일마다 박힌 서버 주소를 https로 하나씩 고치려던 방향을 거부하고, 주소를 서버 .env의 PUBLIC_URL 한 곳에서 읽게 함
date: 2026-11-16
updated: 2026-11-16
judge: junhyun
ai: Claude Code
verdict: 거부
area: 기타
presentation: 아님
shared: 예
---

# HTTPS 전환에서 파일마다 박힌 서버 주소를 https로 하나씩 고치려던 방향을 거부하고, 주소를 서버 .env의 PUBLIC_URL 한 곳에서 읽게 함

## 맥락
- 해결하려던 작업: 배포 서버 HTTPS(ISE-278) 두 번째 PR. https로 바꾸면 깨질 곳(하드코딩된 http 주소, 토스 리다이렉트, Grafana 링크)을 찾던 중이었다.
- 찾은 것: 공인 IP `52.63.10.174`가 슬랙 알림 템플릿(`infra/alertmanager/templates/slack.tmpl`, 배포 Grafana 링크)에 박혀 있었고, AI는 새로 넣을 Grafana `root_url`과 nginx 인증서 경로에도 같은 주소를 적으려 했다.
- AI 활용 방식: Claude Code가 저장소를 검색해 주소가 박힌 곳을 찾았다.

## AI 제안
박힌 주소를 https 주소로 하나씩 고치는 방향이었다(파일마다 주소를 그대로 둠). 사용자 지시 뒤에는 아래처럼 바꿨다.
- 공개 주소는 서버 `.env`의 `PUBLIC_URL`(예: `https://<서버 IP>`) 한 값만 둔다. 배포 compose가 이 값으로 Grafana `root_url`을 만들고, 알림 템플릿은 compose `configs`가 이 값으로 만든 작은 템플릿(`iseonjwa.grafana_url`)을 읽는다(슬랙 웹훅 주소를 넘기는 방식과 같다).
- nginx 설정에는 주소를 두지 않는다. http → https 넘김은 요청의 Host를 쓰고, 인증서는 `certbot --cert-name iseonjwa`로 이름을 붙여 `/etc/letsencrypt/live/iseonjwa/`에서 찾는다.
- 프론트는 화면 주소(`window.location.origin`)를 쓰므로 주소가 없다.

## 판단
- 결과: 거부
- 판단자: junhyun
- 근거: "그거 전부 전역설정으로 옮겨줘 그건 기본이지". 무엇을 옮길지 선택지로 다시 묻자 "하드코딩된 서버 주소"를 골랐다.
- 검토한 대안: nginx 공통 지시어를 http 단계로 옮기기, Claude Code 전역 설정으로 옮기기(대상이 아니었다)

## 반영
ISE-278 두 번째 PR(`compose.yml`·`compose.deploy.yml`·`infra/alertmanager/`·`frontend/nginx/deploy.conf`·`.env.example`·`script/deploy/init-env.sh`). 다른 기계의 주소는 이 PR 범위 밖이다: Jenkins는 이미 `/opt/jenkins/jenkins.env`의 `PUBLIC_URL`을 읽고, 발생기 측정 스크립트 `loadtest/k6/tools/g1-run.sh`의 사설 IP 기본값은 측정 중인 팀원 도구라 따로 알린다.

## 검증
구현 전.

## 판단 이력
- 2026-11-16 거부: 서버 주소는 파일마다 적지 않고 서버 `.env`의 `PUBLIC_URL` 한 곳에서 읽는다.

## 발표·면접 포인트
- 서버가 바뀌면 `.env` 한 줄만 고치면 되게 했다. 설정 파일이 환경 변수를 못 읽는 곳(Alertmanager 템플릿, nginx)은 compose `configs`로 값을 파일로 만들어 넘기거나, 요청의 Host와 인증서 이름을 써서 주소를 아예 없앴다.

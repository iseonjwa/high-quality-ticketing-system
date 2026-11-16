---
title: 시연과 외부 심사 채점 때 심사위원이 Grafana를 직접 보도록, Grafana만 HTTPS 경로(/grafana/)로 누구나 보기 전용으로 열기로 함
date: 2026-11-16
updated: 2026-11-16
judge: junhyun
ai: Claude Code
verdict: 수용
area: 기타
presentation: 아님
shared: 예
---

# 시연과 외부 심사 채점 때 심사위원이 Grafana를 직접 보도록, Grafana만 HTTPS 경로(/grafana/)로 누구나 보기 전용으로 열기로 함

## 맥락
- 해결하려던 작업: 배포 서버에 HTTPS를 붙이는 중(ISE-278)에 사용자가 "참고로 시연을 하고 외부 심사위원분들이 채점도 해야해서, grafana랑 이런것도 보였으면 좋겠어"라고 했다.
- 지금 상태: Grafana는 3000을 호스트 모든 주소에 열고 보안 그룹이 팀원 IP만 허용한다. 익명은 보기(Viewer)만 된다(2026-11-05 결정, `docs/design/infra/deployment.md` 3절). Kafka-UI·Prometheus·Zipkin은 `127.0.0.1`에 묶여 SSH 터널로만 본다. 심사위원은 어느 것도 직접 열 수 없다.
- AI 활용 방식: 공개 방법과 공개 범위를 선택지로 받았다.

## AI 제안
- 공개 방법: `https://52.63.10.174/grafana/`로 프론트 nginx가 Grafana를 중계하고 누구나 보기만 되게 한다(추천). 새 포트를 열지 않고 HTTPS 인증서를 같이 쓰며, 시연 화면의 traceId 링크도 같은 주소가 되어 https 화면에서 깨지지 않는다. 대시보드와 로그를 누구나 볼 수 있으므로 공개 전에 로그에 비밀값·개인정보가 없는지 확인하고, 로그인 화면이 노출되는 점(Grafana 자체 로그인 시도 제한, 관리자 비밀번호는 서버 `.env`)을 알렸다.
- 대안: 3000을 모두에게 열기(보안 그룹만 바꾸면 되지만 http 평문이라 관리자 로그인이 암호화되지 않고, https 화면에서 http Grafana로 넘어가는 링크가 남음), 지금 그대로(시연 때 발표자 화면으로만).
- 공개 범위: Grafana만(추천). Grafana에 Prometheus·Loki·Zipkin 데이터 소스가 있어 지표·로그·트레이스를 한 곳에서 본다. Kafka UI는 읽기 전용이어도 메시지 본문이 그대로 보이고 로그인이 없어 공개를 권하지 않았다. 나머지는 시연 때 발표자 화면(SSH 터널)으로 보여 준다.

## 판단
- 결과: 수용
- 판단자: junhyun
- 근거: 시연과 외부 심사위원 채점 때 Grafana 같은 관측 화면이 보여야 한다. 선택지에서 "https 경로로 공개"와 "Grafana만"을 골랐다.
- 검토한 대안: 3000 전체 공개, 지금 그대로, Grafana + Zipkin, Grafana + Kafka UI

## 반영
미반영. ISE-278 두 번째 PR에서 반영한다. 배포 nginx 443에 `/grafana/` 중계, Grafana 하위 경로 설정(배포만), 프론트 Grafana 링크, `deployment.md` 3절 표와 "관리 화면 접근" 행(2026-11-05 결정을 이 기록이 대체), 한계 표(ISE-276).

## 검증
구현 전.

## 판단 이력
- 2026-11-16 수용: Grafana만 `https://<서버 IP>/grafana/`로 보기 전용 공개, 나머지 관리 화면은 SSH 터널 유지.

## 발표·면접 포인트
- 공개 범위를 데이터 소스가 모인 Grafana 하나로 줄이고, 새 포트 대신 이미 TLS가 붙은 진입점의 하위 경로로 열었다. 메시지 본문이 보이는 Kafka UI는 공개하지 않았다.
- 익명 보기 공개의 한계(대시보드·로그를 누구나 본다)를 알고 공개 전에 로그 내용을 점검했다고 말할 수 있어야 한다.

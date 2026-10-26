---
title: Config 서버: AI의 "Config 서버 없이 .env + 프로필" 추천 대신 Config 서버를 두기로 했다
date: 2026-10-26
updated: 2026-10-26
judge: GGangMY
ai: Claude Code (10/24 결정 기록 추천), codex + Claude 교차 검토 (10/23 1차 AI SA 피드백)
verdict: 거부
area: 범위·일정
presentation: 아님
shared: 예
---

# Config 서버: AI의 "Config 서버 없이 .env + 프로필" 추천 대신 Config 서버를 두기로 했다

## 맥락
- 해결하려던 작업: 결정 기록 #30 기술 스택·Spring Cloud 구성을 Java 21 기준으로 정리하던 중이었다.
- AI 활용 방식: "config 서버 있는 게 낫겠음, 수정 반영해서 새로 뽑아 줘"라고 요청했다.

## AI 제안
- 10/23 1차 AI SA 피드백(교차 검토): Config 서버는 발제상 "(선택)"이고, 로컬에서 JVM이 늘어 부하 측정이 오염될 수 있어 과잉이라고 지적했다.
- 10/24 결정 기록 추천: 설정은 `.env` + 프로필로 충분해 Config 서버는 뺀다. 서비스 3개엔 과하고 기동 순서 문제가 늘어난다는 이유였다.
- 10/26 Config 서버를 두기로 한 뒤의 구성 제안: native 백엔드(모노레포 `config-repo/` 마운트), 고정 주소 연결 + `fail-fast`·재시도, 비밀값은 `.env`에 두고 설정 파일엔 자리표시자만, 갱신은 재시작 기본(`@RefreshScope`는 자주 바꾸는 값만, Spring Cloud Bus 안 씀), 기동 순서 config-server → eureka → 서비스, 포트 8888 내부 전용, 컨테이너 512m.

## 판단
- 결과: 거부 (Config 서버 없음 추천을 받지 않음). 두기로 한 뒤의 구성 제안은 수정안에 반영했다.
- 판단자: GGangMY
- 근거: 설정 파일을 통합하자는 의견 ("설정 파일 통합하자는 의견임")
- 검토한 대안: `.env` + 프로필만 (10/24 추천)

## 반영
- 노션 결정 기록 #30·#57은 아직 반영 전이다(본문 교체안 준비됨).
- SA 사본 [`docs/core/SA MASTER.md`](../../core/SA%20MASTER.md)에 9-6 Config 서버 절을 만들고 7-1·7-2·9-1~9-3·6-2·13·15·17장·부록 A에 반영. `docs/core/milestones.md` M1 범위에도 추가.

## 검증
구현 전. M1에서 config-server가 먼저 뜨고, 서비스가 `config-repo`의 프로필별 값을 읽는지 확인한다.

## 판단 이력
- 2026-10-26 거부: Config 서버 없음 추천 대신 Config 서버를 두기로 함
- 2026-10-26 근거 보충: 설정 파일 통합

## 발표·면접 포인트
- Config 서버는 발제상 선택이고 AI도 과잉이라고 봤지만, 측정하며 바꿀 숫자 기준과 프로필별 설정을 한 곳에서 관리하려고 두었다. 대가인 기동 순서 문제는 healthcheck·fail-fast·재시도로, 비밀값 노출은 자리표시자 + .env로 막았다.

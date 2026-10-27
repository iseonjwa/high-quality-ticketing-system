---
title: 인증 구현 보강 4개 수용과 항목별 커밋
date: 2026-10-27
updated: 2026-10-27
judge: Junhyun An
ai: Codex
verdict: 수용
area: 기타
presentation: 아님
shared: 예
---

# 인증 구현 보강 4개 수용과 항목별 커밋

## 맥락

- 해결하려던 작업: M1 인증 구현의 동시성·상태 검사·기동 준비·설정 오류를 보강한다.
- AI 활용 방식: 현재 구현을 검토하고 보강할 부분을 추천해 달라고 요청했다.

## AI 제안

로그인과 비밀번호 초기화의 경합, 잠금 계정의 Refresh 정책, Gateway Redis 연결 준비, 잘못된 인증 설정의 기동 시 거절을 제안했다.
검토와 초기 진단은 [학습 노트](../../learning/junhyun/qna/2026-10-27-implementation-hardening-review.md)에 있다.

## 판단

- 결과: 수용. 4개 항목을 보강하고 각각 커밋한다.
- 판단자: Junhyun An
- 사용자 원문: "해당 사항들 다 보강하고, 각각을 보강할때 커밋해줘"
- 근거: 미기재. 사용자는 보강 범위와 커밋 단위를 지정했다.
- 구현 선택: 사용자 행 잠금과 최신 해시 재확인으로 발급·폐기를 직렬화한다. 잠금과 비밀번호 변경 필요 상태는 Refresh 재발급도 거절하는 방향으로 보강한다. 세부 구현 선택과 사용자 원문을 구분한다.

## 반영과 검증

| 항목 | 상태 | 검증 |
|---|---|---|
| 로그인·비밀번호 초기화 경합 | 구현 | PostgreSQL·Redis·HTTP 통합 테스트 18개 통과, 새 경합 2개는 수정 전 실패 확인 |
| 잠금 상태의 Refresh 거절 | 구현 | 잠금·변경 필요 상태 거절과 세션 미회전, 폐기 토큰 401 우선 처리 포함 통합 테스트 20개 통과 |
| Gateway Redis 연결 준비 | 구현 | Gateway 테스트 42개(예열 5개 포함), 실제 컨테이너 재기동 후 첫 보호 요청의 User 도달 확인 |
| 인증 설정 기동 검증 | 구현 | 잘못된 설정 6개는 수정 전 실패 확인. 기동 컨텍스트 8개와 인증 통합 20개 통과 |

[ADR 0010](../../decisions/0010-auth-session-serialization.md)에 동시성 제어의 범위와 비용을 남겼다. SA의 Gateway 인증·헤더 전달·HS256과 기존 Access의 만료 정책은 유지한다.

최종 검증: Java 21에서 `bash script/check.sh`가 통과했다. JUnit 105개·Python 14개, 포트 공개 검사, 전체 빌드, Config 불가 시 기동 실패, 기록·diff 검사를 확인했다.
`python3 script/smoke-workspace.py --redis-failure --cold-start`도 통과했다. 실제 스택 HTTP 계약 55건과 Config·Eureka·Kafka·Prometheus·Grafana·Zipkin, 재기동 첫 인증 요청, Redis 장애·복구를 확인했다.
운영 배포와 부하 용량은 검증 범위 밖이다.

위 명령과 건수는 보강 당시의 기록이다. 이후 [검증 경계 변경](../../decisions/0011-junit-verification-boundary.md)으로 같은 앱·시스템 검증을 내부 JUnit으로 옮겼다. 현행 실행 명령은 [테스트 가이드](../../guides/testing.md)를 따른다.

## 판단 이력

- 2026-10-27 수용: 보강 4개와 항목별 커밋을 요청했다.

## 발표·면접 포인트

- 단위 진단에서 찾은 경합을 실제 DB·Redis·HTTP 회귀 테스트로 재현하고 수정한다.
- BCrypt는 잠금 밖에 두고 발급·폐기 경계만 직렬화해 정확성과 자원 점유를 함께 고려한다.

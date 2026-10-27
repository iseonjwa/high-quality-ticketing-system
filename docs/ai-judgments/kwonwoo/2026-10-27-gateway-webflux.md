---
title: "Gateway 스택: 현재 WebMVC 대신 SA 전제와 AI 추천대로 WebFlux로 전환"
date: 2026-10-27
updated: 2026-10-27
judge: kwonwoo
ai: Claude Code (SA 문서와 현재 코드 비교 후 선택지 제시)
verdict: 수용
area: 기타
presentation: 후보
shared: 예
---

# Gateway 스택: 현재 WebMVC 대신 SA 전제와 AI 추천대로 WebFlux로 전환

## 맥락
- 해결하려던 작업: SA 문서를 기준으로 Gateway·Eureka·인증 기본 인프라를 세팅하던 중이었다. 문서와 기존 구현이 다른 부분은 트레이드오프를 함께 정리하기로 했다.
- AI 활용 방식: AI에게 문서와 현재 코드를 비교하게 했다. AI는 갈리는 지점을 선택지로 제시했다.

## AI 제안
SA 11-2는 "Gateway가 응답하는 401·403·429도 WebFlux 전용 처리기로 실패 형태로 만든다"고 적어 WebFlux를 전제한다. 현재 코드는 `spring-cloud-starter-gateway-server-webmvc`다. AI는 WebFlux 전환을 추천했다.
- 근거: 문서 전제와 맞는다. 이벤트 루프 구조라 적은 스레드로 많은 연결을 버텨 티켓 오픈 폭주에 유리하다. Spring Cloud Gateway 자료가 대부분 WebFlux 기준이다.
- 대가: 필터와 Redis 호출을 리액티브(`Mono`)로 짜야 하고 디버깅이 어렵다.
- WebMVC 유지안의 대가: 요청마다 스레드를 잡아 폭주 때 스레드 풀이 병목이 된다. 가상 스레드로 보완할 수는 있지만, SA는 Java 21 고정(pinning) 문제로 가상 스레드를 기본으로 끈다.

## 판단
- 결과: 수용 (선택지 중 WebFlux 전환 선택)
- 판단자: kwonwoo
- 근거: 미기재
- 검토한 대안: WebMVC 유지

## 반영
`gateway-edge/build.gradle`을 `spring-cloud-starter-gateway-server-webflux`로 바꾸고 라우트 설정 접두사를 `spring.cloud.gateway.server.webflux`로 옮겼다.

## 검증
Gateway가 Netty(WebFlux)로 기동하고 Eureka 등록·`lb://` 라우팅이 동작함을 실행으로 확인했다(대상 서비스 없음 503, 미정의 경로 404).

## 판단 이력
- 2026-10-27 수용: Gateway를 WebFlux로 전환하기로 함

## 발표·면접 포인트
- 티켓 오픈처럼 연결이 몰리는 진입점은 요청당 스레드 모델보다 이벤트 루프 모델이 유리해 Gateway만 WebFlux로 두고, 서비스는 MVC를 유지했다. 그 결과 공통 모듈을 웹 스택 기준으로 나눠야 했다.

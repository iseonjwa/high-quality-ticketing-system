---
title: 기술 스택: AI가 추천한 Java 25 대신 Java 21로 바꾸고 Boot 4.1·Spring Cloud 2025.1은 유지했다 (팀 합의 전)
date: 2026-10-26
updated: 2026-10-26
judge: junhyun (Java 21 결정) · GGangMY (스택 조정 요청)
ai: Claude Code (10/24 결정 기록 추천, 10/26 호환성 확인)
verdict: 부분 수용
area: 범위·일정
presentation: 아님
shared: 예
---

# 기술 스택: AI가 추천한 Java 25 대신 Java 21로 바꾸고 Boot 4.1·Spring Cloud 2025.1은 유지했다 (팀 합의 전)

## 맥락
- 해결하려던 작업: 결정 기록 #30 기술 스택이 잠정 결정(10/24, Claude 추천)으로 남아 있었다. M1 공통 모듈을 만들기 전에 확정해야 했다.
- AI 활용 방식: "Java 21로 쓸 거고 그에 맞게 다른 기술 스택들도 전부 최적화해 줘"라고 요청했다.

## AI 제안
- 10/24 추천: Java 25 LTS + Spring Boot 4.1.x + Spring Cloud 2025.1.x + Gradle 9.x. JDK 24부터 가상 스레드의 synchronized 고정 문제가 해결돼(JEP 491) 성능 개선 선택지로 쓰기 좋다는 이유였다.
- 10/26 Java 21 기준 확인(공식 문서): Spring Boot 4.1.2는 Java 17~27을 지원하고 Gradle 8.14 이상 또는 9.x를 지원한다. Spring Cloud 2025.1.x는 2025.1.2부터 Boot 4.1을 지원한다. 그래서 Boot·Cloud·Gradle은 바꾸지 않고 JDK만 21로 바꾸면 된다고 봤다.
- Java 21에 맞춘 조정: 가상 스레드는 기본으로 끈다(21에서는 synchronized 안 블로킹이 캐리어 스레드를 고정). Java 22 이후 기능은 쓰지 않는다. 컨테이너 기본 이미지는 `eclipse-temurin:21-jre`, Gradle 툴체인 21. Temurin 21은 최소 2029-12까지 지원된다.
- Boot 3.5로 낮추는 안은 짝이 되는 Spring Cloud 2025.0.x가 지원 종료라 권하지 않았다.

## 판단
- 결과: 부분 수용. AI가 추천한 Java 25는 받지 않고 Java 21로 정했다. Boot 4.1.x·Spring Cloud 2025.1.x·Gradle 9.x 유지와 Java 21에 맞춘 조정(가상 스레드 기본 끔 등)은 받았다.
- 판단자: Java 21은 junhyun(결제 담당)이 결정했다(SA MASTER v3 변경 이력, 팀 전체 합의로 표기하지 않음). GGangMY가 그 결정에 맞춰 나머지 스택 조정을 AI에 요청했다
- 근거: 미기재 ("java 21로 쓸 거고")
- 검토한 대안: Java 25 + Boot 4.1, Java 21 + Boot 3.5, Java 21 + Boot 4.0

## 반영
- 노션 결정 기록 #30은 아직 반영 전이다(본문 교체안 준비됨, 상태는 잠정 결정 유지).
- SA 사본 [`docs/core/SA MASTER.md`](../../core/SA%20MASTER.md) 9-1·15장·17-1에 반영.

## 검증
구현 전. M1 첫 빌드에서 Boot 4.1 + Spring Cloud 2025.1 + Java 21 조합으로 의존성 호환을 확인한다.

## 판단 이력
- 2026-10-26 부분 수용: Java 25 대신 Java 21, 나머지 조합은 유지
- 2026-10-26 기록 정정: SA MASTER v3 변경 이력에 따라 Java 21은 junhyun의 결정이고 팀 전체 합의가 아님을 반영

## 발표·면접 포인트
- Java 버전을 낮춰도 Spring Boot 4.1의 지원 범위(17~27) 안이라 프레임워크 조합은 그대로 유지했다. 대신 Java 21에서는 가상 스레드가 synchronized 구간에서 고정되는 한계(JDK 24의 JEP 491에서 해결)가 있어, 가상 스레드는 측정으로 필요가 확인될 때만 켜기로 했다.

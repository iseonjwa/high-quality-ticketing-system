---
title: 인바운드 어댑터와 아웃바운드 어댑터(entity·entry와 구분)
date: 2026-11-03
tags: [계층형 아키텍처, 헥사고날, 어댑터]
---

# 인바운드 어댑터와 아웃바운드 어댑터

## 질문
Kafka 소비자·스케줄러를 "입구"라고 부를 때 맞는 용어가 entity인가, entry인가?

## 답
둘 다 아니다. 표준 용어는 헥사고날 아키텍처(Ports & Adapters)의 **인바운드 어댑터**(inbound adapter)다. 같은 뜻으로 driving adapter, primary adapter라고도 한다. 반대쪽은 **아웃바운드 어댑터**(outbound adapter, driven·secondary adapter)다.

| 구분 | 방향 | 예 | 호출 관계 |
| --- | --- | --- | --- |
| 인바운드 어댑터 | 밖 → 애플리케이션 | 컨트롤러, Kafka 소비자, `@Scheduled` 스케줄러 | 애플리케이션(유스케이스)을 호출한다 |
| 아웃바운드 어댑터 | 애플리케이션 → 밖 | JPA 저장소 구현, Redis, Kafka 생산자, PG 클라이언트 | 도메인·애플리케이션이 정의한 포트(인터페이스)를 구현한다 |

- **entity**: 식별자로 구분되는 도메인 객체(JPA `@Entity`, DDD 엔티티)다. 계층 위치와 상관없다.
- **entry point**: "요청이 들어오는 지점"이라는 일반 표현이다. 틀린 말은 아니지만 어댑터의 방향을 나누는 용어는 inbound다.

## 이 프로젝트에 적용
SA 13장 표의 "infrastructure → domain 인터페이스 구현"은 아웃바운드 어댑터만 설명한다. 소비자·스케줄러는 인바운드 어댑터라 application을 호출하는 것이 정상이고, 이를 infrastructure에 둘지 presentation에 둘지가 2026-11-03 안건이다([AI 판단 기록](../../../ai-judgments/kangmin/2026-11-03-inbound-adapters-to-presentation.md)).

## 관련
- [4계층 아키텍처](2026-10-27-four-layer-architecture.md): 헥사고날과 4계층 비교

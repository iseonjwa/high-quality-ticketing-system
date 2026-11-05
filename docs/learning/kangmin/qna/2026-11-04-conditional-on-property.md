---
title: "@ConditionalOnProperty의 havingValue와 matchIfMissing은 무엇을 뜻하는가"
date: 2026-11-04
updated: 2026-11-04
asker: GGangMY
level: 기초
tags: [spring-boot, conditional, scheduler, test]
---

# @ConditionalOnProperty의 havingValue와 matchIfMissing은 무엇을 뜻하는가

## 질문
`@ConditionalOnProperty(name = "lottery.scheduler.enabled", havingValue = "true", matchIfMissing = true)`는 무슨 뜻인가.

## 짧은 답
설정값을 보고 이 클래스를 스프링 빈으로 등록할지 정한다. 값이 `true`이거나 설정이 아예 없으면 등록하고, `false`면 등록하지 않는다. 빈이 없으면 `@Scheduled` 메서드도 돌지 않는다. 운영에서는 설정 없이 켜지고, 테스트에서는 `false`로 꺼서 스케줄러가 테스트 데이터를 건드리지 못하게 한다.

## 자세히

### 설정값에 따른 결과
| `lottery.scheduler.enabled` | 빈 등록 | 이유 |
|---|---|---|
| `true` | 등록 | `havingValue`와 같다 |
| 설정 없음 | 등록 | `matchIfMissing = true` |
| `false` | 등록 안 함 | `havingValue`와 다르다 |
| 그 밖의 값(`off` 등) | 등록 안 함 | `havingValue`와 다르다 |

### 끄는 방법 비교
| 선택지 | 장점 | 대가 |
|---|---|---|
| `@ConditionalOnProperty` | 빈 자체가 없어 스케줄 등록도 없다. 설정만 바꾸면 된다 | 이 빈을 주입받는 다른 빈이 있으면 시작이 실패한다 |
| `@Profile("!test")` | 프로필로 간단히 나눈다 | 운영 중 특정 인스턴스만 끄는 식의 조절이 어렵다 |
| 메서드 안에서 `if (!enabled) return` | 빈은 그대로 있다 | 스케줄은 계속 돌고, 모든 메서드에 검사가 필요하다 |

### 주의할 점
- 조건은 애플리케이션 시작 때 평가한다. 실행 중에 설정을 바꿔도 빈이 생기거나 사라지지 않는다.
- `matchIfMissing = true`는 설정을 빠뜨려도 켜지는 쪽이다. 결제 대사처럼 빠뜨렸을 때 꺼져 있어야 안전한 기능은 이 옵션을 쓰지 않는다.

## 이 프로젝트에서는
- 추첨 스케줄러(`LotteryDrawScheduler`, `LotteryAssignmentScheduler`, `LotteryFinishScheduler`, `LotterySuccessionScheduler`)가 같은 조건을 쓴다.
- `reservation-service/src/test/resources/application-test.yml`이 `lottery.scheduler.enabled: false`로 끈다. 테스트는 서비스를 직접 불러 시각과 데이터를 정한다.
- `ReservationReconciliationConfiguration`은 `matchIfMissing` 없이 `enabled=true`일 때만 켠다.

## 면접 포인트
- 테스트에서 스케줄러를 왜 끄는가: 스케줄러가 임의 시점에 데이터를 바꾸면 결과가 실행 순서에 따라 달라진다. 끄고 서비스를 직접 부르면 시각과 순서를 테스트가 정할 수 있다.
- `matchIfMissing`은 어떻게 정하는가: 설정을 빠뜨렸을 때 켜져 있는 편이 안전한지, 꺼져 있는 편이 안전한지로 정한다.

## 참고
- 코드와 설정을 읽어 확인했다. 값별 동작을 이 프로젝트에서 따로 실행해 보지는 않았다.
- Spring Boot `@ConditionalOnProperty` API 문서

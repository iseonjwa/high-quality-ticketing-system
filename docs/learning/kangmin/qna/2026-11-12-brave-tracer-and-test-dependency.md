---
title: Brave 추적 처리기는 무엇이고 왜 reservation 테스트에만 따로 넣었나
date: 2026-11-12
updated: 2026-11-12
asker: kangmin
level: 중급
tags: [distributed-tracing, brave, micrometer-tracing, zipkin, gradle, test]
---

# Brave 추적 처리기는 무엇이고 왜 reservation 테스트에만 따로 넣었나

## 질문
`reservation-service/build.gradle`의 아래 줄에서 "Brave 추적 처리기"는 무엇인가.

```groovy
// 스케줄러 Redis 스팬 제외를 실제 Brave 추적 처리기로 시험한다. 운영에서는 starter-zipkin이 실행 시점에만 넣는다
testImplementation 'io.micrometer:micrometer-tracing-bridge-brave'
```

## 짧은 답
- Brave는 OpenZipkin이 만든 분산 추적 구현체(tracer)다. 스팬을 만들고, 추적 ID를 스레드에 붙여 이어 가고, 끝난 스팬을 Zipkin으로 보내는 일을 실제로 한다.
- 애플리케이션 코드는 Micrometer Tracing이라는 추상 API(`Tracer`, `SpanExportingPredicate` 등)만 본다. `micrometer-tracing-bridge-brave`는 그 추상 API를 Brave 구현에 연결하는 어댑터(브리지)다. SLF4J와 Logback 관계와 같다.
- 운영에서는 `spring-boot-starter-zipkin`이 이 브리지를 runtime 의존으로만 가져온다. 그래서 컴파일 클래스패스에 Brave 클래스가 없고, 테스트에서 `brave.Tracing`이나 `BraveTracer`를 직접 쓰려면 `testImplementation`으로 따로 넣어야 한다.

## 자세히

### 추적 관련 계층
| 계층 | 라이브러리 | 하는 일 |
|---|---|---|
| 관측 API | Micrometer Observation | 한 작업(HTTP 요청, `@Scheduled` 실행, Redis 명령)을 관측 하나로 감싼다. 처리기(handler)를 붙이면 지표·스팬이 나온다 |
| 추적 추상 API | Micrometer Tracing (`micrometer-tracing`) | `Tracer`, `Span`, `FinishedSpan`, `SpanExportingPredicate` 같은 구현 중립 인터페이스 |
| 브리지 | `micrometer-tracing-bridge-brave` 또는 `-bridge-otel` | 추상 API 호출을 실제 구현으로 넘긴다 |
| 추적 구현체 | Brave 또는 OpenTelemetry SDK | 스팬 생성, 추적 문맥 전파, 샘플링, 끝난 스팬 넘기기 |
| 보고기(reporter) | `zipkin-reporter-brave` | 끝난 스팬을 Zipkin 서버로 HTTP 전송 |

Brave와 OpenTelemetry는 같은 자리의 대체재다. Spring Boot의 Zipkin 스타터는 Brave 쪽을 고른 조합이다.

### "추적 처리기"라는 말
Micrometer Observation에서 관측이 시작·종료될 때 불리는 것이 `ObservationHandler`다. 처리기를 무엇을 붙이느냐에 따라 결과가 달라진다.
- `DefaultMeterObservationHandler`: 관측을 지표(Timer)로 바꾼다.
- `DefaultTracingObservationHandler(tracer)`: 관측을 스팬으로 바꾼다. 이 `tracer`가 Brave 브리지(`BraveTracer`)면 Brave가 실제 스팬을 만든다.

빌드 파일 주석의 "Brave 추적 처리기"는 이 추적 처리기에 Brave 구현을 붙인 것을 뜻한다. 표준 용어로는 "Brave tracer"이고, 처리기 자체는 Micrometer의 `TracingObservationHandler`다.

### 왜 가짜가 아니라 실제 Brave로 시험하나
`SchedulerRedisSpanConfiguration`은 두 단계로 스팬을 거른다.
1. `ObservationFilter`가 관측 종료 시점에 "예약 작업 바로 아래 Redis 명령"이면 높은 카디널리티 태그 `iseonjwa.scheduler-redis=true`를 단다.
2. `SpanExportingPredicate`가 끝난 스팬의 태그를 보고 그 표시가 있으면 내보내지 않는다.

이 둘이 맞물리려면 (1) 관측의 높은 카디널리티 값이 실제로 스팬 태그로 옮겨지는지, (2) Brave의 `SpanHandler` 단계에서 내보내기 조건이 실제로 불리는지가 확인돼야 한다. 이것은 Micrometer와 Brave 사이 변환 동작이라 모의 객체(mock)로 바꾸면 시험하고 싶은 부분이 사라진다. 그래서 테스트는 `Tracing.newBuilder()`로 진짜 Brave를 만들고, `CompositeSpanHandler`에 제외 조건과 수집 리스트를 붙여 "Zipkin으로 보냈을 스팬"을 메모리에 모은다. 네트워크와 Zipkin 서버는 쓰지 않는다.

### 왜 `testImplementation`인가
| 선택지 | 결과 |
|---|---|
| 아무것도 안 넣음 | 운영 runtime에는 Brave가 있지만 테스트 컴파일 클래스패스에 `brave.*`가 없어 테스트가 컴파일되지 않는다 |
| `implementation`으로 넣음 | 운영 코드도 Brave 클래스를 직접 쓸 수 있게 열린다. 운영 코드는 추상 API만 쓴다는 경계가 흐려진다 |
| `testImplementation`으로 넣음 (고른 것) | 테스트만 Brave를 직접 다룬다. 운영 코드는 여전히 Micrometer 추상 API에만 의존한다 |

감수한 점: 루트 `build.gradle`의 starter-zipkin 버전과 테스트 의존이 따로 적혀 있다. 버전은 Spring Boot BOM(`spring-boot-dependencies`)이 둘 다 같은 1.7.2로 맞추므로 어긋나지 않는다. 나중에 OpenTelemetry 쪽으로 바꾸면 이 테스트도 함께 바꿔야 한다.

## 이 프로젝트에서는
- 운영 의존: 루트 `build.gradle`의 애플리케이션 모듈 공통 블록 `implementation 'org.springframework.boot:spring-boot-starter-zipkin'`.
- 테스트 의존: `reservation-service/build.gradle` 18행.
- 대상 코드: `reservation-service/src/main/java/com/iseonjwa/reservation/global/observability/SchedulerRedisSpanConfiguration.java`.
- 테스트: `reservation-service/src/test/java/com/iseonjwa/reservation/global/observability/SchedulerRedisSpanConfigurationTest.java`.
- 확인한 의존 경로(2026-11-12, `./gradlew :reservation-service:dependencyInsight --dependency micrometer-tracing-bridge-brave`):
  - `runtimeClasspath`: `spring-boot-starter-zipkin:4.1.2`를 거쳐 `micrometer-tracing-bridge-brave:1.7.2`가 들어온다.
  - `compileClasspath`: 찾지 못한다("No dependencies matching"). 즉 starter가 runtime 범위로만 가져온다.
- 배경: 스케줄러 Redis 스팬이 평시 스팬의 약 87%를 차지해 Zipkin 메모리 한도를 채웠다(TS-54, `2026-11-11-scheduler-span-off-what-we-lose.md`).

## 면접 포인트
- Micrometer Tracing과 Brave의 관계는? 추상 API(파사드)와 구현체다. 브리지를 바꾸면 OpenTelemetry로 옮길 수 있고 애플리케이션 코드는 그대로다.
- 추적 필터를 왜 실제 tracer로 테스트했나? 시험 대상이 관측 태그가 스팬 태그로 옮겨지고 내보내기 단계에서 걸러지는 "라이브러리 사이 연결"이라, 모의 객체로 바꾸면 그 연결을 검증하지 못한다.
- Gradle `implementation`과 `testImplementation`, Maven의 runtime 범위 차이는? runtime 범위 의존은 실행 클래스패스에만 있고 컴파일 클래스패스에는 없다. 테스트 코드에서 직접 import하려면 테스트 범위로 명시해야 한다.

## 참고
- Micrometer Tracing 문서: Supported Tracers(Brave, OpenTelemetry), Bridges
- Spring Boot 참조 문서: Observability > Tracing
- 위 `dependencyInsight` 실행 결과(2026-11-12)

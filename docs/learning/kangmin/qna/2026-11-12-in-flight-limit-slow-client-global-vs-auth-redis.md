---
title: 클라이언트가 느려 Gateway 동시 처리 수 상한(64)에 막히면, 상한을 전역 대신 auth-redis별로 걸어야 하나
date: 2026-11-12
updated: 2026-11-12
asker: kangmin
level: 중급
tags: [gateway, in-flight-limit, bulkhead, littles-law, slow-client, load-test, k6]
---

# 클라이언트가 느려 Gateway 동시 처리 수 상한(64)에 막히면, 상한을 전역 대신 auth-redis별로 걸어야 하나

## 질문
좌석 조회 로컬 부하 테스트(좌석 조회 담당). 캐시를 붙여 조회가 빨라졌고 Gateway도 빨리 처리했다. 그런데 k6가 요청을 보내고 응답을 받는 쪽이 점점 느려졌다. 그 사이 Gateway 동시 처리 수 상한 64에 막혀 503이 계속 났다. 서버 성능은 남아 있는데 막힌 것처럼 보였다. 이 상한을 전역으로 걸지 말고 auth-redis별로 걸어야 하지 않나?

## 짧은 답
- **auth-redis별로 옮겨도 이번 문제는 풀리지 않는다.** auth-redis별 상한은 이미 있다(bulkhead 50). 이 상한은 Redis 조회 몇 ms만 묶는다. 전역 상한을 없애면 이번 503은 사라진다. 대신 배포 서버 측정으로 얻은 지연·메모리 보호(p95 435 → 87ms, OOM 방지)도 함께 사라진다.
- **문제는 상한을 거는 위치가 아니라 상한이 세는 범위다.** 상한은 자리를 요청 시작부터 **응답을 클라이언트에 다 쓸 때까지** 잡는다(`InFlightLimitHandlerDecorator`). 그래서 클라이언트가 느리게 받으면 서버 일이 끝났어도 자리가 찬다.
- 64는 배포 서버에서 k6를 다른 EC2에 두고 한 건 체류 40~45ms로 계산한 값이다. 로컬처럼 체류 시간이 길어지면 같은 64가 받을 수 있는 처리량이 줄어든다(리틀의 법칙).
- 먼저 가설을 확인한다(아래 4절). 로컬 테스트는 상한을 끄거나 키워서 돌린다. 운영에서 느린 클라이언트(모바일망)가 자리를 먹는 문제는 따로 다룰 설계 질문이다.

## 자세히

### 1. 두 상한은 지키는 것이 다르다
| | 동시 처리 수 상한 | auth-redis bulkhead |
|---|---|---|
| 위치 | Gateway 맨 앞 `HttpHandler` 데코레이터 | 인증 필터 안 Redis 조회 |
| 감싸는 범위 | 요청 하나 전체(인증, 라우팅, 다운스트림 대기, 응답 쓰기) | Redis 조회 한 번 |
| 값 | 64, 넘치면 바로 503 + `Retry-After: 2` | 50, 대기 0 |
| 지키는 것 | Gateway 메모리(5차 OOM)와 전체 지연 | Redis 호출 자원, Redis 장애가 Gateway 전체로 번지는 것 |
| 근거 | `gateway.yml` 67~74행, 설계 메모 A1 | `gateway.yml` 96~101행, ADR 0050 |

자세한 비교는 같은 날 노트 "벌크헤드와 동시 처리 수 상한은 무엇이 다른가"(`2026-11-12-bulkhead-vs-in-flight-limit.md`)에 있다.

### 2. 왜 서버가 한가한데 막히나: 자리를 언제 반납하나
```java
return Mono.defer(() -> delegate.handle(request, response))
        .doFinally(signal -> inFlight.decrementAndGet());   // 응답 완료·오류·취소 때 반납
```
`delegate.handle`은 응답 본문을 클라이언트 연결에 다 쓰고 나서 끝난다. 클라이언트가 느리게 읽으면 TCP 수신 창이 차고 쓰기가 늦어진다. 그 동안 Gateway CPU는 거의 쓰지 않지만 자리는 계속 잡혀 있다.

리틀의 법칙(처리 중 수 = 처리율 × 체류 시간)으로 보면 상한 64가 받을 수 있는 처리율은 `64 ÷ 체류 시간`이다(계산값).
| 한 건 체류 시간 | 받을 수 있는 최대 처리율 |
|---|---|
| 45ms(배포 서버 측정 기준) | 약 1,420건/s |
| 100ms | 640건/s |
| 200ms | 320건/s |

캐시로 서버 쪽 시간이 줄어도 응답 전송 시간이 늘면 체류 시간이 늘어난다. 그러면 상한이 먼저 닿는다. 상한은 "Gateway가 느리다"와 "클라이언트가 느리다"를 구분하지 못한다.

### 3. 선택지 비교
| 안 | 내용 | 이번 증상 | 대가 |
|---|---|---|---|
| A. 전역 상한을 없애고 auth-redis bulkhead만 둠 | 질문의 안 | 사라짐 | 6차 측정의 효과(2,000/s p95 435 → 87ms, 메모리 평탄)를 잃는다. bulkhead는 Redis 조회만 묶어 다운스트림 대기·응답 버퍼 메모리를 묶지 못한다 |
| B. 라우트별 상한 | 좌석 조회, 결제 등 따로 | 좌석 조회 몫만 막힘 | 느린 클라이언트가 자리를 먹는 것은 같다. 값이 라우트 수만큼 늘어난다 |
| C. 반납 시점을 앞당김 | 다운스트림 응답 헤더를 받으면 반납 | 느린 클라이언트 몫이 빠짐 | 느린 클라이언트가 붙잡는 연결·버퍼 메모리는 묶이지 않는다. 5차 OOM의 원인이 동시 수였으므로 위험하다 |
| D. 느린 클라이언트에 시한 | Gateway 서버 쪽 유휴·쓰기 시한으로 너무 느린 연결을 끊음 | 자리를 잡는 시간에 상한이 생김 | 정상인 느린 모바일 사용자도 끊길 수 있다. 값을 정할 측정이 없다. 지금 이런 설정은 config-repo에 없다(확인함) |
| E. 적응형 상한 | 지연을 보고 상한을 자동 조정(concurrency limits의 gradient 방식 등) | 줄어들 수 있음 | 재는 지연에 클라이언트 시간이 섞이면 같은 문제가 남는다. 구현·설명 비용이 크다 |
| F. 지금 상한 유지 + 측정 환경 분리 | 로컬은 끄거나 키움, 값은 배포 서버에서만 판단 | 로컬에서 안 남 | 운영의 느린 클라이언트 문제는 미해결로 남는다 |

**추천: F를 먼저 하고, D는 배포 서버에서 측정한 뒤 정한다.**
- 64는 배포 서버(k6가 다른 EC2)에서 정한 값이다. 서비스와 k6가 같은 PC에서 CPU를 나눠 쓰는 로컬 결과로 바꿀 값이 아니다.
- 운영에서도 느린 클라이언트가 많으면 같은 모양(CPU는 남는데 503)이 나올 수 있다. 이것은 실제 설계 위험이다. 그래도 A·C처럼 보호를 걷어내는 방식은 OOM을 다시 부를 수 있다. 자리를 잡는 시간 자체에 상한을 거는 D가 원인에 맞는 대책이다.

**감수한 점**: F만 하면 운영의 느린 클라이언트 위험은 측정 전까지 남는다.

### 4. 가설을 먼저 확인하는 방법
| 볼 것 | 가설이 맞다면 |
|---|---|
| k6 `http_req_receiving`(응답 받는 시간) | 단계가 올라갈수록 커진다 |
| k6 `http_req_waiting`(첫 바이트까지) | 작게 유지된다 |
| 예매 서비스 `http_server_requests` p95(서버 일) | 작게 유지된다 |
| Gateway `gateway_in_flight_requests` | 64에 붙어 있다 |
| Gateway `gateway_in_flight_rejected_total` | 늘어난다 |
| k6 프로세스 CPU(같은 PC) | 높다. k6가 응답을 늦게 읽는 원인 후보다 |
| 좌석 조회 응답 크기 | 클수록 전송 시간이 길다. 좌석 배치 응답 크기는 확인 못 함 |

`http_req_waiting`이 작고 `http_req_receiving`만 크면 서버가 아니라 전송·클라이언트 쪽 시간이다. 그때 503은 상한이 설계대로 동작한 결과이고, 문제는 측정 환경이다.

로컬에서 상한을 바꾸는 방법(설정 키는 코드에서 확인함): `iseonjwa.gateway.in-flight.enabled=false`로 끄거나 `iseonjwa.gateway.in-flight.max-requests`를 키운다. 환경변수로 줄 때의 이름(`ISEONJWA_GATEWAY_IN_FLIGHT_MAX_REQUESTS`)은 스프링의 환경변수 이름 변환 규칙으로 추론한 것이고, 실제로 시험하지 않았다.

## 이 프로젝트에서는
- 코드: `gateway/src/main/java/com/iseonjwa/gateway/overload/InFlightLimitHandlerDecorator.java`(자리 잡기·반납), `security/AccessPolicy.java`(좌석 조회 `GET /schedules/{id}/seats`는 공개 경로가 아니라 로그인 필요 → auth-redis를 거친다)
- 설정: `config-repo/gateway.yml` 67~74행(64, `retry-after` 2s), 96~101행(bulkhead 50)
- 측정 기록: `docs/design/infra/gateway-overload-troubleshooting-story.md` 단계 4·6(상한 64의 효과와 32·64·128·500 비교)

## 면접 포인트
- **"동시 처리 수 상한이 서버가 한가한데 거절하면?"** 상한은 요청이 시스템에 머무는 시간 전체를 센다. 그래서 느린 클라이언트도 자리를 먹는다. 먼저 받는 시간(`receiving`)과 서버 처리 시간을 나눠 원인을 확인했다. 보호를 걷어내는 대신 느린 연결에 시한을 거는 방향을 검토했다.
- **"bulkhead가 있는데 전역 상한이 왜 필요한가?"** bulkhead는 의존성 하나의 호출을, 전역 상한은 요청 전체의 메모리와 지연을 묶는다. 전역 상한이 없을 때 OOM이 났고, 넣은 뒤 같은 부하에서 p95가 435ms에서 87ms로 줄었다.

## 참고
- `docs/design/infra/gateway-overload-troubleshooting-story.md`
- 같은 날 노트: `2026-11-12-bulkhead-vs-in-flight-limit.md`, `2026-11-12-gateway-cpu-saturation-next-steps.md`

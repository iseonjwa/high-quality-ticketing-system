---
title: local 부하 측정에서 CPU 상한을 걸어야 하는 이유는 무엇인가
date: 2026-11-11
updated: 2026-11-11
asker: KWON-WOO
level: 중급
tags: [load-test, docker, cpu, jvm, cpuset, measurement-design]
---

# local 부하 측정에서 CPU 상한을 걸어야 하는 이유는 무엇인가

## 질문
측정 회고에서 CPU 상한을 걸고 F를 다시 재자고 했는데, CPU 상한을 걸어야 하는 이유는 무엇인가?

## 짧은 답
측정값이 기기 성능이 아니라 코드와 설정의 효과를 말하게 하려는 것이다. 이유는 세 가지다.
1. 상한이 없으면 처리량이 기기를 따라간다. 기기를 바꾸면 이전 수치를 이어 쓸 수 없다.
2. local은 CPU가 남아 배포 환경(4 vCPU 한 대에 전체 스택)과 병목 순서가 달라진다. CPU 비용을 줄이는 개선(P4d)은 local에서 효과가 보이지 않는다.
3. JVM과 Netty는 보이는 CPU 수로 스레드 수 같은 기본값을 정한다. local에서 32개를 보면 배포와 다른 설정으로 돈다.

배포를 흉내 내려면 컨테이너마다 `cpus`를 따로 주는 것보다, 서버 컨테이너 전체를 같은 4코어에 묶는 `cpuset`이 더 가깝다.

## 자세히

### 1. 상한이 없으면 처리량이 기기를 따라간다
측정 스택은 메모리 상한(배포 메모리)은 걸었지만 CPU 상한은 없었다. 그래서 컨테이너는 Docker VM의 코어를 필요한 만큼 썼다. 이전 노트북에서 새 기기(i9-14900HX, Docker VM 32코어)로 옮기자 같은 코드의 처리량이 달라졌다. base부터 P4b까지 다시 쟀다(측정 브랜치 STEPS.md "측정 기기 이동"). 상한을 걸어 두면 어느 기기에서든 스택이 쓸 수 있는 CPU가 같아진다. 그러면 기기가 바뀌어도 수치를 비교하기 쉽다. 다만 코어 하나의 속도는 기기마다 달라서 완전히 같아지지는 않는다.

같은 기기 안에서 단계끼리 비교한 결과(base → F의 개선 순서)는 상한이 없어도 유효하다. 상한은 다른 기기나 배포 환경으로 결과를 옮길 때 필요하다.

### 2. local과 배포의 병목 순서가 달라진다
F의 K1 S 1,280명 단계에서 local CPU 최대 사용량은 다음과 같았다(k6-p4d-review.md).

| 컨테이너 | local 최대 |
|---|---|
| reservation-service | 6.2~6.7코어 |
| Gateway | 6.8~7.0코어 |
| k6 | 7.6~7.8코어 |

세 개만 더해도 약 20코어다. 배포 서버(`m7i.xlarge`)는 4 vCPU 한 대에서 Gateway, 모든 서비스, PostgreSQL, Redis, Kafka, 관측 도구가 함께 돈다. 거기서 Q1을 초당 2,000~3,000명으로 재자 서버 CPU가 95~99%까지 올라 막혔고, Gateway 하나가 약 2코어를 썼다(open-peak-scenarios.md). 같은 코드라도 배포에서는 CPU가 가장 먼저 바닥난다.

그래서 local 결과는 "CPU가 무한할 때 무엇이 막히나"에 대한 답이다. 커넥션 대기와 DB 조인처럼 CPU 밖의 병목은 잘 보인다. CPU 비용을 줄이는 개선은 보이지 않는다. P4d가 그 예다. P4d는 응답 JSON 직렬화(JFR 기준 CPU의 약 32%)를 줄이는 개선이다. 측정 전에 정한 조건은 맞았지만, local에서는 CPU가 남아 클라이언트 수치에 효과가 나오지 않았다. 그래서 판단을 미뤘다. CPU를 4코어로 묶으면 직렬화 비용이 처리량을 막는지 local에서 바로 볼 수 있다.

### 3. 보이는 CPU 수가 기본 설정을 바꾼다
JVM은 컨테이너의 CPU 제한(CFS 할당량, `cpuset`)을 읽어 `availableProcessors()`를 정한다. 그 값으로 다음 기본값을 고른다.
- GC 스레드 수, 그리고 GC 종류. CPU가 2개 미만이거나 메모리가 약 1,792MB 미만이면 Serial GC를 고른다. base가 Serial이었던 것은 메모리(1,152MB) 때문이었다.
- `ForkJoinPool.commonPool` 크기(병렬 스트림, `CompletableFuture` 기본 실행기).
- Netty 이벤트 루프 스레드 수. Gateway(WebFlux)는 기본으로 CPU 수만큼 둔다.

local에서 상한이 없으면 JVM은 32개를 보고, 배포에서는 4개를 본다. 스레드 수가 다르면 문맥 전환과 GC 정지 모양도 달라진다.

### 4. 부하 생성기가 서버와 CPU를 나눠 쓴다
local에서는 k6도 같은 VM에서 돈다. 1,280명 단계에서 k6가 약 7.8코어를 썼다. 배포 측정에서는 k6가 다른 EC2(발생기)에서 돈다. 상한이 없으면 local 서버는 k6와 CPU를 다투고, 상한을 서버에만 걸면 k6가 서버 몫을 빼앗지 못한다. 그래서 k6는 서버와 다른 코어에 둔다.

### 거는 방법: `cpus`와 `cpuset`

| 방법 | 동작 | 배포와 닮은 정도 |
|---|---|---|
| 컨테이너마다 `cpus: 1.5` 등 | CFS 할당량. 100ms 주기마다 정해진 시간만 쓰고, 넘으면 다음 주기까지 멈춘다(스로틀링) | 낮다. 배포는 컨테이너별 할당량이 아니라 4코어를 함께 나눠 쓴다. 스로틀링 때문에 배포에 없는 지연 튐이 생길 수 있다. 비율을 어떻게 나눌지도 임의로 정해야 한다 |
| 서버 컨테이너 전체에 `cpuset: "0-3"` | 모든 서버 컨테이너가 같은 4코어만 쓴다. 서로 다투는 방식이 배포와 같다 | 높다. k6는 `cpuset`으로 다른 코어에 둔다 |
| Docker VM 전체를 4코어로(`.wslconfig`의 `processors=4`) | VM이 4코어만 본다 | 서버는 배포와 같아지지만 k6도 같은 4코어를 써서 서버 몫을 빼앗는다 |

`cpuset`을 쓸 때 주의할 점이 있다.
- i9-14900HX는 성능 코어(P)와 효율 코어(E)가 섞여 있다. 묶을 코어가 같은 종류인지 확인한다.
- 배포 서버는 클라우드 vCPU라, 같은 4코어라도 코어 하나의 속도가 다르다. 절대 수치는 여전히 배포에서 따로 재야 한다. local 상한 측정은 병목 순서와 상대 비교(P4d를 넣을지)에 쓴다.

## 이 프로젝트에서는
- 지금 측정 스택에는 CPU 상한이 없다. 메모리는 `reservation-service/k6/compose.deploy-memory.yml`(측정 브랜치)로 배포 값을 걸었다.
- 배포 서버: `m7i.xlarge` 4 vCPU 한 대에 전체 스택(docs/design/infra/deployment.md, open-peak-scenarios.md). 발생기는 따로 `c6i.xlarge`다(docs/guides/loadgen.md).
- 적용한다면 측정 브랜치에 `compose.cpuset-4.yml` 같은 덮어쓰기를 두고, 서버 컨테이너 전체를 같은 4코어에 묶은 채 F L·S를 1회씩 잰다. 공유 compose는 고치지 않는다.

## 면접 포인트
- "local 부하 테스트 결과를 운영에 그대로 쓸 수 있나?" → 그대로 쓸 수 없다. 자원이 다르면 병목 순서가 바뀐다. local에 운영과 같은 CPU·메모리 상한을 걸면 순서는 가깝게 볼 수 있지만, 절대 수치는 운영 환경에서 다시 잰다.
- "컨테이너에 CPU 제한을 걸면 JVM에 어떤 영향이 있나?" → JVM이 제한을 읽어 `availableProcessors`를 정한다. 그 값으로 GC 종류와 스레드 수, 공용 스레드 풀 크기가 바뀐다. 할당량(`cpus`)은 넘으면 스로틀링으로 멈춰 지연이 튈 수 있다.

## 참고
- 측정 브랜치 `reservation-service/k6/STEPS.md` "측정 기기 이동"(261행: "CPU 상한이 없어 처리량이 기기를 따라간다")
- [k6-p4d-review.md](../../../design/reservation/k6-p4d-review.md): F의 CPU 최대 사용량, P4d 판단 보류
- [open-peak-scenarios.md](../../../design/infra/open-peak-scenarios.md): 배포 서버 4 vCPU 한계(서버 CPU 95~99%)
- Docker 문서 "Resource constraints"(`--cpus`, `--cpuset-cpus`), OpenJDK 컨테이너 인식(JDK-8146115)

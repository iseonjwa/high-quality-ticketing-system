---
title: 팀원이 가비지 컬렉터를 Serial에서 G1으로 바꿔 잰 내용은 무엇이고, 배포 서버에는 어떤 의미인가
date: 2026-11-05
updated: 2026-11-05
asker: Junhyun An
level: 중급
tags: [jvm, gc, g1, serial-gc, container, load-test, deployment]
---

# 팀원이 가비지 컬렉터를 Serial에서 G1으로 바꿔 잰 내용은 무엇이고, 배포 서버에는 어떤 의미인가

## 질문
팀원들이 가비지 컬렉터를 교체해 봤다는데 어떤 내용인가.

## 짧은 답
- **누가, 왜 바꿨나**
  - 좌석 선점 담당 최권우가 ISE-79 부하 측정(K1: 초당 100명 입장 → 좌석 조회 → 선점)을 하다가 좌석 조회가 GC 정지로 무너지는 것을 봤다.
  - 원인을 찾아보니 JVM이 컨테이너 메모리(1,152MB)를 보고 **Serial GC를 자동으로 고른 상태**였다.
- **바꿨더니**
  - 측정 전용 설정으로 G1을 켜자 GC 정지 합계가 약 241초에서 81.8초로 줄었다.
  - 최대 정지는 1초 미만이 됐고, 오류 없이 버틴 속도는 초당 5명에서 10명으로 올랐다.
- **그래도 남은 것**
  - 초당 100명에서는 여전히 무너졌다. 좌석 조회 한 번이 너무 비싸서(15,000석 엔티티와 약 830KB JSON) 쓰레기 자체가 많기 때문이다.
  - 그래서 다음 단계는 좌석 조회 경량화(P4)로 정했다(ISE-189, 반효승 담당).
- **배포 담당에게 넘어온 결정**
  - 공유 설정(`compose.deploy.yml`)에 G1을 넣을지는 배포 담당과 contract 이슈로 정하기로 남겨 뒀다.
  - 지금 배포 서버의 앱 7개는 모두 Serial GC로 돌고 있다(2026-11-05 확인).

## 자세히

### 1. GC 기초
- Java는 객체를 힙(heap)에 만들고, 더 이상 쓰지 않는 객체(쓰레기)를 GC가 치운다.
- 대부분의 GC는 객체를 나이로 나눈다.
  - 막 만든 객체는 Young 영역에 둔다. 대부분 금방 쓰레기가 되고, 여기를 치우는 것이 Minor GC다.
  - 오래 살아남은 객체는 Old 영역으로 옮긴다. Old까지 치우는 것이 Major GC(Full GC)다.
- 치우는 동안 앱 스레드를 모두 멈추는 것을 **Stop-the-World(STW)**라고 한다. 이 정지가 길면 그동안 들어온 요청이 모두 기다리다 시한을 넘긴다.

| GC | 방식 | 맞는 곳 |
|---|---|---|
| Serial | GC 스레드 **하나**로 치우는 동안 앱을 전부 멈춘다. 오버헤드는 가장 작다 | 아주 작은 힙, CPU 1개 |
| Parallel | 여러 스레드로 빠르게 치우지만 역시 전부 멈춘다. 처리량 우선 | 배치 작업 |
| **G1** (Garbage-First) | 힙을 1~32MB **리전** 수천 개로 나눠 쓰레기가 많은 리전부터 일부만 치운다. 목표 정지 시간(기본 200ms) 안에 끝낼 만큼만 고르고, Old 영역 표시는 앱을 멈추지 않고 동시에 한다 | 대부분의 서버(서버급 기기의 기본값) |
| ZGC | 정지가 1ms 수준. CPU와 메모리를 더 쓴다 | 큰 힙, 아주 짧은 지연 |

### 2. 왜 Serial이 골라졌나: JVM의 자동 선택(ergonomics)
- GC를 명시하지 않으면 JVM이 기기를 보고 고른다.
  - CPU 2개 이상이고 메모리가 약 1,792MB 이상이면 "서버급 기기"로 보고 G1을 고른다.
  - 아니면 Serial을 고른다.
- 컨테이너 안에서는 **컨테이너 메모리 상한을 기기 메모리로 본다.**
- 배포 compose의 앱 상한은 모두 1,792MB보다 작다. 가장 큰 reservation-service가 1,152MB다. 서버 메모리가 16GB여도 앱은 모두 Serial로 뜬다.
- 즉 Serial은 누가 고른 것이 아니라 상한 숫자 때문에 생긴 결과다.

**배포 서버에서 직접 확인 (2026-11-05 14시, `m7i.xlarge`로 다시 시작한 직후)**
- Prometheus의 `jvm_gc_pause_seconds_count`
  - `gc` 라벨이 앱 7개 모두 `Copy`(Serial의 Young 수집) 또는 `MarkSweepCompact`(Serial의 Old 수집)였다.
  - G1이었다면 `G1 Young Generation`처럼 나온다.
- reservation-service 컨테이너
  - `java -XX:+PrintFlagsFinal -version` 결과가 `UseSerialGC = true {ergonomic}`이었다.
  - `JAVA_TOOL_OPTIONS`는 `-Xms384m -Xmx768m`뿐이다.

### 3. 팀원이 한 측정 (ISE-79, 브랜치 `feat/ISE-79-seat-hold-race-condition`)
- **문서 위치**
  - 계획·결과: `docs/design/reservation/k6-seathold-bottleneck-plan.md` 5-1~5-3절
  - 학습 노트: `docs/learning/kwonwoo/qna/2026-11-05-p4-first-stepwise-measurement-and-g1.md`
  - 판단 기록: `docs/ai-judgments/kwonwoo/2026-11-05-g1-first-then-decide-p4-order.md`
  - 11/5 14시 기준으로 dev에 머지되지 않았고 PR도 없다.
- **방법:** 한 번에 변수 하나만 바꿨다.
  - P0': 측정 경로(gateway, reservation-service)의 메모리만 배포 값으로 맞췄다(`reservation-service/k6/compose.deploy-memory.yml`).
  - P0'': 그 위에 reservation-service만 `-XX:+UseG1GC`를 더했다(`reservation-service/k6/compose.g1.yml`). GC 로그도 남겼다.
  - 공유 설정(`compose.yml`, `compose.deploy.yml`)은 고치지 않았다.

| 항목 | P0' (Serial, 힙 768MB) | P0'' (G1, 같은 메모리) |
|---|---|---|
| GC 정지 합계(측정 구간) | 약 241초 | 81.8초 |
| GC 정지 최대 | 수 초 이상, Major 연속 | 0.9초. 4,094회 중 4,031회가 50ms 미만 |
| Full GC | 연속 | 2회 |
| 오류 0으로 버틴 최대 속도 | 초당 5명 | 초당 10명 |
| 초당 100명: 좌석 조회 성공 | 12 / 30,014 | 18 / 29,994 |
| 부하 뒤 회복 | 못 함 | 못 함 |

- **해석: G1은 정지 문제를 풀었다.** 수십 초짜리 정지가 사라졌다.
- **다음 병목이 드러났다.**
  - 좌석 조회 한 건이 15,000석을 엔티티로 읽고 DTO로 바꾸는 동안 트랜잭션이 DB 커넥션을 쥔다.
  - 풀이 5개라 동시에 5건만 처리되고 나머지는 Hikari 대기열(최대 197)에서 기다렸다.
- **G1은 쓰레기를 덜 아프게 치울 뿐, 덜 만들게 하지는 않는다.** 근본 해결은 요청당 할당을 줄이는 P4다.
  - P4a: 필요한 열만 DTO로 읽기
  - P4b: 배치 정보 캐시
  - P4c: 좌석 상태 1초 캐시
- 그래서 단계 순서를 P0 → P0' → P0'' → P4a·b·c → P1(커넥션 풀) …로 바꿨다. 좌석 조회가 막힌 채 커넥션 풀을 늘리면 효과가 "0건 → 0건"이라 잴 수 없기 때문이다.

### 4. 배포 담당 입장에서 남은 결정
- 팀원 문서에는 "G1을 `compose.deploy.yml`에 넣을지는 공유 설정이라 배포 담당과 contract 이슈로 정한다. 정하기 전까지 측정은 측정 전용 파일로 G1을 켠다"고 되어 있다.
- 11/5 14시 기준 Linear에는 이 내용의 contract 이슈가 없다. ISE-189는 P4 요청이고 담당은 반효승이다.

**고려할 점**
| 항목 | 내용 |
|---|---|
| 측정 환경 차이 | 팀원 측정은 노트북(16코어, Docker Desktop 7.6GB)에서 했다. 배포 서버는 4 vCPU를 컨테이너 20개가 나눠 쓴다. G1의 병렬 처리 이점이 노트북보다 작을 수 있어 서버에서 다시 재야 한다 |
| 메모리 | G1은 리전 관리 정보 때문에 Serial보다 힙 바깥 메모리를 조금 더 쓴다. 서버의 유휴 사용량이 약 4.7GB / 15.7GB라 여유는 있다. 바꾼 뒤 `docker stats`로 컨테이너가 상한에 붙지 않는지 본다 |
| 시점 | 11/6 리허설은 트래픽이 작아 Serial로도 문제가 드러날 일이 적다. 리허설 전날 JVM 설정을 바꾸는 위험에 비해 얻는 것이 작다. 배포 서버 부하 측정(ISE-176·181, 11/13 재가동 뒤) 전에 넣으면 된다 |
| 범위 | 부하는 gateway(모든 요청의 입구), reservation, payment(결제 시나리오), mock-pg를 지난다. 입구인 gateway가 Serial로 멈추면 모든 요청이 같이 멈춘다 |

**추천:** 리허설 뒤 contract 이슈로 알리고, `compose.deploy.yml`의 앱 7개 `JAVA_TOOL_OPTIONS`에 `-XX:+UseG1GC`를 명시하는 PR을 낸다.
- 컨테이너에서는 GC를 상한 숫자에 맡기지 않고 명시한다.
- 배포와 측정 환경을 같은 GC로 맞춘다.
- 넣은 뒤 서버에서 Prometheus `gc` 라벨이 `G1 Young Generation`으로 바뀌었는지, `docker stats`로 메모리가 상한 안에 있는지 확인한다.
- 이 결정은 사용자가 내린다.

## 면접 포인트
- **"컨테이너에서 JVM GC는 어떻게 정해지나?"** JVM은 CPU 2개 이상, 메모리 약 1,792MB 이상이면 G1, 아니면 Serial을 고른다. 컨테이너에서는 컨테이너 상한을 메모리로 보므로 작은 컨테이너는 Serial로 뜬다. 그래서 GC를 명시하고, 실제로 무엇이 골라졌는지 `PrintFlagsFinal`이나 GC 지표 라벨로 확인한다.
- **"GC를 바꾸면 성능 문제가 풀리나?"** 정지의 길이와 분포는 바뀌지만 할당량은 그대로다. 요청당 할당을 줄이는 것이 근본 해결이다. 이번 측정에서도 G1으로 한계가 2배 올랐지만 목표(초당 100명)에는 한참 못 미쳤다.
- **"측정은 어떻게 설계했나?"** 한 단계에 변수 하나만 바꿨다(메모리 → GC → 코드). 판정 기준을 측정 전에 정해, 결과를 보고 기준을 맞추는 일을 막았다.

## 관련 문서
- 팀원 문서(위 3절 경로, 브랜치 `feat/ISE-79-seat-hold-race-condition`)
- [부하 테스트(k6)는 어디에서 돌리나](2026-11-05-where-to-run-k6-load-generator.md), [배포 서버 컨테이너 20개](2026-11-05-why-20-containers-in-deploy.md)
- `compose.deploy.yml`(앱 `mem_limit`과 `JAVA_TOOL_OPTIONS`)

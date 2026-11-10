---
title: CPU·메모리가 빡빡한 배포 서버에서 컨테이너를 가볍게 만들어 해결할 수 있나
date: 2026-11-10
updated: 2026-11-10
asker: kangmin
level: 중급
tags: [docker-compose, jvm, capacity, observability, cpu, memory]
---

# CPU·메모리가 빡빡한 배포 서버에서 컨테이너를 가볍게 만들어 해결할 수 있나

## 질문
현 서버에서 CPU와 메모리가 너무 빡빡해 제대로 작업이 되지 않는다. 각 컨테이너가 조금이라도 덜 먹게 가볍게 만들어 해결할 수 없나?

## 짧은 답
- 일부는 된다. 하지만 측정상 CPU 대부분은 요청을 실제로 처리하는 Gateway·reservation·Postgres가 쓴다. 나머지 컨테이너를 가볍게 해서 얻는 몫에는 상한이 있다.
- 11/10 측정 D(1,200~1,600/s)에서 이 셋이 4 vCPU(가상 CPU 4개, 400%) 중 약 300~350%를 썼다. 다른 컨테이너와 OS 몫은 약 50~100%로 추정되고, 컨테이너별 값은 기록이 없다.
- 그래서 순서는 "지금 실제로 누가 얼마나 쓰는지 재기 → 위험이 낮은 것부터 끄거나 줄이기 → 효과 재측정"이다.
- `compose.deploy.yml`은 팀 공유 배포 설정이다. 바꾸려면 contract 이슈로 인프라 담당과 정해야 한다.

## 자세히

### 1. 지금 배분(`compose.deploy.yml`)
| 묶음 | 컨테이너(메모리 상한) | 합계 |
|---|---|---|
| 앱 8개(JVM) | config 512m, eureka 512m, gateway 768m, user 640m, reservation 1152m, payment 768m, mock-pg 512m, assistant 384m | 5,248MiB |
| DB·캐시·메시징 | postgres 3개 512/1024/512m, redis 512m, kafka 1536m, kafka-ui 512m | 4,608MiB |
| 관측 | prometheus 768m, alertmanager 128m, grafana 384m, loki 512m, alloy 256m, zipkin 768m | 2,816MiB |
| 합계(상한) | | 약 12,672MiB(약 12.4GiB, 계산값) / 장비 15.7GiB |

- 상한은 "이 이상 쓰면 종료된다"는 선일 뿐 실제 사용량이 아니다. 측정 직후 실제 사용량 기록은 reservation 699MiB/1,152MiB, gateway 497MiB/768MiB뿐이다(`numbers-and-measurement.md` 284행). 나머지는 확인 못 함이다.
- CPU 제한(`cpus`)은 어디에도 없다. 21개 컨테이너가 4 vCPU를 먼저 잡는 쪽이 쓰는 구조다.
- 평시 추적 샘플링은 1.0이다. 모든 요청의 추적 정보를 Zipkin으로 보낸다(`compose.deploy.yml:27`, 시연에서 traceId를 보려는 이유). 측정 모드에서만 0.01이다(`loadtest/deploy/compose.measure.yml`).

### 2. 선택지
| 선택지 | 무엇을 | 얻는 것 | 감수할 점 |
|---|---|---|---|
| A. 측정·부하 중 안 쓰는 컨테이너 끄기 | kafka-ui, assistant-service, (샘플링 0.01이면) zipkin | 되돌리기 쉽다. 메모리 상한 기준 약 1.6GiB, 상주 스레드·GC가 준다 | CPU 절감량은 확인 못 함이다(평소에 놀고 있으면 작다). 측정 모드 스크립트 변경은 인프라 담당 범위다 |
| B. 관측 비용 줄이기 | 평시 샘플링 1.0 → 0.1(설정 기본값), 요청마다 남는 로그 줄이기, Prometheus 수집 간격 | 요청당 CPU가 Gateway·서비스 모두에서 준다. 전 구간에 효과 | 시연에서 traceId로 아무 요청이나 찾는 기능이 약해진다. 팀이 1.0을 고른 이유와 부딪힌다 |
| C. 부가 JVM 줄이기 | config·eureka·mock-pg·assistant에 `-XX:ActiveProcessorCount=1`, `-XX:TieredStopAtLevel=1`(C1 컴파일러만), 작은 힙 | JVM마다 CPU 4개 기준으로 만드는 GC·JIT(실행 중 컴파일) 스레드와 메모리가 준다 | 그 서비스의 처리 속도가 느려질 수 있다. Gateway·reservation에는 쓰면 안 된다. 효과는 측정 전 추정이다 |
| D. CPU 배분(`cpu_shares`) | Gateway·reservation·Postgres에 우선순위 | 핵심 경로가 다른 컨테이너에 밀리지 않는다 | 가볍게 하는 것이 아니라 나누는 것이다. 총량은 그대로다 |
| E. 장비 키우기·관측 분리 | 관측 스택을 다른 장비로(ADR 0047 방향), 또는 더 큰 인스턴스 | 가장 확실하다 | 비용이 들고 일정이 11/19까지다 |

### 3. 추천
1. 먼저 잰다. 서버에서 평시와 L1 부하 중에 한 번씩 아래 명령을 실행해 컨테이너별 CPU·메모리를 표로 남긴다.
   ```bash
   free -m
   docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}'
   ```
2. 측정 결과에서 "부하 중에도 CPU를 쓰는 부가 컨테이너"가 보이면 A·B를 contract 이슈로 제안한다. B의 샘플링은 측정 모드에서 이미 0.01이라 측정 결과에는 영향이 없다. 평시 작업이 무거운 것이 문제라면 B가 가장 효과가 클 후보다(확인할 질문: 평시 Zipkin·Alloy CPU가 얼마인가).
3. 핵심 셋(Gateway·reservation·Postgres)이 300% 넘게 쓰는 구간은 가볍게 하기로는 풀리지 않는다. 그 구간은 요청당 비용 줄이기(인증 확인, 로그)나 E로 다룬다.

## 이 프로젝트에서는
- 근거: `compose.deploy.yml` 27·30~179행, `loadtest/deploy/compose.measure.yml`, `docs/design/lottery/numbers-and-measurement.md` 3-4절 D·C3(컨테이너 CPU), 284행(docker stats), `docs/learning/kangmin/qna/2026-11-10-gateway-threads-bulkhead-vs-server-spec.md` 3-1절.
- `compose.deploy.yml`과 측정 모드 스크립트는 팀 공유 설정이다. 바꾸려면 `docs/guides/contract-issues.md` 절차로 인프라 담당에게 이슈를 올린다.

## 면접 포인트
- "서버가 부족하면 어떻게 하나": 먼저 컨테이너별 사용량을 재서 핵심 경로와 부가 비용을 나눈다. 부가 비용(관측·관리 도구)은 끄거나 줄이고, 핵심 경로는 요청당 비용을 줄이거나 장비를 나눈다고 답한다.
- "추적 샘플링을 왜 줄였나 / 안 줄였나": 1.0은 디버깅에 좋지만 요청마다 비용이 붙는다. 운영에서는 비율 샘플링이 일반적이라고 답한다.

## 참고
- 값은 모두 저장소 설정과 11/10 측정 기록에서 옮겼다. 컨테이너별 평시 CPU·메모리 사용량과 각 선택지의 실제 절감량은 확인 못 함이다.

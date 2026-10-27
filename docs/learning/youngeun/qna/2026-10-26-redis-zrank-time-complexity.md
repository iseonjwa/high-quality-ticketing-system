---
title: Redis Sorted Set의 ZRANK는 시간 복잡도가 어떻게 되는가
date: 2026-10-26
updated: 2026-10-26
asker: kye
level: 중급
tags: [redis, sorted-set, skiplist, waiting-queue]
---

# Redis Sorted Set의 ZRANK는 시간 복잡도가 어떻게 되는가

## 질문
Redis Sorted Set의 ZRANK는 시간 복잡도가 어떻게 돼?

## 짧은 답
O(log N)이다. N은 Sorted Set의 원소 수다. Sorted Set은 큰 집합에서 skiplist와 hash table을 함께 쓰는데, skiplist의 각 링크가 건너뛰는 원소 수(span)를 저장하고 있어서 원소를 찾아 내려가는 동안 span을 더하면 순위가 바로 나온다. 원소가 적을 때는 listpack으로 저장해 선형 탐색(O(N))을 하지만, 그 N은 기본값 128 이하라 사실상 상수다. ZREVRANK도 같다.

## 자세히

### 내부 구조
Sorted Set은 크기에 따라 인코딩이 바뀐다.

| 인코딩 | 조건(기본값) | ZRANK 동작 |
|---|---|---|
| listpack (Redis 7.0 이전 ziplist) | 원소 수 ≤ `zset-max-listpack-entries`(128), 각 member 길이 ≤ `zset-max-listpack-value`(64바이트) | 앞에서부터 차례로 훑는다. O(N)이지만 N ≤ 128 |
| skiplist + dict | 위 조건을 넘으면 | dict로 member의 score를 O(1)에 찾고, skiplist에서 (score, member)를 탐색하며 span을 누적한다. O(log N) |

### span으로 순위를 구하는 방식
skiplist는 여러 층의 연결 리스트다. 위층일수록 많이 건너뛴다. Redis는 각 층의 다음 노드 포인터마다 "이 링크가 원소 몇 개를 건너뛰는지(span)"를 함께 저장한다. 찾으려는 노드까지 위층부터 내려가며 지나온 링크의 span을 더하면 그 값이 곧 순위다. 탐색 경로 길이가 기대값 O(log N)이므로 순위 계산도 O(log N)이다. span이 없다면 노드를 찾은 뒤 앞쪽 원소 수를 세야 하므로 O(N)이 된다.

ZADD, ZREM이 O(log N)인 것도 같은 이유다. 삽입·삭제 때 경로상의 span만 고치면 된다.

### 함께 알아둘 명령의 복잡도
| 명령 | 복잡도 | 비고 |
|---|---|---|
| ZADD | O(log N) | 원소 하나당 |
| ZRANK / ZREVRANK | O(log N) | Redis 7.2부터 `WITHSCORE` 옵션으로 score도 함께 받는다 |
| ZSCORE | O(1) | dict 조회 |
| ZCARD | O(1) | |
| ZRANGE (index 기준) | O(log N + M) | M은 반환 개수 |
| ZPOPMIN count | O(log N × M) | M은 꺼내는 개수 |
| ZCOUNT | O(log N) | span 덕분에 범위 안 개수를 세지 않는다 |

## 이 프로젝트에서는
대기열을 Redis Sorted Set으로 두면(score = 진입 시각, member = 사용자 ID) "내 앞에 몇 명" 조회가 ZRANK 한 번이다. 대기자 100만 명이어도 skiplist 높이는 대략 log2(1,000,000) ≈ 20 수준이라 한 번의 호출은 충분히 빠르다.

주의할 점은 단건 비용이 아니라 총 호출량이다. Redis는 명령을 단일 스레드로 처리하므로 대기자 수 × 폴링 빈도가 그대로 초당 명령 수가 된다. 예를 들어 대기자 50만 명이 1초마다 순위를 조회하면 초당 50만 건의 ZRANK가 들어온다. 그래서 폴링 간격을 순위에 따라 늘리거나, 순위를 근사값으로 보여주는 방식을 함께 검토한다. 이 부분은 대기열 설계(SA MASTER v3)와 부하 테스트에서 수치로 확인할 항목이다(미확인).

## 면접 포인트
- ZRANK가 왜 O(log N)인가: skiplist 각 링크에 span을 저장해 탐색 중 누적하면 순위가 나온다. 노드를 찾고 나서 세는 방식이 아니다.
- 왜 balanced tree가 아니라 skiplist인가: 구현이 단순하고, 범위 조회(ZRANGE)에서 하위 층을 순서대로 따라가면 되며, 확률적 균형으로 회전 연산이 없다. antirez가 밝힌 이유도 구현 단순성과 범위 연산이다.
- 작은 Sorted Set은 왜 O(N)이어도 괜찮은가: N이 128 이하로 제한되고, 연속 메모리라 캐시 효율이 좋으며 메모리를 크게 아낀다.
- 대기열에서 병목은 어디인가: 단건 O(log N)보다 대기자 수 × 폴링 빈도로 생기는 총 명령 수다.

## 참고
- Redis 공식 문서 ZRANK: https://redis.io/docs/latest/commands/zrank/ (Time complexity: O(log(N)), `WITHSCORE`는 7.2.0부터)
- Redis 소스 `src/t_zset.c`의 `zslGetRank`, `zskiplistLevel.span`
- Redis 설정 `zset-max-listpack-entries`, `zset-max-listpack-value` (7.0부터 listpack 이름)

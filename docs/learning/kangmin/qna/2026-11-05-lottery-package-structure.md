---
title: 추첨의 application과 domain 패키지는 어떤 기준으로 나눌 수 있는가
date: 2026-11-05
updated: 2026-11-05
asker: GGangMY
level: 중급
tags: [architecture, package, ddd, layered]
---

# 추첨의 application과 domain 패키지는 어떤 기준으로 나눌 수 있는가

## 질문
application과 domain은 폴더 구분 없이 전부 나열되어 있는데, 구분한다면 어떤 기준으로 할 수 있고 어떻게 정리하면 되는가.

## 짧은 답
패키지를 나누는 기준은 파일 종류, 기능, 포트 분리 세 가지다. domain은 reservation·seathold·concert가 이미 쓰는 `entity`·`repository`로 맞추고 순수 규칙만 `policy`로 더하는 방안이 팀 구조와 가장 잘 맞는다. application은 파일 13개이고 다른 모듈도 모두 평면이라 지금은 나누지 않아도 된다.

## 자세히

### 기준 비교

| 기준 | 예시 | 장점 | 감수할 점 |
|---|---|---|---|
| 파일 종류 | `entity`, `repository`, `policy` | 팀 구조와 같고 찾기 쉽다 | 한 기능의 파일이 여러 폴더로 흩어진다 |
| 기능 | `draw`, `entry`, `assignment`, `succession` | 함께 바뀌는 파일이 모이고 package-private으로 경계를 지킬 수 있다 | `Lottery`처럼 여러 기능이 함께 쓰는 파일의 위치가 애매하다 |
| 포트 분리 | `port`, `command`, `result` | 외부 의존이 한눈에 보인다 | 파일 2개짜리 폴더가 생기고 다른 모듈과 달라진다 |

### domain 정리안

```text
domain/
├─ entity/      Lottery, LotteryEntry, LotteryStatus, LotteryEntryStatus
├─ repository/  LotteryRepository, LotteryEntryRepository
├─ policy/      DrawRanker, SeatAllocator, SuccessionPlanner,
│               DrawEntrant, DrawTimeWindow, LotterySeat
└─ LotteryErrorCode
```

`LotteryErrorCode`는 `Lottery`가 직접 참조하므로 domain에 남긴다. 이 구조로 옮겼고 테스트도 `domain/policy`로 함께 옮겼다.

### application 정리안

| 안 | 구성 | 판단 |
|---|---|---|
| 평면 유지 | 현재 그대로 | 추천. 13개라 찾기 어렵지 않고 다른 모듈과 같다 |
| 포트만 분리 | `port/`에 `LotterySeatPort`, `SystemHoldPort` | 외부 의존을 드러내고 싶을 때 |
| 기능별 분리 | `draw/`, `entry/`, `assignment/` 등 | 서비스가 늘어 평면이 불편해질 때 |

### 바꿀 조건
- application 파일이 20개 안팎으로 늘거나, 한 기능을 고칠 때 여러 파일을 계속 찾아다니게 되면 기능별 분리를 다시 검토한다.
- 기능별 패키지는 한 모듈만 적용하면 구조가 달라지므로 팀이 함께 정하는 편이 낫다.

## 이 프로젝트에서는
- reservation, seathold, concert의 domain은 `entity`·`repository`로 나뉘어 있고 lottery와 queue만 평면이다.
- 네 모듈의 application은 모두 평면이다.
- SA MASTER 13장은 계층만 정하고 계층 내부 구조는 정하지 않았다.
- `lottery.domain`을 import하는 파일은 26개(import 65줄)로, 테스트를 포함한 수치다. 같은 패키지라 import가 없던 domain 파일끼리의 참조는 나누면 더 늘어난다.
- application에는 `LotteryResult.of`, `UpdateLotteryCommand.mergeInto`, `TOPIC`, `EVENT_TYPE`, `MAX_PAYMENT_ATTEMPTS`처럼 접근 제어자가 없는 멤버가 있어, 패키지를 나누면 `public`으로 넓혀야 할 수 있다.

## 면접 포인트
- 계층별 패키지와 기능별 패키지의 차이: 전자는 구조를 파악하기 쉽고, 후자는 함께 바뀌는 코드가 모이며 package-private으로 캡슐화할 수 있다.
- 패키지를 나누는 시점: 파일 수 자체보다 변경할 때 불편한지, 경계를 강제할 필요가 있는지로 판단한다.

## 참고
- `docs/core/SA MASTER.md` 13장
- 파일 목록과 import 수는 grep으로 확인했다.
- 옮긴 뒤 `reservation-service` 테스트 338개(추첨 107개)가 모두 통과했다.

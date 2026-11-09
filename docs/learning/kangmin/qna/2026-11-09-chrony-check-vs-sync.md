---
title: timedatectl·chronyc tracking 실행은 시계를 맞춘 것인가, 상태만 본 것인가
date: 2026-11-09
updated: 2026-11-09
asker: kangmin
level: 기초
tags: [ntp, chrony, time-sync, aws, timedatectl]
---

# timedatectl·chronyc tracking 실행은 시계를 맞춘 것인가, 상태만 본 것인가

## 질문
배포 서버에서 `timedatectl`과 `chronyc tracking`을 실행한 것은 시각을 확인만 한 것인가, 동기화까지 처리한 것인가.

## 짧은 답
- 두 명령은 상태를 읽기만 한다. 우리가 시계를 맞춘 것은 없다.
- 동기화는 이미 서버 안에서 `chronyd`(NTP 데몬)가 알아서 하고 있었다. 명령 결과가 그 사실을 보여 준다.
- 그래서 할 일은 "맞추기"가 아니라 "맞춰져 있다는 근거를 남기기"다.

## 자세히

### 누가 시계를 맞추나
| 구성 요소 | 하는 일 |
|---|---|
| `chronyd` | 백그라운드에서 계속 돌며 시간 서버에 물어보고 시계를 조금씩 고친다. Ubuntu 24.04 AWS 이미지에 기본으로 켜져 있다 |
| Amazon Time Sync Service `169.254.169.123` | 각 EC2가 내부 주소로 닿는 AWS 시간 서버. 설정 없이 기본 기준으로 쓰인다 |
| `timedatectl` | 시간대, 동기화 여부, NTP 서비스 켜짐 여부를 보여 준다(읽기) |
| `chronyc tracking` | `chronyd`가 지금 어느 서버를 기준으로 얼마나 맞췄는지 보여 준다(읽기) |

### 결과에서 "자동 동기화 중"을 읽는 줄
- `System clock synchronized: yes`, `NTP service: active`: 데몬이 켜져 있고 동기화된 상태다.
- `Reference ID ... (169.254.169.123)`: 기준이 AWS 시간 서버다.
- `Update interval: 16.2 seconds`: 약 16초마다 다시 맞추고 있다.
- `System time: 0.000000126 seconds fast`: 지금 오차가 약 0.13마이크로초다.

### 직접 맞춰야 하는 경우
- `synchronized: no`이거나 `NTP service: inactive`이거나 오차가 밀리초 단위로 크면 그때 손댄다.
- 손대는 방법은 `chronyd` 설정이나 재시작이다. 서버 설정 변경이라 계정 담당과 상의한다.
- 지금 배포 서버는 해당하지 않는다.

### 이 프로젝트에서 의미
- 튜터 NTP 이슈(서버 간 시각이 어긋나면 마감·순서 판단이 틀어질 수 있다)에 대한 근거가 된다.
- 발생기도 같은 두 명령으로 값을 남겨, 발생기와 배포 서버가 같은 기준(AWS 시간 서버)으로 1마이크로초 단위 안에 맞는지 나란히 보인다.

## 관련
- [튜터 세 이슈(NTP·레이트 리미터·벌크헤드)](2026-11-09-tutor-issues-ntp-ratelimiter-bulkhead.md)

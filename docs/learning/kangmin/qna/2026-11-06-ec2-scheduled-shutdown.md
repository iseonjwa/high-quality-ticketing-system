---
title: 떠 있는 EC2를 셸 명령으로 2시간 뒤에 자동으로 내릴 수 있는가
date: 2026-11-06
updated: 2026-11-06
asker: GGangMY
level: 기초
tags: [aws, ec2, linux, shutdown, 비용]
---

# 떠 있는 EC2를 셸 명령으로 2시간 뒤에 자동으로 내릴 수 있는가

## 질문
지금 AWS 이미 띄워져있는데, sh로 시간 걸어서 2시간 뒤에 내려가게 할 수 있나?

## 짧은 답
할 수 있다. 서버 안에서 `sudo shutdown -h +120`을 실행하면 2시간 뒤 OS가 종료되고, 인스턴스의 종료 동작이 기본값 `stop`이면 EC2는 중지 상태가 된다. 예약은 서버 쪽에 남으므로 내 PC를 꺼도 실행된다. 종료 동작이 `terminate`면 인스턴스가 삭제되므로 먼저 콘솔에서 확인한다. 예약은 서버마다 걸어야 한다.

## 자세히

### 선택지 비교

| 방법 | 내 PC를 꺼도 되는가 | EC2 과금 | 필요한 것 | 약점 |
|---|---|---|---|---|
| 서버에서 `shutdown -h +120` | 된다 | 컴퓨팅 과금 멈춤 | SSH, sudo | 서버마다 걸어야 한다 |
| PC에서 `sleep 7200 && aws ec2 stop-instances` | 안 된다 | 컴퓨팅 과금 멈춤 | AWS CLI와 권한 | PC가 절전되거나 터미널이 닫히면 실행되지 않는다 |
| 서버에서 `docker compose down` 예약 | 된다 | 계속 과금 | SSH | 앱만 내려가고 EC2 비용은 그대로다 |
| EventBridge Scheduler로 StopInstances | 된다 | 컴퓨팅 과금 멈춤 | AWS 설정과 IAM 역할 | 일회성 작업에는 설정이 많다 |

비용을 멈추는 것이 목적이고 일회성 작업이면 `shutdown` 예약이 가장 단순하다.

### 서버 끄기, 중지, 종료의 차이
"종료"라는 말이 OS와 AWS에서 다른 뜻으로 쓰여 헷갈리기 쉽다. OS의 `shutdown`은 전원을 끄는 명령이고, AWS 콘솔의 "인스턴스 종료"(Terminate)는 인스턴스를 삭제하는 작업이다. 설정 이름 "종료 동작"(shutdown behavior)의 "종료"는 Terminate가 아니라 OS `shutdown`을 가리킨다. 이 설정은 OS를 껐을 때 인스턴스를 중지할지 삭제할지 정한다.

| 구분 | 중지 (Stop) | 종료 (Terminate) |
|---|---|---|
| 비유 | 컴퓨터 전원을 끈다 | 컴퓨터를 폐기한다 |
| 다시 시작 | 가능 | 불가능 |
| 디스크(EBS) 데이터 | 남는다 | `DeleteOnTermination`이 켜진 볼륨은 삭제된다 |
| 컴퓨팅 비용 | 멈춘다 | 멈춘다 |
| 남는 비용 | EBS, 탄력적 IP | 남겨 둔 볼륨과 해제하지 않은 탄력적 IP |

OS 안에서 `shutdown`을 실행했을 때 둘 중 어느 쪽이 되는지는 `InstanceInitiatedShutdownBehavior` 값이 정한다.

### 종료 동작 설정을 확인하는 법
종료 동작은 인스턴스마다 있는 속성이다. 콘솔의 "중지" 버튼이나 `stop-instances` API처럼 AWS 쪽에서 내리는 요청에는 적용되지 않고, 인스턴스 안에서 OS를 끌 때만 적용된다.

- 콘솔: EC2 → 인스턴스 → 대상 선택 → 작업 → 인스턴스 설정 → Change shutdown behavior
- CLI:
  ```bash
  aws ec2 describe-instance-attribute --instance-id <인스턴스 ID> --attribute instanceInitiatedShutdownBehavior
  ```
- 서버 안의 인스턴스 메타데이터로는 확인할 수 없다고 보고 콘솔이나 CLI를 쓴다(메타데이터 항목은 직접 확인하지 못했다).

"종료 방지"(`DisableApiTermination`)와는 다른 설정이다. 종료 방지를 켜도 종료 동작이 `terminate`이면 OS 안의 `shutdown`으로 인스턴스가 삭제된다.

### 명령
```bash
# 2시간 뒤 종료 예약
sudo shutdown -h +120

# 예약 확인
cat /run/systemd/shutdown/scheduled

# 예약 취소
sudo shutdown -c
```

### 주의할 점
- **종료 동작**: OS 안에서 종료할 때 EC2가 어떻게 될지는 인스턴스 속성 `InstanceInitiatedShutdownBehavior`가 정한다. 기본값은 `stop`이고, `terminate`면 인스턴스가 삭제된다.
- **중지 후 남는 비용**: 중지하면 컴퓨팅 과금은 멈추지만 EBS 볼륨과 탄력적 IP 비용은 계속 나간다.
- **접속 제한**: systemd는 종료 5분 전부터 일반 사용자의 새 로그인을 막는다. 연장하거나 취소하려면 그 전에 한다.
- **다른 사용자**: 공유 서버면 `who`로 접속자를 확인하고 미리 알린다.

## 이 프로젝트에서는
- 대상은 배포 서버(`52.63.10.174`)와 Jenkins 서버 두 대다. 서로 다른 인스턴스라 각각 예약해야 비용이 함께 멈춘다. 서버 정보와 운영 일정은 [`docs/guides/deploy-server-access.md`](../../../guides/deploy-server-access.md)에 있다.
- 배포 서버는 탄력적 IP라 다시 켜도 주소가 같고, Jenkins 서버는 켤 때마다 주소가 바뀐다.
- 2026-11-06 기준으로 이 저장소에는 EC2를 자동으로 끄는 스크립트가 없고, `script/deploy/`에도 종료 명령이 없다.

## 면접 포인트
- EC2 안에서 `shutdown`을 실행하면 중지인가 종료(삭제)인가: 인스턴스 속성 `InstanceInitiatedShutdownBehavior`에 따라 다르고 기본값은 중지다.
- 중지와 종료의 비용 차이: 중지는 컴퓨팅 과금만 멈추고 EBS와 탄력적 IP 비용은 남는다. 종료는 인스턴스가 삭제되고, `DeleteOnTermination` 설정이 켜진 볼륨도 함께 지워진다.

## 참고
- AWS 문서: Change the instance initiated shutdown behavior
- `man shutdown` (systemd)
- 이 답은 저장소 문서와 스크립트를 확인해 작성했다. 실제 서버에서 명령을 실행하지 않았고, 두 서버의 종료 동작 값과 Ubuntu 버전은 확인하지 못했다(로컬에 AWS CLI 없음).

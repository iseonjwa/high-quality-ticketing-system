---
title: 배포 서버 앱의 GC는 리허설(11/6)까지 Serial로 두고, 다음 주 부하 측정 전에 G1을 명시하기로 함
date: 2026-11-05
updated: 2026-11-05
judge: Junhyun An
ai: Claude Code
verdict: 수용
area: 기타
presentation: 아님
shared: 예
---

# 배포 서버 앱의 GC는 리허설(11/6)까지 Serial로 두고, 다음 주 부하 측정 전에 G1을 명시하기로 함

## 맥락
- 해결하려던 작업: 좌석 선점 담당(최권우)이 ISE-79 측정에서 Serial GC를 G1으로 바꿔 효과를 확인했다. 결과는 `docs/design/reservation/k6-seathold-bottleneck-plan.md` 5-3절에 있고, 11/5 기준 브랜치 `feat/ISE-79-seat-hold-race-condition`에만 있다.
  - GC 정지 합계: 약 241초 → 81.8초
  - 오류 없이 버틴 속도: 초당 5명 → 10명
- 팀원 문서는 배포 설정(`compose.deploy.yml`)에 G1을 넣을지를 배포 담당과 contract 이슈로 정하기로 남겼다.
- AI 활용 방식: 사용자가 "팀원들이 가비지 콜렉터를 교체해봤다는데 해당 내용에 관해 좀 알려줄 수 있어?"라고 물었다.

## AI 제안
- 배포 서버(`m7i.xlarge`)에서 확인한 결과, 앱 7개가 모두 Serial GC로 돌고 있다.
  - Prometheus `jvm_gc_pause_seconds_count`의 `gc` 라벨이 `Copy`·`MarkSweepCompact`였다.
  - reservation-service의 `PrintFlagsFinal`이 `UseSerialGC = true {ergonomic}`였다.
  - 원인: 컨테이너 상한이 모두 약 1,792MB보다 작아 JVM이 Serial을 자동으로 골랐다.
- 리허설(11/6)에는 그대로 둔다. 트래픽이 작아 Serial의 문제가 드러날 일이 적고, 전날 JVM 설정을 바꾸는 위험이 더 크다.
- 다음 주 배포 서버 부하 측정(ISE-176·181) 전에 contract 이슈로 알리고, `compose.deploy.yml` 앱 7개의 `JAVA_TOOL_OPTIONS`에 `-XX:+UseG1GC`를 명시하는 PR을 낸다.
  - 이유: 모든 요청이 지나는 gateway가 Serial로 멈추면 요청 전체가 같이 멈춘다.
- 팀원 측정은 16코어 노트북에서 했다. 4 vCPU 서버에서 다시 재고 `docker stats`로 메모리 상한을 확인한다.

## 판단
- 결과: 수용
- 판단자: Junhyun An
- 근거: 미기재("그렇게 하자. 리허설 때는 그냥 두고 이번 다음 주에 그 지원으로 바꾸자")
- 검토한 대안
  - 리허설 전 바로 바꾸기: 전날 변경 위험에 비해 얻는 것이 작다.
  - reservation-service만 바꾸기: 입구인 gateway의 정지가 남는다.
  - 계속 Serial 유지: 측정 환경과 배포 환경의 GC가 달라진다.

## 반영
- `docs/design/infra/deployment.md` 7절 결정 표에 "JVM GC" 행을 더했다.
- contract 이슈와 PR은 리허설 뒤 다음 주(11/9~)에 만든다. 미반영.
- 서버는 11/7~11/12에 멈추므로, 서버 반영은 11/13 재가동 뒤 첫 릴리스에서 한다.

## 검증
구현 전. 반영 뒤 확인할 것:
- Prometheus `gc` 라벨이 `G1 Young Generation`·`G1 Old Generation` 등으로 바뀌었는지
- `docker stats`로 컨테이너 메모리가 상한 안에 있는지
- 스모크 통과 여부

## 판단 이력
- 2026-11-05 수용: 리허설까지 Serial 유지, 다음 주 G1 명시

## 발표·면접 포인트
- 컨테이너에서는 JVM이 상한 메모리를 보고 GC를 자동으로 고르므로, 의도하지 않은 Serial GC가 배포 환경에 숨어 있을 수 있다. 실제로 무엇이 골라졌는지 지표 라벨로 확인한 뒤 명시하기로 했다.
- 효과가 확인된 개선이라도 시연 직전에는 넣지 않고, 위험이 작은 시점(측정 전)으로 미뤘다.

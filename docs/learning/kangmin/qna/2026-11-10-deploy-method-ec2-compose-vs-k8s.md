---
title: 배포는 무엇으로 하나, 쿠버네티스가 아니라 EC2에 그냥 띄우는 것인가
date: 2026-11-10
updated: 2026-11-10
asker: kangmin
level: 기초
tags: [deploy, ec2, docker-compose, kubernetes, ecs, jenkins, github-actions]
---

# 배포는 무엇으로 하나, 쿠버네티스가 아니라 EC2에 그냥 띄우는 것인가

## 질문
뭘로 배포하나? 쿠버네티스 같은 질문이 나왔는데, 지금은 그냥 EC2로 띄우는 건가.

## 짧은 답
- 쿠버네티스는 쓰지 않는다. **EC2 한 대에서 Docker Compose로** 컨테이너 21개를 띄운다(`compose.deploy.yml`).
- 배포는 자동이다. dev → main 머지 → GitHub Actions가 이미지 9개를 만들어 GHCR에 올림 → 별도 Jenkins EC2가 main을 2분마다 폴링 → 배포 EC2에 SSH로 `script/deploy/deploy.sh` 실행(`docker compose pull`, `up -d`) → 스모크 4개 → 슬랙 알림. 실패하면 직전 성공 SHA로 되돌린다.
- 결정은 ADR 0044(배포 구조), ADR 0047(EC2 한 대로 시작)에 있다.

## 선택지 비교
| 선택지 | 얻는 것 | 치르는 것 |
|---|---|---|
| EC2 + Docker Compose(지금) | 로컬 compose와 거의 같은 파일로 운영. 구성·비용·학습이 가장 작다 | 단일 장애점. 자동 복구·수평 확장·무중단 배포가 없다. 앱과 DB가 자원을 나눠 쓴다 |
| ECS(Fargate 등) | AWS가 컨테이너 배치·재시작을 맡는다. 서비스별 확장 | 작업 정의·로드 밸런서·네트워크 구성이 늘고 비용이 오른다 |
| EKS / 쿠버네티스 | 자동 복구, HPA 확장, 롤링 배포, 서비스 메시까지 표준 | 클러스터 자체 운영 비용과 학습량이 크다. 컨트롤 플레인 요금만 따로 붙는다 |

## 고른 이유
- 기간(11/19 종료)과 인원에 비해 쿠버네티스 운영 비용이 크다. 프로젝트의 핵심은 오케스트레이션이 아니라 대기열·좌석 선점·SAGA가 폭주 트래픽을 견디는지다.
- 로컬 compose와 배포 compose가 같은 모양이라, 로컬 측정과 서버 측정의 차이를 줄이기 쉽다.
- 팀 학습 노트도 "EC2 + compose는 운영 정석이라기보다 이 규모·기간에 맞는 가장 단순한 선택"이고, 운영 정석에 가까운 모양은 ECS·EKS + RDS·MSK라고 정리했다(`docs/learning/junhyun/qna/2026-11-05-aws-managed-https-options.md`).

## 감수한 점과 바꿀 조건
- 감수: 서버 한 대가 죽으면 전부 멈춘다. 확장은 인스턴스를 키우는 수직 확장뿐이다. 배포 중 컨테이너 교체 구간에 짧은 중단이 생길 수 있다.
- 바꿀 조건: 무중단 배포나 서비스별 수평 확장이 요구될 때, 여러 대로 나눠야 할 만큼 부하가 커질 때. 그때는 상태 저장소(DB·Redis·Kafka)를 먼저 매니지드로 빼고(ADR 0047 결정 3), 앱을 ECS나 EKS로 옮기는 순서가 자연스럽다.

## 발표 예상 질문용 한 문장
"쿠버네티스 대신 EC2 한 대에 Docker Compose로 배포했고, CI(GitHub Actions)와 배포(Jenkins)를 나눠 커밋 SHA 단위로 배포·되돌리기를 자동화했습니다. 기간과 규모에 맞춰 오케스트레이션보다 트래픽 처리 로직에 집중하려는 선택이었고, 대가는 단일 장애점과 수평 확장 부재입니다."

## 근거
- `docs/design/infra/deployment.md` 1, 2절
- `compose.deploy.yml`
- 2026-11-10 배포 서버 `docker ps`: 실행 중 컨테이너 21개

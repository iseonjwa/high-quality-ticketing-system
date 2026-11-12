---
title: 시스템 구조도의 "EC2 컨테이너 분리"를 컨테이너별 메모리 상한 표시로 읽은 AI 해석을 바로잡고, Jenkins EC2와 부하 발생기 EC2를 그림에 넣게 함
date: 2026-11-11
updated: 2026-11-11
judge: Junhyun An
ai: Claude Code
verdict: 거부
area: 기타
presentation: 아님
shared: 아니오
---

# 시스템 구조도의 "EC2 컨테이너 분리"를 컨테이너별 메모리 상한 표시로 읽은 AI 해석을 바로잡고, Jenkins EC2와 부하 발생기 EC2를 그림에 넣게 함

## 맥락
- 해결하려던 작업: 발표 자료의 기준 그림인 [시스템 구조도](../../presentation/architecture/2026-11-11-system-architecture.svg)에 스택 아이콘과 "EC2 컨테이너 분리된 것"을 반영하는 일이었다.
- AI 활용 방식: 사용자가 md 뷰어 코멘트와 대화로 "각 스택을 대표하는 아이콘을 달아주고, EC2 컨테이너 분리된 것도 반영해 줄래?"라고 요청했다.

## AI 제안
AI는 "EC2 컨테이너 분리"를 배포 서버 EC2 한 대 안에서 서비스마다 컨테이너를 따로 띄우고 `mem_limit`을 건 구조로 해석했다. 그래서 EC2 경계를 그리고 상자마다 컨테이너 이름과 메모리 상한(`compose.deploy.yml`의 `mem_limit`)을 표시하겠다고 알리고, 메모리 상한 값을 뽑기 시작했다.

## 판단
- 결과: 거부(AI 해석을 바로잡음)
- 판단자: Junhyun An
- 근거: "아 Jenkins랑 부하테스트용 ec2까지 넣는걸 말한거였어". 뜻은 배포 서버와 따로 띄운 EC2(Jenkins, 부하 발생기)까지 그림에 넣으라는 것이었다.
- 검토한 대안: AI 해석(컨테이너별 메모리 상한 표시). 그리지 않았다.

## 반영
- 시스템 구조도를 다시 그렸다. 배포 서버 EC2를 경계로 묶고, 경계 밖에 부하 발생기 EC2(c6i.xlarge, k6 → Gateway `:8080` 사설 IP, 지표는 Prometheus `:9090`), Jenkins EC2(t3.small, GitHub `main` 2분 폴링, SSH `deploy.sh`), GitHub(Actions·GHCR, 배포 서버가 이미지를 받음)를 두었다. 사실은 [`deployment.md`](../../design/infra/deployment.md) 1절과 [`loadgen.md`](../../guides/loadgen.md) 1절에서 옮겼다.
- 메모리 상한 표시는 넣지 않았다.
- [다이어그램 스타일](../../presentation/brand/diagram-style.md)에 서버 경계 규칙(한 서버 안 컨테이너는 경계로 묶고, 한 가지 일만 하는 EC2는 일반 상자에 EC2 기호)을 더했다.

## 검증
- PNG를 다시 내보내 글자 겹침 0건(헤드리스 Chrome으로 글자 상자를 잼), 칩 오른쪽 여백 12px 맞춤을 확인했다.
- 서버 유형과 연결은 위 문서 기준이고, 서버에 접속해 다시 확인하지 않았다.

## 판단 이력
- 2026-11-11 거부: "EC2 컨테이너 분리"는 컨테이너 메모리 상한이 아니라 Jenkins·부하 발생기 EC2까지 넣으라는 뜻이었다

---
title: 주소에 ?demo=on을 붙이면 보이는 요청 로그 패널은 어떻게 쓰나
date: 2026-11-05
updated: 2026-11-05
asker: Junhyun An
level: 기초
tags: [frontend, demo, observability, traceid, grafana]
---

# 주소에 ?demo=on을 붙이면 보이는 요청 로그 패널은 어떻게 쓰나

## 질문
주소에 무언가를 넣으면 옆에 보낸 API 목록이 보이도록 만들었던데, 어떻게 쓰나.

## 짧은 답
- **켜기**: 주소 끝에 `?demo=on`을 붙인다. 예: `http://52.63.10.174/?demo=on`, `http://52.63.10.174/concerts/1?demo=on`
  - 화면 오른쪽에 "요청 로그" 패널이 열린다.
  - 그 탭에서 보낸 API 요청이 최신순으로 쌓인다. 한 줄에 메서드, 경로, 상태, 걸린 시간, traceId가 보인다.
- **끄기**: `?demo=off`를 붙이거나 패널의 X(시연 모드 끄기)를 누른다.
- **traceId 누르기**: Grafana "요청 로그" 대시보드가 열린다. 그 요청이 Gateway와 서비스들을 지나며 남긴 로그를 한 화면에서 본다.
- 만든 작업은 ISE-180이다. 규칙은 `docs/design/frontend/README.md` "시연 모드 규칙"에 있다.

## 자세히

### 1. 켜고 끄는 범위
- `?demo=on`은 **그 브라우저 탭에서만** 켜진다.
  - 값은 탭 단위 저장소(sessionStorage)에 둔다. 그래서 새로고침해도 유지되고, 탭을 닫으면 사라진다.
  - 다른 사람이나 다른 탭에는 보이지 않는다. 기본은 꺼져 있다.
- 적용한 뒤에는 주소에서 `?demo=on`을 지운다. 그래서 화면을 공유해도 주소가 깔끔하다.
- 요청 기록은 메모리에만 둔다.
  - 최근 50건까지 남는다.
  - 새로고침하거나 시연 모드를 끄면 비워진다. 패널의 "기록 비우기"로도 비운다.
- 패널 조작
  - 접기·펼치기를 할 수 있다.
  - 왼쪽 경계를 끌어 너비를 바꾸고, 두 번 누르면 기본 너비로 돌아간다.
  - traceId 옆 버튼으로 traceId를 복사한다.

### 2. traceId는 어디서 오나
- 백엔드 공통 응답에는 `traceId`가 들어 있다. 프론트의 요청 함수(`frontend/src/api/transport.ts`)가 응답 본문에서 이 값을 읽어 패널에 남긴다.
  ```json
  {"status": 200, "message": "공연 목록을 조회했습니다", "data": [...], "traceId": "6aec1c7a13f72a055b1e4142366a7df3"}
  ```
  위는 2026-11-05 배포 서버 `GET /api/v1/concerts` 응답에서 확인한 모양이다.
- 같은 traceId가 Gateway, 서비스, Kafka 소비자의 로그에 함께 찍힌다(공통 요청 로그 필터, ISE-182). 그래서 이 값 하나로 요청 하나가 지나간 길을 모두 찾는다.

### 3. Grafana로 이어 보기
- traceId를 누르면 이 주소가 열린다.
  - `http://<화면을 연 호스트>:3000/d/iseonjwa-request-logs?var-traceId=<traceId>&from=<요청 시각-15분>&to=<요청 시각+15분>`
  - 배포 서버에서 열었으면 `http://52.63.10.174:3000/...`, 로컬이면 `http://localhost:3000/...`이다.
  - 앞뒤 15분은 브라우저와 서버의 시계 차이, 요청 뒤 Kafka 소비자가 늦게 남긴 로그까지 넣으려는 것이다.
- 배포 서버 Grafana는 3000을 팀원 IP에만 연다(보안 그룹). 익명 보기(Viewer)라 로그인 없이 대시보드가 열린다. 등록되지 않은 IP에서는 열리지 않는다.
- 처음에는 기본 주소가 `http://localhost:3000`으로 고정돼 있어 SSH 터널이 필요했다. 배포 서버에서 링크가 localhost로 가는 문제를 찾아 2026-11-05에 바꿨다(PR #344, [판단 기록](../../../ai-judgments/junhyun/2026-11-05-grafana-link-deploy-host.md)).
- 처음 한 번만 새 탭이 열리고, 다음부터는 같은 Grafana 탭에서 바뀐다. 처음 열린 탭을 옆 창으로 옮겨 두면 앱 화면과 나란히 보며 시연할 수 있다.

### 4. 시연에서 쓰는 흐름
1. 발표자 노트북의 IP가 보안 그룹 3000 규칙에 있는지 확인한다(안 열리면 담당에게 IP를 보낸다).
2. `http://52.63.10.174/?demo=on`으로 앱을 열고, 처음 열린 Grafana 탭을 옆 창에 둔다.
3. 좌석을 고르거나 결제하면 패널에 `POST /api/v1/...` 같은 요청이 쌓인다.
4. 보고 싶은 요청의 traceId를 누르면 옆 창 Grafana에 그 요청의 서비스별 로그가 뜬다.
5. 끝나면 `?demo=off`나 X로 끈다.

## 면접 포인트
- **분산 추적(distributed tracing)**: MSA에서는 요청 하나가 여러 서비스를 지나므로, 요청마다 같은 traceId를 붙여 로그를 이어 본다. 이 프로젝트는 그 traceId를 응답 본문에도 실어 프론트에서 바로 찾게 했다.
- **시연 기능을 운영 화면에 섞는 방법**: 기본은 꺼 두고, 주소 파라미터로 그 탭에서만 켠다. 다른 사용자에게는 영향이 없고 빌드도 따로 하지 않는다.

## 관련 문서
- `frontend/README.md`(시연 모드), `docs/design/frontend/README.md` "시연 모드 규칙", `docs/design/infra/observability.md`(요청 로그 대시보드)
- [팀원 SSH 접속(터널)](2026-11-05-team-ssh-access-to-deploy-server.md), [외부 접속과 HTTPS](2026-11-05-why-front-unreachable-by-ip.md)

---
title: Grafana Alloy는 무엇이고 왜 쓰나
date: 2026-11-04
updated: 2026-11-04
asker: Junhyun An
level: 기초
tags: [grafana-alloy, loki, promtail, opentelemetry, observability, logging]
---

# Grafana Alloy는 무엇이고 왜 쓰나

## 질문
ISE-178에서 Loki와 함께 넣은 "Alloy"도 도구 이름인가. 개인 워크스페이스 이름(`alloy`)과 같아서 헷갈렸다.

## 짧은 답
Grafana Alloy는 Grafana Labs가 만든 오픈소스 수집기(collector)다. 개인 워크스페이스 이름과는 관계없다. 서버에서 로그·지표·트레이스를 모아 가공한 뒤 저장소로 보낸다. 이 프로젝트에서는 앱 컨테이너 로그를 Loki로 보내는 일을 맡는다.

## 자세히

### 1. 수집기와 저장소를 나누는 이유
- 관측은 "모으기"와 "저장·조회"로 나뉜다. 저장소(Loki, Prometheus, Zipkin)는 받은 데이터를 저장하고 질의에 답한다. 수집기(Alloy)는 데이터가 생기는 곳에서 읽어 저장소 형식에 맞춰 보낸다.
- 나누면 앱이 저장소를 몰라도 된다. 앱은 표준 출력에 로그를 쓰기만 하고, 어디로 보낼지·무엇을 붙일지는 수집기 설정에서 바꾼다.

### 2. Alloy가 하는 일
- **찾기(discovery)**: Docker 소켓으로 어떤 컨테이너가 떠 있는지 찾는다.
- **읽기(source)**: 그 컨테이너들의 로그를 읽는다.
- **가공(process)**: 여러 줄 병합, 정규식으로 값 뽑기, 라벨·메타데이터 붙이기.
- **보내기(write)**: Loki의 push API로 보낸다.
- 설정 파일(`.alloy`)은 이 단계를 블록으로 쓰고, 앞 블록의 출력을 다음 블록의 입력으로 잇는다.

### 3. Promtail과의 관계
- Loki용 수집기로 예전에는 Promtail을 많이 썼다. Grafana는 Promtail을 지원 종료 예정으로 돌리고 Alloy로 옮기라고 안내한다.
- Alloy는 OpenTelemetry Collector 기반이라 로그뿐 아니라 지표·트레이스도 같은 도구로 모을 수 있다. 수집기를 종류별로 따로 둘 필요가 줄어든다.

## 이 프로젝트에서는
- 설계: ADR 0045, `docs/design/infra/observability.md` 1절. 구현: ISE-178(`infra/alloy/config.alloy`, compose `alloy` 서비스).
- 앱 컨테이너 7개만 모은다. 예외 스택 트레이스를 한 항목으로 묶고, `service`·`level`·`env` 라벨과 `traceId` 구조화 메타데이터를 붙인다.
- 관측 도구 역할: Prometheus(지표 수집·저장), Alertmanager(알림 전달), Alloy(로그 수집), Loki(로그 저장), Zipkin(트레이스 저장), Grafana(화면).

## 면접 포인트
- 앱은 표준 출력에 로그를 쓰고 수집기가 모아 보내는 구조라서, 저장소를 바꾸거나 라벨을 더할 때 앱 코드를 고치지 않는다.
- 로그 라벨은 값 종류가 적은 것(서비스, 레벨)만 두고, 요청마다 달라지는 값(traceId)은 라벨로 두지 않는다. 라벨 조합마다 색인이 생기기 때문이다.

## 참고
- [Grafana Alloy 문서](https://grafana.com/docs/alloy/latest/)
- [Migrate from Promtail to Grafana Alloy](https://grafana.com/docs/alloy/latest/set-up/migrate/from-promtail/)

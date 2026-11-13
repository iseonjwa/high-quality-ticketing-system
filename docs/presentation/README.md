# 발표 자료

최종 발표(11/19)와 시연에 쓸 그림, 스크린샷, 발표용 요약을 주제별 폴더에 모은다. 제출 마감은 11/18이다([마일스톤](../core/milestones.md)). 내용의 정본은 설계 문서, ADR, 트러블슈팅, 판단 기록이다. 여기에는 그 내용을 발표 한 장으로 줄인 요약과 그림 파일을 두고 정본을 링크한다.

발제 필수 산출물의 상태와 남은 일, 팀원별·도구별 작업 방법은 [required-deliverables.md](required-deliverables.md)에 있다.

## 폴더

표 순서는 발표 순서의 가안이다. 순서는 발표 준비(11/16~) 때 팀이 정한다.

| 폴더 | 발표에서 맡는 부분 | 정본 |
|---|---|---|
| [`overview/`](overview/README.md) | 프로젝트 소개, 해결하려는 문제, 목표 수치, 팀 담당 | [SA](../core/SA%20MASTER.md) 1~3장, 6장 |
| [`architecture/`](architecture/README.md) | 시스템 구조도, 서비스 경계, 일반 예매·추첨 흐름, 예매↔결제 SAGA, 상태 머신 | SA 7~8장, [`design/`](../design/) |
| [`considerations/`](considerations/README.md) | 주요하게 고려한 점. 문제, 선택지, 고른 것과 이유, 결과 수치 | [`decisions/`](../decisions/README.md) |
| [`deployment/`](deployment/README.md) | 배포 서버 구성도, CI/CD 흐름, 관측(로그·지표·알림) 구성 | SA 9장, [`design/infra/`](../design/infra/README.md) |
| [`performance/`](performance/README.md) | 부하 측정 그래프, 개선 전후 비교 수치, Grafana 캡처 | [`design/infra/load-test.md`](../design/infra/load-test.md), [`test-scenarios/runs/`](../test-scenarios/README.md) |
| [`troubleshooting/`](troubleshooting/README.md) | 트러블슈팅 대표 사례 | [`troubleshooting.md`](../troubleshooting.md) |
| [`ai-verification/`](ai-verification/README.md) | AI 설계 검증 대표 사례(발제 필수, 2건 이상) | [`ai-judgments/`](../ai-judgments/README.md) |
| [`demo/`](demo/README.md) | 시연 순서, 시연 화면 스크린샷과 녹화 | [`design/demo/`](../design/demo/2026-11-06-mvp-scenarios.md), [`guides/demo-scenarios.md`](../guides/demo-scenarios.md) |
| [`brand/`](brand/README.md) | 아이콘, 로고, 그림에 함께 쓸 색 | [`design/frontend/design-system.md`](../design/frontend/design-system.md) |

어느 폴더에도 맞지 않는 자료가 생기면 위 표에 한 줄을 더한 뒤 폴더를 만든다.

폴더를 나누기 전에 올라온 자료는 다른 문서가 경로로 가리키고 있어 그 자리에 둔다.

| 날짜 | 파일 | 내용 |
|---|---|---|
| 2026-11-12 | [2026-11-12-gateway-overload-source.md](2026-11-12-gateway-overload-source.md) | Gateway 과부하 개선(11/9~12) 발표 소스. 장면 10개마다 핵심 메시지·숫자·그래프 데이터·발표자 메모·출처, 용어, 예상 질문, 숫자 사용 주의(같은 날 비교만 %). 발표 자료는 이 소스를 가공해 만든다. 성능·트러블슈팅 장면의 재료다 |

## 규칙

- 파일은 주제에 맞는 폴더에 `YYYY-MM-DD-<내용>.<확장자>`로 두고, 그 폴더 README 표에 한 줄을 더한다. 그림(PNG)은 같은 README의 "미리보기"에도 넣어 GitHub와 md 뷰어에서 바로 보이게 한다.
- 그림은 고칠 수 있는 원본(Mermaid `.mmd`, draw.io `.drawio`, Excalidraw `.excalidraw`, `.svg`, `.html`)과 슬라이드에 붙일 내보낸 파일(`.png`)을 같은 이름으로 함께 둔다.
- 다이어그램은 [다이어그램 스타일](brand/diagram-style.md)을 따른다. 기준 그림은 [시스템 구조도](architecture/2026-11-11-system-architecture.svg)이고, SVG는 `script/render-diagram.sh`로 PNG를 만든다.
- 그림 속 서비스 이름, 포트, 수치는 정본 문서와 맞춘다. 정본이 바뀌면 그림을 다시 내보내고 표의 날짜를 고친다.
- 발표용 요약(`.md`)은 정본을 다시 쓰지 않는다. 한 장 분량으로 줄이고 정본을 링크한다.
- 비밀값이 보이는 화면(토큰, 웹훅 주소, `.env`, 실제 비밀번호)은 가린 뒤 올린다.
- 한 파일이 수 MB를 넘으면 줄여서 올린다. 녹화는 GIF보다 MP4가 작다.

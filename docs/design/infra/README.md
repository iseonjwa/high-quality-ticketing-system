# 배포·관측·부하 측정 설계 (infra)

EC2 배포 파이프라인, 로그·알림 관측, 부하 측정 환경의 설계를 모은다. 사용자(안준현)가 2026-11-03에 정한 내용을 옮긴 것이고, 정하지 않은 값은 `[가정]`이나 "미정"으로 표시했다. 설계 정본은 [SA MASTER](../../core/SA%20MASTER.md) 3-1·9장이며, 여기서 달라지거나 더해지는 부분은 각 문서 끝의 "SA와 다른 점/더한 점"에 모았다.

| 문서 | 내용 |
|---|---|
| [deployment.md](deployment.md) | EC2 한 대 배포 구조, GitHub Actions·GHCR·Jenkins 파이프라인, 보안그룹, 비밀값, 되돌림, 인스턴스 크기 결정 방법 |
| [observability.md](observability.md) | Loki·Alloy 로그, 요청 로그 대시보드, Alertmanager·슬랙 알림, 메모리 추가분, 시연 방식과 프론트가 할 일 |
| [load-test.md](load-test.md) | k6 도구, 임시 발생기 EC2, 범위와 시나리오, 준비물, 지표, 측정 환경 기록표, ISE-25와의 분담 |
| [open-peak-load-plan.md](open-peak-load-plan.md) | 오픈 피크 흐름에서 트래픽이 몰리는 구간, 구간별 측정 시나리오와 단계별 개선 방안·트레이드오프·예상 결과, 측정 순서(ISE-181) |
| [open-peak-scenarios.md](open-peak-scenarios.md) | 구간별 부하 시나리오 명세(G1·B1·Q1~Q3·PAY1·R1·X1·L1~L3·O1): 준비, 부하 모양, 지표, 판정 기준, 담당 검토 요청 |
| [single-host-msa-resource-isolation.md](single-host-msa-resource-isolation.md) | 한 서버(4 vCPU) 안에서 서비스 경계를 CPU·메모리·유입량 경계로 옮기는 방법 비교와 추천 순서(초안) |
| [gateway-auth-redis-resilience.md](gateway-auth-redis-resilience.md) | 11/9~10 Gateway `auth-redis` 차단기·벌크헤드·시한 설정을 정하기까지의 고민 흐름, 정리된 원칙, 재측정 값과 대가, 트러블슈팅·발표용 요약 |
| [gateway-overload-troubleshooting-story.md](gateway-overload-troubleshooting-story.md) | 11/9~11 Gateway 과부하 트러블슈팅 서사: 차단기 오탐 → 차단기 예민화 실패 → OOM·메모리 예산 → 동시 처리 수 상한 → 거절 비용과 관측 위치 → HttpHandler 데코레이터 → 요청 로그 비용. 단계별 가설·실험·결과 표, AI 추천과 결정이 갈린 지점, 발표 장면·예상 질문, 7차 판정(H1 거절 위치, H2 요청 로그) |

관련 ADR:
- [0044](../../decisions/0044-deploy-actions-ghcr-jenkins.md) 배포 파이프라인 (GitHub Actions + GHCR + Jenkins, EC2 한 대)
- [0045](../../decisions/0045-logs-loki-alerts-slack.md) 로그·알림 (Loki·Alloy·공통 요청 로그·Alertmanager·슬랙)
- [0046](../../decisions/0046-load-test-k6.md) 부하 측정 도구·환경 (k6, 임시 발생기 EC2)

구현 이슈: ISE-6 하위 ISE-173(이미지)·174(배포 compose)·175(Jenkins)·176(크기 측정)·177(배포 DB 시드), ISE-182(공통 요청 로그, contract)·178(Loki·Alloy)·179(Alertmanager·슬랙), ISE-180(프론트 traceId 표시, 프론트 세션), ISE-181(k6, M4).

관련 문서: 결제 지표·알림 규칙은 [payment/observability.md](../payment/observability.md), 프론트 컨테이너는 [ADR 0040](../../decisions/0040-frontend-react-spa-in-monorepo.md), 로컬 실행은 [workspace.md](../../guides/workspace.md), 시스템 테스트는 [testing.md](../../guides/testing.md).

## 아직 구현하지 않았다

이 폴더는 설계다. Alertmanager 설정(`infra/alertmanager/`)은 ISE-179에서 구현했다. 배포용 compose, Jenkinsfile, Alloy·Loki 설정, k6 스크립트는 코드로 존재하지 않는다. 구현 이슈에서 위치와 이름을 정한다.

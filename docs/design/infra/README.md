# 배포·관측·부하 측정 설계 (infra)

EC2 배포 파이프라인, 로그·알림 관측, 부하 측정 환경의 설계를 모은다. 사용자(안준현)가 2026-11-03에 정한 내용을 옮긴 것이고, 정하지 않은 값은 `[가정]`이나 "미정"으로 표시했다. 설계 정본은 [SA MASTER](../../core/SA%20MASTER.md) 3-1·9장이며, 여기서 달라지거나 더해지는 부분은 각 문서 끝의 "SA와 다른 점/더한 점"에 모았다.

| 문서 | 내용 |
|---|---|
| [deployment.md](deployment.md) | EC2 한 대 배포 구조, GitHub Actions·GHCR·Jenkins 파이프라인, 보안그룹, 비밀값, 되돌림, 인스턴스 크기 결정 방법 |
| [observability.md](observability.md) | Loki·Alloy 로그, 요청 로그 대시보드, Alertmanager·슬랙 알림, 메모리 추가분, 시연 방식과 프론트가 할 일 |
| [load-test.md](load-test.md) | k6 도구, 임시 발생기 EC2, 범위와 시나리오, 준비물, 지표, 측정 환경 기록표, ISE-25와의 분담 |

관련 ADR (메인 세션이 따로 쓴다. 번호는 정해졌고 파일은 아직 없을 수 있다):
- 0044 배포 파이프라인 (GitHub Actions + GHCR + Jenkins, EC2 한 대)
- 0045 로그·알림 (Loki·Alloy·Alertmanager·슬랙)
- 0046 부하 측정 도구·환경 (k6, 임시 발생기 EC2)

관련 문서: 결제 지표·알림 규칙은 [payment/observability.md](../payment/observability.md), 프론트 컨테이너는 [ADR 0040](../../decisions/0040-frontend-react-spa-in-monorepo.md), 로컬 실행은 [workspace.md](../../guides/workspace.md), 시스템 테스트는 [testing.md](../../guides/testing.md).

## 아직 구현하지 않았다

이 폴더는 설계다. 배포용 compose, Jenkinsfile, Alloy·Loki·Alertmanager 설정, k6 스크립트는 코드로 존재하지 않는다. 구현 이슈에서 위치와 이름을 정한다.

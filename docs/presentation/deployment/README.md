# 배포와 운영

배포 서버 구성도(서버, 컨테이너, 포트, 외부 노출), CI/CD 흐름, 관측(로그·지표·알림) 구성 그림을 둔다. 그림에는 기준 날짜를 적는다. 배포 구성은 바뀌는 중이라(ADR [0047](../../decisions/0047-single-ec2-first-managed-split-review.md)) 날짜가 없으면 어느 시점 그림인지 알 수 없다.

정본:
- 인프라 구조도와 외부 노출: [SA](../../core/SA%20MASTER.md) 9장
- 배포 파이프라인과 서버 구조: [`design/infra/deployment.md`](../../design/infra/deployment.md), ADR [0044](../../decisions/0044-deploy-actions-ghcr-jenkins.md)
- 로그·알림: [`design/infra/observability.md`](../../design/infra/observability.md), ADR [0045](../../decisions/0045-logs-loki-alerts-slack.md)
- 한 서버 안 자원 격리: [`design/infra/single-host-msa-resource-isolation.md`](../../design/infra/single-host-msa-resource-isolation.md)
- 서버 접속: [`guides/deploy-server-access.md`](../../guides/deploy-server-access.md)

| 날짜 | 파일 | 내용 |
|---|---|---|
| 2026-11-05 | [2026-11-05-deploy-flow-motion.html](2026-11-05-deploy-flow-motion.html) | 배포 노선도(모션). main 머지 커밋이 GitHub → Actions → GHCR → Jenkins → 배포 서버 → Slack을 지나는 과정을 단계별로 재생한다(성공, 되돌림, 중단 세 경우). CI/CD 개념, 담당, 슬랙 알림(배포 준비·배포 중 진행 알림과 결과 여섯 가지), 11/5 릴리스 #338 실측 타임라인(머지에서 배포 완료까지 4분 35초)을 담았다. 브라우저로 연다 |

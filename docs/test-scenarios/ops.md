# 관측·배포·부하 테스트 시나리오

기준 커밋 `0bc8141`. 표 읽는 법은 [README](README.md)에 있다. 이 문서는 시나리오 정의만 담고, 실행 결과는 [`runs/`](runs/)에 있다.

## 관측·경보 (9건)

| ID | 시나리오 | 유형 | 우선순위 | 기대 결과 | 테스트 경로 | 실행 환경 |
|---|---|---|---|---|---|---|
| OBS-001 | 메트릭·대시보드와 Gateway-User 추적 연결 | 정상 | P0 | 수집과 추적 연결 | `system-tests/WorkspaceSystemTest` | 자동 테스트, 로컬, 서버 |
| OBS-002 | Eureka에 업무 앱 5개 등록 | 정상 | P1 | 5개 등록 | `system-tests/WorkspaceSystemTest` | 자동 테스트, 로컬 |
| OBS-003 | 요청 로그: 경로 템플릿, actuator 제외, 예외 500, CANCELLED | 경계값 | P2 | 한 줄 기록, 쿼리 제외, 설정 끄면 필터 미등록 | `common/RequestLogFilterTest`, `gateway/RequestLogWebFilterTest` | 자동 테스트 |
| OBS-004 | 결제 지표 이름·라벨·집계 게이지 (ISE-195) | 정상 | P1 | 세부는 테스트 본문 확인 필요 (근거: 추정) | `payment-service/PaymentApprovalMetricsTest`, `payment-service/PgProtectionMetricsTest`, `payment-service/DbAggregateGaugesTest`, `payment-service/MetricLabelsTest`, `payment-service/PrometheusNamesTest`, `payment-service/MeteredPgClientTest` | 자동 테스트 |
| OBS-005 | 추첨 지표: 0 시계열, 커밋 후 집계, 중복·롤백 제외 (ISE-94) | 정상 | P1 | 시간은 추첨 시각부터, 모르는 사유 OTHER, NOT_SENT는 재시도로 집계 | `reservation-service/LotteryMetricsTest`, `reservation-service/LotteryPaymentMetricsTest` | 자동 테스트 |
| OBS-006 | 결제 경보 규칙 promtool 단위 검증 (ISE-195) | 정상 | P1 | 수동 확인·DLQ·Outbox·대사·지연·CB·작업 정지·DB 경보가 기대대로 발화 | `infra/prometheus/tests/payment-alerts.test.yml` | 자동 테스트 |
| OBS-007 | 추첨 경보 규칙 promtool 단위 검증 (ISE-94) | 정상 | P1 | LotteryResultNotApplied, SeatsNotReturned, DrawFailing, JobFailing, SettlementSlow, FinishOverdue 발화 | `infra/prometheus/tests/lottery-alerts.test.yml` | 자동 테스트 |
| OBS-008 | 실제 환경에서 경보 발화를 Alertmanager·Slack까지 확인 | 실패 | P1 | 확인할 질문: 알림이 기대 시간 안에 오는가(자동 테스트 없음) (근거: 추정) | 없음 | 로컬, 서버 |
| OBS-009 | 장애 중 Grafana 패널과 traceId 연결 확인 (ISE-180) | 정상 | P1 | 요청 시각 앞뒤 15분으로 열림 | `frontend/src/demo/grafana.test.ts` | 로컬, 서버 |

### 관측·경보: 직접 실행할 때

| ID | 사전 조건 | 절차 | 서버 데이터 변경 |
|---|---|---|---|
| OBS-001 | compose 스택 | 요청 후 Prometheus·Grafana·Zipkin 확인 | 없음 |
| OBS-002 | compose 스택 | Eureka 조회 | 없음 |
| OBS-008 | Alertmanager, 수신 채널 설정 | 장애 주입으로 경보 조건 유발 | 있음 |
| OBS-009 | 시연 모드, Grafana 접근 | 요청 기록의 traceId 링크로 대시보드 열기 | 없음 |

## 배포·운영 (11건)

| ID | 시나리오 | 유형 | 우선순위 | 기대 결과 | 테스트 경로 | 실행 환경 |
|---|---|---|---|---|---|---|
| OPS-001 | 배포 뒤 스모크: 화면, SPA 깊은 경로, 공개 API, 인증 401 (ISE-174) | 정상 | P0 | 항목별 200, 200, 200, 401. 실패 시 최대 120초 재확인 | `script/deploy/smoke.sh` | 서버 |
| OPS-002 | 시연 시드 재실행 안전과 open 회차 추가 (ISE-177) | 경계값 | P1 | 중복 생성 없음, 중간 실패 이어가기, 비밀번호·토큰 미출력 | `script/deploy/seed-demo.sh` | 로컬, 서버 |
| OPS-003 | Config 응답과 설정 저장소에 비밀값 미노출 | 보안 | P0 | 실제 비밀값 없음 | `system-tests/WorkspaceSystemTest`, `config-server/ConfigRepoTest` | 자동 테스트, 로컬 |
| OPS-004 | 포트 노출 규칙: loopback 허용, 서비스 포트 공개 거절 | 보안 | P1 | 허용 포트 외 거절 | `workspace-tools/PortExposureTest` | 자동 테스트 |
| OPS-005 | 비밀값 분리 전달: 토큰·빌링키 키는 필요한 서비스에만 | 보안 | P1 | 필요한 키만 전달, 누락 시 값 노출 없이 거절 | `workspace-tools/LocalAppsTest` | 자동 테스트 |
| OPS-006 | 로컬 .env 생성과 해석: 덮어쓰기 금지, 빠진 키만 추가 | 경계값 | P2 | 따옴표·주석 해석, 0600 권한, 부모 환경 우선 | `workspace-tools/LocalEnvironmentTest`, `workspace-tools/CommandsTest` | 자동 테스트 |
| OPS-007 | Ops 프로필 격리와 조회·사용법 오류 | 보안 | P2 | 세부는 테스트 본문 확인 필요 (근거: 추정) | `payment-service/OpsRunnerTest`, `payment-service/OpsProfileContextTest`, `payment-service/OpsRunnerAbsentTest`, `payment-service/InspectQueryTest` | 자동 테스트 |
| OPS-008 | main 머지 뒤 이미지·Jenkins 자동 배포 끝단 확인 | 정상 | P1 | 확인할 질문: 배포 후 스모크가 통과하고 코드가 바뀌는가 (근거: 추정) | `Jenkinsfile`, `script/deploy/deploy.sh` | 서버 |
| OPS-009 | 일반 예매 시스템 테스트: 대기열-선점-결제-확정 (S1, S2, S3, S4) | 정상 | P0 | 한 흐름 완료 | `system-tests/BookingFlowSystemTest` | 자동 테스트, 로컬 |
| OPS-010 | 추첨 수동 E2E 시나리오 (S8, S9) | 정상 | P1 | 파일의 요청 순서대로 진행(세부는 파일 확인 필요) (근거: 추정) | `http/lottery-e2e.http` | 로컬 |
| OPS-011 | 추첨 응모자 적재 스크립트: 재실행 안전과 원격 보호 (ISE-199, S8, S9) | 경계값 | P0 | 기본 120명(2매 30, 카드 미등록 12). 재실행해도 활성 키 1개, 같은 계정은 처음 응모 반환. 일부 실패는 종료 코드 1 | `script/deploy/seed-lottery-entrants.sh` | 로컬, 서버 |

### 배포·운영: 직접 실행할 때

| ID | 사전 조건 | 절차 | 서버 데이터 변경 |
|---|---|---|---|
| OPS-001 | 배포 완료 | script/deploy/smoke.sh | 없음 |
| OPS-002 | 관리자 API | script/deploy/seed-demo.sh, open 하위 명령 | 있음 |
| OPS-003 | 없음 | Config 조회와 config-repo 검사 | 서버 실행 안 함 |
| OPS-008 | main 머지 권한 | 머지 후 커밋 정하기·이미지 대기·배포 단계 확인 | 있음 |
| OPS-009 | compose 스택 | system-tests 실행 | 서버 실행 안 함 |
| OPS-010 | 로컬 스택 | http/lottery-e2e.http 실행 | 있음 |
| OPS-011 | 추첨 존재. 원격은 LOTTERY_SEED_ALLOW_REMOTE=yes 필요 | script/deploy/seed-lottery-entrants.sh prepare, enter <추첨 id> | 있음 |

## 부하 (5건)

| ID | 시나리오 | 유형 | 우선순위 | 기대 결과 | 테스트 경로 | 실행 환경 |
|---|---|---|---|---|---|---|
| LOAD-001 | [미구현] 오픈 피크 한 흐름 부하 측정 (ISE-25) | 동시성 | P0 | TPS·p95·p99·오류율 측정. 수치 기준은 설계 문서상 [가정]. k6 스크립트 45a0900에 없음 (근거: 설계 문서) | 없음(docs/design/infra/load-test.md 시나리오 1) | 서버 |
| LOAD-002 | [미구현] 선점 경합 부하 기준선 (ISE-25) | 동시성 | P0 | 확인할 질문: 초과 판매 0건인가, 포화점은 어디인가. 스크립트 없음 (근거: 설계 문서) | 없음(docs/design/infra/load-test.md 시나리오 2) | 서버 |
| LOAD-003 | [미구현] PG 지연·장애 중 결제 부하 | 실패 | P1 | 확인할 질문: CB·벌크헤드가 타 흐름을 지키는가. 스크립트 없음 (근거: 설계 문서) | 없음(docs/design/infra/load-test.md 시나리오 3) | 서버 |
| LOAD-004 | [미구현] 사용자를 늘려 포화점 찾기 | 경계값 | P1 | 처리량이 멈추고 p95가 급증하는 지점 기록 (근거: 설계 문서) | 없음(docs/design/infra/load-test.md 6절) | 서버 |
| LOAD-005 | 추적 샘플링을 낮춘 loadtest 설정 | 정상 | P2 | 샘플링 감소 | `config-server/ConfigRepoTest` | 자동 테스트 |

### 부하: 직접 실행할 때

| ID | 사전 조건 | 절차 | 서버 데이터 변경 |
|---|---|---|---|
| LOAD-001 | k6 발생기, 측정 환경 | N명 동시 진입, nextPollAfterMillis로 폴링, 이후 선점·결제 | 있음 |
| LOAD-002 | 입장 사용자, 한정 좌석 | 한정 좌석에 선점 집중 | 있음 |
| LOAD-003 | loadtest 프로필 Mock PG | 장애 주입을 켜고 결제 부하 | 있음 |
| LOAD-004 | 시나리오 1\~3 | 단계별 부하 | 있음 |

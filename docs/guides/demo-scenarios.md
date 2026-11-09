# 시연 장애 시나리오 진행 순서

발표·시연에서 결제가 실패하거나 늦어지는 흐름을 화면으로 보여 주는 순서다. 운영자 화면의 **시연 장애 패널**(`/admin/demo/faults`, ISE-134)로 시연용 Mock PG에 장애를 건다.

## 1. 준비
- 서버 주소와 계정은 [deploy-server-access.md](deploy-server-access.md)를 따른다. 패널은 운영자(ADMIN) 계정으로만 열린다.
- 탭 두 개를 연다.
  - 운영자 탭: 시연 장애 패널
  - 사용자 탭: 일반 사용자로 로그인한 예매 화면. 주소 끝에 `?demo=on`을 붙이면 요청 로그 패널이 켜진다. 요청마다 traceId와 Grafana 요청 로그 링크가 보인다(ISE-180).
- 패널 위쪽 경고처럼 장애는 **이 서버의 모든 결제**에 걸린다. 시연하는 동안 다른 사람은 결제하지 않게 미리 알린다.
- 부하 측정(`script/loadtest/measure-mode.sh on`) 중에는 쓰지 않는다. 측정 결과가 섞인다.

## 2. 시나리오
각 시나리오는 패널에서 버튼을 누른 뒤, 사용자 탭에서 좌석을 잡고 결제한다.

| 시나리오 | 패널 버튼 | 사용자 화면에서 보이는 것 | 같이 볼 곳 |
|---|---|---|---|
| 결제 거절 | [결제 거절] 다음 결제 1건에 걸기 | "결제가 거절됐어요", "카드사가 결제를 거절했어요." 잡았던 좌석이 풀린다 | 요청 로그의 결제 요청 traceId → Grafana |
| PG 응답 지연 | [PG 응답 지연] 다음 결제 1건에 걸기(4초) | "결제를 확인하고 있어요" → 10~20초 뒤 "예매가 확정됐어요"(화면은 10초 간격으로 다시 조회한다) | Grafana 결제 대시보드의 재확인 처리. 결제 서비스가 PG를 2초에 포기하고(UNKNOWN), 재확인 스케줄러가 승인을 찾아 확정한다 |
| 승인 응답 유실 | [승인 응답 유실] 다음 결제 1건에 걸기 | "결제를 확인하고 있어요" → 확정 | 패널 "Mock PG 원장"의 승인이 1건만 는다. 응답을 잃어도 돈은 한 번만 나간다 |
| PG 서버 오류 계속 | [PG 서버 오류 계속] 해제할 때까지 걸기 | "결제를 확인하고 있어요" → "결제가 거절됐어요"(PG에 주문이 없어 늦은 승인 방지 뒤 거절) | 서킷 브레이커는 최근 20건 중 절반 넘게 실패해야 열린다. 아래 3절처럼 k6로 보여 준다 |

- 시나리오 하나가 끝날 때마다 패널 "지금 걸린 장애"에서 남은 장애가 없는지 본다. 1건짜리는 결제 한 번에 소비된다.
- 시연이 끝나면 [모두 해제]를 누른다.

## 3. 버튼 한 번으로 재현되지 않는 것
- **서킷 브레이커 OPEN**: [PG 서버 오류 계속]을 건 뒤 k6 결제 시나리오(`loadtest/k6/scenarios/pay1-payment.js`)를 짧게 돌린다. 실행 방법은 [loadtest/k6/README.md](../../loadtest/k6/README.md)다.
  - **`MOCK_PG_URL=`(빈 값)으로 돌린다.** 이 시나리오는 Mock PG 주소가 있으면 시작할 때 승인 지연(기본 300ms)을 걸고 끝날 때 풀어서, 패널에서 건 서버 오류를 덮어쓴다.
  - 최근 승인 호출 20건 중 절반 넘게 실패하면 Grafana 결제 대시보드에서 승인 서킷 브레이커가 OPEN이 되고, 결제가 PG를 부르지 않고 바로 실패한다.
  - 끝나면 [모두 해제]를 누른다. 자동으로 닫히지 않는다. 10초가 지난 뒤 다음 결제가 오면 반쯤 열림이 되고, 시험 호출 3건이 성공해야 닫힌다. 그때까지 대시보드는 OPEN으로 보인다.
- **선점 만료 뒤 승인 → 자동 환불**: 선점 시간이 몇 분이라 시연 중 기다리기 어렵다. 결제 장애 시나리오 테스트 결과로 보인다([failure-scenarios.md](../design/payment/failure-scenarios.md)).

## 4. 로컬에서 쓰기
로컬 compose는 Mock PG를 `local` 프로필로 띄워 장애 주입 API가 없다. 그래서 패널에 "이 환경에서는 장애를 걸 수 없어요"가 뜬다. 로컬에서 쓰려면 Mock PG만 `loadtest`를 더해 다시 띄운다.

```bash
docker compose -f compose.yml -f compose.local.yml -f loadtest/k6/compose.mockpg-loadtest.yml up -d --no-deps --wait mock-pg
```

## 5. 동작 근거
- 경로: Gateway `config-repo/gateway.yml`의 `mock-pg-demo` 라우트가 `/api/v1/admin/demo/pg/faults`·`/ledger/summary`를 Mock PG `/mock-pg/...`로 넘긴다. `/api/v1/admin/**`라 Gateway가 ADMIN만 통과시킨다. `reset`과 결제 API는 열지 않는다.
- 장애 모드의 뜻과 프로필 제한: [mock-pg.md](../design/payment/mock-pg.md) 5절
- 화면 규칙: [docs/design/frontend/README.md](../design/frontend/README.md) "시연 장애 패널 규칙"

# 11/15 배포 서버 측정 그래프

11/15 측정 구간의 Prometheus `query_range`(step 5초) 원자료로 그렸다. 시각은 KST(UTC+9)다. 원자료와 쿼리 설명은 노트북 `C:\Users\Ptah\ise264-results\prom\`(`queries.md`), 그림을 만드는 스크립트는 `C:\Users\Ptah\ise264-results\charts\make_charts.py`다(맨 위 `SEGMENTS`·`COMPARE`에 B1·B2 구간을 더해 다시 돌린다).

구간: 122 O1 균일(오픈 18:52:00), 123 O1 Synthetic(오픈 19:18:27), L2-b 추첨 28(추첨 19:46:57), L3 추첨 29(추첨 20:01:49). 오픈 비교 그림은 오픈 기준 -60\~+300초만 그린다(힙 그림은 구간 전체).

| 그림 | 한 줄 설명 | 원자료 파일 | 쿼리 |
|---|---|---|---|
| `inflight-122-123.png` | 균일은 표본 최댓값 63건(18:52:35)으로 상한 64에 붙었고, Synthetic은 표본 최댓값 13건이다. 5초 순간값이라 표본 사이의 순간 최대는 놓친다 | `s1-o1u-122-gw-inflight.json`, `s1-o1s-123-gw-inflight.json`, `*-gw-limit.json` | `gateway_in_flight_requests`, `gateway_in_flight_limit` |
| `rejected-rate-122-123.png` | 상한 거절은 오픈 뒤 90초 안에만 났다. 최댓값 균일 32.84건/초(18:52:55), Synthetic 62.2건/초(19:18:55). 30초 창 rate라 모양이 30초 폭으로 퍼진다 | `s1-o1u-122-gw-rejected-rate.json`, `s1-o1s-123-gw-rejected-rate.json` | `rate(gateway_in_flight_rejected_total[30s])` |
| `gateway-heap-122-123.png` | Gateway 힙 최댓값은 균일 203.1MiB, Synthetic 204.9MiB로 최대 힙 512MiB의 절반 아래다(MiB 환산은 계산값) | `s1-o1u-122-gw-heap.json`, `s1-o1s-123-gw-heap.json` | `sum(jvm_memory_used_bytes{application="gateway",area="heap"})` |
| `l2b-billing-vs-approve.png` | 일반 결제 승인(`POST /api/v1/payments` 200)이 추첨 직후부터 끝까지 초당 30(최댓값 30.04)을 유지했고, Gateway payment 경로 평균 지연 최댓값은 317ms(Mock PG 승인 지연 300ms 포함, ms 환산은 계산값)다. 상한 거절률 최댓값 0 | `s2-l2b-l28-svc-rps.json`, `s2-l2b-l28-gw-route-avg.json`, `s2-l2b-l28-gw-rejected-rate.json` | `sum by (application, uri, status) (rate(http_server_requests_seconds_count{...}[30s]))`, `gw-route-avg`(sum/count) |
| `l3-hold-latency.png` | 추첨 뒤 120초 동안 Gateway reservation 경로 평균 지연이 추첨 전 표본 평균 6.4ms(계산값)에서 최댓값 10.1ms(20:02:20)로 올랐다가 돌아왔다. 아래 칸은 선점 201·409 처리율 | `s2-l3-l29-gw-route-avg.json`, `s2-l3-l29-svc-rps.json` | `gw-route-avg`(sum/count), `svc-rps` |
| `hikari-pending-122-123.png` | 균일 오픈 직후 reservation-service DB 커넥션 대기가 최대 10(18:53:05)까지 생겼고, Synthetic은 0이다 | `s1-o1u-122-hikari-pending.json`, `s1-o1s-123-hikari-pending.json`, `*-hikari-acquire-max.json`(주석) | `hikaricp_connections_pending`, `hikaricp_connections_acquire_seconds_max` |
| `host-cpu-122-123.png` | 오픈 직후 호스트 CPU가 균일 최대 99.75%(18:53:05), Synthetic 최대 80.28%(19:18:50)까지 올랐다(백분율 환산은 계산값) | `s1-o1u-122-host-cpu.json`, `s1-o1s-123-host-cpu.json` | `system_cpu_usage{application="gateway"}` |

## 읽을 때 주의
- `l2b-billing-vs-approve.png`: `pg-billing`·`pg-approve` 허가 그룹의 남은 허가 수는 Prometheus 내보내기 19지표에 없어 그리지 못했다. 진행 기록의 값(pg-billing 최소 0, pg-approve 최소 20)은 그림 주석에만 옮겼다.
- `l3-hold-latency.png`: Gateway 경로 평균은 reservation 경로 전체(조회 포함)의 30초 창 평균이라 선점만의 p95가 아니다. 서비스 히스토그램 p95(`svc-p95`)는 버킷이 거칠어 약 24ms로 평평해 쓰지 않았다. 선점 p95는 발생기 집계(pre 11.06ms → during 21.0ms)를 본다. 내보내기 구간 앞부분(19:56\~20:00)은 응모·빌링키 준비라 선점 요청이 없어 20:00:30부터 그린다.
- 처리 중 수·커넥션 대기는 5초마다 찍은 순간값이다. Synthetic도 거절이 있었으므로 상한에 닿은 순간이 표본 사이에 있었다고 본다(추정, 확인 못 함).
- `host-cpu`는 Gateway JVM이 보고한 호스트 전체 CPU다. 컨테이너별 CPU·메모리(`container_*`)는 Prometheus에 없다.

# k6 측정에서 나온 공유 설정·Gateway 제안 (ISE-79, 의논 초안)

상태: **의논 전 초안. Linear 이슈는 아직 올리지 않았다.** 팀과 의논한 뒤 이슈로 올린다. 작성: 좌석 선점 담당 최권우 요청으로 Claude Code.

좌석 선점 k6 단계 측정([k6-seathold-bottleneck-plan.md](k6-seathold-bottleneck-plan.md) 5-5절)에서 효과를 확인한 설정 중 다른 서비스나 공유 파일에 걸린 것을 모았다. 측정 브랜치에서는 덮어쓰기 파일로만 켰고, 공유 설정(`config-repo/`, `compose.deploy.yml`)은 고치지 않았다. 절차는 [contract-issues.md](../../guides/contract-issues.md)를 따른다.

## 1. 한눈에

| # | 제안 | 지금 값 | 제안 값 | 바뀌는 파일 | 받는 쪽 | 근거 강도 |
|---|---|---|---|---|---|---|
| A | Gateway 인증 차단기(`auth-redis`) 기준 | 느린 호출 50ms·비율 50%, 실패율 50%, 판단 범위 20번 | 의논 거리(2절) | `config-repo/gateway.yml` | Gateway 담당 | 관찰은 여러 회, 고칠 값은 미정 |
| B | reservation-service GC를 G1으로 명시 | 지정 없음(1,152MB 컨테이너라 JVM이 Serial 선택) | `-XX:+UseG1GC` | `compose.deploy.yml` reservation-service `JAVA_TOOL_OPTIONS` | 인프라 담당 | 1회씩, 효과 큼. **dev에 이미 반영(ISE-193). 이슈로 올리지 않는다** |
| C | Hikari 커넥션 대기 시한 | 30초(기본값) | 1초 | `config-repo/reservation-service.yml` | 인프라 담당(공유 설정), 이미 ISE-196 | 이전 기기 3회 + 새 기기 1회 |
| D | reservation-service DB 커넥션 풀 | 5 | 10 | `config-repo/reservation-service.yml` | 인프라 담당(공유 설정) | 1회씩(5·10·20·40 비교) |

B·C·D는 reservation-service 자체 설정이지만 공유 파일에 있어 contract 이슈로 알린다. A는 Gateway 담당 영역이라 관찰만 넘기고 값은 그쪽이 정한다.

## 2. A. Gateway 인증 차단기

### 무엇을 봤나
Gateway는 인증 요청마다 Redis에서 로그아웃 토큰을 확인한다. 이 호출이 느리거나 실패하면 `auth-redis` 차단기가 10초 열리고, 그동안 인증 요청은 모두 즉시 503이다(fail closed, ADR 0005).

1. **공유 기준이면 가벼운 부하에서도 열린다(2026-11-07, 이전 기기).** 선점만 보내는 K1-H 초당 100명부터 Gateway CPU 상승만으로 느린 호출(50ms) 비율이 90~100%가 되어 열렸다. Redis CPU는 10% 아래였다.
2. **실패율 50%·판단 범위 20번이면 몰린 시한 초과만으로 열린다.** 시한 초과는 초당 1건 안팎인데 몰려 나와 최근 20번 중 절반을 채웠고, 정상 요청까지 초당 300~750건 거절했다.
3. **통과 요청이 초당 약 600건을 넘으면 Redis 확인이 느려진다.** 평균 0.5ms에서 8~13ms로 늘고 시한 초과(100ms)가 초당 5~45건 나왔다. Gateway GC를 G1으로 바꿔도 같았다.
4. **측정용으로 완화한 기준(느린 호출 100ms, 실패율 100%)에서도 큰 부하에서는 열린다(2026-11-10, 새 기기).** k6가 여유 있는 상태(CPU 1.6~2.6코어)에서 K1-H 800명 단계는 6회(base 3, F 3) 모두 열려 회마다 약 4,500~18,600건을 즉시 거절했다. K1 S 1,280명 단계(좌석 조회 초당 약 1,280건)는 3회 중 1회 열렸다(약 12,900건). 이때 reservation-service는 5xx 0이었다.
5. **k6 메모리 상한이 낮으면 훨씬 자주 열렸다.** k6가 GC로 멈췄다 요청을 몰아 보내면 거절이 K1-H 800명에서 약 41,000건이었다. 측정 쪽 문제라 고쳤지만(TS-60), 실제 사용자 요청도 몰려 들어오면 같은 일이 생길 수 있다는 관찰이기도 하다.

덧붙임: base(Serial GC, 풀 5)의 K1-H에서는 Gateway가 reservation-service에 연결하지 못한 500(`ConnectTimeoutException`, 연결 시한 1,000ms)이 회마다 41~60건 나왔다. F에서는 0이다. reservation-service 쪽 개선으로 사라진 것이라 Gateway 변경 거리는 아니다.

### 의논할 질문
1. 공유 기준(느린 호출 50ms·비율 50%)은 배포 서버(4 vCPU에 전체 스택)에서 티켓 오픈 부하를 받으면 바로 열릴 가능성이 크다. 느린 호출 판정을 호출 시한(100ms)과 맞춰 사실상 끄는 것(측정 환경 값)을 공유 기준으로 삼을지.
2. 실패율 기준과 판단 범위: 20번 중 50%는 순간 몰림에 민감하다. 판단 범위를 늘리거나(예: 100번, 또는 시간 기준 창) 실패율을 올릴지. 100%는 Redis가 완전히 죽었을 때만 보호한다.
3. 차단기가 열렸을 때 모든 인증 요청을 503으로 막는 정책(fail closed)을 유지할지. 로그아웃 토큰 확인만 건너뛰는 fail open은 보안 결정이라 ADR 0005를 다시 봐야 한다.
4. Redis 확인 자체를 줄이는 방법(로그아웃 토큰 목록의 짧은 로컬 캐시 등)을 Gateway 쪽에서 볼지.
5. 결정 전까지 배포 환경 부하 측정에서는 측정용 덮어쓰기를 쓸지, 공유 기준 그대로 잴지.

### 이슈 초안
```markdown
제목: contract(reservation→gateway): k6 측정에서 auth-redis 차단기가 부하에 먼저 열림
라벨: contract, gateway
담당: Gateway 담당(이슈를 가져간 사람 확인 필요)

@Gateway담당 좌석 선점 k6 측정에서 본 auth-redis 차단기 동작을 공유합니다. 공유 기준을 바꿀지 판단을 부탁합니다.

## 배경
* 근거: docs/design/reservation/k6-contract-proposals.md 2절, k6-seathold-bottleneck-plan.md 5-5절·6절 13번
* 공유 기준에서는 선점만 보내는 초당 100명부터 Gateway CPU 상승만으로 차단기가 열려 인증 요청이 모두 503이었습니다. 측정용으로 완화한 기준에서도 K1-H 800명은 매회 열렸습니다(reservation-service 5xx 0).

## Gateway가 할 일
1. 2절 관찰 1~4를 확인하고, 느린 호출 기준·실패율·판단 범위를 바꿀지 정한다.
2. 바꾸지 않는다면 이유를 이 이슈에 남긴다(배포 측정 해석에 쓴다).

## 완료 기준
- [ ] 담당이 내용을 확인하고 이견이 있으면 이 이슈에 남긴다.
- [ ] 바꾼다면 config-repo/gateway.yml 변경 PR을 이 이슈에 잇는다.
```

## 3. B. reservation-service GC를 G1으로

상태: dev `compose.deploy.yml`에 이미 반영됐다(ISE-193, 앱 8개 모두 `-XX:+UseG1GC`, reservation-service `-Xms384m -Xmx768m`). 2026-11-10 dev에서 확인했다. 아래는 측정 근거로만 남긴다.

- **지금:** `compose.deploy.yml`의 reservation-service는 `-Xms384m -Xmx768m`만 준다. 컨테이너 메모리가 약 1,792MB보다 작으면 JVM이 Serial GC를 자동으로 고르므로, 배포 값(1,152MB)은 Serial이다.
- **측정:** 새 기기 base K1 L에서 GC 정지가 부하 시간의 약 75%였다. G1만 켜자 7.3%로 줄었고 S 버틴 단계가 10명 → 20명이 됐다. 이전 기기에서도 같은 방향이었다(정지 66~69% → 24.5%).
- **대가:** G1은 Serial보다 메모리 부가 비용(리전 관리, 기억 집합)이 있다. 같은 힙(768MB)에서 R1 S 320명 단계에 `Evacuation Failure`가 났다. 다만 P4a 이후에는 GC 정지 1~3%였다.
- **의논할 질문:** reservation-service만 바꿀지, 같은 크기대(512~768MB 힙)인 다른 서비스에도 같은 기준을 둘지. 다른 서비스는 측정하지 않았으므로 이 측정은 reservation-service만 근거가 된다.
- **이슈 초안 요지:** `contract(reservation→infra): 배포 compose의 reservation-service에 G1 GC 명시`. 할 일은 `JAVA_TOOL_OPTIONS`에 `-XX:+UseG1GC` 추가, 완료 기준은 배포 뒤 GC 로그 첫 줄 `Using G1` 확인.

## 4. C. 커넥션 대기 시한 30초 → 1초 (R1)

- 이미 ISE-196으로 제안했다. 의논 전에 이슈 상태를 확인하고, 아직 열려 있으면 새 기기 결과를 댓글로 보탠다.
- **새 기기 결과:** G1만 켠 S에서 커넥션 대기 197건이 부하 뒤에도 남아 회복 탐침이 모두 504였다. R1을 켜자 부하 뒤 40초에 회복했고, L의 서버 좌석 조회 처리량이 초당 13.5 → 41.0건으로 늘었다(이전 기기에서는 처리량이 줄었다. 새 기기는 코어가 남아 커넥션 회전이 빨라진 것으로 보며 확정하지 않았다).
- **대가:** 몰릴 때 500이 빨리 나온다. Gateway 응답 시한(3초)보다 서버가 먼저 포기해야 쌓인 일이 남지 않는다.

## 5. D. 커넥션 풀 5 → 10 (P1)

- **측정:** Gateway를 우회한 K1-H 800명(서버 선점 초당 약 2,200건, k6 6GiB)에서 풀 5는 커넥션 대기 최대 40건, p99 33ms였다. 풀 10은 대기 0, p99 6ms, 서버 선점 평균 3.7 → 2.0ms. 풀 20·40은 10과 같았다(동시에 쓰는 커넥션 최대 5~8개).
- **왜 10에서 멈추나:** 더 키워도 이득이 없고, 인스턴스 수 × 풀이 PostgreSQL `max_connections`(지정 없음, 기본 100)를 넘으면 안 된다. 풀이 크면 DB의 문맥 전환과 잠금 경합도 는다.
- **대가:** reservation 전용 DB(`postgres-reservation`)의 연결이 5개 늘어난다. 다른 서비스 DB와 따로라 서로 영향은 없다. 배포가 한 대라 인스턴스를 늘릴 때만 다시 계산하면 된다.
- **의논할 질문:** Outbox 릴레이·스케줄러처럼 요청 밖에서 커넥션을 쓰는 작업이 늘면 10이 모자랄 수 있다. 측정은 만료 스케줄러를 끄고 했다.
- **이슈 초안 요지:** `contract(reservation→infra): reservation-service Hikari 풀 5 → 10`. 할 일은 `config-repo/reservation-service.yml`의 `maximum-pool-size` 변경, 완료 기준은 배포 뒤 `hikaricp_connections_max` 10 확인.

## 6. 의논 순서 제안

1. 팀에 이 문서와 5-5절을 공유한다(결과 요약).
2. A는 Gateway 담당과 따로 이야기한다. 값을 바로 정하기보다 관찰 1~4가 Gateway 쪽에서 재현되는지부터 묻는다.
3. B·C·D는 reservation-service 설정이라 반대가 없으면 한 PR로 묶을 수 있다. 다만 결정을 나눠 보이려면 이슈는 따로 둔다.
4. 이슈를 올리기 전에 목록을 사용자(최권우)가 확인한다(contract-issues.md 3절 "올린 뒤").

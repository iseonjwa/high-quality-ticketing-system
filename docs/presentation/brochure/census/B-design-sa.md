# B. 설계·SA·가이드 문서 전수조사 (사실 원장)

- 범위: `docs/design/**`, `docs/core/**`, `docs/sa-review/**`, `docs/guides/**`, `docs/agents/**`, `docs/README.md`, `README.md`, `AGENTS.md`. origin/dev(fetch 시 ref 잠금 오류가 있어 로컬 추적 ref `486501b7` 기준) 128개 파일 전부. 로컬 수정본인 `docs/design/infra/synthetic-data.md`, `docs/design/lottery/numbers-and-measurement.md`는 작업 트리 내용을 읽었고 해당 행에 "로컬" 표시가 있다.
- 방식: 파일을 16개 조각(A~P)으로 나눠 조각별로 끝까지 읽고 카드를 만들었다. 카드 id 접두사가 조각이다(A: SA MASTER 등, B/C/D/E: infra, F: 추첨·대기열, G: assistant·demo·guides, H: guides·sa-review, I: 브랜치 통합, J/K/L: 결제, M/N/O: 좌석, P: 프런트엔드·화면).
- 숫자는 원문 그대로이며 "직접 계산"은 조각 담당이 계산한 값이다. 서로 다른 날짜·장비·코드 버전의 숫자를 섞어 쓰지 말 것(조건 열 확인).
- 주의: 조각 간 중복(같은 측정을 여러 문서가 인용)이 있을 수 있다. 문서 간 모순은 행을 나눠 "모순"으로 표시했다.


## 1. 사실 카드 표 (전체 1566행, id 접두사 = 담당 조각 A~P)

| id | 서비스 | 주장 한 줄 | 숫자(전 → 후, 단위) | 배수·변화율(계산식) | 조건 | 성격 | 근거(경로:줄) | 시각 자료 | 서사 장면 후보 |
|---|---|---|---|---|---|---|---|---|---|
| A-01 | 기타 | 좌석 선점 API 부하 측정 설계: 동시 100명, 1초 안에 시작, 각 10회 | 동시 100명 × 각 10회 | 직접 계산: 100 × 10 = 1,000 요청 | 3장 검증 기준 표, 계획(측정 결과는 이 문서에 없음) | 설계 | docs/core/SA MASTER.md:292 | — | 오픈 정각 100명이 1초 안에 몰아치는 측정 설계 |
| A-02 | 기타 | 측정 목표를 고정 처리량(이전 200/sec)에서 같은 환경 동기형 → 접수형 비교와 포화점으로 바꿈 | 200/sec(이전 안) → 고정 수치 폐기 | — | 결정 기록 #47, 10/26 피드백 반영 | 판단 | docs/core/SA MASTER.md:308; docs/core/SA MASTER.md:2401 | — | 목표를 숫자에서 비교로 바꾼 순간 |
| A-03 | 기타 | 포화점 정의: 동시 사용자를 늘려 처리량이 멈추고 p95가 급증하는 지점 | — | — | 3-1 | 설계 | docs/core/SA MASTER.md:308 | — | 포화점이라는 측정 언어 |
| A-04 | 기타 | 측정 환경 기록표 항목: 장비(CPU·메모리·OS·EC2 타입)·부하 도구 장비·Docker 할당·JVM 힙(-Xmx)·컨테이너 메모리 상한·풀·스레드·데이터 규모·시나리오·커밋 해시 | — | — | 3-1, 장비 사양에 따라 결과가 달라진다는 10/26 피드백 반영 | 설계 | docs/core/SA MASTER.md:305; docs/core/SA MASTER.md:309 | — | 환경 차이를 기록해 개선 효과와 분리 |
| A-05 | 기타 | 오류 정의: 409는 정상 거절, 5xx·타임아웃만 오류. 접수형(202)은 접수 응답 시간과 접수→처리 완료 시간(컨슈머 랙)을 따로 기록 | — | — | 3장 오류 정의 | 설계 | docs/core/SA MASTER.md:293; docs/core/SA MASTER.md:2238 | — | 409를 오류로 세면 개선 효과가 가려진다는 판단 |
| A-06 | 기타 | 관측성 목표: Grafana에 처리량·평균/p95 응답·오류율·대기열 길이·결제 성공률·컨슈머 랙·이선좌율·CPU·메모리, Zipkin으로 느린 요청 구간 추적 | — | — | 3장 | 설계 | docs/core/SA MASTER.md:297 | — | 어디가 먼저 막히는지 숫자로 보는 운영자 페르소나 |
| A-07 | 기타 | 메시지 신뢰성 검증: 커밋 직후 프로듀서 중단, 컨슈머 강제 종료, 같은 이벤트 재발행을 주입해 유실 0건·중복 처리 0건. 브로커 1대라 브로커 자체 유실은 범위 밖 | 유실 0건·중복 0건(검증 기준) | — | 3장 | 설계 | docs/core/SA MASTER.md:295 | — |  |
| A-08 | 기타 | 측정·시연 공연장 시드는 1,500석(ADR 0060, 2026-11-16). 15,000석 회차의 좌석 응답(약 830KB)과 부하를 장비 한 대가 못 버텨 규모를 1/10로 줄임 | 15,000석 → 1,500석 (좌석 응답 약 830KB는 15,000석 회차 기준) | 원문 "1/10"; 직접 계산: 1,500 / 15,000 = 0.1 | 장비 한 대, 2026-11-16, ADR 0060 | 판단 | docs/core/SA MASTER.md:333; docs/core/SA MASTER.md:2051 | — | 규모를 줄여야 했던 이유 |
| A-09 | 기타 | 실제 공연장 규모 조사값은 그대로 둠: KSPO DOME 약 15,000석, 고척스카이돔 16,601석(공연 시 최대 약 25,000) | 약 15,000 / 16,601 / 약 25,000석 | — | 10/24 조사, 공연장 공식 자료 | 설계 | docs/core/SA MASTER.md:329 | — | 실제 규모와 시연 규모 대비 |
| A-10 | 기타 | 임영웅 2023 전국투어 오픈 1분 안에 약 370만 트래픽, 대기 20만 명 이상(기사 인용) | 약 370만 트래픽/1분, 대기 20만 명 이상 | — | 10/24 조사, 출처 레이디경향(2차 출처, 이 조사에서 원문 검증 못 함) | 설계 | docs/core/SA MASTER.md:330 | — | 현실 폭주 규모 참고 |
| A-11 | 기타 | NCT DREAM 2022 고척 공연 동시 접속 약 160만 명(기사 인용) | 약 160만 명 | — | 10/24 조사, 출처 Soompi(2차 출처, 검증 못 함) | 설계 | docs/core/SA MASTER.md:331 | — | 현실 폭주 규모 참고 |
| A-12 | 기타 | 숫자 계산 틀(모수·동시 접속·행동 비율·피크 요청·로컬 연결)은 [가정]으로 두고 기준선 측정 후 갱신. 참고 수치를 계산 틀에 넣는 작업은 U2 미해결 | — | — | 3-2, 17-2 U2 | 설계 | docs/core/SA MASTER.md:314; docs/core/SA MASTER.md:335; docs/core/SA MASTER.md:2267 | — |  |
| A-13 | 대기열 | 동시 입장 상한 = N × T = 50 × 600초 = 30,000명. KSPO DOME 약 15,000석의 2배, 측정 시드 1,500석의 20배 | 30,000명 (50명/초 × 600초) | 원문: 15,000의 2배, 1,500의 20배. 직접 계산: 30,000/15,000 = 2, 30,000/1,500 = 20 (일치) | [가정] 임시값 N=50, T=10분 | 계산값 | docs/core/SA MASTER.md:339 | — | 입장 속도 임시값이 좌석 수보다 훨씬 크다는 발견 |
| A-14 | 대기열 | 입장 속도 역산: L_max = μ/r, λ = L_max/W. 계산 예 μ=200, r=0.2, W=150초 → L_max=1,000, λ≈6.7명/초(초안 50명/초보다 훨씬 작음) | 50명/초(초안) → 약 6.7명/초(계산 예) | 직접 계산: 200/0.2 = 1,000; 1,000/150 ≈ 6.67; 50/6.67 ≈ 7.5배 | 모든 값 [가정], 리틀의 법칙 L=λ×W | 계산값 | docs/core/SA MASTER.md:339; docs/core/SA MASTER.md:340; docs/core/SA MASTER.md:341 | — | 리틀의 법칙으로 입장 속도를 역산 |
| A-15 | 대기열 | 50명/초는 기준선 측정 전 임시값. 동기형 기준선에서 포화점 μ와 실제 W를 측정한 뒤 N을 역산하고 T는 W의 몇 배로 정함 | N = 50(임시) | — | 3-3, 9-4, 측정 결과는 이 문서에 없음 | 설계 | docs/core/SA MASTER.md:342; docs/core/SA MASTER.md:889 | — |  |
| A-16 | 대기열 | 입장 토큰 유효 시간 T=10분, 초당 입장 수 N=50명(임시), 대기열 폴링 기본 3초·순번 멀수록 길게 최대 10초, Access/Refresh 30분/14일 | T 10분, N 50명/초, 폴링 3~10초 | — | [가정] | 설계 | docs/core/SA MASTER.md:888; docs/core/SA MASTER.md:889; docs/core/SA MASTER.md:890; docs/core/SA MASTER.md:891 | — |  |
| A-17 | 대기열 | 폴링에 서버가 0~+20% jitter를 ms로 더해 nextPollAfterMillis로 줌(최소 3초 유지). 503 Retry-After는 정수 초라 프론트가 0~+20%를 ms로 더함 | jitter 0~+20%, 최소 3초 | — | ADR 0031 제안 | 설계 | docs/core/SA MASTER.md:139; docs/core/SA MASTER.md:890; docs/core/SA MASTER.md:1268; docs/core/SA MASTER.md:1269 | — | 회복 순간 재시도가 몰리지 않게 jitter |
| A-18 | 대기열 | 차례 유효 시간 1분(queue.turn-ttl): 차례가 온 뒤 1분이 지나면 진입은 새 번호로 맨 뒤, 토큰 API·순번 조회는 404. admitLog 키 추가 | 1분 | — | 11/15, ADR 0031 결정 3·5·8·11, ISE-282 | 설계 | docs/core/SA MASTER.md:142; docs/core/SA MASTER.md:2032 | — |  |
| A-19 | 대기열 | 입장 판정은 seq <= admitted(#27). admitted 상한을 발급된 seq로 둬 줄이 빈 동안 카운터가 앞서가는 일을 막고, lastTick으로 인스턴스가 여러 개여도 1초에 한 번만 증가 | — | — | ADR 0015 제안(lastTick·상한), #27 결정(seq) | 설계 | docs/core/SA MASTER.md:130; docs/core/SA MASTER.md:131; docs/core/SA MASTER.md:132; docs/core/SA MASTER.md:2038; docs/core/SA MASTER.md:2041 | — |  |
| A-20 | 대기열 | 순번을 진입 시각(ms) ZSET 점수에서 seq 카운터(INCR + Lua)로 바꿈: 같은 ms 요청 동점 문제와 인스턴스 시계 차이. 대기열 담당의 지적을 AI 검증 #10으로 수용 | — | — | 10/26, #27 | 판단 | docs/core/SA MASTER.md:148; docs/core/SA MASTER.md:2132; docs/core/SA MASTER.md:2385 | — | 같은 ms에 들어온 요청의 공정성 |
| A-21 | 대기열 | 입장 토큰 jti는 entry:{회차}:{userId}:{발급 시각}의 SHA-256 앞 16바이트(hex)로 계산해 같은 발급 시각이면 같은 토큰 | 16바이트 | — | ADR 0031 | 설계 | docs/core/SA MASTER.md:636 | — |  |
| A-22 | 대기열 | 입장 토큰은 좌석 조회에는 exp까지 여러 번 쓰이고 선점에는 성공한 순간 소모됨(ADR 0053, ISE-207) | — | — | 날짜 미기재 | 설계 | docs/core/SA MASTER.md:635 | — |  |
| A-23 | 대기열 | 입장 처리 Lua가 1초마다 issued에서 T가 지난 항목을 최대 500개 정리, 만료 뒤 다시 진입하면 새 seq로 맨 뒤 | 1초마다, 최대 500개 | — | ADR 0031 제안 | 설계 | docs/core/SA MASTER.md:2046 | — |  |
| A-24 | 대기열 | 입장 토큰 발급을 POST /queue/token으로 분리, 상태 READY 추가, 진입은 새로 서면 201 이미 있으면 200, 순번 조회는 읽기 전용 | — | — | 10/27~10/29 제안(팀 합의 전), ADR 0031 | 판단 | docs/core/SA MASTER.md:134; docs/core/SA MASTER.md:135; docs/core/SA MASTER.md:1262; docs/core/SA MASTER.md:1263 | — | 토큰 발급을 순번 조회에서 분리한 이유 |
| A-25 | 좌석 | 선점 유지 5분, 회차당 매수 4매, 결제 토큰 만료 = 선점 만료 − 10초(사용자 결제 가능 시간 5분 → 4분 50초) | 5분 → 4분 50초(결제 가능), 4매 | 직접 계산: 300초 − 10초 = 290초 = 4분 50초(원문 일치) | [가정], 결정 기록 #35 | 설계 | docs/core/SA MASTER.md:633; docs/core/SA MASTER.md:885; docs/core/SA MASTER.md:886; docs/core/SA MASTER.md:887 | — | 결제 토큰을 10초 앞당긴 이유 |
| A-26 | 좌석 | 결제 토큰 10초의 구성: Gateway 요청 타임아웃 3초 + 결제 결과의 예매 반영 2초 + 여유 5초 | 10초 = 3 + 2 + 5 | 직접 계산: 3+2+5 = 10 (일치) | [가정] | 계산값 | docs/core/SA MASTER.md:633 | — |  |
| A-27 | 좌석 | 선점 DB lock_timeout 500ms: 오래 기다리기보다 바로 "이미 선택된 좌석" 안내 | 500ms | — | [가정] | 설계 | docs/core/SA MASTER.md:884 | — |  |
| A-28 | 좌석 | 레이스 컨디션: 조건부 UPDATE + 부분 유니크(DB가 기준)를 쓰고 분산락은 쓰지 않음. 검증 E1은 같은 좌석 동시 100요청에 1건 성공·나머지 409·중복 판매 0건 | 동시 100요청 → 성공 1, 중복 판매 0 (검증 기준) | — | 결정 기록 #59 잠정, E1, 측정 결과는 이 문서에 없음 | 설계 | docs/core/SA MASTER.md:247; docs/core/SA MASTER.md:2221; docs/core/SA MASTER.md:2225 | — | 분산락 대신 DB 제약을 고른 이유 |
| A-29 | 좌석 | 동시 요청으로 매수 제한을 우회하는 문제(D1)를 사용자·회차별 보유 수 행(quota) + 조건부 UPDATE로 막음. D2 좌석 상태 기준 두 곳, D3 다른 멱등키 중복 결제, D4 접수형 저장·발행 비원자도 10/25 자체 검수에서 수용 | 결함 4건(D1~D4) | — | 10/25, AI 검증 #8 수용 | 판단 | docs/core/SA MASTER.md:568; docs/core/SA MASTER.md:569; docs/core/SA MASTER.md:570; docs/core/SA MASTER.md:571; docs/core/SA MASTER.md:572; docs/core/SA MASTER.md:573; docs/core/SA MASTER.md:574; docs/core/SA MASTER.md:575; docs/core/SA MASTER.md:1998; docs/core/SA MASTER.md:2001; docs/core/SA MASTER.md:2002; docs/core/SA MASTER.md:2003; docs/core/SA MASTER.md:2004; docs/core/SA MASTER.md:2130 | — | 구현 전에 찾은 정합성 결함 4건 |
| A-30 | 좌석 | 접수형 선점: API가 seat_hold_requests(REQUESTED)+outbox를 한 트랜잭션에 저장, 소비자가 선점. 파티션 키 {scheduleId}:{section}(AI 검증 #5에서 원칙 수용·키 선택 부분 거부) | — | — | M4 고도화, 비교 측정 결과는 이 문서에 없음 | 설계 | docs/core/SA MASTER.md:493; docs/core/SA MASTER.md:978; docs/core/SA MASTER.md:2127 | — | 동기형 → 접수형 전환 |
| A-31 | 좌석 | 접수형 접수→처리 완료 2초 이내(outbox 폴링 500ms + 컨슈머 랙), 접수 요청이 30초 넘으면 REJECTED(TIMEOUT) | 2초 이내 / 30초 | — | [가정] | 설계 | docs/core/SA MASTER.md:893; docs/core/SA MASTER.md:894 | — |  |
| A-32 | 좌석 | 접수형을 Outbox로 바꾸면 접수→완료 시간에 폴링 500ms가 더해짐. 이전 안은 이 지연을 측정에 섞지 않으려 직접 발행을 골랐고(AI 검증 #8), 재측정에서 동기형·직접 발행·outbox 세 경우를 같은 환경에서 비교하기로 함 | +500ms | — | 계획 | 설계 | docs/core/SA MASTER.md:984; docs/core/SA MASTER.md:2130 | — | 측정에 폴링 지연이 섞이는 것을 피하려던 선택 |
| A-33 | 좌석 | 좌석 ID 오름차순 잠금으로 교착 방지, 만료 스케줄러는 SKIP LOCKED로 만료 대상 ID만 뽑아 건별 트랜잭션 | — | — | 8-4 | 설계 | docs/core/SA MASTER.md:758 | — |  |
| A-34 | 좌석 | 좌석 조회는 available만 내려 주고 HELD·SOLD를 구분해 노출하지 않음. 좌석 조회를 열어 두면 대기열을 우회한 조회 트래픽이 생기므로 입장 토큰 검증 | — | — | 8-1, 7-2 | 설계 | docs/core/SA MASTER.md:613; docs/core/SA MASTER.md:707; docs/core/SA MASTER.md:1246 | — |  |
| A-35 | 좌석 | 회차 좌석 3,000행(1,500석 × 2회차)은 INDEX(schedule_id, section)으로 구역 단위 조회, 별도 집계 테이블 없음. 병목이면 H2 캐시 | 3,000행 | 원문 산식 1,500 × 2회차 = 3,000 | [가정] 12-9 | 계산값 | docs/core/SA MASTER.md:2056; docs/core/SA MASTER.md:2067 | — |  |
| A-36 | 좌석 | 캐시 TTL(H2 도입 시): 공연 정보 5분, 좌석 상태 1초 | 5분 / 1초 | — | 결정 기록 #45, [가정] | 설계 | docs/core/SA MASTER.md:905 | — |  |
| A-37 | 결제 | 요청 전체 타임아웃(Gateway) 3초, PG 호출 타임아웃 연결 0.5초·응답 2초(전체 3초 중 나머지 1초는 DB·이벤트 저장) | 3초 / 연결 0.5초 + 응답 2초 | — | [가정] | 설계 | docs/core/SA MASTER.md:879; docs/core/SA MASTER.md:880 | — |  |
| A-38 | 결제 | pg-approve: Bulkhead 동시 10·대기 0, CB 최근 20회 중 실패율 50% 이상 OPEN, OPEN 10초, HALF_OPEN 시험 3회. pg-recovery: Bulkhead 동시 2, CB 같은 기준·상태 별도 | 동시 10 / 동시 2, 20회 중 50%, 10초, 3회 | — | [가정], 부하 측정 뒤 조정(S-20, ADR 0018) | 설계 | docs/core/SA MASTER.md:516; docs/core/SA MASTER.md:881; docs/core/SA MASTER.md:882 | — | 승인 장애와 복구가 서로 막지 않게 자원 분리 |
| A-39 | 결제 | DB 연결 대기(TX1·TX2) 1초, 커넥션 풀 서비스당 20 | 연결 대기 1초 / 풀 20 | — | [가정], payment-service는 config-repo에 반영(S-21) | 설계 | docs/core/SA MASTER.md:883; docs/core/SA MASTER.md:904 | — |  |
| A-40 | 결제 | Gateway 3초와 결제 승인: 최악 처리 시간 TX1(연결 대기 1초 이내) + PG 최대 2.5초 + TX2(1초 이내)가 Gateway 3초를 넘을 수 있음. 504 뒤에도 Payment 처리는 계속되고 같은 멱등키 재시도로 결과를 받음. 3초 기준은 바꾸지 않음 | 최악 4.5초 vs Gateway 3초 | 직접 계산: 1 + 2.5 + 1 = 4.5초; 4.5/3 = 1.5배 | [가정, N29로 확인](S-26, ADR 0018) | 계산값 | docs/core/SA MASTER.md:907 | — | 3초 타임아웃과 결제 처리 시간의 어긋남 |
| A-41 | 결제 | 결제 서비스 실행기 분리: Outbox 릴레이(1), 복구 작업(2[가정]), 정리·Gauge(1). 배경 작업의 DB 연결 최대 6개, 풀 20 | 스레드 1 / 2 / 1, 연결 최대 6, 풀 20 | — | S-21, ADR 0018·0022 | 설계 | docs/core/SA MASTER.md:908 | — | PG 재조회가 느려도 릴레이가 늦어지지 않게 |
| A-42 | 결제 | 결제 재조회 간격: 10초 × 6회 → 1분 → 5분 → 15분 → NEEDS_MANUAL_CHECK(총 약 21분). REQUESTED는 10초 넘으면 재조회 대상 | 총 약 21분 | 직접 계산: 1분 + 1분 + 5분 + 15분 = 22분(60초 구간을 10초×6회로 보면 1+1+5+15). 원문 "약 21분"과 1분 차이, 경계 해석 확인 못 함 | [가정], 결정 기록 #28 | 계산값 | docs/core/SA MASTER.md:281; docs/core/SA MASTER.md:895; docs/core/SA MASTER.md:896 | — |  |
| A-43 | 결제 | PG 타임아웃으로 UNKNOWN이 된 결제는 나중에 APPROVED가 될 수 있어 만료 뒤 승인(E8)과 자동 환불은 사라지지 않음. 확인 전에 좌석을 돌려주면 돈만 빠지는 고객이 생김(AI 검증 #2 수용) | — | — | 7-3, 14장 #2 | 판단 | docs/core/SA MASTER.md:634; docs/core/SA MASTER.md:2124 | — | 타임아웃은 실패가 아니라 결과 모름 |
| A-44 | 결제 | AI 피드백의 "결제 진행 중(PAYING) 예매는 만료에서 제외" 안을 쓰지 않고 만료 우선 + 자동 환불 + 결제 토큰 10초 앞당김을 택함(상태가 하나 줄고 Choreography와 맞음) | — | — | #35, #13 | 판단 | docs/core/SA MASTER.md:759; docs/core/SA MASTER.md:2314 | — | AI 제안 대신 다른 규칙을 고른 판단 |
| A-45 | 결제 | SAGA는 Orchestration 권고를 거부하고 Choreography. 일반 예매 참여자가 2개라 이벤트 구독으로 충분하고 결제 진입 경합은 서명 결제 토큰으로 막음 | 참여자 2개 | — | AI 검증 #3 거부 | 판단 | docs/core/SA MASTER.md:2125 | — | 근거를 갖고 AI 권고를 거부한 대표 사례 |
| A-46 | 결제 | Outbox·DLQ 범위: AI의 "최소 경로부터"를 부분 거부. 결제 결과가 사라지면 돈과 예매가 어긋나 고객 피해가 가장 크고 DLQ 없이는 원인 추적 불가 → 공통 모듈로 한 번 만들어 모든 토픽에 적용 | — | — | AI 검증 #6 | 판단 | docs/core/SA MASTER.md:2128 | — |  |
| A-47 | 결제 | Outbox 발행 재시도: 500ms에서 시작해 두 배씩 늘려 최대 60초 + Jitter, 횟수 한계로 포기하지 않고 영구 오류만 FAILED. 임대 30초[가정](프로듀서 delivery.timeout.ms의 2배 이상) | 500ms → 최대 60초, 임대 30초 | — | S-10, ADR 0022 | 설계 | docs/core/SA MASTER.md:901; docs/core/SA MASTER.md:902 | — |  |
| A-48 | 결제 | 이벤트 테이블 보관: outbox_events(SENT) 7일, processed_events 14일(이전 둘 다 7일), 멱등키 24시간, Kafka 토픽·DLQ 보관 7일[가정] | 7일 / 14일 / 24시간 (이전: 7일 / 7일) | — | S-10, ADR 0022 | 설계 | docs/core/SA MASTER.md:900; docs/core/SA MASTER.md:903; docs/core/SA MASTER.md:1372 | — |  |
| A-49 | 결제 | 결제 결과의 예매 반영 2초 이내(Outbox 폴링 500ms), Kafka 소비 재시도 3회 1·2·4초 지수 백오프 후 DLQ | 2초 / 500ms / 1·2·4초 | — | [가정], 결정 기록 #46 | 설계 | docs/core/SA MASTER.md:892; docs/core/SA MASTER.md:897 | — |  |
| A-50 | 결제 | 결제·예매 대사 RC-1~RC-8: 결제 서비스가 5분[가정]마다 최근 7일[가정] 대상으로 예매와 대조, 허용 지연 10분[가정], 기록·알림만 하고 데이터는 고치지 않음. 가장 심각한 RC-2는 결제 APPROVED인데 환불 의무가 등록되지 않은 경우 | 5분 / 7일 / 10분, 규칙 8개 | — | S-23, ADR 0029(사용자 확인 필요: 서비스 경계 해법) | 설계 | docs/core/SA MASTER.md:1081; docs/core/SA MASTER.md:1082; docs/core/SA MASTER.md:1083; docs/core/SA MASTER.md:1084 | — | 결제와 예매가 어긋나는 8가지 경우를 주기 점검 |
| A-51 | 결제 | NEEDS_MANUAL_CHECK는 종결이 아니라 운영자 확인 필요 상태. 감사되는 Ops 명령 6개로만 닫고 dry-run 기본·계획 해시·4-eyes, 직접 SQL 금지, 감사 테이블 UPDATE·DELETE·TRUNCATE 거부 트리거 | 명령 6개 | — | S-22, ADR 0028(사용자 확인 필요) | 설계 | docs/core/SA MASTER.md:1071; docs/core/SA MASTER.md:1072; docs/core/SA MASTER.md:1073; docs/core/SA MASTER.md:1077; docs/core/SA MASTER.md:1078 | — | 수동 복구도 감사 가능하게 |
| A-52 | 결제 | payment.command.dlq의 환불 요청을 DLQ 처리기가 환불 행으로 영속화해 자동 환불로 되돌림. 환불 워커가 9번째 PG 호출까지 확정 못 하면 NEEDS_MANUAL_CHECK | 9번째 호출 | — | S-8, ADR 0020 | 설계 | docs/core/SA MASTER.md:982; docs/core/SA MASTER.md:1080 | — |  |
| A-53 | 결제 | 결제 토큰 키 규칙: 난수 32바이트 → 소문자 hex 64자, HMAC 키는 문자열 UTF-8 64바이트(디코딩 안 함), 토큰 길이 상한 4,096자[가정], exp 허용 오차 0초, 알고리즘 HMAC-SHA256 고정 | 64자 hex / 64바이트 / 4,096자 / 0초 | — | S-13, ADR 0025(사용자 확인 필요: 키 바이트 해석) | 설계 | docs/core/SA MASTER.md:638; docs/core/SA MASTER.md:639; docs/core/SA MASTER.md:642 | — |  |
| A-54 | 결제 | 입장 토큰과 결제 토큰의 서명 키를 분리해 서로의 토큰을 위조할 수 없게 함. HMAC은 검증 키로 서명도 가능한 한계가 있고 비대칭 서명은 MVP 이후 | 키 2개(ENTRY_TOKEN_SECRET, PAYMENT_TOKEN_SECRET) | — | #56 | 판단 | docs/core/SA MASTER.md:627; docs/core/SA MASTER.md:631; docs/core/SA MASTER.md:632 | — |  |
| A-55 | 결제 | Kafka-UI를 READONLY로 둠(KAFKA_CLUSTERS_0_READONLY). 설정 이름은 [가정]이라 실제 동작 확인 필요 | — | — | S-24, ADR 0020 | 설계 | docs/core/SA MASTER.md:843 | — | 관리 UI에서 이벤트 위조 경로 차단 |
| A-56 | 결제 | Mock PG 장애 주입 모드는 기본 4개(NONE·DELAY·DECLINE·DROP_RESPONSE)에 7개(PROCESSING·SERVER_ERROR·BAD_REQUEST·MISSING_AMOUNT·WRONG_AMOUNT·FRACTIONAL_AMOUNT·REJECT)를 더함. WRONG_AMOUNT는 1,000원, FRACTIONAL_AMOUNT는 0.5원을 뺌. 응답 변조 모드는 test 프로필에서만 허용 | 4개 → 11개(직접 집계) | 직접 계산: 4 + 7 = 11 | S-29, ADR 0023 | 설계 | docs/core/SA MASTER.md:523; docs/core/SA MASTER.md:1349; docs/core/SA MASTER.md:1351 | — | Mock PG로 승인 후 응답 유실(E9) 재현 |
| A-57 | 결제 | Mock PG 원장 조회로 "PG를 몇 번 불렀는가"와 "PG에만 있는 승인"을 대조. 호출 수는 500·409처럼 저장하지 않는 응답도 셈 | — | — | test·loadtest 프로필, S-28 | 설계 | docs/core/SA MASTER.md:1347 | — |  |
| A-58 | 결제 | 결제 지표·알림 초기값 예: 결제 REQUESTED 최고령 60초, UNKNOWN 최고령 5분, 환불 대기 30분(심각 2시간), NEEDS_MANUAL_CHECK 30분(심각 24시간), Outbox PENDING 5분(심각 30분). 알림은 Alertmanager가 팀 슬랙으로 | 60초 / 5분 / 30분 / 24시간 | — | 초기값 [가정], S-12, ADR 0024·0045 | 설계 | docs/core/SA MASTER.md:946; docs/core/SA MASTER.md:948; docs/core/SA MASTER.md:966 | — | 돈이 확정 안 된 채 남은 건과 그 나이를 먼저 본다 |
| A-59 | 결제 | 결제 승인 처리 순서 10단계(인증 → 형식 → 같은 키 조회 → 토큰·금액 → 같은 예매 결제 조회 → 허가 획득 → TX1 → 토큰 만료 재검사 → PG → TX2) | 10단계 | — | S-6, ADR 0018·0017·0019 | 설계 | docs/core/SA MASTER.md:517; docs/core/SA MASTER.md:695; docs/core/SA MASTER.md:696; docs/core/SA MASTER.md:705 | — |  |
| A-60 | 결제 | D3 변경: 같은 예매 중복 결제 방지를 상태 조건 부분 유니크에서 UNIQUE(reservation_id) 전체로 바꿈(거절·환불된 예매도 두 번째 결제 행 불가). #58의 "재결제 가능" 문장은 11/9 정정 | — | — | v3.3, ADR 0026, S-16 | 판단 | docs/core/SA MASTER.md:1578; docs/core/SA MASTER.md:2229; docs/core/SA MASTER.md:2230; docs/core/SA MASTER.md:2414 | — | 재결제 여부와 추첨 U9의 충돌 |
| A-61 | 결제 | 예매의 결제 이벤트 처리를 상태별 규칙으로 바꿈: 같은 paymentId의 CONFIRMED·CANCELED는 멱등, 다른 paymentId는 DUPLICATE_APPROVAL 환불, EXPIRED·RELEASED·대조 실패 승인은 확정하지 않고 RefundRequested | — | — | 11/9 PR1, S-1·S-17, ADR 0016·0027 | 설계 | docs/core/SA MASTER.md:61; docs/core/SA MASTER.md:62; docs/core/SA MASTER.md:1021; docs/core/SA MASTER.md:1055; docs/core/SA MASTER.md:1056 | — |  |
| A-62 | 추첨 | 응모 접수를 Kafka 접수(10/26 거부 판단)에서 동기 INSERT(응답 201)로 바꿈. 11/10 L1(마감 직전 몰림) p95 10.58ms로 기준 300ms 안 → 동기 유지. 접수 행 + outbox 방식과 비교 측정은 하지 않음 | p95 10.58ms (기준 300ms) | 직접 계산: 300 / 10.58 ≈ 28.4배 여유 | 11/10 L1, 측정 환경(로컬/배포)은 이 문서에 없음, 원본은 docs/design/lottery/numbers-and-measurement.md 3-3 | 측정(환경 확인 못 함) | docs/core/SA MASTER.md:19; docs/core/SA MASTER.md:374; docs/core/SA MASTER.md:985; docs/core/SA MASTER.md:2133; docs/core/SA MASTER.md:2274 | — | 응모를 Kafka로 접수하려다 동기로 돌아온 이야기 |
| A-63 | 추첨 | 응모 응답 202 → 201, 응모 상태에서 REQUESTED 삭제. 팀 확인 전에 먼저 반영(선조치 후보고) | 202 → 201 | — | 11/15, ISE-269 | 판단 | docs/core/SA MASTER.md:15; docs/core/SA MASTER.md:21 | — |  |
| A-64 | 추첨 | AI 검증 #4(응모 처리): 유니크 제약은 수용, 응모 동기 처리는 10/26에 거부(결과가 바로 필요 없고 마감 직전에 몰림, 결제 결과와 같은 패턴 공유) → 10/29 재검토로 #11 수용. 10/26 판단은 이력으로 남김 | — | — | 10/26 → 10/29 → 11/15 | 판단 | docs/core/SA MASTER.md:2126; docs/core/SA MASTER.md:2133; docs/core/SA MASTER.md:2134 | — | AI에게 맞서 거부했던 판단이 측정으로 뒤집힌 반전 |
| A-65 | 추첨 | 추첨 진행 순서: 응모 → 마감 → 추첨·자동결제 → 승계 마감(추첨 후 30분) → 종결(최장 추첨 후 60분). 설정 검증 draw_at + 60분 <= starts_at. 추첨 시각은 저트래픽 시간대 권장 | 30분 / 60분 | — | 10/31, 11/2 | 설계 | docs/core/SA MASTER.md:76; docs/core/SA MASTER.md:382 | — |  |
| A-66 | 추첨 | 순위는 시드 기반 해시 정렬 SHA-256(seed ‖ entryId), seed는 BIGINT에서 BYTEA 32바이트(SecureRandom, ADR 0041) | 32바이트 | — | 11/2, ADR 0041 | 설계 | docs/core/SA MASTER.md:75; docs/core/SA MASTER.md:384; docs/core/SA MASTER.md:1531 | — | 추첨 공정성을 시드로 사후 검증 |
| A-67 | 추첨 | 추첨석은 한 등급에서 좌석 수만큼 떼어 등급 LOTTERY로 표시해 일반 선점과 좌석이 겹치지 않음. 서비스 3개 근거의 추첨 문장을 이 방식으로 바꿈(선반영, 팀 확인 대기) | — | — | 10/31, D9, ADR 0037 | 판단 | docs/core/SA MASTER.md:86; docs/core/SA MASTER.md:92; docs/core/SA MASTER.md:586 | — |  |
| A-68 | 추첨 | 추첨을 Should에서 Must로 올림. 기본 기능은 M2, 통합 테스트는 M3, 측정·수치 기반 개선은 M4 | Should → Must | — | 11/2 팀 합의 | 판단 | docs/core/SA MASTER.md:77; docs/core/milestones.md:5 | — |  |
| A-69 | 추첨 | U19: 응모 접수 변경 조건은 p95 300ms 초과. 측정 시나리오 1은 마감 직전 초당 100건 | 초당 100건 / p95 300ms | — | 10/31 계획, 11/15 갱신 | 설계 | docs/core/SA MASTER.md:2274 | — |  |
| A-70 | 추첨 | 자동결제 방식은 빌링키(잠정), 대안 가승인은 쓰지 않음. 응모 때 빌링키는 확인하지 않고 결제 시점 실패 → 승계. 결제 거절 reasonCode로 분기(NOT_SENT는 새 예매로 1회 재시도) | NOT_SENT 재시도 1회 | — | 10/31, D4·D12, ADR 0038(결제 담당 확인 대기 ISE-89) | 판단 | docs/core/SA MASTER.md:88; docs/core/SA MASTER.md:91; docs/core/SA MASTER.md:385; docs/core/SA MASTER.md:2268 | — |  |
| A-71 | 인프라 | 서비스는 3개(User·Reservation·Payment), 대기열·추첨·알림은 Reservation 안 모듈. Queue는 순번 조회가 선점 p95를 늘리면 분리(측정 조건). 5개 → 3개 | 5개 → 3개 | — | AI 검증 #1(Lottery 병합 수용 / Queue 분리 거부), #10·#14 | 판단 | docs/core/SA MASTER.md:586; docs/core/SA MASTER.md:587; docs/core/SA MASTER.md:2123; docs/core/SA MASTER.md:2236 | — | 서비스를 5개에서 3개로 줄인 이유 |
| A-72 | 인프라 | 기술 스택: Java 21 LTS, Spring Boot 4.1.2, Spring Cloud 2025.1.4, Gradle 9.8.2, PostgreSQL 17, 컨테이너 eclipse-temurin:21-jre. Java 25 대신 21(Junhyun An 결정, 이유 미기재, 팀 전체 합의 표기 안 함), 가상 스레드는 기본 끔 | Java 25 → 21 | — | 2026-10-26 결정, 10/27 구현 버전 확정 | 판단 | docs/core/SA MASTER.md:122; docs/core/SA MASTER.md:156; docs/core/SA MASTER.md:810; docs/core/SA MASTER.md:811; docs/core/SA MASTER.md:812 | — | Java 25 대신 21을 고른 이유가 미기재 |
| A-73 | 인프라 | Config 서버(native, config-repo/, 포트 8888)를 둠. 이전 안은 Config 서버 없이 .env+프로필(1차 AI 피드백 "과잉" 지적)이었으나 10/26 GGangMY 판단으로 바꿈, 10/27 #30 결정 | — | — | 10/26 → 10/27 | 판단 | docs/core/SA MASTER.md:121; docs/core/SA MASTER.md:944; docs/core/SA MASTER.md:2340 | — | AI의 과잉 지적을 뒤집은 판단 |
| A-74 | 인프라 | EC2 1대 docker compose 배포. 서비스 단위 격리는 컨테이너 수준, 인스턴스 장애 시 전체 중단. 운영이라면 서비스별 다중 인스턴스 + 매니지드 DB·Redis·Kafka로 분리 | EC2 1대 | — | 10/26 피드백 답변 | 설계 | docs/core/SA MASTER.md:871; docs/core/SA MASTER.md:2246 | — |  |
| A-75 | 인프라 | 보안그룹: 외부 허용은 22(팀원 IP)와 8080(gateway, 측정 중에는 측정 기기 IP로 제한), 관리자 UI는 SSH 터널, 내부 포트는 compose 내부 전용. compose에서 관리자 UI는 127.0.0.1 바인딩 | — | — | #57 | 설계 | docs/core/SA MASTER.md:834; docs/core/SA MASTER.md:835; docs/core/SA MASTER.md:836; docs/core/SA MASTER.md:837; docs/core/SA MASTER.md:838; docs/core/SA MASTER.md:839; docs/core/SA MASTER.md:840; docs/core/SA MASTER.md:841 | — |  |
| A-76 | 인프라 | Redis 호출 타임아웃 100ms(정상 응답 1ms 안팎), 서킷 브레이커 최근 20회 중 50% 실패 또는 50ms 초과 시 OPEN, 10초 뒤 HALF_OPEN 5회 시험 | 100ms / 50ms / 20회·50% / 10초 / 5회 | — | [가정], #48 | 설계 | docs/core/SA MASTER.md:898; docs/core/SA MASTER.md:899; docs/core/SA MASTER.md:912; docs/core/SA MASTER.md:914 | — | Redis는 죽는 것보다 느려지는 게 위험 |
| A-77 | 인프라 | Redis 장애 정책: 대기열 진입·입장 closed(503 + Retry-After), 입장 토큰 검증은 서명으로 통과, 로그아웃 블랙리스트 closed(2026-10-27 ADR 0005, 이전 안 open), 요청 제한은 로컬 카운터로 open, 캐시는 DB로 대체 | — | — | 9-5 | 판단 | docs/core/SA MASTER.md:917; docs/core/SA MASTER.md:918; docs/core/SA MASTER.md:919; docs/core/SA MASTER.md:920; docs/core/SA MASTER.md:921; docs/core/SA MASTER.md:922; docs/core/SA MASTER.md:923 | — | 블랙리스트 open → closed 번복 |
| A-78 | 인프라 | 적재량 추정(1공연 2회차, 회차당 1,500석): reservations 1,200~4,000, reservation_seats 3,000~10,000, tickets 3,000 이하, payments 1,500, 입장자 50,000명이면 user_schedule_quotas 100,000행. 용량 1GB를 넘지 않을 것으로 보임(행 크기 미측정) | 표 참조 | 원문 산식: 확정 약 1,200 = 3,000석 ÷ 평균 2.5매; 입장자 50,000 × 2회차 = 100,000 | [가정], 2026-11-16 ADR 0060으로 15,000석 기준에서 변경 | 계산값 | docs/core/SA MASTER.md:2051; docs/core/SA MASTER.md:2055; docs/core/SA MASTER.md:2056; docs/core/SA MASTER.md:2057; docs/core/SA MASTER.md:2058; docs/core/SA MASTER.md:2060; docs/core/SA MASTER.md:2061; docs/core/SA MASTER.md:2063; docs/core/SA MASTER.md:2065 | — |  |
| A-79 | 인프라 | 측정 회차는 지우지 않아 schedule_seats가 추정(3,000행)보다 훨씬 커짐(11/12 배포 DB 약 156만 행). 새 회차의 통계가 갱신되지 않는 문제는 ISE-284 | 추정 3,000행 → 약 156만 행 | 직접 계산: 1,560,000 / 3,000 = 520배("약 156만"을 1,560,000으로 본 근사) | 11/12 배포 DB, 측정 방법은 이 문서에 없음 | 배포 측정 | docs/core/SA MASTER.md:2066 | — | 측정 데이터가 쌓여 통계가 낡는 문제 |
| A-80 | 인프라 | venue_seats 시드 15,000행 중 13,500행은 소프트 삭제, 쓰는 행 1,500 | 15,000 → 1,500(13,500 삭제 표시) | 직접 계산: 15,000 − 1,500 = 13,500(원문 일치) | ADR 0060 | 설계 | docs/core/SA MASTER.md:2055 | — |  |
| A-81 | 인프라 | 앱 포트: gateway 8080, user 8081, reservation 8082, payment 8083, mock-pg 8090, eureka 8761, config-server 8888, 관리 포트 9080(로컬 바인딩 18080 등). PostgreSQL 3개 로컬 5433·5434·5435 | — | — | 10/27 통합 코드 기준(U12) | 설계 | docs/core/SA MASTER.md:815; docs/core/SA MASTER.md:817; docs/core/SA MASTER.md:818; docs/core/SA MASTER.md:819; docs/core/SA MASTER.md:820; docs/core/SA MASTER.md:821; docs/core/SA MASTER.md:822; docs/core/SA MASTER.md:823; docs/core/SA MASTER.md:824; docs/core/SA MASTER.md:825; docs/core/SA MASTER.md:826 | — |  |
| A-82 | 인프라 | 프로필 local·test·loadtest(Mock PG, 요청 제한 완화, 샘플링 낮춤)·prod. loadtest 준비: 가상 사용자 토큰 사전 발급, 측정마다 좌석 초기화 스크립트 | — | — | 9-1, 3-1 | 설계 | docs/core/SA MASTER.md:310; docs/core/SA MASTER.md:813 | — |  |
| A-83 | AI | AI 검증 로그는 11행. 제출 기준은 3건 이상, 거부 1건 이상, 대표 사례 2건 이상. 발표 대표 사례 후보는 #1(교차 검토 → 원문 대조 → 종합), #3(권고를 근거 갖고 거부), #9(AI 제안 두 번을 받지 않고 검토 지적만 수용) | 11건(기준 3건 이상) | 직접 계산: 11 / 3 ≈ 3.7배 | 14장 | 설계 | docs/core/SA MASTER.md:2117; docs/core/SA MASTER.md:2121; docs/core/SA MASTER.md:2135 | — | AI 검증 대표 사례 3건 |
| A-84 | AI | 표 하단 집계는 거부 4건(#1·#3·#4·#9)·부분 거부 2건(#5·#6)·수용 5건·부분 수용 1건. 직접 합산하면 4+2+5+1 = 12로 표의 11행과 다름(#1·#4가 수용과 거부가 섞인 행). 인용 전 확인 필요 | 12 vs 11행 | 직접 계산: 4+2+5+1 = 12 ≠ 11 | 14장 집계 줄 | 모순 | docs/core/SA MASTER.md:2134; docs/core/SA MASTER.md:19 | — |  |
| A-85 | AI | AI 검증 #9 API 응답 형식: AI의 기존 형식 유지·6키 통일 제안을 거부하고 성공·실패 다른 형태 + 둘 다 traceId(#19)로 결정, 검토 지적(traceId·Gateway 처리기·HTTP 코드 유지)만 수용 | — | — | 10/26, #19 | 판단 | docs/core/SA MASTER.md:2131; docs/core/SA MASTER.md:1131 | — | AI 제안 두 번을 받지 않은 판단 |
| A-86 | AI | AI 검증 #7 계층형 아키텍처(user-service 코드 기준, 10/27 실행): 인프라 → 응용 의존 1곳·응용의 영속 예외 의존 1곳은 수용, 토큰 응답 레코드 분리는 거부, AuthService 239줄 Fat Service 경계선은 보류 | 의존 위반 2곳, AuthService 239줄 | — | 10/27, 상세는 docs/sa-review/06-layered-architecture-check.md | 판단 | docs/core/SA MASTER.md:2129 | — | 계층 의존을 AI로 점검해 부분 수용 |
| A-87 | AI | AI 검증 #5 Kafka 파티션 키: 정합성은 DB가 지킨다는 원칙은 수용, 접수형 키는 회차 + 구역으로 부분 거부(같은 구역 한 줄 처리로 락 경합 감소, 인기 회차가 파티션 하나에 몰리지 않게) | — | — | 14장 #5 | 판단 | docs/core/SA MASTER.md:2127 | — |  |
| A-88 | AI | AI 검증 #1 서비스 경계: Lottery 병합은 수용, Queue 분리 권고는 거부(자기 데이터 없음, Queue가 멈추면 예매도 멈춰 장애 격리 불성립) | 서비스 5개 → 3개 | — | 14장 #1 | 판단 | docs/core/SA MASTER.md:586; docs/core/SA MASTER.md:2123 | — |  |
| A-89 | 팀 | 결정 기록 54건: 결정 15·잠정 결정 37·보류 2(10/26 기준). 10/27에 #30을 결정으로 정함 | 54 = 15 + 37 + 2 | 계산 일치 | 10/26 | 설계 | docs/core/SA MASTER.md:11; docs/core/SA MASTER.md:2186 | — |  |
| A-90 | 팀 | 팀 목표: 팀원 모두가 자기 담당 고도화를 "무엇이 문제였고, 무엇과 비교해, 왜 이 방식을 골랐는지"로 설명할 수 있게 한다. 점검 문제 11개(서비스 3개·동기→접수형·오류 정의·측정 환경·분산락 등) | 점검 문제 11개 | — | 3장, 16장 | 설계 | docs/core/SA MASTER.md:301; docs/core/SA MASTER.md:2232; docs/core/SA MASTER.md:2236; docs/core/SA MASTER.md:2246 | — |  |
| A-91 | 팀 | 담당: 권영은(대기열), 최권우(좌석 선점), 반효승(공연·좌석·예매 흐름), 안준현(결제·Mock PG·프론트·AI 상담), 이강민(User·공통 → 추첨). 10/28 이강민 역할 변경, Mock PG 포함 결제 전부 안준현 | 5인 | — | 10/25 합의, 10/28 변경 | 설계 | docs/core/SA MASTER.md:107; docs/core/SA MASTER.md:449; docs/core/SA MASTER.md:452; docs/core/SA MASTER.md:453; docs/core/SA MASTER.md:454; docs/core/SA MASTER.md:455; docs/core/SA MASTER.md:456; docs/core/SA MASTER.md:457; docs/core/SA MASTER.md:458; docs/core/SA MASTER.md:2411 | — |  |
| A-92 | 팀 | 일정: M1 10/27~10/29, M2 10/30~11/3, M3 11/4~11/6(MVP 시연 11/6), M4 11/9~11/16, 발표 자료 준비 11/16~11/18, 제출 마감 11/18, 발표 11/19, 수료식 11/20 | M1 3일 / M2 5일 / M3 3일 / M4 8일 | 직접 계산(양끝 포함 일수): 10/27–10/29 = 3, 10/30–11/3 = 5, 11/4–11/6 = 3, 11/9–11/16 = 8 | milestones 표 | 설계 | docs/core/milestones.md:7-14 | — | 마일스톤 흐름 |
| A-93 | 팀 | M1 완료 기준: docker compose 한 번으로 인프라와 Config 서버·Gateway·Eureka·User가 뜨고, 회원가입 → 로그인 → Gateway 인증 요청에서 X-User-Id·X-User-Role이 서비스까지 전달. 공통 모듈은 각자 만들어 비교 후 하나로 합침 | — | — | 10/27~10/29 | 설계 | docs/core/milestones.md:20-24; docs/core/milestones.md:36-41 | — | 공통 모듈 구현을 비교·병합 |
| A-94 | 팀 | M2 완료: 입장 → 선점 → 결제 → 확정이 API로 끝까지 돌고 동기형 기준선 1회 측정 + 추첨 기본 기능이 단위·통합 테스트로 동작. 연동 순서는 공연·좌석 → 좌석 선점 → 예매 ↔ 결제, 대기열은 병렬 | — | — | 10/30~11/3 | 설계 | docs/core/milestones.md:57-62 | — |  |
| A-95 | 팀 | M3 완료: Mock PG 장애 주입으로 거절·타임아웃·만료 뒤 승인 환불 재현, 추첨 E2E 통합 테스트, EC2 배포 1회, 11/6 MVP 시연 | — | — | 11/4~11/6 | 설계 | docs/core/milestones.md:66-81 | — |  |
| A-96 | 팀 | M4 완료: 동기형·접수형 비교 수치, 추첨 측정 결과로 응모 접수(D3)·자동결제 PG 그룹(D6) 개선 여부 결정, 장애 주입·대사 결과와 트러블슈팅 정리. M4 이후 발표 준비 11/16~ | — | — | 11/9~11/16 | 설계 | docs/core/milestones.md:85-100; docs/core/milestones.md:14 | — |  |
| A-97 | 팀 | 저장소 기술: Java 21, Spring Boot 4.1.2, Spring Cloud 2025.1.4, 앱 7개(Config Server·Eureka·Gateway·User·Reservation·Payment·Mock PG)와 공통 라이브러리 | 앱 7개 | — | README 현재 | 설계 | README.md:30 | — |  |
| A-98 | 팀 | 모순: README.md:3·milestones.md:3은 정본을 Notion "SA MASTER v3 (통합)"으로, AGENTS.md:8·docs/README.md:50은 v3.3으로 적음. AGENTS.md:7은 "버전과 모듈 구성은 2차 SA에서 확정"이라 하나 SA MASTER 9-1은 10/27에 확정 | v3 vs v3.3 | — | 문서 간 갱신 시점 차이 | 모순 | README.md:3; docs/core/milestones.md:3; AGENTS.md:7-8; docs/README.md:50 | — |  |
| A-99 | 팀 | AI 활용 기록 운영: 질문은 learning 노트, AI 판단은 ai-judgments, 되돌리기 어려운 결정은 ADR, 해결한 문제는 troubleshooting에 에이전트가 동의를 기다리지 않고 남김. 판단 근거는 사용자가 말한 대로(말하지 않으면 미기재) | 기록 4종 | — | AGENTS.md 대전제 2·3·4 | 설계 | AGENTS.md:13-15; AGENTS.md:48; README.md:13-26 | — | AI 활용을 기록 구조로 남긴 방식 |
| A-100 | 팀 | AI 공동 작성자 표기 금지(커밋·PR에 Co-Authored-By·생성 도구 문구 불가), 커밋 제목 type(scope): 요약, 비밀값 커밋 금지, 코드 주석은 한국어 평서체 | — | — | AGENTS.md 대전제 7·8·9 | 설계 | AGENTS.md:18-20 | — |  |
| A-101 | 팀 | 서비스 간 접점(이벤트·토큰·내부 API·공유 설정) 변경은 Linear contract 라벨 이슈로 알리고 작업 전에 확인 | — | — | AGENTS.md 대전제 11 | 설계 | AGENTS.md:22 | — |  |
| A-102 | 팀 | 품질 기준: 빌드·테스트 통과, 변경 동작 테스트, 실행 필요한 변경은 실제 기동 확인(못 했으면 "미확인"), 빌드 성공을 API 확인으로 대신하지 않음. 디버깅은 재현 → 가설 → 확인 → 수정 → 회귀 테스트, 같은 수정을 두 번 실패하면 가설 재수립 | — | — | docs/agents/quality-gates.md | 설계 | docs/agents/quality-gates.md:5-25 | — |  |
| A-103 | 팀 | 검증 명령: ./gradlew verifyWorkspace --no-daemon, ./gradlew :system-tests:systemTest -PredisFailure=true -PcoldStart=true --no-daemon (README는 -PredisFailure=true만) | — | — | quality-gates, README | 설계 | docs/agents/quality-gates.md:3; README.md:32-38 | — |  |
| A-104 | 팀 | 학습 운영: 설명 순서 결론 → 이유 → 이 프로젝트에서는 → 더 깊이, 작업 마무리 때 면접 질문 1~2개를 노트에 남김, 판단 근거를 스스로 말해 보는 것이 면접 준비 | 면접 질문 1~2개 | — | learning-guide | 설계 | docs/agents/learning-guide.md:6-8; docs/agents/learning-guide.md:19-25 | — |  |
| A-105 | 팀 | 문서 지도: AI 판단·학습·ADR·troubleshooting·발표 자료·설계·시연·테스트 시나리오·가이드로 구성. sa-review 문서 6개(01~06) + 구현 브랜치 통합 검토 1개 | sa-review 문서 6개 + 통합 검토 1개 | — | docs/README.md | 설계 | docs/README.md:7-30; docs/README.md:38-46 | — |  |
| A-106 | 기타 | 시스템 구조(mermaid)와 컴포넌트 포트·메모리(9-1, 9-3)는 EC2 1대 compose 한 벌: gateway·eureka·config-server·user·reservation·payment·mock-pg·postgres×3·redis·kafka(KRaft)·kafka-ui·prometheus·grafana·zipkin | 컨테이너 약 16개(직접 집계: 앱 7 + postgres 3 + redis + kafka + kafka-ui + prometheus + grafana + zipkin = 16) | 직접 계산: 7+3+1+1+1+1+1+1 = 16 | 9-1 구조도 | 설계 | docs/core/SA MASTER.md:765; docs/core/SA MASTER.md:766; docs/core/SA MASTER.md:767; docs/core/SA MASTER.md:768; docs/core/SA MASTER.md:769; docs/core/SA MASTER.md:770; docs/core/SA MASTER.md:771; docs/core/SA MASTER.md:772; docs/core/SA MASTER.md:773; docs/core/SA MASTER.md:774; docs/core/SA MASTER.md:775; docs/core/SA MASTER.md:776; docs/core/SA MASTER.md:777; docs/core/SA MASTER.md:778; docs/core/SA MASTER.md:779; docs/core/SA MASTER.md:780; docs/core/SA MASTER.md:781; docs/core/SA MASTER.md:782; docs/core/SA MASTER.md:783; docs/core/SA MASTER.md:784; docs/core/SA MASTER.md:785 | docs/core/SA MASTER.md:765-800 (mermaid 인프라 구조도) | 한 대 EC2에 컨테이너 16개를 올리는 구조 |
| A-107 | 기타 | 예외 시나리오 E1~E16 정의(동일 좌석 동시 선택 1명만 성공, 연타 1건, PG 타임아웃 UNKNOWN, Redis 지연 시 대기열만 503 등). 검증 기준으로 사용 | 16개 | — | 2-5 | 설계 | docs/core/SA MASTER.md:245; docs/core/SA MASTER.md:247; docs/core/SA MASTER.md:249; docs/core/SA MASTER.md:255; docs/core/SA MASTER.md:257; docs/core/SA MASTER.md:262 | — | 시연·테스트 케이스 16개 |
| B-01 | Gateway | Redis는 멀쩡한데 인증 차단기가 열렸다(차단기가 Gateway 자신의 혼잡을 Redis 장애로 오해) | Redis 컨테이너 CPU 8%, 조회 0.3~1.9ms | — | 11/9~10 부하 측정 | 배포 측정 | RES:8 | — | Redis는 정상인데 인증 전체 503 |
| B-02 | Gateway | 1.9ms는 평소가 아니라 열리기 직전 몰림 중 평균, 평소는 0.3ms | 1.9ms 대 0.3ms | 1.9/0.3=6.3배(직접 계산) | 몰림 중 vs 평소 | 배포 측정 | RES:46 | — | 직관("시한을 조여라")이 구조상 반대로 작동 |
| B-03 | Gateway | 라이브러리 기본 벌크헤드 25가 모든 리액티브 차단기에 씌워져 있었다("설정에 없음 ≠ 기능 없음") | 벌크헤드 25(기본값) | — | `bulkhead.resilience4j.enabled` 기본 true, 바이트코드 확인 | 설계 | RES:39 | — | AI 판단 거부 1: "Gateway에 벌크헤드 없다" |
| B-04 | Gateway | 호출 순서: 시간 제한 1초 → 차단기(창 20·최소 20·실패율 50%·느린 호출 50ms·50%·열림 10초·반열림 5건) → 벌크헤드 25·대기 0 → Redis 조회(필터·Lettuce 시한 100ms) | 위 값(운영, ADR 0050 이전) | — | spring-cloud-circuitbreaker-resilience4j 5.0.3 바이트코드, `config-repo/gateway.yml:58-65` | 설계 | RES:29-34 | — | 호출 하나가 거치는 장치 |
| B-05 | Gateway | 벌크헤드 25 대 100 비교: 둘 다 초당 1,000명 통과, 2,000명에서 열림(한도 같음) | 실패 건수 33 → 7건(100일 때), p95는 늘었다 | 33/7=4.7배 감소(직접 계산) | 데운 Q1, 덤 몰림 웜업 표준 | 배포 측정 | RES:41 | — | 벌크헤드는 한도를 정하지 않는다(반증) |
| B-06 | 대기열 | 워밍업 뒤 같은 초당 300명은 오류 0, p95 5.51ms(재기동 직후 몰림이 차단기에 막혔던 것과 대비) | 오류 0, p95 5.51ms | — | 11/9 배포 Q1, 초당 300명 | 배포 측정 | RES:40 | — | 측정용으로 차단기를 풀자는 AI 제안 거부(판단 거부 2) |
| B-07 | 추첨 | L1 응모 C1(콜드 시작 초당 800)에서 15초 만에 열렸고 그동안 reservation CPU 2%, Postgres 0.3% | 열림까지 15초, reservation CPU 2%, Postgres 0.3% | — | 11/10 L1 C1, 초당 800 콜드 | 배포 측정 | RES:42 | — | 서비스는 한가한데 Gateway에서 거절 |
| B-08 | Gateway | 건수 창 20은 초당 1,000건에서 0.02초 분량 | 20건 ÷ 1,000/s = 0.02초 | 원문 계산값 | 창 크기 | 계산값 | RES:42,89(STORY) | — | 창 크기는 처리량과 함께 정한다 |
| B-09 | Gateway | C3(차단기만 완화): 1,200/s 거절 4.8%, 1,600/s p99 8.8s | 거절 4.8%, p99 8.8s | — | 측정 모드, 차단기 완화 | 배포 측정 | RES:43 | — | 풀면 병목이 옮겨 간다 |
| B-10 | Gateway | D(차단기 완화+벌크헤드 100+reservation DB 풀 20): p95 300ms 한도가 약 800/s에서 약 1,200/s | 800/s → 1,200/s | 1.5배(원문 계산값) | 측정 모드 | 계산값 | RES:43 | — | 풀면 병목이 옮겨 간다(C2 → D) |
| B-11 | Gateway | D에서 처리량은 약 1,600/s에서 정체, 서버 CPU 100%, 1,600/s p95 1.1s | 약 1,600/s, CPU 100%, p95 1.1s | — | 측정 모드 D | 배포 측정 | RES:43 | — | 마지막에는 장비 CPU가 막는다 |
| B-12 | Gateway | D와 E의 p95 차이(1,200/s 238ms 대 339ms)는 각 1회 측정이고 E는 추첨 DB 처리와 겹쳐 확인 못 함 | 238ms 대 339ms | 339/238=1.42배(직접 계산) | 1회 측정 | 배포 측정 | RES:107 | — | — |
| B-13 | Gateway | #463에서 실패 원인 6가지 지표 추가: lookup_timeout, time_limiter, bulkhead_full, breaker_open, redis_error, other | 6가지 | — | ISE-230, #463 | 설계 | RES:44 | — | 원인을 보려 했는데 기록이 없었다 |
| B-14 | Gateway | 안쪽 100ms 시한 초과가 차단기에 두 번(실제 시간 + 1,000ms) 기록되던 것 발견. 측정의 "실패 최대 1.0s"는 기록값일 가능성이 큼(확인 안 됨). 고친 뒤 R1 실제 110~115ms | 1.0s → 110~115ms(R1) | — | #463 전후 | 배포 측정 | RES:44,94(STORY) | — | 측정 도구가 보여 주는 숫자도 검증 대상 |
| B-15 | Gateway | #463 main 배포가 Gateway 기동 실패로 되돌려짐(생성자 둘인 `@Component`에 `@Autowired` 없음, 앱 전체 기동 테스트 없음) | — | — | CI 전부 통과했는데 기동 실패 | 배포 측정 | RES:45 | — | CI 통과 ≠ 기동 보장(TS-53) |
| B-16 | Gateway | 재측정 값 정리: 창 건수 20/최소 20 → 시간 10초/최소 500건, 느린 호출 50ms·50% → 100ms·80%, 열림 10초·5건 → 5초·20건, 필터 시한 100 → 200ms, Lettuce 100 → 300ms, 벌크헤드 25 → 100(1회) → 50+거절 제외(2회) | 위 값 | — | G1, 11/10 UTC, 2회 | 설계 | RES:63-71 | — | 한 번에 하나만 바꾸기 위해 회를 나눔 |
| B-17 | Gateway | 벌크헤드 50은 1,000/s × 50ms로 낸 계산값이고 측정 근거 없음 | 1,000/s × 50ms | — | — | 계산값 | RES:71 | — | — |
| B-18 | Gateway | 재측정 G1 2,000/s: 1회 성공 99.53% p95 208ms, 2회 98.90% p95 157ms | 성공 99.53% → 98.90%, p95 208 → 157ms | p95 208/157=1.32배 감소(직접 계산) | G1 `GET /schedules/{id}/queue/me`, 11/10 UTC, 각 1회 | 배포 측정 | RES:80 | — | — |
| B-19 | Gateway | 재측정 G1 2,500/s: 1회 98.16% p95 497ms, 2회 95.58% p95 428ms | 성공 98.16% → 95.58%, p95 497 → 428ms | 497/428=1.16배(직접 계산) | 상동 | 배포 측정 | RES:81 | — | — |
| B-20 | Gateway | 재측정 G1 3,000/s: 1회 92.67% p95 1.01s, 2회 87.96% p95 806ms | 성공 92.67% → 87.96%, p95 1.01s → 806ms | 1010/806=1.25배(직접 계산) | 상동 | 배포 측정 | RES:82 | — | — |
| B-21 | Gateway | 두 회 모두 3,000/s까지 차단기가 열리지 않았고 503은 모두 `bulkhead_full`, Redis 시한 초과·연결 오류 0건 | 열림 0, Redis 오류 0건 | — | G1 재측정 1·2회 | 배포 측정 | RES:84 | — | 오탐이 재현되지 않음 |
| B-22 | Gateway | 2회에서 거절이 차단기 집계에서 모두 빠짐(`failed` 0, `ignored` 16,024) | failed 0, ignored 16,024건 | — | `ignoreExceptions` 적용 확인 | 배포 측정 | RES:85,104 | — | 실패의 뜻을 바꿈(장면 2) |
| B-23 | Gateway | 성공 처리량은 두 회 모두 약 2,300~2,470/s에서 멈췄고 서버 CPU 99% | 약 2,300~2,470/s, CPU 99% | — | 재측정 1·2회 | 배포 측정 | RES:86 | — | 한도가 CPU로 옮겨 감 |
| B-24 | Gateway | 5차 0a: 서버 CPU 90%는 1,000/s(평균 75%)와 1,500/s(평균 93%) 사이에서 넘음, 굿풋 약 1,470/s에서 멈춤 | 75% → 93%, 굿풋 약 1,470/s | 93−75=18%p(직접 계산) | 배포 서버 5차 0a, 11/11 UTC | 배포 측정 | RES:92 | — | 기준선 |
| B-25 | Gateway | 0a 2,500/s에서 사용자 p95 878ms인데 차단기가 잰 호출 시간 평균은 11~17ms, 느린 호출 비율 0.13% 이하 | p95 878ms 대 평균 11~17ms | 878/17=약 52배~878/11=약 80배(직접 계산, 서로 다른 통계량이라 참고) | 서버 CPU 99% | 배포 측정 | RES:93 | — | 장치는 자기가 재는 구간만 본다 |
| B-26 | Gateway | CPU 몫: gateway 50~55%, reservation 23%, alloy 7~10%, kafka·redis 각 4~10%. Gateway 안 HTTP 이벤트 루프 약 70%, Lettuce 응답 스레드 약 20% | 위 값 | — | 5차 0a | 배포 측정 | RES:94 | — | 어디가 CPU를 쓰나 |
| B-27 | Gateway | B20(느린 호출 20ms)도 2,500/s까지 열리지 않음(느린 호출 비율 최대 24%). 차단기로 CPU 포화를 잡는다는 가설 닫음 | 열림 0, 비율 최대 24%(기준 80%) | — | 5차 B20 | 배포 측정 | RES:95, STORY:133 | — | 가설 실패, AI 추천과 같은 결론이나 근거가 숫자가 됨 |
| B-28 | Gateway | 0b JFR Gateway Java CPU 몫: 관측 약 24%, 헤더 처리 약 7%, JWT 6.6%, Redis·차단기 2.5% | 24%, 7%, 6.6%, 2.5% | — | 5차 0b JFR | 배포 측정 | RES:96, STORY:119 | — | 관측이 가장 큰 몫 |
| B-29 | Gateway | M1(상한 1,024MiB, 직접 메모리 128MiB) OOM 0건, 메모리 최대 849MiB, 그러나 2,000/s p95 752ms | OOM 3회 → 0회, p95 435 → 752ms | 752/435=1.73배 악화(직접 계산) | OOM 조건 재현 | 배포 측정 | RES:97, STORY:148 | — | 죽는 실패가 느려지는 실패로 |
| B-30 | Gateway | Gateway가 컨테이너 메모리 한도 768MiB로 세 번 강제 종료(08:18:53·08:25:46·08:35:11Z, anon-rss 757~758MB, 활성 VU 5,309~5,938) | 768MiB, 3회, 757~758MB | — | 0a 도중 | 배포 측정 | RES:98, STORY:144 | — | OOM |
| B-31 | Gateway | OOM 계산: 힙 512MB + 비힙 약 158MB + JVM 밖 약 130MB ≈ 800MB > 768MiB | 512+158+130≈800MB | 800−768=32MB 초과(직접 계산) | 계산값 | 계산값 | RES:98, STORY:152 | — | 메모리 예산 |
| B-32 | Gateway | 요청당 약 47KiB로 (768−500)÷0.047≈5,700개에서 죽는다는 거친 계산이 실제 VU 5,309~5,938과 맞음 | ≈5,700개 | — | 거친 계산값 | 계산값 | STORY:152 | — | — |
| B-33 | Gateway | 동시 처리 수 상한 L64: 2,000/s 굿풋 1,469 → 1,696, p95 435 → 87ms, 메모리 511~540MiB 평탄, OOM 0 | 굿풋 1,469 → 1,696, p95 435 → 87ms | 굿풋 +15.5%, p95 435/87=5.0배(직접 계산) | 6차, 상한 64 | 배포 측정 | STORY:163,21 | — | 리틀의 법칙으로 고른 값이 측정에서 맞음 |
| B-34 | Gateway | 상한별 2,000/s 굿풋/p95: L32 1,575/49ms, L64 1,696/87ms, L128b 1,721/162ms, L500 1,304/571ms | 위 값 | L500 p95 571/87=6.6배(직접 계산) | 6차 | 배포 측정 | STORY:163,280-283 | — | L500 = 상한 없음과 같음 |
| B-35 | Gateway | 상한 64 첫 값 계산: 굿풋 멈춘 약 1,480/s, p95/평균 비 약 4.5~5 → 평균 40~45ms → L≈60~67 | 1,480/s × 0.040~0.045s | — | 리틀의 법칙, 평균 식 | 계산값 | STORY:167,161 | — | 설계 세션 안 500(= 1,500×152ms≈228의 약 2배) 대 64 |
| B-36 | Gateway | 상한이 있어도 서버 CPU는 1,500/s 91~93%, 2,500/s 99%(CPU 실패). 상한은 지연·메모리를 묶지만 CPU를 만들지 않음 | CPU 91~93%/99% | — | 6차 | 배포 측정 | STORY:164,243 | — | 작업 보존형 |
| B-37 | Gateway | L32는 1,000/s에서도 392건 거절, L128은 `bulkhead_full` 섞임(2,500/s 1,966건, L64 96건) | 392건, 1,966건 vs 96건 | 1,966/96=20.5배(직접 계산) | 6차 | 배포 측정 | STORY:164 | — | — |
| B-38 | Gateway | 상한이 있으면 최대 VU 208(L64) 대 5,792(M1)로 발생기 VU도 빨리 돌아옴 | 5,792 → 208 | 5,792/208=27.8배(직접 계산) | 6차 | 배포 측정 | STORY:168 | — | — |
| B-39 | Gateway | 기본값 64 유지 결정: 128과 2,000/s 굿풋 차이 25건(1.5%)은 같은 128 두 번 잰 차이(5%) 안, p95는 64가 약 절반 | 25건, 1.5%, 5% | — | 판단 거부 | 판단 | STORY:169 | — | AI 128 제안 거부 |
| B-40 | Gateway | 거절 비율 25%(L500)~40%(L32)로 달라도 한 건당 Gateway CPU 0.66~0.71ms | 0.66~0.71ms | — | 2,500/s, 계산값 = 쓴 코어×1,000÷달성 요청/초 | 계산값 | STORY:174 | — | 거절에도 비용이 든다 |
| B-41 | Gateway | Boot 4에는 `ServerHttpObservationFilter`가 없고 관측은 어댑터가 WebFilter 체인 전체를 감싸 연다 → 거절 위치를 HttpHandler 데코레이터로 | — | — | Spring Boot 4.1.2, spring-web 7.0.10 소스 확인 | 설계 | STORY:194 | STORY:184-192 요청 순서 표 | 숫자의 이상함을 소스로 따라가 구조를 찾음 |
| B-42 | Gateway | H1(7차): 2,500/s 굿풋 1,608 → 1,814, p95 104 → 78ms, 서버 CPU ±1%p, 2,000/s 굿풋 1,696 → 1,781·p95 87 → 73ms | 1,608 → 1,814, 104 → 78ms | 굿풋 +12.8%(원문 +13%), p95 −25%(직접 계산 26/104) | 7차, 6차 L64와 같은 부하, 상한 64, #497·#498(배포 `1e9b1c75`) | 배포 측정 | STORY:178,339 | — | 미리 정한 판정 지표가 포화 단계에서는 가설을 못 가림 |
| B-43 | Gateway | 성공 한 건당 Gateway CPU 1.10 → 0.99ms(−10%), 한 건당 Gateway CPU 0.71 → 0.72ms | 1.10 → 0.99ms | −10%(원문 계산값) | 2,500/s | 계산값 | STORY:179,178 | — | 포화 단계 CPU÷요청 수는 몫 |
| B-44 | Gateway | 6차 거절 한 건은 받은 요청의 약 5분의 1(약 0.15~0.21ms)이라는 7차 노트의 계산값 | 0.15~0.21ms | 약 1/5 | 계산값, 직접 잰 값 아님 | 계산값 | STORY:201,320 | — | — |
| B-45 | Gateway | H1 1,000/s에서만 상한 거절 242건(6차 L64·H2 0건), 원인 확인 못 함(5초 표본 사이 짧은 몰림 추정) | 242건 | — | 7차 | 배포 측정 | STORY:200,327 | — | — |
| B-46 | Gateway | 7차 H2(H1+요청 로그 끔): 서버 CPU 1,000/s 78 → 66%, 1,500/s 93 → 85%, 2,500/s 굿풋 1,814 → 2,122, p95 60ms | 93 → 85%, 1,814 → 2,122 | CPU −8%p, 굿풋 +17%(2,122/1,814, 직접 계산), 6차 L64 대비 +32%(원문, 2,122/1,608=1.32) | 7차 H2, 측정 덮어쓰기 `iseonjwa.request-log.enabled=false` | 배포 측정 | STORY:210,50 | — | 비용은 JVM 안이 아니라 로그 경로에 있었다 |
| B-47 | Gateway | H2에서 Gateway CPU는 거의 같고(1,000/s 114 → 111%) 줄어든 곳은 alloy 22 → 14%와 컨테이너 밖 호스트 쪽 약 35%p. 호스트는 프로세스별로 안 재서 확인 못 함 | 114 → 111%, alloy 22 → 14%, 호스트 약 35%p | — | 7차 H2 | 배포 측정 | STORY:211,322 | — | 프로세스 안 프로파일러(JFR 로그 5.2%)는 프로세스 밖 비용을 못 본다 |
| B-48 | Gateway | H2에서 상한 거절 2,500/s 18,724 → 11,627건, `bulkhead_full` 247 → 402건 | 18,724 → 11,627, 247 → 402 | 거절 −38%(직접 계산), bulkhead +63%(직접 계산) | 7차 H2 | 배포 측정 | STORY:210,214 | — | — |
| B-49 | Gateway | 8차 P(problems, 운영) 대 O(끔): 1,000/s 59.6 대 56.7%(+2.9%p), 1,500/s 78.5 대 79.1%(−0.5%p), 2,000·2,500/s ±0.6%p, 굿풋·p95 같음(2,500/s 2,237~2,275, 50~54ms) | 78.5 대 79.1% | — | 8차, P1→O1→P2→O2, 같은 날, ADR 0052, #501·#503(배포 `eda80051`) | 배포 측정 | STORY:225,226 | — | 운영 설정이 끔과 2%p 안팎 |
| B-50 | Gateway | 같은 설정 끔(O)이 7차 H2보다 6~9%p 낮음(1,000/s 56.7 대 66%, 1,500/s 79.1 대 85%) → 날·배포가 다르면 직접 비교 금지 | 66 → 56.7%, 85 → 79.1% | −9.3%p, −5.9%p(직접 계산) | 8차 대 7차 | 배포 측정 | STORY:229,333 | — | 비교는 같은 날 대조군과 |
| B-51 | Gateway | 같은 설정 두 회차 차이 0.0~1.3%p, 30초 평균 하나의 표본 표준오차 약 2.5%p(+2.9%p는 가리지 못함) | 0.0~1.3%p, 2.5%p | — | 8차 | 배포 측정 | STORY:225,226 | — | 판정 보류 칸 |
| B-52 | Gateway | R0: Gateway CPU = b + a×받은 요청 + r×거절. a 1.063ms, r 0.021ms(pidstat)·0.080ms(docker stats), r/a 0.020~0.075 | a 1.063ms, r 0.021~0.080ms | r/a=0.020~0.075(원문) → 받은 요청의 2~8%(원문) | 8차, 상한 1 대 1,000, 500·1,000/s 두 번씩, 14개 단계, 대기자 1명 | 배포 측정 | STORY:225,312 | — | 비포화 실험으로 포화 단계에서 못 가린 비용을 직접 풂 |
| B-53 | Gateway | 거절을 Netty 단계로 더 앞당기는 이득 상한은 약 0.02코어(서버의 0.5%) | 약 0.02코어, 서버 0.5% | 원문 계산값 | 2,500/s 거절 약 250/s | 계산값 | STORY:227,312 | — | 안 하기로 한 이유 |
| B-54 | Gateway | R0 1,000/s 거절 비율 46%(계획 기준 50%에 조금 못 미침), r 오차 ±0.02ms | 46%, ±0.02ms | — | R0 | 배포 측정 | STORY:231 | — | — |
| B-55 | Gateway | 누적 표: 0a 굿풋 1,469·p95 435ms·CPU 93%, 0b 1,396·497·92%, B20 1,463·528·92%, M1 1,315·752·92%, L64 1,696·87·93%, L32 1,575·49·93%, L500 1,304·571·91%, L128b 1,721·162·93%, H1 1,781·73·93%, H2 1,871·57·85%, g8-P 1,950·1,937/43·44ms/78.5%, g8-O 1,954·1,937/43·44ms/79.1% | 위 값(2,000/s 굿풋·p95, 1,500/s 서버 CPU) | — | G1, 0a 기준(8차는 날이 달라 직접 비교 금지) | 배포 측정 | STORY:276-288 | — | 누적 표 |
| B-56 | Gateway | 0b JFR 부담 0.4~0.7%(반복 변동 5% 안) | 0.4~0.7% | — | 0a 대 0b(굿풋 1,469 → 1,396, p95 435 → 497ms) | 배포 측정 | STORY:119,277 | — | — |
| B-57 | Gateway | 한 건당 Gateway CPU는 2,000/s에서 0.95ms, 서버 전체는 2.0ms | 0.95ms, 2.0ms | — | 계산값 = 쓴 코어×1,000÷달성 요청/초 | 계산값 | STORY:122 | — | — |
| B-58 | Gateway | Gateway 스레드: `reactor-http-epoll` 4개 합 130%, `lettuce-epollEventLoop` 39%(2,000/s), run queue 평균 15.7(4 vCPU) | 130%, 39%, 15.7 | — | 5차 0a | 배포 측정 | STORY:118, RES:106 | — | CPU 차례 대기 |
| B-59 | Gateway | 현재 운영: 동시 처리 수 상한 64, 넘치면 503 `GATEWAY_OVERLOADED`·`Retry-After: 2`(dev 11/13에 3초로 변경), 요청 로그 `problems`(5xx·끊긴 요청·200ms 이상) | 64, Retry-After 2 → 3초 | — | 배포 `eda80051`, 2026-11-12 | 설계 | STORY:25-30 | — | 지금 운영 상태 |
| B-60 | Gateway | 8차 같은 G1 부하에서 1,500/s 서버 CPU 78.5%, 2,500/s 굿풋 약 2,250, p95 50~54ms | 78.5%, 약 2,250, 50~54ms | — | 8차, 날이 달라 5차 0a와 바로 비교 금지 | 배포 측정 | STORY:30 | — | — |
| B-61 | Gateway | 과부하(2,500/s) 서버 CPU 96~97%. 목표를 둘로 나눔: 평상 1,500/s 서버 CPU 90% 미만, 과부하 2,000/s 이상은 p95·OOM 0·굿풋 | 96~97%, 90% 미만 | — | 11/12 강민 결정 | 판단 | STORY:33 | — | 목표 재정의 |
| B-62 | Gateway | 대기열 폴링 상한 10 → 30초 제안(요청 수 계산상 약 −63%)을 강민이 거부 | −63%(계산상) | — | 11/12 | 판단 | STORY:35,313 | — | 서버 부하를 사용자에게 넘기지 않음 |
| B-63 | Gateway | 단계 무효 규칙(5차부터): 빠진 요청이 목표의 1% 초과 또는 발생기 CPU 유휴 10% 미만이면 무효 | 1%, 10% | — | — | 설계 | STORY:73 | — | 측정을 믿을 수 있나 |
| B-64 | 인프라 | 측정 환경: 배포 `m7i.xlarge`(4 vCPU, 15.7GiB)에 컨테이너 21개, 발생기 `c6i.xlarge`, k6 v1.0.0, Gateway CPU 제한 없음 | 4 vCPU, 15.7GiB, 21개 | — | 11/9~12 | 배포 측정 | STORY:71 | — | 한 대의 한도 |
| B-65 | 인프라 | 7차 판정 기준을 측정 전에 적었고 H1은 기준대로면 실패(0.71 → 0.72ms)였음을 인정 | 0.71 → 0.72ms | — | 7차 | 판단 | STORY:339 | — | 사전 등록한 기준이 가설을 못 가림 |
| B-66 | 인프라 | 7차 상한 거절 수가 Prometheus 30초 증가량과 k6 수에서 약 40% 다름(H1 1,500/s 622 대 1,054건) | 622 대 1,054 | 622/1,054=0.59(약 −41%, 직접 계산) | 7차 | 배포 측정 | STORY:326 | — | 확인 못 함 |
| B-67 | 인프라 | 상한 64·96·128 반복 측정은 7·8차에서 뺌(강민 결정), 남은 일 | — | — | — | 판단 | STORY:341 | — | — |
| B-68 | 인프라 | 배포 구조: main 머지 → Actions가 SHA 태그 이미지 9개 빌드·GHCR → Jenkins 폴링·이미지 확인(30초 간격, 최대 20분) → SSH `docker compose up -d` → 스모크 → 실패 시 직전 성공 태그로 되돌림 | 이미지 9개, 30초, 20분 | — | ADR 0044, 2026-11-03 결정 | 설계 | DEP:3,28-34 | DEP:9-19 mermaid | 배포 파이프라인 |
| B-69 | 인프라 | 이미지 9개: config-server, eureka, gateway, user-service, reservation-service, payment-service, mock-pg, assistant-service, frontend, 태그는 40자 SHA 하나만(latest 없음), linux/amd64 | 9개 | — | `ghcr.io/iseonjwa/<서비스>:<SHA>` | 설계 | DEP:55-57 | — | — |
| B-70 | 인프라 | 이미지를 앱마다 매트릭스로 나누지 않고 bake 한 번으로: Gradle builder 단계 한 번(8번 → 1번) | 8회 → 1회 | 8배 감소(직접 계산) | 2026-11-04 로컬 확인 | 로컬 측정 | DEP:68 | — | — |
| B-71 | 인프라 | 되돌림 종료 코드 0 성공, 1 실패(되돌림 성공), 2 사용법, 3 되돌림도 실패. 헬스는 `--wait` 최대 7분 | 7분 | — | `deploy.sh` | 설계 | DEP:158 | — | — |
| B-72 | 인프라 | 이미지 보관: 최근 3개 버전과 이번·직전·그 앞 성공 커밋만 남김(`KEEP_IMAGE_VERSIONS`, TS-60) | 3개 버전 | — | ISE-249 | 설계 | DEP:158,167 | — | — |
| B-73 | 인프라 | 컨테이너 상한 합계 약 12.5GB(앱 5.2GB, 데이터 4.5GB, 관측 2.8GB), 16GB급의 약 78%로 SA 9-3의 75% 기준을 조금 넘음 | 12.5GB, 78% | 12.5/16=78%(원문) | assistant-service 384m 포함, 2026-11-09 | 설계 | DEP:169 | — | 메모리 예산 |
| B-74 | 인프라 | 첫 배포(2026-11-04) 직후 유휴 메모리 사용 약 4.7GB / 15.7GB | 4.7GB / 15.7GB | 4.7/15.7=30%(직접 계산) | `docker stats`, 배포 직후 | 배포 측정 | DEP:383 | — | — |
| B-75 | 인프라 | 서비스 발견 지연: Eureka 응답 캐시 30 → 5초, 클라이언트 fetch 30 → 5초, LoadBalancer 캐시 35 → 5초. 최악의 합 95초 → 15초 | 95 → 15초 | 95/15=6.3배(직접 계산) | ISE-244, 단계별은 안 재고 합계(35초, 55초)만 잰 값 | 계산값 | DEP:181-190 | DEP:184-188 표 | 컨테이너 healthy ≠ Gateway가 찾을 수 있음 |
| B-76 | 인프라 | 관측된 발견 지연: 첫 배포 스모크 실패 약 35초(TS-34), PAY-2에서 컨테이너 시작 46초 뒤에도 10초간 결제 176건 503, 컨테이너 시작부터 약 55초 | 약 35초, 176건, 약 55초 | — | 2026-11-04, 11/12 | 배포 측정 | DEP:181,379 | — | — |
| B-77 | 인프라 | 새 컨테이너 앱이 뜰 때까지 약 25초는 요청을 받을 곳이 없음(인스턴스 1개) | 약 25초 | — | — | 판단 | DEP:192 | — | 무중단 배포는 범위 밖 |
| B-78 | 인프라 | 배포 후 JIT 예열 근거: 11/15 균일 122 오픈 피크 30초에 reservation 컴파일이 CPU의 약 30%(0.31~0.43코어), payment 0.34~0.61, 오픈 전 10분 상세만 받아 데워진 Gateway 0.03~0.06 | 0.31~0.43, 0.34~0.61, 0.03~0.06코어 | reservation/Gateway 최대 0.43/0.03=14배(직접 계산) | `jvm_compilation_time_ms_total`, 컴파일러 스레드 경과 시간이라 CPU 몫의 상한 | 배포 측정 | DEP:199 | — | 첫 요청 폭주가 JIT과 겹침 |
| B-79 | 인프라 | 예열: 기본 초당 25번 × 120초, 상한 420초, 배포가 약 2.5분 길어짐, 빈 좌석 750석(25×30초) 미만이면 새 회차(1,500석) | 25/s×120s, 420초, 약 2.5분, 750석 | 25×120=3,000회(직접 계산) | `warmup.sh`, W1, ISE-264 | 설계 | DEP:160,203,208 | — | 예열 효과는 확인 못 함(v2 배포 뒤 비교 예정) |
| B-80 | 인프라 | 예열 서버 실행과 효과는 확인 못 함(`bash -n`, DRY_RUN, `k6 inspect`만) | — | — | 2026-11-16 | 설계 | DEP:213 | — | 해 보지 않은 일 구분 |
| B-81 | 인프라 | Kafka 헬스체크 변경: 간격 10 → 30초, 기동 중 120초는 2초마다, 11/15 배포 서버에서 헬스체크마다 Kafka CPU 45~224% 튐 | 간격 10 → 30초, CPU 45~224% | 30/10=3배(직접 계산) | `kafka-broker-api-versions.sh` → bash `/dev/tcp`, ISE-264, TS-70, 2026-11-16 | 배포 측정 | DEP:175 | — | 헬스체크가 만든 CPU 스파이크 |
| B-82 | 인프라 | 관측 도구 설정 해시를 라벨로 넘겨 바뀐 도구만 재생성(11/12에 Alertmanager·Alloy·Loki가 11/9 설정으로 돌고 있었던 사고, TS-64) | 11/9 설정이 11/12까지 | — | ISE-256 | 배포 측정 | DEP:174 | — | 수동 단계는 하지 않아 사고 → 자동화 |
| B-83 | 인프라 | HTTPS: Let's Encrypt IP 인증서(shortlived 160시간 약 6일), certbot 5.4 webroot, 수명 절반(약 80시간) 남으면 갱신, 48시간 안 만료 알림(`CERT_MIN_HOURS`), 6시간마다 점검, HSTS 끔 | 160시간, 약 80시간, 48시간, 6시간 | 160/2=80시간(원문) | ISE-278, 2026-11-16, 서버 `52.63.10.174` | 설계 | DEP:106,252,301-302,297,263 | — | 도메인 없이 IP 인증서 |
| B-84 | 인프라 | 80은 302(임시)로 https 넘김(301은 브라우저가 오래 기억해 되돌릴 때 위험) | — | — | — | 설계 | DEP:259 | — | — |
| B-85 | 인프라 | Grafana를 443 `/grafana/`로 익명 공개(2026-11-16 사용자 결정, 2026-11-05 팀원 IP 결정 대체). 한계: 익명이 데이터 소스 조회 API도 열어 Prometheus(768m)·Loki(512m)에 무거운 쿼리 부하 가능, `limit_req` 안 둠 | 768m, 512m | — | 심사위원 직접 보기 | 판단 | DEP:106,118,308-309,428 | — | 공개 대시보드의 대가 |
| B-86 | 인프라 | 보안그룹: 443·80 전체, 8080은 측정 중 발생기 보안그룹만, 22는 팀원 IP·Jenkins 보안그룹, 관리 UI는 SSH 터널. 첫 배포 때 아웃바운드 규칙 없어 GitHub·GHCR로 나가지 못함 → 0.0.0.0/0 추가 | — | — | 2026-11-04 | 설계/배포 측정 | DEP:112-123,376 | — | 보안그룹 아웃바운드 함정 |
| B-87 | 인프라 | 첫 배포(2026-11-04): 컨테이너 20개 기동, 스모크 공연 목록 503(약 35초 Eureka 지연), 이후 Jenkins 빌드 #1은 23초 SUCCESS(커밋 확인 → 이미지 8개 → SSH → 스모크 4개) | 20개, 23초, 이미지 8개 | — | 당시 이미지 8개, 서버 `m7i-flex.xlarge` | 배포 측정 | DEP:379,382 | — | 첫 배포 |
| B-88 | 인프라 | 서버 인스턴스: `m7i-flex.xlarge` → `m7i.xlarge`(2026-11-05), 시간당 0.2394달러 → 0.252달러(시드니). flex는 vCPU당 40% 기준 성능이라 지속 부하에서 CPU 보장이 안 됨 | 0.2394 → 0.252달러/시간 | +5.3%(0.252/0.2394, 직접 계산) | 부하 측정 대비 | 판단 | DEP:370-375 | — | 지속 부하엔 flex가 부적합 |
| B-89 | 인프라 | 인스턴스 크기 결정: 16GB급, 측정을 기다리지 않고 SA 9-3 합계(관측 포함 약 12GB)로 정함. 로컬 유휴 합계 약 4.1GB(2026-11-03) | 약 12GB, 약 4.1GB | — | 11/6 리허설 일정 | 판단 | DEP:403-407 | — | 측정 전에 정한 결정 |
| B-90 | 인프라 | 서버 운영 일정(ADR 0047): 11/6까지 켜 둠, 11/7~11/12 중지, 11/13~11/19 다시 켬, 그 뒤 삭제 | 11/7~12 중지 | — | — | 설계 | DEP:430 | — | — |
| B-91 | 인프라 | 앱 JVM GC: 앱 7개에 `-XX:+UseG1GC` 명시(컨테이너 메모리 약 1,792MB 미만이면 Serial), 2026-11-05 Serial 방침을 11/8 번복 | 약 1,792MB 기준 | — | ISE-193, 2026-11-08 | 판단 | DEP:419 | — | GC 선택 번복 |
| B-92 | 인프라 | Jenkins: `t3.small` 급, `mem_limit` 1200m, `-Xmx768m`, 실행기 1개, 전체 40분 제한, 폴링 `H/2 * * * *`, 플러그인 7개 고정 | 1200m, 768m, 40분 | — | ISE-175 | 설계 | DEP:319-325,338 | — | — |
| B-93 | 인프라 | 시연 샘플 시드: 공연 1·회차 2·각 좌석 15,000석(2026-11-04) → ADR 0060 이후 1,500석 | 15,000 → 1,500석 | 1/10(직접 계산) | `seed-demo.sh`, ISE-177 | 설계 | DEP:37,171,162 | — | 좌석 수 결정 변경 |
| B-94 | 추첨 | 추첨 시연 응모 시드(2026-11-05 로컬): prepare 120명 73초(재실행 27초), 활성 빌링키 108개, enter 120건 75초(1매 90·2매 30), 추첨(30석, 예비 15) 90초 뒤 PAID 22건(30석), FAILED·BILLING_KEY_MISSING 2건, dead_letters 0 | 120명 73초, PAID 22건 | — | `seed-lottery-entrants.sh`, ISE-199, 로컬 nginx 8000 | 로컬 측정 | DEP:172 | — | 서버 실행은 미확인 |
| B-95 | 결제 | 부하·장애 시나리오 측정은 늘 `mock` PG(토스 테스트는 호출 한도·지연을 정할 수 없어 오염), 빌링도 늘 Mock | — | — | ISE-45 | 설계 | DEP:140-141 | — | — |
| B-96 | 결제 | 결제 토큰 키 쌍(ES256)이 든 릴리스(ISE-245, ADR 0055) 전 `init-env.sh` 필수. 키가 없으면 reservation·payment가 기동 안 하고 `deploy.sh`가 되돌림. 개인키가 SEC1이면 예매 서비스가 못 읽음(TS-61) → PKCS#8 | — | — | OpenSSL 3.0 | 설계 | DEP:129,132-133 | — | 키 형식 함정 |
| B-97 | 인프라 | 이미지 푸시 워크플로 동시 실행: main은 커밋마다 그룹을 따로 둠(한 그룹이면 연속 머지 때 가운데 커밋 이미지가 빠짐) | — | — | `images.yml` | 설계 | DEP:71 | — | — |
| B-98 | AI | `assistant-service`: 포트 8095, 관리 9080, `mem_limit` 384m, `-Xms96m -Xmx192m`, Gateway 라우트 응답 시한 10초·연결 1초, 키 없으면 검색 모드 | 384m, 192m, 10초 | — | ISE-126, ADR 0049 | 설계 | DEP:222-227 | — | — |
| B-99 | 인프라 | 앱 컨테이너 메모리 예산(2026-11-15 17:48 UTC): payment 768m → 1024m(11/15 cgroup OOM 5회, TS-71), 컨테이너 상한 합 12,992 → 13,248MiB(22개), 장비 15,701MiB의 84%, 실제 `free -m` 사용 7,217MiB | 768 → 1024m, 12,992 → 13,248MiB, 84%, 7,217MiB | 13,248/15,701=84.4%(원문 84%), 7,217/15,701=46%(직접 계산) | `docker compose config` 계산, Prometheus·memory.current | 배포 측정/계산값 | DEP:387-398 | DEP:389-395 표 | payment OOM 5회 |
| B-100 | 인프라 | 앱별 메모리 표(힙 최대/힙 committed/비힙 committed/지표 밖/힙 최대일 때): payment 512/375/193/144/870, gateway 512/256/170/104/786+직접 128, reservation 768/384/272/140/1,180, user 384/192/193/96/673, assistant 192/96/124/75/391 | 위 값(MiB) | — | 계산: 지표 밖 = cgroup 사용량 − 힙·비힙 committed | 배포 측정/계산값 | DEP:389-395 | DEP:389-395 표 | 메모리 예산 공식 |
| B-101 | 인프라 | payment 옵션 `-Xms256m -Xmx512m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=128m -XX:MaxDirectMemorySize=64m -XX:+UseG1GC`, 힙 밖 상한은 실측의 약 2~3배(누수 시 exit 137 대신 JVM 오류) | 위 옵션 | 힙 밖 상한 2~3배(원문) | ISE-264, TS-71 | 설계 | DEP:397 | — | — |
| B-102 | 인프라 | reservation·user·assistant 각 128·128·64MiB 늘리면 합 13,568MiB(장비 86%). 부하 중 cgroup 사용량이 상한 90% 넘으면 재산정 | 13,568MiB, 86% | 13,568/15,701=86.4%(직접 계산) | 계산값 | 계산값 | DEP:399 | — | — |
| B-103 | 인프라 | 첫 배포 유휴 메모리 상위: reservation 505MiB(상한 1,152m의 44%), kafka 429MiB, payment 404MiB, user 384MiB(60%), gateway 347MiB, grafana 278MiB(384m의 72%), eureka 273MiB | 위 값 | — | `docker stats`, 2026-11-04 배포 직후 | 배포 측정 | DEP:383 | — | — |
| B-104 | 대기열 | Q1 1차 데운 2,000/s: 201 428건 / 503 19,573건. Q2 대기자 10,000명은 시작 3초 뒤 열려 600초 동안 회복 못 함. Redis 서버 지연 0.1~0.8ms | 428 / 19,573건, 3초, 600초 | 503 비율 19,573/20,001=97.9%(직접 계산) | 배포 1차 | 배포 측정 | STORY:84 | — | Redis 정상인데 인증 전체 503 |
| B-105 | 팀 | 판단 기록: AI 거부 2건(벌크헤드 없음, 측정용 완화) 수용 2건(기록값 해석, 재측정 값)이 발표 대표 사례 | 거부 2·수용 2 | — | 11/9~10 | 판단 | RES:122 | — | AI 설계 검증 로그 원천 |
| B-106 | Gateway | 대기열 담당 → Gateway 요청으로 ISE-230 진행. 2026-11-13부터 Retry-After: 차단기 열림 거절 5초, 닫힌 상태 한 번 실패 3초(그 전 둘 다 1초) | 1초 → 5초/3초 | 5배/3배(직접 계산) | ISE-230 | 설계 | RES:112 | — | k6는 재시도 안 해 측정이 낙관적(추정) |
| B-107 | Gateway | 로그아웃 목록 로컬 캐시·fail-open·장비 분리는 보안 성질·비용이 바뀌어 이번 범위에서 안 고름 | — | — | ADR 0047, 0005 | 판단 | RES:113 | — | — |
| B-108 | Gateway | 로컬 5차 판단: 강민이 CPU 포화를 맨 앞 유입 제한 대신 차단기 예민화로 시도 | — | — | 11/11 | 판단 | RES:90 | — | 거부 후 결론은 같음 |
| B-109 | Gateway | 리틀의 법칙: 벌크헤드 25가 차려면 1,200/s에서 한 호출이 약 21ms 머물러야 함, Redis는 2ms 안이라 벌크헤드 포화는 CPU 포화의 증상 | 약 21ms | 25/1,200=20.8ms(원문 계산값) | 계산값 | 계산값 | RES:55 | — | — |
| B-110 | 팀 | 일하는 원칙: 한 회차에 값 하나만 바꿈, 누적 표, 실패한 가설도 같은 형식으로 남김 | — | — | 강민 | 판단 | STORY:74-77 | — | 측정 원칙 |
| C-01 | 인프라 | 부하 도구는 k6, 같은 VPC 팀 공용 발생기 EC2에서 Gateway를 내부 주소로 부르고 결과는 Prometheus remote write로 서버 지표와 같은 Grafana에서 본다 | — | — | 결정 2026-11-03(사용자), ADR 0046 | 설계 | LT:3 | — | 측정 도구·환경을 어떻게 골랐나 |
| C-02 | 인프라 | 평소 배포 Prometheus는 remote write 수신·9090을 닫고, 측정 모드 덮어쓰기에서만 연다. 보안 그룹은 발생기 SG에서 오는 9090만 허용 | — | — | 정함 2026-11-08, ISE-191 | 설계 | LT:9 | — | 측정할 때만 문을 여는 보안 설계 |
| C-03 | 인프라 | 11/9 Q1~Q3 측정 뒤 배포 Prometheus에 k6_* 지표 78개가 들어옴, testid별 조회됨, k6 전용 대시보드는 없어 Explore에서 봄 | k6_* 지표 78개 | — | 확인 2026-11-10 | 배포 측정 | LT:10 | — | 결과가 같은 Grafana에 쌓인다는 증거 |
| C-04 | 인프라 | 팀 공용 발생기 iseonjwa-loadgen을 c6i.xlarge로 2026-11-09 생성, 측정 날 사이 중지, 팀 마지막 측정 뒤 종료 | c6i.xlarge(4 vCPU, 8GiB) | — | 2026-11-09 | 설계 | LT:14, LT:27 | — | 발생기 준비 |
| C-05 | 인프라 | k6는 사설 IP(172.31.4.93)만 씀. 공인 IP는 같은 서브넷이어도 GB당 0.01달러 양방향 과금 + SG에 막힘 | 0.01달러/GB(양방향) | — | 같은 VPC | 설계 | LT:15 | — | 사설 IP를 쓰는 이유 |
| C-06 | 인프라 | 부하 도구는 프론트 nginx를 거치지 않고 Gateway를 직접 부른다 | — | — | ADR 0040 4절 | 설계 | LT:17 | — | — |
| C-07 | 인프라 | 발생기 자체 한계(CPU 포화)가 측정을 왜곡하는지 확인하는 방법은 미정. 발생기 CPU 동시 기록이 기본 | — | — | 미정 | 설계 | LT:18 | — | 측정 도구 자체의 한계 |
| C-08 | 인프라 | 발생기 유형 근거: 좌석 조회 K1 응답이 약 830KB, 초당 100건이면 응답만 초당 약 83MB(약 0.66Gbps) | 830KB/응답, 100건/초 → 83MB/초, 0.66Gbps | 830KB x 100 = 83MB/초(원문 "약") | 좌석 선점 K1 | 계산값 | LT:35 | — | 응답이 너무 커서 발생기 대역폭이 먼저 막힌다 |
| C-09 | 인프라 | c6i.large 네트워크 기준 대역폭 0.781Gbps라 여유 거의 없고, c6i.xlarge는 1.562Gbps | 0.781Gbps → 1.562Gbps | 1.562 / 0.781 = 2.0배(직접 계산) | AWS 연산 최적화 인스턴스 표 | 설계 | LT:36-37 | — | 발생기 유형 선택 |
| C-10 | 인프라 | 발생기 대역폭이 먼저 막히면 k6 dropped_iterations가 생기고 서버 한계와 구분 불가 | — | — | — | 판단 | LT:38 | — | 측정 왜곡 위험 |
| C-11 | 인프라 | 인스턴스 가격: c6i.xlarge 시드니 시간당 0.222달러, c6i.large 0.111달러 | 0.222달러/시 vs 0.111달러/시 | 0.222 / 0.111 = 2.0배(직접 계산) | 시드니 리전 | 설계 | LT:27 | — | 측정 비용 |
| C-12 | 인프라 | 같은 가용 영역(ap-southeast-2a)이면 사설 IP 통신 무료·지연 최소, 다르면 GB당 0.01달러 양방향 | 0.01달러/GB | — | VPC vpc-08032543fc446b5c7, subnet-07b3d9d0e31a7d35d | 설계 | LT:28 | — | — |
| C-13 | 인프라 | 발생기 디스크 gp3 20GB, AMI Ubuntu 24.04 LTS | 20GB | — | — | 설계 | LT:24, LT:33 | — | — |
| C-14 | 인프라 | 측정 모드 절차: on → 키 옮김 → 짧은 확인 → 본 측정 → off(운영 키·샘플링 1.0·닫힌 9090 복귀 후 스모크) → 발생기 중지, 팀 마지막 뒤 종료 | 단계 10개 | — | — | 설계 | LT:42-61 | — | 측정 모드 켜기·끄기 절차 |
| C-15 | 인프라 | 측정하는 동안 main 릴리스를 하지 않는다. Jenkins가 main을 2분마다 확인해 새 커밋이 있으면 배포하고 그러면 측정 모드가 풀린다 | 2분 간격 | — | — | 설계 | LT:57, LT:101 | — | 측정 중 배포가 측정 모드를 풀어버리는 함정 |
| C-16 | 인프라 | 측정용 토큰 키: measure-mode.sh on이 JWT_ACCESS_SECRET·ENTRY_TOKEN_SECRET을 새 무작위(openssl rand -hex 32)로 .env.measure(권한 600)에 두고 off 때 버린다. 운영 키는 발생기에 두지 않음 | 키 2개, hex 32 | — | 정함 2026-11-08(사용자), ADR 0046 변경 | 설계 | LT:90, LT:98-103 | — | 운영 키를 발생기에 안 두는 설계 |
| C-17 | 인프라 | 측정 모드 동안 기존 로그인은 끊긴다(Access 서명 키 변경), Refresh 키는 그대로 | — | — | — | 설계 | LT:100 | — | — |
| C-18 | 인프라 | 측정 모드 추적 샘플링 TRACING_SAMPLE_RATE 0.01, compose.deploy.yml은 시연용 1.0 | 1.0 → 0.01 | 1/100로 감소(1.0/0.01=100배, 직접 계산) | 정함 2026-11-08 | 설계 | LT:92 | — | 측정 때 추적 비용을 낮추는 이유 |
| C-18b | 인프라 | SA가 말한 "요청 제한 완화"는 config-repo/application-loadtest.yml에 없고 요청 제한 설정 위치는 미확인 | — | — | 미확인 | 설계 | LT:92, LT:143 | — | — |
| C-19 | 인프라 | Mock PG 기준 지연 300ms. 배포 mock-pg는 이미 loadtest 프로필이라 장애 API가 열려 있고 포트는 밖에 안 열며, 서버 안 일회용 curl 컨테이너로 건다 | 300ms | — | 정함 2026-11-08 | 설계 | LT:94 | — | — |
| C-20 | 인프라 | 시나리오 3(PG 지연·장애 중 결제): mock-pg를 local,loadtest 프로필로 띄우는 덮어쓰기 파일이 필요(ISE-71, 팀 확인 대기) | — | — | 작성 시점 | 설계 | LT:81 | — | — |
| C-21 | 대기열 | 폴링 간격은 서버가 주는 값: 기본 3초, 순번이 멀수록 길어져 최대 10초, jitter 0~+20% | 3초 → 최대 10초, +0~20% | — | SA 9-4 | 설계 | LT:83 | — | — |
| C-22 | 인프라 | 가상 사용자 Access 토큰 유효 30분(SA 9-4)이라 측정 직전 생성 또는 재발급 필요 | 30분 | — | — | 설계 | LT:89 | — | — |
| C-23 | 좌석 | BookingFlowSystemTest는 회차마다 좌석 1,500석(ADR 0060 전에는 15,000석) | 15,000석 → 1,500석 | 15,000 / 1,500 = 10배 감소(직접 계산) | ADR 0060 | 설계 | LT:91 | — | 좌석 규모를 줄인 결정 |
| C-24 | 인프라 | 지표 항목: 처리량·p95·p99·오류율은 k6, 포화점은 가상 사용자를 늘려 처리량이 멈추고 p95가 급증하는 지점, DB 연결 대기는 Hikari, Kafka 랙은 클라이언트 지표 | — | — | 서비스별 자원 도구(docker stats/cAdvisor)는 미정 | 설계 | LT:107-114 | — | — |
| C-25 | 결제 | payment_approve_seconds_bucket의 시계열 수와 메모리는 부하에서 확인된 적이 없다(미확인) | — | — | 미확인 | 설계 | LT:116 | — | 메모리 관련(2절 참조) |
| C-26 | 인프라 | 측정 환경 기록표 9개 항목(장비, 발생기, Docker 할당, JVM 힙·컨테이너 메모리, 풀·스레드, 데이터 규모, 시나리오, 커밋 해시, 샘플링) | 9항목 | — | SA 3-1 | 설계 | LT:122-132 | — | 재현 가능한 측정 기록 |
| C-27 | 인프라 | 측정 결과로 배포 EC2 크기(SA U10)와 컨테이너 상한을 정한다 | — | — | — | 설계 | LT:136 | — | — |
| C-28 | 팀 | 범위 분담: 공통 기반·시나리오 1·3 안준현, 선점 경합은 공통 기반 시나리오를 ISE-25(권영은) 기준선이 씀. 2026-11-05에 ISE-181 담당이 최권우로 바뀜 | — | — | 2026-11-05 | 판단 | LT:65-73 | — | — |
| C-29 | 인프라 | SA 변경점: 부하 도구를 "측정할 때만 띄우는 같은 VPC 임시 EC2"로 바꿈, 측정 범위를 선점 API 하나에서 오픈 피크 한 흐름·PG 장애 중 결제까지 넓힘, Prometheus remote write 수신 추가 | — | — | — | 설계 | LT:151-153 | — | SA 대비 변경 |
| C-30 | 인프라 | 로그 구조: Alloy → Loki → Grafana, 보관 7일, 라벨은 서비스 이름 위주로 적게(카디널리티), traceId는 라벨이 아니라 줄 필터 | 보관 7일 | — | 결정 2026-11-03(사용자), ADR 0045 | 설계 | OB:3, OB:15-19 | OB:7-13 mermaid | 로그 파이프라인 구성 |
| C-31 | 인프라 | 로그 확인(2026-11-03): 예매 한 흐름을 Gateway로 돌린 3분 동안 gateway·user·reservation·payment 로그에 요청에서 나온 줄이 없었다. 남은 줄은 Eureka 갱신·대사 작업 등 배경 작업 | 3분간 요청 줄 0 | — | 최신 dev compose, 2026-11-03 | 로컬 측정 | OB:37 | — | 정상 요청이 로그에 안 남는다는 발견 → 요청 로그 필터 |
| C-32 | Gateway | 요청 로그 필터를 Gateway(WebFlux 전역 필터)와 서블릿 서비스(common)에 더함. 한 줄: 메서드·경로 템플릿·상태·처리 시간 ms. 본문·헤더·쿼리 값·/actuator 제외 | — | — | 결정 2026-11-03(사용자) | 설계 | OB:38-43 | — | 로그가 없어서 필터를 만든 경위 |
| C-33 | 인프라 | 구현(ISE-182): 로그 형식 "요청 {메서드} {경로} {상태} {시간}ms", 예 "요청 POST /api/v1/payments 200 521ms". mock-pg는 common 미사용이라 제외 | 521ms(예시) | — | — | 설계 | OB:49 | — | — |
| C-34 | 인프라 | 요청 로그 mode: all은 모든 요청, problems는 상태 min-status(500) 이상·클라이언트 끊김·처리 시간 slow-threshold(200ms) 이상만. 운영 기본 problems(Gateway 11/13, 서비스 11/16 ISE-286), 로컬 all | min-status 500, slow-threshold 200ms | — | ADR 0052 | 설계 | OB:53 | — | 운영에서 정상 요청 로그를 끈 결정 |
| C-35 | 인프라 | 운영에서 정상 요청·409 같은 업무 거절은 줄이 안 남으므로 Zipkin 트레이스와 http_server_requests 지표로 본다. 4xx까지 보려면 min-status를 400으로 | — | — | problems 모드 | 설계 | OB:53 | — | — |
| C-36 | 인프라 | 필터 순서 이유: 관측 필터 안쪽(traceId가 MDC에 있을 때)이면서 요청 거절 필터 바깥(401·403·404도 기록). Gateway는 관측 필터가 @Order(0)이라 이 필터는 order 1(HIGHEST_PRECEDENCE+2로 두면 traceId가 비어서) | order 0 → 1 | — | — | 설계 | OB:54 | — | traceId가 비는 함정 |
| C-37 | 인프라 | 확인(2026-11-04, 로컬 compose) 테스트 통과 건수: common 100, gateway 76, user 35, reservation 294, payment 764 | 100/76/35/294/764건 | 합계 100+76+35+294+764 = 1,269건(직접 계산) | 2026-11-04, 로컬 | 로컬 측정 | OB:57 | — | — |
| C-38 | Gateway | 확인: Gateway 줄에도 traceId 채워짐(spring.reactor.context-propagation: auto). 인증 없는 /api/v1/users/me는 "요청 GET /api/v1/users/me 401 29ms", 없는 경로는 "404 3ms" | 29ms, 3ms | — | 2026-11-04, 로컬 | 로컬 측정 | OB:58 | — | — |
| C-39 | 인프라 | 같은 요청의 Gateway 줄과 서비스 줄에 같은 traceId. 로그인 400, 공연 조회 200. BookingFlowSystemTest 통과, POST /api/v1/payments Gateway 638ms·payment 521ms | 638ms(Gateway), 521ms(payment) | 638 − 521 = 117ms 차(직접 계산) | 2026-11-04, 로컬 | 로컬 측정 | OB:59-60 | — | Gateway와 서비스 줄이 같은 traceId로 묶임 |
| C-40 | 인프라 | 결제 승인 뒤 예매 확정은 Kafka 소비자에서 일어나 요청 로그에 안 잡히는 것이 설계 그대로. 최근 10분 중 세 서비스를 모두 지나는 traceId는 없었음 | — | — | 2026-11-04 | 로컬 측정 | OB:60-62 | — | — |
| C-41 | 인프라 | 수집 구현(ISE-178): Loki grafana/loki:3.7.9(512m), Alloy grafana/alloy:v1.21.3(256m). 이미지는 인덱스 digest 고정, 호스트 포트 안 엶 | 512m, 256m | — | — | 설계 | OB:66 | — | 메모리 관련(2절 참조) |
| C-42 | 인프라 | 모으는 컨테이너는 앱 8개(config-server, eureka, gateway, user, reservation, payment, mock-pg, assistant)만. DB·Kafka·관측 도구는 안 모음(로그 양·메모리 예측용) | 앱 8개 | — | — | 설계 | OB:67 | — | — |
| C-43 | 인프라 | 라벨(색인)은 service·level·env 셋. traceId는 구조화 메타데이터 | 라벨 3개 | — | — | 설계 | OB:68 | — | 카디널리티를 라벨 3개로 제한 |
| C-44 | 인프라 | 스택 트레이스 병합: ^\d{4}-\d{2}-\d{2}T로 시작하지 않는 줄을 앞 항목에 붙임(stage.multiline, 최대 대기 3s) | 3s | — | — | 설계 | OB:69 | — | — |
| C-45 | 인프라 | Loki 보존: retention_period 168h, compactor는 기동 뒤 10분 기다렸다 시작 | 168h, 10분 | 168h / 24 = 7일(직접 계산) | — | 설계 | OB:70 | — | — |
| C-46 | 인프라 | Alloy가 /var/run/docker.sock을 :ro로 읽지만 소켓으로 Docker API 전부 호출 가능해 호스트 root와 같은 권한. 공식 이미지라 수용 | — | — | 설계상 수용 | 판단 | OB:71 | — | 보안 위험을 알고 수용한 판단 |
| C-47 | 인프라 | 배포 서버 compose 프로젝트 이름이 iseonjwa가 아니면 오류 없이 로그가 0건이 됨(Alloy가 프로젝트 라벨로 선택). 배포 첫 확인에 Loki label/service/values 앱 7개 확인 | — | — | ISE-174 | 설계 | OB:74 | — | 조용히 실패하는 함정 |
| C-48 | 인프라 | Alloy는 5초마다 컨테이너 목록을 다시 찾음(기본 1분). 1분 안에 죽는 컨테이너 로그도 남기려는 것 | 5초(기본 1분) | 1분/5초 = 12배 빈번(직접 계산) | — | 설계 | OB:75 | — | — |
| C-49 | 인프라 | Loki 받기 한도를 기본(초당 4MB, 스트림당 3MB)보다 넉넉히(초당 16MB, 스트림당 8MB). 측정 때 loki_discarded_samples_total이 늘지 않는지 본다 | 4MB/초 → 16MB/초, 3MB → 8MB(스트림당) | 16/4 = 4배, 8/3 = 약 2.67배(직접 계산) | 설계값 | 설계 | OB:76 | — | 부하에서 로그가 거절되지 않게 한도를 올림 |
| C-50 | 인프라 | Alloy는 새로 뜰 때 컨테이너의 지난 로그부터 읽음(6시간 전 줄도 들어옴), 위치는 alloy 볼륨에 남음 | 6시간 | — | — | 로컬 측정 | OB:77 | — | — |
| C-51 | 인프라 | 수집 확인(2026-11-04): Loki /ready ready, 앱 7개 모두 보임, 라벨 env·level·service·service_name, Alloy 오류·경고 없음. traceId 메타데이터 붙은 줄 최근 12시간에 reservation 7줄, eureka 4줄. 스택 트레이스 항목 7개가 한 항목으로 묶임 | 앱 7개, 7줄, 4줄, 항목 7개 | — | 2026-11-04, 로컬 | 로컬 측정 | OB:79-84 | — | — |
| C-52 | 인프라 | "요청 로그" 대시보드 request-logs.json, UID iseonjwa-request-logs. 패널: 트레이스(Zipkin) → 이 요청의 로그(모든 서비스 시간순) → 서비스별 4개 → 접힌 앱 7개 WARN·ERROR. 기본 시간 범위 최근 15분 | 15분 | — | — | 설계 | OB:95-107 | 대시보드(Grafana, 파일 infra/grafana/dashboards/request-logs.json) | 프론트 traceId 클릭으로 로그·트레이스를 한 화면에 |
| C-53 | 인프라 | 프론트가 여는 주소의 시간 범위는 요청 시각 앞뒤 15분(시계 차이와 요청 뒤 Kafka 소비자 로그까지 포함) | ±15분 | — | — | 설계 | OB:107, OB:219 | — | — |
| C-54 | 인프라 | 대시보드 확인(2026-11-04, 로컬): Loki에 있던 traceId로 쿼리해 줄 하나가 나오고, Zipkin traceID 쿼리로 Gateway 트레이스 스팬 표를 받음 | 줄 1개 | — | 2026-11-04, 로컬 | 로컬 측정 | OB:108 | — | — |
| C-55 | 인프라 | Alertmanager 알림: 전송 대상 슬랙 #이선좌-성능-모니터링(2026-11-04 생성), 배포 결과는 #이선좌-배포상태알림. 해결 알림 보냄 | — | — | — | 설계 | OB:116-119 | — | — |
| C-56 | 인프라 | 알림 묶기: group_by env·service·severity, group_wait 30s, group_interval 5m, 반복 기본 4h·immediate 1h. critical이 울리면 같은 env·service·alertname의 warning 억제 | 30s, 5m, 4h, 1h | — | — | 설계 | OB:120-122 | — | — |
| C-57 | 인프라 | Alertmanager 구현: prom/alertmanager 0.34.2 digest 고정, mem_limit 128m, 127.0.0.1:9093. 웹훅 주소는 .env SLACK_WEBHOOK_MONITORING를 compose configs 파일로 만들어 api_url_file로 읽음 | 128m | — | ISE-179 | 설계 | OB:130, OB:118 | — | 메모리 관련(2절 참조) |
| C-58 | 인프라 | 웹훅을 파일로 넘기는 이유: Alertmanager는 환경 변수를 안 풀고, compose secrets environment 방식은 변수 없으면 compose 전체 실패, configs content가 비면 파일 대신 디렉터리가 생김(Compose v5.6.0, 2026-11-04 확인). 자리표시 주소 http://127.0.0.1:9/slack-webhook-not-set | — | — | 2026-11-04 | 로컬 측정 | OB:137 | — | 설정 함정 3개를 확인하고 우회 |
| C-59 | 인프라 | STACK_ENV로 Prometheus external_labels.env를 채움(기본 local, 배포 deploy). Prometheus 3.15.1은 external_labels의 ${VAR}를 기능 플래그 없이 풀어줌 | — | — | 2026-11-04 확인 | 로컬 측정 | OB:138 | — | — |
| C-60 | 인프라 | 알림 메시지 링크: 배포 서버 결제 대시보드는 Grafana 공개 주소 <PUBLIC_URL>/grafana(2026-11-16 ISE-278 전에는 http://<서버 IP>:3000). 처음에는 모두 localhost라 배포 서버 알림의 대시보드 링크가 열리지 않았음(2026-11-12 ISE-256) | — | — | 2026-11-12, 2026-11-16 | 설계 | OB:134 | — | 알림 링크가 localhost라 안 열리던 문제 |
| C-61 | 인프라 | 알림 검사: script/check-prometheus-rules.sh가 promtool 규칙 검사·테스트, 설정 검사, amtool check-config, --tree로 즉시 확인 알림만 1시간 반복 하위 경로인지 확인, 템플릿 렌더 검사. CI prometheus-rules 워크플로 | — | — | — | 설계 | OB:140 | — | — |
| C-62 | 인프라 | 알림 확인(2026-11-04, 로컬): 환불 확인 알림 2건 슬랙 전송, 결제 DB 12초 정지로 PaymentDbConnectionTimeout 발생·약 10분 뒤 해결 메시지, 미리보기 3종. 전송 실패 0건 | 12초 정지, 약 10분, 실패 0건 | — | 2026-11-04, 로컬 compose | 로컬 측정 | OB:142 | — | DB를 12초 멈춰 실제 알림이 슬랙에 도착 |
| C-63 | 결제 | 건별 알림(ISE-183): 사용자가 "몇 번 유저가 언제 결제한 건인지"를 알림에 담기 원함(2026-11-04). 세 안(Loki 로그 알림 / 결제 서비스 직접 슬랙 전송 / 말투만 바꾸고 상세는 링크) 중 Loki 로그 알림 선택, 2026-11-09 여섯 결정 | 안 3개 중 1개, 결정 6개 | — | 2026-11-09 | 판단 | OB:147-156 | — | 선택지 비교 → Loki 로그 알림 선택 |
| C-64 | 결제 | 건별 알림 결정: paidAt은 payments.created_at을 Asia/Seoul ISO-8601 초 단위, 금액 불일치는 NMC 사유(PG_AMOUNT_MISMATCH·REFUND_AMOUNT_MISMATCH)로 합침, 해결 메시지 없음, 한 회차에 규칙 하나가 남기는 새 OPEN 건별 줄 10건 상한(초과 시 요약 한 줄) | 상한 10건 | — | 2026-11-09 | 설계 | OB:151-156 | — | — |
| C-65 | 결제 | 건별 로그 줄 형식(ERROR, 필드 순서 고정, 값 없으면 -): event paymentId userId reservationId paidAt. 사건 4종(PAYMENT_NEEDS_MANUAL_CHECK, REFUND_NEEDS_MANUAL_CHECK, RECONCILIATION_MISMATCH_OPEN, DEAD_LETTER_OPEN). afterCommit에 찍어 롤백 전이는 줄 없음. 개인정보는 내부 ID와 시각뿐(공개 채널) | 사건 4종 | — | — | 설계 | OB:158-171 | — | — |
| C-66 | 인프라 | Loki ruler: 1분마다(evaluation_interval 1m) 쿼리 4개(최근 10분 ERROR 줄)를 평가. 규칙 4개(PaymentNeedsManualCheckEvent 등). for 없음, urgency immediate, severity critical | 1분, 쿼리 4개, 10분 | — | Loki 3.7.9 | 설계 | OB:173-174, OB:193 | — | — |
| C-67 | 인프라 | regexp 뒤 \| event="…" 필터가 없으면 라벨 없는 알림이 생겨 건별 경로를 못 탐. 검사 스크립트의 미끼 줄이 잡음 | 미끼 줄 3건, 정상 줄 4건 | — | check-loki-rules.sh | 로컬 측정 | OB:174, OB:177 | — | 미끼 줄 테스트가 잡은 버그 |
| C-68 | 인프라 | 건별 알림 Alertmanager 설정: 수신자 slack-monitoring-event(send_resolved false), group_by [alertname, env, paymentId, reservationId, refundId, rule, eventKey]라 결제 한 건마다 메시지 하나, group_wait 10s, repeat_interval 24h | 10s, 24h | — | — | 설계 | OB:175 | — | — |
| C-69 | 인프라 | 한계: 줄을 읽는 구간 10분이라 Loki·Alertmanager를 함께 --force-recreate하면 10분 안의 같은 줄이 새 알림으로 한 번 더 올 수 있음. 결제 한 건에 묶이지 않는 알림은 지표 알림으로 둠 | 10분 | — | — | 설계 | OB:178 | — | — |
| C-70 | 인프라 | 서비스 다운·Alertmanager 장애 알림은 미정. 실제 슬랙·실제 NMC 결제로 끝까지 보는 확인은 사용자가 함 | — | — | 미정 | 설계 | OB:126, OB:233-234 | — | — |
| C-71 | 인프라 | 메모리 추가분: loki 512m, alloy 256m, alertmanager 128m, 추가 합계 896m. SA 9-3 합계 11,392m에 더하면 약 12,288m(12.0GB), 75% 규칙이면 필요 메모리 약 16.0GB로 16GB급이 정확히 한계 | 11,392m → 약 12,288m, 필요 약 16.0GB | 512+256+128 = 896m. 11,392+896 = 12,288m. 12,288/0.75 = 16,384m(직접 계산) | 값은 모두 [가정] | 계산값 | OB:184-191 | — | 관측 도구가 서버 메모리 예산을 한계까지 밀어올린다 |
| C-72 | 인프라 | 유휴 실측(2026-11-04, 로컬 compose, docker stats): loki 약 83MiB, alloy 약 53MiB. 부하 없는 값이라 상한 512m·256m은 그대로 [가정] | loki 83MiB(상한 512m), alloy 53MiB(상한 256m) | 83/512 = 약 16%, 53/256 = 약 21%(직접 계산, MiB와 m을 같은 단위로 봄) | 유휴, 로컬 | 로컬 측정 | OB:195 | — | 상한 대비 유휴 사용량 |
| C-73 | 프론트/인프라 | 시연 방식 M3: Grafana를 프론트 화면과 나란히 띄우고 프론트가 응답 본문 traceId를 보여주면 대시보드가 그 traceId로 서비스별 로그를 거른다. 응답 본문에 traceId가 있음을 401 응답으로 확인(헤더에는 없음) | — | — | 2026-11-03 확인, 사용자 결정 | 시연 | OB:199-201 | — | 시연 때 traceId 하나로 서비스 로그를 따라가기 |
| C-74 | 인프라 | 배포 서버 Grafana 접근: 2026-11-05에 3000을 팀원 IP에만 열었고, 2026-11-16(ISE-278)부터 nginx 443의 /grafana/로 중계, 익명 Viewer만 | — | — | 2026-11-05, 2026-11-16 | 설계 | OB:214 | — | — |
| C-75 | 인프라 | 서비스가 응답을 못 만드는 경우(게이트웨이 시간 초과 등)의 본문에 traceId가 있는지는 미확인 | — | — | 미확인 | 설계 | OB:202 | — | — |
| C-76 | 인프라 | 알림 문구 정책: 결제 규칙 summary를 합니다체로 바꾸고 해야 할 일을 끝에 붙임(2026-11-04 사용자 요청). 색 띠 해결 #1E7F4F·심각 #D12B3A·경고 #F59F00 | 색 3종 | — | 2026-11-04 | 설계 | OB:133, OB:143 | — | — |
| C-77 | 팀 | OP는 설계 문서(측정 전 가설)이며 2026-11-05 ISE-181 담당 최권우 요청으로 Claude Code가 작성 | — | — | 상태: 설계, 측정 전 | 판단 | OP:3 | — | AI가 가설을 세우고 측정으로 검증하는 구조 |
| C-78 | 추첨/Gateway | 오픈 피크 트래픽 가정: 회차 1개, 15,000석(11/16부터 측정 시드 1,500석, ADR 0060), 30,000명이 오픈 시각에 대기열 진입, 초당 입장 N=50 | 30,000명, 15,000석 → 1,500석, N=50 | 30,000/50 = 600초(문서 표기 600초) | 가정 | 설계 | OP:16 | — | 오픈 피크 가정 |
| C-79 | 기타 | 구간별 초당 요청(계산): Z2 수백~수천(10초에 한 번 새로고침이면 초당 3,000건), Z3 약 3,000(30,000÷10초), Z4 약 2,700(30,000÷약 11초), Z5 50, Z6 50~150, Z7 50~150, Z8 최대 약 50, Z9 최대 약 50 이벤트 | Z3 약 3,000/초, Z4 약 2,700/초, Z5 50/초 | 30,000/10 = 3,000, 30,000/11 = 2,727(직접 계산) | 가정 | 계산값 | OP:20-28 | — | 어느 구간에 요청이 몰리는지 |
| C-80 | 기타 | 요청 수가 가장 많은 곳은 대기열(Z3·Z4)과 오픈 전 조회(Z2), 요청 한 건 비용이 가장 큰 곳은 좌석 조회(Z6), 처리 한도가 가장 낮은 곳은 결제 승인(Z8)과 결제 결과 반영·만료(Z9·Z10) | — | — | 가설 | 판단 | OP:31 | — | — |
| C-81 | 좌석 | 좌석 조회(Z6)는 실측으로 오류 없이 버틴 최대 속도 초당 10명(P0''), 초당 100명에서 조회 성공 19건. 요청마다 15,000석 엔티티를 읽고 커넥션 5개 | 초당 10명(한계), 초당 100명에서 성공 19건 | 100/10 = 10배 부하에서 성공 19건(직접 계산) | 배포 메모리 + G1 | 배포 측정 | OP:38, OP:223 | — | 좌석 조회가 가장 먼저 무너짐 |
| C-82 | 기타 | reservation-service 공통 자원: DB 커넥션 5개, Tomcat 요청 스레드 200개(기본), @Scheduled 스레드 1개(입장·회차 캐시 갱신·만료·추첨 4종이 공유), 힙 768MB(배포), 자동 Serial GC | 커넥션 5, 스레드 200, 스케줄러 1, 힙 768MB | — | 2026-11-05 코드 기준 | 설계 | OP:85-90 | — | 한 프로세스가 모든 구간을 나눠 씀 |
| C-83 | 좌석 | 실측에서 좌석 조회 하나가 Tomcat 스레드 200개를 모두 묶었다 | 200개 | — | k6-overload-recovery.md 3-1절 인용 | 배포 측정 | OP:92 | — | 한 구간이 다른 구간을 같이 무너뜨림 |
| C-84 | 좌석 | C1-1 빨리 거절: 커넥션 대기 30초 → 1초. 2026-11-05 local 측정에서 R1(커넥션 대기 1초)만으로 회복, R2·R3 시작값은 R1보다 나빴음. R1만 ISE-196으로 제안 | 30초 → 1초 | 30/1 = 30배 단축(직접 계산) | 2026-11-05, local | 로컬 측정 | OP:97 | — | 커넥션 대기를 1초로 줄여 회복 |
| C-85 | 좌석/추첨 | 추첨 실행은 한 트랜잭션에서 최대 약 4.5초(ISE-87 실측). 그동안 입장 약 200명분이 빠짐 | 약 4.5초, 약 200명 | 4.5 x 50 = 225명(원문 "약 200명", 직접 계산 참고) | ISE-87 실측, 스케줄러 스레드 1개 공유 | 로컬 측정 | OP:98, OP:202 | — | 추첨 실행이 대기열 입장을 멈추게 함 |
| C-86 | Gateway | Gateway 인증 요청마다 Redis EXISTS auth:blacklist:{jti} 1번(타임아웃 100ms, auth-redis 차단기). 차단기는 최근 20회 중 50% 이상이 50ms를 넘으면 10초 열려 인증 필요 요청 전부 503(fail closed, ADR 0005). 요청 제한 없음 | 100ms, 20회 중 50%, 50ms, 10초 | — | 2026-11-05 코드 기준 | 설계 | OP:108-112 | — | 인증 Redis 1번 호출이 차단기를 열 수 있음 |
| C-87 | Gateway | local에서는 K1-H 중에도 Gateway 차단기가 네 번 열렸다. EC2에서는 Redis가 50ms를 넘는지부터 확인 | 4회 | — | local, k6-seathold-bottleneck-plan.md 5-4절 인용 | 로컬 측정 | OP:118 | — | — |
| C-88 | Gateway | G-2 블랙리스트 jti를 Gateway 메모리에 캐시(블룸 필터) 시 인증 요청당 Redis 호출이 거의 0으로 줄 것이라는 가설. 로그아웃 반영이 갱신 주기만큼 늦음(보안 결정 ADR 0005과 함께 검토) | — | — | 가설 | 설계 | OP:120 | — | — |
| C-89 | Gateway | G-3 요청 제한(RequestRateLimiter) 제안. 폴링 간격을 어기면 429. G1 시나리오는 초당 1,000 → 2,000 → 4,000 → 8,000건 | 1,000 → 8,000건/초 | 8,000/1,000 = 8배 증가 범위(직접 계산) | 가설, 측정 전 | 설계 | OP:114, OP:121 | — | — |
| C-90 | 기타 | Z2 오픈 전 조회: GET /api/v1/concerts는 읽기 전용 트랜잭션 안에서 쿼리 3번(페이지, 공연 전체 count() 요청마다, 다가오는 회차 IN). 캐시 없음, 커넥션 5개를 좌석 조회·선점과 공유 | 쿼리 3번/요청 | — | 2026-11-05 코드 기준 | 설계 | OP:129-135 | — | — |
| C-91 | 기타 | Z2 B-1: DB 왕복 3 → 1~2로 줄면 한도 1.5~2배(가설). B-2 로컬 캐시(Caffeine) TTL 5분(SA 9-4 "공연 정보 5분") 시 한도 수십 배(가설) | 3 → 1~2 왕복, 1.5~2배, 수십 배 | 3/2 = 1.5, 3/1 = 3(직접 계산, 원문은 1.5~2배) | 가설, 측정 전 | 설계 | OP:142-143 | — | 캐시로 DB를 안 거치게 하려는 가설 |
| C-92 | 기타 | B1 시나리오: 오픈 10분 전부터 30,000명이 상세를 10~30초마다 새로고침, 초당 1,000 → 3,000건 | 1,000 → 3,000건/초 | 3,000/1,000 = 3배 | 가정 | 설계 | OP:137 | — | — |
| C-93 | 대기열 | Z3 대기열 진입: 회차를 메모리 캐시(30초마다 갱신)에서 찾고 Lua 한 번(enter.lua: 판매 시각 확인, ZSCORE, INCR seq, ZADD)으로 처리. 캐시가 비면 요청마다 DB findById, 초당 3,000건이 커넥션 5개로 감 | 30초, 3,000건/초, 커넥션 5 | — | 오픈 직후 재시작 시 | 설계 | OP:152-160 | — | 캐시 미스가 DB로 몰리는 위험 |
| C-94 | 대기열 | Q1 시나리오: 30,000명이 10초 안에 진입(초당 3,000건), 이어서 60,000 → 120,000명 | 30,000 → 60,000 → 120,000명 | 120,000/30,000 = 4배(직접 계산) | 가정 | 설계 | OP:160 | — | — |
| C-95 | 대기열 | Z4 순번 폴링: DB 없이 읽기 전용 Lua(TIME, ZSCORE 2번, GET). 다음 폴링 clamp(대기 초, 3초, 10초) + 0~20% jitter. 대기자 30,000명 ÷ 평균 11초 = 초당 약 2,700건이 10분(30,000 ÷ 50) 동안 이어짐 | 약 2,700건/초, 10분(600초) | 30,000/11 = 2,727, 30,000/50 = 600초(직접 계산) | 가정 | 계산값 | OP:172-177 | — | 요청 수 1위 구간 |
| C-96 | 대기열 | 폴링 1건마다 Gateway(JWT 검증, Redis 1번, 요청 로그 1줄)와 서비스(스레드 1개, Redis 1번, 요청 로그 1줄, 추적 구간 배포값 100%) 비용이 생김 | — | — | 배포 추적 100% | 설계 | OP:178-179 | — | — |
| C-97 | 대기열 | Q2-2 poll-max 10초 → 20~30초 시 폴링 부하가 간격에 반비례해 1/2~1/3(가설). 입장 토큰 유효 시간 10분을 쓸 위험. Q2-4 SSE·WebSocket은 연결 수가 대기자 수만큼 늘어남 | 10초 → 20~30초, 1/2~1/3 | 10/20 = 1/2, 10/30 = 1/3(원문과 일치) | 가설 | 설계 | OP:187-189 | — | 폴링 간격 조정 vs 밀어주기 비교 |
| C-98 | 대기열 | Z4 가설: 초당 2,700건은 Redis에는 가볍고 Gateway CPU나 로그·추적 비용이 먼저 한도가 될 것. Q2-1은 요청 로그 끄기·추적 1.0으로 비용 비율을 잼 | 초당 2,700건 | — | 가설 | 설계 | OP:185-186, OP:385 | — | — |
| C-99 | 대기열 | Z5 입장: 500ms마다 판매 중 회차를 돌며 admit.lua 호출. Redis 시각으로 같은 초에 두 번 실행 안 함, 한 번에 admitted를 50 올림, 지난 초는 안 따라잡음, 만료 입장 토큰 정리 한 번에 최대 500건. 입장 토큰 발급 issue-token.lua는 멱등 | 500ms, 50, 500건 | — | 2026-11-05 코드 기준 | 설계 | OP:196-201 | — | — |
| C-100 | 대기열 | Q3-3 핵심: N은 Z6~Z8 측정(좌석 조회 한계 초당 10명)에서 정해야 함. 지금 N=50은 너무 큼. 대기열은 뒤쪽이 감당할 만큼만 들여보내는 장치 | N=50 vs 좌석 조회 한계 초당 10명 | 50/10 = 5배(직접 계산) | 가설 | 판단 | OP:211-213, OP:388 | — | 대기열이 뒤쪽 구간을 보호하려면 입장 속도를 한도에 맞춰야 한다 |
| C-101 | 결제 | Z8 결제 승인: PG 허가 Bulkhead 동시 10, 대기 0(못 얻으면 즉시 503 PG_UNAVAILABLE). PG 연결 0.5초·응답 2초·재시도 없음. 커넥션 풀 20개 | 동시 10, 연결 0.5초, 응답 2초, 풀 20 | — | 2026-11-05 코드 기준 | 설계 | OP:242-246 | — | — |
| C-102 | 결제 | 처리량 상한 = 동시 수 ÷ PG 응답 시간. PG 300ms면 10 ÷ 0.3 = 초당 약 33건. 오픈 피크 최대 약 50건 시 약 3분의 1이 503 | 초당 약 33건 vs 최대 약 50건 | 10/0.3 = 33.3, (50−33)/50 = 약 34%(직접 계산) | PG 지연 300ms 가정 | 계산값 | OP:248-251 | — | 결제 동시 수가 처리량 상한을 정함 |
| C-103 | 결제 | Mock PG 기본 지연이 0이라 지연을 안 넣으면 결제 한도가 실제보다 10배 넘게 높게 나옴. 측정 때 DELAY 300ms | 0ms → 300ms | 10배 이상(원문) | — | 설계 | OP:72, OP:252 | — | 지연을 주입 안 하면 한도가 가려짐 |
| C-104 | 결제 | PAY-1 리틀의 법칙: 동시 수 = 목표 처리량 × PG 응답 시간. 초당 50건 × 0.3초 = 15, 여유 2배면 30 | 15, 30 | 50 x 0.3 = 15, 15 x 2 = 30 | 가설 | 계산값 | OP:259 | — | 리틀의 법칙으로 Bulkhead 크기를 정함 |
| C-105 | 결제 | P1 시나리오: 결제만 초당 20 → 40 → 80 → 160건, Mock PG DELAY 300ms, 변형으로 PG 지연 1초·2초(타임아웃 경계) | 20 → 160건/초 | 160/20 = 8배 범위 | 가정 | 설계 | OP:254 | — | — |
| C-106 | 결제 | PAY-2 대기 0 → 100~200ms, PAY-3 차단기 느린 호출 기준 추가(현재 실패율만 봄), PAY-4 자동결제를 별도 Bulkhead로 분리 | 0 → 100~200ms | — | 제안 | 설계 | OP:260-262 | — | — |
| C-107 | 결제 | Z9 결과 반영: outbox 릴레이 500ms마다 20건씩 최대 10묶음, Kafka 토픽 자동 생성이라 파티션 1개·소비자 동시 1개. 이벤트 1건마다 DB 왕복 약 8~10번, 건당 10~20ms면 소비자 1개로 초당 50~100건. 목표는 결제→확정 2초 이내(SA 9-4) | 20건 x 10묶음, 파티션 1, 8~10왕복, 10~20ms, 초당 50~100건, 2초 | 1000ms/10~20ms = 50~100건/초(직접 계산, 원문과 일치) | 가정 | 계산값 | OP:269-280 | — | — |
| C-108 | 결제 | R-2 payment.result 파티션 3개·소비자 동시 3개 시 처리량 약 3배(가설), 이때 커넥션 5개가 다음 한도. R-3 왕복 8~10 → 5~6 | 파티션 1 → 3, 왕복 8~10 → 5~6 | 3/1 = 3배, 5~6/8~10 = 약 0.5~0.75배(직접 계산) | 가설 | 설계 | OP:288-289 | — | — |
| C-109 | 좌석 | Z10 선점 만료: 5초마다 최대 100건, 최대 초당 20건. local 실측 초당 약 15.6건(주기가 처리 시간 약 1.4초만큼 길어짐). 미결제 비율 40% 초과(초당 50 × 0.4 = 20) 시 만료가 밀림 | 최대 초당 20건 → 실측 초당 약 15.6건, 처리 약 1.4초, 40% | 100/5 = 20건/초, 15.6/20 = 78%(직접 계산), 50 x 0.4 = 20 | local 실측(open-peak-scenarios.md 3절 인용) | 로컬 측정 | OP:299-302 | — | 만료 처리 속도가 입장 속도를 못 따라감 |
| C-110 | 좌석 | X1 시나리오: 만료 5,000건 미리 만들어 끝나는 시간 측정. X-0 기준선 예상 5,000건에 약 250초(초당 20건) | 5,000건, 약 250초 | 5,000/20 = 250초(직접 계산) | 가설 | 계산값 | OP:304, OP:308 | — | — |
| C-111 | 추첨 | Z11 응모 마감 직전 초당 100건, 동기 처리(201, 202 접수형 아님), 쿼리 3번, 커넥션 5개를 일반 예매와 공유, 대기열·입장 토큰 없음 | 초당 100건, 쿼리 3번 | — | 코드 확인 | 설계 | OP:319-323 | — | — |
| C-112 | 추첨 | 자동결제 2,000건: 배정 스케줄러 1초마다 최대 100명, 당첨자마다 남은 좌석 전체(당첨자 2,000명 × 좌석 3,000석)를 다시 읽음. 결제 소비자 동시 1개·PG 동기라 Bulkhead 10개 중 실제로는 1개만 사용. PG 300ms면 초당 약 3.3건, 2,000건에 약 10분(목표 10분 안에 95%의 경계). 추첨 문서는 Bulkhead 10을 다 쓴다고 보고 약 1분으로 계산(모순) | 2,000건, 초당 약 3.3건, 약 10분 vs 문서 간 계산 약 1분 | 1/0.3 = 3.3건/초, 2,000/3.3 = 약 600초(직접 계산). 모순: 소비자 동시 1개 가정 약 10분 vs 추첨 문서 Bulkhead 10 가정 약 1분 | PG 300ms | 계산값 | OP:326-331, OP:383 | — | 추첨 자동결제가 동시 1개에 묶여 약 10분 걸린다는 발견 |
| C-113 | 추첨 | LOT-2 소비자 동시 수를 3~5개로 늘리면 자동결제 시간이 동시 수에 반비례해 2~3분(가설) | 약 10분 → 2~3분 | 10분/3~5 = 2~3.3분(직접 계산) | 가설 | 설계 | OP:339 | — | — |
| C-114 | 기타 | 공통 조건 표: 추적 샘플링 배포 1.0 → 측정 0.01, 요청 로그 켠 채 잼(한 번 꺼서 차이 측정), Mock PG 지연 0 → 300ms, 발생기 노트북 → 같은 VPC EC2, 결과 보관 파일 → remote write | 1.0 → 0.01, 0ms → 300ms | — | 2026-11-05 시점 현황 | 설계 | OP:67-75 | — | 측정 전 공통 조건을 맞추는 이유 |
| C-115 | 인프라 | local k6 컨테이너는 약 32초마다 3초씩 멈췄다(2026-11-05 확인). local에서는 Gateway Redis 차단기 503 같은 환경 잡음이 섞여 발생기를 EC2로 옮김 | 약 32초마다 3초 정지 | 3/32 = 약 9.4% 정지(직접 계산) | 2026-11-05, local k6 | 로컬 측정 | OP:74 | — | 노트북 발생기의 한계를 발견해 EC2로 옮김 |
| C-116 | 기타 | 측정 방법: 구간별 단독 시나리오와 한 흐름(O1) 두 층. 모든 구간에 p95·p99, 성공·정상 거절·오류 건수, 오류 없이 버틴 최대 속도(속도 두 배씩 60초씩, 오류율 1% 미만 가장 높은 속도), 부하 뒤 회복 시간 | 60초, 오류율 1% | — | — | 설계 | OP:53-63 | — | 오류 없이 버틴 최대 속도로 비교 |
| C-117 | 좌석 | 앞 구간이 막히면 뒤 구간까지 요청이 안 가므로 단독 시나리오가 필요: K1에서 선점 개선이 "0건 → 0건"으로 보인 경험 | 0건 → 0건 | — | k6-seathold-bottleneck-plan.md 5-3절 인용 | 판단 | OP:56 | — | 앞 구간이 막혀 뒤 구간 개선이 안 보이던 경험 |
| C-118 | 좌석 | K1-H 기준선에서 선점 경로는 커넥션 5개로도 초당 100명을 버팀, 첫 1분 이후 p95 20ms 미만. 단계별 차이를 보려면 속도를 초당 100 → 800명까지 | 초당 100명, p95 20ms 미만, 100 → 800 | 800/100 = 8배(직접 계산) | k6-seathold-bottleneck-plan.md 5-4절 인용 | 배포 측정 | OP:234 | — | — |
| C-119 | 좌석 | P4a~d 좌석 조회 개선: 필요한 열만 DTO, 정적 배치 정보 캐시, 좌석 상태 1초 캐시(SA 9-4), 응답 JSON 재사용(조건부), 후속으로 응답 크기 약 830KB 축소 | 약 830KB, 캐시 1초 | — | 2026-11-06 개정(R1 켠 채 측정) | 설계 | OP:219-228 | — | — |
| C-120 | 기타 | O1 한 흐름: 오픈 10분 전 상세 새로고침(10~30초) → 대기열 진입(10초에 30,000명) → nextPollAfterMillis 폴링·입장 → 좌석 조회·1~3초 고민·선점·409면 재시도 2번 → 5~30초 뒤 결제(10% 이탈 가정) → 확정 확인 2초 간격 최대 5번. 판정: 중복 판매 0, 대사 불일치 0 | 30,000명, 10% 이탈, 재시도 2번, 2초 x 5번 | — | 가정 | 설계 | OP:346-357 | — | 한 흐름으로 최종 판정 |
| C-121 | 기타 | 측정 순서: 공통 기반 → Q2·Q1 → B1 → K1·K1-H·P1·R1·X1 → Q3와 N 역산 → L1·L2 → O1 | 7단계 | — | 최권우 결정 | 설계 | OP:361-369 | — | — |
| C-122 | 팀 | 담당: 공통 기반·O1·선점·만료·측정 최권우, 공연 조회·좌석 조회·확정 소비자 반효승, 대기열 권영은, 결제·outbox 안준현, 추첨 이강민 | — | — | 2026-11-05 | 판단 | OP:392-401 | — | — |
| C-123 | 팀 | 결정 이력: 스크립트 위치 loadtest/k6/(2026-11-05 최권우), 측정 전용 compose 덮어쓰기와 measure-mode.sh(2026-11-08), Mock PG 300ms(2026-11-08), 측정 전용 토큰 키(2026-11-08 사용자) | — | — | 2026-11-05, 2026-11-08 | 판단 | OP:410-413, OP:375 | — | — |
| C-124 | 기타 | 구간별 개선 우선순위: 1순위 Z6 좌석 조회와 C1 공통, 2순위 Z2·Z8·Z11, 3순위 Z4·Z5·Z10, 4순위 Z9·Z3, C2 Gateway는 local에서 관찰 | — | — | 가설, 측정 전 | 판단 | OP:35-47 | — | — |
| D-01 | 기타 | 시나리오 12종(G1,B1,Q1,Q2,Q3,K1,PAY1,R1,X1,L1,L2,L3,O1) 명세를 구간별로 정함. 숫자는 모두 `[가정]`이고 측정 결과를 본 뒤에는 판정 기준을 바꾸지 않는다 | 시나리오 ID 13개 표기(K1·K1-H 묶음) | — | 2026-11-05 작성, 11/09 검토 반영 | 설계 | OPS:3-27 | — | 측정 전에 판정 기준을 못 박고 시작 |
| D-02 | 인프라 | 기본 측정 환경: 배포 EC2 한 대 + 같은 VPC 임시 발생기, 추적 샘플링 0.01, Mock PG DELAY 300ms, 실행마다 재시작·새 회차, 30초 워밍업(목표 10%) | 샘플링 0.01, DELAY 300ms, 워밍업 30초 | — | 1-1절 | 설계 | OPS:33-35 | — | 측정 규칙 |
| D-03 | 인프라 | 회차 좌석 수 기준이 1,500석(ADR 0060), 11/16 전 측정은 15,000석 | 15,000석 → 1,500석 | 직접 계산: 1/10 | 11/16 기준 변경 | 설계 | OPS:43,286 | — | 좌석 규모를 10분의 1로 바꾼 결정 |
| D-04 | 팀 | 결과 기록 규칙: 시나리오마다 환경 기록표, "오류 없이 버틴 최대 속도", "부하 뒤 회복 시간"을 적는다 | — | — | 1-4절 | 설계 | OPS:61-64 | — | — |
| D-05 | Gateway | G1 설계: `GET .../queue/me`로 초당 1,000 → 2,000 → 4,000 → 8,000건(단계 60초), 판정 p95 100ms 이하·오류 1% 미만, 차단기 미열림. 10,000명 돌려 씀 | 1,000→8,000/s | 직접 계산: 8배 | 명세(가정) | 설계 | OPS:68-81 | — | 단일 서버 한계를 찾는 계단식 부하 |
| D-06 | Gateway | `auth-redis` 차단기 설정(1차 기본): 창 20 / 최소 20 / 느린 호출 50ms·50% / 실패율 50% / 열림 10초. 열리면 보호 API 전부 즉시 503(fail closed, ADR 0005). 호출 시한 100ms | 창 20, 50ms, 50%, 10초, 시한 100ms | — | `config-repo/gateway.yml` | 설계 | OPS:87-94 | — | 차단기가 정상 요청까지 막는 구조 |
| D-07 | Gateway | 다른 시나리오 6곳에서 서비스보다 `auth-redis`가 먼저 막음: Q1-d 300명 0.1초·1초에서 503 68~93%(100명·1초는 오류 0, Redis 지연 평균 0.15ms) | 503 68~93% | — | local, 노트북 한 대 | 로컬 측정 | OPS:96-100 | — | Redis는 멀쩡한데 차단기가 먼저 열림 |
| D-08 | Gateway | K1-H base: 선점만 초당 100명부터 Gateway CPU 100~180%에서 느린 호출 비율 90~100%로 차단기 열림, 거절 약 6만 1천 건, Redis CPU 10% 아래 | 거절 약 61,000건, Redis CPU <10% | — | local, 최권우 | 로컬 측정 | OPS:102 | — | — |
| D-09 | Gateway | K1-H(Gateway GC를 G1로): 통과 요청이 초당 약 600건을 넘자 Redis 확인 평균 0.5ms → 8~13ms, 시한 초과 초당 5~45건. GC를 바꿔도 같아 GC는 원인 아님 | 0.5ms → 8~13ms | 직접 계산: 16~26배 | local | 로컬 측정 | OPS:104 | — | 원인 후보를 하나씩 지움 |
| D-10 | Gateway | K1 S(P4b) 좌석 조회+선점 160명부터 Gateway 503 10,366건 중 10,048건이 차단기 즉시 거절 | 10,048 / 10,366건 | 직접 계산: 96.9% | local | 로컬 측정 | OPS:105 | — | — |
| D-11 | Gateway | 해석: Gateway가 바쁘면 Redis가 멀쩡해도 호출 시간이 느린 호출/시한 초과로 세어진다. 판단 창이 20번이라 순간 지연에도 열리고 열리면 10초간 정상 요청까지 거절 | — | — | 11/09 시점 해석, 배포 확인 전 | 판단 | OPS:107-111 | — | 차단기가 혼잡을 장애로 오해 |
| D-12 | Gateway | 측정 환경: K1·K1-H 단계 측정은 측정 브랜치에서만 느린 호출 판정 100ms·실패율 100%로 덮어씀(11/07 결정). 공유 설정은 그대로 | 100ms, 100% | — | — | 판단 | OPS:113 | — | — |
| D-13 | 기타 | B1 설계: 공연 상세 80%·목록 20%, 초당 500→1,000→2,000→4,000건(단계 60초), 사용자 30%는 10초, 70%는 30초 간격 새로고침 가정. B1-d(상세 단독, `b1d-concert-detail.js`, PR #438): p95 300ms 미만·오류 1% 미만 | 500→4,000/s | 직접 계산: 8배 | 명세 | 설계 | OPS:117-132 | — | — |
| D-14 | 대기열 | Q1 설계: 30,000명을 10초 안에(초당 3,000), 이어서 60,000(6,000/s)·120,000명(12,000/s). 기준 p95 200ms 이하·오류 1% 미만. 순번 판정은 Redis `seq`가 첫 요청 201 건수와 같을 것 | 30,000→120,000명 / 3,000→12,000/s | 직접 계산: 4배 | 명세(가정) | 설계 | OPS:134-152 | — | 순번 중복 없음을 증명하는 장면 |
| D-15 | 대기열 | Q1-d: 사용자 수만큼 VU를 미리 두고 1초 안에 앞쪽이 붐비게(절반이 앞 약 0.3초) 진입. 발생기 출발 지연 p95 50ms 이하일 때만 서버 결과로 인정 | 절반이 앞 0.3초, `send_lag` p95 ≤50ms | — | `q1d-queue-burst.js`, ISE-217 | 설계 | OPS:144,151 | — | — |
| D-16 | 대기열 | Q2 설계: 대기자 30,000→120,000명이 서버가 준 간격으로 폴링, 계산값 약 2,700건/s, 한도 p95 100ms 이하·오류 1% 미만 | 약 2,700건/s | — | 명세 | 설계 | OPS:154-166 | — | 요청 수 1위 구간 |
| D-17 | 대기열 | Q3 설계: 입장 속도 N=50/초, 10분 동안 초당 45~50인 초가 95% 이상, 0인 초가 2초 넘게 이어지지 않음 | 50/s, 95% | — | 명세 | 설계 | OPS:168-179 | — | — |
| D-18 | 결제 | PAY1 설계: 초당 20→40→80→160건, 계산값 PG 동시 10 ÷ 0.3초 ≈ 초당 33건에서 503 `PG_UNAVAILABLE` 시작 예상. 장애 후 차단기 30초 안에 닫힘 | 20→160/s, 약 33건/s | 직접 계산: 8배 | 명세 | 설계 | OPS:181-201 | — | 계산값을 측정으로 확인 |
| D-19 | 결제·예약 | R1 설계: 승인→확정 p95 2초 이하인 최대 속도, 25→50→100→200건/s | 25→200/s | 직접 계산: 8배 | 명세(SA 9-4) | 설계 | OPS:203-218 | — | — |
| D-20 | 좌석 | X1 설계·예상: 만료 처리 속도 local 관찰 초당 약 15.6건(5초 주기·100건 묶음, 한 주기 약 6.4초), 5,000건에 약 320초. 처음 계산(초당 20건, 약 250초)은 한 주기 처리 약 1.4초를 빼먹은 값 | 20/s → 15.6/s, 250초 → 약 320초 | 직접 계산: 20÷15.6=1.28배 느림, 5,000÷15.6=320.5초 | local 관찰 | 설계(정정) | OPS:228,563 | — | 계산값이 실측에 맞춰 정정된 순간 |
| D-21 | 추첨 | L1 설계: 마지막 1분에 6,000건(초당 100), 초당 100→200→400→800건, 단계 60초. 기본 단계면 사용자 약 10만 명(11/06 local 100,384건) | 100→800/s, 약 100,384건 | 직접 계산: 8배 | 좌석 3,000석, 예비 1,000명 | 설계/로컬 | OPS:236-246 | — | — |
| D-22 | 추첨 | L1 local 관찰: 201 50,860건, 응모 행 48,553 = 고유 사용자 48,553(재요청은 같은 멱등키라 201 중복 정상). 초당 400건부터 1~2ms짜리 503 | 201 50,860 / 행 48,553 | 직접 계산: 중복 응답 2,307건(50,860−48,553) | 2026-11-06 local | 로컬 측정 | OPS:241-244 | — | 중복 응모 0 증명 |
| D-23 | 추첨·결제 | L2 설계: 당첨 2,000명 10분 안 95% 자동결제. 소비자 동시 1개·PG 300ms면 2,000건 약 10분. local 처리량 초당 3.10건이면 2,000건 약 10.4분이라 기준 초과 가능 | 3.10건/s, 약 10.4분 | 직접 계산: 2,000÷3.10=645초=10.75분(원문은 약 10.4분) | local 참고 | 설계/로컬 | OPS:248-268 | — | 배정 직렬 소비자의 한계 |
| D-24 | 추첨 | L3 설계: 선점 p95 증가 20% 이하, 입장 0인 초 2초 이하. 추첨 실행 시간이 local에서 0.5초·4.5초 두 갈래 | 20%, 0.5초/4.5초 | — | 명세 | 설계 | OPS:270-280 | — | — |
| D-25 | 팀 | O1 설계: 30,000명, 회차 1,500석(11/16 전 15,000석), Mock PG 300ms. 정합성(중복 판매 0·승인=확정), 선점·결제 p95가 Gateway 3초 안, 오류 1% 미만, 부하 후 30초 내 회복 | 30,000명, 3초, 1%, 30초 | — | 명세 | 설계 | OPS:282-299 | — | 최종 한 흐름 판정 기준 |
| D-26 | 기타 | 결과 절 시점: 배포 측정은 1차(11/09)·2차(11/10)·3차(11/10 저녁)·4차(11/10 UTC=KST 11/11)·S1/B1-d(11/11)·PAY1(11/12)이 있고 나머지 측정 전. 스크립트 12개는 11/05 local에서 동작만 확인 | — | — | 결과 절 머리 | 기타 | OPS:303 | — | 측정 타임라인 |
| D-27 | 인프라 | 배포 서버 사양: m7i.xlarge 4 vCPU 15.7GiB Ubuntu 24.04, 한 대에 전체 서비스. 발생기 c6i.xlarge 4 vCPU 8GiB 같은 VPC, k6 v1.0.0 | 4 vCPU/15.7GiB, 발생기 4 vCPU/8GiB | — | 배포 1차 환경표 | 배포 측정 | OPS:311-312 | — | 서버 한 대 한계 서사의 전제 |
| D-28 | 인프라 | 1차 컨테이너 메모리 상한: gateway 768MiB, reservation-service 1,152MiB, redis 512MiB. 힙 gateway -Xms256m -Xmx512m, reservation -Xms384m -Xmx768m(G1) | 768/1,152/512MiB | — | 배포 1차 | 설계/설정 | OPS:313-314 | — | — |
| D-29 | Gateway | 1차 Q1 초당 300명 재기동 직후: 실패, 201/503 = 849/2,152, p95 62.8ms, 느린 호출로 열림 | 849/2,152 | 직접 계산: 503 비율 71.7% | 배포, 10초, 기본 설정 | 배포 측정 | OPS:327 | — | 재기동 직후 첫 몰림 |
| D-30 | Gateway | 1차 Q1 초당 300명, 초당 100명 5분 뒤: 통과, p95 5.51ms, 201 3,001 / 503 0 | 62.8ms → 5.51ms | 직접 계산: 11.4배 감소(62.8÷5.51), 단 상태(식음/데움)가 다름 | 배포 | 배포 측정 | OPS:327-328 | — | 워밍업 효과 |
| D-31 | Gateway | 1차 Q1 초당 1,000명 데움: 통과, p95 12.95ms, 10,001/0 | 12.95ms | — | 100/s 5분 + 3,000/s 실행 뒤 | 배포 측정 | OPS:329 | — | — |
| D-32 | Gateway | 1차 Q1 초당 3,000명: 실패, 201/503 = 41/29,959, 차단기 열림(빠른 실패 10건, 느린 호출 0) | 41/29,959 | 직접 계산: 503 99.86% | 배포 | 배포 측정 | OPS:330 | — | — |
| D-33 | Gateway | bulkhead 25로 초당 1,000명(워밍업 100/s 5분): 실패, 201/503 = 561/9,440, p95 163.22ms | 561/9,440 | 직접 계산: 503 94.4% | 배포 | 배포 측정 | OPS:331 | — | — |
| D-34 | Gateway | 표준 워밍업 뒤 초당 1,000명×2 bulkhead 25: 통과(p95 7.15·6.14ms, 10,001/0), 초당 2,000명: 실패(428/19,573, 열림) | 428/19,573 | 직접 계산: 503 97.9% | 배포 | 배포 측정 | OPS:332-333 | — | 2,000/s에서 차단기 열림 |
| D-35 | Gateway | bulkhead 100으로 올려도 1,000×2 통과(p95 56.06·16.95ms), 2,000/s는 실패(503/19,498 → 201 503건), 열림 | 503/19,498 | 직접 계산: 503 97.5% | 배포 | 배포 측정 | OPS:334-335 | — | bulkhead만 올려선 소용없음 |
| D-36 | Gateway | reservation-service만 재기동 직후 초당 300명: p95 307.95ms, 201 3,001/0, 차단기 정상 | 307.95ms | — | 각 1회라 가능성 | 배포 측정 | OPS:336,340 | — | — |
| D-37 | Gateway | 1차 결론: 오류 없이 버틴 최대 속도 = 데운 상태 초당 1,000명. 2,000/s에서 `auth-redis` 열림. Redis 서버 지연 0.1~0.8ms, 모든 실행에서 Redis seq=waiting=201 수 | 1,000/s, Redis 0.1~0.8ms | — | 1차 | 배포 측정 | OPS:338-339 | — | 서버는 정확, 차단기가 문제 |
| D-38 | Gateway | 부하 뒤 회복: 열린 뒤 요청이 없으면 계속 열려 있다(자동 반열림 꺼짐), 10초 뒤 첫 요청부터 반열림, 시험 호출 5건 성공으로 닫힘 | 10초, 5건 | — | 1차 | 배포 측정 | OPS:342 | — | — |
| D-39 | Gateway | Q1-d·Q1-c는 차단기가 먼저 막아 배포에서 재지 않음. bulkhead 끄는 설정(`AUTH_BULKHEAD_ENABLED=false`)은 지표가 안 사라져 실제로 꺼졌는지 확인 못 함 | — | — | 1차 | 기타(확인 못 함) | OPS:338,343 | — | — |
| D-40 | 대기열 | Q2 대기자 3,000명(VUS 300) 10분: 통과, poll p95 2.79ms, 폴링 200/503 = 193,563/0, 초당 322(계산값 약 327), 준 간격 중앙값 10,616ms | 322 vs 약 327 | 직접 계산: -1.5% | 배포 | 배포 측정 | OPS:349 | — | 계산값과 실측이 맞음 |
| D-41 | 대기열 | Q2 대기자 10,000명: 실패, 폴링 200/503 = 19,848/1,910,147, 초당 평균 3,181(재시도 포함, 계산값 약 964), 시작 3초 뒤 차단기 열려 600초간 회복 못 함 | 3,181 vs 964 | 직접 계산: 3.3배(3,181÷964) | 배포 1차 | 배포 측정 | OPS:350,354 | — | 재시도 폭주로 자가 회복 불가 |
| D-42 | 대기열 | 10,000명 실패 원인: 503을 받은 대기자가 3초 뒤 재시도, 503이 빨리 돌아와 초당 2,300~3,800건으로 불음. 지수 백오프를 검토 거리로(ISE-59). setup 진입(10,000명 6초)은 성공 | 2,300~3,800건/s | — | — | 배포 측정/판단 | OPS:354 | — | 재시도가 장애를 키운 반전 |
| D-42b | 대기열 | 폴링 계산값: 간격이 clamp(순번/50초,3초,10초)×(1+0~20%), 앞 500명이 3~11초마다 호출. 명세 근사(3,000명이면 약 270건/s)와 다름 | 약 270 → 약 327(3,000명) | 직접 계산: 1.21배 | — | 계산값 | OPS:352 | — | — |
| D-43 | 대기열 | Q3 대기자 3,000명 120초: 통과, 초당 입장 45~50인 초 58/60(96.7%), 0인 초 최대 연속 0, 토큰 발급 p95 3.4ms, 3,000 발급/오류 0. 첫 초 76, 이어 58초 50, 마지막 24 | 50/s 유지 96.7% | — | 배포 | 배포 측정 | OPS:359-363 | — | 입장 속도 설정이 지켜짐 |
| D-44 | 기타 | Q3 30,000명 10분, Q3-b·Q3-c, Q2 30,000명(1차)은 재지 않음 | — | — | — | 기타 | OPS:355,363 | — | — |
| D-45 | Gateway | 2차: 느린 호출 기준만 100·101ms로 올리자 느린 호출로는 안 열리고(창 20 중 최대 3) 대신 실패로 열림. 기준만 바꿔선 식은 Gateway 첫 몰림을 못 넘김 | 100ms: 201/503 = 73/2,928, p95 138.27ms; 101ms: 792/2,209, p95 97.37ms | — | 초당 300명 식음 | 배포 측정 | OPS:383-389 | — | 임계값 튜닝만으로는 부족 |
| D-46 | Gateway | 2차 실패 호출 최대 시간 1.0s는 중복 기록(Redis 시한 초과가 차단기에 실제 시간+1,000ms로 2번)일 가능성, dev #463 뒤 확인(실제 110~115ms) | 1.0s → 110~115ms | — | 2차→3차 | 배포 측정/정정 | OPS:390,420,542 | — | 측정 도구의 이중 기록 발견 |
| D-47 | Gateway | 측정 A: Redis 연결은 기동 약 8초 뒤 1개 생겨 끝까지 그 하나. "첫 몰림에서 연결을 새로 맺다 막혔다" 가설은 기각 | 연결 수 1 | — | 101ms, 식음 | 배포 측정 | OPS:395-406 | — | 가설 하나 기각 |
| D-48 | Gateway | 몰림 중 JIT 컴파일 시간 합: 식음 100ms 6.1s, 식음 101ms 9.0s, 데움 0.34s. 식은 실행이 데운 실행의 18~26배 | 6.1s/9.0s vs 0.34s | 원문 18~26배 (6.1÷0.34=17.9, 9.0÷0.34=26.5 직접 계산 일치) | 5초 표본 | 배포 측정 | OPS:408-416 | — | 식은 JVM의 JIT가 첫 몰림에 몰림 |
| D-49 | Gateway | GC 멈춤 최대 61ms(식음 100ms), 17ms(식음 101ms), 6ms(데움). 61ms만으로 100ms 시한 초과가 설명되는지 확인 안 됨 | 61/17/6ms | — | — | 배포 측정 | OPS:413-417 | — | — |
| D-50 | Gateway | 3차 설정 5종(R1 기본 / R2 100ms / R3 E값 시간창10초·최소500·느린80%·열림5초·half-open20·bulkhead100 / F 100ms+bulkhead100 / G R3+bulkhead50) | 5종 | — | 3차 | 설계 | OPS:439-447 | — | 설정 다섯 가지 비교 |
| D-51 | Gateway | 식은 초당 300명: R1 열림(201 35, 오류 98.8%, p95 17.36ms), R2 열림(251, 91.6%), R3 안 열림(2,600, 13.3%, p95 269.16ms, 서버 CPU 95%), F(3,000, 0%, 47.94ms), G(3,001, 0%, 20.37ms) | 오류율 98.8% → 0% | — | 배포 3차 각 1회 | 배포 측정 | OPS:453-457 | — | 설정에 따라 오류 0 |
| D-52 | Gateway | 식은 초당 1,000명: R1 99.7% 오류(201 32), R2 99.2%(84), R3 37.6%(5,364, p95 7.7s, 서버 CPU 99.6%), F 76.7%(2,335), G 0.74%(9,926, p95 85.46ms, 서버 CPU 94%) | 오류율 99.7% → 0.74% | 직접 계산: 오류율 약 135배 차(99.7÷0.74) | 배포 3차 | 배포 측정 | OPS:458-462 | — | G만 식은 서버 1,000/s 소화 |
| D-53 | Gateway | 데운 초당 1,000명: R3·F·G 모두 오류 0 (p95 27.29/13.76/5.83ms) | 5.83ms(G) | — | 데움 | 배포 측정 | OPS:463-465 | — | 워밍업 효과 |
| D-54 | Gateway | 데운 초당 2,000명: R3 오류 12.0% p95 7.02s dropped 3,070, F 94.1%(열림), G(1회차) 3.13% p95 500.5ms | 3.13% vs 94.1% | 직접 계산: 오류율 약 30배 차(94.1÷3.13) | 배포 3차 | 배포 측정 | OPS:466-468 | — | — |
| D-55 | Gateway | 데운 초당 3,000명 G: 오류 15.0%, p95 1.67s(p99 9.71s), dropped 2,860(발생기 CPU 245%라 발생기 한계 가능), 서버 CPU 99% | p95 1.67s, 성공 23,066 | — | 배포 | 배포 측정 | OPS:469,474 | — | G도 3,000/s에서 한계 |
| D-56 | 대기열 | Q2 10,000명 F 설정: 통과(오류 0, 폴링 575,485/0, 초당 안정 958, p95 7.32ms) vs 1차 기본 실패(19,848/1,910,147, 오류 98.5%, p95 33.76ms) | 오류 98.5% → 0, p95 33.76 → 7.32ms | 직접 계산: p95 4.6배 감소(33.76÷7.32). F 초당 958 vs 계산값 약 964 = -0.6%(원문) | 배포 | 배포 측정 | OPS:480-491 | — | 같은 부하, 설정만 바꿔 통과 |
| D-57 | 대기열 | Q2 30,000명 F: 실패·중단(폴링 6,360/746,045, 503 99.15%, 초당 안정 6,398·최대 8,310=계산값의 약 2.3배, 125초간 open, 상태 전환 CLOSED→OPEN 9회) | 6,398 vs 2,782 | 원문 약 2.3배(6,398÷2,782=2.30) | 배포 3차 | 배포 측정 | OPS:493-511 | — | 재시도 폭주가 다시 재현 |
| D-58 | 대기열 | Q2 30,000명 G: 실패·중단(서버 CPU 95% 초과 35초, 폴링 94,017/11,859 503 11.2%, 초당 2,582, poll p95 373.26ms, lateness p95 2.61s), 차단기 안 열림, 발생기 CPU 357% | 2,582 vs 계산값 2,782 | 직접 계산: -7.2% | 배포 3차 | 배포 측정 | OPS:495-513 | — | 차단기는 안 열렸지만 서버 CPU 포화 |
| D-59 | Gateway | F 실행 중 요청 로그가 10초에 약 64,000줄 → 컨테이너 로그(20MB×5)가 2분 안에 회전, 상태 전환 로그 약 1분 소실 | 약 64,000줄/10초 | 직접 계산: 약 6,400줄/초 | 배포 | 배포 측정 | OPS:514 | — | 로그가 증거를 지움 |
| D-60 | Gateway | G 초당 2,000명 반복 4회 p95: 500.5 → 162.2 → 138.3 → 44.5ms (오류율 3.13→3.29→1.90→0.19%) | 500.5 → 44.5ms | 직접 계산: 11.2배 감소. 원인이 JIT인지 확인 안 됨 | 배포, 회차 87·90·91 | 배포 측정 | OPS:516-523,548 | — | 같은 서버가 반복할수록 빨라짐 |
| D-61 | Gateway | G-H2k-4 시간 분해: k6 평균 13.4ms, auth-redis 평균 2.8ms, 대기열 처리 1.3ms, Gateway 전체 9.9ms; 초당 3,000명은 k6 평균 684.1ms, Gateway 바깥 몫 446.9ms, 내부 221.7ms | 평균 13.4ms ↔ 684.1ms | 직접 계산: 51배(684.1÷13.4) | 평균 요청(p95 구성 아님) | 배포 측정 | OPS:518-538 | — | 지연의 대부분이 Redis가 아닌 Gateway 안팎 |
| D-62 | Gateway | 대기열 처리(reservation 진입)는 평균 1.3~4.0ms로 병목 아님. Q2 세 실행 모두 seq=waiting=대기자 수 | 1.3~4.0ms | — | — | 배포 측정 | OPS:546 | — | 대기열 서버는 정확하고 빠름 |
| D-63 | 인프라 | 이 서버(4 vCPU 한 대)의 Q1 한계는 초당 약 2,000~2,300건. G 2,000/s 서버 CPU 95~97%, 3,000/s 성공 약 2,300/s·CPU 99%, Gateway 컨테이너 183~219% | 2,000~2,300/s | — | 3차 | 배포 측정 | OPS:545 | — | 장비 한계 숫자 |
| D-64 | Gateway | 차단기가 열린 원인 대부분은 bulkhead 거절이 차단기 실패로 세진 것(bulkhead_full 10~17건). 느린 호출로 열린 것은 R1 300명(50ms)과 F 식은 1,000명뿐 | 10~17건 | — | 3차 | 배포 측정 | OPS:541 | — | 차단기를 오작동시킨 건 bulkhead 거절 |
| D-65 | Gateway | G 설정을 기본값 후보로 제안(시간창 10초, 최소 500, 느린 100ms·80%, 열림 5초, half-open 20, bulkhead 50). bulkhead 거절을 차단기 실패로 세지 않도록 무시 예외 제안(→ dev #470 ISE-233) | — | — | 제안 | 판단 | OPS:551-552 | — | 설정 선택 근거 |
| D-66 | 인프라 | kafka 컨테이너가 Q1 부하와 무관하게 약 200%(184~209%)까지 오르는 구간. 원인 확인 안 됨 | 184~209% | — | 3차 | 배포 측정(원인 미확인) | OPS:547 | — | 미해결 질문 |
| D-67 | 좌석 | X1 local: 만료 스케줄러 초당 약 15.6건(35주기 3,500건 224초) | 3,500건/224초 | 직접 계산: 15.625/s | local 1회 | 로컬 측정 | OPS:563 | — | — |
| D-68 | 결제 | PAY1 local: mock-pg 재기동 직후 결제 서비스 PG 연결 끊김→차단기 열림 `PG_UNAVAILABLE`. 측정 절차: 재기동 뒤 30초 넘게 대기 | 30초 | — | local | 로컬 측정 | OPS:565 | — | 측정 절차를 낳은 실패 |
| D-69 | Gateway | 4차(G1, 배포 `74f91ffb`, 회차 92, 10,000명) 1회(bulkhead 100) vs 2회(bulkhead 50 + BulkheadFullException 무시): 1,000/s 100%/100%, 2,000/s 성공 99.53% vs 98.90%, 3,000/s 92.67% vs 87.96% | p95 2,000/s 208 → 157ms, 3,000/s 1.01s → 806ms | 직접 계산: 2,000/s p95 -24.5%(157÷208) | 배포, 단계 30초 | 배포 측정 | OPS:583-589,608 | — | bulkhead 50이 p95를 줄이고 거절을 늘림 |
| D-70 | Gateway | 4차: 두 회 모두 3,000/s까지 차단기가 안 열림. 503은 모두 bulkhead 거절이고 빠르게 끝남(elapsedMs 0~1), 2회는 failed 0·ignored 16,024(1회는 failed 8,027) | failed 8,027 → 0 / ignored 0 → 16,024 | — | 4차 | 배포 측정 | OPS:595-607 | — | 무시 예외가 배포에서 처음 동작 확인 |
| D-71 | Gateway | 4차: 성공 처리량은 두 회 모두 약 2,300~2,470/s에서 멈췄고 서버 CPU 99%. 한도는 차단기가 아니라 장비 CPU | 2,300~2,470/s | — | 4차 | 배포 측정 | OPS:609 | — | 병목 위치 이동 |
| D-72 | Gateway | 4차 서버 지표: Gateway CPU 최대 216%/213%, Redis CPU 9~17%/10~16%, GC 멈춤 최대 44/48ms | 216%/213% | — | — | 배포 측정 | OPS:599-602 | — | — |
| D-73 | Gateway | 5차 0a 기준선(회차 96, ADR 0050 값): 1,500/s에서 이미 서버 CPU 93%. 1,000/s 999 굿풋 p95 15ms; 1,500/s 굿풋 1,440 p95 152ms; 2,000/s 굿풋 1,469 p95 435ms 서버 CPU 99%; 2,500/s 굿풋 136(무효, VU 상한); 3,000/s 굿풋 33·성공률 23.26%(무효, OOM) | 굿풋 1,500/s 1,440 → 2,000/s 1,469 | — | 배포, 회차당 30초 | 배포 측정 | OPS:628-636,706 | — | CPU 포화가 먼저, 그다음 OOM |
| D-74 | Gateway | 5차: 차단기가 잰 호출 시간은 2,500/s에서도 평균 10.7~16.5ms, 최대 121ms로 느린 호출 비율 0.04~0.13%. 사용자 p95 878ms인데 Gateway 경로 시간 평균은 200~240ms, reservation 처리 평균 5~7ms. 사용자 대기 대부분이 차단기가 재는 구간 밖 | 차단기 평균 10.7~16.5ms vs 사용자 p95 878ms | 직접 계산: 약 53~82배 | 2,500/s | 배포 측정 | OPS:644-653 | — | 차단기가 보지 못하는 대기 |
| D-75 | Gateway | CPU 분해(docker stats 2,000/s): gateway 188%(55%), reservation 78%(23%), alloy 27%(8%), kafka 19%(6%), redis 14%(4%). Gateway 스레드: reactor-http-epoll 4개 130%, lettuce 39%. alloy는 부하와 무관하게 22~27% | 188%/78%/27% | — | 5차 | 배포 측정 | OPS:658-665,675 | — | CPU 주범 지도 |
| D-76 | Gateway | 한 건당 CPU(계산값): 서버 전체 3.0ms(1,000/s) → 2.5ms(1,500) → 2.0ms(2,000); gateway 1.16 → 1.07 → 0.95ms | 3.0 → 2.0ms | 직접 계산: 33% 감소 | 계산값 | 계산값 | OPS:666-674 | — | — |
| D-77 | Gateway | 4차 vs 5차 같은 2,000/s p95: 157ms → 435ms 악화. 원인 확인 못 함(후보: F1 뒤 Kafka/alloy 부하, 같은 회차 반복 상태, OOM 재시작 뒤 짧아진 웜업) | 157 → 435ms | 직접 계산: 2.77배 | — | 배포 측정 | OPS:676 | — | 미해결 모순 |
| D-78 | Gateway | Gateway OOM 4건(dmesg): 08:18:53(VU 5,938/MAX 6,000), 08:25:46(5,309), 08:35:11(3,000, RestartCount 3), 0a 3차 웜업은 VU 2,931에서 OOM 없음. 모두 anon-rss 757~758MB | 758MB, 768MiB 상한 | — | 배포 5차 | 배포 측정 | OPS:679-686 | — | CPU 포화보다 먼저 서비스를 내리는 실패 |
| D-79 | Gateway | 메모리 구성: 힙 최대 512MB + 비힙 약 158MB(Metaspace 90, 코드캐시 약 50) + JVM 밖 약 130MB ≈ 약 800MB > 768MiB. 직접 버퍼 5MB뿐. Gateway에는 동시 연결·처리 수 상한 없음(bulkhead 50은 Redis만 감쌈) | 800MB vs 768MiB | 직접 계산(원문 계산값): 합 약 800MB | 계산값 | 계산값 | OPS:687-697 | — | 원인을 메모리 합산으로 설명 |
| D-80 | Gateway | 메모리는 단계마다 올라가고 쉬는 구간에도 안 내려감: 1,000/s 501MiB → 1,500 505 → 2,000 527 → 2,500 639 → 3,000 OOM | 501 → 639MiB | 직접 계산: +27.5% | 5차 0a | 배포 측정 | OPS:693 | — | — |
| D-81 | Gateway | 11/10 03:50Z 컨테이너 OOM(java 약 754MB)이 Gateway였는지 4차 시점에는 확인 못 했고, 5차 OOM과 같은 원인일 가능성이 높음(추정) | 754MB | — | 4차 미해결 | 기타(추정) | OPS:611,698 | — | — |
| D-82 | Gateway | 결론: 차단기는 Redis 장애 전용으로 두고(ADR 0050), Gateway 과부하는 다른 장치로 막는다. 처음 거부한 AI 추천과 같은 결론을 측정으로 확인 | — | — | 5차 | 판단 | OPS:774 | — | AI 추천 거부가 측정으로 입증 |
| D-83 | Gateway | 0b: JFR 켠 기능별 CPU: Reactor 연산자 24.9%, Spring Web·보안 21.7%, Netty 17.6%, 필터·라우팅 8.2%, 추적 6.7%, JWT 6.6%, 지표 6.3%, 로그 5.2%, Redis(Lettuce) 2.0%, 차단기·벌크헤드 0.5% | 표본 4,181건 | 직접 계산: Redis+차단기 2.5%(원문) | JFR 20ms | 로컬/배포 측정(JFR) | OPS:721-740 | — | 차단기 코드는 CPU의 작은 몫 |
| D-84 | Gateway | 관측 묶음(추적 6.7+지표 6.3+MDC 약 2.2+문맥 전파 약 8.5+관측 규약 일부) 약 24%(근사, 추정) | 약 24% | — | 계산값 | 계산값 | OPS:738 | — | 관측 비용이 큼 |
| D-85 | Gateway | JFR 부담: JFR 스레드 CPU 0.4~0.7%, 1,000/s에서 메모리 501 → 535MiB(약 30MiB 더). 0a 대비 굿풋 차이는 반복 변동 안(2,000/s p95 435 → 497ms) | +34MiB | — | 0b | 배포 측정 | OPS:707,712 | — | — |
| D-86 | Gateway | 줄일 항목 예상 이득(계산): 관측 축소 약 24% → 서버 CPU 약 12%, 헤더 7% → 3.5%, JWT 6.6% → 3.5%, Redis·차단기 2.5% → 1.3%. 겹치므로 더하면 안 됨 | 12%/3.5%/3.5%/1.3% | 계산값 | 2,000/s Gateway 한 건당 0.95ms 기준 | 계산값 | OPS:741-750 | — | — |
| D-87 | Gateway | B20(느린 호출 100 → 20ms): 차단기 한 번도 안 열림. 2,500/s에서도 느린 호출 비율 최대 24%(기준 80%에 못 미침), 서버 CPU 99%, 사용자 p95 약 1초. 1,000/s 굿풋 999, 2,000/s 굿풋 1,463 p95 528ms | 느린 호출 비율 24% vs 80% | — | 5차 B20 | 배포 측정 | OPS:708,754-763 | — | 임계를 극단까지 내려도 안 열림 |
| D-88 | Gateway | 가설 정리: "차단기를 예민하게 하면 CPU 포화 직전에 열려 CPU를 내린다"는 가설이 기각됨. 이유: 사용자 대기 대부분이 차단기 구간 밖(D·C 대기)이고, 기준을 정상 호출 시간(1~5ms)까지 내려야 CPU 90% 전에 열리는데 그러면 평소에도 열림 | — | — | 5차 | 판단 | OPS:764-774 | — | 가설 → 실험 → 기각 |
| D-89 | Gateway | M1(Gateway mem_limit 768 → 1,024m, MaxDirectMemorySize 128m): OOM 0건(최대 849MiB), 그러나 p95는 2,000/s 752ms, 2,500/s 2.46s로 0a(435·878ms)보다 나쁨. 504(Gateway 3초 시한)가 나타남. 처리량 안 늘어남 | OOM 3→0건(웜업 포함 4건 중 0), 메모리 최대 849MiB | 직접 계산: 2,000/s p95 1.73배 악화(752÷435) | 배포 | 배포 측정 | OPS:776-793,834 | — | 죽는 실패가 느려지는 실패로 바뀜 |
| D-90 | Gateway | M1: 서버 메모리: 컨테이너 합 약 6.5GiB, 장비 15.7GiB 중 사용 가능 약 8.4GiB(free -m, 측정 직후) | 약 6.5GiB / 15.7GiB, 가용 8.4GiB | — | 측정 직후 | 배포 측정 | OPS:790 | — | — |
| D-91 | Gateway | 리틀의 법칙 L=λW: 0a 1,500/s L 46(W 31ms), 2,000/s L 261, 2,500/s L 1,153; M1 2,000/s L 377, 2,500/s L 2,603. p95 200ms를 지키는 처리 중 수 60~67 → 첫 상한 추천 64(설계 메모 500은 너무 큼) | L 35~46(1,500/s) vs 261~377(2,000/s), 추천 64 | 직접 계산: 1,480×0.040~0.045=59~67 | 계산값 | 계산값 | OPS:795-815 | — | 상한 64를 이론으로 정한 장면 |
| D-92 | Gateway | 처리 중 요청 하나에 약 47KiB(0a, 처리 중 약 46 → 3,000개일 때 메모리 501 → 639MiB). (768−500)÷0.047≈5,700개는 실제 OOM 5,309~5,938과 맞음. 1,024MiB면 약 1만 개 | 약 47KiB/요청, 5,700개 | 계산값 | 거친 값 | 계산값 | OPS:816-820 | — | 메모리 한도를 동시 수로 환산 |
| D-93 | Gateway | 6차(A1 동시 처리 상한) 설정: `iseonjwa.gateway.in-flight.max-requests` 32·64·128·500, 배포 `8b95fe68`, Gateway 메모리 상한 1,024MiB(M1 운영 반영), 웜업 동일 | 상한 32/64/128/500 | — | 배포 6차 | 설계 | OPS:836-850 | — | — |
| D-94 | Gateway | 2,000/s 굿풋/p95: 0a 1,469/435ms, M1 1,315/752ms, L32 1,575/49ms, L64 1,696/87ms, L128b 1,721/162ms, L500 1,304/571ms | p95 435 → 87ms(L64) | 직접 계산: 5.0배 감소(435÷87), 굿풋 +15.5%(227÷1,469) | 배포 6차 | 배포 측정 | OPS:886-891 | — | 상한 하나로 p95가 5분의 1 |
| D-95 | Gateway | 2,500/s: 0a 136/878ms(무효), M1 53/2.46s(무효), L32 1,503/56ms, L64 1,608/104ms, L128b 1,732/180ms, L500 511/624ms. 상한 있는 회차는 같은 발생기 설정에서 2,500/s도 유효(일찍 거절해 VU가 빨리 돌아옴: L64 최대 VU 208 vs M1 5,792) | L64 최대 VU 208 vs M1 5,792 | 직접 계산: VU 27.8배(5,792÷208) | 배포 | 배포 측정 | OPS:886-894 | — | 거절이 발생기·서버 모두를 살림 |
| D-96 | Gateway | L500 대조군은 0a·M1과 같은 모양(처리 중 500까지, 2,000/s p95 571ms) → 개선은 상한 덕분. L32는 1,000/s에서도 거절 392건, 모든 단계 굿풋이 64보다 낮음(너무 빡빡) | 1,000/s 거절 392건 | — | — | 배포 측정/판단 | OPS:893,917 | — | 대조군으로 원인 분리 |
| D-97 | Gateway | 6차 Gateway 메모리는 모든 회차 511~540MiB로 평탄, OOM·재시작 0건(5차 0a 2,500/s 639MiB, M1 809MiB) | 511~540MiB | — | 6차 | 배포 측정 | OPS:880 | — | OOM 해결의 증거 |
| D-98 | Gateway | 거절 비용(계산, 2,500/s): 상한 32 0.66ms, 64 0.71ms, 128 0.70ms, 500 0.70ms(거절 비율 40/35/28/25%). 상한을 낮춰도 서버 CPU는 내려오지 않음(32에서도 1,500/s 93%, 2,500/s 99%) | 0.66~0.71ms | 계산값(포화 단계라 한계 있음, D-110 참조) | 6차 | 계산값 | OPS:897-907 | — | — |
| D-99 | Gateway | 상한 기본값 제안: 64 → 128(근거: 판정 기준을 지키는 가장 큰 값, 2,500/s 굿풋 L128b 1,732 vs L64 1,608). 감수: p95 여유 작음(180 vs 104ms), 반복 변동 안 차이 | 64 → 128 | 직접 계산: 2,500/s 굿풋 +7.7%(124÷1,608), 원문은 +124건(8%) | 제안(코드 미변경) | 판단 | OPS:920-934 | — | 상한 값 결정 근거 |
| D-100 | Gateway | L128 웜업 1 빠짐: Gateway 재생성 직후 setup 대기자 1만 명 진입 중 4건이 lookup_timeout으로 실패해 웜업 1이 21초에 끝남. 스크립트를 "시작 준비 실패 시 한 번 다시"로 고친 뒤 L500·L128b 측정 | 4건, 21초 | — | 6차 | 배포 측정/절차 개선 | OPS:882 | — | 식은 Gateway 첫 요청 현상 재현 |
| D-101 | Gateway | 6차 회복: 단계가 끝난 4~7초 안에 서버 CPU 30% 아래, 쉬는 구간 p95 3~5ms | 4~7초 | — | 6차 | 배포 측정 | OPS:881 | — | 부하 뒤 회복 시간 |
| D-102 | Gateway | 7차 H1(상한 거절을 HttpHandler 데코레이터로, #497): 2,000/s 굿풋 1,696 → 1,781, p95 87 → 73ms; 2,500/s 1,608 → 1,814, p95 104 → 78ms. 서버 CPU 차이 ±1%p. 한 건당 Gateway CPU 거의 같음(2,500/s 0.71 → 0.72ms) | 2,500/s 굿풋 1,608 → 1,814 | 직접 계산: +12.8%(206÷1,608), 원문 +13% | 배포 7차, 배포 `1e9b1c75` | 배포 측정 | OPS:966-969,988-990 | — | 거절 위치 이동 효과 |
| D-103 | Gateway | 7차 H2(H1 + Gateway 요청 로그 끔): 서버 CPU 1,000/s 78% → 66%(-12%p), 1,500/s 93% → 85%(-8%p), 2,500/s 굿풋 2,122(6차 L64 1,608 대비 +32%, H1 1,814 대비 +17%), p95 60ms | 서버 CPU 78 → 66%, 굿풋 1,608 → 2,122 | 직접 계산: +32.0%(514÷1,608) | 배포 | 배포 측정 | OPS:970-985,994-997 | — | 로그 끄니 CPU 12%p 내려감 |
| D-104 | Gateway | H2의 해석: Gateway CPU는 거의 같고(114 → 111%) alloy가 22 → 14%, 컨테이너 합 250 → 237%. 서버 전체 감소(약 48%p 상당)와의 차이 약 35%p는 컨테이너 밖(Docker 데몬 가능성, 확인 못 함). 요청 로그 비용은 JVM이 아닌 로그 경로(stdout → Docker → Alloy → Loki)에 있다고 봄 | 22 → 14%(alloy), 250 → 237% | 직접 계산: 서버 12%p×4코어=48%p, 차이 약 35%p(원문) | 7차 | 배포 측정/해석 | OPS:995-997 | — | 보이지 않던 비용 위치 발견 |
| D-105 | Gateway | 요청 로그 운영 선택지 4안: A 끔, B 실패·느린 요청만, C 1% 표본, D 측정 때만 끔(강민 판단 필요, 이후 ADR 0052 problems 채택) | 4안 | — | 7차 | 판단 | OPS:1010-1017,1033 | — | 선택지 비교 → 채택 |
| D-106 | Gateway | 7차 H1의 1,000/s에서 상한 거절 242건이 새로 나옴(6차 L64 0건). 처리 중 최대 표본 5라 5초 표본 사이의 짧은 몰림(추정), 원인 확인 못 함 | 242건 | — | 7차 | 배포 측정(원인 미확인) | OPS:991 | — | — |
| D-107 | Gateway | 7차 상한 거절 수는 Prometheus와 k6가 약 40% 어긋남(H1 1,500/s k6 1,054 vs Prometheus 622). 원인 확인 못 함 | 1,054 vs 622 | 직접 계산: k6가 1.69배 | 7차 | 배포 측정(불일치) | OPS:976 | — | 측정 도구 불일치 |
| D-108 | Gateway | 7차 Gateway 메모리 510~519MiB 평탄, OOM 0, Zipkin 200ms 초과 추적 0건(샘플링 0.01) | 510~519MiB | — | 7차 | 배포 측정 | OPS:975,1026 | — | — |
| D-109 | Gateway | 8차 R0(거절 한 건 비용) 모델: Gateway CPU = b + a×받은 요청/s + r×거절/s. pidstat: a 1.063±0.013ms, r 0.021±0.021ms, r/a 0.020; docker stats: a 1.064ms, r 0.080ms, r/a 0.075. 기준 r/a ≤0.1이면 "H1 뒤 거절은 거의 공짜" → 가설 맞음 | r/a 0.020/0.075 | 원문: 거절 한 건은 받은 요청의 2~8% | 상한 1,000 vs 1 (대기자 1명) | 배포 측정/계산 | OPS:1082-1090 | — | 거절이 공짜에 가까움을 선형 모형으로 증명 |
| D-110 | Gateway | R0 뜻: 6차(거절 위치가 인증 직전) 거절은 받은 요청의 약 5분의 1(0.15~0.21ms)로 추정, H1이 그 대부분을 없앰. Netty 단계 더 앞당기기 이득 상한 약 0.02코어(서버의 0.5%) → 하지 않기로 | 거절 약 250/s × 0.08ms ≈ 0.02코어 | 계산값 | 8차 | 판단/계산값 | OPS:1090-1091 | — | 더 하지 않는다는 판단 |
| D-111 | Gateway | r0-R(상한 1) 단계: 1,000/s 목표에서 받은 요청 535/s, 거절 465/s, 성공률 53.52%; 500/s에서 449/51 | 535 vs 465 | — | 8차 | 배포 측정 | OPS:1057-1060 | — | — |
| D-112 | Gateway | 8차 P(problems) vs O(끔) 서버 CPU(두 회차 평균): 1,000/s 59.6 vs 56.7(+2.9%p), 1,500/s 78.5 vs 79.1(-0.5%p), 2,000/s 90.6 vs 90.1(+0.5), 2,500/s 96.3 vs 96.8(-0.6). 기준 ±2%p 같음/4%p 초과 바꿀 조건/2~4 보류 → 1,000/s만 보류 칸(표본 표준오차 약 2.5%p와 같은 크기) | +2.9%p | 원문 | 배포 8차, 배포 `eda80051` | 배포 측정 | OPS:1094-1105 | — | 사전 판정 기준에 따른 결론 |
| D-113 | Gateway | 8차 굿풋(2,500/s) P 2,268·2,237, O 2,242·2,275 동일, 성공 p95 1,000/s 6~8ms·2,500/s 50~54ms. alloy CPU g8-P1 실행 평균 0.7%(7차 1,000/s 22%) | alloy 22% → 0.7% | 직접 계산: 31배 감소(22÷0.7), 서로 다른 날·조건 | 8차 vs 7차 | 배포 측정 | OPS:1105 | — | 로그 비용 감소의 증거 |
| D-114 | Gateway | 같은 설정(O)이 7차 H2(1,000/s 66%·1,500/s 85%)보다 8차(56.7%·79.1%)에서 6~9%p 낮음 → 날·배포가 다르면 이만큼 달라지므로 7차와 직접 수치 비교 금지. 원인 확인 못 함 | 66 → 56.7%, 85 → 79.1% | 직접 계산: -9.3%p, -5.9%p | 7차 vs 8차 | 배포 측정(모순) | OPS:1108-1110 | — | 측정 변동의 크기 인정 |
| D-115 | Gateway | 8차 최고 처리: P2·O2 2,500/s 목표에서 서버 CPU 96~97%, 굿풋 2,237~2,275 | 2,237~2,275/s | — | 8차 | 배포 측정 | OPS:1064,1068,1072,1076 | — | 7차 H1 1,814 → 8차 약 2,250 (직접 비교 금지) |
| D-116 | Gateway | 발견: 6·7차 pidstat은 컨테이너 init만 잡음(compose `init: true`로 State.Pid가 init) → `gw-collect.sh`로 Gateway Java PID 잡도록 수정. 6·7차 `exit=0`은 k6 종료 코드가 아니었음(`$?` 위치 오류) → `g1-run.sh`에서 수정 | — | — | 8차 | 기타(측정 도구 오류 발견) | OPS:1040,1113-1115 | — | 측정 도구 자체의 결함 고백 |
| D-117 | Gateway | 8차 처음 계획(P·O·A 두 바퀴 + 상한 96·128)을 앞당기려 A 2회차와 상한 96·128을 뺌(강민 결정, 선조치 후보고) | — | — | — | 판단 | OPS:1034-1047 | — | — |
| D-118 | 좌석 | S1 1차(P4a, DTO 직접 조회): 초당 10~30은 p95 62~114ms 실패 0; 40/s p95 340~382ms; 50/s 중앙값 673ms~1.02s, p95 1.27~1.74s, 504 1~3건, 처리 45.7~47.0/s, 커넥션 대기 최대 59. 오류 없이 최대 초당 40건 | p95 30/s 94.5~113.6ms → 50/s 1.27~1.74s | 직접 계산: 11~18배 | 회차 73(15,000석, 응답 약 850KB), 예매 DB 풀 5 | 배포 측정 | OPS:1131-1154 | — | 응답 850KB 좌석 조회가 병목 |
| D-119 | 좌석 | S1 개선 전(P4a 이전 이미지): 초당 10건에서 중단. 중앙값 277ms~1.57s, p95 1.18~2.43s, 3회에서 500 121건(40%). reservation-service 메모리 1.116GiB / 상한 1.125GiB, CPU 약 250%(1차는 같은 속도에서 CPU 약 17%, 메모리 약 666MiB) | CPU 250% → 17%, 메모리 1.116GiB → 666MiB | 직접 계산: CPU 14.7배 감소(250÷17), 메모리 -40%(666÷1,116=0.597) | 같은 초당 10건 | 배포 측정 | OPS:1156-1166 | — | 요청마다 엔티티 3만 개 생성 |
| D-120 | 좌석 | 개선 전 3회 도중 04:44:16Z reservation-service가 오류 로그 없이 끊겨 Docker 재시작(RestartCount 1, `OOMKilled=false`). 메모리 상한 강제 종료로 보지만 dmesg는 미확인 | RestartCount 1 | — | 개선 전, 초당 10건 | 배포 측정(원인 미확인) | OPS:1165 | — | 서비스가 조용히 죽음 |
| D-121 | 좌석 | B1-d 공연 상세 3회: 100/s p95 4.0~11.0ms, 200/s 3.3~3.6ms, 400/s 3.8~4.4ms, 실패 0. 오류 없이 400건/s 이상(한도 미측정) | 400건/s 이상 | — | 회차 공연 1, 공개 API | 배포 측정 | OPS:1168-1176 | — | 공연 상세는 가볍다 |
| D-122 | 좌석 | 1차의 병목은 예매 DB: 좌석 쿼리가 요청마다 15,000행 조인·정렬, 풀 5개로 동시 5개만. 커넥션 대기 최대 124. 한계 찾기에서 50/s 처리 42.4건/s, 504 31건(2.2%), 서버 CPU 400% 중 postgres 약 210%, reservation 약 95%, gateway 약 45%, Kafka 12초마다 90~160% | 대기 124, CPU postgres 210% | — | 1차 한계 찾기 | 배포 측정 | OPS:1154,1180-1183 | — | — |
| D-123 | 좌석 | 1차의 실패는 auth-redis가 아니라 504(Gateway 응답 시한 3초 초과). 서비스 호출에는 차단기 없이 응답 시한 3초만 있음. 차단기 open 최대 0 | 504, 3초 | — | S1 | 배포 측정 | OPS:1180 | — | 병목을 차단기로 오인하지 않은 확인 |
| D-124 | 좌석 | S1 단계별 한계(상한 끔, 11/12·14): 개선 전 5/s(서비스 메모리·GC, CPU 약 220%, 메모리 1.11/1.125GiB, 10/s 500 2.6~7.0%), 1차 P4a 40/s(DB CPU 약 211%), 2차 P4b 100/s(커넥션 풀 5, 대기 최대 196, DB 약 95%), 3차 P4c 200/s(응답 만들기·전달 CPU, 처리 약 290~295/s) | 5 → 40 → 100 → 200건/s | 직접 계산: 개선 전 대비 40배(200÷5), 단계 사이 8배·2.5배·2배 | 회차 104(15,000석, 응답 약 820KB), 풀 5, 대기 시한 1초 | 배포 측정 | OPS:1186-1207 | — | 단계마다 병목이 서비스 → DB → 풀 → CPU로 이동 |
| D-125 | 좌석 | P4c에서 DB CPU 1~2%, 커넥션 대기 0. 처리 약 295/s에서 Gateway CPU 230~280%, 서비스 약 220%로 4코어 포화. 약 240MB/s 전송이 네트워크 대역폭에 닿았는지는 재지 않음 | 약 295/s, 약 240MB/s | 직접 계산: 295×0.82MB=약 242MB/s(원문 약 240) | 3차 | 배포 측정 | OPS:1205-1207 | — | 병목이 DB에서 CPU·전송으로 |
| D-126 | Gateway | 3차(P4c)에서 상한 값별: 상한 64 → 800/s 성공 8,819~9,067 503 14,834~15,059 p95 0.35~0.36s, 128 p95 0.79~0.84s, 256 p95 1.89~2.11s. 30초 성공은 상한과 무관하게 약 8,900건(초당 약 295) | p95 0.36 → 0.8 → 2초 | 직접 계산: 256 vs 64 p95 약 5.5배 | 배포 11/14 | 배포 측정 | OPS:1209-1217 | — | 상한을 키우면 지연만 늘고 성공은 같음 |
| D-127 | Gateway | 서버가 막히는 구간(초당 200)에서 상한 64: 2차 보강 성공 3,384~3,875(끔) → 4,043~4,333(64), 500 1,932~2,373 → 29~53, 503 0 → 1,571~1,849. 커넥션 풀이 막힐 때 64가 500을 503으로 바꾸고 성공은 약 15% 늘림 | 성공 약 15% 증가(원문) | 원문 | 2차 보강 | 배포 측정 | OPS:1219-1227 | — | 상한 64 유지 결정의 근거 |
| D-128 | Gateway | 큰 응답 위험: 상한 자리는 응답 마지막 조각 전달까지 잡힘. 로컬에서 본문 받기 1.73초 걸려 초당 500에서 자리가 64를 넘음(503 1.79%). 배포 발생기는 같은 망이라 재현 못 함. 대응은 상한 값이 아니라 대기열 입장 속도와 응답 크기 | 1.73초, 503 1.79% | — | local | 로컬 측정/판단 | OPS:1232 | — | 좌석 조회가 다른 API를 막을 수 있음 |
| D-129 | 좌석 | 11/12 처음 측정에서 1차가 초당 20에 실패 40%·DB CPU 약 310% → 원인: 회차 104가 마지막 자동 통계 갱신 뒤 생겨 실행 계획이 Nested Loop. `ANALYZE schedule_seats` 뒤 Hash Join, 42ms·실패 0·DB 약 67%. 156만 행에 15,000행(약 1%)은 자동 갱신 기준(10%) 미달 → ISE-284로 좌석 생성 API가 커밋 직후 ANALYZE | 실패 40% → 0, DB CPU 약 310% → 약 67%, 156만 행에 15,000행 | 직접 계산: DB CPU -78%, 15,000÷1,560,000=0.96% | 회차 104 | 배포 측정(트러블슈팅) | OPS:1237 | — | 통계 갱신 누락이 만든 가짜 병목 |
| D-130 | 인프라 | 서비스·Gateway를 다시 띄운 직후 첫 회에 503·500이 섞임(예: 2차 보강 상한 끔 R100#1 503 647건). 판정은 2·3회를 함께 봄 | 503 647건 | — | S1 11/14 | 배포 측정 | OPS:1238 | — | 재기동 직후 첫 회 현상 반복 |
| D-131 | 결제 | PAY1-a(PG 300ms, 허가 10): 초당 20→40→80 단계에서 승인 약 19.5/30.5/31.2건/s, 503 PG_UNAVAILABLE 1.7%(17건)/22.7%(448건)/59.5%(2,297건), 승인 p95 501/329/329ms. 전체 6,824건, 결제 오류 0 | 승인 31.2건/s vs 계산 약 33(10÷0.3) | 직접 계산: -5.5%(31.2÷33.3) | 배포 `08b08e64`, 회차 97 | 배포 측정 | OPS:1241-1264 | — | 계산값 33과 측정 31이 맞음 |
| D-132 | 결제 | PAY1-b(PG 지연 없음): 50/100/200건/s에서 승인 46/96/189건/s, 200/s에서 PG_UNAVAILABLE 177건(2.6%), GATEWAY_OVERLOADED 결제 6건·선점 11건, p95 20/24/139ms. 결제 서비스 1개가 초당 약 190건까지 받음. Hikari 대기 0(활성 최대 8) | 승인 약 190/s | — | 회차 98 | 배포 측정 | OPS:1266-1277 | — | PG가 빠르면 결제 서비스는 190/s |
| D-133 | 결제 | 결제 처리량은 결제 서비스가 아니라 `동시 허가 ÷ PG 지연`이 정함. 넘는 요청은 기다리지 않고 바로 503 | 10 ÷ 0.3초 | — | ADR 0018 | 판단 | OPS:1276 | — | 처리량 공식 |
| D-134 | 결제 | PAY-0 첫 단계(20/s) p95 501ms·p99 994ms는 서비스가 막 뜬 직후 예열로 보임. 다음 측정은 웜업 두고 판정에서 뺌 | p99 994ms | — | — | 배포 측정(추정) | OPS:1278 | — | — |
| D-135 | 결제 | PAY-1(허가 10 → 30): PG 300ms에서 승인 한도 초당 31.2 → 91.5건, 계산값 30÷0.32초≈94. 초당 80건에서 99.8% 승인. 지연 없는 200/s에서 PAY-0의 PG_UNAVAILABLE 2.6% → 0, 승인 p95 139 → 43ms | 31.2 → 91.5건/s, p95 139 → 43ms | 직접 계산: 2.93배(91.5÷31.2), 원문 3배; p95 3.2배 감소 | 배포 `08b08e64`, 허가 30 덮어쓰기 | 배포 측정 | OPS:1283-1326 | — | 설정 하나로 처리량 3배 |
| D-136 | 결제 | PAY-2(허가 30 + 대기 200ms): 과부하(120/s)에서 승인 91.5 → 98.5건/s(+7.7%)이나 승인 p50 309 → 491ms(약 180ms 늦어짐), 거절도 약 200ms 늦게. 스레드 최대 112 → 133. 대기 설정은 코드에 남기되 값은 0 | 91.5 → 98.5건/s, p50 309 → 491ms, 스레드 112 → 133 | 직접 계산: 승인 +7.7%(7÷91.5), p50 +59%, 스레드 +18.8% | PAY-2 ISE-238 `72758ec0` | 배포 측정/판단 | OPS:1303-1306,1322,1327 | — | 개선처럼 보였지만 손익이 안 맞아 값 0 유지 |
| D-137 | 결제 | PAY-2 재기동 공백: 컨테이너 시작 46초 뒤 부하 → 첫 10초 결제 176건이 503 SERVICE_UNAVAILABLE. Gateway 로그 `No servers available for service: payment-service`. 서비스가 healthy여도 Gateway가 새 인스턴스를 알기까지 시간이 걸림(배포에 Eureka·LoadBalancer 주기 값 없음, local만 5초). 측정 절차: 재생성 뒤 60초 넘게 대기. 운영 재배포에도 같은 공백 → 별도 이슈 | 176건, 46초, 60초 | — | PAY-2 | 배포 측정(트러블슈팅) | OPS:1328 | — | 재배포 공백이라는 운영 위험 발견 |
| D-138 | 결제 | 콜드 스타트: 세 실행 모두 재기동 직후 첫 단계 p99 1.0~1.4초. Prometheus 최대 승인 1.4~2.0초, PG 호출 0.45~0.55초, 첫 findByReservationId 0.6~0.9초, 커넥션 획득 0.03~0.16초. 원인 특정은 ISE-243 | 1.0~1.4초 | — | PAY1 | 배포 측정(원인 미확인) | OPS:1329 | — | — |
| D-139 | 결제 | 결제 서비스 안쪽 자원은 여유: Hikari 활성 최대 3~8/대기 0, 프로세스 CPU 최대 17~29%, JVM 스레드 91~133, 차단기 열림 0 | CPU 17~29% | — | PAY-1·PAY-2 | 배포 측정 | OPS:1316-1323 | — | — |
| D-140 | 결제 | 재지 않은 것: PAY1-b(1,000·1,900·2,500ms), PAY1-c(SERVER_ERROR 50%), 정합성 판정(이중 승인 없음, UNKNOWN 확정). 허가 30은 PG 느릴 때 PG 대기에 묶이는 스레드 수이기도 해 운영 반영 전 측정 필요 | — | — | — | 기타(미측정) | OPS:1279,1330 | — | — |
| D-141 | 결제 | 결제 서비스 환경: 1개, 컨테이너 768MiB, Hikari 풀 20, PG 동시 허가 10(넘으면 기다리지 않고 503, ADR 0018) | 768MiB, 풀 20, 허가 10 | — | PAY1 | 설계/설정 | OPS:1250 | — | — |
| D-142 | 대기열 | 대기열 입장 속도 `admit-per-second: 50`인데 PG 300ms 기준 한도 초당 31건 → 허가를 올렸을 때·대기를 줬을 때를 측정 | 입장 50/s vs 결제 한도 31/s | 직접 계산: 1.6배 격차(50÷31.2). 원문: 초당 80건은 입장 속도 50의 1.6배(80÷50) | PAY-1 배경 | 설계 | OPS:1285,1325 | — | 입구 속도와 결제 한도의 불일치 |
| D-143 | 인프라 | 검토 상태(11/10 마감): Q1 반영, Q2·Q3 local 확인 뒤, B1·R1 대기, PAY1·R1·L2 안준현 대기, L1~L3 반영(LOT-2 안준현과 논의 중), X1 자체 검토 반영(2대 변형 뺌), G1·O1 팀 대기 | — | — | 2026-11-10 마감 | 기타 | OPS:1334-1345 | — | — |
| E-01 | 인프라 | 배포 환경은 4 vCPU EC2 한 대에 앱 9개(프런트 포함)·PostgreSQL 3개·Redis·Kafka·관측 도구를 compose로 띄운다 | 4 vCPU, 21개 컨테이너가 400%를 먼저 잡는 쪽이 씀 | — | ADR 0047, `compose.deploy.yml` | 설계 | docs/design/infra/single-host-msa-resource-isolation.md:21,34 | — | "코드는 나뉘었지만 자원은 하나다" |
| E-02 | 인프라 | 서비스 간 경계 중 CPU·맨 앞 유입량·JVM 밖 메모리에는 경계가 없었다. 메모리·데이터·DB 연결은 이미 나뉨 | 컨테이너 `mem_limit` 상한 합 12,672MiB / 장비 약 15.7GiB(계산값) | — | 초안 시점(11/11) | 계산값 | docs/design/infra/single-host-msa-resource-isolation.md:25-38 | — | "MSA인 듯 아닌 MSA" 진단 |
| E-03 | 인프라 | Hikari 풀: reservation 5, user 5, payment 20 | 5 / 5 / 20 | — | `config-repo/*-service.yml` | 설계 | docs/design/infra/single-host-msa-resource-isolation.md:32 | — | — |
| E-04 | Gateway | 5차 0a: 목표 1,000/s에서 굿풋 999, p95 15ms, 서버 CPU 75% | 굿풋 999, 15ms, 75%, gateway 116%, reservation 52%, alloy 23%, kafka 12%, redis 12% | — | 배포 5차 0a, 2026-11-11 UTC 08:17~08:36, G1 인증 경로, ADR 0050 값 | 배포 측정 | docs/design/infra/single-host-msa-resource-isolation.md:42-46 | — | 부하별 CPU 쌓임 막대 |
| E-05 | Gateway | 5차 0a: 목표 1,500/s에서 굿풋 1,440, p95 152ms, 서버 CPU 93% | 1,440, 152ms, 93%, gateway 160%, reservation 72%, alloy 25%, kafka 31%, redis 15% | — | 위와 같음 | 배포 측정 | docs/design/infra/single-host-msa-resource-isolation.md:47 | — | 무릎 구간 |
| E-06 | Gateway | 5차 0a: 목표 2,000/s에서 굿풋 1,469, p95 435ms, 서버 CPU 99% | 1,469, 435ms, 99%, gateway 188%, reservation 78%, alloy 27%, kafka 19%, redis 14% | 목표 1,000→2,000/s 2.0배에 굿풋 999→1,469 = 1.47배(직접 계산 1,469÷999) | 위와 같음 | 배포 측정 | docs/design/infra/single-host-msa-resource-isolation.md:48 | — | 처리량이 목표를 못 따라가는 순간 |
| E-07 | Gateway | Gateway CPU의 약 70%가 HTTP 이벤트 루프, 약 20%가 Redis 응답 처리 | `reactor-http-epoll` 4개 합 130%, `lettuce-epollEventLoop` 39%(2,000/s) | — | 위와 같음 | 배포 측정 | docs/design/infra/single-host-msa-resource-isolation.md:50 | — | CPU 병목 위치 |
| E-08 | 인프라 | 1,500/s에서 한 건당 CPU: 서버 2.5ms, gateway 1.07ms, reservation 0.48ms, alloy 0.17ms | 2.5 / 1.07 / 0.48 / 0.17ms | — | 5차 0a 원천의 계산값 | 계산값 | docs/design/infra/single-host-msa-resource-isolation.md:51 | — | 요청 1건의 CPU 값 |
| E-09 | Gateway | Gateway가 OOM으로 3회 죽음(상한 768MiB, `-Xmx512m`) | 활성 VU 5,300~5,900에서 웜업 중 사망, 2,931에서 웜업 생존(메모리 최대 488MiB), 3,000/s 본 측정 중 사망 | — | 5차 0a. 비힙 약 158MB, JVM 지표에 안 잡힌 메모리 약 130MB(원천의 계산값) | 배포 측정 | docs/design/infra/single-host-msa-resource-isolation.md:52 | — | OOM 3회 → M1 메모리 예산 |
| E-10 | 인프라 | Kafka는 부하 없는 쉬는 구간에도 최대 140%까지 튀고, S1에서 12초마다 90~160% 튄다 | 최대 140%, 90~160%/12초 | 400% 중 최대 35% 순간 점유(140÷400, 계산값) | 원인 확인 못 함 | 배포 측정 | docs/design/infra/single-host-msa-resource-isolation.md:53,102 | — | 미해결 의문 |
| E-11 | 인프라 | 컨테이너 밖 CPU(dockerd·shim·커널 네트워크·수집기 추정)가 400% 중 약 13~17% | 1,000/s 약 68%, 1,500/s 약 52%, 2,000/s 약 54% | 서버 CPU×400% − gateway CPU÷gateway 몫(직접 계산 원문 표기, 300−232 / 372−320 / 396−342) | `docker stats` 3초 평균과 `vmstat` 1초 평균 혼합, 거칠다 | 계산값(추정) | docs/design/infra/single-host-msa-resource-isolation.md:59-60 | — | 보이지 않는 CPU 몫 |
| E-12 | 인프라 | 요청 2.0배에 alloy CPU 1.17배: 부하 비례보다 고정 비용이 큼 | alloy 23% → 27%, 요청 1,000 → 2,000/s | 2.0배 vs 1.17배(원문) | 추정, 확인할 질문 | 계산값(추정) | docs/design/infra/single-host-msa-resource-isolation.md:61 | — | 관측 도구 고정 비용 |
| E-13 | Gateway | M1: Gateway `mem_limit` 768m → 1024m, `-XX:MaxDirectMemorySize=128m`, `-Xmx512m` 유지. 5차 실패 조건(활성 VU 5,849) 재실행에서 OOM 0, 메모리 최대 785MiB | mem_limit 768m → 1024m, OOM 3회 → 0, 최대 785MiB | 상한 합 12,672 → 12,928MiB(원문 계산) | 측정 덮어쓰기, 5차 실패 조건 재실행. dev 반영, 배포 전 | 배포 측정(근거는 open-peak-scenarios.md, 본 문서는 요약) | docs/design/infra/single-host-msa-resource-isolation.md:9,71 | — | OOM 3회 → 메모리 예산 맞춤 → 0 |
| E-14 | Gateway | A안 여유 약 270MB: 1,024MiB(약 1,074MB) − 800MB | 여유 약 270MB | 직접 표기: 1,074−800=274 | 계산값 | 계산값 | docs/design/infra/single-host-msa-resource-isolation.md:71 | — | M1 산식 |
| E-15 | Gateway | B안(상한 768m 유지, `-Xmx384m`) 여유 약 130MB | 384+158+130=672MB, 768MiB(약 805MB) | 805−672=133(직접 계산) | 계산값. 채택은 A안 | 계산값 | docs/design/infra/single-host-msa-resource-isolation.md:71 | — | 선택지 비교 |
| E-16 | Gateway | A1 동시 처리 수 상한: 기본 64, 넘으면 503 + `Retry-After: 2` + `GATEWAY_OVERLOADED`, 운영자 경로 제외 | 기본 64 | — | `InFlightLimitHandlerDecorator`, H1 거절 앞당기기 구현(PR 리뷰 전), 7차 측정 전. 배포·6차 측정 완료 | 설계 | docs/design/infra/single-host-msa-resource-isolation.md:10 | — | 맨 앞 안전망 |
| E-17 | Gateway | 6차에서 거절도 한 건당 Gateway CPU 0.66~0.71ms를 씀 → 거절을 서버 관측·요청 로그·라우트 매칭보다 앞으로 옮김(H1) | 0.66~0.71ms/건 | — | 6차 측정. Boot 4 `HttpWebHandlerAdapter`가 WebFilter 체인 전체를 감쌈 | 배포 측정 | docs/design/infra/single-host-msa-resource-isolation.md:10 | — | 거절도 공짜가 아니다 |
| E-18 | Gateway | A1 첫 값을 500에서 64로 정정: p95를 평균 체류 시간 자리에 넣은 거친 값이었음 | 500 → 64. p95 200ms를 지키는 처리 중 수 1,480/s × 0.040~0.045초 ≈ 60~67 | 1,480×0.040=59.2, ×0.045=66.6(원문 계산). 500이면 2,000/s에서도 거절 안 함 | 5차 단계별 평균 응답으로 재계산(2026-11-11), 처리 중 수 260 초과 단계는 p95 435ms 이상 | 계산값 | docs/design/infra/single-host-msa-resource-isolation.md:85 | — | 설계 수정(500 → 64)과 이유 |
| E-19 | Gateway | 리틀의 법칙 L=λ×W: 1,000/s×p95 15ms ≈ 15, 1,500/s×152ms ≈ 228. 처음 후보 500(생존값 약 1/6) | 15, 228, 500 | 15ms×1,000=15, 152ms×1,500=228(원문) | 초안 값, E-18에서 정정됨 | 계산값 | docs/design/infra/single-host-msa-resource-isolation.md:81 | — | 모순 주의: 초안 500 vs 정정 64 |
| E-20 | Gateway | M3 `cpus` 2.0 Gateway 처리 한도 ≈ 2.0코어 ÷ 0.95~1.07ms | 약 1,870~2,100/s | 2.0÷0.00107=1,869, 2.0÷0.00095=2,105(직접 계산) | 계산값, 미적용 | 계산값 | docs/design/infra/single-host-msa-resource-isolation.md:73 | — | 선택지 비교(미채택) |
| E-21 | 인프라 | M2 `cpu_shares`: alloy 약 25%가 비켜 주면 초당 100~125건 여유(상한) | 250ms/s ÷ 2.0~2.5ms = 100~125건/s | 직접 표기 원문 | 계산값 | 계산값 | docs/design/infra/single-host-msa-resource-isolation.md:72 | — | 이득 상한이 작아 후순위 |
| E-22 | Gateway | C4 폐기 목록 캐시 이득 상한 약 0.26ms(서버 한 건당 2.0ms의 약 13%) | Lettuce 20%×gateway 0.95ms ≈ 0.19ms + redis 14%÷2,000건 ≈ 0.07ms → 약 0.26ms | 0.26÷2.0=13%(원문) | ADR 0005 fail closed와 충돌 가능, 미적용 | 계산값 | docs/design/infra/single-host-msa-resource-isolation.md:96 | — | 보안 결정과의 충돌 |
| E-23 | Gateway | C1·C2 관측 축소는 보류. 0b JFR에서 관측 묶음이 Gateway CPU의 약 24% | 약 24% | — | kangmin 판단: 장애 분석·시연 추적 약해질 위험 | 판단 | docs/design/infra/single-host-msa-resource-isolation.md:11 | — | 거부/보류 판단 |
| E-24 | 인프라 | 추적 샘플링: 측정 0.01, 평시·시연 1.0 → 측정값이 시연 조건을 대표하지 않는다 | 0.01 vs 1.0 | 1.0의 비용 확인 못 함 | `compose.measure.yml`, `compose.deploy.yml` | 설계 | docs/design/infra/single-host-msa-resource-isolation.md:94 | — | 측정과 시연 조건 불일치 |
| E-25 | 인프라 | 11/11 S1에서 좌석 조회 초당 50건에 postgres-reservation 약 210% | 210% | — | 11/11 S1 | 배포 측정 | docs/design/infra/single-host-msa-resource-isolation.md:113 | — | DB 분리 근거 |
| E-26 | 인프라 | 4차와 5차의 같은 2,000/s p95가 157ms vs 435ms로 다르고 원인 확인 못 함 | 157ms vs 435ms | 435÷157=2.77배(직접 계산) | 확인 못 함 | 배포 측정 | docs/design/infra/single-host-msa-resource-isolation.md:82,168 | — | 모순·미해결 |
| E-27 | 인프라 | 상태 저장·관측 등 컨테이너 메모리 상한: kafka-ui 512 + assistant 384 = 896MiB | 896MiB | 계산값 | B3 | 계산값 | docs/design/infra/single-host-msa-resource-isolation.md:104 | — | — |
| E-28 | 인프라 | 한 대 안에서도 서비스 경계를 자원 경계(메모리·CPU·유입량)로 옮길 수 있고 선택은 측정으로 정했다는 발표 장면 | gateway 50~55%, reservation 23%, alloy 7~10%, 컨테이너 밖 13~17% 쌓기 + OOM 3회 | — | 4 vCPU 막대 | 설계(발표안) | docs/design/infra/single-host-msa-resource-isolation.md:127-128 | 4 vCPU 막대 쌓기(설계안만, 파일 없음) | 발표 장면 |
| E-29 | 추첨 | 규모 가정: 회차 15,000석, 추첨 배정 20% = 3,000석, 1인 1–2매 평균 1.5, 당첨자 2,000명, 응모 30,000명(15:1), 예비 1,000명, 자동결제 실패율 5% 약 100건 | 15,000 / 3,000 / 2,000 / 30,000 / 1,000 / 약 100 | 3,000÷1.5=2,000(원문), 30,000÷2,000=15:1(원문) | `[가정]` | 설계 | docs/design/lottery/numbers-and-measurement.md:11-19 | — | 숫자 기준 먼저 |
| E-30 | 추첨 | 시간 기준: 마감 직전 마지막 1분에 응모 20%(6,000건) → 초당 100건, 추첨 실행 상한 10초, 자동결제 추첨 후 10분 내 95%, 승계 마감 30분 | 100건/s, 10초, 10분·95%, 30분 | 6,000÷60=100(원문) | `[가정]` | 설계 | docs/design/lottery/numbers-and-measurement.md:23-30 | — | 목표 숫자 |
| E-31 | 추첨 | 일반 결제 `pg-approve` 벌크헤드: 동시 24–30(지금 30), 대기 0. 10/31~11/10 기준선은 동시 10 | 10 → 30 | 3.0배(직접 계산 30÷10) | ADR 0051 결정 3, ISE-246 | 설계 | docs/design/lottery/numbers-and-measurement.md:37 | — | 허가 수 조정 |
| E-32 | 추첨 | 자동결제 `pg-billing` 벌크헤드 동시 3, 대기 0, `payment.command` 파티션 3·소비자 3 | 3 / 3 / 3 | — | ADR 0051 결정 1·2(11/12 채택, PR `#521`) | 설계 | docs/design/lottery/numbers-and-measurement.md:38 | — | 전용 허가 분리 |
| E-33 | 추첨 | 자동결제 2,000건 처리 계산: 소비자 1개 초당 3.17 × 3 = 9.51 → 약 3.7분 | 3.17 → 9.51/s, 약 3.7분 | 3.17×3=9.51(원문) | ADR 0051 기대 효과, 계산값 | 계산값 | docs/design/lottery/numbers-and-measurement.md:39 | — | 예측 |
| E-34 | 추첨 | 이전 기준선 가정: 공유 동시 10 → 초당 약 33건, 2,000건 약 1분. 실측은 소비자 1개라 초당 3.17, 약 10.7분 | 약 1분 → 약 10.7분(가정 vs 실측) | 10.7÷1≈10.7배(직접 계산, 가정 대비) | D6 10/31, ADR 0039 → ADR 0051로 대체 | 설계 vs 실측(모순 아님, 가정 정정) | docs/design/lottery/numbers-and-measurement.md:40-42 | — | 가정이 깨진 순간 |
| E-35 | 추첨 | 3-1: 추첨 실행 8회 시간 4,508·515·481·4,600·504·593·4,440·446ms, 모두 상한 10초 안, 약 0.5초/약 4.5초 두 갈래(원인 확인 못 함) | 최악 4,600ms | 4,600÷10,000=46%(직접 계산, 상한의 절반 이하) | 로컬 Windows PC, PostgreSQL 17 컨테이너(tmpfs), 응모 30,000행, 추첨석 2,000, 예비 1,000, `LotteryDrawServiceTest`, 2026-11-02, ISE-87 | 로컬 측정 | docs/design/lottery/numbers-and-measurement.md:58-61 | — | 한 트랜잭션(D8 A안) 유지 판단 |
| E-36 | 추첨 | 3-2 로컬 L1 응모 몰림 초당 100 → 800: 503 4,239(400단계), 44,943(800단계), 400단계 p95 35.2ms, 800단계 p95 478.6ms | 100: p50 7.7/p95 16.6/p99 41.2ms; 200: 6.9/20.2/56.4; 400: 7.6/35.2/118.7(503 4,239); 800: 58.9/478.6/581.3(503 44,943) | 800단계 p95 ÷ 100단계 p95 = 478.6÷16.6=28.8배(직접 계산) | 로컬 compose, 이미지 `b6d3ffd`, k6와 서비스 같은 PC, 앞 5초 제외, 2026-11-06 | 로컬 측정 | docs/design/lottery/numbers-and-measurement.md:81-89 | — | 로컬 vs 배포 대비 |
| E-37 | 추첨 | 3-2 로컬 L1 전체: 요청 100,384건, 201 50,860건, 503 49,519건, 시작 못 한 반복 4건. 응모 행 48,553 = 고유 사용자 48,553 | 100,384 / 50,860 / 49,519 / 4 | — | 위와 같음 | 로컬 측정 | docs/design/lottery/numbers-and-measurement.md:90-91 | — | — |
| E-38 | 추첨 | 로컬 L1에서 503은 시작 144초 뒤부터. 측정 직후 reservation-service 메모리 509.7MiB / 512MiB, Zipkin 구간 1,145개 버림. 원인 여부 확인 못 함 | 509.7/512MiB, 1,145개 | 509.7÷512=99.6%(직접 계산) | 로컬 | 로컬 측정 | docs/design/lottery/numbers-and-measurement.md:92 | — | 메모리(2절 참조) |
| E-39 | 추첨 | 3-2 로컬 L2-a(응모 2,000, 추첨석 600, 예비 100, PG 300ms): 결제 396건, 추첨 뒤 첫 완료 11.1초/마지막 138.6초, p95 132.1초, 처리량 초당 3.10건 | 396건, 11.1/138.6초, 3.10/s | 2,000건 외삽 약 10.4분(원문, 외삽값) | 로컬 | 로컬 측정 | docs/design/lottery/numbers-and-measurement.md:67-78 | — | 동시성 1 가설 첫 단서 |
| E-40 | 추첨 | `payment.command` 소비자는 키 기반 순서 때문에 동시성 1. PG 300ms면 계산 상한 초당 약 3.3건 | 약 3.3건/s | 1÷0.3≈3.3(원문 계산) | 코드 확인(`PaymentCommandKafkaConfiguration`, `ConsumerContainerFactories`) | 계산값 | docs/design/lottery/numbers-and-measurement.md:76 | — | 병목 가설 |
| E-41 | 추첨 | 배포 서버 환경: `m7i.xlarge`(4 vCPU, 15.7GiB, Ubuntu 24.04, 디스크 40GB), 시드니 `ap-southeast-2`; 발생기 `c6i.xlarge`(4 vCPU, 8GiB), 같은 VPC 사설 통신 | m7i.xlarge / c6i.xlarge | — | 11/5에 `m7i-flex.xlarge`에서 변경 | 배포 측정 조건 | docs/design/lottery/numbers-and-measurement.md:102-103 | — | 측정 환경 |
| E-42 | 추첨 | Hikari 풀 reservation 5, user 5, payment 20(연결 대기 1초); Redis 명령 timeout 100ms; Gateway response-timeout 3s; 샘플링 0.01; Tomcat 스레드 설정 없음(실제값 확인 못 함) | 5/5/20, 100ms, 3s, 0.01 | — | 측정 환경 기록표 | 측정 조건 | docs/design/lottery/numbers-and-measurement.md:106-111 | — | — |
| E-43 | 추첨 | 3-3 L2-a 1회: 결제 1,996건, 600초 안 93.1%(1,858/1,996), p95 약 612초, 미달 | 1,996 / 93.1% / 약 612초 | 95% 기준 1,897건에 39건 모자람(원문) | 배포, 2026-11-10 UTC, 응모 30,000, 추첨석 3,000, 예비 1,000, PG 300ms, LOTTERY 5 | 배포 측정(p95는 계산값) | docs/design/lottery/numbers-and-measurement.md:138,168 | — | 기준 미달 1 |
| E-44 | 추첨 | 3-3 L2-a 2회: 2,023건, 92.4%(1,870/2,023), p95 약 616초, 미달 | 2,023 / 92.4% / 약 616초 | 기준 1,922건에 52건 모자람(원문) | 같음, LOTTERY 6 | 배포 측정 | docs/design/lottery/numbers-and-measurement.md:139,195 | — | — |
| E-45 | 추첨 | 3-3 L2-a 3회: 2,019건, 93.4%(1,885/2,019), p95 약 610초, 미달 | 2,019 / 93.4% / 약 610초 | 기준 1,919건에 34건 모자람(원문) | 같음, LOTTERY 7 | 배포 측정 | docs/design/lottery/numbers-and-measurement.md:140,215 | — | 세 번 모두 같은 모양 |
| E-46 | 추첨 | L2-a 처리 속도는 세 회 모두 초당 약 3.2건으로 일정, 첫 결제 추첨 약 10~13초 뒤, 전체 약 10.5분 | 3.17·3.17·3.15 / 3.21·3.19·3.11·3.18 / 3.16~3.23 | 2,000÷3.2≈625초≈10.4분(원문 약 10.5분) | 계산값 | 계산값 | docs/design/lottery/numbers-and-measurement.md:155,182,202,217-220 | — | 구조적으로 10분 초과 |
| E-47 | 추첨 | L2-a 1회 판정 근거 버킷: le 458.13→1,410, 549.76→1,700, 600.0→1,858, 641.38→1,989, 733.01→1,996 | 1,410/1,700/1,858/1,989/1,996 | — | Prometheus 누적 | 배포 측정 | docs/design/lottery/numbers-and-measurement.md:159-166 | — | 그래프 데이터 |
| E-48 | 추첨 | L2-a 1회 setup: 빌링키 30,000명 발급 77초, k6 `http_req_failed` 0/60,001, p50 8.47ms·p95 28.35ms·p99 54.15ms·최대 396.77ms | 77초, 28.35ms | — | setup 요청 값, 판정에 안 씀 | 배포 측정 | docs/design/lottery/numbers-and-measurement.md:152 | — | — |
| E-49 | 추첨 | L2-a 1회 DB 최종: PAID 1,996, FAILED 0, WAITLISTED 1,000, LOST 27,004(합 30,000) | 1,996 / 0 / 1,000 / 27,004 | 합 30,000 | — | 배포 측정 | docs/design/lottery/numbers-and-measurement.md:153 | — | — |
| E-50 | 추첨 | L2-b 1회(PAY_RATE 20): 자동결제 2,007건, 600초 안 95.6%(1,919/2,007), p95 약 596초, 일반 결제 503 0.05%(6/13,201), 합격 | 2,007 / 95.6% / 596초 / 6건 | 기준 1,907건 대비 12건 초과(원문) | 배포 11/10, LOTTERY 8, 일반 결제 13,201건(승인 13,195), p50 307.0ms·p95 311.6ms·p99 316.7ms·최대 645.9ms | 배포 측정 | docs/design/lottery/numbers-and-measurement.md:141,226-240 | — | 결제 간섭 합격 |
| E-51 | 추첨 | L2-b 2회(PAY_RATE 30): 자동결제 1,997건, 92.9%(1,856/1,997), p95 약 613초, 일반 결제 503 9.5%(1,424/15,000), 미달 | 1,997 / 92.9% / 613초 / 1,424건 | 기준 1,898건에 42건 모자람(원문) | 배포 11/10, LOTTERY 9, 승인 13,576, p50 307.2/p95 310.7/p99 314.1/최대 337.9ms. 분당 503 약 9.4~9.9% | 배포 측정 | docs/design/lottery/numbers-and-measurement.md:142,246-261 | — | 겹치면 503 폭증(0.05%→9.5%) |
| E-52 | 결제 | 일반 결제 `pg-approve` 허가 10 ÷ PG 300ms 상한 초당 약 33건, 자동결제가 허가 1개를 쓰면 일반 몫 9 → 초당 30(실측 p50 307ms로 약 29건) | 33 → 30 → 29 | 10÷0.3=33.3, 9÷0.3=30, 9÷0.307=29.3(직접 계산) | 11/10 가설(이후 B에서 확인) | 계산값 | docs/design/lottery/numbers-and-measurement.md:265 | — | 가설 |
| E-53 | 추첨 | 3-3 L1(응모 몰림, 배포): 100→200→400→800/s 단계 모두 503 0건, p95 5.0/4.2/4.9/15.8ms, 요청 100,374건 실패 0, 합격 | 단계 p95 5.0ms(100/s) … 15.8ms(800/s). entry_duration p95 10.58ms | 로컬 800/s p95 478.6ms vs 배포 15.8ms = 30.3배 차이(직접 계산 478.6÷15.8) | 배포, 단계마다 65초(앞 5초 제외), LOTTERY 10 | 배포 측정(배수는 직접 계산, 서로 환경이 다름) | docs/design/lottery/numbers-and-measurement.md:143,272-283 | — | 로컬 vs 배포 대비 |
| E-54 | 추첨 | L1 배포 단계별 초당·건수: 6,313건/105.2/s, 12,608건/210.1/s, 25,136건/418.9/s, 50,410건/840.2/s | 105.2/210.1/418.9/840.2 req/s | — | 위와 같음 | 배포 측정 | docs/design/lottery/numbers-and-measurement.md:276-281 | — | — |
| E-55 | 추첨 | L1 응모 행 95,622 = 고유 사용자 95,622(중복 0), 요청과의 차이 4,752건은 연타·멱등 처리 | 95,622 / 4,752 | 100,374−95,622=4,752(원문 계산값) | 배포 | 배포 측정 | docs/design/lottery/numbers-and-measurement.md:284 | — | 멱등성 증명 |
| E-56 | 추첨 | L1 직후 `docker stats`: reservation-service 699MiB/1.125GiB(약 61%), gateway 497MiB/768MiB(약 65%), redis 36MiB, postgres-reservation 183MiB | 699 / 497 / 36 / 183MiB | 699÷1,152≈61%, 497÷768≈65%(비율 원문 계산값) | 측정 직후 | 배포 측정 | docs/design/lottery/numbers-and-measurement.md:285 | — | 메모리(2절 참조) |
| E-57 | 추첨 | L1 당첨자는 빌링키 없어 추첨 뒤 2,998건이 FAILED `BILLING_KEY_MISSING`, 추첨 +97초에 정리 완료(의도한 결과) | 2,998건, 97초 | — | 응모만 재는 시나리오 | 배포 측정 | docs/design/lottery/numbers-and-measurement.md:286 | — | — |
| E-58 | 추첨 | 3-4 A 자동결제 PG 지연 스윕: 300ms → 구간 속도 약 3.17/s(2,019건/642초), 150ms → 6.27/s(2,006건/344초), 50ms → 15.9/s(1,998건/133초) | 3.17 → 6.27 → 15.9/s. 한 건 주기 315.5 / 159.6 / 62.9ms | 300ms→50ms 지연 6배 감소에 속도 15.9÷3.17=5.0배(직접 계산). 주기−PG 지연 15.5/9.6/12.9ms(원문) | 배포 11/10 오후, LOTTERY 7/11/12, 측정 모드 on `0e77953`, 발생기 `1abd8ab` | 배포 측정(속도는 계산값) | docs/design/lottery/numbers-and-measurement.md:297-309 | — | 속도가 PG 지연에 반비례 |
| E-59 | 추첨 | 150ms 구간에서 payment-service CPU 최대 17%, `pg-approve` 남은 자리 최소 9/10, DB 풀 대기 0, Kafka lag 최대 1,106 → 소비자 1개 직렬 대기가 병목으로 확정 | 17%, 9/10, 0, 1,106 | — | `ConsumerContainerFactories.java:26` `setConcurrency(1)` | 배포 측정(결론 확정 표기) | docs/design/lottery/numbers-and-measurement.md:310-311 | — | 병목 확정 장면(CPU·허가·DB는 남는데 lag만 쌓임) |
| E-60 | 추첨 | 개선 예측: 파티션·소비자 N=1→2→3이면 3.17 → 6.35 → 9.52/s, 2,000건 약 10.5 → 5.3 → 3.5분. N=3이면 일반 결제 몫 초당 약 22.6 | 3.17/6.35/9.52, 10.5/5.3/3.5분, 22.6/s | 7÷0.31=22.6 | 계산값, 측정하지 않음 | 계산값 | docs/design/lottery/numbers-and-measurement.md:313-321 | — | 개선 예측 → 3-5에서 실측으로 확인 |
| E-61 | 결제 | 3-4 B 일반 결제 기준선(ENTRANTS=10, PG 300ms, 단계마다 120초): 20/s 503 0.13%, 25/s 0%, 30/s 0.31%, 35/s 9.4%(승인 31.7/s), 40/s 23.2%(승인 30.7/s). p95 313~315ms | 목표 20/25/30/35/40 → 승인 20.0/25.0/29.9/31.7/30.7/s | 계산상한 32.3/s(10÷0.31), 실측 상한 30.7~31.7/s | 배포, `SALE_SCHEDULE_ID` 60·61, 40/s에서 `pg-approve` 남은 자리 0·payment-service CPU 4% | 배포 측정(비율은 계산값) | docs/design/lottery/numbers-and-measurement.md:323-335 | — | 한도는 CPU가 아니라 허가 수 |
| E-62 | 결제 | L2-b 2회(초당 30, 503 9.5%) 원인은 자동결제 소비자가 허가 1개를 쥐어 일반 몫이 9÷0.31 ≈ 29.0/s로 줄어든 경합. 자동결제 없이 초당 30은 503 0.31% | 9.5% vs 0.31% | 9.5÷0.31≈30.6배(직접 계산) | 11/10 | 계산값/배포 측정 | docs/design/lottery/numbers-and-measurement.md:336 | — | 가설 확정 |
| E-63 | Gateway | C1 L1 콜드 시작 800→3,200/s: `auth_unavailable` 비율 800/s 55%, 1,600/s 97%, 2,400/s 99%, 3,200/s 99%. 차단기가 시작 15초 뒤 열림, reservation CPU 2%·Postgres 0.3% | 55%/97%/99%/99% | — | 배포, 단계마다 65초, LOTTERY 18, 발생기 CPU 최대 60%, 서버 CPU 최대 91% | 배포 측정 | docs/design/lottery/numbers-and-measurement.md:338-349 | — | 서비스에 닿기 전 Gateway에서 거절 |
| E-64 | Gateway | C2 웜업 뒤 세밀 단계: 100~400/s 거절 0% p95 5~6ms, 800/s 0% 17.4ms, 1000/s 19% 46.5ms, 1200/s 83%, 1400~2000/s 77~99%. 차단기 첫 열림 03:35:45 | 위 값 | — | 배포, 단계 30초, LOTTERY 19. Redis 조회 평균 0.3ms → 열리기 직전 1.9ms, Redis CPU 8%. 1000 단계 gateway 119%/reservation 85%/Postgres 57% | 배포 측정 | docs/design/lottery/numbers-and-measurement.md:352-364 | — | 운영 설정 한도 판정 근거(약 800/s 통과, 1,000/s부터 차단) |
| E-65 | Gateway | 차단기 설정: 창 20건, 최소 호출 20, 실패율 50%, 느린 호출 50ms·50%, 열림 10초, 반열림 5건 → 20건 창은 초당 1,000건에서 0.02초 분량, 순간 지연 한 번에 10초 전체 거절(진동) | 창 20, 50ms, 10초 | 20÷1,000=0.02초(원문 계산값) | `config-repo/gateway.yml:58-65`, 해석은 추정 | 설계+추정 | docs/design/lottery/numbers-and-measurement.md:365-366 | — | 차단기 오탐 → 설정 조정 |
| E-66 | Gateway | C3(차단기 완화 시간 창 10초·최소 1,000건·느린 호출 1초): 1200/s 거절 4.8%(1,796/37,452) p50 84ms p95 367ms, 1600/s 달성 1,362/s(85.1%) p95 1.7s(p99 8.8s), 2000~3200 붕괴 | 거절 83%(C2) → 4.8%(C3) at 1200/s | 1,796÷(35,656+1,796)=4.8%, 1,362÷1,600=85.1%(원문 계산값) | 배포, 인증 벌크헤드 25(기본값), Hikari 5. 1200단계 벌크헤드 남은 자리 0, DB 풀 대기 최대 약 195. Gateway CPU 3코어대, GC 87ms/s, 서버 vmstat 100%, 발생기 79% | 배포 측정(임시 덮어쓰기) | docs/design/lottery/numbers-and-measurement.md:377,381-393 | — | 병목이 차단기 → 벌크헤드·DB 풀로 이동 |
| E-67 | Gateway | D(완화 + 벌크헤드 100 + Hikari 20): 달성 841/1,047/1,253/1,441/1,565/1,603(정체), 1200/s 거절 0.07% p95 238ms, 1400/s 1.1% p95 597ms, 1600/s 7% p95 1.1s, 2000/s 8% 정체 | p95 300ms 이하 한도 약 800 → 약 1,200/s | 1,200÷800=1.5배(원문, 계산값) | 배포 11/10, LOTTERY 21, 차단기 열리지 않음. 1200~1600 gateway 135~170%/reservation 93~105%/Postgres 70~74%, 서버 vmstat 100% | 배포 측정(설정 임시 덮어쓰기) | docs/design/lottery/numbers-and-measurement.md:378,395-410 | — | 설정만으로 한도 1.5배, 다음 한계는 서버 CPU(추정) |
| E-68 | Gateway | E(측정 기반 차단기: 시간 창 10초, 최소 500건, 느린 호출 100ms·80%, 열림 5초, 반열림 20건 + 벌크헤드 100 + Hikari 20): 1000/s 1,046(104.6%) p95 132ms, 1200/s 1,251 거절 0.5% p95 339ms, 1300/s 1,360 0.7% 456ms, 1400/s 1,442 1.9% 789ms. 차단기 끝까지 안 열림 | p95 300ms 기준 한도 약 1,000/s | — | 배포, LOTTERY 22. D(1200에서 238ms)보다 낮은 이유는 확인 못 함 | 배포 측정 | docs/design/lottery/numbers-and-measurement.md:379,412-424 | — | D vs E 편차 해석 불가 |
| E-69 | 인프라 | Q1 대기열 측정(ISE-202)은 차단기 완화 없이 운영 설정으로 쟀고 웜업 뒤 1,000/s 통과·2,000/s 실패, 벌크헤드 25와 100이 같았다 | 1,000/s 통과, 2,000/s 실패 | — | 11/10, 판단 기록 youngeun | 배포 측정 | docs/design/lottery/numbers-and-measurement.md:372 | — | 운영 설정으로만 한도 판정한다는 원칙 |
| E-70 | 추첨 | 부수 관찰: 추첨 낙첨·실패 처리가 2~3분 동안 Postgres CPU 200%대. 추첨 실행 시간 최대 4.44초 | 200%대, 2~3분, 4.44초 | — | `lottery_draw_duration_seconds_max` | 배포 측정 | docs/design/lottery/numbers-and-measurement.md:427-428 | — | 추첨 DB 부하 |
| E-71 | 추첨 | 병목 정리 표: 자동결제(소비자 1개)·일반 결제(허가 10÷0.31=32.3/s)·Gateway 인증 차단기·인증 벌크헤드(25)·reservation 풀(5, 대기 195)·서버 CPU(vmstat 100%, 정체 1,600/s)·추첨 대량 처리 | 위 숫자 | — | 3-4 요약 | 판단/정리 | docs/design/lottery/numbers-and-measurement.md:432-440 | — | 병목 표 한 장 |
| E-72 | 추첨 | 3-5 ADR 0051 재측정: 10분 안 100%(1·2회), 결제 완료 p95 약 210초·205초, 초당 약 9.4건, FAILED 0, 당첨 1,992·1,993 | 10분 안 93.1·92.4·93.4% → 100%, p95 약 612·616·610초 → 약 210·205초, 초당 약 3.2 → 약 9.4건 | 9.4÷3.2=2.9배(원문은 11/10의 약 2.95배, 직접 3.17 기준 9.4/3.17=2.97) | 배포, main `43015207`, 11/13(KST 10:33:54·10:52:20 추첨), LOTTERY 26/27, 응모 30,000·추첨석 3,000·예비 1,000·PG 300ms, 측정 모드 샘플링 0.01, Gateway 상한 64 | 배포 측정(p95는 버킷 206~229초 선형 보간 계산값) | docs/design/lottery/numbers-and-measurement.md:443-455 | — | 전/후 핵심: 미달 → 합격(ADR 0051 효과 실측) |
| E-73 | 추첨 | 3-5 처리 속도 산정: 1회 10:34:37~10:37:08 151초에 1,425건 | 1,425건/151초 | 1,425÷151=9.44건/s(직접 계산), 1-3절 계산값 9.51과 부합 | 계산값 | 계산값 | docs/design/lottery/numbers-and-measurement.md:455 | — | 예측 vs 실측 |
| E-74 | 결제 | 자동결제 중 `pg-billing` 남은 자리 3 → 0 → 3, 일반 `pg-approve`는 내내 30. DLQ 0건 | 3→0→3, 30 | — | 3-5 | 배포 측정 | docs/design/lottery/numbers-and-measurement.md:457-458 | — | 허가 분리가 간섭을 끊음 |
| E-75 | 결제 | 3-6 L2-b(ADR 0051 뒤, 11/15): 일반 결제 15,000건 모두 승인, 503 0건(11/10 같은 조건 9.5%), p95 319.8ms, 자동결제 PAID 2,008·100%, 약 215초, 합격 | 503 9.5% → 0% | 1,424건 → 0건 | 배포 main `e109c11c`, 측정 모드, Gateway 상한 64, PG 300ms, `PAY_RATE` 30·500초, LOTTERY 28/128, 추첨 19:46:57 KST. `pg-billing` 남은 자리 최소 0, `pg-approve` 최소 20(일반 최대 10 사용), 결제 DB APPROVED 17,008 = 15,000+2,008, dead_letters 0 | 배포 측정(p95 약 215초는 선형 보간 계산값) | docs/design/lottery/numbers-and-measurement.md:465-471 | — | 간섭 해소 전/후(503 9.5% → 0) |
| E-76 | 추첨 | 3-6 L3(추첨이 일반 예매에 주는 간섭): 선점 p95 추첨 전 11.06ms → 추첨 뒤 120초 21.0ms(+90%) → 그 뒤 15.7ms, 오류 0, 기준 20% 미달(넘김) | 11.06 → 21.0ms → 15.7ms | +90%(원문; 21.0÷11.06=1.90, 직접 검산) | 배포 main `e109c11c`, `USER_ID_BASE` 14100000, 선점 `RATE` 30, 300초, 추첨 전 60초·추첨 뒤 120초 구간, 추첨 20:01:49, LOTTERY 29/130, 자동결제 PAID 1,990·100%·206~229초 버킷 | 배포 측정(1회) | docs/design/lottery/numbers-and-measurement.md:468,472-473 | — | lottery 분리 근거(SA 7-1), 팀 판단으로 넘김 |
| E-77 | 추첨 | L3 선점 결과: 추첨 전 201 1,732·409 221, 추첨 뒤 120초 201 3,267·409 2,475, 그 뒤 201 2,397·409 5,714 | 1,732/221 → 3,267/2,475 → 2,397/5,714 | — | 그 뒤 409 증가는 판매 회차 좌석 감소 탓으로 봄(확인 못 함) | 배포 측정 | docs/design/lottery/numbers-and-measurement.md:472 | — | — |
| E-78 | 추첨 | 관측 지표: 추첨·배정·자동결제·불변식·승계·종결·작업·진행 상태 영역별 Prometheus 이름과 라벨 정의, 경보 규칙 6개 promtool 통과(실제 Slack 알림은 확인 못 함) | 경보 규칙 6개 | — | 로컬 compose 2026-11-05 확인, 배포 확인 못 함 | 설계+로컬 확인 | docs/design/lottery/numbers-and-measurement.md:477-501 | — | 관측 가능성 |
| E-79 | 추첨 | 서버 쪽 API p95는 볼 수 없다. `http_server_requests_seconds_bucket` 미활성이라 응모 API p95는 k6 결과로 판정 | — | — | — | 설계(한계) | docs/design/lottery/numbers-and-measurement.md:496 | — | 한계 |
| E-80 | 기타 | Synthetic Data: LLM이 분포(JSON)와 표본 40행을 내고 3,000명은 `generate.py`가 시드 20261113으로 만든다. 같은 시드 두 번 생성 바이트 같음 | 3,000명, 표본 40행, 시드 20261113 | — | 2026-11-13 검증 | 시연/설계(로컬 수정본) | docs/design/infra/synthetic-data.md:22,47 | — | 재현 가능한 가상 사용자 |
| E-81 | 기타 | LLM 페르소나 8개 비율: office_pc_fan 28, pair_buyer 18, refresh_spammer 12, mobile_commuter 12, late_casual 10, vip_hunter 8, multi_device 7, browse_only 5 | 합 100%(28+18+12+12+10+8+7+5) | 합계 직접 계산 100 | 모수는 모두 LLM 추정 | 설계(로컬) | docs/design/infra/synthetic-data.md:26-35 | — | 가상 사용자 구성 |
| E-82 | 기타 | 생성 3,000명 성질: VIP 노리는 사용자 1,265명(42.2%, LLM 계산 약 42%), 진입 요청÷사용자 1.59(LLM 약 1.6), 오픈 전 접속 2,500(83.3%), 오픈 3초 안 진입 2,307(76.9%), 진입 안 함 169, 좌석 1·2·3·4석 999·1,613·84·304, 결제 안 하고 떠남 328(10.9%) | 위 값 | 999+1,613+84+304=3,000(직접 검산) | 2026-11-13 생성 | 계산값(로컬) | docs/design/infra/synthetic-data.md:51-60 | — | LLM 예측 vs 생성 데이터 |
| E-83 | 기타 | 행동 축 6개(3구간 → 3^6=729칸) 중 202칸만 채워짐, 칸당 평균 약 4명 | 202/729 | 202÷729=27.7%(직접 계산), 3,000÷729=4.1 | 2026-11-13 | 계산값(로컬) | docs/design/infra/synthetic-data.md:79,107 | — | 분석 단위 한계 |
| E-84 | 기타 | 축별 인원(낮음/중간/높음): 도착 480/749/1,771, 진입 1,151/1,352/497, 좌석 욕심 1,081/952/967, 결정 속도 1,072/1,823/105, 집착도 444/669/1,887, 구매 의지 72/353/2,575 | 합 각 3,000 | — | 2026-11-13 | 계산값(로컬) | docs/design/infra/synthetic-data.md:98-105 | — | — |
| E-85 | 기타 | 축 열을 넣어도 기존 14열은 넣기 전 CSV와 바이트 단위로 같다(난수 안 씀) | 14열 | — | 2026-11-13 확인 | 시연(로컬) | docs/design/infra/synthetic-data.md:93 | — | 결정적 재현성 |
| E-86 | 기타 | 혼합 생성(원형 8개 중심점+잡음) 검토: 잡음 σ 0.15에서 채워진 칸 200칸으로 v1 점수화와 비슷 → 점수화만 하기로 결정 | 200칸 vs 202칸 | — | 시뮬레이션, 판단 기록 kangmin 2026-11-13 | 판단(로컬) | docs/design/infra/synthetic-data.md:109 | — | 거부 판단: 부족한 것은 다양성이 아니라 분석 관점 |
| E-87 | 기타 | 비교 측정 계획: 배포 서버 `m7i.xlarge`, 발생기 `c6i.xlarge`, 3,000명·VU 300, 오픈 전 600초·오픈 뒤 900초, 회차마다 새 15,000석, Mock PG 300ms | 3,000/300/600초/900초/15,000석/300ms | — | 5절 계획 | 설계(로컬) | docs/design/infra/synthetic-data.md:113-120 | — | 비교 조건 |
| E-88 | 기타 | 측정 전 고정 판정 기준: 발생기 CPU 320%(=4 vCPU의 80%) 이하·메모리 90% 이하, `action_lateness` p95 1,000ms 이하, A0 정확히 1,000건·409 0건, 중복 판매 0, 확정=SOLD=승인, 오류율 1% 미만 | 320%, 90%, 1,000ms, 1,000건, 1% | — | 5-0절, 측정 전 고정 | 설계(로컬) | docs/design/infra/synthetic-data.md:134-145 | — | 결과 보고 기준 고르지 않기 |
| E-89 | 기타 | 처리량에 고정 목표를 두지 않는다(SA 3장, 결정 #47: 고정 200/sec 대신 전·후 비교와 포화점) | 200/sec(폐기) | — | — | 설계(로컬) | docs/design/infra/synthetic-data.md:147 | — | 결정 근거 |
| E-90 | 기타 | 첫 O1 균일 실행(회차 120) 버림: 결제 2,020건 모두 400이라 확정 0건(발생기 코드에 `pgProvider` 없어 배포 결제 서버가 결제창 값 요구). 패치 뒤 확인 실행(회차 121, 60명) 확정 50/50 | 2,020건 400, 확정 0 | — | 배포 2026-11-15 17:59~18:24 KST | 배포 측정(버린 실행)(로컬) | docs/design/infra/synthetic-data.md:193 | — | 트러블슈팅: 측정 도구 코드 누락 |
| E-91 | 기타 | 6-1 환경: 배포 서버 `m7i.xlarge`(4 vCPU, 15GiB), main `e109c11c`, 발생기 EC2 4 vCPU·7.6GiB, `grafana/k6:1.0.0`, 코드 `88f14d86`+`pgProvider: 'MOCK'` 패치, 사용자 3,000명·VU 300, 오픈 전 약 595초+오픈 뒤 900초, Mock PG 300ms, 선점 만료 스케줄러 켜짐 | — | — | 2026-11-15, ISE-264 | 측정 조건(로컬) | docs/design/infra/synthetic-data.md:191-192 | — | — |
| E-92 | 기타 | 6-1 유효성: 발생기 CPU 최대 168.3%(균일)·146.0%(Synthetic), 메모리 6.3%·6.1%, `action_lateness` p95 0ms(p99 81ms / 77ms), 두 O1 사이 재시작 없음 | 168.3/146.0%, 6.3/6.1% | — | 같음 | 배포 측정(로컬) | docs/design/infra/synthetic-data.md:198-200 | — | 측정 신뢰도 |
| E-93 | 기타 | 6-2 정합성: 중복 판매 0·0. 균일 확정 1,973건 = 2,908석 = 승인 1,973, Synthetic 확정 2,547건 = 4,913석 = 승인 2,547. 균일 `error` 291, Synthetic 8, A0 오류율 어김(성공 48.2%·41.3%·50.6%) | 291 vs 8 | 291÷8≈36.4배(직접 계산) | 위와 같음 | 배포 측정(로컬) | docs/design/infra/synthetic-data.md:206-210 | — | 정합성 통과, 기준 일부 어김 |
| E-94 | Gateway | 6-2 503 전부가 Gateway 맨 앞 동시 처리 상한 64와 auth-redis bulkhead 거절: 균일 k6 503 1,150 = `gateway_in_flight_rejected_total` 증가 1,150, Synthetic 1,603 = 상한 거절 1,555 + bulkhead_full 48, A0 1,599 = 상한 1,598 + bulkhead 1. 서버 오류(500)·타임아웃 없음 | 1,150 / 1,603 / 1,599 | 1,555+48=1,603, 1,598+1=1,599(원문, 검산 일치) | 배포 2026-11-15 | 배포 측정(로컬) | docs/design/infra/synthetic-data.md:212-216 | — | 건수가 지표와 정확히 맞는 장면(안전망이 일했다) |
| E-95 | 대기열 | 핵심 비교(균일 → Synthetic): 확정 사용자 1,973(65.8%) → 2,547(84.9%), 선점 409 비율 48.1% → 11.7%, 선점 p95 192.4 → 36.9ms, 좌석 조회 p95 222.9 → 43.4ms, 결제 p95 421.3 → 325.7ms, 확정 대기 p95 3.9s → 1.0s, `queue_wait` p95 49.0 → 41.1s, `queue_enter` 초당 최대 353 → 2,792, 상한 거절 1,150 → 1,603 | 위 값 | `queue_enter` 2,792÷353=7.9배(원문 "8배 가까이"), 409 48.1÷11.7=4.1배, 선점 p95 192.4÷36.9=5.2배(직접 계산) | 배포 2026-11-15, 회차 122(균일)·123(Synthetic), O1 각 1회 | 배포 측정(로컬) | docs/design/infra/synthetic-data.md:220-228,249-250 | — | 같은 서버, 사용자 행동만 다름: 균일이 오히려 더 가혹 |
| E-96 | 좌석 | 같은 상한이라도 어느 단계에서 거절되는지에 따라 사용자 결과가 다름: 균일은 좌석 조회·선점 503이 바로 `error`(291), Synthetic은 대기열 진입 503이 `retryLater`로 다시 들어와 결과에 안 남음(8). 첫 1분 선점 409 균일 1,906건 vs Synthetic 364건 | 1,906 vs 364 | 1,906÷364=5.2배(직접 계산) | 추정 | 판단/추정(로컬) | docs/design/infra/synthetic-data.md:249-250 | — | 해석: 거절 위치가 결과를 가른다 |
| E-97 | 기타 | A0 3회(동기 선점 100 VU 동시): 성공 482·413·506건(48.2·41.3·50.6%), 409 0, 평균 52.2/42.3/50.6ms, p95 159.5/142.1/153.7ms, 처리량 992.1/1162.8/1019.4건/초, 모두 불합격. 처리량은 상한 64가 걸린 상태의 값 | 992.1/1,162.8/1,019.4건/s | — | 측정 창 1.01/0.86/0.98초, 대표 103600 | 배포 측정(로컬) | docs/design/infra/synthetic-data.md:232-236,251 | — | 처리량이 아닌 안전망 값 |
| E-98 | 기타 | LLM 예측 4개 판정: 진입 정점 2,792>353 맞음, VIP 409 19.5%(295/1,512)>11.7% 맞음, 5분 뒤 두 번째 파도 55(27+28)>30 조건은 맞으나 1분당으로는 파도 안 보임, 상세 조회 정점 263>231 맞음 | 263 vs 231(`concert_detail` 초당 최대) | 263÷231=1.14배(직접 계산) | 배포 2026-11-15 | 배포 측정(로컬) | docs/design/infra/synthetic-data.md:240-245 | — | AI 예측 검증: 3/4 적중, 1개 반증 |
| E-99 | 기타 | 대기 취소·이탈 판정 대가 0: 3,000명이면 대기열이 약 45초면 비어 `queue_patience_s`(최소 30초)가 작동 안 함. 이탈자 0명(폴링 10초 더해도 2명), 입장 10/s로 낮춰 계산해도 이탈 약 137명·마지막 사람 추가 대기 최대 약 14초, 떠난 사람 한 명당 뒤 사람 입장 0.02초(1÷50) 지연 | 0명/2명/137명/14초/0.02초 | 1÷50=0.02(원문) | 2026-11-14, ISE-268 댓글, 계산값 | 계산값(로컬) | docs/design/infra/synthetic-data.md:258 | — | 별도 실행을 계산으로 갈음한 판단 |
| E-100 | 기타 | 한계: 비율·분포 모수는 LLM 추정, O1은 각 1회라 편차 모름, 매크로 미포함, EC2 한 대라 CPU 공유 | — | — | — | 판단(한계)(로컬) | docs/design/infra/synthetic-data.md:255-261 | — | 한계 명시 |
| E-101 | 기타 | 이력: ISE-28 Synthetic Data 1차본 11/11 취소(대기열 담당 판단: 인기 회차 쏠림은 회차별 Redis 키가 따로라 Q1~Q3와 결과 같음, 좌석 구역 쏠림은 좌석 담당 몫), 발제 필수 산출물이 저장소에 없어 11/13 ISE-262로 재시작 | — | — | — | 판단(로컬) | docs/design/infra/synthetic-data.md:5 | — | 취소 후 재시작 서사 |
| F-01 | 추첨 | 추첨 당첨을 "시스템이 대신 선점한 일반 예매"로 다뤄 확정·만료·늦은 승인 환불(E8)·예매당 결제 1개(ADR 0026)를 새로 만들지 않는다 | 추첨 고유 로직 4가지(응모·순위 계산·좌석 배정·예비 승계) | — | 예매 HELD, `source=LOTTERY` | 설계 | lottery/README.md:16-19 | — | 새로 만들지 않고 재사용한 이유 |
| F-02 | 추첨 | 추첨은 Reservation 서비스 안 모듈이고 결제와는 `payment.command`·`payment.result` 이벤트로만 만난다 | — | — | SA 7-1, 결정 기록 #18·#14 | 설계 | lottery/README.md:41 | README.md:43-50 ASCII 구조도 | 모듈형 모놀리스 경계 |
| F-03 | 추첨 | 측정 시나리오 3종: 1 응모 마감 직전 몰림(동기 INSERT 초당 100건), 2 자동결제 폭주(2,000건이 다른 회차 일반 결제를 503으로 만들지 않나), 3 일반 예매 간섭(선점 p95 20% 초과 시 lottery 서비스 분리) | 초당 100건 / 2,000건 / p95 +20% | — | `[가정]` | 설계 | lottery/README.md:34-38 | — | 무엇을 증명하나 |
| F-04 | 추첨 | 시간표: 응모 3일 → 추첨 T~T+10초 → 자동결제 ~T+4분 → 늦은 확정 ~T+25분 → 승계 마감 T+30분 → 종결 ~T+60분 | 응모 3일, 10초, 4분, 25분, 30분, 60분 | — | `[가정]`(T=draw_at) | 설계 | lottery/README.md:55-67 | README.md:55-67 시간표 | 추첨 하루 타임라인 |
| F-05 | 추첨 | 자동결제 2,000건: 전용 그룹 pg-billing 동시 3 + 소비자 3으로 약 3.7분(계산값, ADR 0051) | 약 3.7분 | — | 계산값 | 계산값 | lottery/README.md:61 | — | 개선 후 목표 |
| F-06 | 추첨 | 기준선(공유 pg-approve 10, 소비자 1)은 실측 약 10.7분 | 약 10.7분 → 약 3.7분(계산값) | 직접 계산: 10.7÷3.7 ≒ 2.9배 단축(원문에 배수 없음) | 2,000건 자동결제, 11/10 배포 측정 | 배포 측정(기준선) / 계산값(후) | lottery/README.md:61; lottery/options.md:91,100 | — | 모순 아닌 전후 비교 소재 |
| F-07 | 추첨 | 예측했던 처리 속도: 겹치지 않으면 10÷0.3초 = 초당 약 33건, 2,000건 약 1분 → 11/10 측정으로 틀린 가정으로 판명, 실측 초당 3.17·약 10.7분(소비자 1개가 PG 응답 직렬 대기) | 초당 약 33건(가정) → 초당 3.17(실측) | 직접 계산: 33÷3.17 ≒ 10.4배 낮음 | 기준선 코드, 11/10 배포 | 배포 측정 vs 가정 | lottery/options.md:91 | — | 가정이 실측에 무너진 순간 |
| F-08 | 결제 | 11/10 시나리오 2(L2-a)에서 기준선이 10분 안 95%를 못 지킴. 3회 측정 93.1·92.4·93.4% | 목표 95% 대비 93.1% / 92.4% / 93.4% | 직접 계산: 95-92.4 = 2.6%p 부족(최악) | 11/10 배포, 기준선 | 배포 측정 | lottery/options.md:95 | — | 목표 미달 → ADR 0051 |
| F-09 | 결제 | 원인은 pg-approve 자리가 아니라 payment.command 소비자 1개의 직렬 대기. PG 지연 스윕 동안 남은 자리 9/10 | 남은 자리 9/10 | — | PG 지연 스윕 | 배포 측정 | lottery/options.md:95 | — | 병목 위치 반전(자리가 아니라 소비자) |
| F-10 | 결제 | 11/12 ADR 0051 채택: payment.command 파티션 3, 리스너 동시성 3, 전용 그룹 pg-billing(동시 3, 대기 0), pg-approve는 일반 결제만 24–30(지금 30, ISE-246 뒤 확정), 전용 토픽 payment.billing은 재측정 뒤 | 파티션 1→3?(원문은 "3"만), 소비자 1 → 3, pg-billing 동시 3 | — | 결제 담당 구현 PR `#521` | 설계(채택) | lottery/options.md:95-101; lottery/README.md:104; details.md:56 | — | 설계가 바뀐 순간 |
| F-11 | 결제 | ADR 0051 기대값: 2,000건 초당 9.51, 약 3.7분(계산값). 11/13 재측정(L2-a ×3, L2-b 30/s)으로 확인 예정 | 초당 9.51, 약 3.7분 | 직접 계산: 2000÷9.51 ≒ 210초 ≒ 3.5분(원문은 약 3.7분이라 불일치 가능, 원문대로 3.7분 인용) | 계산값 | 계산값 | lottery/options.md:100 | — | 재측정 예정 |
| F-12 | 추첨 | D3 응모 접수: 동기 INSERT(A안) 기준선. 11/10 L1에서 초당 100건 p95 10.58ms로 유지, 접수형(C) 비교 측정은 하지 않음 | p95 10.58ms | — | 11/10 배포 L1, 팀 확인 전 | 배포 측정 | lottery/README.md:21,101 | — | 단순한 안 고수, 측정으로 확인 |
| F-13 | 추첨 | L1 세부: 초당 100건 p95 5.0ms, 전체 구간 p95 10.58ms, 초당 800건까지 실패 0 | 100건/s p95 5.0ms; 전체 p95 10.58ms; 800건/s 실패 0 | 직접 계산: 임계 300ms ÷ 10.58ms ≒ 28배 여유 | 11/10 배포 서버 L1 | 배포 측정 | lottery/README.md:190,203 | — | 동기 INSERT가 충분했다 |
| F-14 | 추첨 | D3 바꿀 조건: 초당 100건에서 접수 p95 300ms 초과 또는 커넥션 풀 대기 발생 시 접수 행+outbox(C)로 | p95 300ms | — | `[가정]` | 설계 | lottery/options.md:34; lottery/README.md:198 | — | 전환 기준 |
| F-15 | 추첨 | D3 선택지 B(Kafka 직접 발행)는 실패 저장 문제로 후보에서 뺀다. 동기 INSERT는 SA 4-2 ③·AI 검증 #4(응모 동기 처리 거부)를 뒤집는다 | — | — | 선반영(11/15, PR `#631`, ISE-269) | 판단(기존 거부 판단 번복) | lottery/README.md:24,190; options.md:30,33 | — | 거부했던 판단을 측정으로 뒤집음 |
| F-16 | 추첨 | D2 처음 추천은 A(응모에 schedule_id 복제+부분 유니크)였으나 D9 추첨석 한 등급 분리 덕에 E안(회차당 추첨 1개, UNIQUE(lottery_id,user_id) 그대로)으로 | 추첨 1개/회차 | — | 10/29 결정 | 판단(번복) | lottery/options.md:16-19 | — | 구조 단순화로 제약 해결 |
| F-17 | 추첨 | D5: 마감 전 취소(CANCELED)는 받고 재응모는 막음(D안). 처음 추천은 A(재응모 새 행) | — | — | 10/30 결정 | 판단(번복) | lottery/options.md:62-68 | — | 재응모 금지 |
| F-18 | 추첨 | D6: 10/31에 "전용 그룹 B안 추천"을 "공유 기준선 A+운영 시간대"로 바꿔 문제를 먼저 재현하는 트러블슈팅 소재로 삼고, 11/12 다시 전용 그룹으로 확정 | 10/30 B안 → 10/31 기준선 A → 11/12 B+소비자 3 | — | 결제 담당 합의 | 판단(번복 2회) | lottery/options.md:86-101; lottery/README.md:104,189,199 | — | 일부러 문제를 재현한 뒤 고친 서사 |
| F-19 | 결제 | 요구 조건 ③: 허가 대기로 요청이 유실되거나 DLQ로 가지 않는다(오프셋 안 넘기고 재시도, 백프레셔)는 기준선에서도 지킨다 | 유실 0·DLQ 0 | — | 정합성 항목이라 측정 대상 아님 | 설계 | lottery/options.md:89 | — | 백프레셔 원칙 |
| F-20 | 추첨 | D7 순위: SHA-256(seed ‖ entryId) 오름차순. 시드 약속(commit-reveal)은 확장안. 범위는 1계정 기준 중복 차단 | 응모 1~2매, 예비자 = 당첨자의 50%(상위 1,000명) | 직접 계산: 1,000÷2,000 = 50%(원문 표기) | `[가정]` 당첨 2,000 | 설계 | lottery/options.md:110-119; details.md:11 | — | 공정성 설명 방식 |
| F-21 | 추첨 | 배정 규칙: 남은 추첨석이 응모 매수 이상이면 당첨, 모자라면 건너뜀. 2매는 옆자리 쌍 우선(같은 section·row_no에서 seat_no 1 차이), 없으면 떨어진 두 좌석 | — | — | 10/30 수용, 11/1 예비자 포함 규칙 | 설계 | lottery/options.md:119,156 | — | 연석 미보장 결정 |
| F-22 | 추첨 | D8: 추첨은 한 트랜잭션(A안). 상한 timeout 10초, lock_timeout 3초. 30,000행 갱신, WON·WAITLISTED(좌석 수+1,000행)만 배치, 나머지 LOST는 한 문장 | 상한 10초, 응모 30,000행 | — | `[가정]` | 설계 | lottery/options.md:127-136; details.md:71 | — | 긴 트랜잭션 방어 3가지 |
| F-23 | 추첨 | 30,000행 순위 갱신이 한 트랜잭션 10초 안에 끝나는지는 확인하지 못함(Testcontainers 3만 행 측정 예정) | 10초 상한(미측정) | — | 확인 못 함 | 확인 못 함 | lottery/README.md:204; details.md:140 | — | 미측정 항목 |
| F-24 | 추첨 | D9: 추첨석을 설정 때 좌석 등급 LOTTERY로 떼어 두고(관리자가 한 등급·좌석 수 지정), 종결 때 source_grade로 반환. 처음 추천은 B(안 떼고 추첨 후 배정)였음 | 기본 등급 S | — | 10/29 결정, 팀 확인 전 | 판단(번복) | lottery/options.md:149-158 | — | 시간 규칙 대신 데이터로 격리 |
| F-25 | 추첨 | D10: 당첨자 1명 = 1트랜잭션 배정 워커, 선점 만료 생성+30분. 계산: Kafka 대기 최대 약 3.3분 + REQUESTED 판정 10초 + UNKNOWN 재조회 최장 약 21분 + 여유 | 30분 | — | 10/30 결정 | 설계 | lottery/options.md:174,178 | — | 30분이 나온 계산 |
| F-26 | 추첨 | 근거 정정(11/12): 기준선 소비자 1에서는 합이 약 31.9분으로 30분을 넘었고, ADR 0051 뒤 대기 약 3.7분으로 합 약 25분(계산값). 30분 유지 | 약 31.9분 → 약 25분(계산값) | 직접 계산: 31.9-25 ≒ 6.9분 감소 | 계산값 | 계산값 | lottery/options.md:175 | — | 설계값이 측정으로 흔들렸다 복구 |
| F-27 | 추첨 | 시간 예산(원문 계산): 자동결제 첫 시도 2,000÷(3÷0.3초=초당 10건)=약 3.3분, 결과 미확정 최장 REQUESTED 10초+재조회 10초×6회→1분→5분→15분 ≒ 약 21분 | 3.3분, 약 21분 | 원문 계산 | `[가정]` | 계산값 | lottery/details.md:96-99 | details.md:95-103 시간 예산 표 | 시간 예산 근거 |
| F-28 | 추첨 | 종결 최장 T+60분 = 마지막 승계 예매(T+30분)+만료 30분 | 60분 | 30+30 | — | 계산값 | lottery/details.md:101-102 | — | 종결 시점 |
| F-29 | 추첨 | 자동결제 목표 "10분 안 95%"는 첫 시도 약 4분 + 재조회 필요 비율로 정해지며 재조회 비율은 측정 전 | 10분, 95% | — | 측정 전 | 설계/확인 못 함 | lottery/details.md:107 | — | 목표 |
| F-30 | 추첨 | D13: 승계는 승계 워커(1초 주기, 추첨 행 FOR UPDATE SKIP LOCKED)가 직렬화. 처음 추천은 A(소비자 트랜잭션 내 승계)였으나 D안 | 지연 약 1초, 워커 6개 | — | 10/30 결정 | 판단(번복) | lottery/options.md:226-233; details.md:73 | — | 이중 승계 방지 |
| F-31 | 추첨 | D14: 승계 마감 30분, 남은 WAITLISTED → LOST, NMC는 선점 만료로 끊고 나중 승인은 E8 환불 | 30분 | — | 10/30 결정 | 설계 | lottery/options.md:244-247 | — | 추첨은 끝나야 한다 |
| F-32 | 추첨 | 스케줄러·워커 6종 주기: 마감 10초, 추첨 10초, 배정 1초, 승계 1초, 승계 마감 10초, 종결 1분 | 10/10/1/1/10/60초 | — | — | 설계 | lottery/details.md:68-75 | details.md:68-75 표 | 상태 기반 복구 구조 |
| F-33 | 추첨 | 응모 INSERT는 DB now()로 기간을 SQL 안에서 판정(서버 시계 무관). ON CONFLICT DO NOTHING으로 유니크 위반 처리 | — | — | — | 설계 | lottery/details.md:50 | — | 서버 시계 차이 방어 |
| F-34 | 추첨 | D12 실패 사유별 처리: NOT_SENT는 새 예매로 1회 재시도(payment_attempts 1→2), 결과 미수신 만료는 같은 예매 선점 1회 30분 연장(상한 승계 마감+30분) | 재시도 1회, 연장 30분 1회 | — | 10/30 결정 | 설계 | lottery/options.md:199-216; details.md:79-81 | — | 같은 키 vs 새 키 |
| F-35 | 추첨 | 연장 판정은 저장하지 않고 "기한이 예매 생성+31분 안이면 연장 전"으로 본다 | 31분 | — | ISE-149 | 설계 | lottery/details.md:80 | — | 구현 트릭 |
| F-36 | 추첨 | 배정 워커: 같은 응모가 연속 3번 실패하면 FAILED(ASSIGN_ERROR), 좌석 없음(NO_SEAT)은 승계 마감 전엔 닫지 않고 재시도(ISE-153) | 연속 3회 | — | 11/3 | 설계 | lottery/details.md:72 | — | 방어 규칙 |
| F-37 | 추첨 | 설정 검증: draw_at + 60분 <= starts_at(종결 상한, 11/2 추가). 설정·수정은 now() < LEAST(open_at, entry_start_at)만 | 60분 | — | — | 설계 | lottery/details.md:16-17 | — | 설정 규칙 |
| F-38 | 추첨 | 연속 모순 표시: 일반 오픈 시각은 D9 이전에는 `open_at >= draw_at + 70분` 검증이 필요하다는 안이었으나 D9 결정으로 필요 없어짐 | 70분 → 필요 없음 | — | 10/29~31 | 판단(번복) | lottery/options.md:146,155; README.md:99 | — | 규칙을 데이터 분리로 대체 |
| F-39 | 추첨 | API 목록: 설정(POST admin), 수정(PATCH), 응모(201), 취소 DELETE, 내 결과 GET, 공개 조회 GET(serverTime 포함, ISE-227), 회차의 추첨 조회 GET(404면 버튼 숨김) | 응답 201(SA는 202) | — | — | 설계 | lottery/details.md:40-48 | — | 서버 시각을 화면이 쓰는 이유 |
| F-40 | 추첨 | 시드 32바이트(BYTEA, ADR 0041), SecureRandom, 설정 뒤 변경 불가, 비공개 | 32바이트 | — | — | 설계 | lottery/details.md:15 | — | 시드 |
| F-41 | 추첨 | 실패 표 15행 (DB 장애, 마감 지연, 추첨 중 종료, 10초 초과, 동시 실행, 배정 중 종료, 좌석 없음, 좌석 부족, 반환 누락, outbox·Kafka 정지, PG 장애, UNKNOWN→NMC, 연장 직전 승인 경쟁, 대조 실패 승인, result 소비 실패, 승계 경합, 빌링키 해지, 빌링키 전체 무효) | 행 수 약 18 | 직접 계산 | — | 설계 | lottery/details.md:111-129 | details.md:109-129 실패 표 | 실패의 실패까지 적는 표 |
| F-39b | 추첨 | 시스템 원인으로 빌링키 전체 무효(Mock PG 서명 키 변경, 빌링키 암호화 키 변경) 시 모든 당첨자가 BILLING_KEY_MISSING으로 거절되고 승계가 되돌릴 수 없다. 두 키를 바꾸지 않는다 | — | — | ISE-235 배포 뒤 Mock PG 재생성만으로는 생기지 않음 | 설계 | lottery/details.md:129 | — | 대응도 실패하는 항목 |
| F-42 | 추첨 | 첫 기한 직후 승인이 연장보다 먼저 처리되면 만료·환불·FAILED, 뒤에 처리되면 확정·PAID로 갈림. 좌석 이중 판매는 없음. 감수(예매 흐름 담당 동의 11/4) | 시간대 = 만료 스케줄러 지연 | — | ISE-150 리뷰 | 판단(감수) | lottery/details.md:124 | — | 경쟁 상태를 알고 감수 |
| F-43 | 추첨 | 대조 실패 승인(사용자·금액·통화 불일치)은 환불 요청하고 예매 HELD로 남아 좌석이 최장 T+60분 묶임. 드물 것으로 봄(측정 전) | 최장 T+60분 | — | ISE-150 | 설계/측정 전 | lottery/details.md:125 | — | 알려진 한계 |
| F-44 | 추첨 | 테스트 기준: 변이 테스트로 확인할 것(정렬 방향, 건너뛰기→중단, 유니크 제거, LOTTERY 제외 조건 제거, 추첨 행 잠금 제거, 조건부 UPDATE 제거) | 7개 대상 행 | — | — | 설계 | lottery/details.md:131-140 | — | 변이 테스트 |
| F-45 | 추첨 | 이슈화: ISE-49 하위 ISE-82~96 등록(10/31), 모듈 경계 검사(ArchUnit)는 팀 안건으로 | 이슈 15개 | 직접 계산: 82~96 = 15개 | 10/31 | 팀 | lottery/README.md:127-145 | — | 작업 분해 |
| F-46 | 추첨 | 추첨 때문에 타 영역(좌석·예매 흐름·결제)에 생기는 일도 추첨 담당이 맡고, 해당 담당은 인터페이스 합의와 PR 리뷰 | — | — | 10/31 결정 | 판단 | lottery/README.md:147 | — | 협업 방식 |
| F-47 | 추첨 | 구현 4단계: 1 계산·API·스케줄러, 2 배정 워커·소비자 훅, 3 자동결제·승계·종결, 4 빌링키 사본·확장안 | 4단계 | — | — | 설계 | lottery/README.md:117-122 | — | 순서 |
| F-48 | 추첨 | SA 일괄 반영 목록과 ADR 0037(D9 등급 분리)·0038(D12)·0039(D6, 후에 0051이 대체) 작성(10/31) | ADR 3건 | — | 10/31 | 설계 | lottery/README.md:183-193 | — | 기록 |
| F-49 | 추첨 | 확인하지 못한 것: 토스 테스트 모드 빌링 API 동시 호출 한도와 응답 시간, payment-service 인스턴스 수와 파티션 수 | — | — | 확인 못 함 | 확인 못 함 | lottery/README.md:205-206 | — | 한계 |
| F-50 | 추첨 | E2E 11/2 로컬 compose(dev `5071f3b`): 응모자 3명(1명 빌링키 미등록), 추첨석 3, 예비 2 → 3명 WON, 예매 3 HELD, 빌링키 있는 2명 APPROVED, 없는 1명 DECLINED·BILLING_KEY_MISSING, outbox 전부 SENT | 3명/예매 3/승인 2/거절 1 | — | 로컬 | 로컬 측정 | lottery/e2e-runbook.md:64-76 | — | 첫 end-to-end 성공 |
| F-51 | 추첨 | 빌링키 저장 암호문 92자, DB에 원문(`bk-`) 없음 | 92자 | — | 11/2 로컬 | 로컬 측정 | lottery/e2e-runbook.md:75 | — | 빌링키 암호화 |
| F-52 | 추첨 | E2E 11/5 로컬 compose(dev `b7217fc`): 시나리오 1은 추첨석 4·예비 3·응모자 7명(1명 수량 2, 3명 빌링키 없음, 1명 취소) 60개 확인 통과, 시나리오 2(추첨석 5·빌링키 없는 응모자 2) 12개 확인 통과 | 60개 + 12개 항목 통과 | 직접 계산: 72개 확인 | 로컬 | 로컬 측정 | lottery/e2e-runbook.md:82-83 | — | 승계·종결까지 확인 |
| F-53 | 추첨 | 11/5: 거절로 빈 2석에 순위 5(수량 2) 예비자가 WON → PAID, 순위 6은 자리가 없어 LOST. 종결 DONE, 판매 4석은 S·SOLD, 남은 5석은 S·AVAILABLE(S석 6000) | 4석 SOLD, 5석 AVAILABLE, S석 6000 | — | 로컬, 추첨 시각을 61분 앞으로 옮겨 종결 확인 | 로컬 측정 | lottery/e2e-runbook.md:89-92 | — | 승계 시연 장면 |
| F-54 | 추첨 | 11/5 결제·예매 dead_letters 0건 | 0건 | — | 로컬 | 로컬 측정 | lottery/e2e-runbook.md:93 | — | 메시지 유실 없음 |
| F-55 | 추첨 | 겪은 것: 스택 직후 첫 로그인 503(Gateway가 Eureka 등록 정보를 받는 데 시간), 11/2 실행 예매의 결과가 재기동 뒤 처리되어 RefundRequested(EXPIRED_AFTER_APPROVAL)가 나갔고 Mock PG가 NOT_REFUNDABLE로 거절(Mock PG 재기동으로 승인 기록이 사라졌기 때문인지는 확인 못 함) | — | — | 로컬 | 로컬 측정 | lottery/e2e-runbook.md:78,95 | — | 알려진 함정 |
| F-56 | 인프라 | F1 장애 주입(11/11 16:25~16:57 KST) 배포 서버: 3건 주입(F1-a 결제 서버 SIGKILL, F1-b Kafka 정지, F1-c 복구) | 3건 | — | 서버 커밋 `74f91ffb`, 측정 모드 켬 | 배포 측정 | lottery/f1-fault-injection.md:3,151 | — | 장애 주입 실험 |
| F-57 | 결제 | F1-a: kill 시점 결제 APPROVED 7, REQUESTED 0. 재기동 뒤 APPROVED 17, DECLINED(BILLING_KEY_MISSING) 2, NOT_SENT 0, NEEDS_MANUAL_CHECK 0. 응모 PAID 17(20매), FAILED 2. 원장 승인 34 = 결제 DB APPROVED 34, 중복 주문 0, dead_letters 0/0 | 승인 7 → 17(재기동 뒤), 원장 34 = DB 34 | 직접 계산: 7→17은 +10건 | 추첨 24(회차 94, 20석, 16:25), 응모자 300명 | 배포 측정 | lottery/f1-fault-injection.md:146 | — | 이중 결제 0 입증 |
| F-58 | 인프라 | F1-a 타임라인: T0 16:25:05.108 첫 결제 행, T1 16:25:05.282 docker kill(약 0.17초 뒤), T2 16:25:35.085 docker start, T3 16:25:50.668 healthy(기동 15.6초), 마지막 갱신 16:25:52.147 | 기동 15.6초, 수렴까지 약 1.5초(T3→마지막 갱신) | 직접 계산: 16:25:52.147-16:25:50.668 ≒ 1.48초 | 서버 감시 스크립트가 0.3초마다 결제 행을 보다 첫 행에서 즉시 kill | 배포 측정 | lottery/f1-fault-injection.md:146,151 | — | 정확한 타이밍 |
| F-59 | 인프라 | docker kill 뒤 결제 서버는 자동 재기동 안 됨(Exited 137, RestartCount 0), 서비스 다운 경보 규칙(`up == 0`) 없어 결제 서버가 내려간 동안 슬랙 경보 없음 | RestartCount 0 | — | `restart: unless-stopped` 설정이지만 kill은 수동 정지로 처리 | 배포 측정 | lottery/f1-fault-injection.md:146,75 | — | 울려야 했는데 안 울린 경보 |
| F-60 | 결제 | F1-a에서 NOT_SENT 경로는 서버에서 재현되지 않았고(걸린 건 0), 통합 테스트 4개로 대체: payment 1개, reservation 3개. 2026-11-11 15:15 KST 실행 결과 4개 모두 통과(실패 0, 건너뜀 0) | 4개 통과 | — | 로컬 dev `e122d335`, Docker Desktop 29.8.2 | 로컬 측정 | lottery/f1-fault-injection.md:11,166-173 | — | 서버에서 못 보인 것을 테스트로 대체 |
| F-61 | 인프라 | F1-b: T4 16:32:47.307 Kafka 정지(강민 직접 실행, 에이전트 실행은 권한 분류기가 막음). 추첨 25(5석) 16:35:04 WON 4(5매), 예매 HELD 4, outbox PENDING 4, 결제 행 0 | WON 4(5매) | — | 측정 모드 켬 | 배포 측정 | lottery/f1-fault-injection.md:147 | — | 릴레이 정지 |
| F-62 | 추첨 | F1-b 선점 만료를 1분 뒤로 앞당긴 DB UPDATE 1회(계정 담당 동의 뒤, 4건) → 만료 3초 뒤 4건 모두 1회 연장: hold_expires_at 17:06:55.787(앞당긴 값+30분), HELD·WON 유지 | 연장 4/4건, +30분 | 직접 계산: 16:36:58.7~.76 연장 - 16:36:55.787 만료 ≒ 3초 | 16:35:55.736 앞당김 | 배포 측정 | lottery/f1-fault-injection.md:147 | — | 연장 규칙 입증 |
| F-63 | 인프라 | LotterySettlementSlow(warning) Alertmanager startsAt 16:45:49.894(WON 생성 16:35:04 + 10분 45초). reservation outbox 경보는 없어 울리지 않음("안 울린 경보"). 슬랙 수신 시각은 확인 못 함 | 10분 45초 | 직접 계산: 16:35:04→16:45:49.894 = 10분45초 | 10분 지속 규칙 | 배포 측정 | lottery/f1-fault-injection.md:147,139 | — | 경보 탐지 지연 10분+ |
| F-64 | 결제 | F1-c: T6 16:46:14.034 Kafka start, healthy 16:46:26.505(약 12.5초), 결제 첫 생성 16:46:18.954, T7 16:46:49.214 마지막 예매 확정. 밀린 outbox 4건 발행, PENDING 0. 결제 APPROVED 4, DECLINED(BILLING_KEY_MISSING) 2, 응모 PAID 4(5매)·FAILED 2, 예매 CONFIRMED 4·RELEASED 2. 예매당 결제 1건. 원장 승인 38 = DB APPROVED 38, 중복 0, dead_letters 0/0 | Kafka 정지 약 13분 27초(16:32:47→16:46:14), 복구 후 확정까지 약 35초 | 직접 계산: 16:46:14→16:46:49 = 35초; 16:32:47→16:46:14 = 13분27초 | 이중 결제 0 | 배포 측정 | lottery/f1-fault-injection.md:148 | — | 복구 후 1분 안에 수렴 |
| F-65 | 결제 | 당첨 4건 중 1건(78521)이 카드 없음 → 승계 78523도 카드 없음 → 승계 78524 결제. 카드 없음 응모자 비율 120명 중 12명(기본) | 12/120 | 직접 계산: 10% | seed-demo 기본 | 배포 측정 | lottery/f1-fault-injection.md:45,148 | — | 승계 연쇄 |
| F-66 | 인프라 | 끝 상태(16:47:51): 활성 경보 0건, 컨테이너 21개 실행 중, 스모크 4개 통과. 기준값 대비 응모 PAID +38, FAILED +6, 결제 APPROVED +38, BILLING_KEY_MISSING +6, dead_letters 0/0 | +38, +6 | — | 11/11 | 배포 측정 | lottery/f1-fault-injection.md:149 | — | 정상 복귀 |
| F-67 | 인프라 | 시작 전 기준값: 활성 경보 0건(15:29), outbox 예매 SENT 32,252 / 결제 SENT 75,522, 응모 PAID 14,125, FAILED 18,127, LOST 1,053,150, 결제 APPROVED 57,394, DECLINED BILLING_KEY_MISSING 18,127·CARD_DECLINED 1, Mock PG 15시간 전 재생성 | LOST 1,053,150, APPROVED 57,394 | — | 11/11 15:29 | 배포 측정(기준값) | lottery/f1-fault-injection.md:145 | — | 누적 데이터 규모 |
| F-68 | 팀 | F1 운영 규칙: 시연 패널 사용 금지(orderId 없이 건 장애는 서버 모든 승인에 적용), POST /mock-pg/reset 금지(원장 삭제), F1 동안 main 머지 중단, Mock PG 재시작 금지, 측정 모드는 켜고 팀 공지 | — | — | ISE-213, 0절 | 판단 | lottery/f1-fault-injection.md:9-14,28-35 | — | 공유 서버 운영 규칙 |
| F-69 | 팀 | 측정 모드를 켜지 않는다는 처음 안을 강민이 거절하고 켜기로 함(추적 샘플링 0.01이라 Zipkin으로 장애 경로 추적이 어려움, 켜기·끄기는 슬랙 자동 알림). 켜고 끌 때 gateway·user·reservation·payment·eureka·prometheus가 약 1분 재기동되고 로그인 끊김 | 샘플링 0.01 → 1.0, 재기동 약 1분 | — | 판단 기록 `2026-11-11-f1-measure-mode-required.md` | 판단(거부 AI 제안) | lottery/f1-fault-injection.md:14,33 | — | AI 제안 거부 사례 |
| F-70 | 팀 | 에이전트가 서버 명령을 실행하는 것은 팀 규칙(사람이 직접 치기)과 다른 이번 F1 한정 예외. 읽기·시드·docker kill/stop/start는 단계마다 강민 허락, DB 쓰기는 계정 담당 동의 | — | — | 0절 1번 | 판단 | lottery/f1-fault-injection.md:9 | — | 예외 승인 방식 |
| F-71 | 추첨 | F1-b 탐지 한계: reservation outbox 경보 규칙이 없고(규칙은 application="payment-service"만 봄), 경보 확대 안 함, 결제 담당에게 개선 후보로 남김. F1-b 탐지가 10분 넘게 걸림 | 10분+ | — | 0절 4번 | 판단 | lottery/f1-fault-injection.md:12,139 | — | 경보 사각지대 발견 |
| F-72 | 대기열 | 입장 토큰은 JWS(JWT) HS256 고정, 길이 상한 4,096자, claim 7개(typ, aud, sub, scheduleId, exp, iat, jti) | 4,096자, 7 claim | — | 확정 2026-10-30 | 설계 | queue/contracts.md:15-19,21-31 | contracts.md:37-41 payload 예시 | 토큰 계약 |
| F-73 | 대기열 | 토큰 유효 시간은 설정 queue.entry-token-ttl 기본 10분(SA 9-4의 T `[가정]`) 하나에서 읽어 Redis issued와 JWT exp에 같은 값을 쓴다(ISE-283) | 10분 | — | `[가정]` | 설계 | queue/contracts.md:29 | — | TTL 단일 출처 |
| F-74 | 대기열 | jti = entry:{scheduleId}:{sub}:{발급 시각 ms}의 SHA-256 앞 16바이트(hex 32자). 재발급은 같은 발급 시각을 써 토큰 문자열까지 같다 | 16바이트/hex 32자 | — | ADR 0031 결정 9 | 설계 | queue/contracts.md:31,33 | — | 멱등 재발급 |
| F-75 | 대기열 | Gateway는 서비스·Redis 호출 없이 토큰만으로 검증, 틀리면 403 ENTRY_TOKEN_INVALID(이유 미노출). Redis 장애 중에도 이미 받은 토큰은 통과 | — | — | SA 9-5 | 설계 | queue/contracts.md:9,55-57 | — | Redis 장애 내성 |
| F-76 | 대기열 | 입장 토큰은 선점 성공 시 소모(ADR 0053, ISE-207). DB seat_hold_requests.entry_token_jti로 확인, 트랜잭션 밖에서 discardEntryToken 호출, 같은 jti일 때만 Lua로 삭제. Redis 장애 시 로그만 남기고 최대 10분간 같은 토큰을 재수령 | 최대 10분 | — | — | 설계 | queue/contracts.md:62-66 | — | 토큰 재사용 차단 |
| F-77 | 대기열 | 차례가 온 뒤 1분(queue.turn-ttl) 지나면 토큰 API가 404 QUEUE_NOT_JOINED, 새 번호로 줄 맨 뒤(ADR 0031 결정 11, ISE-282) | 1분 | — | — | 설계 | queue/contracts.md:74 | — | 차례 유효 시간 |
| F-78 | 대기열 | 이전 안 대비 변경: 선점 재사용 "10분 안에 같은 토큰으로 여러 번 가능" → "성공 시 소모". 선점 경로를 POST /schedules/{id}/reservations로(본문 scheduleId 제거), 회차 비교를 Gateway가 경로로 | 10분 재사용 → 1회 소모 | — | ADR 0053 | 설계(변경) | queue/contracts.md:78-85 | — | 토큰 정책 변경 |
| F-79 | 대기열 | 계약은 2026-10-29~10-30 좌석 선점 최권우·좌석 조회 반효승 확인으로 확정(이름 노출은 브로셔에서 역할로 대체 필요) | 확인 3건 | — | — | 팀 | queue/contracts.md:5,91-95 | — | 팀 합의 |
| G-01 | AI | 상담은 예매·결제와 분리된 읽기 전용 별도 컨테이너이며 DB·Redis·Kafka·타 서비스 API를 쓰지 않는다 | 해당 없음 | — | assistant-service 설계 2026-11-09 | 설계 | docs/design/assistant/README.md:7-9 | — | 상담이 죽어도 예매는 산다 |
| G-02 | AI | 상담 장애 시 Gateway는 상담 경로에만 503을 준다. LLM 장애는 오류가 아니라 200 SEARCH로 강등 | 해당 없음 | — | 설계 | 설계 | docs/design/assistant/README.md:9, 53 | — | 장애가 번지지 않는 격리 |
| G-03 | AI | 상태 없는 단일 턴. 대화 기록은 화면 상태에만 두고 서버에 저장하지 않음 | 해당 없음 | — | v1 | 설계 | docs/design/assistant/README.md:10 | — | — |
| G-04 | AI | 답변 모드 2종: AI(Claude가 문서 근거로 답함) / SEARCH(검색 결과만). fallbackReason은 LLM_DISABLED, LLM_UNAVAILABLE | 해당 없음 | — | 설계 | 설계 | docs/design/assistant/README.md:41-47 | — | 강등(fallback) 구조 |
| G-05 | AI | question은 1~500자, 공백만 불가 | 1~500자 | — | API 검증 | 설계 | docs/design/assistant/README.md:26 | — | — |
| G-06 | AI | SSE 스트리밍 엔드포인트 추가(`/messages/stream`). 이벤트 순서 meta → delta* → (fallback) → sources → done | 5종 이벤트 | — | 2026-11-09 사용자 요청 | 설계 | docs/design/assistant/README.md:55-72 | — | 토큰이 만들어지는 대로 보이는 답변 |
| G-07 | AI | 스트림 시한: 첫 delta까지 8초, 전체 20초, 응답 안전망 30초 | 8초 / 20초 / 30초 | — | 설계 | 설계 | docs/design/assistant/README.md:74-75 | — | — |
| G-08 | AI | SDK 클라이언트 전역 timeout(8초)은 OkHttp callTimeout이라 스트림 전체를 8초에 끊음. 스트림 호출에만 읽기 간격 8초·전체 22초를 주고 첫 조각 8초·전체 20초는 감시 스레드가 잼 | 8초 → (읽기 8초 / 전체 22초) | — | anthropic-java 3.3.0 | 설계(구현 메모) | docs/design/assistant/README.md:91 | — | 라이브러리 기본 시한의 함정 |
| G-09 | AI | Gateway `metadata.response-timeout`은 Reactor Netty ReadTimeoutHandler라 읽기 사이 간격 시한이지 응답 전체 시한이 아님. 스트림이 계속 조각을 보내면 20초여도 안 끊김. 라우트 시한은 10초 유지 | 라우트 10초, 연결 1초, 가장 긴 공백 최대 8초 | — | reactor-netty-http 1.3.8 바이트코드 확인 | 설계(소스 확인) | docs/design/assistant/README.md:95, 134 | — | 바이트코드로 확인해 라우트 시한을 늘리지 않기로 함 |
| G-10 | AI | 실제 키 확인(claude-haiku-5-5, 질문 2개): 첫 조각까지 0.8~1.4초, 전체 1.3~2.1초, 조각 간격 약 0.02~0.15초 | 첫 조각 0.8~1.4초, 전체 1.3~2.1초 | — | 2026-11-09, 질문 2개, 키 있는 실제 호출 | 로컬 측정 | docs/design/assistant/README.md:96 | — | 실측 지연 |
| G-11 | AI | 둘째 질문(문서에 없는 내용)은 sources 빈 배열 | 해당 없음 | — | 질문 2개 중 1개 | 로컬 측정 | docs/design/assistant/README.md:96 | — | 문서 밖 질문은 인용 없음 |
| G-12 | AI | 지식 문서 7개(booking-flow, open-time, purchase-limit, queue, payment, cancel-refund, lottery), 절 ID는 `<분류>.<이름>` 18개 | 7개 파일, 18개 절 | — | 구현 메모 | 설계 | docs/design/assistant/README.md:100, 203 | — | — |
| G-13 | AI | 검색은 벡터 DB 없이 한글 bigram 겹침 점수, 상위 4절, 최고 점수가 기준보다 낮으면 전체 절 투입 | top-k 4, search-result-count 3, min-score 2.0 | — | 설정 기본값 | 설계 | docs/design/assistant/README.md:110-112, 198 | — | 벡터 DB를 안 쓴 이유 |
| G-14 | AI | 점수는 IDF 가중, 제목·keywords 일치 2배. 질문 말투 묶음("되나요" 등)을 빼지 않으면 "환불은 언제 되나요?"가 매수 초과 절에 먼저 붙었음 | 해당 없음 | — | 구현 중 발견 | 로컬 측정 | docs/design/assistant/README.md:202 | — | 검색 품질 문제와 해결 |
| G-15 | AI | 모델 claude-haiku-5-5, max_tokens 1024, effort low. citations는 structured outputs와 함께 쓰면 400이라 쓰지 않음 | max_tokens 1024 | — | 설계 | 설계 | docs/design/assistant/README.md:115-119 | — | — |
| G-16 | AI | 비용 가정: 질문당 입력 3k·출력 400 토큰 시 약 $0.0005. 프롬프트 캐싱은 접두가 바뀌어 쓰지 않음 | 약 $0.0005/질문 | — | 가정(원문 "가정 시") | 계산값(가정) | docs/design/assistant/README.md:126 | — | 질문당 비용이 작다 |
| G-17 | AI | SDK 시한 8초, 재시도 0회(SDK 기본 10분·2회를 바꿈). 동시 LLM 세마포어 4, 넘으면 SEARCH | 10분 → 8초, 재시도 2 → 0, 세마포어 4 | 시한 10분→8초 = 75배 단축(600/8, 직접 계산) | 설계 | 설계 | docs/design/assistant/README.md:129-130 | — | 기본값을 믿지 않는다 |
| G-18 | AI | 요청 제한(메모리, 재시작 시 초기화) `[가정]`: 사용자당 5회/분·30회/일, 전체 LLM 1,000회/일 | 5/분, 30/일, 1,000/일 | — | 가정 표시됨 | 설계(가정) | docs/design/assistant/README.md:131-133, 199 | — | — |
| G-19 | AI | assistant 라우트만 응답 시한 10초(SA 9-4의 3초 상한 예외). ADR 0049에 기록 | 3초 → 10초 | 직접 계산 3.33배 | 설계 | 설계 | docs/design/assistant/README.md:134 | — | 예외를 ADR로 남긴 사례 |
| G-20 | AI | 키가 없거나 LLM_ENABLED=false면 클라이언트를 만들지 않고 SEARCH. CI·테스트·로컬 기본 모드 | 해당 없음 | — | 설계 | 설계 | docs/design/assistant/README.md:137 | — | 키 없이도 동작하는 최소 완료선 |
| G-21 | AI | Anthropic에는 질문 원문만 보내고 userId·이메일은 안 보냄. 로그에 질문 원문 안 남김 | 해당 없음 | — | 설계 | 설계 | docs/design/assistant/README.md:139-140 | — | 개인정보 최소화 |
| G-22 | AI | 위젯 UI: 2026-11-09 사용자가 `/help` 페이지 유지 추천 대신 떠 있는 위젯을 선택. 400px 창, 40rem보다 좁으면 전체 덮음. 예매 흐름·로그인·운영자 화면엔 위젯 없음 | 400px, 40rem | — | 2026-11-09 | 판단 | docs/design/assistant/README.md:144-149 | — | 추천을 거부하고 위젯을 고른 판단 |
| G-23 | AI | 스트림 요청 헤더 Accept: text/event-stream만 보내면 400은 빈 본문, 429는 500이던 결함(셀프 리뷰에서 재현). ISE-221로 오류 응답을 Accept와 무관하게 application/json으로 고정 | 400 → 빈 본문, 429 → 500 | — | 2026-11-09, 모든 서비스 공통 처리기에 적용 | 로컬 측정(재현) | docs/design/assistant/README.md:76, 207 | — | 셀프 리뷰가 잡은 결함 |
| G-24 | AI | 구현 순서의 최소 완료선은 4단계(키 없이 동작). 컨테이너 상한 +384m | +384m | — | 설계 | 설계 | docs/design/assistant/README.md:166-174 | — | — |
| G-25 | AI | 상담 서비스 테스트 64개 통과. `:assistant-service:test :gateway:test :config-server:test :workspace-tools:test` 통과 | 64개 | — | 2026-11-09 | 로컬 측정 | docs/design/assistant/README.md:212 | — | — |
| G-26 | AI | app.jar가 약 102MB(Anthropic SDK 의존성이 큼) | 약 102MB | — | 2026-11-09 | 로컬 측정 | docs/design/assistant/README.md:211 | — | — |
| G-27 | AI | bootJar를 test 프로필로 띄워 200 SEARCH/LLM_DISABLED와 출처 3개로 답함, X-User-Id 없으면 401 | 출처 3개 | — | 로컬 | 로컬 측정 | docs/design/assistant/README.md:213 | — | — |
| G-28 | AI | 아직 확인 못 함: 실제 Anthropic API 품질, 8초 안 지연, 배포 EC2 실제 메모리(384m·힙 192m은 가정), GHCR 패키지 공개 전환 필요(전엔 Jenkins 대기가 20분 뒤 중단) | 384m, 힙 192m, 20분 | — | 가정 | 설계(미확인) | docs/design/assistant/README.md:217-222 | — | 미확인 목록 |
| G-29 | 기타 | 11/6 시연 방식: 화면 `?demo=on`, 요청마다 traceId, Grafana 요청 로그 연결, DB 쿼리로 저장 결과 확인, 장애 현장 주입 | 해당 없음 | — | 2026-11-05 초안, 11-06 수정 | 설계 | docs/design/demo/2026-11-06-mvp-scenarios.md:7-13 | — | 장애를 현장에서 주입하는 시연 |
| G-30 | 기타 | 11/5 릴리스는 머지에서 배포 완료까지 4분 35초. 재기동 뒤 약 35초 503 가능(11-04 첫 배포 기록) | 4분 35초, 약 35초 | — | 2026-11-04/05 | 배포 측정 | docs/design/demo/2026-11-06-mvp-scenarios.md:27 | — | 배포 속도 |
| G-31 | 추첨 | 시연용 응모자 120명 제안(추첨석 30 / 예비 15, 1매 90명·2매 30명, 카드 미등록 12명). 원문이 "제안값이고 근거 있는 기준이 아니다"라고 명시 | 120명, 30석, 예비 15, 1매 90, 2매 30, 미등록 12 | — | 제안값 | 설계(제안) | docs/design/demo/2026-11-06-mvp-scenarios.md:46-53 | — | 추첨 모의 진행 |
| G-32 | 추첨 | 응모자 적재 방식 비교: 공개 API 적재(채택) vs DB 직접 INSERT(제외). 직접 INSERT는 카드가 없어 자동결제가 전부 거절되고 공유 서버 DB에 쓰기를 함 | 해당 없음 | — | 설계 | 판단 | docs/design/demo/2026-11-06-mvp-scenarios.md:55-58 | — | 선택지 비교 |
| G-33 | 추첨 | 로컬에서 108개 카드 재등록 82초, REVOKED 108건 증가 | 108개, 82초, REVOKED +108 | 직접 계산 약 0.76초/개(82/108) | 2026-11-06 로컬 | 로컬 측정 | docs/design/demo/2026-11-06-mvp-scenarios.md:83 | — | — |
| G-34 | 결제 | 11/6 시연 때 Mock PG가 빌링키를 메모리 Set에 보관해 재기동하면 DB는 ACTIVE인데 자동결제가 BILLING_KEY_MISSING. ISE-235(#505)부터 서명(`bk-<UUID>.<서명>`) 확인으로 재기동해도 유효. 서버에서는 재현 안 함 | 해당 없음 | — | 코드 근거, 서버 재현 안 함 | 설계(코드 근거) | docs/design/demo/2026-11-06-mvp-scenarios.md:67 | — | 재기동하면 사라지던 빌링키와 서명 방식 전환 |
| G-35 | 추첨 | 추첨 순위는 SHA-256(seed ‖ entryId)라 특정 계정을 당첨시킬 수 없음 | 해당 없음 | — | 설계 | 설계 | docs/design/demo/2026-11-06-mvp-scenarios.md:87 | — | 추첨 공정성 |
| G-36 | 인프라 | 시연 전후 dead_letters 증가 없음, outbox_events SENT가 공통 기대 | 해당 없음 | — | 설계 | 설계 | docs/design/demo/2026-11-06-mvp-scenarios.md:120 | — | — |
| G-37 | 결제 | 장애 주입 API(`/mock-pg/faults`, count만큼 쓰이면 해제, 재기동 시 소멸). orderId 없는 규칙은 서버 모든 승인에 적용 | 해당 없음 | — | 설계 | 설계 | docs/design/demo/2026-11-06-mvp-scenarios.md:124-144 | — | — |
| G-38 | 인프라 | 로컬 리허설 2026-11-06 00:20~01:43(KST), 기준 커밋 0bc8141(ISE-200 LIMIT 수정 900f83e 전). 공유 서버에는 요청 안 보냄 | 약 83분 | 직접 계산 00:20→01:43=1시간 23분 | 로컬 | 로컬 측정 | docs/design/demo/2026-11-06-mvp-scenarios.md:148-154 | — | 리허설 조건 |
| G-39 | Gateway | D-02: 브라우저 시계를 서버보다 1시간 빠르게 둬도 카운트다운이 서버 기준(00:01:12), 오픈 0.4초 뒤 새로고침 0회로 [예매하기] 활성 | 1시간 어긋남, 0.4초, 새로고침 0회 | — | 로컬 헤드리스 Chrome | 로컬 측정 | docs/design/demo/2026-11-06-mvp-scenarios.md:180 | — | 서버 시각이 오픈 기준 |
| G-40 | 대기열 | D-03: POST /queue 201 → GET /queue/me 200 → POST /queue/token 200 → GET /seats 200, WAITING→READY 확인. 혼자 진행하면 대기열이 금방 끝나 긴 줄 안 보임(입장 속도 초당 50명) | 초당 50명 | — | 로컬 | 로컬 측정 | docs/design/demo/2026-11-06-mvp-scenarios.md:181, 188 | — | 긴 대기를 보이려면 부하가 필요 |
| G-41 | 좌석 | D-01 기대: 회차 좌석 1,500행(ADR 0060 전에는 15,000행). 15,000석 생성 시간은 서버에서 재지 않음 | 15,000 → 1,500행 | 직접 계산 1/10(1,500/15,000) | ADR 0060 | 설계 | docs/design/demo/2026-11-06-mvp-scenarios.md:41, 179, 236 | — | 회차 좌석 수 축소 |
| G-42 | 결제 | D-04: 선점 201 HELD → 결제 200 APPROVED → 완료 → 내 예매. 예매·좌석 CONFIRMED, 회차 좌석 SOLD, 결제 CARD·APPROVED, 티켓 ISSUED, outbox SENT | 해당 없음 | — | 예매 44, 로컬 | 로컬 측정 | docs/design/demo/2026-11-06-mvp-scenarios.md:182 | — | 정상 흐름 한 줄 |
| G-43 | 인프라 | D-05(trace 추적): 수정 전 기대 결과로는 실패. 결제 trace 6aeca0bc0c4d…는 Gateway·payment만(Zipkin 4구간, Loki 각 1줄, reservation 0줄). 예매 확정은 Kafka 비동기라 다른 trace, 이벤트 본문 traceId에만 원 값. 기대 결과를 설계에 맞게 고침(#367). 수정 절차 전체는 재실행 안 함 | Zipkin 4구간, Loki 1줄, reservation 0줄 | — | 로컬 | 로컬 측정(실패 후 기대 수정) | docs/design/demo/2026-11-06-mvp-scenarios.md:183, 186 | — | 기대를 코드에 맞춘 반전(trace가 Kafka에서 끊김) |
| G-44 | 좌석 | F-01: 동시 선점 3회 모두 1명 201 HELD, 1명 409 SEAT_ALREADY_HELD, 좌석당 활성 예매 1건 | 3회 | — | 로컬 | 로컬 측정 | docs/design/demo/2026-11-06-mvp-scenarios.md:194 | — | 이선좌 동시 선택 |
| G-45 | 좌석 | F-02: 4매 선점 201 뒤 1매 추가 409 PURCHASE_LIMIT_EXCEEDED | 4매 한도 | — | 로컬 | 로컬 측정 | docs/design/demo/2026-11-06-mvp-scenarios.md:195 | — | — |
| G-46 | Gateway | F-03: 오픈 전 진입 409 SALE_NOT_OPEN, 토큰 없음·바꾼 입장 토큰·다른 회차 토큰 403 ENTRY_TOKEN_INVALID, 바꾼 결제 토큰 403 PAYMENT_TOKEN_INVALID | 해당 없음 | — | 로컬 | 로컬 측정 | docs/design/demo/2026-11-06-mvp-scenarios.md:196 | — | 검사는 서버가 한다 |
| G-47 | 결제 | F-04: 결제 거절 주입 → 결제 DECLINED·CARD_DECLINED, 예매·좌석 RELEASED, 회차 좌석 AVAILABLE | 해당 없음 | — | 예매 52, 로컬 | 로컬 측정 | docs/design/demo/2026-11-06-mvp-scenarios.md:197 | — | SAGA 보상 |
| G-48 | 좌석 | F-05: 실제 5분 대기. 만료 1.9초 뒤 EXPIRED, 풀린 좌석을 예매 55가 7.8초 뒤 선점해 구매. 만료 스케줄러 5초 주기, 선점 기본 5분 | 1.9초, 7.8초, 5초 주기, 5분 | — | 예매 43, 로컬 | 로컬 측정 | docs/design/demo/2026-11-06-mvp-scenarios.md:198 | — | 방치된 좌석이 돌아온다 |
| G-49 | 결제 | F-06: PG 응답 유실(DROP_RESPONSE) → 202 UNKNOWN → DB UNKNOWN→APPROVED 10.5초, 완료 화면 20.2초, approveCalls 1(중복 결제 없음). 3회 실행 모두 완료 화면, DB 확인은 2건(53·55)만 | 10.5초, 20.2초, approveCalls 1, 3회 중 DB 확인 2건 | — | 예매 53·55·58, 로컬 | 로컬 측정 | docs/design/demo/2026-11-06-mvp-scenarios.md:199 | — | 응답이 사라져도 돈은 한 번만 |
| G-50 | 결제 | F-07: 지연 5초 주입 시 PG 시한(연결 0.5초, 응답 2초)이 지나 202 UNKNOWN 응답 2,022ms, UNKNOWN→APPROVED 10.7초, 완료 화면 22.2초, approveCalls 1. Grafana 패널은 화면으로 확인 못 함 | 2,022ms, 10.7초, 22.2초 | — | 예매 56, 로컬 | 로컬 측정 | docs/design/demo/2026-11-06-mvp-scenarios.md:200 | — | 느린 PG가 요청을 묶지 않는다 |
| G-51 | 추첨 | L-01: 추첨 7 설정 201 OPEN, 회차 14에서 LOTTERY 정확히 30석, S 5,970석. LIMIT 수정 전 이미지, 2회 모두 초과 전환 없음 | 30석, 5,970석 | 합계 6,000석(직접 계산 30+5,970) | 로컬, 0bc8141 | 로컬 측정 | docs/design/demo/2026-11-06-mvp-scenarios.md:206 | — | 추첨석 분리 |
| G-52 | 추첨 | L-02: 적재 120명 85초 실패 0, ENTERED 120건(150매). 발표자 화면 응모 201 | 120명, 85초, 150매 | 직접 계산 약 0.71초/명(85/120). 11-05 로컬 값은 가입 73초, 응모 75초 | 로컬 | 로컬 측정 | docs/design/demo/2026-11-06-mvp-scenarios.md:207, 237 | — | — |
| G-53 | 추첨 | L-03: 시작 전·마감 뒤 409 LOTTERY_NOT_OPEN, 같은 키 재요청은 같은 entryId, 새 키 409 LOTTERY_ALREADY_ENTERED, 같은 키 다른 내용 409 IDEMPOTENCY_KEY_REUSED | 해당 없음 | — | 로컬 | 로컬 측정 | docs/design/demo/2026-11-06-mvp-scenarios.md:208 | — | 멱등 응모 |
| G-54 | 추첨 | L-04: 추첨 4초 안에 DRAWN, WON 25건(30매), WAITLISTED 15건(20매), LOST 82건, 순위 1~40 중복 없음 | 25건/30매, 15건/20매, 82건 | 직접 계산 25+15+82=122(응모 120명과 불일치, 원문 수치를 그대로 옮김. 시연 중 응모 120 + 발표자 1 가능성은 원문에 없어 확인 못 함) | 로컬 추첨 7 | 로컬 측정 | docs/design/demo/2026-11-06-mvp-scenarios.md:209 | — | 모순 후보: 합계 122 대 L-02의 120 |
| G-55 | 추첨 | L-05 1회차 실패: Mock PG 재생성으로 빌링키가 사라져 PAID 2건, FAILED 36건(BILLING_KEY_MISSING 35건, 주입 CARD_DECLINED 1건). 카드 108개 재등록(82초) 후 추첨 7로 재실행해 통과 | PAID 2, FAILED 36 (35+1) | 직접 계산 36/38 = 약 94.7% 실패 | 로컬 추첨 6 | 로컬 측정(실패 후 재실행) | docs/design/demo/2026-11-06-mvp-scenarios.md:163, 210 | — | 빌링키 소실로 첫 리허설 실패 후 반전 |
| G-56 | 추첨 | L-06: 순위 3·5·7이 FAILED·BILLING_KEY_MISSING, 순위 26·27·30이 승계해 PAID, 2매인 28·29는 남은 자리 부족으로 건너뜀, 추첨석 30석 모두 판매 | 30석 | — | 로컬 | 로컬 측정 | docs/design/demo/2026-11-06-mvp-scenarios.md:211 | — | 예비자 승계 |
| G-57 | 추첨 | L-07: PG 거절(CARD_DECLINED)도 같은 경로로 승계 | 해당 없음 | — | 로컬 | 로컬 측정 | docs/design/demo/2026-11-06-mvp-scenarios.md:212 | — | — |
| G-58 | 추첨 | L-08(로컬 DB 시각을 31분 앞당김): 2.5초 뒤 예비 12명 LOST, 22.7초 뒤 DONE, 판매 30석 S·SOLD, 남은 LOTTERY 0석. 승계 마감은 추첨 + 30분 | 2.5초, 22.7초, 12명, 31분 | — | 로컬 DB 조정(UPDATE 1건), 실제 대기 아님 | 로컬 측정(조정) | docs/design/demo/2026-11-06-mvp-scenarios.md:158, 213 | — | 31분 대기를 시각 조정으로 대체 |
| G-59 | 결제 | F-08: holdMinutes=1 회차, 토큰 만료 약 1.5초 전 DELAY 5초 결제. 결제 APPROVED(15:40:35.044 UTC) 뒤 예매 EXPIRED(35.094, 선점 만료 약 1.1초 뒤), 환불 EXPIRED_AFTER_APPROVAL → REFUNDED. 재확인은 202 응답 10.4초 뒤. 토큰 만료 전 약 2.4초 안에 눌러야 함(응답 시한 2초 + 재확인 10.4초 - 토큰 여유 10초, 계산값, 반복 측정 안 함). 결제 토큰은 선점보다 10초 먼저 끝남(EXPIRY_MARGIN) | 1.5초, 1.1초, 10.4초, 2.4초, 10초 여유 | 2 + 10.4 - 10 = 2.4초(원문 계산값) | 예매 60, 로컬 | 로컬 측정 + 계산값 | docs/design/demo/2026-11-06-mvp-scenarios.md:224 | — | 만료 뒤 승인 자동 환불 |
| G-60 | 결제 | F-09: 수동 결제 22건으로 서킷브레이커 재현. 4번째부터 503 PG_UNAVAILABLE(타임아웃 7건 + 3건 = 최근 20회 중 10건), CB open, 해제 11초 뒤 200 APPROVED, 실패 3건은 DECLINED·NOT_SENT로 수렴. 슬랙 미확인 | 22건, 20회 중 10건(50%) | 직접 계산 10/20 = 50% | 로컬 | 로컬 측정 | docs/design/demo/2026-11-06-mvp-scenarios.md:225 | — | PG 장애 차단과 복구 |
| G-61 | 결제 | F-10: 지연 주입 결제 중 docker restart(SIGTERM) 후 13.9초 뒤 APPROVED, 예매 86 CONFIRMED, approveCalls 1, dead_letters 증가 없음. 강제 중단은 재현 안 함 | 13.9초 | — | 로컬 | 로컬 측정 | docs/design/demo/2026-11-06-mvp-scenarios.md:226 | — | 재시작해도 결제 수렴 |
| G-62 | 기타 | 시연 제외: Redis 장애(E11, ISE-59 미완료), 접수형 선점·DLQ 도달(E15, M4), 예매 취소(M4), 성능 수치(기준선 ISE-25 미측정) | 해당 없음 | — | 11/6 시점 | 설계 | docs/design/demo/2026-11-06-mvp-scenarios.md:228 | — | 11/6 시점엔 성능 기준선이 없었다 |
| G-63 | 팀 | 진행은 통합 진행자 1명(판단 기록 있음) | 1명 | — | 2026-11-05 | 판단 | docs/design/demo/2026-11-06-mvp-scenarios.md:3 | — | — |
| G-64 | 기타 | 서버 로컬 로그 이후 시연 장애 패널: 시연 장애 시나리오 4종(결제 거절, PG 응답 지연 4초, 승인 응답 유실, PG 서버 오류 계속). 지연은 "10~20초 뒤 확정"(화면 10초 간격 재조회), 서킷브레이커는 최근 20건 중 절반 넘게 실패해야 열림 | 지연 4초, 10~20초, 20건 중 절반 초과 | — | 설계 가이드(측정 아님) | 시연 | docs/guides/demo-scenarios.md:16-21 | — | 시연 장애 패널 |
| G-65 | 결제 | 장애 패널은 서버 모든 결제에 걸리므로 시연 중 다른 사람 결제 금지 공지, 부하 측정(measure-mode on) 중에는 쓰지 않음 | 해당 없음 | — | 가이드 | 설계 | docs/guides/demo-scenarios.md:10-11 | — | — |
| G-66 | 결제 | 서킷브레이커는 버튼 한 번으로 안 됨: PG 서버 오류 계속 + k6 pay1-payment 시나리오(MOCK_PG_URL= 빈 값으로). 안 비우면 시작 시 승인 지연 300ms를 걸어 패널 장애를 덮어씀. OPEN 후 10초 지나 다음 결제가 오면 반쯤 열림, 시험 호출 3건 성공해야 닫힘 | 지연 300ms, 10초, 시험 호출 3건 | — | 가이드 | 설계 | docs/guides/demo-scenarios.md:27-30 | — | 시연 도구가 시연을 덮어쓰던 함정 |
| G-67 | 결제 | 선점 만료 뒤 승인 자동 환불은 선점 시간이 몇 분이라 시연 중 기다리기 어려워 테스트 결과로 대체 | 해당 없음 | — | 가이드 | 설계 | docs/guides/demo-scenarios.md:31 | — | — |
| G-68 | Gateway | 시연 장애 경로: Gateway config-repo/gateway.yml의 mock-pg-demo 라우트가 /api/v1/admin/demo/pg/faults·/ledger/summary를 Mock PG로 넘김, /api/v1/admin/** 라서 ADMIN만 통과, reset과 결제 API는 열지 않음 | 해당 없음 | — | 설계 | 설계 | docs/guides/demo-scenarios.md:41 | — | — |
| G-69 | 팀 | AI 코드 검토 5겹: L0 기준 고정, L1 작성 세션 자기 검증, L2 다른 눈의 AI 리뷰, L3 CI, L4 배포 환경 확인 | 5겹 | — | 2026-11-13 origin/dev | 설계 | docs/guides/ai-code-review.md:16-24 | — | 다섯 겹 검증 |
| G-70 | 팀 | 필수 승인 대신 관문: 사람 리뷰가 병목이라 CI(build, records-check)를 필수 관문으로 둠. 다섯 명이 서비스 하나씩 담당 | 5명 | — | git-workflow 5절 | 판단 | docs/guides/ai-code-review.md:10 | — | 승인 대신 관문 |
| G-71 | 팀 | 머지된 PR 313개 중 GitHub 리뷰가 남은 PR은 12개(#57, #88, #91, #94, #95, #194, #231, #282, #283, #321, #505, #526). 검토가 로컬 세션에서 일어나 PR 화면에 흔적이 적음 | 313개 중 12개 | 직접 계산 12/313 = 약 3.8% | 2026-11-13 기준 | 계산값(원문 숫자) | docs/guides/ai-code-review.md:14, 119 | — | 흔적이 적다는 한계 인정 |
| G-72 | 팀 | 테스트 규모: Java @Test 1,833개(@ParameterizedTest 52개 별도), 테스트 파일 287개, 시스템 테스트 16개, 프론트 테스트 파일 101개, 결제 계약 테스트 5개 | 1,833 / 52 / 287 / 16 / 101 / 5 | — | 2026-11-13 다시 셈 | 계산값(집계) | docs/guides/ai-code-review.md:6, 64 | — | 테스트 규모 |
| G-73 | 팀 | 변이 확인 사례: Gateway 컨텍스트 테스트는 @Autowired를 빼면 실패(TS-53). 계층 정리 PR #88은 변이 7건을 모두 잡음. 살아남은 변이 예 "6건 중 5건" | 7/7, 5/6 | 7/7 = 100%, 5/6 = 약 83.3%(직접 계산) | 해당 PR | 로컬 측정 | docs/guides/ai-code-review.md:34-35 | — | 일부러 틀리게 바꿔 보기(변이 확인) |
| G-74 | 팀 | 실제로 잡은 결함 1: DB가 멈추면 연결 생존 검사가 5초를 기다려 Gateway 3초 시한을 넘김(HikariCP 생존 검사 시한 기본값은 connection-timeout 1초로 줄지 않음). PR #280, DB docker pause 재확인 3회 모두 2.0초에 503 | 5초 → 2.0초, Gateway 시한 3초 | 직접 계산 5→2.0초 = 60% 단축 | L2 Opus 리뷰, HikariCP·pgjdbc 소스 | 로컬 측정 | docs/guides/ai-code-review.md:90 | — | 리뷰가 소스를 읽어 잡은 결함 |
| G-75 | 결제 | 결함 2: 예매 대사 RC-1~RC-6에 결함 5가지. PR #284, 반영 뒤 payment-service 테스트 758개 통과. RC-5·6은 ADR이 바뀌는 일이라 사용자가 선택지 고름 | 758개 | — | L2 Opus 리뷰 | 로컬 측정 | docs/guides/ai-code-review.md:91 | — | — |
| G-76 | 결제 | 결함 3: 예매 응답(SuccessResponse.data 배열)과 결제 어댑터({"items":…} 전제) 불일치. PR #289에서 맞춤 | 해당 없음 | — | L2 diff 대조 | 판단 | docs/guides/ai-code-review.md:92 | — | 두 PR을 맞대어 본 리뷰 |
| G-77 | 결제 | 결함 4: DLQ 처리기가 DB 연결 실패를 업무 실패로 분류 등. 효승 수정 커밋 3987eb6, 테스트 5개 → 9개 | 5 → 9개 | 직접 계산 1.8배 | L2 | 로컬 측정 | docs/guides/ai-code-review.md:93 | — | — |
| G-78 | 인프라 | 결함 5: 두 PR이 같은 Flyway 버전 V5를 써서 머지 뒤 dev에서 결제 서비스 기동 불가(파일명 달라 충돌로 안 보임). dev build 실패 268건, ISE-155에서 V6으로 이동(TS-31) | 268건 | — | L3 | 로컬 측정 | docs/guides/ai-code-review.md:94 | — | 파일 충돌 없이 터진 마이그레이션 번호 충돌 |
| G-79 | Gateway | 결함 6: Gateway 생성자에 @Autowired 없어 배포 서버 기동 실패, 단위 테스트는 new로 만들어 통과. 컨텍스트 테스트 추가(TS-53) | 해당 없음 | — | L4 배포 롤백, Loki | 배포 측정 | docs/guides/ai-code-review.md:95 | — | 테스트는 통과, 배포에서 실패 |
| G-80 | 인프라 | 결함 7: 경보 규칙 단위 테스트는 통과했지만 Micrometer 태그 job이 수집 라벨과 겹쳐 경보가 울릴 수 없었음. 태그를 task로 변경(TS-37) | 실제 Prometheus 조회 0건 | — | L4 | 배포 측정 | docs/guides/ai-code-review.md:96 | — | 경보가 울리지 않는 경보 |
| G-81 | 결제 | 결함 8: 예매 대사가 배포 규모(변경 약 14만 6천 건)에서 쪽 상한을 넘어 매 회차 실패. 워터마크 이후만 읽는 방식(TS-62, PR #552) | 약 14만 6천 건 | — | L4 Slack 경보, 로컬은 몇 건만 확인 | 배포 측정 | docs/guides/ai-code-review.md:97 | — | 규모에서 드러난 결함 |
| G-82 | 좌석 | 결함 9: 같은 사용자가 탭 4개로 동시 선점하면 한도 4매를 넘어 8매. C4 30라운드 모두 4매 이하, C5 자기 409 0건(TS-65~68). k6 측정은 못 드러내고 동시성 테스트가 잡음 | 8매 → 4매 이하, 30라운드 | — | L1 동시성 테스트 | 로컬 측정 | docs/guides/ai-code-review.md:98, 101 | — | 한도 4매를 8매까지 뚫은 탭 4개 |
| G-83 | Gateway | 결함 10: 관리자 경로를 루트·matrix parameter·인코딩으로 바꾸면 권한 검사 통과. codex 두 에이전트 독립 검토. TS-9 | 해당 없음 | — | L2 codex | 로컬 측정 | docs/guides/ai-code-review.md:99 | — | 권한 우회 경로 |
| G-84 | 팀 | 근거 들어 거부한 AI 제안 7건: 결제 재시도 이어받기(PG 호출 판정 기록 없어 이중 승인 가능), Gateway 동시 처리 상한 64→128, "Gateway 벌크헤드 없음"(바이트코드로 기본 25 확인), 대기열 멱등 근거, 측정용 차단기 완화, 입장 토큰 정리 리스너 유지, JUnit 부하 근사 | 7건 | — | 5절 표 | 판단 | docs/guides/ai-code-review.md:105-113 | — | 거부 사례 모음 |
| G-85 | Gateway | 동시 처리 상한 128 제안 거부 근거: 굿풋 차이 1.5%가 반복 측정 차이 5% 안이고 p95는 128이 약 두 배(162ms 대 87ms) | 64 → 128, 굿풋 +1.5%, 오차 5%, p95 87ms → 162ms | 162/87 = 약 1.86배(직접 계산) | 배포 측정 기록(kangmin 11/11) | 배포 측정 | docs/guides/ai-code-review.md:108 | — | 상한을 올리지 않은 이유 |
| G-86 | Gateway | 기본 벌크헤드(동시 25) 확인: "설정에 없으면 기본값 아니냐"는 반문에서 시작해 바이트코드(javap)로 AI 결론 거부 | 동시 25 | — | TS-56, 2026-11-09 | 판단 | docs/guides/ai-code-review.md:45, 109 | — | 반문으로 AI 결론을 뒤집음 |
| G-87 | 팀 | 공통 판단 표 기준 전체 판단 170건 중 거부 55건, 부분 수용 31건(코드 리뷰만이 아니라 AI 모든 제안 합산) | 170건, 거부 55, 부분 수용 31 | 거부 55/170 = 약 32.4%, 부분 수용 31/170 = 약 18.2%(직접 계산) | 2026-11-13 | 계산값(집계) | docs/guides/ai-code-review.md:115 | — | AI 제안을 수용만 하지 않았다 |
| G-88 | 팀 | AI 리뷰 효과를 재지 않음(결함을 몇 개 줄였는지 미측정). 같은 계열 모델 맹점 한계: codex 판단 기록 16건은 10/23~11/2에 몰리고 11월 구현 리뷰는 대부분 Claude끼리. 자동 구조 검사(ArchUnit) 없음, 변이 테스트 도구(pitest) 없음 | codex 기록 16건 | — | 2026-11-13 | 판단 | docs/guides/ai-code-review.md:120-123 | — | 한계를 숨기지 않기 |
| G-89 | 팀 | script/ai-review.sh 시험(2026-11-13, PR #577): Claude(Opus) 2회(각 약 3분 30초) 모두 같은 중간 결함(커밋 뒤 정리에서 잡는 예외 범위), codex 1회(55초)는 그 결함은 안 짚고 ADR 0053과 계약 문서 불일치를 "확인 필요"로 냄 | 3분 30초 x2, 55초 | 3.5분/55초 = 약 3.8배(210/55, 직접 계산) | 2026-11-13, PR #577 | 로컬 측정 | docs/guides/ai-code-review-prompt.md:3 | — | 모델마다 보는 곳이 다르다 |
| G-90 | 팀 | 다른 조 동시성 체크리스트에서 세 가지(잠근 뒤 상태 재확인, 멱등하지 않은 요청의 재시도, @Transactional 오용)와 살아남은 변이 지적을 프롬프트에 더함. 더한 뒤로는 돌려 보지 않음 | 3가지 | — | 2026-11-13 | 판단 | docs/guides/ai-code-review-prompt.md:5, 20-24 | — | 남의 체크리스트에서 배워 오기 |
| G-91 | 팀 | CodeRabbit 같은 리뷰 봇은 쓰지 않음(개발자별 시간당 한도로 발표 전 PR 몰리면 리뷰 누락, 우리 설계 문서·실패 경로를 모르는 일반 리뷰) | 해당 없음 | — | 2026-11-13 | 판단(거부) | docs/guides/ai-code-review.md:135; docs/guides/ai-code-review-prompt.md:5 | — | 봇을 거부한 이유 |
| G-92 | 팀 | 리뷰 프롬프트 출력 형식 3절(요약, 지적, 확인했고 문제 없는 것), 지적마다 심각도·위치·근거·확인 방법·제안. 직접 실행 못 한 검증은 "확인하지 못함" | 3절, 5항목 | — | 프롬프트 | 설계 | docs/guides/ai-code-review-prompt.md:29-38 | — | — |
| G-93 | 팀 | 작성자 답변 규칙: 수용·부분 수용·거부·보류 + 근거, 반영 커밋 SHA, 높음·중간 반영 시 같은 리뷰 재실행. 거부는 개별 기록 | 해당 없음 | — | 프롬프트 | 설계 | docs/guides/ai-code-review-prompt.md:40-47 | — | — |
| G-94 | 팀 | 7절 제안(팀 동의 전): PR마다 AI 리뷰 결과를 PR 코멘트로, 계약 경계 PR은 상대 담당 승인. 정할지는 ISE-271 | 해당 없음 | — | 제안 | 설계(제안) | docs/guides/ai-code-review.md:126-140 | — | — |
| G-95 | 팀 | 코드 스타일 대전제: 줄 수보다 읽는 흐름, 메서드 최대 줄 수 일률 기준 없음, 주석 Note:는 필요한 지점에만(수·비율 목표 없음), Lombok은 모든 모듈에서 제한 없이 가능(2026-10-27 통합 회의 D13) | 해당 없음 | — | 가이드 | 설계 | docs/guides/code-style.md:6-16, 33-62, 84 | — | — |
| G-96 | 인프라 | 공통 모듈: 모듈 하나(common), 패키지 com.iseonjwa(ADR 0013·0014, 2026-10-27 통합 회의). 서비스는 implementation project(':common') 하나만 의존, Gateway(WebFlux)가 MVC·JPA·Kafka를 받으면 기동 못 하므로 compileOnly, 바꾸면 GatewayClasspathTest 실패 | 해당 없음 | — | 가이드 | 설계 | docs/guides/common-modules.md:3, 7 | — | 모듈 하나로 정한 이유 |
| G-97 | 인프라 | 응답 형식(SA MASTER v3.1 #19): 성공 {status, message, data, traceId}, 실패 {status, code, message, errors, traceId}. 오류 응답은 Accept와 무관하게 application/json | 해당 없음 | — | 2026-10-27 정렬 | 설계 | docs/guides/common-modules.md:3, 29 | — | — |
| G-98 | 인프라 | 소비 재시도·DLQ: 일반 소비자는 1·2·4초 간격 3회 재시도 뒤 `<토픽>.dlq`. DLQ 처리기는 1초에서 2배씩 최대 60초 간격 무제한 재시도, 오프셋 커밋 안 함. 전역 CommonErrorHandler 빈은 만들지 않음(DLQ 처리기가 건너뛸 수 있어서) | 1·2·4초 x3회, 최대 60초 | — | 가이드 | 설계 | docs/guides/common-modules.md:68-75 | — | 전역 에러 핸들러를 안 만든 이유 |
| G-99 | 인프라 | Outbox 릴레이 설정: batch-size 20, lease 30s, send-timeout 12s, interval 500ms. lease는 send-timeout의 2배 이상, 프로듀서 max.block.ms + delivery.timeout.ms(결제 2초 + 10초)는 send-timeout 안(어긋나면 기동 실패). 재시도 백오프 min(500ms × 2^(k-1), 60s)의 50~100%(equal jitter) | 20, 30s, 12s, 500ms, 2+10초 | 직접 계산 12 x 2 = 24초 ≤ 30초 lease 조건 충족 | 가이드 | 설계 | docs/guides/common-modules.md:114-115 | — | 시한 숫자를 서로 맞물리게 검증 |
| G-100 | 인프라 | Outbox 영구 오류만 FAILED(SerializationException, RecordTooLargeException, RecordBatchTooLargeException, InvalidTopicException), 나머지는 일시 오류로 무한 재시도. FAILED는 같은 키 뒤 행을 막음 | 4종 예외 | — | 가이드 | 설계 | docs/guides/common-modules.md:115 | — | 재시도할 실패와 포기할 실패 분류 |
| G-101 | 인프라 | 지표 주의: outbox_relay_last_success_timestamp는 릴레이 스레드 생존 신호일 뿐 Kafka가 죽어도 갱신됨. 브로커 장애는 DB 집계(PENDING 최고령)로 봄 | 해당 없음 | — | 가이드 | 설계 | docs/guides/common-modules.md:117 | — | 정상 신호가 거짓일 수 있는 지표 |
| G-102 | 인프라 | 보관 정리 RetentionCleanup: SENT이고 sent_at < cutoff만 삭제, PENDING·FAILED 안 지움, pg_try_advisory_xact_lock으로 다중 인스턴스 보호 | 해당 없음 | — | 가이드 | 설계 | docs/guides/common-modules.md:119 | — | — |
| G-103 | 인프라 | outbox 릴레이·처리 이력은 PostgreSQL 문법(SKIP LOCKED, RETURNING, ON CONFLICT)이라 common의 H2 테스트로는 검증 못 하고 서비스 PostgreSQL 통합 테스트로 확인 | 해당 없음 | — | 가이드 | 설계 | docs/guides/common-modules.md:120 | — | — |
| G-104 | 팀 | contract 이슈 방식: 2026-10-30에 결제 담당이 처음 쓴 방식(ISE-73~77)을 공통 규칙화. 제목 `contract(보내는 쪽→받는 쪽): 요약`, 담당은 상대 서비스 담당자, 작업 전 나에게 배정된 열린 contract 확인 | ISE-73~77 | — | 2026-10-30 | 설계 | docs/guides/contract-issues.md:3, 37-39, 68-75 | — | 접점 변경을 이슈로 알리기 |
| G-105 | 팀 | 접점 5종: 이벤트, 토큰, 내부 API, 공유 설정, 정책 확인. 정본은 저장소 문서, 이슈는 알림·추적용 | 5종 | — | 가이드 | 설계 | docs/guides/contract-issues.md:9-15, 21-26 | — | — |
| H-01 | 인프라 | 배포 서버는 m7i.xlarge 1대에 앱·DB·Redis·Kafka·관측 도구 컨테이너 20개가 올라간다 | 컨테이너 20개 | — | 배포 서버 52.63.10.174, 2026-11-05 기준 문서 | 배포 측정(구성 사실) | docs/guides/deploy-server-access.md:9 | — | 한 대 서버에 20개 컨테이너를 얹은 구성 소개 |
| H-02 | 인프라 | Jenkins가 main을 2분마다 확인해 새 커밋이면 자동 배포 | 2분 간격 | — | Jenkins 서버 t3.small | 설계 | docs/guides/deploy-server-access.md:10, :158, docs/guides/loadgen.md:34 | — | main 머지 → 2분 안에 배포되는 CI/CD |
| H-03 | 인프라 | 접속 주소가 https://52.63.10.174/ 로 바뀜(2026-11-16, ISE-278), 인증서는 약 6일마다 자동 갱신 | 갱신 주기 약 6일 | — | 2026-11-16부터 | 설계 | docs/guides/deploy-server-access.md:12 | — | HTTPS 적용(발표 직전 마무리) |
| H-04 | 인프라 | Grafana는 로그인 없이 보기 전용으로 누구에게나 공개(시연·외부 심사용, 2026-11-16), 3000번은 더 열지 않음 | — | — | https://52.63.10.174/grafana/ | 설계/판단 | docs/guides/deploy-server-access.md:53 | — | 심사위원이 직접 Grafana를 보게 한 결정 |
| H-05 | 인프라 | Swagger UI는 터널 없이 https://52.63.10.174/api/docs 로 열림(ADR 0063) | — | — | — | 설계 | docs/guides/deploy-server-access.md:66, docs/guides/workspace.md:128 | — | API 문서 공개 |
| H-06 | 인프라 | 서버 가동 일정: 11/6 리허설까지 가동, 11/7~11/12 중지, 11/13~11/19 재가동 후 삭제 | 중지 6일(11/7~11/12) | — | 계획(안) | 설계 | docs/guides/deploy-server-access.md:13-15, :160-167 | — | 비용 관리하며 일정에 맞춰 서버를 껐다 켠 운영 |
| H-07 | 인프라 | 관리 화면(Kafka-UI, Prometheus, Alertmanager, Zipkin)은 포트를 서버 밖에 열지 않고 SSH 터널로만 접근 | 포트 8085/9090/9093/9411 | — | 배포 서버 | 설계 | docs/guides/deploy-server-access.md:55-70 | — | 외부 노출 포트 최소화(보안그룹+SSH 터널) |
| H-08 | 인프라 | 배포 실패 시 deploy.sh가 새 버전이 헬스·스모크를 통과하지 못하면 직전 성공 커밋으로 스스로 되돌린다 | — | — | Jenkins deploy-main 잡 | 설계 | docs/guides/deploy-server-access.md:84-88 | — | 자동 롤백 배포 파이프라인 |
| H-09 | 인프라 | GHCR 이미지가 20분 안에 생기지 않으면 배포 중단 알림(회색) | 20분 | — | — | 설계 | docs/guides/deploy-server-access.md:89 | — | 배포 알림 4종(주황/회색/빨강) |
| H-10 | 인프라 | 2026-11-12 옛 이미지가 쌓여 서버 디스크 100%로 배포 실패(TS-60), 이후 deploy.sh가 받기 전 옛 이미지를 정리 | 디스크 100% | — | 2026-11-12 | 배포 측정(사고) | docs/guides/deploy-server-access.md:102 | — | 디스크 100% 배포 실패 트러블슈팅 TS-60 |
| H-11 | 인프라 | 롤백 시 TAG는 40자 SHA만 허용(7자 SHA는 거부) | 40자 | — | Jenkins 수동 재배포 | 설계 | docs/guides/deploy-server-access.md:104 | — | — |
| H-12 | 인프라 | 스키마(Flyway) 변경은 코드 되돌려도 되돌려지지 않음 | — | — | — | 설계/주의 | docs/guides/deploy-server-access.md:112 | — | 롤백의 한계 |
| H-13 | 인프라 | 서버에서 금지: docker compose down -v, 직접 deploy.sh, .env 열기, authorized_keys 수정, 배포 서버에서 k6 실행 | — | — | 예외는 측정 날 measure-mode.sh on/off | 설계 | docs/guides/deploy-server-access.md:117-125 | — | 서버 운영 규칙 |
| H-14 | 인프라 | k6는 배포 서버에서 돌리지 않고 같은 VPC·같은 가용 영역의 팀 공용 발생기 EC2(iseonjwa-loadgen, 2026-11-09 생성)에서 돌린다 | — | — | ADR 0046 | 설계 | docs/guides/deploy-server-access.md:146 | — | 부하 발생기를 분리해 측정 왜곡 제거 |
| H-15 | 인프라 | 한국-시드니 왕복이 약 0.27초라 노트북에서 큰 부하를 쏘면 결과에 네트워크가 섞인다 | RTT 약 0.27초 | — | — | 판단(근거 수치는 문서 기재) | docs/guides/deploy-server-access.md:156, docs/guides/loadgen.md:42 | — | 측정 환경 선택(노트북 vs 같은 VPC 발생기) |
| H-16 | 인프라 | 발생기에서는 Gateway 사설 주소 172.31.4.93:8080을 직접 부르고 프론트 nginx는 거치지 않음 | — | — | 측정 중에만 보안그룹 8080·9090 개방 | 설계 | docs/guides/deploy-server-access.md:149-151 | — | — |
| H-17 | 인프라 | 공인 IP로 보내면 GB당 0.01달러가 양방향으로 붙어 1회 약 1달러, 노트북 경유는 2회 만에 월 무료 100GB 소진 후 1회 약 5.7달러 | 사설 0원 / 공인 1회 약 1달러 / 노트북 1회 약 5.7달러 | 약 5.7 ÷ 1 = 약 5.7배(직접 계산, 문서 수치 기준) | AWS 담당 안내 11/9, K1 응답 약 830KB × 초당 100건 × 10분 | 계산값(AWS 담당 안내) | docs/guides/loadgen.md:36-42 | — | 요금 때문에 사설 IP로만 측정한 이유 |
| H-18 | 좌석 | K1 응답이 약 830KB라 초당 100건으로 10분이면 응답만 약 50GB | 830KB, 초당 100건, 10분, 약 50GB | 0.83MB × 100 × 600 = 약 49.8GB(직접 계산) | K1 시나리오 | 계산값 | docs/guides/loadgen.md:36 | — | 좌석 조회 응답이 무겁다는 증거(P4 좌석 조회 경량화의 동기) |
| H-19 | 좌석 | K1(초당 100명 입장 → 좌석 조회 → 선점) 측정은 로컬 스택(배포 메모리·G1 덮어쓰기)에서 진행, G1 GC로 한계가 초당 5명 → 10명 | 한계 초당 5명 → 10명(G1 적용) | 10 ÷ 5 = 2배(직접 계산) | 최권우 ISE-79, 로컬 스택, 문서 2026-11-05 기준 | 로컬 측정 | docs/guides/deploy-server-access.md:140 | — | GC 선택(G1)만으로 한계가 2배가 된 반전 |
| H-20 | 좌석 | 다음 개선은 좌석 조회 경량화 P4(반효승, ISE-189) | — | — | — | 계획 | docs/guides/deploy-server-access.md:140 | — | 좌석 조회 경량화 |
| H-21 | 인프라 | 발생기 사양: c6i.xlarge, 4 vCPU, 8GiB, 네트워크 기준 1.562Gbps, Ubuntu 24.04, 디스크 20GB | 4 vCPU / 8GiB / 1.562Gbps / 20GB | — | 11/9 기준 | 설계 | docs/guides/loadgen.md:14 | — | 발생기 스펙 |
| H-22 | 인프라 | 발생기 설치물: Docker 29.9.3, Compose v5.7.0, grafana/k6:1.0.0(k6 v1.0.0, go1.24.2) | — | — | — | 배포 측정(환경 기록) | docs/guides/loadgen.md:20 | — | — |
| H-23 | 인프라 | 시계 동기: chrony가 Amazon Time Sync Service에 맞추며 11/9 오차 발생기 1.2µs, 배포 서버 0.13µs | 1.2µs / 0.13µs | 1.2 ÷ 0.13 = 약 9.2배(직접 계산) | 2026-11-09 | 배포 측정 | docs/guides/loadgen.md:21 | — | 두 서버 시계 오차가 마이크로초라 지연 측정이 믿을 만하다는 근거 |
| H-24 | 인프라 | 측정은 한 번에 한 명, 시작·끝 팀 채널 알림, 측정 모드를 켜면 기존 로그인이 끊김, 측정 중 main 머지 금지(배포가 돌면 측정 모드가 풀림) | — | — | — | 설계/운영 | docs/guides/loadgen.md:25-34, docs/guides/deploy-server-access.md:181 | — | 측정 오염을 막는 협업 규칙 |
| H-25 | 인프라 | 측정 모드는 측정 전용 서명 키로 바꿔 띄우고 k6가 그 키로 직접 서명, 운영 키·관리자 비밀번호는 발생기에 두지 않음(ADR 0046 변경) | — | — | 정함 2026-11-08 | 설계/판단 | docs/guides/deploy-server-access.md:170, docs/guides/loadgen.md:31 | — | 운영 키를 부하 도구에 주지 않은 보안 판단 |
| H-26 | 인프라 | k6 결과는 Prometheus remote write로 Grafana에서 보고 요약 JSON·CSV도 남김(정함 2026-11-08) | — | — | — | 설계 | docs/guides/deploy-server-access.md:172 | — | — |
| H-27 | 인프라 | 연기 테스트 결과(11/9, 측정 모드 꺼짐): 요청 40건 모두 200, http_req_failed 0%, 중앙값 9.91ms, p95 16.35ms, 최대 24.43ms | 40건, 중앙값 9.91ms, p95 16.35ms, 최대 24.43ms | — | 공연 목록 GET, VU 2개 20초(초당 약 2건), 발생기 → 사설 IP 8080 | 배포 측정 | docs/guides/loadgen.md:119-134 | — | 같은 서브넷이라 네트워크 지연이 거의 없다는 기준선 |
| H-28 | 인프라 | 발생기 사용 시작·끝 알림 문구와 측정 모드 on/off 슬랙 자동 알림(ISE-223/224/228) 체계 | — | — | — | 설계 | docs/guides/loadgen.md:71, :77-82, :90, :94-99 | — | 측정 협업 프로토콜 |
| H-29 | 인프라 | k6 stdout 진행 표시줄(K6_PROGRESS=1)은 실행 한 번에 토큰 약 1만 개를 소모(ISE-219) | 약 1만 토큰/회 | — | 에이전트 규칙 | 배포 측정(관찰) | docs/guides/loadgen.md:157 | — | AI 에이전트 토큰 비용 최적화 |
| H-30 | 인프라 | 11/9 비대화형 SSH가 거부로 나와 서버에 키가 없다고 오판한 사례 | — | — | 키에 암호가 있으면 비대화형 SSH 실패 | 판단(오판 사례) | docs/guides/loadgen.md:154 | — | AI 오판 사례(11/9) |
| H-31 | 인프라 | 관리자 계정이 필요한 시나리오 L1·L2·B1·G1·PAY1·R1·X1·L3·O1은 배포에서 아직 안 돔(adminLogin 필요, 운영 관리자 비밀번호를 발생기에 두지 않는 규칙 때문) | 시나리오 9종 | — | 11/9 기준 | 설계/한계 | docs/guides/loadgen.md:161 | — | 안전 규칙과 측정 편의의 충돌 |
| H-32 | 팀 | 커밋 형식 type(scope): 요약, 요약은 50자 안쪽 한국어 평서형, AI 공동 작성자 표기 금지 | 50자 | — | — | 설계 | docs/guides/git-workflow.md:5-23 | — | — |
| H-33 | 팀 | 브랜치 전략(2026-10-27 통합 회의 확정): dev에서 분기, squash 기본, 필수 승인 0명이지만 CI(build, records-check) 통과 필수, main·dev 직접 push 금지, 마일스톤마다 dev → main | 필수 승인 0명 | — | 2026-10-27 | 설계/결정 | docs/guides/git-workflow.md:3, :43-52 | — | 리뷰 대신 CI를 자동 관문으로 둔 결정 |
| H-34 | 팀 | 2026-11-02 점검(ISE-141)에서 GitHub PR 대부분의 담당자·라벨·마일스톤이 비어 있었고 GitHub 마일스톤이 아예 없었음 | — | — | 2026-11-02 | 배포 측정 아님(운영 점검) | docs/guides/git-workflow.md:56 | — | 메타데이터 누락 발견 → 기준 정비 |
| H-35 | 팀 | Linear 이슈는 GitHub로 동기화되나 마일스톤은 안 따라옴, 머지하면 연결 이슈가 자동 Done(11/2 ISE-85~93 확인), 부분 완료로 자동 Done된 경우 후속 이슈를 만들어 옮김(ISE-88) | ISE-85~93 | — | 2026-11-02 | 판단/운영 | docs/guides/git-workflow.md:62, :90, :93 | — | Linear-GitHub 연동 동작 확인 |
| H-36 | 팀 | 마일스톤 4개: M1 공통 모듈+인증기반, M2 일반 예매 한 흐름 연결, M3 MVP 완성·배포, M4 고도화 | 4개 | — | — | 설계 | docs/guides/git-workflow.md:72 | — | 일정 마일스톤 |
| H-37 | 기타 | 테스트는 Java·JUnit로 관리, Python 검증(script/smoke-workspace.py 등)은 제거하고 Java 시스템 테스트로 이관 | — | — | — | 설계 | docs/guides/testing.md:1-4, :54-72 | — | Python 스모크를 JUnit 시스템 테스트로 이관한 리팩터링 |
| H-38 | 기타 | 기본 검사 ./gradlew verifyWorkspace --no-daemon: 포트 검사, 격리 테스트 인프라, 전체 빌드·JUnit, 기록·diff 검사 | — | — | CI도 같은 태스크 | 설계 | docs/guides/testing.md:22-29, docs/guides/workspace.md:149 | — | 한 명령 검증 관문 |
| H-39 | 좌석 | 일반 예매 한 흐름 시스템 테스트(BookingFlowSystemTest)는 회차마다 좌석 1,500석(ADR 0060 전에는 15,000석)이 생김 | 15,000석 → 1,500석 | 1,500 ÷ 15,000 = 0.1배(1/10)(직접 계산) | ADR 0060 이후 | 설계 | docs/guides/testing.md:39 | — | 좌석 수를 1/10로 줄인 결정(ADR 0060) |
| H-40 | 결제 | payment 테스트 DB 연결 한도를 100 → 300으로 올림(기본 100에서는 전체 실행 중 too many clients 발생, 컨텍스트별 커넥션 풀 때문) | 100 → 300 | 3배(직접 계산) | compose.test.yml postgres-payment | 로컬 측정(테스트 실패 재현) | docs/guides/testing.md:94 | — | 테스트 DB too many clients 트러블슈팅 |
| H-41 | 결제 | 결제 장애 시나리오 테스트(FT-1·FT-2, 검증기 V1~V3)가 실제 Mock PG 프로세스와 테스트 PostgreSQL로 돔 | — | — | payment-service scenario 패키지 | 설계 | docs/guides/testing.md:88-92 | — | 장애 주입 시나리오 테스트 |
| H-42 | 기타 | 시스템 테스트 옵션: -PcoldStart=true(Gateway 재기동 첫 요청), -PredisFailure=true(Redis pause·복구) | — | — | local 스택 | 로컬 측정 | docs/guides/testing.md:45-52, docs/guides/workspace.md:157 | — | Redis 장애 주입 테스트 |
| H-43 | 인프라 | 업무 서비스 7개 앱 포트: config-server 8888, eureka 8761, gateway 8080, user 8081, reservation 8082, payment 8083, mock-pg 8090(관리 포트 local 19888/18761/18080/18081/18082/18083/18090, 컨테이너 내부 관리 포트 공통 9080) | 앱 7개 | — | — | 설계 | docs/guides/workspace.md:11-21 | — | 서비스 구성도 |
| H-44 | 인프라 | 기술 스택: Java 21, Gradle Wrapper 9.1.0, Spring Boot 4.1.2, Spring Cloud 2025.1.4 | — | — | — | 설계 | docs/guides/workspace.md:7 | — | 버전 정보 |
| H-45 | 인프라 | 서비스 포트 비공개 전제: 서비스는 Gateway가 넣은 X-User-Id·X-User-Role을 서명 없이 믿으므로 Gateway 외 포트가 외부에 닿으면 사용자·ADMIN 위조 가능 → checkPortExposure가 CI에서 검사 | — | — | SA 7-2, 서비스 직접 검증·비대칭 서명은 MVP 이후로 미룸(ADR 0008) | 설계/판단 | docs/guides/workspace.md:27-33, :107 | — | 신뢰 모델의 한계를 인정하고 포트 비공개로 방어 |
| H-46 | Gateway | 인증 필요 여부·관리자 경로 판정은 AccessPolicy.of 한 곳, 라우터와 같은 PathPattern으로 판정해 matrix parameter·인코딩 변형도 방어, 요청마다 정규식·목록을 만들지 않음 | — | — | — | 설계 | docs/guides/workspace.md:105 | — | 관리자 경로 우회 차단 |
| H-47 | 기타 | 인증 토큰: Access 30분, Refresh 14일, HS256 두 키 분리, issuer iseonjwa-auth, Refresh는 사용자당 1개 SHA-256 해시를 Redis auth:refresh:{userId}에 저장 | 30분 / 14일 | — | — | 설계 | docs/guides/workspace.md:97, :101 | — | — |
| H-48 | 기타 | 로그인 5회 연속 실패 시 계정 잠금(423), 실패 횟수는 PostgreSQL UPDATE 한 문장으로 기록(동시 시도에도 정확, Redis 유실에도 안 풀림). 한계: 남의 이메일로 5회 실패시켜 잠글 수 있음 | 5회 | — | auth.max-login-failures | 설계/한계 | docs/guides/workspace.md:113-118 | — | 동시성 정확성을 위해 DB 원자 갱신 선택 |
| H-49 | Gateway | Redis 인증 조회는 100ms 타임아웃과 Resilience4j 서킷, 장애·OPEN이면 503과 Retry-After(ADR 0005 fail-closed 잠정 구현) | 100ms | — | — | 설계 | docs/guides/workspace.md:120 | — | Redis 느려짐 방어(fail-closed) |
| H-50 | Gateway | Gateway는 준비 완료 전에 Redis PING으로 실제 연결을 예열: 시도당 2초, 최대 3회, 재시도 간격 200ms, PONG 못 받으면 기동 실패 | 2초 × 3회, 200ms | — | — | 설계 | docs/guides/workspace.md:122 | — | 첫 요청 지연(콜드 스타트) 방지 |
| H-51 | 인프라 | 추적 샘플링: local 100%, loadtest 1%, prod 10% | 100% / 1% / 10% | — | config-repo | 설계 | docs/guides/workspace.md:140 | — | 관측 비용 조절 |
| H-52 | 인프라 | Loki·Alloy가 앱 7개 컨테이너 로그를 7일 보관, 요청 로그는 로컬 모든 요청, 배포는 5xx·끊긴 요청·200ms 이상만 남김(ADR 0052) | 7일, 200ms | — | — | 설계 | docs/guides/workspace.md:136 | — | 로그 양 조절 |
| H-53 | 인프라 | 대시보드는 요청률·5xx·JVM 메모리·인증 Redis 서킷을 보여 주고, 메모리 제한은 로컬 기동용이며 운영 수용량을 뜻하지 않음 | — | — | — | 설계 | docs/guides/workspace.md:140 | — | — |
| H-54 | 기타 | 문서 작성 규칙: 사실 기반, 확인 못 한 것은 미확인으로 표기, 수치는 단위와 함께, 300줄 넘으면 분리 | 300줄 | — | — | 설계 | docs/guides/writing.md:6-30 | — | 팀 문서 품질 규칙 |
| H-55 | 팀 | SA 1차 검토(2026-10-23): codex와 Claude 에이전트가 각자 검토 후 종합, 발제문 인용을 원문과 대조 | 리뷰어 2 | — | 2026-10-23 | 판단 | docs/sa-review/01-sa-1st-review.md:3-9 | — | AI 교차 검토 방식 |
| H-56 | 팀 | 두 리뷰어 모두 Lottery는 Reservation에 병합하라고 판정, Queue는 의견이 갈림(codex는 모듈로 강등 3개 구성 / Claude는 조건부 유지 4개 구성), 종합 권고는 4개 서비스(User/Queue/Reservation+추첨/Payment) | 서비스 5개 → 권고 4개(codex 3개) | — | 2026-10-23 | 판단(AI 제안) | docs/sa-review/01-sa-1st-review.md:13-14, :22-28, :217-219 | — | 서비스 경계 재검토(5 → 4) |
| H-57 | 추첨 | Lottery 병합 근거: SA 진행 순서가 응모 → 마감 → 추첨·자동결제 → 일반 예매 오픈이라 추첨과 실시간 선점은 시간이 겹치지 않음. 분리 비용으로 3서비스 추첨 SAGA, Lottery DB, 이벤트 계약 2쌍이 생김 | 이벤트 계약 2쌍 | — | — | 판단(AI 제안) | docs/sa-review/01-sa-1st-review.md:32-38 | — | Lottery 분리 근거가 진행 순서와 모순 |
| H-58 | 대기열 | Queue 조건부 유지: DB 없음, SAGA 불참, Reservation은 Queue를 호출하지 않고 서명 토큰만 검증하므로 분리 비용이 가장 낮음. 유지 조건: 이유를 독립 확장+입장 관문으로, 전용 Redis 또는 격리 주장 제거, 인스턴스 여러 개면 입장 속도를 Redis Lua 전역 카운터로 제어 | — | — | — | 판단(AI 제안) | docs/sa-review/01-sa-1st-review.md:40-50 | — | 서명 토큰이라 상태 없는 대기열 |
| H-59 | 팀 | AI 검증 로그 #0 수정 필요: 거부 사례 근거가 Lottery 분리 논리라 발표 때 먼저 반박당할 지점. 4개로 바꾸면 3개 권고 거부+4개 절충안 수용으로 재작성 가능 | — | — | 발제: AI 활용 부분도 랭킹 반영 | 판단 | docs/sa-review/01-sa-1st-review.md:52-55 | — | AI 제안 거부·수용 사례(발표 대표 사례) |
| H-60 | 결제 | C1: PG 타임아웃은 실패가 아니라 결과 모름이므로 UNKNOWN 상태 도입, 멱등키(reservationId)로 Mock PG에 결과 조회 후 확정, 목표 표의 "타임아웃 시 자동 원복"은 위험 | — | — | 두 리뷰어 공통 | 판단(AI 제안) | docs/sa-review/01-sa-1st-review.md:59-67 | — | PG 타임아웃을 실패로 보면 돈은 빠지고 좌석은 다른 사람에게 팔리는 반전 |
| H-61 | 결제 | PAYING 예매는 만료 스케줄러에서 제외하고 별도 결제 마감 시한을 두며, 만료 뒤 승인이 도착하면 보상은 좌석 복구가 아니라 자동 환불 | — | — | — | 설계(제안) | docs/sa-review/01-sa-1st-review.md:64 | — | — |
| H-62 | 팀 | C2: 발제 필수 항목(Kafka 비동기 접수, Outbox·멱등, CB, Grafana, Synthetic Data, AWS 배포)이 P1에 있고 그 P1에 쓸 수 있는 시간은 트러블슈팅 기간 11/9~16 6영업일뿐. 반대로 빌링키 자동결제·추첨·취소/환불 SAGA가 P0 | 6영업일 | — | 2026-10-23 | 판단(AI 제안) | docs/sa-review/01-sa-1st-review.md:69-80 | — | 우선순위가 거꾸로였다는 발견 |
| H-63 | 좌석 | C3: UNIQUE(seat_id)는 취소·만료 뒤 재판매를 막으므로 부분 유니크 인덱스 UNIQUE(schedule_id, seat_id) WHERE status IN ('HELD','PAYING','SOLD') 또는 UPDATE ... WHERE status='AVAILABLE' 영향 행 수 검사. 정본은 DB, 만료는 DB 스케줄러(FOR UPDATE SKIP LOCKED) | — | — | — | 설계(제안) | docs/sa-review/01-sa-1st-review.md:82-90 | — | 부분 유니크 인덱스로 중복 판매 방어 |
| H-64 | 좌석 | M1: Kafka 파티션 키는 회차 ID(핫 파티션)·좌석 ID(원자성 깨짐) 모두 부적합, 파티션 순서를 정합성 수단으로 쓰지 않고 키 userId/reservationId는 부하 분산·사용자 순서에만 사용, 접수 API는 DB 쓰기가 없어 Outbox가 맞지 않으므로 acks=all+idempotent producer | — | — | 두 리뷰어 공통 | 판단(AI 제안) | docs/sa-review/01-sa-1st-review.md:94-104 | — | Kafka 순서에 정합성을 맡기지 않는다는 판단 |
| H-65 | 좌석 | Claude 제안 혼합형: Redis Lua로 여러 좌석을 원자적으로 선점해 즉시 결과 반환 → 성공분만 Kafka 적재 → Consumer가 DB 기록(DB 제약이 2차 방어) | — | — | — | 설계(제안) | docs/sa-review/01-sa-1st-review.md:104, docs/sa-review/03-seat-hold-kafka-design.md:109-157 | docs/sa-review/assets/seat-hold-kafka.html(문서에서 참조, 이 담당 범위에서는 열어 보지 않음) | 개선 전/후 스토리: 동기 DB UPDATE → 혼합형 |
| H-66 | 대기열 | M3: 토큰 발급(F3-3)은 P0인데 입장 토큰 없는 요청 차단(H3)은 P1이라 MVP에서 선점 API 직접 호출 가능. 서명 토큰은 무상태라 한 명 나가면 다음 입장은 불가능하고 일정 속도 입장만 가능. 최대 동시 활성 인원 = N × T | N × T | — | — | 판단(AI 제안) | docs/sa-review/01-sa-1st-review.md:116-121 | — | 매크로가 프론트를 거치지 않고 API를 직접 부르므로 보안문자는 서버 검증 필요 |
| H-67 | 팀 | M4: 같은 좌석 동시 요청 중 1건만 성공과 오류율 0%가 부딪침 → 409(업무 거절)와 5xx·타임아웃을 구분. 발제 필수 설정 1초 안에 모두 시작·요청 당 평균 응답 시간이 목표에서 빠짐. PG 장애 중에도 조회·대기열 정상은 구조상 당연히 참이라 무의미 | — | — | — | 판단(AI 제안) | docs/sa-review/01-sa-1st-review.md:123-134 | — | 성공 기준을 업무 거절과 오류로 나눈 정의 |
| H-68 | 팀 | M5: 트랙 A 책임자 없음(전부), 프론트 담당 없음, 트랙 D·E·B 쏠림. 10/29까지 compose 한 번으로 Gateway → 서비스 1개 → Kafka → Prometheus가 뜨는 걷는 뼈대 제안 | 10/29 | — | — | 판단(AI 제안) | docs/sa-review/01-sa-1st-review.md:136-149 | — | 걷는 뼈대(Walking Skeleton) |
| H-69 | 추첨 | M6: 예비 승계 규칙·연석·결제 실패 유형별 재시도 규칙 등이 비어 있음. Claude 1차 단순화안: 등급별 응모, 1~2매 고정, 연석 미보장, 같은 등급·매수 예비자만 승계, 승계 마감은 일반 오픈 30분 전, 1인 1응모는 UNIQUE(lottery_id, user_id) | 승계 마감 30분 전 | — | — | 설계(제안) | docs/sa-review/01-sa-1st-review.md:151-159 | — | 추첨 범위 축소 |
| H-70 | 결제 | M7: Outbox는 각 서비스 업무 변경과 같은 DB 트랜잭션에 넣어야 하며 Payment 담당이 공통 모듈을 만들어도 Reservation 트랜잭션에 자동 적용되지 않음. CB는 동시 실행 수를 제한하지 않으므로 Bulkhead 조합 필요 | — | — | codex | 판단(AI 제안) | docs/sa-review/01-sa-1st-review.md:161-167 | — | — |
| H-71 | 팀 | 발제 대비 누락 표: AWS 지원금 구글폼(10/26 16:00 기한, 11/10 입금), Kafka-UI, 파티션 근거, README, 서비스별 DB 분리, 시연 시나리오, SAGA 로그 캡처, 1초 Ramp-up, Synthetic Data, 자원 지표, 발표 일정(11/16~18 정리·브로셔, 11/19 발표) 12항목 | 12항목 | — | 2026-10-23 | 판단 | docs/sa-review/01-sa-1st-review.md:171-184 | — | — |
| H-72 | 인프라 | 과잉 지적: Config 서버는 발제상 선택, Eureka도 Gateway 직접 라우팅 가능. 로컬에서 JVM 9개(서비스 5+Gateway+Eureka+Config+Mock PG)에 Kafka·PostgreSQL·Redis·Prometheus·Grafana, 발제 권장 Docker 메모리 4GB+ | JVM 9개, 4GB+ | — | 2026-10-23 | 판단 | docs/sa-review/01-sa-1st-review.md:189-191 | — | 서비스 5개일 때의 로컬 자원 우려 |
| H-73 | 팀 | 1차 피드백 전 할 일 체크리스트 6개(2026-10-26 13:00) 및 팀 논의 31개 항목 | 31개 논의 | — | — | 판단 | docs/sa-review/01-sa-1st-review.md:204-269 | — | — |
| H-74 | 결제 | 실제 PG의 PG 타임아웃 처리 두 방식: 망취소(나이스페이 등, read-timeout 시 반드시 망취소 요청) vs 멱등 재시도+조회(토스페이먼츠, Idempotency-Key 15일 유효, 첫 요청 처리 중이면 409 IDEMPOTENT_REQUEST_PROCESSING) | 나이스페이 connect 5초 / read 30초, 망취소 승인 후 1시간 안, 토스 멱등키 15일 | — | 외부 문서 인용(출처 링크 있음), 토스 승인 기한 10분/30분 문서 불일치로 미확인 | 판단/조사 | docs/sa-review/02-pg-timeout-handling.md:6-27 | — | 국내 PG 망취소 관행을 Mock PG에 재현(면접 스토리) |
| H-75 | 결제 | 프로젝트 적용안: Mock PG에 승인(orderId 멱등)·조회·망취소 3개 API, 승인 read-timeout → 망취소 시도 → 성공이면 실패 확정, 실패면 UNKNOWN으로 두고 스케줄러가 조회로 확정, CB fallback은 실패 확정이 아니라 UNKNOWN 보류 기록까지만 | API 3개 | — | — | 설계(제안) | docs/sa-review/02-pg-timeout-handling.md:34-49 | — | — |
| H-76 | 결제 | 최후 안전망은 정산 대사이며 이는 문서로 확인한 내용이 아닌 업계 일반 관행 | — | — | — | 판단(원문 표시: 문서 미확인) | docs/sa-review/02-pg-timeout-handling.md:32 | — | — |
| H-77 | 좌석 | Kafka 파티션 3개 근거 예시: 로컬 단일 브로커, Reservation 1대 × Consumer concurrency 3, 키 userId | 파티션 3, concurrency 3 | — | 예시(설명용 값) | 설계(예시) | docs/sa-review/03-seat-hold-kafka-design.md:71 | — | 파티션 키 한 줄 근거 |
| H-78 | 좌석 | 순수 비동기(202+폴링)에서 5분 선점 타이머는 HELD 확정 시점부터 센다(Consumer가 30초 밀리면 사용자가 결제 시간 30초를 잃기 때문) | 5분, 폴링 0.5~1초 | — | 예시 | 설계 | docs/sa-review/03-seat-hold-kafka-design.md:77-83 | — | — |
| H-79 | 좌석 | 혼합형에서 Redis TTL을 DB 만료보다 조금 길게(예: 5분 + 30초), 결제가 시작되면 Redis 키 TTL을 없애거나 늘림 | 5분 + 30초 | — | 예시 | 설계 | docs/sa-review/03-seat-hold-kafka-design.md:166-167 | — | — |
| H-80 | 좌석 | 부하 테스트 개선 전(v1: API가 DB 조건부 UPDATE를 동기 처리) vs 개선 후(v2: 혼합형, 경합 패자는 Redis에서 끝나고 DB 쓰기는 Consumer가 분산) 비교 스토리 | — | — | — | 설계(계획) | docs/sa-review/03-seat-hold-kafka-design.md:179-184 | docs/sa-review/03-seat-hold-kafka-design.md:30-34(ASCII 파티션 쏠림 도식) | 발제가 원하는 병목 발견 → 개선 서사 |
| H-81 | 팀 | 1026 멘토 피드백 28개(F1~F28)를 정리하고 논의사항 N1~N12 도출, 10/27 계약 합의 전 논의 종결이 목표, 작성자 A의 직책(멘토인지 팀원인지)은 확인 못 함 | F 28개, N 12개, 코멘트 스레드 19개 | — | 2026-10-26 | 판단 | docs/sa-review/04-mentor-feedback-1026.md:3-10, :186-188 | — | — |
| H-82 | 결제 | F1: PG 요청 중 Payment가 다운되면 REQUESTED로 남아 재조회도 환불도 안 됨 → 일정 시간 지난 REQUESTED도 재조회 대상에 | — | — | — | 판단(피드백) | docs/sa-review/04-mentor-feedback-1026.md:18 | — | 고아 REQUESTED 상태 발견 |
| H-83 | 결제 | F3: 몇 초 안에 재확인은 Kafka, 몇 분 이상은 스케줄러. SA는 UNKNOWN 10초 간격 6회 | 10초 × 6회 | — | — | 판단(피드백) | docs/sa-review/04-mentor-feedback-1026.md:20 | — | — |
| H-84 | 결제 | F4: 결제 토큰 만료를 선점 만료보다 앞당겨 결제 완료된 사용자가 환불되는 사례를 막음. 여유는 PG 응답 타임아웃(2초)+Gateway 요청 타임아웃(3초) 이상 | 2초 + 3초 = 5초 이상(직접 계산) | — | 비용: 결제 가능 시간 감소 | 판단/설계 | docs/sa-review/04-mentor-feedback-1026.md:21, :97 | — | — |
| H-85 | 결제 | F5: 입장 토큰과 결제 토큰 claim이 비슷하면 교차 통과 여지, 선택지 (가)키 분리 (나)aud·typ 필수 (다)둘 다, 추천 (다) | — | — | — | 설계/판단 | docs/sa-review/04-mentor-feedback-1026.md:22, :98 | — | 토큰 교차 사용 방어 |
| H-86 | 좌석 | F6/N1: v2 접수형은 DB 행이 없어 실패 상태 저장소가 없음. (가)현행/(나)접수 행+outbox/(다)Redis 상태 중 추천 (나). 이 결정은 결정 기록 #33·#50, D4 문구를 바꿈, 담당 제안 최권우 | — | — | v2 재측정에서 (나)를 v1과 비교 필요(측정 전) | 판단(제안) | docs/sa-review/04-mentor-feedback-1026.md:23, :71-83 | — | 상태를 못 남기는 접수형의 허점 발견 |
| H-87 | 좌석 | F10/N6: 레이스 컨디션 방식 비교: 조건부 UPDATE+부분 유니크(별도 인프라 없음, DB 최종 방어) vs 분산락(이중, 락 만료 위험) vs Redis Lua(유실 시 오판). 추천: 현 설계 유지 | — | — | — | 판단(제안) | docs/sa-review/04-mentor-feedback-1026.md:37, :111-121 | docs/sa-review/04-mentor-feedback-1026.md:115-119(비교 표) | 분산락을 쓰지 않은 이유(발표 질문 대비) |
| H-88 | 좌석 | F11/N5: user_schedule_quota 조건부 UPDATE 0행이 한도 초과인지 행 없음인지 구분 불가 → INSERT ... ON CONFLICT DO NOTHING으로 행 보장 후 UPDATE | — | — | — | 설계(제안) | docs/sa-review/04-mentor-feedback-1026.md:38, :106-109 | — | — |
| H-89 | 대기열 | F13/N7: 입장 판정은 seq 기준(ZRANK 아님)으로 확정 제안. 이탈자가 줄에서 빠지면 ZRANK는 앞당겨지지만 admitted는 입장시킨 번호까지만 올라가 어긋남 | — | — | 권영은 담당 제안, 10/27 처리 | 설계(제안) | docs/sa-review/04-mentor-feedback-1026.md:40, :124-127 | — | ZRANK vs seq 선택 |
| H-90 | Gateway | F16/F17/N8: Redis는 죽는 것보다 느려지는 것이 더 위험. 서킷 브레이커/짧은 타임아웃으로 끊고 기능별 fail-open/closed 초안(대기열 입장 closed, 로그아웃 블랙리스트 팀 선택, 요청 제한 로컬 open, 공연 캐시 open) | — | — | — | 설계(제안) | docs/sa-review/04-mentor-feedback-1026.md:48-49, :129-144 | docs/sa-review/04-mentor-feedback-1026.md:135-140(기능별 정책 표) | 느려짐이 장애보다 위험하다는 통찰 |
| H-91 | 인프라 | F18/F19/N9: 외부 공개 포트 정리, 관리자 UI SSH 접근 여부, 현재 문서는 grafana(3000)를 외부에 연다고 지적됨. 추천: SSH 터널(가) | — | — | — | 판단(제안) | docs/sa-review/04-mentor-feedback-1026.md:50-51, :146-152 | — | — |
| H-92 | 대기열 | F22/N10: 입장 토큰 TTL 10분과 초당 입장 50명은 리틀의 법칙 L = λ × W와 같은 꼴, 50 × 600초 = 30,000명이며 KSPO DOME 좌석 약 15,000석(회차당 schedule_seat 15,000행)보다 큼(상한이며 실제 체류 시간은 측정 전 미확인) | 50명/초 × 600초 = 30,000명 vs 좌석 15,000석 | 30,000 ÷ 15,000 = 2배(원문에 "좌석 수의 2배" 표기) | 설계값, 측정 전 | 계산값 | docs/sa-review/04-mentor-feedback-1026.md:54, :155 | — | 입장 상한이 좌석 수의 2배라는 모순 지적 |
| H-93 | 인프라 | F20/F21/N10: 컨테이너 약 15개가 한 대에 올라가므로 서비스별 -Xmx와 컨테이너 상한 합이 EC2 메모리의 70~80% 이하인지 확인 필요(수치 측정 전 미확인). 단일 EC2 한계 문구 추가 추천 | 컨테이너 약 15개, 70~80% | — | 설계(측정 전) | 설계/판단 | docs/sa-review/04-mentor-feedback-1026.md:52-53, :156-157 | — | 인스턴스 하나가 죽으면 전부 죽지 않느냐는 지적 |
| H-94 | 팀 | 10/27 합의 때 정할 것 8개, 담당 제안: 최권우(좌석 선점), 안준현(결제), 반효승(결제 토큰), 권영은(입장 토큰·U10) | 8개 | — | — | 판단 | docs/sa-review/04-mentor-feedback-1026.md:174-184 | — | — |
| H-95 | 대기열 | 개선안 2-1: 입장은 seq 기준, INCR queue:{scheduleId}:seq, admitted를 초당 N(50)씩 올림, 표시 순번 max(0, seq - admitted). 비용: 이탈자 번호도 admitted가 지나가 실제 입장 속도가 N보다 낮아짐 | N = 50/초 | — | [가정] 값 | 설계(제안) | docs/sa-review/05-feedback-improvement-proposal.md:30-33 | — | — |
| H-96 | 결제 | 개선안 2-2: 입장 토큰 키 ENTRY_TOKEN_SECRET, 결제 토큰 키 PAYMENT_TOKEN_SECRET 분리, typ·aud 이중 방어, 결제 토큰 exp = hold_expires_at - 10초(Gateway 타임아웃 3초 + 결제 결과 반영 2초 + 여유 5초), 비용으로 결제 가능 시간 5분 → 4분 50초 | 10초(3 + 2 + 5), 5분 → 4분 50초 | 3 + 2 + 5 = 10초(직접 계산), 10 ÷ 300 = 약 3.3% 감소(직접 계산) | [가정] 값 | 설계(제안) | docs/sa-review/05-feedback-improvement-proposal.md:37-49 | docs/sa-review/05-feedback-improvement-proposal.md:37-43(토큰 비교 표) | 결제 토큰을 10초 일찍 만료시키는 판단 |
| H-97 | 결제 | HMAC은 검증 키로 서명도 가능해 Gateway·Payment도 토큰을 만들 수 있는 한계가 있고 비대칭 서명은 MVP 이후로 미룸(이후 workspace.md에서 결제 토큰 ES256 키 쌍으로 구현됨) | — | — | 결정 변화: 05(2026-10-26) 미룸 → workspace.md 47행(ADR 0055) ES256 키 쌍 사용 | 설계(변화) | docs/sa-review/05-feedback-improvement-proposal.md:46, docs/guides/workspace.md:47 | — | HMAC → ES256 전환(설계가 바뀐 순간) |
| H-98 | 좌석 | 개선안 2-4: 접수형 v2를 DB 행 없이 Kafka 직접 발행 → seat_hold_request 행 + outbox 한 트랜잭션으로 변경, 같은 request_id는 PK 충돌로 멱등, 소비 재시도 3회 실패 DLQ → REJECTED(PROCESSING_FAILED), REQUESTED 30초 [가정] 넘으면 모니터링 | 30초, 재시도 3회 | — | [가정], 처리량 영향 측정 전 | 설계(제안) | docs/sa-review/05-feedback-improvement-proposal.md:58-81 | docs/sa-review/05-feedback-improvement-proposal.md:63-73(DDL 초안) | 접수형 상태 저장 도입(직접 발행 → outbox) |
| H-99 | 결제 | 개선안 2-5: REQUESTED가 requested_at 10초 [가정] 넘으면 UNKNOWN과 같은 재조회 대상(PG 응답 타임아웃 2초에 여유), NOT_FOUND는 DECLINED(NOT_SENT)로 닫음 | 10초, PG 타임아웃 2초 | — | [가정] | 설계(제안) | docs/sa-review/05-feedback-improvement-proposal.md:86-87 | — | — |
| H-100 | 결제 | 재확인 스케줄러 간격 [가정]: 10초 × 6회(약 1분) → 1분 → 5분 → 15분 → NEEDS_MANUAL_CHECK, 총 약 21분 | 10초×6 + 1분 + 5분 + 15분 | 60초 + 60초 + 300초 + 900초 = 1,320초 = 22분(직접 계산, 원문은 총 약 21분 표기, 원문 수치와 차이 있음) | [가정] 값 | 설계(제안) | docs/sa-review/05-feedback-improvement-proposal.md:112-113 | docs/sa-review/05-feedback-improvement-proposal.md:219-238(mermaid 흐름) | UNKNOWN을 사라지지 않게 하는 다단계 재확인 |
| H-101 | 결제 | 환불 테이블 초안: payment_id UNIQUE(결제 1건당 환불 1건), pg_refund_id UUID를 PG 호출 전 생성해 멱등키로 사용, 상태 REQUESTED/REFUNDED/FAILED/UNKNOWN/NEEDS_MANUAL_CHECK | 상태 5개 | — | 초안(팀 확인) | 설계(제안) | docs/sa-review/05-feedback-improvement-proposal.md:89-108 | docs/sa-review/05-feedback-improvement-proposal.md:90-103(DDL) | 환불을 결제와 같은 구조로 |
| H-102 | Gateway | 개선안 2-6: Redis 호출 타임아웃 100ms(정상 응답은 1ms 안팎), 서킷 브레이커 최근 20회 중 50% 실패 또는 50ms 초과 시 OPEN, 10초 뒤 HALF_OPEN 5회 시험 | 100ms, 1ms, 20회, 50%, 50ms, 10초, 5회 | 100 ÷ 1 = 100배(직접 계산, 원문 "1ms 안팎") | [가정] | 설계(제안), 이후 workspace.md:120에 100ms 타임아웃 구현 반영 | docs/sa-review/05-feedback-improvement-proposal.md:118-121, docs/guides/workspace.md:120 | docs/sa-review/05-feedback-improvement-proposal.md:123-129(기능별 정책 표) | Redis 느려짐 방어 설계 → 구현 |
| H-103 | Gateway | OPEN 시 정책: 대기열 closed 503+Retry-After, 입장 토큰 서명 검증은 Redis 없이 통과, 블랙리스트 open(로그아웃된 Access 토큰이 최대 30분 통함), 요청 제한 로컬 카운터 open, 공연 캐시 open·DB 조회 대체(초당 상한) | 최대 30분 | — | [가정] | 설계(제안) | docs/sa-review/05-feedback-improvement-proposal.md:123-129 | — | 기능별 fail-open/closed 선택 |
| H-104 | 좌석 | 개선안 2-7: INSERT ... ON CONFLICT DO NOTHING 후 held_count + :n <= :maxPerUser 조건부 UPDATE, 만료·취소 시 held_count 미감소 고아는 대사 쿼리로 검사 | — | — | 초안 | 설계(제안) | docs/sa-review/05-feedback-improvement-proposal.md:134-148 | — | — |
| H-105 | 인프라 | 개선안 2-8: 보안그룹 표(22 팀원 IP, 8080 gateway 전체·측정 중엔 측정 기기 IP, 3000·8085·9090·9411·8761 허용 없음, 서비스·DB·Redis·Kafka 내부 전용), compose 관리자 UI 127.0.0.1 바인딩 이중 방어, 시연도 SSH 터널(04 문서의 시연 기간 한시 공개 검토 안을 이 안으로 변경) | 포트 다수 | — | (팀 확인) 초안. 이후 deploy-server-access.md:53은 Grafana를 공개로 바꿈 | 설계(변화) | docs/sa-review/05-feedback-improvement-proposal.md:150-165, docs/guides/deploy-server-access.md:53 | docs/sa-review/05-feedback-improvement-proposal.md:154-160(보안그룹 표) | Grafana 비공개(SSH 터널) → 2026-11-16 로그인 없는 공개 보기로 바뀐 순간 |
| H-106 | 대기열 | 개선안 2-9: 입장 속도 역산식 L_max = μ / r, λ = L_max / W. 계산 예(전부 [가정]): μ = 200, r = 0.2, W = 150초 → L_max = 1,000, λ ≈ 6.7명/초. 현재 초안 50명/초는 이 예보다 훨씬 큼 | 1,000명, 6.7명/초 vs 50명/초 | 50 ÷ 6.7 = 약 7.5배(직접 계산) | [가정], 포화점 μ·W 측정 후 N 역산 | 계산값 | docs/sa-review/05-feedback-improvement-proposal.md:167-171 | — | 입장 속도 50을 임시값으로 두고 측정으로 역산하는 방법론 |
| H-107 | 인프라 | 개선안 2-10: EC2 메모리 예산표 합계 10,880m(약 10.6GB), 합계를 EC2 메모리의 75% 이하로 두면 필요 약 14.5GB이고 16GB급이 최소, 8GB급은 맞지 않음 | 10,880m, 약 14.5GB 필요, 16GB급 최소 | 10,880 ÷ 0.75 = 14,507m = 약 14.5GB(직접 계산, 원문 일치) | [가정] 시작값, docker stats로 측정해 조정 | 계산값 | docs/sa-review/05-feedback-improvement-proposal.md:173-196 | docs/sa-review/05-feedback-improvement-proposal.md:176-191(메모리 예산 표) | 16GB급 인스턴스를 고른 근거(→ 실제 배포는 m7i.xlarge, 16GiB는 아래 메모리 절 참고) |
| H-108 | 인프라 | 서비스별 메모리 상한(가정): gateway 512m/768m, eureka 256m/512m, user 384m/640m, reservation 768m/1,152m, payment 512m/768m, mock-pg 256m/512m, postgres×3 합 2,048m, redis 512m(maxmemory 약 400m), kafka 힙 1,024m/1,536m, kafka-ui 512m, prometheus 768m, grafana 384m, zipkin 768m | 표 참조 | — | [가정] | 설계(시작값) | docs/sa-review/05-feedback-improvement-proposal.md:176-191 | 위 표 | — |
| H-109 | 좌석 | 개선안 2-11: ADR 부분 유니크 인덱스 uq_reservation_seat_active ON reservation_seat (schedule_seat_id) WHERE status IN ('HELD','CONFIRMED'), uq_payment_reservation_active WHERE status IN ('REQUESTED','APPROVED','UNKNOWN'). 검증: E1 같은 좌석 동시 100요청 → 1건 성공, 나머지 409, 중복 판매 0건 | 동시 100요청, 성공 1건 | — | 초안(팀 확인) | 설계(제안) | docs/sa-review/05-feedback-improvement-proposal.md:199-214 | docs/sa-review/05-feedback-improvement-proposal.md:200-208(SQL) | 중복 판매 0건 검증 기준 |
| H-110 | 기타 | 테이블 적재량 추정(전부 [가정], 1공연 2회차 회차당 15,000석): schedule_seat 30,000, reservation 12,000~40,000, reservation_seat 30,000~100,000, history 36,000~120,000, ticket 30,000 이하, user_schedule_quota 입장자 50,000명이면 100,000행. 용량은 1GB를 넘지 않을 것으로 보이며(행 크기 미측정) 병목은 용량이 아니라 같은 좌석 행에 몰리는 쓰기 동시성 | 표 참조 | 30,000 = 15,000 × 2회차(원문 산식) | [가정] | 계산값(추정) | docs/sa-review/05-feedback-improvement-proposal.md:249-263 | 위 표 | 병목은 용량이 아니라 동시성이라는 결론 |
| H-111 | 기타 | 새 테스트 케이스 E11~E16(Redis 지연 주입, PG 호출 중 중단, 환불 중복, 토큰 교차 사용, DLQ 도달, 첫 선점) | 6개 | — | — | 설계(제안) | docs/sa-review/05-feedback-improvement-proposal.md:275-285 | — | — |
| H-112 | 팀 | 개선안 적용 순서: 10/27 합의 전 2-1~2-4·2-5(방향)·2-6(초안), MVP(10/29~11/6) 2-5·2-6 구현·2-7·2-8·2-10·2-11·플로우차트, 기준선 측정 후 2-9, 11/9 이후 페르소나·적재량·법률 | — | — | — | 설계(계획) | docs/sa-review/05-feedback-improvement-proposal.md:289-294 | — | — |
| H-113 | 팀 | 법률 조사 계획: 조사 질문 4건뿐이며 확인된 법령 아님, 전자상거래법 5년 보관 외 법령명·조항은 미확인 | 질문 4건 | — | — | 판단(미확인) | docs/sa-review/05-feedback-improvement-proposal.md:265-273, docs/sa-review/04-mentor-feedback-1026.md:167 | — | 법률 → 설계 결정(발표 소재) |
| H-114 | 팀 | 페르소나 4명(P1 도예린, P2 오민재, P3 서유진, P4 한도운) 유저스토리 초안 | 4명 | — | 초안 | 설계 | docs/sa-review/05-feedback-improvement-proposal.md:240-247 | — | 페르소나 기반 시연 시나리오 |
| H-115 | 팀 | 서비스 플로우 차트(mermaid) 초안 포함 | — | — | 초안 | 설계 | docs/sa-review/05-feedback-improvement-proposal.md:219-238 | mermaid 흐름도(로그인 → 대기열 → 입장 토큰 → 선점 → 결제 → 확정/만료/환불/NEEDS_MANUAL_CHECK) | 전체 흐름 한 장 그림(브로셔 쓸모 상) |
| H-116 | 기타 | AI 설계 검증 #7(계층형 아키텍처, 2026-10-27): 대상은 4계층으로 구현된 유일한 user-service(클래스 16개), Reservation은 빈 패키지(package-info 16개)뿐이라 구조만 대조 | 클래스 16개, package-info 16개, 기준 커밋 f6df9f1 | — | 2026-10-27, dev f6df9f1 | 판단(AI 검증) | docs/sa-review/06-layered-architecture-check.md:3-11, :24-31 | — | AI 설계 검증 로그 사례 |
| H-117 | 기타 | #7 검토 결과 4문항: 도메인 규칙 부분 충족(빈약한 도메인 모델), 의존 역전 대체로 충족+위반 2곳(RedisSessionStore → application UserErrorCode import 5행, AuthService가 DataIntegrityViolationException 잡음 66행), DTO 분리 대체로 충족(1곳), Fat Service 경계선(AuthService 239줄, 공개 메서드 8개) | 239줄, 공개 메서드 8개, 위반 2곳 | — | user-service | 판단(AI 검증) | docs/sa-review/06-layered-architecture-check.md:35-40, :44-71 | docs/sa-review/06-layered-architecture-check.md:26-31(계층별 클래스 표) | 계층 규칙 위반을 AI가 코드에서 찾아낸 사례 |
| H-118 | 팀 | #7 판단(2026-10-27, kangmin): A 부분 수용, B 수용, C 수용, D 거부(응답 형식을 바꿀 계획 없어 클래스만 늘어남), E 보류(239줄·8개는 아직 읽기 어렵지 않음, M2 이후 재검토), F 수용, G 수용(선점 코드가 생기면 재실행). A~C는 타 팀원 코드라 담당과 협의 | 수용 4 / 부분 수용 1 / 거부 1 / 보류 1 | — | 2026-10-27 | 판단(수용/거부/보류) | docs/sa-review/06-layered-architecture-check.md:78-90 | docs/sa-review/06-layered-architecture-check.md:80-88(판단 표) | 수용·거부·보류를 근거와 함께 남긴 AI 검증 로그(거부 1건 충족) |
| H-119 | 좌석 | 07: reservation-service는 concert/queue/seathold/reservation/lottery/shared 6개 모듈의 모듈러 모놀리스, 정합성 방어선 4중: quota 행락(D1), schedule_seats ID 오름차순 FOR UPDATE(lock_timeout = 500ms) 후 영향 행 수 검사, reservation_seats 부분 유니크(D2), 잠금 순서 고정(매수 한도 → 회차 좌석 → 예매) | lock_timeout 500ms, 모듈 6개 | — | SA MASTER v3.2, 2026-10-28 | 설계 | docs/sa-review/07-reservation-system-review.md:9-24 | — | 정합성 4중 방어 |
| H-120 | 좌석 | G1(치명): 동기형 선점 실패(409) 시 롤백으로 seat_hold_requests 행이 사라져 REJECTED 기록 불가, 같은 키 다른 본문 판별 불가 → TX1(요청 선행 INSERT) / TX2 REQUIRES_NEW / TX3(REJECTED 갱신)로 분리 | TX 3개 | — | 협의 대상 최권우, (팀 확인) | 판단(AI 검토 발견) | docs/sa-review/07-reservation-system-review.md:32, :49-61 | — | 롤백이 거절 기록까지 지우는 허점 |
| H-121 | Gateway | G2: POST /api/v1/reservations는 scheduleId가 본문에 있어 Gateway에서 Body 파싱은 비용·결합 → Gateway는 서명·typ·aud·exp·sub만 검증하고 X-Entry-Schedule-Id 내부 헤더로 넘겨 Reservation이 sid == scheduleId 대조(방안 A 권장) | — | — | 권영은·최권우 | 설계(제안) | docs/sa-review/07-reservation-system-review.md:33, :63-70 | — | Gateway에서 POST Body를 파싱하지 않는 판단 |
| H-122 | 좌석 | G3: 접수형 선점은 section이 없어 다중 구역 선택 시 파티션 키 {scheduleId}:{section} 생성 불가 → 1회 선점은 같은 구역 내 좌석만 허용, 오픈 전 검증은 202 전에 수행 | — | — | 최권우·반효승 | 설계(제안) | docs/sa-review/07-reservation-system-review.md:34, :72-82 | — | — |
| H-123 | 좌석 | G4: 접수형 30초 타임아웃 스케줄러와 소비자의 경합으로 요청은 TIMEOUT인데 좌석은 HELD로 묶이는 유령 선점 → 소비자 시작 시 상태 선확인, 마지막 조건부 UPDATE 0행이면 전체 롤백 | 30초 | — | — | 설계(제안) | docs/sa-review/07-reservation-system-review.md:35, :84-92 | — | 유령 선점 발견 |
| H-124 | 좌석 | G5: 만료·거절·취소 경합 시 user_schedule_quotas 이중 차감 위험 → held_count >= :n 조건과 reservations 조건부 UPDATE 0행이면 전체 롤백 | — | — | 반효승·최권우 | 설계(제안) | docs/sa-review/07-reservation-system-review.md:36, :94-102 | — | — |
| H-125 | 결제 | G6: CONFIRMED 예매에 같은 paymentId의 PaymentApproved 재전달 시 환불 위험(ADR 0027), 만료 10초 전~만료 사이 조회 시 이미 만료된 결제 토큰 반환 → now >= hold_expires_at - 10초면 paymentToken을 null로 | 10초 | — | 반효승·안준현 | 설계(제안) | docs/sa-review/07-reservation-system-review.md:37, :104-114 | — | 확정 티켓을 환불해버릴 수 있는 재전달 허점 |
| H-126 | 좌석 | G7: 미결제 HELD 예매의 자발적 해제 경로 부재로 잘못 고른 좌석이 5분 만료 전까지 4매 한도를 묶음 → HELD → RELEASED 즉시 해제 허용 | 4매 한도, 5분 | — | 반효승 | 설계(제안) | docs/sa-review/07-reservation-system-review.md:38, :116-122 | — | — |
| H-127 | 추첨 | G8: 추첨 DONE 이후에만 일반 오픈이 오도록 강제, 승계 시 좌석을 AVAILABLE로 풀지 않고 같은 트랜잭션에서 다음 예비자의 신규 HELD로 소유권 인계 | — | — | 이강민·반효승, (팀 확인, U9) | 설계(제안) | docs/sa-review/07-reservation-system-review.md:39, :124-130 | — | 승계 중 좌석 격리 |
| H-128 | 좌석 | M2(설정): config-repo/reservation-service.yml의 HikariCP maximum-pool-size가 5이고 SA 9-4는 20 → 20으로 수정 권고(동시 100명 기준선에서 DB 락보다 커넥션 대기가 먼저 병목이 되는 측정 왜곡 방지) | 5 → 20 | 20 ÷ 5 = 4배(직접 계산) | 2026-10-28 코드 기준 | 로컬 측정 아님(설정 불일치 발견) | docs/sa-review/07-reservation-system-review.md:41, :140-142 | — | 커넥션 풀 5가 측정을 왜곡할 뻔한 발견 |
| H-129 | 좌석 | M1(코드): SA 12-3 단수 테이블명 vs 엔티티 복수 테이블명(reservations 등) 불일치 → 복수형으로 통일 | — | — | — | 판단 | docs/sa-review/07-reservation-system-review.md:40, :136-138 | — | — |
| H-130 | 팀 | M4: 대기열 안전장치(ADR 0015), 결제↔예매 이벤트 계약(ADR 0016, 0027), 대사 API(ADR 0029)가 제안 상태이며 팀 합의 후 확정 필요 | ADR 4건 | — | 2026-10-28 | 판단 | docs/sa-review/07-reservation-system-review.md:43, :148-149 | — | — |
| H-131 | 팀 | codex 원문 총평: 3개 서비스(User/Reservation/Payment)로 줄이고 Queue·Lottery를 Reservation 내부 모듈로 두며, 좌석·결제 실패 복구를 P0로 완결하는 것이 현실적 | 서비스 5개 → 3개 권고 | — | codex CLI read-only, 2026-10-23 | 판단(AI 제안) | docs/sa-review/raw/codex-review.md:7, :213-215 | — | codex 3개 vs Claude 4개 대립(발표 서사 소재) |
| H-132 | 팀 | codex Critical 3건: 좌석 정합성 최종 권위·상태 전이 계약 부재, PG 타임아웃을 실패 취급, 추첨 자동결제 P0인데 실패 복구 P1 | Critical 3건, Major 10건, Minor 2건 | — | — | 판단(AI 리뷰) | docs/sa-review/raw/codex-review.md:26-56 | — | — |
| H-133 | 팀 | codex Major 8: MVP 9평일, 트러블슈팅 6평일, 발제문 424행의 11/09(금)은 요일 오기(2026-11-09는 월요일) | 9일, 6일 | — | — | 판단 | docs/sa-review/raw/codex-review.md:132-142 | — | 발제문 날짜 요일 오기 발견 |
| H-134 | 팀 | codex 발제 대비 표: SAGA 1개 이상 반영, 부하 테스트 100명 × 각 10회 · 1초 안에 모두 시작 누락, 200/sec는 권장 목표이며 미달도 개선 증거로 인정, RAG·DDD·Eureka·Config는 필수 아님 | 100명 × 10회, 200/sec | 100 × 10 = 1,000회(직접 계산) | 발제문 행 번호 인용 | 판단 | docs/sa-review/raw/codex-review.md:111-120, :178-205 | — | 발제 요구와 팀 범위의 대조 |
| H-135 | 팀 | codex Major 10: AI 검증 #1~#6은 질문 목록이고 답변·판단이 비어 있음, 발제는 최소 3개 검증, 거부 1개 이상, 발표 사례 2건 필요 | 3개 이상, 거부 1개 이상, 사례 2건 | — | — | 판단 | docs/sa-review/raw/codex-review.md:156-164 | — | AI 검증 실적 쌓기 |
| H-136 | 기타 | 워크스페이스 가이드 검증 범위 밖: EC2 배포, 부하·운영 용량, 실제 PG, GitHub 원격 CI, IntelliJ UI 실행 | — | — | — | 설계/한계 | docs/guides/workspace.md:159 | — | — |
| H-137 | 인프라 | 서버 포트 전제: 배포(EC2)는 보안그룹으로 화면 80만 외부에 연다고 workspace.md에 쓰여 있으나 deploy-server-access.md는 HTTPS 52.63.10.174(443)로 접속 | 80 vs https | — | 문서 간 불일치 가능성(모순) | 모순 표시 | docs/guides/workspace.md:31, docs/guides/deploy-server-access.md:9-12 | — | — |
| H-138 | 좌석 | 모순: 04(2026-10-26 N9)·05는 Grafana를 SSH 터널로만 접근시키자고 했으나 deploy-server-access.md는 2026-11-16 로그인 없는 공개로 변경 | — | — | 설계 변경(날짜 10/26 → 11/16) | 모순 표시 | docs/sa-review/05-feedback-improvement-proposal.md:158, :163, docs/guides/deploy-server-access.md:53 | — | 비공개 → 공개로 판단이 바뀐 이유(시연·외부 심사) |
| I-01 | 팀 | 준현 브랜치를 구현 기준으로 삼고 효승·강민 장점을 선별 이식하는 안을 추천(채택 결정 아님) | 후보 브랜치 5개, 기준 선택 1개(준현) | — | 2026-10-27 조사, 병합·코드수정·커밋 없음 | 판단 | B/README.md:3-5 | — | 5명이 각자 만든 골격 중 무엇을 기준으로 삼을지 정한 날 |
| I-02 | 팀 | 5개 브랜치의 공통 기준 dev 커밋은 4e02b5e이고 모두 그 후손 | 기준 SHA `4e02b5ed84bf22bbdbeb22861c86f98ffb7127fd` | — | 2026-10-27T16:17:02+09:00 확인 | 설계 | B/README.md:17, B/git-conflicts.md:3-5 | — | — |
| I-03 | 팀 | 브랜치별 dev 대비 브랜치에만 있는 커밋 수: 준현 35, 권우 5, 효승 9, 강민 21, 영은 1 (dev에만 있는 커밋은 모두 0) | 35 / 5 / 9 / 21 / 1 커밋 | — | git rev-list 계열 집계 | 계산값(로컬 git) | B/git-conflicts.md:11-15, B/git-evidence.jsonl:1 | — | 준현이 35커밋으로 가장 많이 쌓음 |
| I-04 | 팀 | dev 대비 변경 파일 수: 준현 184, 권우 52, 효승 81, 강민 130, 영은 78 | 184 / 52 / 81 / 130 / 78 파일 | — | 같은 기준 SHA | 계산값 | B/git-conflicts.md:11-15, B/git-evidence.jsonl:1 | — | — |
| I-05 | 팀 | 변경 main Java/test Java 파일 수: 준현 62/20, 권우 12/6, 효승 27/9, 강민 50/7, 영은 44/0 | 62·20 / 12·6 / 27·9 / 50·7 / 44·0 | — | 파일 수이지 품질 지표 아님. 문서가 "코드량·테스트 건수 순위가 아니라 업무 흐름 연결로 기준 선택"이라 명시 | 계산값 | B/git-conflicts.md:11-15, B/README.md:27 | — | — |
| I-06 | 팀 | 5개 브랜치 쌍 10개 전부 텍스트 병합 충돌이 있음 | 10쌍 중 10쌍 충돌(종료코드 1) | 10/10 = 100% (직접 계산) | `git merge-tree --write-tree --name-only --no-messages`, git 2.52.0 | 계산값 | B/README.md:47, B/git-conflicts.md:19-32 | — | 통째 연속 merge를 권하지 않는 근거 |
| I-07 | 팀 | 준현 기준 충돌 파일 수: 권우 13, 효승 26, 강민 26, 영은 18 | 13 / 26 / 26 / 18 파일 | 합 83 (직접 계산 13+26+26+18, 독립 비교라 누적 아님) | 각 브랜치를 준현에 독립 병합 | 계산값 | B/README.md:49-51, B/git-conflicts.md:36-131, B/git-evidence.jsonl:2-5 | — | 같은 날 5명이 build.gradle·gradlew 등을 각자 만든 충돌 |
| I-08 | 팀 | 나머지 쌍 충돌 파일 수: 권우+효승 7, 권우+강민 4, 권우+영은 9, 효승+강민 20, 효승+영은 13, 강민+영은 34 | 7 / 4 / 9 / 20 / 13 / 34 | 강민+영은 34가 최대(원문 표), 권우+강민 4가 최소 | 위와 동일 | 계산값 | B/git-conflicts.md:27-32, B/git-evidence.jsonl:6-11 | — | 강민+영은 34건 중 대다수가 package-info.java 충돌 |
| I-09 | 팀 | 텍스트 충돌이 해소돼도 패키지 3종·응답/ErrorCode API·JWT 키 encoding·Redis 장애 정책·관리자/알림 라우트가 의미 충돌 | 패키지 3종(`iseonjwa`/`com.highquality`/`com.iseonjwa`) | — | 서로 다른 이름 클래스는 충돌 표시 없이 공존해 중복 빈 가능 | 판단 | B/README.md:53, B/common-contracts.md:73, B/integration-plan.md:18 | — | Git 성공 ≠ 통합 완료 |
| I-10 | 팀 | 준현 브랜치 검증: 77건 JUnit 성공, 실패·오류·skip 0 (User는 설정 8건만 실행, DB/Redis AuthIntegrationTest 20건 미실행) | 77건 (common-auth 8 + common-web 19 + gateway 42 + user-service 8) | 8+19+42+8=77 (직접 계산) | `./gradlew :common-auth:test :common-web:test :gateway:test :user-service:test --tests 'iseonjwa.user.AuthConfigurationTest' --no-daemon`, BUILD SUCCESSFUL 20s, 21 task, 로컬 | 로컬 측정 | B/README.md:61, B/auth-review.md:117-125 | — | 77건 통과해도 인증 통합 20건은 아직 안 돌렸다는 단서 |
| I-11 | 팀 | 권우 검증: 6건 성공, 직접 `./gradlew`는 permission denied, `bash gradlew`로 성공 | 6건, 16s | — | Java 21.0.12, Gradle 9.8.2, `:common:compileJava :common:test`, standalone MockMvc | 로컬 측정 | B/README.md:62, B/common-contracts.md:96-98 | — | gradlew 실행 권한 0644 문제 |
| I-12 | 팀 | 효승 검증: 31건 성공, 43초, 35 task | 31건 / 43s / 35 tasks | — | `bash gradlew build --no-daemon --console=plain`, 전체 스택 연동 아님 | 로컬 측정 | B/README.md:63, B/infra-build.md:106 | — | — |
| I-13 | 팀 | 강민 검증: 11건 성공, 1분20초, 55 task, PostgreSQL 18 Testcontainers·Flyway 포함 | 11건 / 80s / 55 tasks | — | 위와 동일 명령, 인증·SAGA 검증 아님 | 로컬 측정 | B/README.md:64, B/infra-build.md:107 | — | 실제 DB 컨테이너 테스트를 가장 먼저 갖춘 브랜치 |
| I-14 | 팀 | 영은 검증: 테스트 0건(NO-SOURCE), 28초, 43 task, Gradle10 비호환 deprecation 경고 | 0건 / 28s / 43 tasks | — | 컴파일·JAR 생성만 확인 | 로컬 측정 | B/README.md:65, B/infra-build.md:108,110 | — | — |
| I-15 | 팀 | 검증 합계는 범위가 달라 합산해 통합 품질 점수로 쓰지 않는다고 명시 | 77+6+31+11+0=125건(직접 계산, 문서는 합산 금지) | — | 모듈 범위가 서로 다름 | 계산값(인용 비권장) | B/README.md:57 | — | 숫자를 합치지 말라는 문서 자체의 경고 |
| I-16 | 팀 | 인증 통합 테스트 AuthIntegrationTest는 20개가 소스에 존재하나 이번에 미실행(DB localhost:25433/user_test, Redis 26379 필요) | 20개 | — | Refresh 동시 재사용·잠금·변경/초기화 경합 회귀 포함, "소스 존재 확인이며 통과 아님" | 설계(소스 존재) | B/auth-review.md:129-130, B/README.md:61 | — | 20건은 미검증이라 인용 시 "통과" 금지 |
| I-17 | 팀 | 문서 자체 점검: 코드 링크 80개·내부 링크 23개 이상 없음, records-check가 기존 AI 판단 기록 7건 근거 미기재 경고 | 80 / 23 / 7건 | — | `git diff --check`, `bash script/records-check.sh` | 로컬 측정 | B/README.md:71 | — | — |
| I-18 | 인프라 | 모든 브랜치 Java 21 / Boot 4.1.2 / Cloud 2025.1.4, 통합 때문에 바꿀 근거 없음 | Java 21, Spring Boot 4.1.2, Spring Cloud 2025.1.4 | — | 공식 호환표: 2025.1.x는 2025.1.2부터 Boot 4.1.x 지원 | 설계 | B/infra-build.md:23, B/source-notes.md:59, B/team-decisions.md:107 | — | 버전은 5명이 같았다 |
| I-19 | 인프라 | Gradle wrapper: 준현 9.1.0(SHA256 있음), 권우·효승·영은 9.8.2(SHA256 없음), 강민 9.8.2(SHA256 있음) | 9.1.0 vs 9.8.2 | — | — | 설계 | B/infra-build.md:24 | — | — |
| I-20 | 인프라 | 실행 앱 수: 준현 7, 권우 5, 효승 4, 강민 7, 영은 7 | 7/5/4/7/7 | — | 효승은 Reservation/Payment 라우트·DB는 있으나 실행 모듈 없음. 권우는 Config·Mock PG 없음 | 설계 | B/infra-build.md:26,31 | — | — |
| I-21 | Gateway | 5개 브랜치 Gateway 모두 WebFlux(spring-cloud-starter-gateway-server-webflux). Notion #60의 권우 MVC Gateway 설명은 현 코드와 다름 | 5/5 브랜치 WebFlux | 100% (직접 계산) | 고정 SHA 코드 기준 | 설계 | B/infra-build.md:39, B/source-notes.md:43, B/README.md:78 | — | 설계 문서 가정이 코드와 달랐던 순간 |
| I-22 | Gateway | Gateway connect/response timeout: 준현·효승·강민 1000ms/3s, 영은 500ms/3s, 권우 명시 없음 | 1000ms / 3s (영은 500ms / 3s) | 영은 connect 값이 1/2 (직접 계산 500/1000) | 각 브랜치 config | 설계 | B/infra-build.md:50 | — | — |
| I-23 | Gateway | 관리자 사용자 경로: 준현만 `/admin/users/**`를 User로 우선, 나머지 넷은 `/admin/**` 전체를 Reservation으로 | 1 vs 4 브랜치 | — | 준현 순서를 다른 Gateway 파일로 덮으면 초기화 요청이 잘못 전달 | 설계 | B/infra-build.md:51, B/auth-review.md:33, B/team-decisions.md:79 | — | 라우트 소유권 충돌(D08) |
| I-24 | Gateway | 알림 라우트 `/notifications/**`: 준현에만 없고 나머지는 Reservation | 1 vs 4 | — | — | 설계 | B/infra-build.md:52, B/team-decisions.md:79 | — | — |
| I-25 | Gateway | 사용자 헤더(X-User-Id·X-User-Role) 위조 방지: 준현·효승 모두 인증 전 제거 후 검증값만 설정. 준현 공개 API도 제거만 수행 | — | — | 서비스는 JWT 재검증 없이 헤더 신뢰(SA 전제) | 설계 | B/auth-review.md:28, 68 | — | Gateway만 인증하는 신뢰 경계 |
| I-26 | Gateway | 준현은 `/internal/**`, `/api/v1/internal/**`를 외부 404 처리. 효승은 별도 내부 경로 차단 없음 | — | — | — | 설계 | B/auth-review.md:30 | — | — |
| I-27 | 인프라 | Redis 장애 정책 충돌: 준현 fail-closed(100ms + auth-redis 서킷, 503 + Retry-After: 1), 효승 fail-open(실패 시 통과), Notion #48 잠정은 fail-open | 타임아웃 100ms, 서킷 최소 호출 수 20, Retry-After 1 | — | #48의 100ms는 "측정으로 조정할 잠정값" | 설계 | B/auth-review.md:34, 69, 97; B/source-notes.md:31-35 | — | 가용성(fail-open) vs 보안(fail-closed) 선택, 로그아웃 토큰 최대 30분 통과 위험 |
| I-28 | 인프라 | 준현 기동 시 Redis PING 2초 제한, 200ms 간격 2회 재시도 뒤 기동 실패 | 2초 / 200ms / 2회 | — | 효승은 warmup 없음 | 설계 | B/auth-review.md:35 | — | Redis 없는 cold start는 실패하는 비용 |
| I-29 | 인증 | Access 기본 유효 30분, Refresh 기본 TTL 14일, 로그인 5회 실패 시 잠금, 비밀번호 BCrypt UTF-8 72바이트 상한, 최소 8자 | 30분 / 14일 / 5회 / 72바이트 / 8자 | 14일÷30분=672배 (직접 계산) | 준현 구현 기준 | 설계 | B/auth-review.md:47-52 | — | — |
| I-30 | 인증 | 사용자당 Refresh 1개(`auth:refresh:{userId}`, SHA-256 digest), 로그아웃은 현재 Access jti 블랙리스트(`auth:blacklist:{jti}`, TTL=Access 남은 시간)와 Refresh 삭제를 Lua로 함께 수행 | Refresh 1개/사용자 | — | 새 로그인이 이전 Refresh를 대체, 비밀번호 변경·초기화는 Refresh만 폐기(기발급 Access는 폐기 구현 없음) | 설계 | B/auth-review.md:51-55, 74; B/team-decisions.md:60 | — | 단일 세션·Access 잔여 유효 30분 위험을 감수하는 결정(D06) |
| I-31 | 인증 | JWT 키 계약 차이: 준현은 UTF-8 원문 32바이트 이상·HS256·Nimbus, 효승은 Base64 decode·JJWT. 같은 환경변수 값이라도 서명 바이트가 다름 | 32바이트 이상 | — | 효승 parser는 알고리즘을 HS256 하나로 제한하는 설정 없음(실행 미검증) | 설계 | B/auth-review.md:25, 40 | — | 같은 이름 키가 다른 바이트가 되는 함정 |
| I-32 | 인증 | 준현 jti는 null만 거절해 빈 문자열 jti는 통과 가능, 만료 JWT도 일반 UNAUTHORIZED로 응답(TOKEN_EXPIRED 구분 상실) | — | — | TOKEN_EXPIRED 구분은 2026-11-02 ISE-131에서 반영, 빈 jti 거절은 남음 | 판단 | B/auth-review.md:70-72, B/team-decisions.md:127 | — | 검토 지적이 후속 이슈로 이어진 사례 |
| I-33 | Gateway | 두 Gateway 모두 사용자 단위 요청 제한(H1) 구현 없음. M1 인증 구현을 H1 완료로 쓰지 않는다 | — | — | — | 설계 | B/auth-review.md:73, B/team-decisions.md:82 | — | — |
| I-34 | 공통 | 응답 형식 #19(결정 상태): 성공 `{status,message,data,traceId}`, 실패 `{status,code,message,errors,traceId}`, 본문 없는 성공은 200 + data:null, errors는 검증 오류에서만 [{field,reason}] | — | — | Notion 마지막 편집 2026-10-26 19:47 KST | 설계 | B/source-notes.md:15, 27-29 | — | 확정 계약과 구현 간 이름 차이만 남음 |
| I-35 | 공통 | 공통 모듈 구성안: 준현 common/response/web/auth/messaging, 권우 단일 common, 효승 common/response/web, 강민 common/response/web/messaging(Java 구현 없음), 영은 package-info만 | 모듈 5종 vs 1종 | — | #60(10/27 제안)은 common 하나+Gateway 미의존+응답 DTO 복제+계약 테스트 | 설계 | B/README.md:19-25, B/common-contracts.md:75-77, B/source-notes.md:39-41 | — | 모듈 수 논쟁(D02) |
| I-36 | 공통 | Outbox·processed_event·재시도·DLQ 실행 코드는 5개 브랜치 어디에도 없고 package-info 설명뿐 | 0개 구현 | — | `git grep -n -i 'outbox\|processed_event'` 5개 SHA main 소스 | 로컬 측정(git grep) | B/common-contracts.md:67, 95 | — | 설명 ≠ 구현 구분 |
| I-37 | 공통 | 준현 감사는 시스템 수정 시 updatedBy를 명시적 NULL로 쓰는 JPA 테스트가 있음. 효승 표준 감사는 해당 DB 결과 검증 없음. 감사 컬럼 6개 | 감사 컬럼 6개 | — | — | 설계 | B/common-contracts.md:57-61 | — | 감사 정책 선택 근거 |
| I-38 | 인프라 | PostgreSQL 버전: 준현 17-alpine digest(서비스별 3 컨테이너), 권우 18 태그(단일 컨테이너), 효승 17.6-alpine(3), 강민 18.6-alpine(3), 영은 없음 | 17 vs 18 | — | 추천은 최초 17 유지, 18 전환은 별도 검증 | 설계 | B/infra-build.md:64, B/team-decisions.md:90 | — | DB major 버전 선택 |
| I-39 | 인프라 | Redis 설정 차이: 준현 8-alpine AOF yes·128MB noeviction, 효승 7.4-alpine 400MB noeviction, 강민 8.10.2-alpine 400MB noeviction RDB/AOF off | 128MB vs 400MB(maxmemory 한도) | 400÷128=3.125배 (직접 계산) | 메모리 한도 설정값, 실제 사용량 아님 | 설계 | B/infra-build.md:66 | — | Redis 영속성이 인증 상태 유실 가능성을 바꿈 |
| I-40 | 인프라 | Kafka 버전·포트: 준현 4.1.1(internal 9092/host 19092), 효승 4.1.0(9092/29092), 강민 4.3.2(29092/9092, 자동 토픽 생성 off) | 4.1.1 / 4.1.0 / 4.3.2 | — | — | 설계 | B/infra-build.md:67 | — | — |
| I-41 | 인프라 | Gateway 외부 bind: 준현 127.0.0.1:8080, 효승·강민 모든 주소 8080 | — | — | 결정 #57: Gateway만 외부 공개, 관리 UI loopback | 설계 | B/infra-build.md:68, B/source-notes.md:21 | — | — |
| I-42 | 인프라 | 관측: 준현 7앱 Actuator/Prometheus 관리포트 9080 분리, 효승 Eureka 동적 수집, 강민 정적 목록, 영은은 prometheus registry 의존성 없음 | 관리포트 9080 | — | 영은 YAML 선언만으로 지표 수집된다고 보지 않음 | 설계 | B/infra-build.md:86-98 | — | 관측 수집 방식 선택 |
| I-43 | 인프라 | Zipkin 샘플링: 준현 local 1.0 / loadtest 0.01 / prod 0.1, 효승 기본1.0 / 0.1 / prod 없음, 강민 기본1.0 / 0.1 / 0.1 | 1.0 / 0.01 / 0.1 | loadtest 샘플링 0.01은 local의 1/100 (직접 계산) | 설정값 | 설계 | B/infra-build.md:89 | — | 부하 테스트 시 추적 비용 줄이는 설정 |
| I-44 | 인프라 | 준현 Docker는 non-root·JDK/JRE digest 고정, CI는 verifyWorkspace + always test infra down. 나머지는 기록 검사만 | — | — | 검사 통과 ≠ 운영 용량 | 설계 | B/infra-build.md:90-91, 37 | — | — |
| I-45 | 인프라 | 시스템 테스트 계획: `./gradlew :system-tests:systemTest -PredisFailure=true -PcoldStart=true --no-daemon` 로 Redis 장애·cold start 확인 예정 | — | — | "향후 실행 기준이며 이번 조사에서 통과한 결과 아님" | 설계 | B/integration-plan.md:49-56 | — | 장애 주입 검증 계획 |
| I-46 | 팀 | 통합 계획 순서: 전용 브랜치 → 계약 정렬(D01~D08) → 선별 이식 → 실행·저장소 정렬 → 단계별 검증 → dev PR. 5개 브랜치 통째 연속 merge는 권하지 않음 | 단계 6개 | — | 아직 미실행 | 설계 | B/integration-plan.md:3-72 | — | 팀 결정 후 실행하는 조건부 계획 |
| I-47 | 팀 | 팀 결정 안건 D01~D12가 모두 "결정: ______" 빈칸 상태 | 안건 12개 | — | 미채택 추천 | 설계 | B/team-decisions.md:7-121 | — | 10/27 시점 미결 12건 |
| I-48 | 좌석 | 좌석 선점 Kafka 설계: 파티션 키는 userId 권장(회차 ID는 hot partition, 좌석 ID는 원자성 깨짐). 정합성은 Redis 원자 연산 + DB 조건부 UPDATE가 담당 | 파티션 3개, Consumer concurrency 3, 키=userId | — | 로컬 단일 브로커, Reservation 1대. 그림의 u12·회차7·A1은 예시 | 설계 | K:275-353 | K 파티션 레인 도식(L274-334) | 키 선택 비교로 "왜 userId인가" |
| I-49 | 좌석 | 키=회차 ID이면 Consumer 3개 중 1개만 일하고 파티션을 10개로 늘려도 결과 같음 | 3개 중 1개 일함 | 1/3 활용 (직접 계산) | 개념 도식(측정 아님) | 설계 | K:276-285 | 파티션 레인 | 오픈 순간 인기 회차 쏠림 |
| I-50 | 좌석 | 좌석별 메시지로 보내면 A는 A1만 잡은 어정쩡 상태 가능, 첫 좌석을 키로 쓰면 A2 요청이 다른 파티션에서 동시 처리됨 | — | — | 개념 도식 | 설계 | K:288-319 | 파티션 레인 | — |
| I-51 | 좌석 | 순수 비동기(202+조회)는 폴링 0.5~1초 간격 필요 | 0.5~1초 폴링 | — | 개념 설명 | 설계 | K:364-367 | 요청 행 도식 | 202 접수만으로 결제 못 넘어가는 UX 충돌 |
| I-52 | 좌석 | 5분 홀드 타이머는 HELD 확정부터 센다. 예시: Consumer 지연으로 30초 뒤 확정되면 클릭부터 세면 결제에 4분 30초만 남음 | 5분, 30초, 4분 30초, 5:30 | 30초 손실 = 5분 대비 10% (직접 계산 30/300) | 가정 예시(막대 비율 9.09%/90.91%) | 설계(가정) | K:371-393 | 타임라인 막대(L372-392) | 타이머 기준을 HELD 확정으로 잡은 이유 |
| I-53 | 좌석 | 발신 유실: send() 결과를 안 기다리면 202 후 메시지 유실 가능. acks=all + enable.idempotence=true + 저장 확인 후 응답, 실패 시 Redis 키 해제 후 503 | acks=all, idempotence=true | — | 브로커 1대, 디스크 소실은 범위 밖 명시 | 설계 | K:403-447 | 흐름 비교 목록 | 202의 함정 |
| I-54 | 좌석 | Outbox는 접수 API에 부적합(DB 쓰기가 다시 병목). Consumer 이후 단계(예매→결제 요청)에서 사용 | — | — | — | 판단 | K:439-447 | Outbox 체인 도식 | Outbox를 어디에 쓰고 어디에 안 쓰는지 |
| I-55 | 좌석 | 혼합형(Claude 에이전트 제안): Redis Lua로 즉시 판정, Kafka는 DB 기록만. 단계 7개: 입장토큰·1인 최대매수 확인 → Lua 전부 비면 둘 다 잡기 → Kafka(키 userId) ack 대기 → 200 {holdId,좌석,expiresAt} → 비동기 Consumer가 PENDING 예매·조건부 UPDATE HELD | 단계 7개 | — | 실패 분기 3종: 409 이미 선점, Kafka 실패 시 Redis 키 삭제+503, DB 제약 위반 시 FAILED+키 해제 | 설계 | K:454-510, 517-538, 651-654 | 혼합형 단계 도식(L471-510, 상황 4종 토글) | Redis에서 진 요청을 DB 앞에서 끊는다 |
| I-56 | 좌석 | 만료의 기준은 DB. Redis TTL을 DB 만료보다 30초 길게(5:00 vs 5:30), 만료 스케줄러가 Redis 키도 지움. 결제 시작(PAYING) 시 Redis 키 TTL을 PERSIST로 제거 | DB 5:00 / Redis TTL 5:30, 차이 30초 | 30÷300=10% 여유 (직접 계산) | 설계값 | 설계 | K:543-577 | 타임라인(L545-564), 체인(L570-576) | Redis가 먼저 풀리면 Redis 성공·DB 실패 혼란 방지 |
| I-57 | 좌석 | v1(개선 전)=API에서 DB 조건부 UPDATE 동기 처리로 기준선 측정, v2(개선 후)=Redis Lua 1차 + Kafka + Consumer. 요청 1,000건은 발제 부하 설정(100명 × 10회) | 요청 1,000건 = 100명 × 10회 | 100×10=1,000 (직접 계산) | 선 굵기는 개념 표현, 측정값 아님 | 설계 | K:600-633 | v1/v2 파이프 도식(L600-621) | 병목을 찾고 바꾼 전/후 스토리 |
| I-58 | 좌석 | 비교표: 순수 비동기는 폴링 필요·정합성 DB 하나, 혼합형은 버튼 즉시 결과·Redis+DB 이중 규칙·구현 난이도 중간. 발제 요구는 둘 다 충족 | — | — | — | 판단 | K:587-598 | 비교 표 | — |
| I-59 | 팀 | 출처: 1차 SA 피드백 M1 항목. 헤더 "1차 SA 피드백 · M1", 팀 결정 10/23 "좌석 점유 시점 → 선택 완료 버튼 눌렀을 때" | 날짜 10/23 | — | — | 설계 | K:222, 250, 642 | — | 좌석 점유 시점이 10/23에 정해짐 |
| I-60 | 팀 | 결정 상태 구분: #19 결정, #9·#10 결정, #14·#15·#21·#23·#30·#48·#56·#57·#59 잠정 결정, #60 제안(10/27 생성) | 결정 3건(#9·#10·#19), 잠정 9건, 제안 1건 | 3+9+1=13 (직접 계산, 표에 나온 항목 한정) | 문서는 "전체 결정 목록 조사 아님" | 설계 | B/source-notes.md:9-23 | — | — |
| J-01 | 결제 | 설계 원칙은 "금전적 의무가 추적 없이 사라지지 않는다"이고 불변 조건 INV-1~INV-9를 먼저 정했다 | INV 9개(INV-1~7 사용자 정함, INV-8·9 기존 결정 정리) | — | 2026-10-27 작성, 10-28 검토·사용자 결정 반영. 문서 시점엔 "구현 전" | 설계 | docs/design/payment/README.md:5-7, invariants.md:3-7 | — | 결제는 "돈이 사라지지 않게"에서 출발 |
| J-02 | 결제 | 결제 행은 예매당 하나(`UNIQUE(reservation_id)` 전체)로 강제한다 | 예매당 결제 ≤ 1 | — | ADR 0026, 사용자 결정 1. 빈틈 #2(부분 유니크에 NMC 누락)를 대체 | 설계 | README.md:40,45, data-model.md:27,53, domain-and-states.md:244,342 | — | 부분 유니크 인덱스의 구멍을 전체 UNIQUE로 막은 순간 |
| J-03 | 결제 | 결제 승인은 TX1(REQUESTED 저장) → PG 호출(트랜잭션 밖) → TX2(조건부 UPDATE)의 10단계 처리 순서다 | 10단계 | — | api.md 1절 | 설계 | api.md:23-34 | api.md:23-34 처리 순서 표 | PG 호출을 트랜잭션 밖에 두는 이유 |
| J-04 | 결제 | PG 승인 호출 시한은 연결 0.5초·응답 2초, TX1·TX2 DB 연결 대기는 각각 1초 `[가정]` | 연결 0.5초, 응답 2초, DB 연결 대기 1초 | — | DB 대기는 `[가정]` 표시. PG 시한은 원문에 가정 표시 없음 | 설계 | api.md:31,33,58 | — | 시간 예산 쪼개기 |
| J-05 | 결제 | 승인 최악 처리 시간 = TX1(연결 대기 1초 이내) + PG 2.5초 + TX2(1초 이내)로 Gateway 응답 시한 3초(SA 9-4)를 넘을 수 있고, 넘으면 504 뒤에도 Payment 처리는 계속된다 | 1 + 2.5 + 1 = 4.5초 대 3초 | 직접 계산: 4.5 / 3 = 1.5배(원문은 합산값을 적지 않음) | `[가정, N29로 확인]` | 설계 | api.md:58, sa-change-requests.md:180(S-26) | — | 3초 안에 안 끝나는 결제를 같은 키 재시도로 받는 설계 |
| J-06 | 결제 | 같은 멱등키 재요청은 저장된 응답, 다른 키로 같은 예매를 재요청하면 기존 결제로 응답하고 새 키는 ALIAS로 기록, PG 재호출 없음 | PG 호출 0회(재요청) | — | ADR 0017·0019 | 설계 | api.md:27,29,53, failure-scenarios.md:9,24,25,27,45 | — | 결제 버튼 연타(E2)에도 결제 1건 |
| J-07 | 결제 | 멱등 기록 보존 24시간, 토큰 만료 후 24시간까지 기존 결제 결과를 같은 키 재요청으로 조회 가능 | 24시간 | — | 설계값 | 설계 | data-model.md:139, contracts.md:44, api.md:28,67 | — | — |
| J-08 | 결제 | 결제 토큰은 처음 HS256·공유키(`PAYMENT_TOKEN_SECRET`)로 구현했다가 ISE-245에서 ES256·개인키(Reservation)/공개키(Payment) 분리로 바꿨다 | HS256 → ES256 | — | ADR 0055, SA 변경 요청 S-31(상태 "제안") | 설계(변경) | api.md:77, contracts.md:15-17,128, sa-change-requests.md:185 | — | 검증만 하는 서비스가 서명도 할 수 있던 구조를 없앤 설계 변경 |
| J-09 | 결제 | 결제 토큰 길이 상한 4,096자, `exp` 허용 오차 0초, `exp` = `hold_expires_at - 10초`(SA 7-3) | 4,096자, 0초, 10초 | — | 4,096자는 `[가정]` | 설계 | api.md:26,67,71, contracts.md:18,29 | contracts.md:35-38 payload 예시 JSON | — |
| J-10 | 결제 | 토큰 `amount`는 JSON 정수, 예시는 amount 50000, reservationId 8100001 | 50000(원) | — | 예시값 | 설계 | contracts.md:28,36-37 | contracts.md:35-38 | — |
| J-11 | 결제 | `payment.result` 토픽: 파티션 키 `reservationId`, 소비자 그룹 `reservation.payment-result`, Outbox 발행, 이벤트 3종(Approved·Declined·Refunded) | 이벤트 3종 | — | 2026-10-29 계약 확정(반효승이 확인 요청 1~7번 "그대로"), 8번은 2026-11-12 확인 | 설계 | contracts.md:5,54-60,82-84,134 | contracts.md:64-73 이벤트 봉투 JSON | 서비스 간 계약을 이슈로 확정 |
| J-12 | 결제 | `payment.command` 토픽은 결제 서비스가 파티션 3·복제 1로 선언(ISE-236, 2026-11-12) | 파티션 3, 복제 1 | — | `KafkaAdmin`이 시작할 때 만들거나 늘림 | 설계 | contracts.md:91 | — | — |
| J-13 | 결제 | 빌링키 자동결제(`BillingPaymentRequested`)에서 `pg-approve` 허가를 못 얻으면 DLQ로 보내지 않고 같은 메시지를 다시 읽으며, 같은 파티션의 뒤 메시지(환불 포함)도 기다린다 | — | — | "D6 기준선의 감수 사항, 측정 시나리오 2에서 확인"(측정 결과는 이 문서에 없음) | 설계(감수한 점) | contracts.md:92 | — | 순서를 지키려고 대기를 감수한 선택 |
| J-14 | 결제 | 추첨 예매는 `reasonCode = NOT_SENT`일 때만 같은 당첨자에게 새 예매로 1회 재시도하고 나머지 사유는 예비자 승계 | 재시도 1회 | — | ADR 0038, 10/31 추첨 담당 선반영, 결제 담당 확인 대기(ISE-89) | 설계 | contracts.md:90 | — | 추첨 결제 실패를 사유별로 나누는 판단 |
| J-15 | 결제 | `NOT_SENT`는 PG 조회가 `NOT_FOUND`이고 같은 주문번호 망취소가 `Prevented`일 때만 부여해 이후 승인이 나지 않음을 보장한다 | — | — | SA 10-5, INV-8 | 설계 | contracts.md:90, invariants.md:116 | — | "모르면 실패로 닫지 않는다" |
| J-16 | 결제 | 대사용 내부 API 두 개(ids 1~100건, 변경 목록 쪽 크기 100 고정). 2026-11-12 로컬 전체 스택(compose)에서 실제 예매 서비스와 붙여 확인(ISE-239)하고 `payment.reconciliation.reservation.enabled`를 `true`로 켬 | ids 상한 100, 쪽 크기 100 | — | 연결 1초·읽기 2초 `[가정]`. 계약 ⑤는 PR #283(ISE-75) 형태 | 설계 + 로컬 확인 | contracts.md:140-150 | — | 대사 API를 실제 서비스와 붙인 날(11/12) |
| J-17 | 결제 | 대사 대량 누락 가드: 한 묶음 2건 이상 물어 하나도 안 오거나, 회차 전체 빠진 예매가 5건 이상이고 10% 초과면 조회 실패 처리. 변경 목록 회차당 쪽 수 예산 200 | 2건, 5건, 10%, 200쪽 | — | 모두 `[가정]` | 설계 | contracts.md:151 | — | — |
| J-18 | 결제 | 404를 "예매 없음"으로 읽지 않는다. 경로 오류 때 모든 승인 결제가 RC-2로 잘못 기록되는 것을 막으려는 판단 | — | — | 2026-11-03 결정(어댑터를 예매 쪽 응답 형식에 맞춤) | 판단 | contracts.md:140,150 | — | 응답 형식 어긋남을 어댑터 쪽에서 맞춘 결정 |
| J-19 | 결제 | 결제 상태 6종(REQUESTED·UNKNOWN·APPROVED·NEEDS_MANUAL_CHECK·DECLINED·REFUNDED), 환불 상태 5종(+FAILED). 종결은 결제 DECLINED·REFUNDED, 환불 REFUNDED·FAILED | 결제 6종, 환불 5종 | — | domain-and-states.md | 설계 | domain-and-states.md:246-266,296-302 | domain-and-states.md:248-257, 288-294 ASCII 상태 머신 2개 | 상태 머신 그림 |
| J-20 | 결제 | NEEDS_MANUAL_CHECK는 SA 8-2가 "종결"로 적은 것을 "운영자 확인 필요"로 바꿨고, 환불 FAILED도 PG 거절 즉시 FAILED가 아니라 NMC(REFUND_REJECTED)로 기록한다 | — | — | SA 변경 요청 S-5 | 설계(변경) | domain-and-states.md:269,304, sa-change-requests.md:159 | — | 종결 정의를 바꾼 이유 |
| J-21 | 결제 | 상태 전이는 조건부 UPDATE(`WHERE status = 이전상태`)이고 영향 행 1일 때만 이력·Outbox·멱등 응답을 쓴다. dirty checking 금지 | 영향 행 1 | — | SA 8-4, INV-5 | 설계 | domain-and-states.md:244,332-333 | — | 경합에서 진 쪽이 이벤트를 안 내는 규칙 |
| J-22 | 결제 | 재확인 스케줄러: REQUESTED는 TX1에서 `next_check_at = 저장 시각 + 10초`, PG 호출 9회 소진 시 NMC(PG_UNRESOLVED) | 10초, 9회 | — | 설계 | 설계 | domain-and-states.md:261,281, data-model.md:39, api.md:30 | — | — |
| J-23 | 결제 | 환불 워커 재시도는 PG 호출 9회·약 21분 뒤 NMC | 9회, 약 21분 | — | 원문 표기 "약 21분" | 설계 | invariants.md:75 | — | — |
| J-24 | 결제 | 환불은 결제당 1건·전액만(`UNIQUE(payment_id)`), 환불 금액은 PG 승인 금액을 `INSERT … SELECT … ON CONFLICT DO NOTHING`으로 복사 | 결제당 환불 1건 | — | SA #16 | 설계 | data-model.md:96-98,115-124, domain-and-states.md:306 | data-model.md:115-124 SQL | — |
| J-25 | 결제 | DB 제약: `chk_payments_approved`는 APPROVED면 `pg_approved_amount = amount`와 `pg_tx_id`·`approved_at`을 강제 | payments CHECK 4개 | — | 마이그레이션 테스트가 카탈로그로 확인 | 설계 | data-model.md:19,47-50 | — | DB가 마지막 방어선 |
| J-26 | 결제 | 감사 테이블(`payment_status_histories`, `manual_action_logs`)은 UPDATE·DELETE·TRUNCATE를 트리거가 거부(append-only). 같은 DB 계정이 트리거를 끌 수 있어 계정 분리는 후속 | 트리거 4개 | — | ISE-158, V8 | 설계 | data-model.md:74-89, invariants.md:107,141 | data-model.md:77-84 SQL | 불변 조건의 한계를 문서에 적은 대목 |
| J-27 | 결제 | Flyway 마이그레이션 V1~V9(+V10 `pg_provider`, ISE-231). V6는 같은 날 먼저 머지된 V5와 버전이 겹쳐 V5에서 V6으로 옮김(ISE-155) | V1~V10 | — | — | 설계(이력) | data-model.md:7-17,33 | — | 마이그레이션 버전 충돌을 푼 사례 |
| J-28 | 결제 | 빌링키 등록 횟수 상한: 분당 3회·하루 10회, 슬라이딩 윈도 로그, 429 + `Retry-After`. 메모리에만 있어 재시작하면 초기화, 인스턴스마다 따로 센다 | 분 3회, 일 10회 | — | ISE-241, `[가정]`. 결제 서비스 1대 배포 전제 | 설계 | data-model.md:230-237, api.md:3 | — | check-then-act 경쟁을 `ConcurrentHashMap.compute` 한 구역으로 막은 이유 |
| J-29 | 결제 | 빌링키 상한에서 고르지 않은 대안: DB 시도 테이블, Redis 카운터, Gateway IP 제한. 여러 인스턴스로 늘면 Redis 카운터 재검토 | — | — | 결제 서비스가 Redis를 쓰지 않는다는 전제 | 판단 | data-model.md:237 | — | 선택지 비교 → 이유 → 감수 |
| J-30 | 결제 | 빌링키 암호화는 AES-256-GCM(`BILLING_KEY_SECRET`, ADR 0042), 사용자당 ACTIVE 1개 | 256비트 | — | ISE-96, 11/2 M2로 당겨 구현 | 설계 | data-model.md:224-228 | — | — |
| J-31 | 결제 | Outbox 임대 30초 `[가정]`이고 릴레이 전송 시한(12초)의 2배 이상, 전송 시한은 프로듀서 `max.block.ms + delivery.timeout.ms` 이상(기동 때 검증) | 임대 30초, 전송 시한 12초 | 직접 계산: 30 / 12 = 2.5배(원문은 "2배 이상") | 기동 시 검증 | 설계 | data-model.md:157, invariants.md:87 | — | — |
| J-32 | 결제 | 보관: 결제 기록 5년(자동 삭제 없음), 정리 대상은 `outbox_events`·`processed_events`(14일)·`idempotency_records`뿐. Outbox 보관 7일, DLQ(`.dlq`) 보관 7일 | 5년, 14일, 7일 | — | SA 12-1 | 설계 | data-model.md:164,241, invariants.md:74, sa-change-requests.md:164 | — | — |
| J-33 | 결제 | 승인 API의 `pgProvider`(MOCK·TOSS)로 결제별 PG를 고르고, 토스 모드에서 MOCK을 명시하면 결제창 없이 Mock으로 승인하는 "Mock 건너뛰기"(시연용, `mock-bypass-enabled` 기본 꺼짐) | 기본 꺼짐 | — | 11/10 개정(ADR 0023), ISE-231 | 시연 / 설계 | api.md:7,12-17,94-100, data-model.md:33 | api.md:11-17 | 토스 연동 시연에서 Mock 건너뛰기 버튼 |
| J-34 | 결제 | 토스 결제창은 `POST /api/v1/payments/checkout`이 `orderId`·`clientKey`·`amount`를 돌려주고, 성공 주소로 돌아온 `paymentKey`를 승인 요청의 `clientPaymentRef`로 보낸다 | — | — | 2026-11-09 구현(ISE-45), 프런트 ISE-125 | 설계(구현됨) | api.md:85-100, sa-change-requests.md:168 | — | 토스 결제창 연동 흐름 |
| J-35 | 결제 | 결제 조회 접근 제어: 소유자 또는 ADMIN, 타인·없음은 같은 404(본문 동일), 타인 접근은 WARN 로그 | — | — | ADR 0021 | 설계 | api.md:102-111 | — | IDOR 방어 |
| J-36 | 결제 | 알려진 경합: 요청 스레드가 TX1 뒤 오래 멈춘 사이 재확인이 REQUESTED를 바꾸면 TX2가 0행이 되고, UNKNOWN이면 다음 간격에 PG 조회로 APPROVED 확정. 남는 위험은 PG가 계약을 어기는 경우(ERROR `PG_APPROVED_NOT_RECORDED`) | — | — | INV-8 남는 위험 #5 | 설계(위험) | api.md:82 | — | 경합의 위험을 문서에 먼저 적음 |
| J-37 | 결제 | compose 수동 확인(2026-11-03): `docker pause`로 결제 Postgres를 얼리고 승인을 보냈더니 쿼리가 멈춰 503이 아니라 Gateway 시한(3초)을 넘긴 504가 났다. 연결 대기 시한은 빈 연결이 없을 때만 걸려 빌린 연결의 쿼리에는 시한이 없었다 | 504(Gateway 시한 3초 초과) | — | 로컬 compose, 수동 확인 | 로컬 측정(수동) | failure-scenarios.md:134 | — | 트러블슈팅 반전: DB가 얼면 503이 아닌 504 |
| J-38 | 결제 | 위 문제를 ISE-163에서 소켓 시한 2초·문장 시한 1.5초로 막았고, 같은 방법으로 세 번 다시 확인하니 모두 Gateway를 거쳐 2.0초 만에 503 `SERVICE_UNAVAILABLE`(원인 `SocketTimeoutException`)이 왔다. 결제 행은 생기지 않았고 풀린 뒤 같은 키 재시도는 200 `APPROVED`, 결제·Outbox 각 1건 | 504(3초 초과) → 503(2.0초) | 직접 계산: 최소 1.0초 이상 단축(수정 전 값은 "시한 초과"로만 적혀 정확한 초는 확인 못 함) | 2026-11-03, 로컬 compose, 3회 | 로컬 측정(수동) | failure-scenarios.md:134 | — | 같은 장애, 수정 전후 504에서 503 |
| J-39 | 결제 | 위 compose 확인에서 릴레이 같은 배경 작업이 연결을 0.5초 안에 계속 써서 Hikari 생존 검사를 건너뛰었을 가능성이 크다는 해석을 적었다(검증하지 않은 해석) | 0.5초 | — | 원문의 "가능성이 크다" 표시 | 판단(추정) | failure-scenarios.md:134 | — | — |
| J-40 | 결제 | 컨테이너를 `kill -9`로 죽이는 확인은 compose Mock PG의 장애 주입이 닫혀 있어(local 프로필) PG 호출 도중을 만들 수 없어 하지 않았다 | — | — | 확인 못 함 | 확인 못 함 | failure-scenarios.md:134 | — | — |
| J-41 | 결제 | FT-4 (e) TX1 멈춤: 503, 경과 2.3초(Gateway 시한 3초 안) | 2.3초 | — | 프록시 `freeze()`, 한 번 실행한 값 | 로컬 측정(테스트) | failure-scenarios.md:165 | — | — |
| J-42 | 결제 | FT-4 (f) TX2 멈춤: 202 `REQUESTED`, 경과 2.0초 | 2.0초 | — | 한 번 실행한 값 | 로컬 측정(테스트) | failure-scenarios.md:166 | — | — |
| J-43 | 결제 | FT-4 (g) 문장 시한: `SELECT pg_sleep(3)`가 SQLState 57014로 실패, 경과 1.5초 | 3초 sleep → 1.5초에 중단 | 직접 계산: 3 / 1.5 = 2배 | 한 번 실행한 값 | 로컬 측정(테스트) | failure-scenarios.md:167 | — | 쿼리 시한이 실제로 걸리는 증거 |
| J-44 | 결제 | FT-4 (h) 변이 확인: `validation-timeout`을 기본값 5000으로 두면 5.9초로 실패(원래 설정은 Gateway 3초 안) | 5.9초(기본 5000ms) | 직접 계산: 5.9 / 3 = 약 1.97배(시한 대비) | 변이 확인 | 로컬 측정(변이) | failure-scenarios.md:168 | — | 설정 하나가 3초 예산을 깨는 증거 |
| J-45 | 결제 | FT-1~FT-6 장애 시나리오 결과가 모두 V1~V3 통과(승인 중복 없음, 환불 중복 없음, 결제↔예매 최종 정합) | 시나리오 FT-1~FT-6, 검증 V1~V3 | — | 로컬 Testcontainers + 실제 Mock PG 프로세스, ISE-161·162 | 로컬 측정(테스트) | failure-scenarios.md:99-195 | failure-scenarios.md:101-111,143-185 결과 표 | 여러 장애에서도 결제 1건·환불 1건 |
| J-46 | 결제 | FT-1 (a): 같은 예매로 6건 동시(같은 키·새 키 섞음) → 응답 200·202만(500 없음), 결제 1건, `approveCalls` 1 | 동시 6건 → 결제 1건, approveCalls 1 | 직접 계산: 6 → 1 | 로컬 시나리오 테스트 | 로컬 측정(테스트) | failure-scenarios.md:104 | — | 동시 6건에도 결제 1건 |
| J-47 | 결제 | FT-1 (a): 같은 키 3회·새 키 2회 → 결제 1건 APPROVED, `approveCalls` 1, `PaymentApproved` 1건 | 5회 요청 → 승인 호출 1회 | — | 로컬 시나리오 테스트 | 로컬 측정(테스트) | failure-scenarios.md:103 | — | — |
| J-48 | 결제 | FT-2 (c): 환불이 PG에 닿지 않으면 `UNKNOWN → NotFound → 같은 pg_refund_id로 재호출 → REFUNDED`, `refundCount` 1, `refundCalls` 2 | refundCalls 2, refundCount 1 | — | 로컬 시나리오 테스트 | 로컬 측정(테스트) | failure-scenarios.md:110 | — | 재호출해도 환불은 1건 |
| J-49 | 결제 | FT-2 (d): 승인이 PG에 닿지 않으면 `UNKNOWN → NotFound → 늦은 승인 방지 → DECLINED(NOT_SENT)`, 같은 주문의 늦은 승인은 PG가 받지 않음(원장 CANCELED, 승인 0건) | — | — | 로컬 시나리오 테스트 | 로컬 측정(테스트) | failure-scenarios.md:111 | — | 닫은 뒤 늦은 승인 방지 |
| J-50 | 결제 | FT-6 금액 불일치: 승인 호출 금액을 1,000원 줄이면 NMC(`PG_AMOUNT_MISMATCH`), `pg_approved_amount` 49,000, `payment resolve` 뒤 환불 49,000·`PaymentRefunded` 49,000 1건 | 1,000원 감소, 49,000 | 직접 계산: 49,000 + 1,000 = 50,000(원 결제 금액은 원문에 직접 없고 추정) | 로컬 시나리오 테스트 | 로컬 측정(테스트) | failure-scenarios.md:139,185 | — | 금액 불일치는 자동 확정하지 않고 환불 |
| J-51 | 결제 | ISE-161 변이 확인에서 시나리오가 금액 불일치 경로를 지나지 않는 것이 드러나 FT-6을 더했다 | — | — | 변이 테스트, 코드는 되돌리고 커밋하지 않음 | 판단(변이 검증) | failure-scenarios.md:119,187-194 | — | 변이 테스트가 테스트의 빈틈을 찾은 사례 |
| J-52 | 결제 | 변이 확인 4건: 환불 워커가 `findRefund` 없이 `refund` 호출 → FT-3(d)·FT-4(d)·FT-2(b)(c) 실패, 복구 CB OPEN일 때 시도 횟수 증가 → FT-5(a) 실패, `--close-failed`의 `external_ref` 검사 삭제 → FT-5(c) 실패, TX2 금액 대조 삭제 → FT-6 실패(DB CHECK가 막아 202가 됨) | 변이 4건 모두 시나리오 실패 | — | 변이 후 코드 되돌림 | 로컬 측정(변이) | failure-scenarios.md:187-194 | failure-scenarios.md:189-194 변이 표 | 같은 실패를 두 방어선이 잡는지 증명 |
| J-53 | 결제 | 시나리오 기다림은 시계를 앞으로 당겨 없애고 `sleep`을 쓰지 않는다. `recoverUntilIdle()` 상한 40바퀴 | 40바퀴 | — | 테스트 방식 | 설계(테스트) | failure-scenarios.md:89,94 | — | — |
| J-54 | 결제 | 장애 테스트 프로필은 대사 허용 지연 5초 `[가정]`, V3 검증기의 강제 대사는 기준 시각을 허용 지연 10분 뒤로 줌 | 5초, 10분 | — | 5초는 `[가정]` | 설계(테스트) | failure-scenarios.md:63,96 | — | — |
| J-55 | 결제 | 단일 방어 점검 9건: 방어 수단이 하나뿐이던 곳과 보강(예: `pg_approved_amount` + CHECK, 대사 RC-2·5·7·8). 남는 위험을 후속 과제로 명시 | 점검 9건 | — | 2026-10-28 사용자 원칙 | 설계 | invariants.md:132-146 | invariants.md:136-146 표 | "한 장치만 믿지 않는다" |
| J-56 | 결제 | 남는 위험: 24시간을 넘긴 누락은 RC-8 범위 밖, PG 정산 자료 대사 없음(후속), SASL은 MVP 밖 | 24시간 | — | invariants 3절 | 설계(위험) | invariants.md:140-142,146 | — | 한계를 숨기지 않는 태도 |
| J-57 | 결제 | SA 변경 요청 목록은 S-1~S-31(README는 S-1~S-30으로 적음). 상태: 반영됨(S-3·S-11·S-18), PR1~PR3 반영 다수, S-14 보류, S-30 SA 수정 대상 아님, S-31 제안 | S-1~S-31 | 모순: README는 "S-1~S-30", sa-change-requests는 S-31까지 | 상태는 2026-11-09 origin/dev 1bcabfc 기준으로 직접 확인한 값 | 설계 + 모순 | README.md:24, sa-change-requests.md:151-185 | — | 설계가 SA와 달라진 지점을 별도 PR로 묶음 |
| J-58 | 결제 | SA 변경 요청 PR 3묶음(PR1 돈·예매 계약, PR2 API 동작, PR3 운영·인프라·Mock PG). PR #393·#405·#406이 Notion SA MASTER v3.3에 반영(2026-11-10, ISE-32) | PR 3개(#393·#405·#406) | — | 별도 리뷰 합의(통합 회의 D9) | 설계(이력) | sa-change-requests.md:187-195 | — | SA 문서와 구현의 괴리를 PR로 정리 |
| J-59 | 결제 | `config-repo/payment-service.yml`은 `maximum-pool-size: 20`, `connection-timeout: 1000`(ms)으로 SA 9-4와 같다. S-21 요청 당시에는 5였다 | 5 → 20(커넥션 풀 크기), 연결 대기 1000ms | 직접 계산: 5 → 20 = 4배 | S-21 요청 당시 5, 지금은 반영됨 | 설계(변경) | sa-change-requests.md:175 | — | 커넥션 풀 크기 정렬 |
| J-60 | 결제 | Kafka-UI READONLY: 배포 서버(2026-11-10, ISE-72)에서 `/api/clusters`가 `readOnly: true`를 반환. 실제 발행 시도로 막히는지는 운영 서버에 쓰기 요청을 보내지 않으려고 확인하지 않음 | readOnly true | — | 2026-11-10 배포 서버 확인, 발행 시도는 확인 못 함 | 배포 확인(부분) | sa-change-requests.md:178 | — | 이벤트 위조 경로를 관리 UI에서 닫음 |
| J-61 | 결제 | 배포는 `compose.deploy.yml`에서 mock-pg를 `loadtest` 프로필로 띄워 `/faults` 404 문제를 해결했다. 로컬 `compose.yml`은 `local` 그대로 | — | — | S-30 | 설계(인프라) | sa-change-requests.md:184 | — | 부하 측정에서 장애 주입이 열려야 했던 이유 |
| J-62 | 결제 | 결제 ADR 14건(0016~0029)이 이 설계의 결정 기록이고, 출처 칸의 C·H·M·L 번호는 2026-10-27 설계에 대한 Opus 검토 지적이다. 사용자 결정 1·4·5·6이 0026·0027·0028·0029 | ADR 14건 | — | AI 판단 기록 `ai-judgments/junhyun/2026-10-27-payment-design-gaps.md`(내용은 읽지 않음) | 판단(AI 검토 수용) | README.md:28-46 | README.md:28-45 ADR 표 | AI 설계 검증 로그의 원천(결제 설계 빈틈 #1~#11) |
| J-63 | 결제 | 결제 담당이 Mock PG 환불 조회·멱등·망취소, 장애 주입 모드 등을 함께 구현한다(10/28 역할 변경, ADR 0023) | — | — | 10/28 | 팀(역할) | README.md:56, sa-change-requests.md:182-183 | — | 역할 변경 기록 |
| J-64 | 결제 | 환불 워커는 재호출 전에 환불 조회부터 한다. PG 멱등키 하나에만 기대던 환불 재호출 방어를 보강하고, 토스 멱등키 15일 만료 뒤 재시도 위험까지 고려 | 15일(토스 멱등키 만료) | — | 원문에 `[가정]` 표시 | 설계 | invariants.md:49,144 | — | 외부 시스템의 멱등키 수명까지 고려 |
| J-65 | 결제 | 유니크 위반을 500으로 내보내지 않고 제약별로 나눠 재조회(멱등 PK·`reservation_id`·`pg_order_id`) 또는 409 `PAYMENT_ALREADY_EXISTS`(`pg_tx_id`) | — | — | api.md 3절 | 설계 | api.md:188 | — | 유니크 위반은 정상 흐름 |
| K-01 | 결제 | 결제 결과는 Outbox로 발행하고 소비는 처리 이력·상태 전이로 멱등, 환불 요청은 PG 호출 전 DB에 영속화 | — | — | 설계 원칙(INV-2·4·5·6) | 설계 | events-and-reliability.md:3 | — | 돈이 걸린 결제는 "확정 못 하면 DB 상태로 남겨 누군가 이어받는다" |
| K-02 | 결제 | Outbox 릴레이는 500ms마다 PENDING 행을 SKIP LOCKED로 집고 임대 30초 | 주기 500ms, 임대 30초 | — | 전용 실행기 1스레드 | 설계 | events-and-reliability.md:32 | — | 중복 전송은 허용하고 소비자 멱등으로 막는 선택 |
| K-03 | 결제 | 프로듀서 delivery.timeout 10초, request.timeout 5초, linger 0, 전송 대기는 delivery.timeout+2초, 임대는 전송 시한 2배 이상 | 10000ms / 5000ms / 0ms / 12초 / 30초 | 임대 30초 ÷ 전송 시한 12초 = 2.5배 (직접 계산) | 값은 `[가정]` | 설계 | events-and-reliability.md:33 | — | 임대가 짧으면 순서 뒤섞인 중복이 생기는 이유 |
| K-04 | 결제 | 재시도 지연은 equal jitter 적용 min(500ms×2^(k-1), 60s)의 50~100%, 횟수로 포기하지 않음 | 500ms 시작, 상한 60초, 8번째 실패부터 60초 | — | ISE-145 | 설계 | events-and-reliability.md:35, 43 | — | 완전 무작위(full jitter)를 안 쓴 이유: 0에 가까운 지연이 장애 브로커를 즉시 재타격 |
| K-05 | 결제 | 영구 오류(직렬화 실패·레코드 크기 초과·배치 크기 초과·잘못된 토픽)만 FAILED로, 같은 키의 뒤 행은 FAILED가 풀릴 때까지 안 집음 | 4종 분류 | — | `OutboxFailureClassifier` | 설계 | events-and-reliability.md:36, 45, 47 | — | 모르는 예외를 FAILED로 보내면 정상 이벤트 뒤가 멈추므로 일시 오류로 취급 |
| K-06 | 결제 | 임대를 바로 풀어 next_attempt_at이 재시도 간격을 정함(임대 만료 30초가 간격이 되면 백오프 무의미) | attempts는 SMALLINT 상한 32767에서 멈추고 60초 간격 계속 | — | ISE-145 | 설계 | events-and-reliability.md:44 | — | 백오프가 실제로 동작하게 만든 세부 |
| K-07 | 결제 | Kafka 장애에서 승인 API가 200을 주고 Outbox가 쌓였다 복구 뒤 SENT가 되는 흐름(N10)은 가짜 sender로만 확인, 실제 브로커 장애 주입은 안 함 | — | — | `OutboxBrokerOutageApiTest` | 설계(테스트) | events-and-reliability.md:48 | — | "확인 못 한 것"을 밝힌 사례 |
| K-08 | 결제 | 소비자는 환불 요청을 검증·영속화만 하고 PG 환불은 안 부름. PG 호출은 환불 워커 한 곳 | — | — | ADR 0020 | 설계 | events-and-reliability.md:52 | — | PG 호출 지점을 한 곳으로 모아 이중 환불 방어 |
| K-09 | 결제 | 일시 오류는 1·2·4초 간격 3회 재시도 뒤 DLQ, 검증 실패·역직렬화 실패는 즉시 DLQ | 1·2·4초 3회 | — | `ack-mode=record` | 설계 | events-and-reliability.md:57, 70 | — | — |
| K-10 | 결제 | 11/10 배포 측정에서 소비자 1개가 추첨 자동결제를 초당 약 3.2건만 처리(PG 응답 직렬 대기). 소비자 3개면 계산값 초당 약 9.5건 | 약 3.2건/초 → 약 9.5건/초(계산값) | 약 3배 (9.5÷3.2≈2.97, 직접 계산) | 11/10 배포 측정, ISE-236, 동시성 1→3 | 배포 측정(전) / 계산값(후) | events-and-reliability.md:64 | — | 추첨 자동결제 병목: 소비자 직렬 처리 → 파티션 3·동시성 3 |
| K-11 | 결제 | 파티션·순서: `payment.command` 파티션 3, 키 reservationId라 같은 예매의 자동결제·환불 명령은 같은 파티션(키 기준 순서 유지), 동시성은 파티션 수 이하 | 파티션 3, 동시성 기본 3 | — | ISE-236 | 설계 | events-and-reliability.md:65 | — | 순서 보장을 지키며 병렬도 올린 선택 |
| K-12 | 결제 | 로컬 전체 스택(compose `ise236`, 2026-11-12)에서 파티션 1→3 증가, 소비자 3개가 파티션 0·1·2를 하나씩 담당, Bulkhead 값 pg-approve 30·pg-billing 3·pg-recovery 2 확인 | PartitionCount 1 → 3 | 3배 (3÷1) | 로컬 실제 브로커 2026-11-12 | 로컬 측정 | events-and-reliability.md:66 | — | 설계가 실제 브로커에서 붙는 것 확인 |
| K-13 | 결제 | 자동결제 2,000건의 처리 속도(계산값 초당 9.51건)와 파티션 3개 순서는 실제 브로커로 재지 않음 | 9.51건/초(계산값), 2,000건 | — | 순서는 `PaymentCommandConcurrencyTest`, 속도는 추첨 쪽 배포 재측정 | 계산값(실측 아님) | events-and-reliability.md:66 | — | "확인 못 함"을 명시한 예 |
| K-14 | 결제 | 허가 대기는 그 파티션만 멈춤: pg-billing 허가를 못 얻으면 같은 레코드를 1초 간격으로 재읽기, 다른 두 파티션은 계속 | 1초 간격 | — | — | 설계 | events-and-reliability.md:67 | — | 격벽(Bulkhead) 단위가 파티션 |
| K-15 | 결제 | 파티션을 늘리는 순간 같은 키가 다른 파티션으로 갈 수 있어 추첨 자동결제 중에는 배포 금지(소비자 랙 0일 때 배포) | — | — | — | 판단/운영 규칙 | events-and-reliability.md:69 | — | — |
| K-16 | 결제 | DLQ 처리기: 검증 통과·환불 행 없음 → 환불 REQUESTED 생성(source=DLQ), 환불 행 있음 → DUPLICATE, 검증 실패 → OPEN+즉시 알림. DB 장애는 오프셋 미커밋·지수 백오프(최대 60초) 무제한 재처리 | 최대 백오프 60초, `.dlq` 보관 7일`[가정]` | — | ADR 0020 | 설계 | events-and-reliability.md:90-100, 109 | — | 환불 의무가 지워지지 않게 건너뛰기를 막은 설계 |
| K-17 | 결제 | DLQ 백오프 최대 60초는 max.poll.interval.ms(기본 5분)보다 작게 둬야 리밸런스 반복을 피함 | 60초 < 5분 | — | — | 설계 | events-and-reliability.md:110 | — | — |
| K-18 | 결제 | 재확인 스케줄러: 주기 1초, 주기당 최대 20건`[가정]`, pg-recovery 허가를 먼저 얻고 claim(임대 30초). 허가 못 얻으면 시도 횟수 안 셈 | 1초, 20건, 임대 30초 | — | `[가정]` | 설계 | events-and-reliability.md:118-120 | — | "허가 먼저, claim 나중" |
| K-19 | 결제 | 재확인 간격은 10초×6회 → 1분 → 5분 → 15분, 9번째 호출도 모르면 NEEDS_MANUAL_CHECK, 약 22분 | 1320초 = 10×6+60+300+900 (원문 계산) | 1320초 ÷ 60 = 22분 | SA 9-4 | 설계(계산값) | events-and-reliability.md:143 | — | 자동 복구의 끝점을 숫자로 못 박음 |
| K-20 | 결제 | 환불은 첫 호출이 바로 나가 총 시간이 재확인보다 10초 짧음 | 10초 차이 | — | ISE-137 | 설계 | events-and-reliability.md:150 | — | — |
| K-21 | 결제 | 환불 워커는 PG 호출 전 결제 상태를 읽어 맞지 않으면 PG를 부르지 않고 STATE_CONFLICT로 넘김(돈이 나간 뒤 결제가 환불 완료로 못 옮겨지는 일 방지) | — | — | 설계 표에 없던 추가 경로 | 판단 | events-and-reliability.md:152 | — | 설계표를 넘어선 방어 추가 |
| K-22 | 결제 | Ops `refund retry`는 `pg_requested_at`을 비우지 않음: 비우면 findRefund 없이 refund부터 시작해 이중 환불 방어가 멱등키 하나에만 의존 | — | — | ISE-160 결정 | 판단(결정) | events-and-reliability.md:156 | — | 이중 환불 방어를 한 겹 더 둔 결정 |
| K-23 | 결제 | 토스 멱등키 유효기간 15일 뒤 재시도도 findRefund 선행 순서가 이중 환불을 막음 | 15일 | — | 토스 문서 | 설계 | pg-integration.md:64 | — | — |
| K-24 | 결제 | 기본 스케줄러 스레드 1개면 PG 재조회가 느려질 때 Outbox 릴레이도 멈춰 선점 만료 위험 → 실행기를 성격별로 분리, 결제는 `@Scheduled` 안 씀 | outbox-relay 1스레드/연결1, recovery 2/2, housekeeping 1/1, Kafka 리스너 3+1스레드/연결4 | 연결 합 1+2+1+4=8 (원문 합산) | `[가정]` 일부 | 설계 | events-and-reliability.md:161-168, 175 | — | 한 스레드 풀이 모든 걸 멈추는 문제를 실행기 분리로 해결 |
| K-25 | 결제 | 배경 작업은 PG 호출 동안 DB 연결을 안 잡아, 동시에 잡는 연결 최대 8개 | 최대 8개 | — | 자동결제도 TX1·TX2에서만 연결 | 설계 | events-and-reliability.md:175 | — | PG 호출을 트랜잭션 밖으로 |
| K-26 | 결제 | 연결 풀은 SA 9-4의 서비스당 20으로 고쳐야 함. 당시 config-repo는 maximum-pool-size 5라 배경 작업만으로 풀이 찰 수 있었음, connection-timeout 1초`[가정]` | 5 → 20 | 4배 (20÷5) | S-21, 기본 30초면 Gateway 3초 초과 | 설계(수정 항목) | events-and-reliability.md:176 | — | 풀 크기 5가 숨은 병목이 될 뻔한 사례 |
| K-27 | 결제 | DB가 응답 없이 멈추면 요청 스레드가 시한 없이 기다려 Gateway 504가 남(2026-11-03 compose에서 `docker pause`로 확인) → hikari 시한 4종 | connection-timeout 1000ms, validation-timeout 1000ms, statement_timeout 1500ms, socketTimeout 2초 | validation-timeout 기본 5초 → 1초 (5배 단축, 직접 계산) | ISE-163, 값은 `[가정]` | 로컬 측정(재현) + 설계 | events-and-reliability.md:177-181 | — | DB 정지 때 504가 나던 문제를 시한 4겹으로 닫음 |
| K-28 | 결제 | 시한 적용 후 승인 요청은 DB 정지 시 PG 호출 전 구간에서 1~2초 안에 503으로 끊김 | 연결 1초 / 첫 문장 2초 | — | 문장마다 1.5초로 느리면 합이 3초 초과 가능(504, N29) | 설계 | events-and-reliability.md:182 | — | — |
| K-29 | 결제 | 한계: TX1 커밋 응답 유실 시 REQUESTED 결제가 남고 재확인이 NOT_SENT로 DECLINED 처리, 이 예매는 재결제 불가(PAYMENT_ALREADY_CLOSED). 승인 0건이라 돈은 안전. 재시도로 이어받는 안은 INV-1 위반 위험으로 거부 | — | — | ADR 0048 | 판단(거부 포함) | events-and-reliability.md:183 | — | 안전(돈)과 편의(재결제) 중 돈을 고른 거부 판단 |
| K-30 | 결제 | Kafka 소비자는 DB 정지 시 시도마다 1~2초에 실패, 1·2·4초 재시도 후 약 10~15초 넘게 멈추면 DLQ | 10~15초 | — | — | 설계 | events-and-reliability.md:185 | — | — |
| K-31 | 결제 | Ops 명령 JVM은 소켓 시한 30초·문장 시한 15초로 늘림(부하 테스트 뒤 커진 Outbox 조회 대비) | 2초→30초, 1.5초→15초 | 각 15배, 10배 (직접 계산) | `payment-service-ops.yml` | 설계 | events-and-reliability.md:186 | — | — |
| K-32 | 결제 | 11/12 배포 측정에서 결제 서비스 재기동 직후 첫 단계(초당 20건) 승인 p99가 세 번 모두 1.0~1.4초, 다음 단계는 약 0.33초 | p99 1.0~1.4초 → 약 0.33초 | 약 3~4배 (1.0÷0.33≈3.0, 1.4÷0.33≈4.2, 직접 계산) | 11/12 배포, 3회, ISE-243 | 배포 측정 | events-and-reliability.md:191 | — | 기동 직후 콜드 스타트 지연 반전 |
| K-33 | 결제 | 같은 구간 Prometheus 최대값: 승인 1.4~2.0초, PG 호출 0.45~0.55초(Mock 지연 300ms 포함), 첫 findByReservationId 0.6~0.9초, saveAndFlush 0.11~0.12초, 커넥션 획득 0.03~0.16초. 원인이 첫 실행 초기화인지 JIT 전 느린 실행인지는 가르지 못함 | 위 구간값 | — | 11/12 배포 | 배포 측정 | events-and-reliability.md:191 | — | 병목 분해: 첫 JPA 조회가 가장 큼 |
| K-34 | 결제 | 기동 웜업(ISE-243): 읽기 전용 db·token·pg 세 단계를 Tomcat 시작 전에 동기 실행, 쓰기 경로는 안 데움. 기본 iterations 20, max-duration 5초 | 20회, 5초 상한 | — | `payment.warmup.*` | 설계 | events-and-reliability.md:192-199 | — | 콜드 스타트 완화책 |
| K-35 | 결제 | 웜업 로컬 값(실제 DB, 가짜 Mock PG, 20회): db 첫 회 184ms·둘째 4.4ms·나머지 평균 2.1ms, token 18ms·0.2ms·0.2ms, pg 37ms·1.1ms·0.7ms, 전체 301ms. 배포 값은 측정 전 | db 184ms → 4.4ms → 2.1ms | db 첫 회÷둘째 회 = 약 41.8배 (184÷4.4, 직접 계산) | 로컬, `PaymentWarmupIntegrationTest` | 로컬 측정 | events-and-reliability.md:201 | — | 첫 호출이 40배 느린 콜드 스타트 수치 |
| K-36 | 결제 | 웜업 한계: 20회로는 JIT C2까지 못 데움(수천 회 필요), 쓰기 경로 첫 비용 0.11~0.12초 남음, 인스턴스 1대라 기동 공백은 그대로 | — | — | — | 설계(한계) | events-and-reliability.md:202 | — | — |
| K-37 | 결제 | 정리 작업 보관: outbox SENT 7일, processed_events 14일, idempotency_records 24시간. 배치 1,000건, 10분`[가정]` 주기 | 7일/14일/24시간, 1,000건, 10분 | — | ADR 0022 | 설계 | events-and-reliability.md:206-212 | — | — |
| K-38 | 결제 | 정리 배치: batch-size 1,000, 쉼 100ms, 주기당 최대 100배치(작업당 최대 10만 행) | 1,000 / 100ms / 100 | 1,000×100 = 10만 행 (원문) | ISE-146 | 설계 | events-and-reliability.md:218, 221 | — | — |
| K-39 | 결제 | 중복 실행 방지를 세션 advisory lock 대신 `pg_try_advisory_xact_lock`(트랜잭션 잠금)으로 바꿈: 세션 잠금은 풀 연결을 정리 끝까지 붙잡고 반납을 잊으면 잠금이 남음. 잠금 키 146001~146003, 대사는 167000+순번 | — | — | ADR 0022 결정 6과 다름(ADR 본문은 안 고침) | 판단(변경) | events-and-reliability.md:219, 255 | — | ADR을 구현이 대체한 순간 |
| K-40 | 결제 | 기동 검증: processed-events-retention이 max(topic-retention, dlq-retention)보다 짧거나 같으면 기동 실패 | 14일 > 7일 | 14일÷7일=2배 (직접 계산) | `IllegalArgumentException` | 설계 | events-and-reliability.md:209, 222 | — | 설정 불일치를 기동 단계에서 차단 |
| K-41 | 결제 | idempotency_records에 expires_at 인덱스가 없어 `V7__idempotency_expires_index.sql` 추가. 남겨야 할 행이 앞에 쌓여도 지울 행이 굶지 않도록 상태 조건을 서브쿼리 WHERE에 둠 | — | — | ISE-146 | 설계 | events-and-reliability.md:216-217 | — | — |
| K-42 | 결제 | 미확인: 두 인스턴스 실제 동시 실행, 대량 적체 정리 소요 시간, config-repo 운영 값 | — | — | ISE-146 | 설계(미확인) | events-and-reliability.md:225 | — | — |
| K-43 | 결제 | 오래된 이벤트 재전달 방어 5겹: 조건부 UPDATE, `payments.reservation_id` UNIQUE, `refunds.payment_id` UNIQUE+ON CONFLICT, findRefund+PG 멱등키, Reservation paymentId 대조 | 5겹 | — | INV-1·2 | 설계 | events-and-reliability.md:229-234 | — | 다층 방어 |
| K-44 | 결제 | 대사: housekeeping 실행기가 5분마다 결제 DB와 예매 내부 조회 API를 대조해 RC-1~RC-8을 기록·알림, 데이터는 고치지 않음. 허용 지연 10분 | 5분 / 10분 / 규칙 8개 | — | `[가정]`, ADR 0029 | 설계 | events-and-reliability.md:238 | — | 서비스 사이 어긋남을 찾는 안전망 |
| K-45 | 결제 | 대사 설정 기본: window 7일, event-window 24시간, 예매 조회 connect 1초·read 2초, 묶음 100건 | 7일 / 24시간 / 1초 / 2초 / 100 | — | `[가정]` | 설계 | events-and-reliability.md:245, 267 | — | — |
| K-46 | 결제 | RC-7 쿼리는 18만 건 기준 0.25~0.4초(Opus 리뷰 측정, 부하 없음), 문장 시한 1.5초에 가까워지면 인덱스·UNION 분리 필요 | 0.25~0.4초 (문장 시한 1.5초) | 0.4÷1.5≈27% (직접 계산) | 18만 건, Opus 리뷰 측정 | 로컬 측정 | events-and-reliability.md:253 | — | AI 리뷰가 찾은 성능 한계 |
| K-47 | 결제 | 대사 규칙 RC-8 판단 범위와 outbox 보관 정합: `event-window + allowed-lag < outbox-sent-retention(7일)` 기동 검증 | 24시간+10분 < 7일 | — | — | 설계 | events-and-reliability.md:250 | — | — |
| K-48 | 결제 | ISE-239 로컬 전체 스택(2026-11-12) 대사 켜기 확인: A 정상 회차 RC-1~8 found=0, 한 회차 실소요 약 0.03초(30.033초-간격 30초), 결제 6건 | 0.03초/회차 | — | 로컬, 간격 30초·allowed-lag 5초로 덮어씀(배포는 5분·10분) | 로컬 측정 | events-and-reliability.md:277, 280 | — | 대사를 켠 근거 |
| K-49 | 결제 | B RC-2 재현: 예매를 EXPIRED로 바꾸자(07:51:40) 같은 회차는 허용 지연 안이라 undecided, 다음 회차(07:52:10)에 RC-2 OPEN 한 행 생성, 알림 로그 ERROR. 발견까지 로컬 약 30초(배포는 최대 약 5분+허용 지연). 예매를 CONFIRMED로 되돌리자(07:52:24) 다음 회차(07:52:40) RESOLVED | 약 30초 | 배포 5분 대 로컬 30초 = 10배 (직접 계산) | 로컬 | 로컬 측정 | events-and-reliability.md:281-282 | — | 시연 가능한 대사 재현 장면 |
| K-50 | 결제 | D 조회 실패: 예매 서비스만 멈추자(07:52:54) RC-1~6이 "대사 규칙 실패"로 기록, RC-7·8은 정상, 실패 지표 증가, OPEN 행은 만들지도 닫지도 않음. 재시작 후 Eureka에 다시 보일 때까지 한 회차 더 실패(실패 지표 2), 07:54:10 회차부터 정상 | 실패 지표 2 | — | 로컬 | 로컬 측정 | events-and-reliability.md:283 | — | 장애 주입에서도 오탐 없이 닫는 동작 |
| K-51 | 결제 | "검사하지 않아서 0" 착시: 규칙을 끈 동안 `reconciliation_mismatch_open{RC-1..6}`이 0이라 문제 없음과 같아 보였음. 켠 뒤의 0만 검사 결과 | — | — | ISE-239 | 판단(교훈) | events-and-reliability.md:286 | — | 지표 0의 의미를 구분하는 교훈 |
| K-52 | 결제 | 11/12 부하 측정으로 최근 7일 승인 결제 약 4만 7천 건 → 첫 회차 묶음 조회 약 470회 | 47,000건 → 약 470회 | 47,000÷100=470 (원문) | 11/12 배포, 계산값 | 배포 측정(건수)/계산값(호출 수) | events-and-reliability.md:285 | — | 규모 추정이 놓친 항목 이야기의 앞부분 |
| K-53 | 결제 | 트러블슈팅 ISE-254: 11/12 19:22 배포 뒤 회차마다 RC-1~6이 "변경 목록이 1000쪽을 넘었다"로 실패. 7일 안 갱신된 예매 약 14만 6천 건, 쪽당 100건으로 약 1,460쪽 > 상한 1,000쪽. RC-7·8은 정상, 대사 정체 알림 울림 | 약 146,000건 / 약 1,460쪽 > 1,000쪽 상한 | 1,460÷1,000 = 1.46배 초과 (직접 계산) | 11/12 19:22 배포 | 배포 측정 | events-and-reliability.md:290 | — | 로컬 확인에서 놓친 규모 문제 → 이어 읽기 |
| K-54 | 결제 | 로컬 확인에서 놓친 이유: ISE-239는 예매 몇 건뿐이었고, 호출 수 추정은 결제 쪽 묶음(약 470회)만 세고 변경 목록 쪽 수(예매 건수에 비례)를 안 셈 | — | — | — | 판단(원인 분석) | events-and-reliability.md:291 | — | "추정이 한 변수를 빠뜨렸다" |
| K-55 | 결제 | 새 동작: 워터마크 이어 읽기, `from = max(now-7일, 워터마크-(allowed-lag+1분))`, 회차당 예산 2,000쪽, 진행 상태는 규칙 판단 완료 뒤에만 확정 | 겹침 = 10분+1분=11분, 예산 2,000쪽 | — | ISE-254 | 설계 | events-and-reliability.md:293-295 | — | 상한 초과를 실패가 아닌 이어 읽기로 바꿈 |
| K-56 | 결제 | 배포 서버에서 변경 목록 한 쪽 약 3ms(1,000쪽을 약 3초에 읽음). 2,000쪽이면 회차가 약 6초 늘어 현재 규모(약 1,460쪽)는 한 회차에 다 읽음. 재시작 시 약 4~5초(계산값) | 약 3ms/쪽, 1,000쪽≈3초, 2,000쪽≈6초 | 1,460쪽×3ms≈4.4초 (직접 계산, 원문 4~5초) | 11/12 실패 회차 | 배포 측정(쪽당)/계산값 | events-and-reliability.md:294, 299 | — | — |
| K-57 | 결제 | 안전한 이유: 변경 목록은 대상 추가에만 쓰이고 "없음"으로 판정하는 곳이 없어 일부만 읽어도 오탐이 없음(RC-2 예매 없음은 묶음 조회에서만) | — | — | — | 설계 | events-and-reliability.md:297 | — | — |
| K-58 | 결제 | 한계: 진행 상태는 메모리, 재시작하면 7일치 재추적. 결제 쪽 묶음 조회는 매 회차 약 470회 호출 그대로 | 약 470회/회차 | — | — | 설계(한계) | events-and-reliability.md:299-300 | — | — |
| K-59 | 결제 | 환불 중복 방어와 DLQ `event_key`: 원본 topic-partition-offset 헤더로 같은 메시지 이중 DLQ 유입을 한 건으로 묶음 | — | — | ISE-138 | 설계 | events-and-reliability.md:105 | — | — |
| K-60 | 결제 | 승인·복구 결과 지표 모두 낮은 카디널리티 라벨만(paymentId·userId 등 라벨 금지), `MetricLabelsTest`가 실제 컨텍스트를 훑음 | — | — | ISE-156 | 설계 | observability.md:32 | — | 카디널리티 폭발 방지 |
| K-61 | 결제 | 승인 결과 outcome 7종, PG 호출 operation 7종·outcome 11종, Unknown cause 5종을 미리 등록해 사건이 없어도 0인 시계열 존재 | 7종 / 7종 / 11종 / 5종 | — | ISE-156 | 설계 | observability.md:11-13, 47 | — | "처음 발생"을 놓치지 않는 장치 |
| K-62 | 결제 | Ops JVM이 명령 하나 실행하고 종료해 Prometheus가 못 수집하므로 `payment_ops_command_total` Counter를 `payment_ops_actions` Gauge(누적, DB 집계)로 바꿈 | Counter → Gauge | — | ISE-158 | 판단(설계 변경) | observability.md:35 | — | 단명 프로세스 지표 문제를 DB 집계로 해결 |
| K-63 | 결제 | 작업 라벨 `job` 대신 `task`: `job`은 Prometheus 수집 라벨과 겹쳐 `exported_job`으로 바뀜 | — | — | ISE-195, `PrometheusNamesTest` | 판단(교훈) | observability.md:39 | — | 지표 라벨 충돌 트러블슈팅 |
| K-64 | 결제 | DB 집계 Gauge는 `gauge-refresh` 15초 주기, 수집 때 DB 조회 안 함, 쿼리 실패 시 스냅숏 유지 | 15초 | — | ISE-156 | 설계 | observability.md:32, 53 | — | — |
| K-65 | 결제 | 인덱스 EXPLAIN: Outbox 조건을 `IN ('PENDING','FAILED')`로 쓰면 어느 부분 인덱스에도 안 맞아 SENT가 쌓인 전체를 순차 읽음 12.7ms, `status = 'PENDING' OR status = 'FAILED'`로 바꾸면 BitmapOr로 0.06ms. 나머지 인덱스 스캔 0.02~0.04ms | 12.7ms → 0.06ms | 약 211.7배 (12.7÷0.06, 직접 계산) | 결제 20만·Outbox SENT 30만 행, 로컬 EXPLAIN | 로컬 측정 | observability.md:55 | — | 쿼리 한 줄 바꿔 200배 개선, 인덱스 추가 없이 |
| K-66 | 결제 | 알림 즉시 확인 6종과 경고 8종, 기준: NMC 장기 미해결 30분(심각 24시간), REQUESTED 체류 60초, UNKNOWN 5분, 환불 대기 30분(심각 2시간), Outbox 지연 5분(심각 30분), 작업 정체 1분/2분/15분/1시간 | 60초/5분/30분/24시간/2시간/30분 등 | — | 초기값은 가정, 부하·복구 정책에 맞춰 조정 | 설계(가정) | observability.md:59-76 | — | — |
| K-67 | 결제 | 알림 규칙 Prometheus 파일 22개(그룹 payment-immediate 8개, payment-threshold 14개). 규칙 파일로 리뷰·`promtool` 단위 테스트 가능해 Grafana 알림 대신 선택 | 8 + 14 = 22개 | — | ISE-157 | 설계 | observability.md:84 | — | 알림을 코드로 리뷰·테스트 |
| K-68 | 결제 | compose 확인(2026-11-03): 규칙 21개 올라옴, Kafka 컨테이너 멈춘 채 결제 승인 시 5분 9초 뒤 `PaymentOutboxDelayed`(경고) firing, Kafka 재기동 50초 뒤 해소(Outbox 13회 재시도 뒤 SENT) | 5분 9초 후 firing / 50초 후 해소 / 재시도 13회 | — | 2026-11-03 compose | 로컬 측정 | observability.md:101 | — | 장애 주입 → 알림 → 자동 복구 시연 후보 |
| K-69 | 결제 | 모순 주의: 알림 규칙 개수가 observability.md:84는 22개(8+14)이고 :101은 "규칙 21개가 올라왔다"(2026-11-03 시점) | 22 vs 21 | — | 시점이 다름(ISE-167 대사 규칙 추가 등 이후 변경 가능, 원문 이유 미기재) | 모순(원인 확인 못 함) | observability.md:84, 101 | — | — |
| K-70 | 결제 | 대시보드 1장 5줄 구성, uid `iseonjwa-payment`, 승인 지연 p50·p95·p99는 histogram_quantile, PG 호출 지연은 평균(히스토그램 안 켬) | 5줄 | — | ISE-157 | 설계 | observability.md:93-106, 109 | infra/grafana/dashboards/payment.json (그림 아님) | — |
| K-71 | 결제 | 미확인: 부하에서 `payment_approve_seconds_bucket`의 시계열 수와 메모리 | — | — | — | 미확인 | observability.md:57 | — | — |
| K-72 | 결제 | 건별 알림(ISE-183): 결제·환불 NMC, 대사 불일치, dead_letters OPEN은 건수 알림에 더해 로그 한 줄을 Loki 규칙이 읽어 슬랙으로 "N번 유저가 YYMMDD HH:MM에 결제한 건" 전송 | — | — | Alertmanager `slack-monitoring-event` | 설계 | observability.md:78, 111 | — | 알림이 실제 사람에게 닿는 경로 |
| K-73 | 결제 | PG 포트는 `PgClients.of(PgProvider)`로 결제마다 `payments.pg_provider`가 정하는 어댑터를 고름. 서버 기본 PG(`payment.pg.provider=mock\|toss`)는 요청에 pgProvider가 없을 때·checkout에만 사용 | mock/toss 2종 | — | ISE-231 | 설계 | pg-integration.md:9 | — | Mock↔토스 전환 구조 |
| K-74 | 결제 | 설정에 없는 PG(TOSS 결제인데 토스 키 없음)면 PG를 안 부르고 모든 호출에 Unknown(CLIENT_ERROR). `PG_ERROR`면 공유 CB가 열려 Mock 결제 재확인·환불까지 멈추므로 허가 반환·CB 미기록. Mock으로 대신 보내지 않음 | — | — | — | 설계 | pg-integration.md:20 | — | 실패 영향 범위를 CB에 전파하지 않는 선택 |
| K-75 | 결제 | 승인·복구 CB·Bulkhead·PG 지표 태그는 PG 구분 없이 하나를 공유(감수 사항, ADR 0023 2026-11-10 개정) | — | — | — | 판단(감수) | pg-integration.md:21 | — | — |
| K-76 | 결제 | 빌링키 발급·자동결제 승인은 늘 Mock(`PgClients.billing()`), 빌링 결제의 pg_provider도 MOCK이라 재확인·환불도 Mock | — | — | 2026-11-10 | 설계 | pg-integration.md:17, 183 | — | — |
| K-77 | 결제 | 금액이 다른 승인은 예매를 확정하지 않고 NEEDS_MANUAL_CHECK(PG_AMOUNT_MISMATCH). 코드가 검사를 빠뜨려도 `chk_payments_approved` CHECK 제약이 APPROVED 저장을 막음. 금액은 BigDecimal 파싱(double이면 소수 오차가 일치로 보임) | — | — | INV-3 | 설계 | pg-integration.md:42, 161 | — | 이중 방어(코드+DB 제약) |
| K-78 | 결제 | 토스 orderId는 결제창을 열 때 필요해 TX1 이전에 정해져야 하므로 `PgOrderIds.of(reservationId, token.exp)` 이름 기반 UUID로 결정적 생성 | — | — | ADR 0023 2026-11-09 개정 | 설계(변경) | pg-integration.md:58 | — | — |
| K-79 | 결제 | PG 호출 보호 3그룹: pg-approve(동시 30, 대기 0, 최근 20회 중 50% 실패 시 OPEN, OPEN 10초, HALF_OPEN 3회), pg-billing(동시 3), pg-recovery(동시 2). 타임아웃 연결 0.5초·응답 2초 | 30 / 3 / 2, 20회 / 50% / 10초 / 3회, 0.5초 / 2초 | 합 최대 동시 PG 호출 30+3+2=35 (원문) | 값 모두 `[가정]`, 부하 측정 뒤 조정 | 설계 | pg-integration.md:69-77, 113 | — | 격벽 3그룹 설계 |
| K-80 | 결제 | pg-approve 동시 허가를 코드 기본 10 → config-repo 30으로 올림(ISE-238). 처리량 상한 = 허가 수÷(TX1+PG 응답 시간), PG 300ms에서 허가 10이면 초당 약 31건 | 10 → 30 | 3배 (30÷10) | 2026-11-12 배포 측정 | 배포 측정 | pg-integration.md:73, 87-93, 112 | — | PG 동시 허가 병목 해소 |
| K-81 | 결제 | 필요 허가 ≈ 결제 도착률 50 × PG p99 0.35초 × 여유 2 ≈ 35 (대기열 admit-per-second 50 기준) | 50 × 0.35 × 2 = 35 (원문 계산식) | — | 대기열이 초당 50명 입장 | 계산값 | pg-integration.md:98 | — | 필요 숫자를 먼저 계산 |
| K-82 | 결제 | PAY1 측정: 허가 10·대기 0은 PG 300ms에서 승인 한도 초당 31.2건, 요청 80/초에서 거절 59.5%, 과부하 승인 p50 311ms, 거절 응답 즉시 | 한도 31.2건/초, 거절 59.5% | — | 배포, 요청 80/초, PG 지연 300ms | 배포 측정 | pg-integration.md:104 | — | 허가 10의 한계 |
| K-83 | 결제 | PAY1 측정: 허가 30·대기 0은 승인 한도 초당 91.5건, 거절 0.2%, 승인 p50 309ms, 거절 응답 p50 3ms | 31.2 → 91.5건/초, 거절 59.5% → 0.2% | 한도 약 2.93배 (91.5÷31.2), 거절 약 297.5배 감소 (59.5÷0.2) (직접 계산) | 배포, 요청 80/초, PG 300ms | 배포 측정 | pg-integration.md:105 | — | 허가 10 → 30의 효과 |
| K-84 | 결제 | PAY1 측정: 허가 30·대기 200ms는 한도 초당 98.5건, 거절 0%, 승인 p50 491ms, 거절 응답 p50 203ms | 98.5건/초, 491ms | 허가 30·대기 0 대비 한도 약 1.08배 (98.5÷91.5), 승인 p50 약 1.59배 느림 (491÷309) (직접 계산) | 같음 | 배포 측정 | pg-integration.md:106 | — | 대기 시간 도입을 거부한 근거 |
| K-85 | 결제 | 허가 30에서도 Hikari 활성 연결 최대 3개(풀 20), 대기 0 → PG 호출이 트랜잭션 밖이라 허가 수가 DB 연결 수요로 이어지지 않음 | 활성 연결 최대 3 / 풀 20 | — | 배포 | 배포 측정 | pg-integration.md:108 | — | — |
| K-86 | 결제 | 결정: 대기 시간은 0(대기를 주면 포화 때 스레드를 쥐고 승인·거절 모두 지연, 이득은 과부하 승인 7.7%뿐). 대기 설정은 코드에 남김(0 이상 1초 이하) | 이득 7.7%(원문), 상한 1초 | — | 허가 30 | 판단(결정) | pg-integration.md:114-115 | — | 대기 도입 거부 판단 |
| K-87 | 결제 | PG가 느린 경우(PAY1-b: 지연 1,000·1,900·2,500ms)는 허가 30으로 재측정 후 24~30 안에서 확정(ISE-246) 예정. 응답 시한 2초 넘으면 UNKNOWN이 허가 수에 비례해 쌓이고 복구 그룹(동시 2)이 치움 | PG 지연 1,000/1,900/2,500ms, 허가 24~30 | — | 미측정 | 설계(미확인) | pg-integration.md:112 | — | 다음 측정 과제 |
| K-88 | 결제 | 토스 동시 호출 한도는 확인 못 함, Mock PG는 한도 없음 | — | — | — | 미확인 | pg-integration.md:113 | — | — |
| K-89 | 결제 | Erlang B 계산값(허가 쥐는 시간 0.315초 기준): 초당 30건(a≈9.5)에서 허가 10=18.9%, 17=0.85%, 20=0.10%. 초당 50건(a≈15.8)에서 허가 17=13.4%, 20=5.9%, 24=1.3%, 27=0.28%, 30=0.05% | 허가 10→18.9%, 30→0.05% (초당 50건 열) | 초당 50건에서 허가 17→30으로 13.4%→0.05% = 268배 (직접 계산) | 포아송 도착 가정, 측정 아님 | 계산값 | pg-integration.md:117-124 | 표(허가 수별 거절 확률) | 리틀의 법칙 vs Erlang B, 학습 노트 연결 |
| K-90 | 결제 | k6 일정 도착은 포아송보다 몰림이 적어 측정(허가 30에서 0.2%)이 계산값(0.05%는 초당 50건 열)보다 낙관적이라는 원문 설명 | 0.2% 측정 vs 0.05% 계산 | 4배 (0.2÷0.05, 직접 계산) | 원문은 "측정이 계산값보다 낙관적"이라 했으나 숫자 대조상 측정 0.2%가 더 큼 → 해석 모순 가능, 부하 조건(초당 80건 vs 50건)이 달라 직접 비교 불가 | 모순(조건 다름) | pg-integration.md:105, 122, 124 | — | — |
| K-91 | 결제 | 하지 않은 것: 결제 서비스 인스턴스 증설(허가가 인스턴스마다 따로, 단일 EC2 메모리 예산 밖), PG 호출 비동기화(스레드가 병목이 아님, 허가 30에서 JVM 스레드 최대 112) | JVM 스레드 최대 112 | — | 배포 측정 | 판단(거부) + 측정 | pg-integration.md:126 | — | 증설·비동기화를 안 한 이유 |
| K-92 | 결제 | 시간 예산: 승인 한 건은 TX1(연결 대기 1초 이내)+PG 최대 2.5초+TX2(1초 이내)라 Gateway 3초를 넘을 수 있고 넘으면 클라이언트 504, 서버는 처리 계속, 같은 키 재시도로 결과 | 1 + 2.5 + 1 = 4.5초 (원문 구성) | 4.5÷3 = 1.5배 (직접 계산) | `[가정]` | 설계 | pg-integration.md:128 | — | 504 후 재시도 안전성 |
| K-93 | 결제 | 503 PG_UNAVAILABLE은 PG 장애만이 아니라 TX1이 느려도 Bulkhead 자리가 차서 같은 503이 남 | — | — | — | 설계 | pg-integration.md:83 | — | — |
| K-94 | 결제 | Mock PG 프로토콜과 계약 테스트 12항목(승인·거절·타임아웃·멱등 재시도·조회 미발견·환불·처리 중·CLIENT_ERROR·늦은 승인 방지·금액 누락) | 12항목 | — | `PgClientContractTest` | 설계 | pg-integration.md:135, 140 | — | Mock과 실제 어댑터를 같은 계약으로 검증 |
| K-95 | 결제 | 토스 어댑터: 토스에 망취소 API가 없어 `preventLateApproval`이 `Prevented`를 안 내고 `Unknown`(DONE만 AlreadyApproved) | — | — | 문서 기준, 실제 호출 미확인 | 판단(거짓 보장 거부) | pg-integration.md:178, 198 | — | 모르는 걸 보장하지 않는 선택 |
| K-96 | 결제 | 토스 Mock 건너뛰기(`mock-bypass-enabled`)는 꺼진 것이 기본: 설정 누락·환경변수 미전달에도 결제창을 거치게, 켜는 쪽이 기본이면 같은 실수가 결제 없는 승인으로 이어짐 | — | — | 시연 편의는 명시적으로 켬 | 판단(기본값 결정) | pg-integration.md:189 | — | 안전 쪽 기본값 선택 |
| K-97 | 결제 | 토스 어댑터 미확인 `[가정]`: confirm 멱등키, 타임아웃, paymentKey 만료(문서에 10분과 30분이 엇갈림)·최대 길이. 테스트 키는 11/11 이후 `tossSandboxTest`·시연으로 확인 예정 | 10분 vs 30분(문서 간 엇갈림) | — | 문서 기준 | 미확인/모순(토스 문서 내) | pg-integration.md:192, 200, 205 | — | — |
| K-98 | 결제 | 토스 sandbox 테스트는 `@Tag("toss-sandbox")`, 기본 test에서 빠지고 `TOSS_TEST_SECRET_KEY`가 있을 때만 `tossSandboxTest`로 실행. 키는 저장소에 두지 않음 | — | — | — | 설계 | pg-integration.md:133 | — | — |
| L-01 | 결제 | 운영자는 결제·환불·Outbox·dead_letters 상태를 SQL로 직접 바꾸지 않고 감사 기록이 남는 Ops 명령으로만 바꾼다(INV-7, ADR 0028) | 해당 없음 | — | 설계 원칙 | 설계 | docs/design/payment/manual-recovery.md:3,7 | — | "직접 SQL 금지, 명령만 허용"이라는 운영 통제 장면 |
| L-02 | 결제 | 운영자는 목표 상태를 고르지 않는다. 명령이 실행 시점에 PG를 다시 조회해 근거에 맞는 전이만 하고, PG를 조회하지 못하면 아무것도 바꾸지 않는다 | 해당 없음 | — | 설계 원칙 | 설계 | docs/design/payment/manual-recovery.md:8 | — | "사람이 결과를 고르지 않는다, PG가 근거다" |
| L-03 | 결제 | 모든 상태 변경 명령은 dry-run이 기본이고 `--apply --confirm <계획 해시>`로 실행한다. 그사이 상태·PG 근거가 바뀌면 거부한다 | 계획 해시 = SHA-256 소문자 16진수 64자 | — | 설계 | 설계 | docs/design/payment/manual-recovery.md:10,26 | — | dry-run → 해시 확인 → 적용 흐름 시연 |
| L-04 | 결제 | 돈이나 이벤트가 움직이는 명령은 4-eyes다. `--operator`와 다른 `--approver`가 필요하고 둘 다 허용 목록에 있어야 한다 | 허용 목록 팀원 5명(hyoseung, junhyun, kangmin, kwonwoo, youngeun) | — | `config-repo/payment-service.yml`의 `payment.ops.operators` | 설계 | docs/design/payment/manual-recovery.md:11,24 | — | 두 사람 승인 통제 |
| L-05 | 결제 | 실행자 ID는 자기 신고이고 증명이 아니라서 감사 기록의 정확성은 호스트 SSH 접근 통제에 기댄다 | 해당 없음 | — | 한계로 명시 | 설계 | docs/design/payment/manual-recovery.md:24 | — | 한계를 숨기지 않는 설계 서술 |
| L-06 | 결제 | Ops 명령은 EC2 호스트 SSH 세션에서 payment-service 이미지를 `ops` 프로필로 한 번 띄우는 방식이고 웹서버·Kafka 소비자·스케줄러·Outbox 릴레이·환불 워커 등을 띄우지 않으며 Kafka에 연결하지 않는다 | 명령 하나 실행 후 종료 | — | `docker compose run --rm --no-deps -e SPRING_PROFILES_ACTIVE=local,ops payment-service ops <명령>` | 설계 | docs/design/payment/manual-recovery.md:12,14-18 | — | 운영 명령 일회성 JVM 구조 |
| L-07 | 결제 | 종료 코드: 0 성공(dry-run 계획 출력 또는 적용), 2 거부, 1 오류 | 0 / 2 / 1 | — | — | 설계 | docs/design/payment/manual-recovery.md:22 | — | — |
| L-08 | 결제 | manual_action_logs에는 `--apply` 시도 결과만 남는다. APPLIED는 상태 변경과 같은 트랜잭션, REJECTED는 별도 트랜잭션. dry-run·inspect는 남기지 않는다 | 해당 없음 | — | — | 설계 | docs/design/payment/manual-recovery.md:27 | — | 감사 기록 원칙 |
| L-09 | 결제 | 구현 검증 범위: `inspect`와 `outbox requeue`(ISE-158)는 로컬 테스트 DB에서 jar를 ops 프로필로 실행해 확인. `payment resolve`·`refund resolve`(ISE-159)와 `refund retry`·`dead-letter resolve`(ISE-160)는 실제 PostgreSQL + 가짜/목 PgClient 통합 테스트로만 확인했고 jar 실행·EC2 compose·실제 Mock PG 연결 실행은 확인하지 않았다 | 명령 6종 중 jar 실행 확인 2종(inspect, outbox requeue), 통합 테스트만 4종 | 직접 계산: 2/6 jar 실행 확인 | 로컬 테스트 DB | 로컬 측정(확인 범위 명시) | docs/design/payment/manual-recovery.md:3 | — | "어디까지 검증했는가"를 정직하게 밝히는 장면 |
| L-10 | 결제 | `inspect`가 결제·환불·예매 ID로 상태, 이력, 환불, Outbox, dead_letters, Ops 기록, 대사 불일치(OPEN + 최근 7일 RESOLVED)를 묶어 출력한다(ISE-167). `--with-pg`면 PG 조회와 대조한다 | 대사 불일치 조회 범위 7일 | — | — | 설계 | docs/design/payment/manual-recovery.md:88,52 | 출력 예 코드블록(inspect) 같은 파일:34-53 | inspect 한 장 출력으로 장애 상황 파악 |
| L-11 | 결제 | inspect 출력 예: 결제 id=27 APPROVED 50000 KRW, Outbox 2건 중 id=3 FAILED 시도=5 `RecordTooLargeException`, id=4 PENDING | 시도 5, 금액 50000 KRW | — | 로컬 테스트 DB 실제 실행 결과 | 로컬 측정 | docs/design/payment/manual-recovery.md:30-53 | 같은 파일:34-53 | Outbox FAILED 발견 예 |
| L-12 | 결제 | `outbox requeue`: FAILED → PENDING(attempts 유지, next_attempt_at=실행 시각, claimed_until=NULL). 1인 명령이며 PG 없음. 같은 키에서 뒤에 막힌 PENDING 건수를 보여 준다 | 예시에서 막힌 PENDING 1건 | — | 로컬 테스트 DB 실제 실행 | 로컬 측정 | docs/design/payment/manual-recovery.md:55-82,92,465-468 | 코드블록 같은 파일:58-82 | 장애 난 이벤트 한 줄 되살리기 |
| L-13 | 결제 | outbox requeue 재실행하면 행이 이미 PENDING이라 거부되고(종료 코드 2) REJECTED가 남는다. 해시 불일치도 거부, 허용 목록 밖 실행자도 거부(dry-run이라 기록 안 함) | 종료 코드 2 | — | 로컬 테스트 DB | 로컬 측정 | docs/design/payment/manual-recovery.md:72,84 | — | 이중 적용 방지 |
| L-14 | 결제 | `payment resolve`: 대상 결제 NMC(NEEDS_MANUAL_CHECK). PG 결과별 계획: Approved+금액·통화 일치 → APPROVED+PaymentApproved, Approved+불일치 → 환불 REQUESTED 생성(PG_AMOUNT_MISMATCH), Declined → DECLINED, NotFound → 적용 때 늦은 승인 방지, Unknown·예외 → 거부 | 5개 분기 | — | ISE-159 | 설계 | docs/design/payment/manual-recovery.md:135-141 | 표 같은 파일:135-141 | PG 근거에 따라 갈리는 결정 표 |
| L-15 | 결제 | dry-run은 `preventLateApproval`을 부르지 않는다(PG에 흔적을 남기기 때문). 적용 때 다시 NotFound면 부르고 Prevented면 DECLINED(NOT_SENT), AlreadyApproved면 금액 대조 | 해당 없음 | — | — | 설계 | docs/design/payment/manual-recovery.md:140 | — | 늦은 승인 방지(INV-8) 연결 |
| L-16 | 결제 | 금액 불일치(PG 승인 49000 vs 결제 50000)면 예매를 확정하지 않고 PG 승인 금액 전액 환불한다(INV-3) | 50000 KRW 결제 → PG 승인 49000 KRW, 환불 금액 49000 | 차이 1000원(직접 계산: 50000-49000) | 가짜 PG 통합 테스트 구성 출력 | 로컬 측정 | docs/design/payment/manual-recovery.md:116,177-193 | 코드블록 같은 파일:180-193 | 금액이 다르면 확정하지 않는다 |
| L-17 | 결제 | 금액 불일치 환불 시작 때 결제의 `pg_tx_id`·`pg_approved_amount`가 비어 있으면(재조회 소진으로 NMC가 된 결제) PG 값으로 채운다. 안 채우면 환불 워커가 STATE_CONFLICT로 멈추고 PaymentRefunded의 금액이 빈다 | 해당 없음 | — | — | 설계 | docs/design/payment/manual-recovery.md:101,144 | — | 설계 엣지 케이스 발견 |
| L-18 | 결제 | 같은 payment resolve를 두 번 적용하면 두 번째는 NMC가 아니라서 거부되고 이벤트는 한 건이다 | 이벤트 1건 | — | — | 설계 | docs/design/payment/manual-recovery.md:146,148 | — | 멱등 보장 |
| L-19 | 결제 | `refund resolve`: PG가 Refunded(금액·통화 일치) → 환불·결제 REFUNDED + PaymentRefunded. Refunded 불일치 → 거부(종결 절차 없음, 후속 과제). RefundRejected → `--close-failed --external-ref` 있으면 FAILED. NotFound·Unknown → 거부 | 해당 없음 | — | ISE-159 | 설계 | docs/design/payment/manual-recovery.md:221-227 | 표 | 환불 종결 규칙 |
| L-20 | 결제 | refund resolve는 `PgClient.refund`를 부르지 않고(0028 결정 4), 환불 완료 전이는 환불 워커와 같은 코드(`RefundSettlementService`)를 쓴다 | 해당 없음 | — | — | 설계 | docs/design/payment/manual-recovery.md:231-232 | — | 같은 코드 경로 재사용 |
| L-21 | 결제 | 환불 NMC 사유별 처리: REFUND_UNRESOLVED·STATE_CONFLICT → refund retry, REFUND_REJECTED → 고치고 retry 또는 외부 조치 후 close-failed, REFUND_AMOUNT_MISMATCH → 종결 절차 없음 | 사유 4종 | — | — | 설계 | docs/design/payment/manual-recovery.md:118-122 | — | 환불 장애 분류 |
| L-22 | 결제 | `refund retry`: 환불을 NMC → REQUESTED로 되돌려 환불 워커가 이어받게 한다. attempt 0, next_check_at 실행 시각. PG가 NotFound·RefundRejected일 때만 허용, Refunded·Unknown은 거부 | 예시 attempt=9 → 0 | — | 목 PG 통합 테스트 구성 출력, 환불 1031 | 로컬 측정 | docs/design/payment/manual-recovery.md:317-345 | 코드블록 같은 파일:332-345 | 환불 재시도 |
| L-23 | 결제 | refund retry는 `pg_refund_id`(PG 멱등키)와 `pg_requested_at`을 그대로 둔다. 비우면 워커가 findRefund 없이 refund부터 불러 이중 환불 방어가 PG 멱등키 하나에만 기댄다 | 해당 없음 | — | events-and-reliability.md 6절 미확인 항목을 이 결정으로 닫음 | 설계/판단 | docs/design/payment/manual-recovery.md:326 | — | 이중 환불 방어 설계 이유 |
| L-24 | 결제 | 실제 Mock PG와 환불 워커 스케줄러를 띄운 상태의 refund retry 이후 흐름은 확인하지 않았다(통합 테스트로 워커 직접 호출만 확인) | 확인 못 함 | — | — | 설계(확인 못 함 표시) | docs/design/payment/manual-recovery.md:367 | — | 검증 한계 |
| L-25 | 결제 | refund retry는 우리 pg_refund_id로만 PG를 조회하므로 콘솔 직접 환불은 NotFound로 보여 재시도가 허용된다. 명령이 막지 못한다 | 해당 없음 | — | — | 설계(한계) | docs/design/payment/manual-recovery.md:327,517 | — | 사람이 지켜야 하는 규칙 |
| L-26 | 결제 | `dead-letter resolve`: `--replay`는 원문(payload_raw)을 DLQ 처리기와 같은 파서·검증·생성 경로로 재처리, `--no-obligation`은 환불 의무 없음을 확인하고 닫음. 두 옵션 중 정확히 하나 필수(아니면 사용법 오류 종료 1). PG 호출 없음 | 해당 없음 | — | ISE-160 | 설계 | docs/design/payment/manual-recovery.md:378-396 | — | DLQ 수동 처리 |
| L-27 | 결제 | dead_letters 처리 결과: 검증 통과·환불 없음 → 환불 REQUESTED 생성(REPLAYED), 이미 있음 → 환불 1건 유지(DUPLICATE). 조건부 UPDATE 0행이면 롤백해 replay가 만든 환불도 되돌아간다 | 환불 1건 | — | 통합 테스트 구성 출력, dead letter 432 → refund_id=1032 | 로컬 측정 | docs/design/payment/manual-recovery.md:390-398,423 | 코드블록 같은 파일:406-428 | 재처리의 멱등 |
| L-28 | 결제 | `.dlq` 토픽 보관 기간 7일 `[가정]` 안에 처리한다. dead_letters에 원문이 있어 토픽이 지워져도 근거는 남는다 | 7일 [가정] | — | 원문의 가정 표시 | 설계(가정) | docs/design/payment/manual-recovery.md:376 | — | — |
| L-29 | 결제 | manual_action_logs `to_status` 컬럼이 20자라 `RESOLVED(NO_OBLIGATION)`이 들어가지 않아 닫힌 방식(REPLAYED·DUPLICATE·NO_OBLIGATION)을 기록한다 | 컬럼 20자 | — | — | 설계 | docs/design/payment/manual-recovery.md:399 | — | 작은 설계 디테일 |
| L-30 | 결제 | Outbox FAILED 복구 절차: inspect로 FAILED 행 확인 → requeue dry-run → 원인 고친 뒤 apply → 릴레이가 같은 eventId로 발행하고 같은 키 뒤 행도 순서대로 나감. 소비자는 멱등이라 중복은 한 번만 반영 | 해당 없음 | — | ADR 0022 키별 순서 | 설계 | docs/design/payment/manual-recovery.md:460-469 | — | 이벤트 재발행 절차 |
| L-31 | 결제 | PG Circuit Breaker `pg-approve` OPEN이면 신규 결제는 503으로 거절되고 기존 결제 조회·재확인·환불은 계속된다 | 503 | — | CB 이름 pg-approve, pg-recovery | 설계 | docs/design/payment/manual-recovery.md:474-475 | — | 장애 격리 |
| L-32 | 결제 | 대사 규칙 RC-1~RC-8. RC-7·RC-8은 결제 DB만으로 항상 동작하고 RC-1~RC-6은 예매 내부 조회 API(ISE-75)가 필요해 `payment.reconciliation.reservation.enabled`(기본 false)를 켠 환경에서만 동작한다(ISE-168) | 규칙 8개(RC-1~RC-8), 항상 동작 2개, 조건부 6개 | 직접 계산: 6/8 조건부 | — | 설계 | docs/design/payment/manual-recovery.md:479-492 | 표 같은 파일:479-486 | 대사로 불일치를 찾는 구조 |
| L-33 | 결제 | 대사는 데이터를 고치지 않는다. 다음 대사에서 일치하면 불일치가 RESOLVED가 된다. RC-8은 보충하지 않고 닫는 명령이 MVP에 없어서 보충 전까지 알림이 계속 울린다 | 해당 없음 | — | — | 설계(한계) | docs/design/payment/manual-recovery.md:488-490 | — | 한계 |
| L-34 | 결제 | 예매 조회가 실패한 회차에는 RC-1~RC-6이 기록도 닫기도 없이 실패하고 기존 OPEN 행은 남는다. 404·시한 초과도 "예매 없음"이 아니라 조회 실패다. 켠 환경에서 끄면 OPEN 행을 판단할 주체가 없어 알림이 계속 울리므로 끄지 말고 API를 고친다 | 해당 없음 | — | — | 설계 | docs/design/payment/manual-recovery.md:494,504 | — | 실패 의미 설계 |
| L-35 | 결제 | PaymentResultAnomalyEvent 알림(ISE-46): 예매 서비스가 결제 결과를 반영하지 않고 격리. 유형이 REFUNDED이고 예매가 CONFIRMED면 결제만 환불되고 좌석은 풀리지 않은 상태이며 좌석은 자동으로 풀지 않는다. 환불 요청은 결제당 환불 한 건(12-4)에 묶여 다시 환불되지 않는다 | 환불 결제당 1건 | — | — | 설계 | docs/design/payment/manual-recovery.md:496-498 | — | 자동 좌석 해제 금지 판단 |
| L-36 | 결제 | Hikari 연결 대기 초과 점검 기준: 풀 크기 SA 9-4의 20 | 풀 20 | — | SA 9-4 참조 | 설계 | docs/design/payment/manual-recovery.md:505 | — | — |
| L-37 | 결제 | 긴급 PG 콘솔 환불(예외 절차): 4조건 모두 충족(NMC, API 불가·콘솔만 가능, 고객 피해 큼, 두 사람) 때만. 현재 한계(ISE-159): findRefund가 우리 pg_refund_id로만 조회하므로 진짜 콘솔 환불은 확인 불가이고 NotFound로 거부되어 NMC로 남는다. 토스 어댑터를 만드는 M4 때 다시 본다 | 예외 조건 4개 | — | — | 설계(한계) | docs/design/payment/manual-recovery.md:507-519 | — | 한계 인정과 후속 |
| L-38 | 결제 | 결제 토큰 키 교체(ADR 0055): 개인키(예매 서비스 서명) 유출·정기 교체 때 키 쌍 재생성 후 두 서비스를 함께 재생성해야 하며 한 서비스만 띄우면 모든 결제가 403 `PAYMENT_TOKEN_INVALID`. 이전 토큰은 무효 | 결제 토큰 수명 최대 4분 50초, 키 P-256 | — | 무중단 교체(공개키 두 개 동시 검증)는 미지원(후속) | 설계 | docs/design/payment/manual-recovery.md:521-527 | — | 키 유출 대응 |
| L-39 | 인프라 | mock-pg는 서버 모듈로 PG 모의. 완료 기준은 공통 계약 테스트 `PgClientContractTest` 23개와 이관 어댑터 고유 테스트를 실제 mock-pg 앱에 돌려 통과시키는 것 | 계약 테스트 23개, 어댑터 고유 테스트 8개 중 6개 이관·2개 비이관 | 직접 계산: 6/8=75% 이관 | 구현 완료 상태 | 로컬 측정(테스트 통과 기술) | docs/design/payment/mock-pg.md:5,9,11-22 | 표 같은 파일:13-22 | 가짜 서버 대신 실제 서버로 계약 검증 |
| L-40 | 인프라 | `FakeMockPgServer`는 설계와 이미 달라(조회 때 IN_FLIGHT를 404, 동시 승인이 둘 다 저장, 환불 검증 없음) 이관하지 않는 두 테스트용으로만 남기고 공통 계약 정본으로 쓰지 않는다. ISE-133에서 승인·환불 흉내를 모두 지움 | 남은 테스트 2개(본문 코드 없는 404, 연결 불가) | — | — | 판단 | docs/design/payment/mock-pg.md:24,245 | — | 가짜 서버가 계약을 속이던 문제 발견 |
| L-41 | 인프라 | 원장은 메모리 `Ledger` 안 `ConcurrentHashMap` 3개(orders, paymentsByTx, refunds). `reset`은 `AtomicReference<Ledger>`를 새 객체로 교체해 지연 중 옛 요청이 새 원장을 오염시키지 못한다(I1) | 맵 3개 | — | — | 설계 | docs/design/payment/manual-recovery.md 아님. docs/design/payment/mock-pg.md:30-36 | 표 같은 파일:32-36 | 원장 자료구조 |
| L-42 | 인프라 | 환불 연산이 orders를 다시 잠그지 않도록 paymentsByTx에 승인 금액·통화를 복사한다. 승인은 orders→paymentsByTx, 환불은 paymentsByTx→refunds 순서라 거꾸로 잡는 경로가 없다(교착 방지) | 해당 없음 | — | — | 설계 | docs/design/payment/mock-pg.md:38,265 | — | 교착 방지 설계 |
| L-43 | 인프라 | 호출 계수 5종(approveCalls, lookupCalls, cancelCalls, refundCalls, refundLookupCalls). 저장 횟수는 멱등이라 1을 넘지 않아 INV-1("주문당 승인 ≤ 1")을 검증하지 못하므로 응답 종류와 무관하게 호출 수를 센다(500·409도 센다) | 계수 5종, V1 `approveCalls ≤ 1` | — | S-28·failure-scenarios V1 정정 | 설계 | docs/design/payment/mock-pg.md:44-65 | 표 같은 파일:46-52 | "저장 횟수 말고 호출 횟수를 센다" 전환 |
| L-44 | 인프라 | 멱등: 승인 orderId는 저장된 결과를 그대로 돌려주고, 처리 중(IN_FLIGHT)이면 409 `REQUEST_IN_PROGRESS`. 환불 refundId는 저장 결과를 돌려주고 pgTxId가 다르면 422 OTHER. 요청 금액이 달라도 저장된 값을 돌려주는 것은 Mock의 선택이며 실제 PG 동작 확정이 아니다 | 해당 없음 | — | ADR 0023 | 설계(Mock 선택) | docs/design/payment/mock-pg.md:79-87 | 표 같은 파일:79-83 | — |
| L-45 | 인프라 | 승인 저장과 망취소 원자성: 상태 전이는 `orders.compute(orderId, ...)`로만. IN_FLIGHT → APPROVED는 현재 값이 자기 시도 토큰일 때만 성공하므로 취소가 CANCELED를 응답했다면 그 주문은 이후 APPROVED가 될 수 없다(INV-8). 지연은 compute 밖에서 | 해당 없음 | — | 동시 approve·cancel 1,000회 경합 테스트 계획 | 설계 | docs/design/payment/mock-pg.md:91-106,311 | 상태 전이 도식(ASCII) 같은 파일:93-96, 상태 표 98-104 | 늦은 승인 방지가 깨지지 않는 이유(INV-8) |
| L-46 | 인프라 | IN_FLIGHT 상태에서 취소가 오면 CANCELED를 주지 않고 202 PROCESSING으로 응답한다(취소 응답이 곧 이후 승인 불가를 보장해야 하므로) | 해당 없음 | — | — | 설계 | docs/design/payment/mock-pg.md:101,106 | — | — |
| L-47 | 인프라 | 승인 스레드는 try/finally로 지연 중 예외·인터럽트 시 자기 토큰의 IN_FLIGHT를 "없음"으로 되돌려 영구 IN_FLIGHT를 막는다 | 해당 없음 | — | — | 설계 | docs/design/payment/mock-pg.md:107 | — | — |
| L-48 | 인프라 | 장애 주입 API: `POST /mock-pg/faults` {target, mode, rate, count, orderId/refundId, delayMs}. target 5종, rate 0~1(생략 시 1), count는 적용 횟수, delayMs는 DELAY에만 1~60,000ms | target 5종, delayMs 1~60,000ms, rate 0.0~1.0 | — | ISE-129 | 설계 | docs/design/payment/mock-pg.md:118-145 | 표 같은 파일:124-131 | 장애를 주문 단위로 정확히 주입 |
| L-49 | 인프라 | 모드와 호출 적용 표: 모드 10종(DELAY, DECLINE, DROP_RESPONSE, PROCESSING, SERVER_ERROR, BAD_REQUEST, MISSING_AMOUNT, WRONG_AMOUNT, FRACTIONAL_AMOUNT, REJECT). 걸 수 없는 조합은 설정 시 400 | 모드 10종 × 호출 5종 | — | — | 설계 | docs/design/payment/mock-pg.md:150-170 | 표 같은 파일:154-165 | 장애 시연 메뉴 |
| L-50 | 인프라 | WRONG_AMOUNT는 요청 금액에서 1,000원을 뺀 금액으로 저장·승인하고, FRACTIONAL_AMOUNT는 0.5원을 뺀 금액으로 저장·승인한다 | 1,000원 / 0.5원 | — | `test` 프로필만 | 설계 | docs/design/payment/mock-pg.md:163-164,148 | 표 | 금액 불일치 감지(INV-3) 시험 |
| L-51 | 인프라 | `count`를 쓴 이유: `PgScenario`의 "바로 다음 호출 한 번에만 적용" 구현. count도 전역 상태라 배경 호출(재확인 스케줄러, 환불 워커)이 한 번을 가져갈 수 있어 orderId·refundId 매칭을 함께 쓴다. 이전 문서의 "count가 동시 실행을 해결한다" 근거는 틀렸다고 정정 | 해당 없음 | — | 2026-10-30 Opus 리뷰 반영 | 판단(정정) | docs/design/payment/mock-pg.md:130-136,242 | — | 리뷰로 설계 근거가 뒤집힌 순간 |
| L-52 | 인프라 | DROP_RESPONSE는 POST(승인·망취소·환불)에만 허용. JDK HttpClient는 응답을 한 바이트도 받지 못한 채 끊긴 GET/HEAD를 자동으로 한 번 다시 보내 CONNECTION_LOST를 만들 수 없다(리뷰가 JDK 소스로 확인) | 자동 재시도 1회(GET/HEAD) | — | — | 설계(소스 확인) | docs/design/payment/mock-pg.md:167 | — | 이유 있는 제한 |
| L-53 | 인프라 | 장애 주입·원장 조회 컨트롤러는 `@Profile`로 등록. 운영형 6모드는 test·loadtest, 변조형 4모드는 test만. prod·local 단독으로는 컨트롤러 빈이 없어 404. prod+loadtest 동시 활성화는 시작 검증으로 기동 실패 | 운영형 6 / 변조형 4 | — | ISE-129·130 | 설계 | docs/design/payment/mock-pg.md:172-181 | 표 같은 파일:176-179 | 운영 사고 방지 안전장치 |
| L-54 | 인프라 | 배포 서버는 compose.deploy.yml이 Mock PG를 loadtest로 띄워 장애 주입 API가 열려 있다(ISE-71). 브라우저 시연 패널은 Gateway 운영자 경로(`/api/v1/admin/demo/pg/faults`·`/ledger/summary`, ADMIN만)로만 호출(ISE-134) | 해당 없음 | — | 배포 서버 | 설계 | docs/design/payment/mock-pg.md:187 | — | 시연 장애 패널 |
| L-55 | 인프라 | compose 프로필 문제(공유 파일이 모든 서비스를 local로 띄워서 측정·시연 때 /faults가 404): 이 설계는 정하지 않고 팀 확인 대기(S-30), mock-pg만 `local,loadtest`로 띄우는 override 제안. 팀 공유 파일을 개인 판단으로 덮어쓰지 않는다 | 해당 없음 | — | 시연 11/6 전 확인 필요 | 판단(보류) | docs/design/payment/mock-pg.md:185,245 | — | 팀 합의 존중 판단 |
| L-56 | 인프라 | 응답 유실(DROP_RESPONSE) 구현 1안(채택): Tomcat Valve에서 `getNext().invoke()` 뒤 `ActionCode.CLOSE_NOW`로 연결을 즉시 닫는다. 컨트롤러는 저장 후 null을 반환해 응답을 커밋하지 않는다(ResponseEntity를 주면 헤더가 flush됨). 지연으로 흉내 내면 TIMEOUT이 되어 시나리오가 달라진다 | 해당 없음 | — | Spring Framework 7.0.10, Tomcat 11.0.27 소스 확인 | 설계(소스 확인) | docs/design/payment/mock-pg.md:191-199 | — | "응답을 잃은" 장애를 한 바이트도 안 보내고 재현 |
| L-57 | 인프라 | 2안(본문 쓰다 예외)은 상태 줄·헤더가 이미 나가 "한 바이트도"와 어긋나고 ERROR 스택 로그가 남아 1안이 막힐 때만 쓴다. Content-Length를 크게 주는 방식은 읽기 시한이 지나서 통과하는 "잘못된 이유의 통과"라 제외 | 해당 없음 | — | — | 판단(대안 기각) | docs/design/payment/mock-pg.md:203-205 | — | 잘못된 이유의 통과를 걸러내는 검증 |
| L-58 | 인프라 | 스파이크(ISE-101, 2026-11-02) 결과: 원시 소켓 첫 read()가 -1, Valve의 CLOSE_NOW 한 줄을 빼면 테스트가 실패(변이 확인), 응답 유실 주문의 조회가 APPROVED, 어댑터 분류 CONNECTION_LOST가 읽기 시한 1초보다 짧게 끝남 | CONNECTION_LOST 소요 49ms(읽기 시한 1초 대비), 1회 측정 | 직접 계산: 49ms/1000ms = 4.9% | `MockPgServerContractTest`, 1회 측정 | 로컬 측정 | docs/design/payment/mock-pg.md:215-223 | 표 같은 파일:217-223 | 변이 테스트로 증명한 응답 유실 |
| L-59 | 인프라 | 테스트 기반: `MockPgProcess`가 bootJar를 별도 JVM으로 띄움. 기동 2회 측정 1.2초, 1.4초. 포트 경합 시 최대 3번 재시도 | 1.2초, 1.4초(2회) | — | 로컬 | 로컬 측정 | docs/design/payment/mock-pg.md:226-227 | — | 실제 서버를 띄우는 테스트의 기동 비용 |
| L-60 | 인프라 | 계약 테스트 실행 방식 결정: (C) bootJar를 별도 프로세스로 띄움. (A) 같은 JVM, (B) Testcontainers는 기각. 근거: 앱 모듈 plain jar 꺼짐, 두 모듈 application.yml 클래스패스 충돌, payment-service 테스트가 이미 Docker에 의존, system-tests가 eureka bootJar를 ProcessBuilder로 실행하는 선례 | 선택지 3개 중 1개 | — | 2026-10-30 사용자 결정 | 판단(선택지 비교) | docs/design/payment/mock-pg.md:240 | 표 같은 파일:237-245 | 선택지 비교 → 이유 → 감수 |
| L-61 | 인프라 | 처리 중 승인 재요청에 409 `REQUEST_IN_PROGRESS`를 주기로 결정(토스의 알려진 동작을 닮게). 조회·취소의 IN_FLIGHT는 202. CONFLICT 주입 모드가 불필요해지고 어댑터는 둘 다 PROCESSING으로 분류해 서비스 동작은 같다 | 해당 없음 | — | 결정 #3 | 판단 | docs/design/payment/mock-pg.md:85,241 | — | — |
| L-62 | 인프라 | 환불 규칙(8절): `paymentsByTx.compute(pgTxId, ...)` 안에서 멱등 재확인·승인 항목 존재·통화·누계 한도를 모두 판정. 통과하면 refundedTotal 증가와 refunds 저장. 환불 DELAY는 저장 전에 건다(워커가 응답 시간 초과로 같은 refundId 재호출하는 상황 재현) | 해당 없음 | — | ISE-132 | 설계 | docs/design/payment/mock-pg.md:249-257 | — | 환불 원자성 |
| L-63 | 인프라 | 개정 전 구멍: 멱등 조회가 원자 연산 밖에 있어 같은 refundId 동시 요청 둘이 모두 통과해 둘째가 한도 초과 422를 받아 서비스가 환불을 NEEDS_MANUAL_CHECK로 잘못 돌리고, 부분 환불이면 refundedTotal이 두 번 더해지지만 원장엔 한 건만 남는다 | 동시 요청 2건 | — | 지금은 2-1이 같은 compute 안이라 막힘 | 판단(설계 결함 발견·수정) | docs/design/payment/mock-pg.md:259 | — | 리뷰가 잡은 동시성 구멍(차단 B1 계열) |
| L-64 | 인프라 | 같은 refundId가 다른 pgTxId로 동시에 오면 compute가 줄 세우지 못하므로 `refunds.putIfAbsent`로 저장하고 진 쪽은 OTHER로 답하며 누계를 늘리지 않는다. 거절은 저장하지 않아 거절된 refundId의 GET은 404 | 해당 없음 | — | ISE-132 | 설계 | docs/design/payment/mock-pg.md:261,265,267 | — | — |
| L-65 | 인프라 | 원장 조회 API 5개: payments/{orderId}, refunds?pgTxId=, summary, orders?status=, POST reset(204). test·loadtest 전용, 조회는 호출 계수를 올리지 않는다. summary에 inFlightCount, 목록 limit 기본 100(1~1000) | API 5개, limit 기본 100 범위 1~1000 | — | ISE-128 | 설계 | docs/design/payment/mock-pg.md:272-291 | 표 같은 파일:274-280 | 부하·장애 시험 후 원장 대조 |
| L-66 | 인프라 | 테스트의 `awaitPendingEffects()`는 inFlightCount 폴링으로 구현(sleep 사용 금지) | 해당 없음 | — | — | 설계 | docs/design/payment/mock-pg.md:283,291 | — | — |
| L-67 | 인프라 | 구현 6단계 분할(ISE-101 스파이크, 127, 128, 129, 130, 132, 133). 이전 분할(PR 2개, ISE-102·103)이 리뷰 단위로 너무 커서 2026-11-02 사용자 요청으로 6개로 나누고 ISE-102·103은 취소 | PR 분할 2개 → 6개(스파이크 제외 단계 1~6) | 직접 계산: 2→6 = 3배 | 2026-11-02 | 판단 | docs/design/payment/mock-pg.md:295-305 | 표 같은 파일:297-305 | 큰 PR을 쪼갠 결정 |
| L-68 | 인프라 | 환불 @Tag 제외 계획은 쓰지 않았다. 환불 장애 주입·원장 조회·reset을 ISE-128·132에서 먼저 구현해서 처음부터 공통 계약 23개 전부를 실제 서버로 돌린다(ISE-130 완료) | 23개 전체 | — | — | 설계/판단(계획 변경) | docs/design/payment/mock-pg.md:303,305,307 | — | 계획이 바뀐 순간 |
| L-69 | 인프라 | 응답 본문 고정: 어댑터가 본문 code·status로 분류하므로 서버가 항상 직접 만든다(Boot 기본 오류 본문은 code가 없어 404가 CLIENT_ERROR로 분류됨). 7개 상황 표 | 상황 7종 | — | — | 설계 | docs/design/payment/mock-pg.md:316-326 | 표 같은 파일:318-326 | — |
| L-70 | 인프라 | 빌링키: `bk-<UUID>.<서명>`, 서명은 HmacSHA256(UUID)의 URL-safe Base64. 저장하지 않고 승인 때 서명을 다시 계산해 확인. 예전 메모리 Set은 배포(컨테이너 재기동) 때마다 빌링키가 모두 BILLING_KEY_INVALID로 거절됐고 Set에 크기 제한이 없었다. 서명 키는 `mock-pg.billing.signing-secret`(env `MOCK_PG_BILLING_SIGNING_SECRET`), 비면 기동 안 함(ISE-235) | 해당 없음 | — | 서명 키 교체 시 기존 빌링키 모두 무효 | 설계/판단 | docs/design/payment/mock-pg.md:339-345 | — | 재시작에도 살아남는 빌링키(메모리·재기동 문제 해결) |
| L-71 | 인프라 | 승인 거래 ID 서명(ISE-253): 11/12 19:32 배포 뒤 16:51에 Mock으로 승인된 결제의 예매를 취소하자 환불이 NOT_REFUNDABLE로 거절되어 NEEDS_MANUAL_CHECK가 되고 심각 알림이 왔다. 원인: `Ledger.refund`가 paymentsByTx에 항목이 없으면 거절 | 시각: 승인 16:51, 배포 19:32 | 직접 계산: 배포와 승인 사이 2시간 41분 (원문 시각 순서는 "19:32 배포 뒤 16:51 승인"으로 쓰여 있으나 배포 날짜가 달라 같은 날 비교는 불가, 날짜 확인 못 함) | 11/12 | 배포 측정(사건) | docs/design/payment/mock-pg.md:348 | — | 배포가 원장을 날려 환불이 막힌 사건 |
| L-72 | 인프라 | 거래 ID 형식: `pg-<UUID>.<승인 금액>.<통화>.<서명>`. 서명은 HmacSHA256(거래 ID 키, "<UUID>.<금액>.<통화>")의 URL-safe Base64. 길이 약 100자, `payments.pg_tx_id` varchar(200)에 들어간다. 금액은 stripTrailingZeros().toPlainString()으로 표현 고정 | 약 100자, 컬럼 한도 200자 | 직접 계산: 100/200 = 50% | — | 설계 | docs/design/payment/mock-pg.md:350 | — | 서명으로 상태를 가진 척 |
| L-73 | 인프라 | 키 파생: 새 환경변수 없이 기존 signing-secret에서 `거래 ID 키 = HmacSHA256(secret, "mock-pg/tx-id/v1")`로 용도별 키를 파생해, 같은 키를 두 용도에 쓸 때 한쪽 서명을 다른 쪽으로 돌려쓰는 것을 막는다. 빌링키 쪽은 바꾸지 않음 | 해당 없음 | — | — | 설계 | docs/design/payment/mock-pg.md:353 | — | 키 용도 분리 |
| L-74 | 인프라 | 되살리기: 원장에 없는 거래의 환불이 오면 서명을 원장 잠금 밖에서 확인하고 맞으면 서명에 담긴 금액·통화로 paymentsByTx 항목을 만들어 8절 판정을 그대로 적용. 주문(orders)은 되살리지 않는다. reset 뒤에도 서명 거래의 환불은 되살아난다 | 해당 없음 | — | ISE-253 | 설계 | docs/design/payment/mock-pg.md:352,354 | — | — |
| L-75 | 인프라 | 한계: 재시작 뒤 결제 조회·환불 조회는 여전히 NotFound. 재시작 전에 환불한 거래를 재시작 뒤 다시 환불하면 다시 받는다(이중 환불 가능 경로). 서명 없는 옛 거래 ID(ISE-253 이전 승인)는 구제되지 않는다 | 11/12 배포 전 Mock 승인 결제 약 4만 6천 건이 구제 불가 | — | 원문 "약" 표기, Mock이라 받아들임 | 설계(한계)/측정 규모 | docs/design/payment/mock-pg.md:355-359 | — | 이번 사건의 영향 범위 |
| L-76 | 인프라 | 재시작 영향: 원장은 프로세스 메모리에만 있어 재시작하면 주문이 사라져 승인된 주문 조회는 NotFound, CANCELED였던 주문은 다시 승인된다(INV-8 위반처럼 보임). ISE-253 이후 서명 거래 환불 하나만 살아남는다. 부하 테스트 중 Mock PG 재시작은 결과를 믿을 수 없다 | 해당 없음 | — | — | 설계(한계) | docs/design/payment/mock-pg.md:67-75 | — | 인메모리 원장의 한계 |
| L-77 | 결제 | Gateway 연동: 문서 간 정합성 항목 — pg-integration.md 6절 프로토콜 표에 409 REQUEST_IN_PROGRESS, GET IN_FLIGHT 202, CANCELED 주문 GET 404 등을 반영. S-28 "주문별 승인 수"를 "승인 호출 수"로 정정, S-29(장애 주입 확장), S-30(compose 프로필) 추가. SA에 "10-6"절은 없고 장애 주입은 6-6·11-4·11-5에 있다 | 해당 없음 | — | — | 판단(정정) | docs/design/payment/mock-pg.md:328-335 | — | 문서 오류 정정 |
| M-01 | 좌석 | 수정하지 않은 코드(base)는 오픈 직후 부하에서 좌석 조회 0.13%만 성공했고, 최종(F)은 100% | 좌석 조회 성공 0.13% (0.11~0.13%) → 100% (3회 모두) | 100/0.13 = 약 769배 (직접 계산) | K1 L(초당 100명, 300초), base `01fd217` vs F `5e9e42a`, 3회, i9-14900HX·Docker VM 32코어 16GiB, 배포 메모리(gateway 768MB, reservation 1,152MB) | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:523-526 ; docs/design/reservation/k6-seathold-story.md:7,184 | — | "0.13%에서 100%로": 한 문장 헤드라인 |
| M-02 | 좌석 | 매진 전 좌석 조회·선점 p95가 3초 초과에서 7ms·8ms로 | 거의 모두 3초 초과 → 7ms · 8ms | 3,000/7 = 약 430배 이상 (직접 계산, base는 "3초 초과"의 하한) | K1 L, 매진 전 구간, base vs F | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:526 ; docs/design/reservation/k6-seathold-story.md:185 | — | 전후 p95 대비 |
| M-03 | 좌석 | F에서 선점 성공 약 10,100건, 15,000석 매진 시각 189초(187~197초). base는 선점 0건·매진 안 됨 | 0건·매진 안 됨 → 약 10,100건·189초 | — | K1 L 3회 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:527 ; docs/design/reservation/k6-seathold-story.md:186 | — | 매진 시각이 개선 지표 |
| M-04 | 좌석 | K1 스트레스에서 버틴 단계 10명(회복 실패) → 640명 3회 100%, 1,280명 2회 100%·1회 81.6%(p95 33ms) | 10명 → 640~1,280명 | 640/10 = 64배, 1,280/10 = 128배 (직접 계산) | 도착률(초당 새 사용자) 기준, 동시 접속자 아님. 640·1,280 단계는 매진 뒤라 좌석 조회만 받음 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:528 ; docs/design/reservation/k6-seathold-story.md:187,193 | — | 한계 단계 비교(단 매진 뒤 한계라는 단서 필수) |
| M-05 | 좌석 | GC 정지가 부하 시간에서 차지하는 비율 약 76% → 1% 안팎 | 약 76% → 1% 안팎 | — | K1 S, base vs F | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:529 ; docs/design/reservation/k6-seathold-story.md:188 | — | Serial GC가 시간의 3/4을 멈춘 장면 |
| M-06 | 좌석 | 단계 표: base L 0.13% / G1 0.7% / R1 40.3% / P4a 81.3% / P4b 100% / P4c 100% | 0.13 → 0.7 → 40.3 → 81.3 → 100 → 100 (%) | — | K1, 1회씩(P4c·F 3회), 새 기기 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:512-519 ; docs/design/reservation/k6-seathold-story.md:49-56 | — | 병목이 옮겨 간 계단 그래프(GC → 커넥션 대기 → DB 조인 → 직렬화) |
| M-07 | 좌석 | 단계별 S 버틴 단계: base 10명(회복 실패), G1 20명(회복 실패), R1 20명(부하 뒤 40초 회복), P4a 80명(30초 뒤 회복), P4b 640명, P4c 1,280명 | 10 → 20 → 20 → 80 → 640 → 1,280 (명/초) | — | 도착률 기준 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:514-519 ; docs/design/reservation/k6-seathold-story.md:51-56 | — | 한계점이 단계마다 오르는 계단 |
| M-08 | 좌석 | base: Serial GC 정지가 부하 시간의 약 75%(단계표 표기. 5-1 구 기기 측정은 6분 중 Major GC 101.5초) | 약 75% (새 기기, 3회) ; Major GC 정지 합계 101.5초, 1회 최대 29.1초 (구 기기 측정 구간 6분) | 101.5/360 = 28% (직접 계산, 구 기기 Major만) | 새 기기 표는 75%, 같은 문서 다른 표는 76%로 표기 → 문서 내 표기 차이(모순 가능, 75% vs 76%) | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:514,529,331 ; docs/design/reservation/k6-seathold-story.md:51,188 | — | 75%와 76% 표기 차이는 인용 시 한쪽으로 통일 필요 |
| M-09 | 좌석 | G1로 바꾸자 GC 정지가 7%로 줄었지만 커넥션 대기 197건이 부하 뒤에도 남음 | GC 정지 약 76% → 7% ; 커넥션 대기 197건 | — | K1, G1 단계 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:515 ; docs/design/reservation/k6-seathold-story.md:52 | — | "하나를 풀면 다음 병목이 보인다" |
| M-10 | 좌석 | R1(커넥션 대기 시한 30초 → 1초)로 좌석 조회 성공 0.7% → 40.3%, 회복 40초 | 0.7% → 40.3% ; 대기 시한 30초 → 1초 ; 회복 "부하 뒤 40초" | 40.3/0.7 = 약 57.6배 (직접 계산) | K1 R1 단계 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:516 ; docs/design/reservation/k6-seathold-story.md:53 | — | 빨리 실패시키면 줄이 비워진다 |
| M-11 | 좌석 | R1 단계에서도 커넥션 대기 시한 초과 500이 28,613건. 좌석 조회 한 건이 엔티티 3만 개를 만듦 | 28,613건 | — | K1 R1 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:516 ; docs/design/reservation/k6-seathold-story.md:53 | — | 다음 병목(P4a)으로 넘어가는 장면 |
| M-12 | 좌석 | P4a(엔티티 대신 필요한 열만 DTO로): PostgreSQL CPU 약 4.3코어, 커넥션 5개가 15,000행 조인·정렬에 묶임 | L 40.3% → 81.3% ; PostgreSQL CPU 약 4.3코어 | 81.3/40.3 = 약 2.02배 (직접 계산) | K1 P4a | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:517 ; docs/design/reservation/k6-seathold-story.md:54 | — | DB 조인 병목 |
| M-13 | 좌석 | P4b(변하지 않는 좌석 배치 캐시)로 100%·매진 전 p95 11ms, 640명까지 버팀. 1,280명에서 44.2%·커넥션 대기 186건 | 81.3% → 100% ; p95 11ms ; 1,280명 44.2% | — | K1 P4b, 단 k6 상한 2.5GiB 아래 값(이후 TS-69로 정정 대상) | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:518 ; docs/design/reservation/k6-seathold-story.md:55 | — | 캐시로 DB 일을 줄임 |
| M-14 | 좌석 | P4c(좌석 상태 1초 공유 캐시): 매진 전 p95 7ms, 1,280명까지. DB 일은 초당 1번으로 줄고 남은 비용은 응답 만들기·직렬화(JFR 약 57%) | 매진 전 p95 11ms → 7ms ; JFR 직렬화 약 57% | — | K1 P4c 3회 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:519 ; docs/design/reservation/k6-seathold-story.md:56 | — | 직렬화가 마지막 병목 |
| M-15 | 좌석 | P4c의 1초 낡은 상태가 409를 늘릴지 확인: 409 비율 76.6% → 76.9% | 76.6% → 76.9% | +0.3%p (직접 계산) | K1, 인기석 쏠림이 원래 409를 많이 만듦 | 로컬 측정 | docs/design/reservation/k6-seathold-story.md:58 | — | 캐시 TTL 걱정이 기우였던 장면 |
| M-16 | 좌석 | 좌석 배치를 공유 Redis에 두면 오히려 느려짐: p95 7.0 → 38.3ms, 한 건당 CPU 약 4.5배, 직접 호출 스트레스 한계 640명 → 160명 | p95 7.0 → 38.3ms ; 한계 640 → 160 (명/초) | 38.3/7.0 = 약 5.5배 (직접 계산), 640/160 = 4배 (직접 계산) | 요청마다 1.3MB 옮겨 역직렬화 | 로컬 측정 | docs/design/reservation/k6-seathold-story.md:59 | — | "캐시를 어디에 둘 것인가"의 반례(로컬 캐시 채택) |
| M-17 | 좌석 | 좌석 조회 응답 약 830KB JSON, 15,000석 객체를 요청마다 만듦 | 약 830KB (배치 응답 1.3MB는 별도 언급) | — | 회차 15,000석 | 계산값(문서 서술) | docs/design/reservation/k6-seathold-bottleneck-plan.md:132,190 ; docs/design/reservation/k6-seathold-story.md:164 | — | 응답이 큰 API가 병목이라는 배경 |
| M-18 | 좌석 | K1-H(Gateway 경유) 800명 선점 p95/p99: base 27/40ms → F 11/16ms | p95 27 → 11ms ; p99 40 → 16ms | 27/11 = 약 2.45배, 40/16 = 2.5배 (직접 계산) | k6 6GiB, 3회 중앙값, 범위 base 22~33/39~63, F 10~11/15~17 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:531-532 ; docs/design/reservation/k6-seathold-story.md:189 | — | 선점 경로 지연 개선 |
| M-19 | 좌석 | K1-H 800명 선점 성공률은 base 91.4% (81.4~99.5%), F 80.8% (80.5~91.4%)로 F가 더 낮게 보임. 원인은 Gateway 인증 차단기 즉시 503 | 91.4% → 80.8% | -10.6%p (직접 계산) | k6 6GiB. 실패는 base·F 모두 차단기 503(회마다 약 4,500~18,600건), reservation 5xx 0 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:531,537 ; docs/design/reservation/k6-seathold-story.md:194-195 | — | 성공률로는 가르지 못한다는 정직한 단서(모순 아닌 해석 주의) |
| M-20 | Gateway | K1-H Gateway 500(ConnectTimeoutException): base 회마다 41~60건 → F 0건 | 41~60건 → 0 | — | 6GiB | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:533 ; docs/design/reservation/k6-seathold-story.md:190 | — | Gateway 연결 실패 소멸 |
| M-21 | 좌석 | reservation-service 5xx·대사 위반 모두 base·F 0·0, 전 회 대사 위반 0 | 0 · 0 | — | 대사 SQL로 매 회 확인 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:534 ; docs/design/reservation/k6-seathold-story.md:37,191 | — | 정합성은 절대 조건 |
| M-22 | 좌석 | 선점 경로 풀 크기: 풀 5 → 10에서 800명 선점 p95 14 → 3ms, p99 33 → 6ms, 커넥션 대기 40 → 0. 풀 20·40은 10과 같음 | p50/p95/p99: 2/14/33ms → 2/3/6ms ; 대기 최대 40 → 0 | p95 14/3 = 약 4.7배 (직접 계산) | Gateway 우회 직접 호출(`IDENTITY=direct`), k6 6GiB, 1회씩. 서버 선점 처리 초당 약 2,270건(평균 3.7ms) → 약 2,210건(평균 2.0ms) | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:543-549 ; docs/design/reservation/k6-seathold-story.md:68-74 | — | 풀을 "측정으로 골랐다"(10) |
| M-23 | 좌석 | 초당 800명(서버 선점 초당 약 2,200건)에서도 선점 경로는 한계에 닿지 않아 P3·P2·P5는 넣지 않음 | 대기 0, p95 3ms | — | 직접 호출 800명 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:549,560-562 ; docs/design/reservation/k6-seathold-story.md:204-206 | — | "직면하지 않은 병목은 미리 고치지 않는다" |
| M-24 | 좌석 | P4d는 조건(대기 평균 1 미만, JFR Jackson 30% 이상)이 맞았는데(대기 0, Jackson 약 32%) 넣지 않음 | JFR Jackson 약 32% | — | local에서는 1,280명까지 서버 병목 없음 | 판단 | docs/design/reservation/k6-seathold-bottleneck-plan.md:559 ; docs/design/reservation/k6-seathold-story.md:203 | — | 조건을 미리 고정하고도 넣지 않은 판단 |
| M-25 | 좌석 | 정합성 수정 S1·S2 투입 후 K1-H 선점 p95는 50명 8~9ms, 100~400명 4~5ms로 넣기 전과 같음 | 8~9ms / 4~5ms (변화 없음) | — | Gateway 경유, k6 2.5GiB, 커넥션 대기 시한 초과 0 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:552 ; docs/design/reservation/k6-seathold-story.md:87 | — | 줄거리 반전: "잃은 처리량을 되찾는다"가 성립하지 않음 |
| M-26 | 좌석 | F의 K1-H 50~400명 p95는 5~9ms. 정합성 수정은 측정 가능한 비용 없이 들어감 | 5~9ms | — | F | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:554 | — | 계획한 서사가 바뀐 순간 |
| M-27 | 좌석 | 멀티 탭 동시 선점(탭 4개가 2석씩, 초당 50명 120초): 한도(4매) 초과 사용자 293명(최대 8석) → 0명 | 293명 → 0명 | — | S1~S4 전(A) vs F(B) 1회씩, 동일 설정(G1·R1·풀 10) | 로컬 측정 | docs/design/reservation/k6-seathold-story.md:93-96 | — | 결함이 부하에서도 재현되고 수정으로 사라짐 |
| M-28 | 좌석 | 연타(같은 멱등키 3번 동시, 초당 50명 120초): 좌석 잡은 2,418명 중 2,416명이 자기 요청에 409 → 0명(세 응답 모두 같은 예매의 201) | 2,416/2,418 → 0 | 2,416/2,418 = 99.9% (직접 계산) | A vs B | 로컬 측정 | docs/design/reservation/k6-seathold-story.md:95 | — | 멱등성 결함의 규모 |
| M-29 | 좌석 | 연타 p95가 약 5ms 늘었다(9.6 → 14.6ms), 멀티 탭 p95는 같음 | 9.6 → 14.6ms | +5.0ms (직접 계산) | A vs B | 로컬 측정 | docs/design/reservation/k6-seathold-story.md:101 | — | 정합성 비용의 실제 크기 |
| M-30 | 좌석 | 만료 스케줄러를 켠 K1 L(선점 유지 1분): 성공 100%, 잠금 대기 0, 기한 지난 HELD 약 9,100건(A)/약 9,050건(B) 적체 | 약 9,100 → 약 9,050건 | — | 만료 한 주기 상한 100(5초 주기, 초당 약 20건) < 기한 도래 초당 약 50건 | 로컬 측정 | docs/design/reservation/k6-seathold-story.md:97,102 | — | 만료 처리 속도가 선점 속도를 못 따라가는 문제 |
| M-31 | 좌석 | 만료 한 주기 상한 100 → 1,000: 적체 약 9,050건 → 57~269건, 누적 만료 4,400 → 17,144건, 선점 p95 8ms 그대로 | 9,050 → 57~269건 ; 4,400 → 17,144건 | 17,144/4,400 = 약 3.9배 (직접 계산) | B만 측정 | 로컬 측정 | docs/design/reservation/k6-seathold-story.md:110,114 | — | 설정 한 줄로 적체 해소(좌석이 약 5초 안에 재판매) |
| M-32 | 좌석 | 2대 만료 경합: A는 읽은 대상의 39%가 이미 처리된 건(헛수고), B(SKIP LOCKED)는 헛수고 0, 처리량 1대의 2배 | 39% → 0 ; 처리량 1배 → 2배 | — | 인스턴스 2대 | 로컬 측정 | docs/design/reservation/k6-seathold-story.md:109,113 | — | S4(SKIP LOCKED)의 효과가 수평 확장에서 드러남 |
| M-33 | 좌석 | 잠금 장애 주입(1차) 중 좌석 조회 성공: A 25% → B 77.5% (재측정 시 A 12.7%, B 53.5%) | 1차 25% → 77.5% ; 재측정 12.7% → 53.5% | 재측정 B/A = 53.5/12.7 = 약 4.2배 (직접 계산) | 인기 구역 근처 잠금. 1차는 잠금이 인기 구역과 덜 겹쳐 좋게 나옴(원문), 재측정은 인기 구역 3,000석을 정확히 잠금. 33초 잠금 | 로컬 측정 | docs/design/reservation/k6-seathold-story.md:108,139 | — | 잠금 하나가 서비스 전체로 번짐 + 값 정정 |
| M-34 | 좌석 | 잠긴 행에 트래픽이 몰릴 때 공연 목록 실패율: 선점 도착률 10 → A 98.2%·B 0%, 20 → 100%·24.7%, 40/80/160 → 100%·65/80/92%, 320 → 100%·99.8% | 위 값 | — | 인기석 10석을 측정 내내 잠금, 구경꾼 API당 초당 10건 | 로컬 측정 | docs/design/reservation/k6-seathold-story.md:120-126 | — | 잠금 장애의 번짐 곡선 |
| M-35 | 좌석 | 잠금 장애가 다른 서비스로 번지지 않음: user·payment 실패 0%(320에서 0.2~1.1%), Gateway는 reservation 경로만 504 | 0% | — | 서비스마다 풀·DB 분리 | 로컬 측정 | docs/design/reservation/k6-seathold-story.md:126-128 | — | MSA 장애 격리 효과 |
| M-36 | 좌석 | 유령 선점: 잠금 해제 순간 A 9건, B 8건의 예매가 생겼지만 클라이언트는 201을 하나도 받지 못함 | 9건, 8건 | — | 클라이언트가 3초 504 뒤 떠난 뒤 서비스 앞 대기 요청이 늦게 처리됨 | 로컬 측정 | docs/design/reservation/k6-seathold-story.md:129 | — | 정합성은 지켜지나 아무도 모르는 좌석이 묶임 |
| M-37 | 좌석 | 매진을 피한 스트레스(섞인 부하): 320명까지 100%(조회 p95 34ms, 선점 p95 27ms), 640명 조회 53.4%·선점 75.8%, 1,280명 13.2%·13.0% | 320명 100% → 640명 53.4%/75.8% → 1,280명 13.2%/13.0% | — | 단계마다 새 회차, 회차 하나에 초당 100명까지. 640명부터 Gateway 즉시 503, reservation 오류 0. Gateway 로그가 없어 원인 미확인 | 로컬 측정 | docs/design/reservation/k6-seathold-story.md:143-152 | — | "1,280명"은 조회만 받은 한계라는 정정(섞인 부하 한계는 320명) |
| M-38 | 좌석 | 모순 후보: 같은 문서 6절 표(K1 S 640명 3회 100%, 1,280명)와 5절 재측정(섞인 부하 320명까지, 640명 53.4%)이 다른 부하(매진 뒤 조회만 vs 섞인 부하)라 숫자가 갈림 | 640명: 100% vs 53.4%(조회) | — | 문서가 스스로 "매진 뒤 조회만"으로 구분해 설명함 | 판단 | docs/design/reservation/k6-seathold-story.md:149,193 | — | 인용 시 반드시 부하 종류를 같이 적어야 함(모순 표시) |
| M-39 | 인프라 | CPU 4코어 상한(배포 4 vCPU 흉내)에서 F: L 성공 100%·매진 전 조회 p95 18ms(상한 없음 7ms), S 160명 100%·p95 149ms, 320명 조회 80.5%·선점 72.3%, 1,280명 12.9% | p95 7 → 18ms ; 320명 100% → 80.5%/72.3% | 18/7 = 약 2.6배 (직접 계산) | 서버 컨테이너 전체를 같은 4코어에 묶음, k6는 다른 코어 | 로컬 측정 | docs/design/reservation/k6-seathold-story.md:156-163 | — | "32코어에서 본 한계는 CPU가 남아서였다" 배포 기대치 하향 |
| M-40 | Gateway | CPU 상한 스트레스 1,280명 단계 끝에 Gateway가 메모리 상한 768MB 초과로 OOMKilled | 같은 조건 3회 중 강제 종료 1회, 메모리 상한 도달 3회 중 3회, 소켓 받기 버퍼 최대 약 590MiB, JDK 직접 버퍼 최대 183MB, 스왑으로 산 회 좌석 조회 p95 최대 5.8초 | — | 로그에 OutOfMemoryError 없음(컨테이너 상한에 걸려 커널이 죽임). 상한 없는 3회에서는 미발생 | 로컬 측정 | docs/design/reservation/k6-seathold-story.md:161,164 | — | 830KB 응답이 Gateway 메모리를 채움(메모리 절 참고) |
| M-41 | Gateway | Gateway 차단기 운영 값(P) vs 시험 값(T): 4코어 320명 49.0% → 99.0%, 상한 없음 640명 38.7% → 88.6%, 1,280명 12.8% → 54.5%. T는 상한 없는 환경에서 Gateway OOMKilled | 49.0 → 99.0% ; 38.7 → 88.6% ; 12.8 → 54.5% | 320명 4코어 99.0/49.0 = 약 2.0배 (직접 계산) | 1회씩. 운영 값 차단기는 Redis 오류 0건인데 벌크헤드(25) 거절이 실패로 세어져 열림 | 로컬 측정 | docs/design/reservation/k6-seathold-story.md:166-176 | — | 차단기 설정이 성공률을 좌우하나 대가가 메모리 |
| M-42 | Gateway | 측정 환경에서만 Gateway 인증 차단기를 완화(느린 호출 100ms, 실패율 100%). 공유 기준(느린 호출 50ms·비율 50%)은 선점만 보내는 초당 100명부터 Gateway CPU만으로 열림 | 50ms/50% → 100ms/100% ; 열린 뒤 정상 요청 거절 초당 300~750건 ; 통과 요청 초당 약 600건 넘으면 Redis 확인 평균 0.5ms → 8~13ms | — | Redis CPU는 10% 아래, Gateway G1 전환해도 같음 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:584 ; docs/design/reservation/k6-seathold-story.md:216 | — | 공유 설정을 안 고치고 측정 환경만 덮어쓴 판단 |
| M-43 | 좌석 | 선점 경로 초기 기준선 K1-H(P0'' 구 기기, 풀 5): 사용자 30,001명·72,208건(초당 217건), 201 8,708건, 409 59,476건, 503 4,020건, 504 3건, 사용자당 평균 2.41요청 | 선점 p50/p95/p99 11/319/1,684ms ; 409 7/16/122ms | 409 비율 59,476/72,208 = 82.4%(요청 기준, 직접 계산; 문서는 201·409 중 87%로 서술: 59,476/(8,708+59,476) = 87.2%) | 풀 5, 배포 메모리+G1, 구 노트북, 1회 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:464-473,193 | — | 선점 경로는 100명/초를 버틴다 |
| M-44 | 좌석 | K1-H 분당 선점 p95/p99(ms): 0~1분 718/1,769, 이후 14/27, 20/166, 13/30, 12/19, 14/23 | 718 → 14ms (1~2분) | 718/14 = 약 51배 (직접 계산) | 첫 1분은 JIT 전·커넥션 대기(최대 171) 겹침 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:477-483 | — | 워밍업이 필요한 이유(60초 워밍업 도입) |
| M-45 | 좌석 | P0(S0) 구 기기 K1 본 측정: 29,536명 중 좌석 조회 성공 0건, 503 18,274 / 504 5,723 / 500 5,539, dropped 464건 | 성공 0 | — | 컨테이너 512MB·`-Xmx256m`, Serial GC, 풀 5, 같은 기기에서 k6 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:263,321 | — | 최초 기준선이 완전 붕괴 |
| M-46 | 좌석 | P0 낮은 속도: 초당 1명 60명 모두 201, 조회 p50/p95/p99 275/597/2,009ms. 초당 2명은 조회 504 → 한계 초당 1~2건 | 1명/초 OK → 2명/초 붕괴 | 목표 초당 100명과 두 자릿수 차이(원문) | 구 기기, Serial, 256MB 힙 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:322-324,339 | — | 한계가 초당 1~2건이라는 충격 |
| M-47 | 좌석 | P0 무너진 순서: Major GC 정지 합계 101.5초(6분), 1회 최대 29.1초, Hikari 대기 최대 197건, DB 활성 세션 최대 2·idle in transaction 최대 5, 부하 10분 뒤에도 회복 못 함 | 197건, 101.5초, 29.1초 | — | 서버 안에서 끝난 조회 200은 172건, 중앙값 27.5초·최대 54.4초(전부 Gateway 3초 초과) | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:330-337 | — | DB가 아니라 앱이 커넥션을 쥔 채 멈춤 |
| M-48 | 좌석 | 초당 1명에서도 60초 중 GC 정지 8.1초(약 11%), 힙 최대 206MB | 8.1초, 약 11%, 206MB | — | 구 기기 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:339 | — | 낮은 부하에서도 GC가 보임 |
| M-49 | 좌석 | P0'(배포 메모리 768MB 힙): 한계 초당 1~2명 → 5~10명, 힙 최대 707MB(상한 768MB), GC 정지 Major 약 108초·Minor 약 133초(측정 7분), 서버 안 조회 200은 4,055건(평균 25.6초) | 한계 1~2 → 5~10명/초 ; 5-1의 172건 → 4,055건 | 4,055/172 = 약 23.6배(문서는 "약 24배", 직접 계산 일치) | 컨테이너 1,152MB·`-Xmx768m`(메모리 3배), 여전히 Serial | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:380-398 | — | 메모리를 3배 늘려도 해결 안 됨 |
| M-50 | 좌석 | P0''(G1): GC 정지 합계 약 241초 → 81.8초, 최대 0.9초(4,094회 중 4,031회가 50ms 미만), Full GC 2회, 오류 0 최대 속도 5 → 10명 | 241 → 81.8초 ; 5 → 10명/초 | 81.8/241 = 0.34(약 3분의 1, 원문과 일치) | 구 기기, 풀 5 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:423-435,437 | — | G1이 정지를 풀었으나 초당 100명에서 여전히 붕괴 |
| M-51 | 좌석 | G1에서도 초당 100명 조회 성공 18/29,994건, 서버가 소화한 조회 초당 약 12건, 앱 CPU 평균 약 6코어(16코어 중) | 12/30,014 → 18/29,994건 | 18/29,994 = 0.06%(직접 계산) | 구 기기 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:425,432,438 | — | 판정 B(P4를 먼저): 순서를 바꾼 근거 |
| M-52 | 좌석 | JVM은 메모리 약 1,792MB 미만이면 Serial GC를 자동 선택하므로 배포 서버 reservation(1,152MB)도 Serial GC | 1,792MB 기준 | — | 컨테이너에서 `java -XX:+PrintFlagsFinal`로 `UseSerialGC = true {ergonomic}` 확인 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:375 | — | "배포 환경은 괜찮다"는 근거가 없었다 |
| M-53 | 인프라 | 배포 설정 앱 7개에 `-XX:+UseG1GC` 명시(ISE-193). 배포 서버(main `0e779531`)의 앱 8개가 모두 `gc="G1 Young Generation"` | 7개 명시, 8개 확인 | — | 2026-11-08 결정, 2026-11-10 확인 | 배포 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:448-449 | — | 측정 결과가 공유 설정으로 반영된 증거 |
| M-54 | 좌석 | 가정(1-1): 초당 100명·300초·총 30,000명, 좌석 15,000석, 1.5석/명, 수요는 약 45,000석(좌석의 3배), 70%가 앞 구역 10%(1,500석) 선택 | 100명/초, 30,000명, 15,000석 | 30,000×1.5 = 45,000석 | `queue.admit-per-second`는 현재 50(SA 9-4 임시값)이라 측정은 2배 | 설계 | docs/design/reservation/k6-seathold-bottleneck-plan.md:17-21 | — | 시나리오 정의 |
| M-55 | 좌석 | 요청량 예상: k=0 조회 100·선점 100·합 200 req/s, k=0.5 합 300, k=1.0 합 400. 서버 안 사용자 약 100명×2초 = 200명(리틀의 법칙) | 200~400 req/s ; 약 200명 | — | 고민 1~3초 | 계산값 | docs/design/reservation/k6-seathold-bottleneck-plan.md:31-37 | — | 도착률 vs 동시 접속자 구분 |
| M-56 | 좌석 | 15,000석 소진 최소 약 100초(초당 약 150석 판매 가정). 판매 구간 0~약 100~150초, 이후 매진 뒤 구간 | 약 100초 | 15,000/150 = 100초 | 선점이 모두 성공할 때의 하한 | 계산값 | docs/design/reservation/k6-seathold-bottleneck-plan.md:43 | — | 매진 시각이 지표 |
| M-57 | 좌석 | 측정 종류 설계 L·S·R: S는 60초마다 도착률 두 배(K1 5→10→20→40→80→160→320명, 이후 640·1,280까지 확장 / K1-H 50→100→200→400→800), R은 120초 동안 10초마다 조회 한 건, 워밍업 60초(처음 제안 30초) | 60초/단계, 120초, 60초 | — | 판정 기준은 `[가정]` | 설계 | docs/design/reservation/k6-seathold-bottleneck-plan.md:111-117 | — | 부하·스트레스·회복을 나눈 이유 |
| M-58 | 좌석 | 판정 기준 `[가정]`: 오류 1% 미만, 조회 p95 500ms·선점 p95 1,000ms 이하, 대사 0, 스트레스 "버틴 최대 속도"=오류 1% 미만이고 p95가 Gateway 시한(3초) 절반 이하인 마지막 단계 | 1%, 500ms, 1,000ms, 3초의 절반 | — | 임계값은 P0 측정 뒤 정하기로 함 | 설계 | docs/design/reservation/k6-seathold-bottleneck-plan.md:61-65,113-114 | — | 목표 숫자의 근거 |
| M-59 | 좌석 | 예상 병목 B1~B8과 실측 순위: B1 커넥션 풀 5(예상 1순위 → 실측 2순위), B2 좌석 조회(실측 1순위), B8 과부하 뒤 회복 불가(회복에 약 10분) 등 | 풀 5 ; Tomcat 연결 8,192·커넥션 대기 30초 ; 회복 약 10분 | — | 코드 읽기 가설. 2026-11-05 커넥션 대기 1초(R1)만으로 10초 안에 회복 | 설계(+로컬 측정으로 수정) | docs/design/reservation/k6-seathold-bottleneck-plan.md:129-138 | — | 예상과 실측 순위가 뒤집힘 |
| M-60 | 좌석 | 선점 한 건이 DB 왕복 약 12~14번, JDBC 배치 불가(IDENTITY), ID 생성 방식 문제 | 12~14번 | — | 코드를 읽고 센 값(원문) | 계산값(코드 읽기) | docs/design/reservation/k6-seathold-bottleneck-plan.md:133-134 | — | 왕복 수가 훗날 반복 3·4에서 줄어드는 전조 |
| M-61 | 좌석 | JUnit 근사 측정(2026-11-03): 풀 5 처리량이 VU 50부터 멈춤(S0 446~478 req/s), 대기 스레드 최대 196개. VU 400 처리량 중앙값 S0 1,125 → S3 669 req/s(편차 큼), S4 전 648 req/s | 1,125 → 669 req/s ; 1,125 → 648 req/s | 669/1,125 = 0.59(-41%), 648/1,125 = 0.58 (직접 계산) | JUnit 근사(부하 생성기와 앱이 같은 JVM, Gateway 미경유) | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:131,137,252 | — | 정합성 수정이 처리량을 깎을 것이라는 예상(후에 k6에서 반증) |
| M-62 | 좌석 | 단계 계획의 최종 실제 구성: K1은 base→G1→R1→P4a→P4b→P4c(P4d 미적용), K1-H는 S1→S2→P1 풀 10→S3·S4 cherry-pick(P3·P2·P5 미적용), F=P4c+S1~S4+G1·R1·풀 10 | — | — | 측정 브랜치 `experiment/ISE-79-k6-steps`(머지 안 함), 한 단계=커밋 하나 | 설계 | docs/design/reservation/k6-seathold-bottleneck-plan.md:502-508,155 | — | 계획 대 실제 |
| M-63 | 좌석 | ISE-79 정합성 수정 커밋: S1 `5b2a9b4`, S2 `4eaa6d3`, S3 `3b9b0bf`, S4 `c307621`. 동시성 테스트 C3은 처음 최대 1,099ms로 목표 1초 초과 → 목표 1,500ms로 조정(`lock_timeout`이 잠금 시도마다 걸려 상한 약 1초) | 1,099ms → 목표 1,000ms → 1,500ms | — | concurrency-results.md 4-3절 참조 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:220 | — | 목표를 코드가 아니라 측정에 맞춰 고친 장면 |
| M-64 | 좌석 | 동시성 테스트 base 코드: C4 30라운드 모두 한도 초과, 최대 8매. C5 자기 요청에 409 60건. C6 헛수고 100건 | 8매, 60건, 100건 | — | 측정 브랜치 base | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:219 | — | 결함이 숫자로 재현 |
| M-65 | 좌석 | P4a 구현 대조(측정 브랜치 vs dev): L 3회 중앙값 좌석 조회 성공 측정 브랜치 82.7%, dev 79.4%(-3.3%p), 서버 조회 처리량 초당 169.4건 vs 158.7건(-6.3%), S 버틴 단계 둘 다 40명 | 82.7 → 79.4% ; 169.4 → 158.7건/초 | -3.3%p ; 158.7/169.4 = 0.937(-6.3%) | G1·R1, 풀 5, k6 6GiB. 채택 기준(5%p, 10%, 같은 단계) 안 | 로컬 측정 | docs/design/reservation/k6-seathold-story.md:242 | — | 구현이 달라도 원리가 같다는 검증 |
| M-66 | 좌석 | ISE-189 S1(좌석 조회 단독): P4a 전후 초당 50건 p95 2.68초 → 40ms, 실패 4 → 0 | 2.68초 → 40ms ; 실패 4 → 0 | 2,680/40 = 67배 (직접 계산) | 초당 30·40·50건 30초, local 노트북, 배포 메모리+G1 | 로컬 측정 | docs/design/reservation/k6-seathold-story.md:230-240 | — | K1과 같은 방향의 별도 검증 |
| M-67 | 좌석 | dev에는 P4a만 들어 있고 P4b(ISE-204)·P4c(ISE-205)는 Backlog. F 결과는 측정 브랜치 P4b·P4c 코드로 잰 값이라 지금 dev는 P4a 수준 | — | — | 문서 작성 시점(2026-11-11) 기준, 현재 상태는 확인 못 함 | 판단 | docs/design/reservation/k6-seathold-story.md:244 | — | 발표에서 F를 말할 때 반드시 붙일 단서 |
| M-68 | 좌석 | 측정 도구 문제: k6 메모리 상한 2.5GiB에 닿아 GC로 멈췄다 요청을 몰아 보내 Gateway 차단기를 자주 열었고, 한때 "1,280명 한계는 Gateway와 부하 생성기"로 오판 → 상한 6GiB(`GOMEMLIMIT` 5632MiB)로 올리니 같은 코드가 1,280명 100% | 2.5GiB → 6GiB | 6/2.5 = 2.4배 (직접 계산) | TS-69. 2026-11-10부터 6GiB | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:499 ; docs/design/reservation/k6-seathold-story.md:213 | — | 측정값을 검증한 반전 사례(발표 소재) |
| M-69 | 좌석 | 이전 기기에서 P4b가 부하 생성기 한계·메모리 부족·k6가 응답 본문 쥔 채 대기 문제로 4번 무효, 5번째에 유효. 유효 기준: k6 비정상 종료(137·2) 없음, 수집 간격 15초 이내, 대사 위반 0 | 무효 4회 → 유효 5회째 | — | 구 기기 Docker 메모리 7.6GiB | 로컬 측정 | docs/design/reservation/k6-seathold-story.md:214-215 ; docs/design/reservation/k6-seathold-bottleneck-plan.md:500 | — | 무효 회를 걸러낸 검증 장면 |
| M-70 | 인프라 | 측정 기기 교체: 구 노트북(16코어, Docker 7.6GB)에서 i9-14900HX(32코어 Docker VM, 16GiB, Docker Desktop 29.9.2)로. 병목이 옮겨 간 순서는 두 기기에서 같았음 | Docker 메모리 7.6GB → 16GiB, 16코어 → 32코어(구 기기 16코어 표기는 5-1 환경표, 5-1에서 "Windows 11, 16코어") | — | 2026-11-09 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:309,494-497 ; docs/design/reservation/k6-seathold-story.md:214 | — | 기기가 다르면 단계 비교가 안 됨 |
| M-71 | 좌석 | 비교점 반복: 탐색 측정 1회, 발표용(기준선·마지막 단계·최종)만 3회, 모든 단계를 3회씩 하면 한 단계에 약 40분 | 3회, 약 40분/단계 | — | 같은 조건 결과가 2배 넘게 흔들린 경험(B8에서 사용자 성공 1,463~3,432건) | 설계 | docs/design/reservation/k6-seathold-bottleneck-plan.md:119-120,300 ; docs/design/reservation/k6-seathold-story.md:45 | — | 통계 신뢰도 장치 |
| M-72 | 좌석 | 조건부 단계 판단 기준 `[가정]`: P4d = 대기 평균 1 미만 + JFR Jackson 30% 이상, P2 = 트랜잭션 안 비DB 시간 10% 이상, P5 = 한계점에서 409 처리 시간 합이 선점 전체 처리 시간 합의 50% 이상, 경계 ±5%p. P5 근사 검증은 `http_server_requests_seconds_sum`과 Hikari 사용 시간 합 차이 20% 이내 | 30%, 10%, 50%, ±5%p, 20% | — | 결과를 보기 전에 고정 | 설계 | docs/design/reservation/k6-seathold-bottleneck-plan.md:229-238 | — | 결과에 맞춰 기준을 옮기지 않는 장치 |
| M-73 | 좌석 | 스파이크·soak 테스트는 넣지 않음: 입장은 대기열이 READY 폴링(3초+jitter)으로 퍼뜨리고, 오픈 순간 폭주는 대기열 진입(Q1, ISE-181)이 받으며, 15,000석은 2~5분에 팔림 | 3초 폴링, 2~5분 | — | 선점 만료 두 번째 파도는 X1(ISE-181) | 설계 | docs/design/reservation/k6-seathold-bottleneck-plan.md:122-123 ; docs/design/reservation/k6-seathold-story.md:217 | — | 측정 범위 경계 |
| M-74 | 좌석 | 입장 속도 N 역산: SA 3-3 식(`L_max = μ / r`, `λ = L_max / W`)에 스트레스 "버틴 최대 속도"를 μ로 넣음. N 변경은 대기열 담당과 정함 | — | — | 한계 측정의 목적 | 설계 | docs/design/reservation/k6-seathold-bottleneck-plan.md:197 ; docs/design/reservation/k6-seathold-story.md:217 | — | 측정이 설정값(입장 속도)을 정하는 연결 |
| M-75 | 좌석 | 구 기기 기준선 K1-H에서 409가 87%(201 8,708 vs 409 59,476) → 좌석 목록 고정 때문에 경합이 실제보다 세다 | 409 59,476/(8,708+59,476) = 87.2% (직접 계산) | — | K1-H 특성, 매진 안 됨(남은 3,141석, 선점 11,859석) | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:193,472,484 | — | K1-H 해석 주의점 |
| M-76 | Gateway | Gateway 인증 Redis 차단기(`auth-redis`)가 열리면 즉시 503. 구 기기 503 4,020건은 Eureka 이탈이 아니라 차단기에서 나옴(5-2의 Eureka 설명 정정). Eureka 이탈이 확인된 것은 P0뿐 | 503 4,020건(지연 중앙값 2ms), 차단기 열림 4구간(UTC 05:48:40 등) | — | 2026-11-05 정정 | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:469,487-490,391 | — | 이전 해석을 정정한 사례 |
| M-77 | 좌석 | LOOP 반복 1: 동시 처리 상한에 대기 인원 상한(64) 추가(`92ba4a47`). 정상 1,800/s: TIMEOUT 27,285 → 0건, 유령 16 → 0석, 좌석 조회 51.0 → 100%, 의도 실패율 19.7 → 11.3%, 요청 p95 3,014 → 54ms | 27,285 → 0 ; 16 → 0 ; 51.0 → 100% ; 19.7 → 11.3% ; 3,014 → 54ms | 의도 실패 11.3/19.7 = 0.57(-43%), p95 3,014/54 = 약 55.8배 (직접 계산) | 한 PC(32코어), 풀 5, 커넥션 대기 1초, Tomcat 200, 열린 모델, 재시도 모사, 조건마다 4회 평균(교차 순서). B 기존 vs C 변경 | 로컬 측정 | docs/design/reservation/seathold-perf-loop.md:21-56 | — | 실패 모양이 "3초 TIMEOUT"에서 "빠른 503"으로 바뀜 |
| M-78 | 좌석 | LOOP 반복 1 잠금 장애 600/s: 의도 실패율 A 67.7% / B 19.5% / C 27.4% / D 25.6%, TIMEOUT 17,283 / 5,300 / 0 / 0, 유령 216 / 17 / 0 / 0, 조회 성공 20.4 / 89.5 / 100 / 100% | 위 값 | 모순 후보: C의 의도 실패율(27.4%)이 B(19.5%)보다 높음. 원문이 이유를 설명(B는 넘친 요청을 Tomcat 대기열에 붙잡았다가 일부 늦게 처리하는 대가로 TIMEOUT·유령 발생) | 같은 조건 | 로컬 측정 | docs/design/reservation/seathold-perf-loop.md:44-49,61 | — | 개선인데 한 지표는 나빠진 정직한 장면 |
| M-79 | 좌석 | 가상 스레드(D) 채택 안 함: 1,800/s 의도 실패 14.7%(C 11.3%), p95 208ms(C 54ms). 서비스 전체 실행 방식을 바꾸는 변경이라 얻는 것 없음 | 14.7% vs 11.3% ; 208ms vs 54ms | — | 반복 1 | 로컬 측정+판단 | docs/design/reservation/seathold-perf-loop.md:39,52-53,64 | — | 대안을 재고 기각한 판단 |
| M-80 | 좌석 | 정상 피크에서 상한을 끈 A가 가장 좋은 이유: 상한은 커넥션 5개 중 4개만 써 처리 한계가 20% 낮음. 1,800/s에서 C는 503→재시도로 요청 기준 실패 53.8%, 평균 1.9번 시도 | 요청 기준 실패 53.8% ; 평균 시도 1.9번 | 4/5 → 20% 낮음 | 반복 1 | 로컬 측정 | docs/design/reservation/seathold-perf-loop.md:62 | — | 상한의 대가 |
| M-81 | 좌석 | 의도 기준/요청 기준 정의: 프론트가 503·응답 없음에 같은 멱등키로 최대 3번 재시도(약 1·2·4초 뒤). 의도 p95는 재시도 대기가 포함 | 재시도 3회, 1·2·4초 | — | 측정 도구에 소비자 재시도 모사(`SEATHOLD_LOAD_CLIENT_RETRY`) 추가 | 설계 | docs/design/reservation/seathold-perf-loop.md:11-13,26,32 | — | 소비자가 겪는 실패를 재는 지표 정의 |
| M-82 | 좌석 | 반복 2 R2-b(커밋 뒤 입장 토큰 정리·결제 토큰 서명을 자리 밖으로): 효과 없음. 정리·서명은 성공(201)에서만 돌고 정상 피크 응답의 약 97%는 409이므로 요청 평균 약 0.02ms(성공 2% × 1ms). 자리 점유 C 3.46~3.73ms vs C2 3.53~3.69ms | 약 0.02ms ; 자리 점유 변화 없음 | 0.02 × 1ms = 성공 2%×1ms (원문 계산) | 입장 토큰·정리 지연 1ms 가정값·재시도 모사·커넥션 탐침, 4회 평균 | 로컬 측정 | docs/design/reservation/seathold-perf-loop.md:90-112,118 | — | 예상이 틀렸고 유지한 판단(비용 없음) |
| M-83 | 좌석 | 반복 2 대기 인원 128(F2) 기각: 실패 그대로(정상 1,200/s 5.9% vs C 5.3%), p95 2배(150 vs 80ms). 실패 원인이 몰림이 아니라 처리 한계 초과(ρ>1) | p95 80 → 150ms ; 1,800/s 128ms vs 68ms | 150/80 = 1.9배 (직접 계산) | 반복 2 | 로컬 측정 | docs/design/reservation/seathold-perf-loop.md:93-103,114 | — | 기각 사례 |
| M-84 | 좌석 | 반복 2 풀 6·자리 5(H2, 참고): 정상 1,800/s 의도 실패 22.5% → 9.4%, 잠금 장애 300/s 503 0. 배경 작업 부하 없는 측정, 풀 크기는 ISE-237(효승) 범위라 채택 안 하고 근거로 전달 | 22.5 → 9.4% | 처리 한계 자리 수에 비례 25% 증가(원문) | 반복 2 | 로컬 측정 | docs/design/reservation/seathold-perf-loop.md:101,115,120 | — | 다른 담당 영역은 근거만 넘김 |
| M-85 | 좌석 | 반복 2 독립 검토 지적: 좌석 조회 탐침은 1초 캐시라 커넥션을 회차당 초당 1번만 써서 "커넥션 보호" 판정 불가, 자리 5(풀과 동일)는 배경 작업 몫이 없어 채택 후보에서 뺌 | 자리 5 후보 제외 | — | 다른 에이전트 세션의 독립 검토 | 판단 | docs/design/reservation/seathold-perf-loop.md:75-78 | — | 독립 검토로 측정 설계가 바뀐 사례 |
| M-86 | 좌석 | 반복 3: 409 경로 조회 합치기(6 → 4번 왕복). 자리 점유 3.58 → 2.40ms, 처리 한계 4 ÷ 3.6ms ≈ 1,110/s → 4 ÷ 2.4ms ≈ 1,670/s(약 50% 상승), 정상 1,800/s 의도 실패 22.6 → 2.4% | 6 → 4번 ; 3.58 → 2.40ms ; 22.6 → 2.4% | 자리 점유 (3.58-2.40)/3.58 = -33% ; 의도 실패 22.6/2.4 = 약 9.4배 (직접 계산) | 커밋 `25fb338b`, 4회 평균 교차 | 로컬 측정 | docs/design/reservation/seathold-perf-loop.md:122-164 | — | 문장 하나 약 0.55ms 줄어든다는 발견 |
| M-87 | 좌석 | 반복 3 잠금 장애 300/s: 503 861건 → 13건, 요청 p95 213 → 145ms, 의도 p95 1.6초 → 0.15초. 600/s 의도 실패 29.1 → 25.1%만 개선(병목이 잠긴 좌석 요청의 50ms 점유) | 861 → 13건 ; 29.1 → 25.1% | 861/13 = 약 66배 (직접 계산) | 반복 3 | 로컬 측정 | docs/design/reservation/seathold-perf-loop.md:144-145,160-161 | — | 개선되는 문제와 안 되는 문제를 구분 |
| M-88 | 좌석 | 반복 3 정상 1,500/s: 의도 실패 9.1 → 0.0%, 1,200/s 7.3 → 0.8%, 2,000/s 30.2 → 10.4% | 9.1 → 0.0% ; 7.3 → 0.8% ; 30.2 → 10.4% | — | 한계 근처는 실패율이 절벽처럼 변함 | 로컬 측정 | docs/design/reservation/seathold-perf-loop.md:146-149,159 | — | 한계를 넘기는 순간 실패 급증 |
| M-89 | 좌석 | 반복 3 독립 검토로 예상 수정: 처음 문장 하나 0.5ms·처리 한계 +40% 예상 → 검토 지적 후 +10~30%로 낮춤. 실측은 +50%로 검토의 보수적 추정보다 컸음 | +40% → +10~30% 예상, 실측 약 +50% | — | 로컬 왕복 0.1~0.3ms일 수 있다는 지적 | 판단 | docs/design/reservation/seathold-perf-loop.md:139,158 | — | 예상 대 실측 |
| M-90 | 좌석 | 반복 3 덤: ①(멱등키)과 ②(입장 토큰)를 한 문장으로 합쳐, 같은 키 첫 요청이 그 사이 커밋되면 재요청이 403을 잘못 받던 경합이 사라짐 | — | — | 검토 세션 지적 | 설계 | docs/design/reservation/seathold-perf-loop.md:135 | — | 성능 개선이 정합성 버그까지 고친 반전 |
| M-91 | 좌석 | 반복 3 버린 안: 토큰 조회를 먼저 거절 뒤로 미루기(검사 순서를 바꿔 사용자가 403 대신 409를 받아 잘못 안내됨) | — | — | `contracts.md` 1절 검사 순서 | 판단 | docs/design/reservation/seathold-perf-loop.md:134 | — | 계약을 지키려고 최적화를 버림 |
| M-92 | 좌석 | 반복 4: 사전 판정을 한 문장으로 + 멱등키 재조회(⑤) 제거(4 → 2번 왕복). 자리 점유 2.32~2.71 → 1.30~1.54ms, 정상 2,400/s 의도 실패 23.2 → 0%, 1,800/s p95 46 → 7.5ms, 처리 한계 약 3,000/s | 4 → 2번 ; 2.4 → 1.3ms ; 23.2 → 0% ; 46 → 7.5ms | 1,800/s p95 46/7.5 = 약 6.1배 ; 자리 점유 중간값 2.5/1.4 = 약 1.8배 (직접 계산, 근사) | 커밋 `14cad0dd`, 4회 평균 교차, 정합성 관문 509건 통과 | 로컬 측정 | docs/design/reservation/seathold-perf-loop.md:167-204 | — | 한 문장 사전 판정이 한계를 3,000/s로 끌어올림 |
| M-93 | 좌석 | 반복 4 ⑤ 제거의 안전성: READ COMMITTED에서 문장마다 스냅샷 하나이므로 한 스냅샷에서 "멱등키 없음 + 그 요청의 좌석 잡힘"이 나올 수 없고, 스냅샷 뒤 커밋이면 좌석도 비어 보여 쓰기로 가 멱등키 PK 충돌 → 재응답(TS-3). ADR 0061 갱신. 접수형 접수의 이론상 자기 좌석 409 경합도 닫힘 | — | — | 독립 검토가 동기형·연타·접수형·접수형→동기형(ADR 0058) 모두 성립 확인. 부분 인덱스 조건은 리터럴로(EXPLAIN으로 인덱스 사용 확인) | 설계 | docs/design/reservation/seathold-perf-loop.md:175-177 | — | 쿼리 하나를 없애는 근거를 검토로 증명 |
| M-94 | 좌석 | 반복 4 잠금 장애: 300/s 503 249 → 1.5건, 600/s 의도 실패 25.6 → 21.4%. 반복 3 코드의 1,500/s는 회차별 13.3·0·11.1·0%로 첫 회차(예열)에 실패 몰림 | 249 → 1.5건 ; 25.6 → 21.4% | — | 반복 4 | 로컬 측정 | docs/design/reservation/seathold-perf-loop.md:185-186,195 | — | 예열 효과 |
| M-95 | 좌석 | 반복 5(트랜잭션 밖 빠른 거절)는 되돌림: 고부하 409는 나아졌으나(정상 3,600/s 의도 실패 19.7 → 4.3%, 자리 점유 1.49~1.66 → 1.16~1.23ms) 성공 위주 L1 처리량 977 → 807 req/s(-17%), 요청 p95 158 → 194ms(+22%), 201 건수 1,332 → 1,260 | 977 → 807 req/s ; 158 → 194ms | 807/977 = 0.826(-17.4%), 194/158 = 1.23(+22.8%) (직접 계산, 원문은 -17%·+22%) | L1은 VU 100×30회 닫힌 모델 6회(회당 반복4: 822/926/1,085/930/1,013/1,085, R5: 801/768/868/674/804/929). 회당 값이 거의 겹치지 않아 편차 아님 | 로컬 측정 | docs/design/reservation/seathold-perf-loop.md:206-243 | — | 성공 요청 손해 때문에 개선을 되돌린 결정 |
| M-96 | 좌석 | 반복 5 되돌림 근거 4가지: 성공 요청 손해, 이득 구간(2,400/s 이상)이 좁음, 성공 요청이 커넥션을 두 번 빌려 배경 작업과 겹치면 1초 초과 위험, 판정 규칙이 엔티티와 원시 SQL 두 곳에 생김 | 2,400/s 이상에서만 차이 | — | 실험 커밋 `3fe24936` 보존, 되돌림 `b303b078` | 판단 | docs/design/reservation/seathold-perf-loop.md:238-243 | — | 비용이 이득을 넘는 최적화는 버림 |
| M-97 | 좌석 | 처음과 끝(C vs R4, 같은 옵션): 정상 1,800/s 의도 실패 24.2 → 0.0%, 요청 p95 68 → 7.5ms, 2,000/s 30.7 → 0.0%, 잠금 장애 300/s 503 177 → 1.5건·p95 205 → 114ms, 600/s 의도 실패 29.1 → 21.4%, 자리 점유 3.5~3.7 → 1.3~1.5ms, 처리 한계 약 1,100 → 2,700~3,000/s(계산) | 위 값 | p95 68/7.5 = 약 9.1배 ; 처리 한계 2,850/1,100 = 약 2.6배 (직접 계산, 중간값 근사) | 다른 날 측정, 옵션 동일(입장 토큰, 정리 지연 1ms, 재시도 모사, 커넥션 탐침, 상한 4·대기 64) | 로컬 측정(계산 포함) | docs/design/reservation/seathold-perf-loop.md:257-267 | — | 5회 반복의 최종 전후 비교표 |
| M-98 | 좌석 | 모든 반복에서 중복 판매·좌석/원장 불일치·유령 좌석 0, 정합성 관문(전체 테스트 509건, 동시성 C1~C7, 지운 회차 404) 매번 통과 | 0, 509건 | — | 반복 1~5 | 로컬 측정 | docs/design/reservation/seathold-perf-loop.md:57,107,164,203,231,269 | — | 정합성은 타협하지 않음 |
| M-99 | 좌석 | LOOP 측정 환경: 한 PC(32코어)에서 Spring Boot 앱과 Docker PostgreSQL에 HTTP로 부하, 풀 5, 커넥션 대기 1초, Tomcat 작업 스레드 200. 같은 코드도 측정마다 15%까지 흔들림 | 풀 5, 1초, 200스레드, 15% 편차 | — | `SeatHoldLoadTest`, 열린 모델 | 로컬 측정(조건) | docs/design/reservation/seathold-perf-loop.md:19 | — | 측정 신뢰도 단서 |
| M-100 | 좌석 | 동시 처리 상한 기본 설정: 자리 4개, 자리 대기 최대 200ms, 대기 인원 상한 `seat-hold.max-concurrent-queue` 기본 64. 코드 기본값은 끔이며 배포 측정에서 켤 값은 config-repo 공유 설정이라 사용자 확인 뒤 적용 | 자리 4, 200ms, 대기 64 | — | ADR 0062, 반복 1 | 설계 | docs/design/reservation/seathold-perf-loop.md:24,29,275 | — | 상한의 설계 숫자 |
| M-101 | 좌석 | 남은 일: 잠금 장애 600/s의 21%(잠금 시한 50ms가 ADR 0061 결정이라 이번 루프에서 못 바꿈), 풀 크기(ISE-237), 캐시 사전 거절(ISE-281, 409 경로 0번으로) | 21% | — | 후속 | 설계 | docs/design/reservation/seathold-perf-loop.md:271-276 | — | 다음 이야기 예고 |
| M-102 | 좌석 | 구 기기 스트레스 한계 표: P0 256MB Serial 오류 0 최대 1명/초(조회 p95/선점 p95 597/89ms), P0' 768MB 5명(347/55ms), P0'' 768MB G1 10명(660/285ms)(조회 p95 500ms 이하 속도는 괄호: 1·5·5) | 1 → 5 → 10명/초 | — | 구 기기, 단계별로 속도마다 따로 실행(워밍업 없음, 최대 80명) | 로컬 측정 | docs/design/reservation/k6-seathold-bottleneck-plan.md:277-281 | — | 구 기기 예비 측정(새 기기 값과 섞지 말 것) |
| M-103 | 좌석 | P1 풀 확대 순서를 P4 뒤로: 요청당 비용을 줄이기 전에 풀을 늘리면 동시 조회가 늘어 GC가 다시 병목이 됨(5-3). 인스턴스 수×풀 크기가 PostgreSQL `max_connections`(기본 100)를 넘으면 안 됨 | 풀 5 → 10·20·40 후보 ; max_connections 기본 100 | — | 설계 판단 | 설계 | docs/design/reservation/k6-seathold-bottleneck-plan.md:194,247 | — | 순서 결정의 이유 |
| M-104 | 좌석 | 좌석 조회 선택지 TTL: 1초(SA 9-4)를 기본으로 0초(P4b)와 비교, 3초는 1초의 409 비율이 0초보다 거의 늘지 않았을 때만 | 1초 vs 0초 vs 3초 | — | 설계 | 설계 | docs/design/reservation/k6-seathold-bottleneck-plan.md:250 | — | TTL 선택 근거 |
| M-105 | 좌석 | 측정 중 만료 스케줄러를 끈 이유: 선점 유지 5분이라 첫 선점이 측정 끝 무렵(약 5분 뒤)에야 만료. 만료가 섞인 상황은 L5에서 따로 | 유지 5분 | — | `SEATHOLD_EXPIRY_ENABLED=false` | 설계 | docs/design/reservation/k6-seathold-bottleneck-plan.md:144,371 | — | 측정 조건 통제 |
| M-106 | 팀 | 판단 기록 3건이 발표 사례로 선정: k6 단계를 기본 코드부터, 측정에서만 Gateway 차단기 완화, K1을 P4c에서 마치고 P4d 생략(근거가 k6 상한 때문에 틀렸던 것을 나중에 정정) | 3건 | — | kwonwoo 판단 기록 | 판단 | docs/design/reservation/k6-seathold-story.md:221-225 ; docs/design/reservation/k6-seathold-bottleneck-plan.md:201 | — | AI 검증 로그 후보(경로: docs/ai-judgments/kwonwoo/2026-11-06-k6-steps-from-unmodified-base.md 등) |
| N-01 | 좌석 | 동시성 결함 TS-2·TS-3·TS-4는 기준선에서 재현됐고 고친 뒤 0이 됐다. TS-1(역순 데드락)은 기준선에서도 재현되지 않았다 | C2 데드락 0 (S0~S4 전 단계) | — | ISE-79, 로컬 Docker, 기준 커밋 `66f9968` 위 미커밋 변경(브랜치 `experiment/ISE-79-concurrency-measure`), 2026-11-03 | 로컬 측정 | docs/design/reservation/concurrency-results.md:8, :43 | — | "가설대로 재현 안 된 것도 결과다" |
| N-02 | 좌석 | 같은 좌석 100명 동시 선점(C1)은 모든 단계에서 1명만 성공, 중복 판매 0 | 성공 3 / 409 297 / 중복 판매 0 (3라운드×100요청, S0~S4 동일) | — | 로컬, 라운드 3 | 로컬 측정 | docs/design/reservation/concurrency-results.md:42; concurrency-results-raw.md:10 | — | "기준선에서도 통과: 회귀 기준일 뿐" (R7) |
| N-03 | 좌석 | C3: 좌석 잠금 2초 보유 중 5명 요청, S3(lock_timeout)에서 성공 0·전원 409로 바뀌고 p50이 약 2,000ms에서 약 560ms로 줄었다 | p50 2,034ms(S0) → 564ms(S3), 최댓값 2,215ms → 1,060ms | p50 564/2034=0.277 (−72.3%, 직접 계산) | `holdLockMillis=2000`, 5명×3라운드, `lock_timeout` 500ms | 로컬 측정 | docs/design/reservation/concurrency-results.md:44; concurrency-results-raw.md:12, :48 | — | "잠금 대기 2초 → 빠른 실패" |
| N-04 | 좌석 | C3 S3·S4 최댓값이 1,060~1,305ms로 테스트 목표(1,000ms 미만)를 넘었다 | 최대 1,060ms(S3), 1,305ms(S4); 다른 실행 1,238/537/1,053ms | — | 목표 최대 1,000ms 미만 | 로컬 측정 | docs/design/reservation/concurrency-results.md:53, :88; concurrency-results-raw.md:48, :60 | — | "목표가 틀렸다: lock_timeout은 시도마다 걸린다" |
| N-05 | 좌석 | `lock_timeout`은 잠금 시도마다 따로 걸려(튜플 잠금, 행을 쥔 트랜잭션) 한 문장이 약 2배(약 1초)까지 기다릴 수 있다. `pg_locks`로 직접 확인은 못 함(해석) | 500ms → 실제 상한 약 1,000ms | 약 2배 (원문) | PostgreSQL 문서 인용 해석, 같은 행을 여럿이 기다릴 때 | 판단(해석, 확인 못 함 명시) | docs/design/reservation/concurrency-results.md:88 | — | "설정값 500ms의 의미가 뒤집힌 순간" |
| N-06 | 좌석 | C3 목표를 1,000ms 미만에서 1,500ms 미만으로 고침(2026-11-06). 커밋 `5b2a9b4`~`c307621` 뒤 재실행에서 C3만 최대 1,099ms로 실패 | 목표 1,000ms → 1,500ms; 실측 최대 1,099ms; 기준선(S0~S2) 최대 2,167~2,215ms | 1,500 = 1,000(시도 2회) + 500(흔들림 여유) | (가) 안 채택, (나) `statement_timeout` 병용 안 비채택(느린 정상 쿼리까지 끊음) | 판단 | docs/design/reservation/concurrency-results.md:92-96, :103 | — | "목표를 고친 거절 안과 채택 안" |
| N-07 | 좌석 | C4: 같은 사용자 4탭 동시 선점에서 기준선은 한도 초과가 매 라운드(30/30) 발생, 최대 8매. S1(조건부 UPDATE) 뒤 0, 최대 4매 | 한도 초과 라운드 30 → 0 / 최대 보유 8매 → 4매 | 30/30 → 0/30 | 30라운드×4요청, 매수 한도 4(로 보이나 표는 최대 4매 표기) | 로컬 측정 | docs/design/reservation/concurrency-results.md:45, :29; concurrency-results-raw.md:13, :25 | — | "읽고 검사한 뒤 더하기 → 조건부 UPDATE" |
| N-08 | 좌석 | C5: 같은 멱등키 3연타에서 기준선은 자기 요청 409(201 30 / 409 60), S2에서 201 90 / 409 0. 증상은 500이 아니라 자기 요청 409(검토 R2와 일치) | 201 30 → 90, 자기 409 60 → 0 (30라운드×3) | — | 멱등키 REQUESTED 선기록(S2) | 로컬 측정 | docs/design/reservation/concurrency-results.md:46, :52; concurrency-results-raw.md:14, :38 | — | "이슈가 말한 500이 아니라 자기 요청 409" |
| N-09 | 좌석 | C6: 만료 100건을 인스턴스 2개가 처리할 때 헛수고 100 → 0(S4, SKIP LOCKED), 소요 1,191ms → 733ms | 헛수고 100 → 0; 소요 S0 1,191ms / S1 1,300 / S2 1,420 / S3 1,366 / S4 733ms | 733/1191=0.615 (−38.4%, 직접 계산) | 1라운드, 인스턴스 2개, 100건 | 로컬 측정 | docs/design/reservation/concurrency-results.md:47; concurrency-results-raw.md:15, :27, :39, :51, :63 | — | "헛수고 100 → 0" |
| N-10 | 좌석 | C7(만료와 같은 사용자 선점)·C2: 모든 단계 데드락 0, 불변식 위반 0 | 데드락 0 / 불변식 위반 0 (30라운드×3) | — | S0~S4 | 로컬 측정 | docs/design/reservation/concurrency-results.md:43, :48, :52 | — | "예상이 맞았던 설계 근거(AVAILABLE 조건)" |
| N-11 | 좌석 | 처리량 병목은 DB 잠금이 아니라 커넥션 풀이었다. 풀 5 → 20에서 L3 VU 400 처리량이 약 2배 | 453 req/s → 869 req/s (S0, L3 VU 400); S4: 327 → 821 req/s | S0: 869/453=1.918배; S4: 821/327=2.51배 (직접 계산); 원문 "약 2배" | 단일 실행, 로컬, SpringBootTest, 부하 생성기·대상 같은 JVM | 로컬 측정 | docs/design/reservation/concurrency-results.md:9, :59-62 | — | "병목은 잠금이 아니라 풀 5개" |
| N-12 | 좌석 | 풀 5에서는 VU 50부터 처리량이 멈췄고 커넥션 대기 스레드가 최대 196개(Tomcat 스레드 거의 전부) | S0 446~478 req/s, S4 273~327 req/s; 대기 최대 196 | — | 풀 5, VU 50~800 | 로컬 측정 | docs/design/reservation/concurrency-results.md:66; concurrency-results-raw.md:75-79, :101-105 | — | — |
| N-13 | 좌석 | 측정 중 `pg_stat_activity` 잠금 대기 세션은 모든 실행에서 최대 0~6개. 중복 판매·좌석 불일치는 모든 실행 0 | 잠금 대기 0~6개; doubleSold 0 | — | 핫 좌석 L2·L6 포함 | 로컬 측정 | docs/design/reservation/concurrency-results.md:67-68 | — | — |
| N-14 | 좌석 | S4 풀 5에서 L3 VU 800은 TIMEOUT 3,744건(전체 6,437) 발생 | TIMEOUT 3,744 / 6,437 | 3,744/6,437=58.2% (직접 계산) | 풀 5, S4, VU 800, 요청 타임아웃 3초 | 로컬 측정 | docs/design/reservation/concurrency-results.md:63; concurrency-results-raw.md:105 | — | "풀 5가 무너지는 순간" |
| N-15 | 좌석 | 고친 코드는 DB 왕복이 늘어 처리량이 줄었다. VU 400 처리량 중앙값 S0 1,125 → S4 648 req/s. 같은 단계 안 편차가 커서 크기는 단정하지 않는다 | 1,125 → 648 req/s (S0 515~1,159 범위) | 648/1125=0.576 (−42.4%, 직접 계산) | 풀 20, 3회 반복, 회마다 단계 순서 바꿈 | 로컬 측정 | docs/design/reservation/concurrency-results.md:10, :74-80 | — | "정합성을 얻고 처리량을 냈다" |
| N-16 | 좌석 | 단계별 L3 VU 400 처리량 중앙값(min~max) | S0 1,125(515~1,159) / S1 892(592~1,276) / S2 759(722~1,142) / S3 669(617~802) / S4 648(606~942) req/s | — | 풀 20, 3회 중앙값 | 로컬 측정 | docs/design/reservation/concurrency-results.md:76-80 | — | — |
| N-17 | 좌석 | 단계별 L3 VU 100 처리량 중앙값 | S0 607(277~764) / S1 648(365~725) / S2 482(429~631) / S3 411(298~440) / S4 356(346~557) req/s | — | 풀 20, 3회 | 로컬 측정 | docs/design/reservation/concurrency-results.md:76-80 | — | — |
| N-18 | 좌석 | 단계별 L3 VU 400 p95 중앙값 / L6(VU 1,000 1초) 409 p95 | S0 615ms / S1 655 / S2 816 / S3 961 / S4 848ms; L6 409 p95 S0 661 / S1 659 / S2 824 / S3 1,147 / S4 741ms | — | 풀 20, 3회 | 로컬 측정 | docs/design/reservation/concurrency-results.md:76-80 | — | — |
| N-19 | 좌석 | 같은 S0도 VU 400 처리량이 515~1,159 req/s로 2배 넘게 흔들림. 10~20% 차이는 이 측정으로 가릴 수 없다 | 515 ~ 1,159 req/s | 1159/515=2.25배 (직접 계산) | 같은 장비에서 부하 생성기·앱·Docker가 CPU 공유 | 로컬 측정(한계) | docs/design/reservation/concurrency-results.md:84 | — | "측정이 흔들릴 때의 정직" |
| N-20 | 좌석 | 처리량이 줄어드는 원인 가설: 요청당 DB 왕복이 S0보다 3번 늘었다(S1 매수 INSERT, S2 선점 결과 UPDATE, S3 set_config SELECT). 구간별 시간은 미확인 | 왕복 +3회 | — | Zipkin·쿼리 로그로 아직 나누지 않음 | 판단(가설, 미확인 명시) | docs/design/reservation/concurrency-results.md:83 | — | — |
| N-21 | 좌석 | S4 변경 후 `reservation-service` 전체 테스트 263건 통과, 실패 0, 건너뜀 7건(환경변수로만 켜는 동시성 시나리오) | 263 통과 / 0 실패 / 7 건너뜀 | — | `./gradlew :reservation-service:test --rerun` | 로컬 측정 | docs/design/reservation/concurrency-results.md:34 | — | — |
| N-22 | 좌석 | 측정 환경: i7-11800H(8코어 16스레드), RAM 15.7GB, Docker VM CPU 16·메모리 약 7.6GiB, postgres:17-alpine(tmpfs), Tomcat 스레드 200, Hikari 5/20, 요청 타임아웃 3초 | — | — | JDK 21, SpringBootTest, 부하 생성기와 대상이 같은 JVM, Gateway·인증 안 거침 | 로컬 측정 | docs/design/reservation/concurrency-results.md:14-23 | — | — |
| N-23 | 좌석 | 부하 원자료: S0 풀 20에서 L3 VU 50 916 req/s, VU 100 939, VU 200 874, VU 400 869, VU 800 833 (p95 86/177/362/651/1,320ms) | 위 값 | — | 단일 실행, 풀 20, S0 | 로컬 측정 | docs/design/reservation/concurrency-results-raw.md:88-92 | — | — |
| N-24 | 좌석 | L2(VU 200 핫 좌석) 처리량 S0 풀 5 586 req/s → 풀 20 890 req/s; 요청 18,343 → 27,768 | 586 → 890 req/s | 890/586=1.52배 (직접 계산) | 단일 실행, L2, 201 980/994 | 로컬 측정 | docs/design/reservation/concurrency-results-raw.md:74, :87 | — | — |
| N-25 | 좌석 | L6(VU 1,000 / 1초 / 1좌석 핫) 풀 5 → 20: S0 처리량 329 → 708 req/s, 409 p95 1,980 → 440ms | 329 → 708 req/s; 1,980 → 440ms | 708/329=2.15배; p95 −77.8% (직접 계산) | 단일 실행, 201=10, 409=990 | 로컬 측정 | docs/design/reservation/concurrency-results-raw.md:73, :86 | — | — |
| N-26 | 좌석 | 반복 측정 L6 S0 rep 1~3 처리량 352 / 945 / 612 req/s로 3배 가까이 흔들림 | 352, 945, 612 req/s; 409 p95 1,441 / 176 / 661ms | 945/352=2.68배 (직접 계산) | 풀 20, 회마다 단계 순서 변경 | 로컬 측정 | docs/design/reservation/concurrency-results-raw.md:137, :164, :170 | — | — |
| N-27 | 좌석 | 설계 결정: 만료는 건별 트랜잭션 안에서 `FOR UPDATE SKIP LOCKED LIMIT 1`로 한 건씩 집는다. ID만 SKIP LOCKED로 뽑으면 커밋과 함께 잠금이 풀려 중복 집기를 막지 못한다(SA 8-4 문장과 다름, ADR로 남김) | — | — | 검토 R3 | 설계 | docs/design/reservation/concurrency-review.md:39-43; concurrency-results.md:32 | — | "SA 문서의 처방이 틀렸음을 코드로 확인" |
| N-28 | 좌석 | 이슈 전제 검토 결론: 네 항목 중 TS-2만 이슈대로 재현, TS-1·3·4는 증상·해결안 수정 필요(실행 전 가설이며 Docker 미기동 상태에서 코드만 읽고 세움) | 8개 검토 항목 R1~R8 | — | 2026-11-03 | 판단(가설) | docs/design/reservation/concurrency-review.md:5, :9-18 | — | "측정 전에 코드로 예상 → 실측으로 7/7 적중" |
| N-29 | 좌석 | 시나리오 예상 7개가 2026-11-03 실측에서 모두 예상대로 나왔다 | 7/7 | — | C1~C7 | 로컬 측정 | docs/design/reservation/concurrency-scenarios.md:87 | — | — |
| N-30 | 좌석 | 설계 순서 원칙: 한 단계에 변경 하나, 단계 전후 C1~C7 전체 실행, 단언은 "고친 뒤 목표"로 둬 실패한 단언이 곧 재현 기록 | S0~S5 6단계 | — | — | 설계 | docs/design/reservation/concurrency-scenarios.md:10-34, :45 | — | "한 번에 하나만 바꿔야 어느 변경이 수치를 바꿨는지 안다" |
| N-31 | 좌석 | 회수 경로 공통 잠금 순서 제안: 예매(조건부 전이) → 회차 좌석 오름차순 → 매수. SA 8-4 순서(매수→좌석→예매)를 만료에 적용하면 교착 위험(R4) | — | — | 결제 결과 소비자 담당과 contract 이슈로 합의 필요 | 설계 | docs/design/reservation/concurrency-review.md:45-49 | — | — |
| N-32 | 좌석 | 만료 스케줄러(ISE-13): 5초마다(fixedDelay) 기한 지난 HELD 예매를 최대 100건 조회, 건별 트랜잭션으로 예매 HELD→EXPIRED → 예매좌석 RELEASED → 회차좌석 AVAILABLE → 매수 차감 → 이력 | 주기 5s, 배치 100 | — | 설정 `seathold.expiry.enabled/interval/batch-size`, 기본값 코드에 둠 | 설계 | docs/design/reservation/hold-expiry.md:10-29 | — | — |
| N-33 | 좌석 | 만료 테스트 신규 5건 통과, 스케줄러를 500ms 주기로 켠 임시 테스트에서 백그라운드 스레드가 EXPIRED로 변경 확인 | 신규 테스트 5건 | — | 2026-11-02 | 로컬 측정 | docs/design/reservation/hold-expiry.md:32-33 | — | — |
| N-34 | 좌석 | 추첨 예매 만료 연동(ISE-149): 첫 만료 때 선점을 1회 30분 연장(상한 추첨 후 60분), 연장 뒤 만료면 `onExpired` 호출 | 연장 30분 / 상한 60분 | — | 2026-11-02 ISE-149 반영 | 설계 | docs/design/reservation/hold-expiry.md:43 | — | "추첨 예매는 만료 시 한 번 더 30분" |
| N-35 | 좌석 | 반환 코드 위치 3안(A concert로 이동 / B reservation 공용 컴포넌트 / C 그대로). 담당 의견 B(ADR 0034 단방향 의존과 맞음) | 3안 | — | ISE-13 논의 | 판단(제안) | docs/design/reservation/hold-expiry.md:47-55 | — | — |
| N-36 | 좌석 | 원장 불일치 처리: 회차 좌석이 HELD 아니거나 보유 매수가 모자라면 롤백하지 않고 WARN만 남김(롤백하면 매 주기 같은 실패 반복). 2026-11-09 대사가 원장 기준으로 고침 | — | — | 판매 기준은 `reservation_seats`(D2) | 설계 | docs/design/reservation/hold-expiry.md:44 | — | — |
| N-37 | 좌석 | 접수형 재측정 계획: 동기형(S)·직접 발행(D)·Outbox(O) 세 방식을 같은 환경에서 비교. 상태 "구현 끝, 측정 전", 결과 절 "측정 전이다" | 3방식 × 3시나리오 | — | 2026-11-15 작성, 11-16 구현 | 설계 | docs/design/reservation/async-seathold-remeasure-plan.md:3, :24-32, :75, :138 | — | — |
| N-38 | 좌석 | 직접 발행 결정(2026-11-16 사용자 결정): 커밋 뒤 전송, 브로커 확인(acks=all) 최대 1초 대기, 실패해도 202, 실패는 `seathold_direct_publish_total{result="failed"}`로 세고 30초 시한 정리가 TIMEOUT으로 닫음 | 확인 대기 1초, 시한 30초 | — | 기본값은 Outbox, 측정용 덮어쓰기에서만 켬 | 판단 | docs/design/reservation/async-seathold-remeasure-plan.md:39-47 | — | "커밋 전 전송 안 고른 이유" |
| N-39 | 좌석 | Outbox O 경로: 같은 `message_key`의 PENDING을 한 번에 한 건만 보내 몰린 구역은 초당 약 2건이 상한으로 추정 | 초당 약 2건 (추정) | 500ms 간격 → 2건/s (직접 계산 정합) | `messaging.outbox.interval` 500ms, 배치 20; 코드 기준 미측정 | 계산값(추정, 미측정 명시) | docs/design/reservation/async-seathold-remeasure-plan.md:30, :91, :128 | — | — |
| N-40 | 좌석 | 소비자 기본값: 동시성 2, 파티션 6, 잠금 시한 100ms; 시한 30초; SA 9-4 목표 접수→처리 시작 2초 이내 | 2 / 6 / 100ms / 30s / 2s | — | 측정 환경 기록표 | 설계 | docs/design/reservation/async-seathold-remeasure-plan.md:89-90, :127 | — | — |
| N-41 | 좌석 | k6 접수형 분기는 모의 서버로 k6 1.0.0을 짧게 돌려 확인했을 뿐 실제 서버로는 돌리지 않음 | — | — | 2026-11-16 | 로컬 측정(제한적) | docs/design/reservation/async-seathold-remeasure-plan.md:64 | — | — |
| N-42 | 좌석 | 측정 회 유효 판정: k6_exit 137 아님, Prometheus 표본 간격 15초 이내, dropped_iterations 적음, 대사 위반 0 | 간격 15초 | — | ISE-79 측정과 같은 기준 | 설계 | docs/design/reservation/async-seathold-remeasure-plan.md:107-113 | — | — |
| N-43 | 좌석 | 발제 기준 시나리오: 동시 100명, 1초 안 시작, 각 10회 | 100명, 1초, 각 10회 | — | SA 3장 | 설계 | docs/design/reservation/async-seathold-remeasure-plan.md:71 | — | — |
| N-44 | 좌석 | 풀 크기: Gateway 우회 K1-H 800명(서버 선점 초당 약 2,200건)에서 풀 5는 커넥션 대기 최대 40건·p99 33ms, 풀 10은 대기 0·p99 6ms·서버 선점 평균 3.7 → 2.0ms, 풀 20·40은 10과 같음 | 대기 40 → 0; p99 33 → 6ms; 선점 평균 3.7 → 2.0ms; 동시 사용 커넥션 최대 5~8개 | p99 6/33=0.18 (−81.8%); 평균 2.0/3.7=0.54 (−45.9%) (직접 계산) | k6 6GiB, 풀 5·10·20·40 비교 1회씩, 만료 스케줄러 끔 | 로컬 측정 | docs/design/reservation/k6-contract-proposals.md:82 | — | "풀 5 → 10에서 멈춘 이유" |
| N-45 | 좌석 | 풀 5→10 제안(D): 인스턴스 수 × 풀이 PostgreSQL `max_connections`(기본 100)을 넘으면 안 됨; ISE-237로 합쳐짐 | 풀 5 → 10 | 2배 | reservation 전용 DB 연결 5개 늘어남 | 판단(제안) | docs/design/reservation/k6-contract-proposals.md:14, :79-84 | — | — |
| N-46 | Gateway | auth-redis 차단기: 공유 기준(느린 호출 50ms·비율 50%, 실패율 50%, 판단 범위 20번)에서 K1-H 초당 100명부터 Gateway CPU 상승만으로 느린 호출 비율 90~100%가 되어 열림, Redis CPU 10% 미만 | 느린 호출 비율 90~100%; Redis CPU <10% | — | 2026-11-07 이전 기기, 선점만 보내는 K1-H 초당 100명 | 로컬 측정 | docs/design/reservation/k6-contract-proposals.md:25 | — | "병은 Redis가 아니라 CPU였다" |
| N-47 | Gateway | 시한 초과 초당 1건 안팎이 몰려 나와 최근 20번 중 절반을 채워 열렸고 정상 요청까지 초당 300~750건 거절 | 거절 초당 300~750건 | — | 실패율 50%·범위 20번 | 로컬 측정 | docs/design/reservation/k6-contract-proposals.md:26 | — | — |
| N-48 | Gateway | 통과 요청이 초당 약 600건을 넘으면 Redis 확인이 느려짐: 평균 0.5ms에서 8~13ms, 시한 초과(100ms) 초당 5~45건. Gateway GC를 G1으로 바꿔도 같음 | 0.5ms → 8~13ms; 시한 초과 초당 5~45건 | 8/0.5=16배 ~ 13/0.5=26배 (직접 계산) | 통과 요청 초당 600건 초과 | 로컬 측정 | docs/design/reservation/k6-contract-proposals.md:27 | — | — |
| N-49 | Gateway | 측정용으로 완화한 기준(느린 호출 100ms, 실패율 100%)에서도 K1-H 800명 단계는 6회(base 3, F 3) 모두 열려 회마다 약 4,500~18,600건 즉시 거절. K1 S 1,280명은 3회 중 1회 열림(약 12,900건). reservation-service 5xx 0 | 6/6회 열림; 거절 4,500~18,600건; 1/3회, 12,900건 | — | 2026-11-10 새 기기, k6 CPU 1.6~2.6코어 여유 | 로컬 측정 | docs/design/reservation/k6-contract-proposals.md:28 | — | "차단기는 열렸는데 서버는 멀쩡했다" |
| N-50 | Gateway | k6 메모리 상한이 낮으면 거절이 K1-H 800명에서 약 41,000건. 측정 쪽 문제로 고쳤음(TS-69) | 약 41,000건 | — | k6 메모리 상한 낮음 | 로컬 측정 | docs/design/reservation/k6-contract-proposals.md:29 | — | "측정 도구가 병목이었다(TS-69)" |
| N-51 | Gateway | base(Serial GC, 풀 5) K1-H에서 Gateway→reservation 연결 실패 500(ConnectTimeoutException, 연결 시한 1,000ms)이 회마다 41~60건, F에서는 0 | 41~60건 → 0 | — | base 대 F | 로컬 측정 | docs/design/reservation/k6-contract-proposals.md:31 | — | — |
| N-52 | Gateway | 차단기 대응은 Gateway 담당의 ADR 0050(Redis 장애만 세고 과부하는 bulkhead가 거절)으로 정리, 팀 회의 확정 예정. 이슈 초안은 올리지 않음 | — | — | 2026-11-12 | 판단 | docs/design/reservation/k6-contract-proposals.md:3, :20 | — | "관찰을 넘기고 값은 담당이 정함" |
| N-53 | 인프라 | reservation-service GC를 G1으로 명시(B): 컨테이너 1,152MB라 JVM이 Serial 선택(기준 약 1,792MB 미만). dev `compose.deploy.yml`에 이미 반영(ISE-193, 앱 8개 모두 `-XX:+UseG1GC`, `-Xms384m -Xmx768m`) | Serial → G1 | — | 2026-11-10 dev 확인 | 설계(반영됨) | docs/design/reservation/k6-contract-proposals.md:63-65 | — | — |
| N-54 | 좌석 | G1 측정: 새 기기 base K1 L에서 GC 정지가 부하 시간의 약 75%, G1만 켜자 7.3%, S 버틴 단계 10명 → 20명. 이전 기기 정지 66~69% → 24.5% | 75% → 7.3%; 이전 기기 66~69% → 24.5%; S 버틴 10 → 20명 | 7.3/75=0.097 (−90.3%, 직접 계산); S 2배 | 새 기기 base K1 L(초당 100명), G1만 변경 | 로컬 측정 | docs/design/reservation/k6-contract-proposals.md:66 | — | "GC 정지가 부하의 75%였다" |
| N-55 | 좌석 | G1 대가: 같은 힙 768MB에서 R1 S 320명 단계에 `Evacuation Failure` 발생. P4a 이후 GC 정지 1~3% | 정지 1~3% (P4a 이후) | — | 힙 768MB | 로컬 측정 | docs/design/reservation/k6-contract-proposals.md:67 | — | — |
| N-56 | 좌석 | Hikari 커넥션 대기 30초 → 1초(R1/C): G1만 켠 S에서 커넥션 대기 197건이 부하 뒤에도 남아 회복 탐침 전부 504, R1 켜자 부하 뒤 40초에 회복, L의 서버 좌석 조회 처리량 초당 13.5 → 41.0건. 이전 기기에서는 처리량이 줄었음(확정하지 않음) | 30s → 1s; 대기 197 → 40초 안 회복; 13.5 → 41.0 req/s | 41.0/13.5=3.04배 (직접 계산) | 새 기기, 설정 PR #529(ISE-196, `connection-timeout: 1000`) 2026-11-12, 확인 마감 11/10 이견 없음 | 로컬 측정 | docs/design/reservation/k6-contract-proposals.md:73-75 | — | "30초 → 1초" |
| N-57 | 좌석 | 커넥션 대기 1초의 대가: 몰릴 때 500이 빨리 나옴. Gateway 응답 시한(3초)보다 서버가 먼저 포기해야 쌓인 일이 남지 않음 | 서버 1s < Gateway 3s | — | — | 설계 | docs/design/reservation/k6-contract-proposals.md:75 | — | — |
| N-58 | 좌석 | B8: K1 초당 100명 5분 부하 후 서비스가 10분 넘게 응답 못해 재시작해야만 돌아왔다 | P0 10분 넘게 전체 503; P0' 3분 넘게 무응답(메모리 1.115GiB/상한 1.125GiB, CPU 170%); P0'' 6분 뒤에도 스레드 200개 전부 바쁨 | — | P0 2026-11-04(힙 256MB Serial), P0'/P0'' 2026-11-05(힙 768MB Serial/G1) | 로컬 측정 | docs/design/reservation/k6-overload-recovery.md:5, :19-25 | — | "메모리를 3배로 늘려도 GC를 바꿔도 회복하지 못했다" |
| N-59 | 좌석 | B8 근본 원인: Gateway는 3초에 포기하지만 서버는 모르고 끝까지 처리. 서버가 끝낸 좌석 조회 200은 5,015건, k6가 받은 200은 19건 | 5,015건 대 19건; 서버 한 일의 99.6%가 버려진 요청 | 19/5,015=0.38% → 99.6% 버려짐 (원문 99.6%) | P0'' 2026-11-05 03:04:12~03:09:12 UTC, 요청 29,994건(조회 29,976·선점 18) | 로컬 측정 | docs/design/reservation/k6-overload-recovery.md:12-13, :50, :73 | ASCII 요청 경로 도식(R=k6-overload-recovery.md:30-34), 원인 사슬 도식(:98-105) | "서버가 한 일의 99.6%는 아무도 기다리지 않는 요청" |
| N-60 | 좌석 | 요청 스레드 200개가 커넥션 5개를 기다림: 처리 중 요청 200, 커넥션 사용 5, 대기 197, JVM 스레드 252(→253) | 200 / 5 / 197 / 252; 부하 끝 6분 뒤에도 동일 | — | P0'', Tomcat 스레드 상한 200 | 로컬 측정 | docs/design/reservation/k6-overload-recovery.md:54-61 | — | — |
| N-61 | 좌석 | 리틀의 법칙: 200건 ÷ 초당 약 14건 ≈ 14초, 실제 서버 안 평균 지연 14.4초와 일치. 대기 시한 초과는 5분간 23건뿐 | 14초 vs 14.4초; 서버 200 초당 9.4~18.7건(평균 약 14건) | 200/14=14.3 (원문 ≈14초) | P0'' | 계산값 + 로컬 측정 | docs/design/reservation/k6-overload-recovery.md:65-67 | — | "리틀의 법칙으로 서버 안 지연을 맞춤" |
| N-62 | 좌석 | 504 8,717건이 Tomcat 연결 상한 8,192와 비슷하다는 추정이 R0 재측정에서 MBean으로 맞다고 확인(부하 중 정확히 8,192에서 멈춤) | 8,717 vs 8,192 | — | 0~2분 504 중앙값 3,010ms; 2~5분 500 15,786건 중앙값 1,008ms | 로컬 측정 | docs/design/reservation/k6-overload-recovery.md:79-86 | — | — |
| N-63 | 좌석 | 쌓인 일 비우는 시간: 약 8,400건 ÷ 초당 약 14건 ≈ 10분 | 약 10분 (계산) | 8,400/14=600초 (직접 계산) | 연결 8,192+스레드 200 | 계산값 | docs/design/reservation/k6-overload-recovery.md:91-94 | — | — |
| N-64 | 좌석 | 설정 어긋남: Gateway 응답 시한 3s·연결 시한 1,000ms vs Tomcat 연결 상한 8,192·accept-count 100·스레드 200·Hikari 대기 30,000ms·풀 5 | — | — | 기본값 대부분 | 설계(현황) | docs/design/reservation/k6-overload-recovery.md:36-46 | ASCII 표(:30-34) | "바깥은 3초에 포기, 안쪽은 30초까지 기다림" |
| N-65 | 좌석 | R1(커넥션 대기 1초) 대응 후: 사용자가 받은 좌석 조회 200이 7건 → 1,463건, 서버 200 중 사용자에게 간 비율 0.1% → 95%, 부하 끝난 뒤 Tomcat 연결 8,192(340초 뒤 4,344) → 17→5, 회복 탐침 31건 504 → 전부 200 | 7 → 1,463건; 0.1% → 95%; 선점 201 0 → 106 | 1463/7=209배 (직접 계산) | local 2026-11-05, 각 1회, 이미지 `534187e`, 힙 768MB G1, 새 회차 15,000석 | 로컬 측정 | docs/design/reservation/k6-overload-recovery.md:15, :176-190 | — | "한 줄 설정(30s → 1s)이 회복을 풀었다" |
| N-66 | 좌석 | R0 대응 전 부하 중: 504 11,081·500 10,924·503 7,548, Tomcat 연결 8,192 최대, 바쁜 스레드 200/200, 커넥션 대기 197, 서버 안 3초 초과 99.5% | 위 값 | — | 같은 이미지 R0 | 로컬 측정 | docs/design/reservation/k6-overload-recovery.md:180-188 | — | — |
| N-67 | 좌석 | R1+R2+R3 시작값(스레드 50·연결 200·OS 대기열 50)은 R1보다 나빴다: 사용자 200이 1,463 → 172건, 504 874 → 11,721건 | 1,463 → 172건; 874 → 11,721건 | 172/1463=0.118 (−88.2%); 504 13.4배 (직접 계산) | 연결 200 중 스레드 못 받은 150개가 Tomcat 안에서 기다리고 서버 속도 초당 약 10건 → 줄 비우는 데 15초쯤 > Gateway 3초 | 로컬 측정 | docs/design/reservation/k6-overload-recovery.md:178-180, :211-214 | — | "시작값이 오히려 나빴다: 리틀의 법칙으로 다시 계산" |
| N-68 | 좌석 | 리틀의 법칙 재계산: 3초 × 초당 10건 = 연결 약 30개 | 연결 30 (3×10) | 3×10=30 | R3 재정의: 연결 30·OS 대기열 10 (`compose.b8-r13.yml`) | 계산값 | docs/design/reservation/k6-overload-recovery.md:213, :219 | — | — |
| N-69 | 좌석 | R3(30) 반복 3회: 경합 큰 1·2회차에서 사용자 좌석 조회 200이 R1 약 1,620건 대 약 2,400건(약 1.5배), 경합 작은 3회차에서는 3,432 대 3,535건(3%). 연결 30에서 고정 | R1 1,463·1,782·3,432 vs R3(30) 2,397·2,407·3,535 | 2,400/1,620≈1.5배(원문); 3,535/3,432=1.03 (+3.0%, 직접 계산) | 3회차는 노트북 메모리 해제 후 재실행(경합 작은 환경), 회복 관찰 120초 | 로컬 측정 | docs/design/reservation/k6-overload-recovery.md:219-228, :245 | — | "경합이 크면 효과 있고 작으면 3%" |
| N-70 | 좌석 | R3(30)은 빠른 실패를 오히려 늦췄다: 오류 p95 1,740/1,727/1,862ms(R1은 1,737/1,273/1,069ms) | R1 3회 중 2회 기준(1.5초) 통과, R3(30) 0회 | — | 오류 p95 기준 1.5초 이하; 원인은 추정 | 로컬 측정 + 추정 | docs/design/reservation/k6-overload-recovery.md:223-228, :239, :246 | — | — |
| N-71 | 좌석 | 판정 기준 4개(회복 30초 안, 버려진 일 5% 미만, 오류 p95 1.5초, 처리량 14건 이상). R1: 회복 통과, 버려진 일 통과(2.6%), 빠른 실패 실패(1,737ms), 처리량 실패(초당 4.9건, 같은 이미지 R0 9.2건) | 회복 30초 / 5% / 1.5초 / 14건 | — | 기준은 결과를 보고 바꾸지 않는다고 사전 명시 | 설계(판정 기준) + 로컬 측정 | docs/design/reservation/k6-overload-recovery.md:150-163, :198-205 | — | "기준을 먼저 적어 두고 결과가 못 넘었음을 인정" |
| N-72 | 좌석 | 처리량 기준은 이 대응으로는 못 넘는다. 요청 한 건 비용은 P4가 줄인다. R0 9.2건 vs R1+R3(30) 8건 남짓, 사용자에게 가는 비율 0.1% → 98% | 9.2 → 8건 남짓; 0.1% → 98% | — | — | 로컬 측정 | docs/design/reservation/k6-overload-recovery.md:249 | — | — |
| N-73 | Gateway | R3(30)은 제안하지 않음. R1만 contract 이슈 ISE-196으로 제안. 판단에 쓸 수치는 배포 환경에서 다시 | 사용자 성공 R1 1,463~3,432건 편차 | 3432/1463=2.35배 (직접 계산) | 로컬 노트북 CPU·메모리 경합이 결과를 크게 흔듦 | 판단 | docs/design/reservation/k6-overload-recovery.md:253-255 | — | — |
| N-74 | Gateway | 503 출처 두 가지: Eureka 인스턴스 이탈(`No servers available`, P0, 긴 GC 정지) vs auth-redis 차단기 열림(`resilience4j_circuitbreaker_state{state="open"}`, 응답 0~5ms) | — | — | 노트북 한 대에서 k6와 전체 스택이 CPU 공유 | 로컬 측정 | docs/design/reservation/k6-overload-recovery.md:111-118 | — | "503을 출처별로 나눠 측정 잡음과 서버 문제 분리" |
| N-75 | 좌석 | 한계: 노트북 한 대(16코어, Docker Desktop 7.6GB), 각 측정 1회(대응 후는 3회 반복 시도 중 메모리 부족으로 백그라운드 실행이 멈춘 적 있음), 3회차 R1은 시작 13초 만에 메모리 부족으로 멈춤 | 16코어, 7.6GB | — | — | 로컬 측정(한계) | docs/design/reservation/k6-overload-recovery.md:219, :260-262 | — | — |
| N-76 | 좌석 | 좌석 배치 캐시 로컬 vs Redis: 매진 전 좌석 조회 p95 7.0ms(로컬) vs 38.3ms(Redis), p50 4.0 vs 17.3ms, p99 10.0 vs 131.9ms | p95 7.0 → 38.3ms | 38.3/7.0=5.47배 (직접 계산) | Gateway 경유 L(초당 100명 300초), 각 조건 1회, local Docker, 배치 1,344,751바이트(1.3MB) TTL 1시간 | 로컬 측정 | docs/design/reservation/k6-p4b-local-vs-redis.md:9-11, :33 | — | "Redis가 느린 게 아니라 1.3MB를 매번 옮긴 것" |
| N-77 | 좌석 | 좌석 조회 한 건당 reservation-service CPU 약 5.5ms(로컬) vs 약 25ms(Redis). 평균 CPU 0.99 vs 4.43코어, 최대 2.46 vs 9.47코어 | 5.5ms → 약 25ms; 0.99 → 4.43코어 | 25/5.5=4.5배; 4.43/0.99=4.47배 (직접 계산) | L | 로컬 측정 | docs/design/reservation/k6-p4b-local-vs-redis.md:12, :35, :81 | — | — |
| N-78 | 좌석 | 직접 호출 M(5~1,280명): 좌석 조회 99% 이상 버틴 단계 로컬 640명 vs Redis 160명, 서버 조회 200 최대 초당 1,244 vs 397건 | 640명 → 160명; 1,244 → 397 건/s | 397/1244=0.319 (−68.1%, 직접 계산) | 직접 호출 M, 단계마다 새 회차 | 로컬 측정 | docs/design/reservation/k6-p4b-local-vs-redis.md:13-14, :44-52 | — | — |
| N-79 | 좌석 | Gateway 경유 CPU 4코어 S(배포 서버 흉내): 99% 이상 버틴 단계 로컬 160명 vs Redis 40명; 서버 조회 200 최대 초당 643 vs 187건 | 160명 → 40명; 643 → 187 건/s | 187/643=0.291 (−70.9%, 직접 계산) | CPU 4코어 제한 S | 로컬 측정 | docs/design/reservation/k6-p4b-local-vs-redis.md:15, :56-66 | — | — |
| N-80 | 좌석 | Redis 구조에서 GC가 L에서 약 17배 잦아짐: 로컬 527회 1.5초 vs Redis 8,797회 21.8초(최대 367ms, Full 2회). 할당 초당 약 259MB vs 약 1.85GB | 527 → 8,797회; 1.5 → 21.8초; 259MB/s → 1.85GB/s | 8,797/527=16.7배 (직접 계산); 1.85GB/0.259GB≈7.1배 | L | 로컬 측정 | docs/design/reservation/k6-p4b-local-vs-redis.md:36-37, :82 | — | — |
| N-81 | 인프라 | Redis 메모리가 상한을 넘음: 직접 M에서 메모리 최고 132.68MB로 상한 128MB(`maxmemory 128mb`, `noeviction`) 초과, SET 34건 거절. 같은 Redis를 쓰는 대기열 입장·Gateway 인증 쓰기도 거절될 수 있음. 직접 M GC 로컬 1,537회 8.3초·Full 1회 vs Redis 10,203회 36.9초·Full 115회 | 132.68MB vs 한도 128MB; SET 거절 34건; Full GC 1 → 115 | 132.68/128=1.037 (직접 계산) | 직접 M | 로컬 측정 | docs/design/reservation/k6-p4b-local-vs-redis.md:24, :53-54, :84 | — | "공유 Redis의 메모리 상한이 대기열까지 위협" |
| N-82 | 좌석 | Redis 내보냄 74.1GB(초당 약 247MB). 예상과 일치(예상 초당 약 250MB) | 74.1GB; 초당 약 247MB | — | L 300초 | 로컬 측정 | docs/design/reservation/k6-p4b-local-vs-redis.md:38, :94 | — | — |
| N-83 | 좌석 | Redis 구조 원인: 요청마다 1.3MB를 받아 15,000개 레코드로 역직렬화. Redis 서버 CPU는 가장 바쁜 회에도 평균 0.16코어. 직접 M 500의 95%가 100~150ms에 끝남(공통 Redis 명령 시한 100ms) | Redis 서버 CPU 0.16코어; 시한 100ms | — | Lettuce 연결 하나 공유 | 로컬 측정 | docs/design/reservation/k6-p4b-local-vs-redis.md:17, :24, :83 | — | — |
| N-84 | 좌석 | 예상 대 실측: reservation-service CPU 예상 1.5~2배 vs 실측 약 4.5배, p95 예상 +5~15ms vs +31ms, 4코어 S 예상 한 단계 낮음 vs 두 단계(160 → 40명), 직접 M 예상 30~50% vs 68% | 예상 vs 실측 | — | "역직렬화 비용을 직렬화 수준으로 낮게 잡고 GC 증가를 빠뜨림" | 판단(예상과 실측 대조) | docs/design/reservation/k6-p4b-local-vs-redis.md:89-96 | — | "예상이 틀린 이유까지 적은 대조표" |
| N-85 | 좌석 | Redis가 앞선 것처럼 보인 지표는 앞 단계 실패로 부하가 줄어든 효과: 4코어 S 320명 선점 성공 로컬 75.0%·p95 2,526ms vs Redis 99.8%·166ms(전체 선점 52,020 vs 35,857건), 직접 M 640명 선점 p95 766 vs 69.7ms(5,358 vs 57,525건) | 위 값 | — | 같은 수의 선점 처리 비교가 아님 | 판단(착시 해석) | docs/design/reservation/k6-p4b-local-vs-redis.md:68-77 | — | "좋아 보이는 숫자의 함정" |
| N-86 | 좌석 | 판단 근거: 크고 판매 중 거의 안 바뀌는 값은 로컬 캐시, Redis는 무효화 알림(Pub/Sub)·버전 번호에만. 압축·연결 풀은 측정하지 않았으므로 수치로 말하지 않음 | — | — | 본 구현은 ISE-204(반효승) | 판단 | docs/design/reservation/k6-p4b-local-vs-redis.md:98-103 | — | — |
| N-87 | 좌석 | 한계: 각 조건 1회, local Docker. 직접 M 로컬 1,280명은 k6 메모리 상한으로 못 잼 | — | — | — | 로컬 측정(한계) | docs/design/reservation/k6-p4b-local-vs-redis.md:103 | — | — |
| N-88 | 좌석 | P4d(좌석 조회 JSON 재사용): 2026-11-09 JFR 1,280명 기준 Jackson 약 32%, 응답 만들기 약 24.5%, 조건(대기 평균 1 미만, Jackson ≥ 30%)이 모두 맞았으나 넣지 않음 | Jackson 약 32%, 응답 만들기 약 24.5% | 합 약 57%(원문 "약 57%") | JFR, 1,280명, 커넥션 대기 0 | 로컬 측정 | docs/design/reservation/k6-p4d-review.md:16, :22 | — | "조건표대로면 다음 단계였지만 안 넣은 이유" |
| N-89 | 좌석 | 2026-11-10 k6 상한 6GiB로 올리자 K1 S 1,280명 100%·p95 32ms, F 3회 중 2회 100%·1회 81.6%(차단기). reservation CPU 최대 6.2~6.7코어, Gateway 6.8~7.0, k6 7.6~7.8. "남은 한계가 부하 생성기와 Gateway"라는 전제는 k6 상한이 만든 것(TS-69) | 100%, p95 32ms; 81.6%; 6.2~6.7 / 6.8~7.0 / 7.6~7.8 코어 | — | k6 6GiB | 로컬 측정 | docs/design/reservation/k6-p4d-review.md:17 | — | "전제가 측정 도구 한계였음이 드러남" |
| N-90 | 좌석 | 좌석 조회 응답 약 830KB, 초당 1,280건이면 약 1GB/s. 15,000석 객체 생성·직렬화가 요청마다 일어남 | 약 830KB; 약 1GB/s | 830KB×1,280≈1.06GB/s (직접 계산) | P4c 뒤 | 계산값 | docs/design/reservation/k6-p4d-review.md:7, :23 | — | — |
| N-91 | 좌석 | P4d 구현(2026-11-16, ISE-264): 로컬 참고값 15,000석 약 811KB 본문 1,000번 — 객체 생성+직렬화 1,210~1,495ms vs 캐시한 좌석 배열 109~110ms. 배포 서버 CPU 효과는 아직 안 쟀다 | 1,210~1,495ms → 109~110ms | 1,210/110≈11.0배 ~ 1,495/109≈13.7배 (직접 계산) | Ryzen 5 7500F, JDK 21, 단일 스레드, 1,000번 | 로컬 측정 | docs/design/reservation/k6-p4d-review.md:47 | — | "응답 JSON을 한 번만 만든다" |
| N-92 | 좌석 | P4d 대가: 1초 낡은 값이 나갈 수 있음(ISE-205와 동일), 바이트 동등성·traceId 요청별·1초 뒤 재생성·회차 분리 테스트와 변이 확인은 PR에 있음 | 낡음 최대 1초 | — | 본문 전체·gzip 바이트 캐시는 API 계약 변경 이유로 비채택 | 설계 | docs/design/reservation/k6-p4d-review.md:9, :47 | — | — |
| N-93 | 인프라 | 배포 서버 재측정 계획: 배포 서버는 4 vCPU에 컨테이너 20개(`m7i-flex.xlarge`). 단계 8개(base → G1 → R1 → P4a → P4b → P4c → F → 사전 거절) × L(초당 100명 300초)·S(초당 5~160명, 회복 120초), 약 2시간 40분 | 8단계, 약 2시간 40분 | — | 측정 전. Gateway 등은 배포 그대로(2026-11-12 사용자 결정), Gateway 동시 처리 상한 64 | 설계 | docs/design/reservation/k6-deploy-steps.md:3, :7, :12 | — | — |
| N-94 | 인프라 | S를 160명에서 멈추는 이유: 발생기 네트워크 1.562Gbps 기준 좌석 조회(응답 약 830KB)가 초당 약 180건에서 막힘 | 초당 약 180건 | 1.562Gbps/(830KB×8)=약 235건/s 이론, 원문은 약 180건(계산값, 직접 계산 차이는 원문 미설명) | 계산값 | 계산값 | docs/design/reservation/k6-deploy-steps.md:12 | — | — |
| N-95 | 인프라 | 단계별 예상(측정 전): L에서 base~P4a 못 버팀, P4b 경계, P4c·F·사전 거절 버팀; S에서 base 5명, G1·R1 10~20명, P4a 20~40명, P4b 80~160명, P4c·F 160명 경계 | 표 | — | 예상(local 32코어와 CPU 상한 측정 기반), 측정 전 | 설계(예상) | docs/design/reservation/k6-deploy-steps.md:18-27 | — | — |
| N-96 | 인프라 | Gateway 동시 처리 상한 64: 좌석 조회·선점 초당 약 200건에서 응답이 0.3초 넘으면 상한 도달(리틀의 법칙). 그래서 base~P4a는 504·500 대신 즉시 503이 많을 것으로 봄. Gateway 메모리 1,024MB, 직접 메모리 상한 128MB | 상한 64; 초당 200건; 0.3초 | 64/200=0.32초 (직접 계산) | 계산값, 측정 전 | 계산값 | docs/design/reservation/k6-deploy-steps.md:11, :30-31 | — | — |
| N-97 | 인프라 | 측정 영향: 측정 중 배포 서버가 측정 모드, main 머지 정지, 단계마다 슬랙 알림, base~P4a는 서버 CPU를 오래 채움 | — | — | — | 설계(절차) | docs/design/reservation/k6-deploy-steps.md:35-37 | — | — |
| N-98 | 좌석 | 테스트 하네스: 작업 N개를 시작 신호 하나로 동시 출발, 에러 코드·SQLState로 분류(`40P01` 데드락, `55P03` lock_timeout, `23505` 유니크 위반), 데드락 수는 `pg_stat_database.deadlocks` 전후 차이. 단언을 고친 뒤 목표 기준으로 둠. 한계: 풀 10(테스트 기본)이 운영 5와 다름, 로컬 Docker 한 대 | — | — | `@EnabledIfEnvironmentVariable(SEATHOLD_CONCURRENCY=true)` | 설계 | docs/design/reservation/concurrency-scenarios.md:41-45, :49, :66-77 | — | — |
| N-99 | 좌석 | C3 목표 변경 이력: 처음 1,000ms 미만 → 2026-11-06 1,500ms 미만으로 수정 | 1,000 → 1,500ms | — | — | 판단 | docs/design/reservation/concurrency-scenarios.md:93 | — | — |
| N-100 | 좌석 | 정리 제안 7건: TS-1 재작성, lock_timeout 실제 상한 1초(가 채택), 부하에서 lock_timeout 효과 거의 안 보임, 풀 5 → 20 contract 제안, S2·S3 처리량 하락 후보(INSERT/UPDATE 합치기, 계정 기본 lock_timeout, 결제 토큰 서명 커밋 뒤로 H3), 측정 편차 크므로 다른 기기 재측정(ISE-25), 추첨 선점도 같은 매수 반영 | 7항목 | — | 5절 결정 표 | 판단(제안) | docs/design/reservation/concurrency-results.md:100-108 | — | — |
| O-01 | 좌석 | 선점 먼저 거절(개선 1)로 인기 구역 쏠림 L7 처리량이 늘었다 | 1,109건/초 → 1,827건/초 | +65%(원문). 직접 계산 1827/1109=1.65배 | L7: 닫힌 모델 VU 200, 20초, 좌석 1,000석, 요청 80%가 인기 100석, 풀 5, 로컬 11/13 | 로컬 측정 | R/seathold-structure-improvements.md:99-101 | — | "질 요청이 이길 요청과 같은 길을 끝까지 갔다" 헛일 제거 |
| O-02 | 좌석 | 개선 1로 L7 p95·p50 감소 | p50/p95 175/198ms → 105/124ms | p95 -37%(원문). 직접 계산 (198-124)/198=37.4% | 위와 동일 | 로컬 측정 | R/seathold-structure-improvements.md:102 | — | 리틀의 법칙 200÷1,827≈109ms 설명 |
| O-03 | 좌석 | 개선 1로 L7 잡힌 좌석·좌석 조회 p95 개선 | 잡힌 좌석 949 → 978~981(1,000석 중), 좌석 조회 p95 195ms → 123ms, 201 건수 평균 608 → 621 | +30석, 조회 p95 -37%, 201 +2%(원문) | L7 | 로컬 측정 | R/seathold-structure-improvements.md:103-105 | — | 번짐(좌석 조회까지 빨라짐) |
| O-04 | 좌석 | L8(잠금 장애)에서는 개선 1 효과가 작다 | 처리량 374 → 443건/초, p95 1,218 → 1,036ms, 500 5~11건 → 0~6건 | +18%, -15%(원문) | L8: VU 400, 20초, 인기 20석 측정 내내 잠금 | 로컬 측정 | R/seathold-structure-improvements.md:106-108 | — | 잠긴 좌석은 AVAILABLE로 보여 사전 거절이 못 거름 → 개선 3 필요 |
| O-05 | 좌석 | 409가 선점 응답의 대부분이다 | 선점 응답의 94~97%가 409(이번 측정). ISE-79 k6 측정은 77~87% | — | L7 | 로컬 측정 | R/seathold-structure-improvements.md:45 | — | 오픈 피크는 대부분 지는 요청 |
| O-06 | 좌석 | 개선 2(좌석 UPDATE를 트랜잭션 끝으로)는 효과를 확인하지 못했다 | L7 1,827/124ms → 1,834/123ms, L8 443/1,036ms → 432/1,063ms, C2 성공 81,78,81 → 86,85,86(다시 잰 3회 75,81,80), C3 최대 대기 224~239ms → 188~259ms | 모두 측정 편차 안(원문) | 로컬, C2 10명·좌석 4개·50라운드 500건 | 로컬 측정 | R/seathold-structure-improvements.md:142-151, 76 | — | 거부 아닌 "효과 미확인이나 남김" 판단, 배포 측정에서 재확인 조건 |
| O-07 | 좌석 | 개선 2로 잠금 구간 DB 왕복이 줄어든다(구조상) | 잠금 쥔 구간 왕복 "3 + 좌석 수"번 → "좌석 수"번(4석이면 7 → 4) | 직접 계산: 7-4=3번 감소 | 설계 | 설계 | R/seathold-structure-improvements.md:54, 138 | 순서 비교 표 R/seathold-structure-improvements.md:129-136 | 잠금 구간 축소 |
| O-08 | 좌석 | 개선 3(동시 처리 상한)으로 초당 600건 과부하에서 TIMEOUT이 사라졌다 | TIMEOUT 57~76% → 0% | — | L9 열린 모델 600건/초, 인기 20석 잠금, 상한 4·대기 200ms, 풀 5 | 로컬 측정 | R/seathold-structure-improvements.md:176 | — | 줄 무한 증가 차단 |
| O-09 | 좌석 | 상한으로 좌석 조회 번짐이 사라졌다 | 좌석 조회 성공률 29~47% → 100%(p95 3,011ms → 3~6ms). 요약에서는 실패 53~71% → 0% | — | L9 600/s | 로컬 측정 | R/seathold-structure-improvements.md:182, 10 | — | 선점 폭주가 조회로 번짐 |
| O-10 | 좌석 | 유령 선점이 0이 됐다 | 204~289석(잡힌 좌석의 23~33%) → 0석 | — | L9 600/s | 로컬 측정 | R/seathold-structure-improvements.md:181, 217 | — | 사용자는 포기했는데 서버가 좌석을 잡은 유령 선점 |
| O-11 | 좌석 | 상한의 대가: 503 비율 | 600/s에서 503 0% → 39~43%(대기 0이면 46%), 300/s에서 p95 56~62ms → 93~182ms, 실패 0% → 0~1.4%(대기 0이면 16~19%) | — | L9 | 로컬 측정 | R/seathold-structure-improvements.md:173-180, 195-196 | — | 무너지는 대신 빠르게 거절하는 교환 |
| O-12 | 좌석 | 상한 켜면 결과받은 선점·201이 오히려 늘었다 | 결과 받은 선점 2,911~5,206건 → 6,853~7,322건, 201 336~409 → 434~475 | — | L9 600/s | 로컬 측정 | R/seathold-structure-improvements.md:178-179, 188 | — | 포기한 요청 처리라는 헛일 제거 |
| O-13 | 좌석 | 측정 방식 정정(TS-74): 닫힌 모델로는 503 장치 측정이 왜곡됐다 | 503이 1ms에 끝나 VU 200명이 1초에 1만 건 넘게 재전송 | 직접 해석 아님(원문 서술) | L7·L8 | 판단 | R/seathold-structure-improvements.md:199-205 | — | 측정 방법 자체를 고친 반전(열린 모델 L9 도입) |
| O-14 | 좌석 | 커넥션 대기 초과가 500으로 나가는 문제 발견 | 계약은 503, 실제 500 | — | 공통 예외 처리기, 11/16 contract 이슈 ISE-293 | 판단 | R/seathold-structure-improvements.md:216, 222 | — | 측정 중 발견한 공통 모듈 결함 |
| O-15 | 좌석 | 계측 보강(개선 2)의 비용은 편차 안 | L7 기준선 1,513·1,500 req/s, p95 155·152ms, 커넥션 사용 2.73·2.77ms / 반영 1차 1,277·1,293, 188·175ms, 3.37·3.33ms / 2차 1,492·1,501, 162·151ms, 2.78·2.77ms | 1차 처리량 약 15% 낮았으나 재측정에서 기준선과 같음(원문) | L7 2·3회차 | 로컬 측정 | R/seathold-pre-deploy-measurements.md:68-73, 83 | — | "같은 코드도 15% 흔들린다" |
| O-16 | 좌석 | 같은 코드도 약 15% 흔들리고 날이 바뀌면 약 20% 이동 | 15% / 20% | — | 한 PC 동거 구조 | 로컬 측정 | R/seathold-pre-deploy-measurements.md:48, 161 | — | 효과 작은 변경은 같은 시점 짝 측정 |
| O-17 | 좌석 | 잠금 장애 L9에서 좌석 UPDATE의 약 68~69%가 50ms를 다 기다리고 실패 | 50ms 이하 31.7~32.2%(300/s), 30.4~30.9%(600/s), 평균 36.5~36.8ms / 37.2~37.6ms | 나머지 약 69%(원문 "약 68%"도 요약표에 있음, 모순 아님 표기차) | L9 300/s·600/s, 상한 4·대기 200ms | 로컬 측정 | R/seathold-pre-deploy-measurements.md:79-80, 85, 11 | — | 처음으로 잠금 대기를 지표로 본 순간 |
| O-18 | 좌석 | 상한 자리 4개로 잠금 요청 처리 한계 계산 | 4 ÷ 0.05초 ≈ 초당 최대 약 80건 | 계산값(원문) | 잠금에 막힌 요청 자리 50ms 점유 | 계산값 | R/seathold-pre-deploy-measurements.md:86 | — | 상한이 4/4로 붙는 이유 |
| O-19 | 좌석 | 만료 주기 5초 → 1초로 회수 속도 2.8배 | 회수 속도 16~17건/초 → 46~47건/초, 3,000건 회수 180~192초 → 64~65초 | 약 2.8배(원문). 직접 계산 180/64≈2.8 | L10, 3,000건, 한 주기 100건 | 로컬 측정 | R/seathold-pre-deploy-measurements.md:12, 99-105, 110 | — | 오염된 다음 측정 방지 |
| O-20 | 좌석 | 만료 1초 계산 vs 실측 | 계산 약 55건/초 → 실측 46~47건/초 | 계산 대비 약 15% 낮음(원문) | 건당 약 8ms 가정 vs 약 11ms 실측 | 계산값/로컬 측정 | R/seathold-pre-deploy-measurements.md:107, 111 | — | 예측과 실측의 차이 설명 |
| O-21 | 좌석 | 11/15 같은 시점 재측정에서 만료 회수 속도 재확인 | 17건/초(174.8초) → 49건/초(61.1초), 동시 선점 948 req/s/63ms → 924 req/s/69ms | 약 2.9배(직접 계산 174.8/61.1=2.86) | L10 최종 코드 5초·1초 | 로컬 측정 | R/seathold-pre-deploy-measurements.md:197-200 | — | 같은 결론 재확인 |
| O-22 | 좌석 | 토큰 서명 이동으로 커넥션 사용 시간이 서명 비용만큼 줄었다 | 7.79 → 7.69ms, 6.33 → 6.22ms. 직접 잰 서명 0.11ms(2만 번×3회 평균 113.6~119.8µs) | -0.10ms(-1.3%), -0.11ms(-1.8%)(원문) | L1 3,000건×3회, 2·3회차 | 로컬 측정 | R/seathold-pre-deploy-measurements.md:13, 119-120, 139-142 | 결과 표 R/seathold-pre-deploy-measurements.md:131-137 | 효과 작아도 코드상 손해 없어 남김 |
| O-23 | 좌석 | 11/15 재측정: 계측·서명 이동 효과는 편차 안 | L1 커넥션 사용 6.40 → 6.20ms(-3.1%), 처리량 722 → 743 req/s(+2.9%), L7 처리량 1,391 → 1,390 req/s, p95 163.0 → 162.2ms, L9 600/s 503 5,281 → 5,193건 | 원문 | 기준선 vs 최종 번갈아 | 로컬 측정 | R/seathold-pre-deploy-measurements.md:184-195 | — | 같은 시점 번갈아 재측정으로 결론 재확인 |
| O-24 | 좌석 | L9 300/s 503 건수가 순서 바꾸니 뒤집혔다 | 처음 4회: 기준선 8건 vs 최종 52건 → 9회 추가: 기준선 27 vs 최종 18, 13회 평균 21 vs 29건 | 요청 6,000건의 0.1%p 차이(원문) | 회당 503 0~105건 | 로컬 측정 | R/seathold-pre-deploy-measurements.md:191, 206 | — | 순서 효과·편차 반전 |
| O-25 | 좌석 | 잠금 장애 없는 정상 피크에서 상한 4가 1,800/s에서 오히려 무너뜨렸다 | 1,800/s: 처리량 1,800 → 1,564~1,565 req/s, TIMEOUT 0건 → 9,936~15,594건(28~43%), p50/p95 14~19/228~283ms → 2,224~2,675/3,009~3,010ms, 좌석 조회 탐침 100% → 56.1~74.0% | — | L9 unlocked, 상한 끔 vs 상한 4·대기 200ms, 11/15 | 로컬 측정 | R/seathold-pre-deploy-measurements.md:220-228 | — | 상한이 커넥션은 지키나 Tomcat 스레드(기본 200)는 못 지킴 반전 |
| O-26 | 좌석 | 600·1,200/s까지는 상한 영향 없음 | 600/s 실패 0%, p95 6~8ms vs 6~10ms, 1,200/s p95 6~7ms vs 11ms | — | 위와 동일 | 로컬 측정 | R/seathold-pre-deploy-measurements.md:222-223 | — | 한계 직전까지 정상 |
| O-27 | 좌석 | 상한 한계 계산: 선점 1건 약 2.2ms로 자리 4개 약 1,800/s, 풀 5면 약 2,300/s | 4 ÷ 0.0022 ≈ 1,800, 5 ÷ 0.0022 ≈ 2,300 | 상한 4는 처리 한계를 20% 낮춤(원문) | 계산 | 계산값 | R/seathold-pre-deploy-measurements.md:231, 234 | — | 커넥션 하나 남기는 대가 |
| O-28 | 좌석 | 스레드 고갈 설명: 1,800건/초 × 평균 100ms 남짓 → 스레드 200개 초과 필요 | 200개(Tomcat 기본), 1,800/s×0.05초=90개, 600/s×0.2초=120개 | 계산값 | 원문 해석 | 계산값 | R/seathold-pre-deploy-measurements.md:232, 235, 239 | — | 상한의 숨은 함정 |
| O-29 | 좌석 | 상한은 코드 기본 끔, config-repo에서도 안 켬 → 배포 측정 전 고쳐야 한다 | 고칠 방향: 대기 50ms, 상한을 풀에 맞춤, 503 `Retry-After` | — | 11/15 | 판단 | R/seathold-pre-deploy-measurements.md:238-240 | — | 설정 판단 |
| O-30 | 좌석 | 선점 503 프론트 재시도: 최대 4번(재시도 3번, 약 1·2·4초) | 4번, 1·2·4초 | — | `frontend/src/pages/seats/seatHold.ts` | 설계 | R/seathold-pre-deploy-measurements.md:240 | — | 재시도가 부하로 되돌아오는 효과(L9에 미반영) |
| O-31 | 좌석 | 캐시 사전 거절 켜면 정상 3,000/s에서 의도 실패 5.5% → 0% | 의도 실패 5.5% → 0%, 503 42.7% → 5.5%, 의도 p95 7.8초 → 46ms | p95 직접 계산 7800/46≈170배. 의도 실패 범위 끔 4.8~6.3%, 켬 0.0%(4회) | L9 정상, 풀 5·상한 4·대기 64, 입장 토큰, 재시도 모사, 탐침 20/s, 조회 50/s, 조건별 n=4, 로컬 11/16 | 로컬 측정 | R/seathold-pre-reject-cache-measure.md:9, 31-36 | — | 처리 한계 직전에서 갈리는 결과 |
| O-32 | 좌석 | 캐시 사전 거절로 자리 쥐는 시간이 줄었다 | 1.30~1.53ms → 1.05~1.11ms(1,800/s 1.36→1.11, 2,400/s 1.30→1.05, 3,000/s 1.53→1.07) | -20~30%(원문). 직접 계산 3,000/s (1.53-1.07)/1.53=30.1% | 위와 동일 | 로컬 측정 | R/seathold-pre-reject-cache-measure.md:10, 28, 30, 34 | — | 쿼리 무게 감소(왕복 수 아님) |
| O-33 | 좌석 | 정상 부하 409 중 사전 거절 비율 | 1,800/s 94.7%, 3,000/s 96.2%, 잠금 장애 시 79~80% | — | 위 | 로컬 측정 | R/seathold-pre-reject-cache-measure.md:27, 33, 42-44 | — | 409 대부분은 캐시에서 끝남 |
| O-34 | 좌석 | 캐시 사전 거절 후 처리 한계 계산 | 4 ÷ 1.53ms ≈ 약 2,600/s → 4 ÷ 1.07ms ≈ 약 3,700/s | 직접 계산 2614 → 3738(원문 약 2,600/3,700) | 계산 | 계산값 | R/seathold-pre-reject-cache-measure.md:49 | — | 3,000/s가 한계 안/밖으로 갈린 이유 |
| O-35 | 좌석 | 잠금 장애에서는 캐시 사전 거절 효과 없음 | 300/s 0%→0%(p95 109→85ms), 600/s 20.8%→19.6%, 900/s 43.4%→41.8% | 범위가 겹침 → 효과 없음(원문) | 잠금 장애 | 로컬 측정 | R/seathold-pre-reject-cache-measure.md:41-46, 50 | — | 병목은 잠긴 좌석의 50ms 대기 |
| O-36 | 좌석 | 판단: 캐시 사전 거절 유지, 한 문장 사전 판정과 함께 둠 | — | — | 코드 기본값 켬 | 판단 | R/seathold-pre-reject-cache-measure.md:7, 54, 80 | — | "둘 중 하나가 아니라 함께" |
| O-37 | 좌석 | 다른 AI 분석 대조: 4개 주장 중 "캐시로 아끼는 몫이 작다"는 측정과 다름, "이 브랜치만 남길 수도"는 측정으로 기각 | — | — | 11/16, 판단 기록 kwonwoo/2026-11-16-verify-other-agent-precheck-analysis.md | 판단 | R/seathold-pre-reject-cache-measure.md:69-80 | 표 R/seathold-pre-reject-cache-measure.md:74-79 | AI 분석 거부·부분 수용 사례 |
| O-38 | 좌석 | 자동 배정에는 개선이 없어 직접 고치지 않고 ISE-287로 제안 | — | — | 코드는 강민 작성(ISE-264) | 판단 | R/seathold-pre-reject-cache-measure.md:82-92 | 표 R/seathold-pre-reject-cache-measure.md:84-90 | 담당 경계 존중 판단 |
| O-39 | 좌석 | 사전 거절 정합성: 캐시는 거절에만 쓰고 모르면 DB가 판정 | 사전 거절 비율 73~84%(측정 브랜치, 지표 문서) | — | 1초 캐시 | 설계 | R/seathold-pre-reject-cache-measure.md:51, R/seathold-metrics.md:151 | — | 정합성 최후 방어는 DB |
| O-40 | 좌석 | 자동 배정(ISE-264)은 직접 선점보다 상한 자리 점유가 약 4배 길었다 | 4.7~5.6ms vs 1.1~1.6ms(300/s 4.93 vs 1.62, 1,000/s 4.72 vs 1.14, 1,800/s 5.63 vs 1.09) | 약 4배(원문). 직접 계산 4.93/1.62=3.0, 5.63/1.09=5.2배 | L9 SEATHOLD_LOAD_AUTO, 구역 A 1,000석, 로컬 11/16, n=4 | 로컬 측정 | R/seathold-auto-assign-baseline.md:8, 19-28 | — | 매진 뒤 거절 경로가 비쌈 |
| O-41 | 좌석 | 자동 배정 의도 실패율 | 1,000/s 직접 0% → 자동 6.0%(범위 5.7~6.6%), 1,800/s 0% → 45.6%(45.3~45.9%), 300/s 0% / 0%, 의도 p95 1,000/s 2ms vs 7.8s, 1,800/s 5ms vs 8.1s | — | 위 | 로컬 측정 | R/seathold-auto-assign-baseline.md:21-30 | — | 같은 부하에서 한쪽만 무너짐 |
| O-42 | 좌석 | 자동 배정 503 비율 | 1,000/s 0% → 43.5%, 1,800/s 0.4% → 82.2% | — | 위 | 로컬 측정 | R/seathold-auto-assign-baseline.md:24, 27 | — | — |
| O-43 | 좌석 | 자동 배정 처리 한계 계산 | 자동 4 ÷ 약 4.9ms ≈ 820/s, 직접 약 3,500/s | 계산값(원문) | 계산 | 계산값 | R/seathold-auto-assign-baseline.md:34 | — | 한계 820/s라 1,000/s부터 넘침 |
| O-44 | 좌석 | 자동 배정 개선 제안 4가지(모두 미측정 추정) | 왕복 3 → 1번(사전 조회 합치기) 등 | 추정 | 적용 전 | 판단 | R/seathold-auto-assign-baseline.md:43-48 | — | 매진 사전 거절이 1순위 |
| O-45 | 좌석 | 자동 배정 성공 건수는 비교하지 않는다 | 자동 회당 약 500건(1,000석을 평균 약 2석씩) | — | 분포 다름(직접은 인기 100석 몰림) | 로컬 측정 | R/seathold-auto-assign-baseline.md:31 | — | 비교 불가 항목 표시 |
| O-46 | 좌석 | 잠금 대기 50ms vs 500ms: 잠금 장애 300/s에서 500ms는 무너졌다 | 의도 실패 0% → 77.2%(범위 0.0% vs 75.6~79.3%), 의도 p95 103ms → 8.6s, 201 427 → 221(500ms 쪽), 자리 쥐는 시간 10.6 → 84.2ms | 자리 쥐는 시간 8배(원문). 직접 계산 84.2/10.6=7.9 | L9 잠금 장애, 캐시 사전 거절 켬, n=4, 로컬 11/16 | 로컬 측정 | R/seathold-lock-timeout-load-compare.md:66, 76-79, 87, 92 | — | ADR 0061 50ms가 부하에서도 맞음 |
| O-47 | 좌석 | 잠금 장애 600·900/s 의도 실패 | 600/s 50ms 19.5% vs 500ms 88.8%(201 501 vs 228), 900/s 40.8% vs 93.0% | — | 위 | 로컬 측정 | R/seathold-lock-timeout-load-compare.md:80-83 | — | — |
| O-48 | 좌석 | 정상 부하에서는 50ms와 500ms 차이 없음 | 1,800/s 0%/10ms 동일, 3,000/s 0%/32ms 동일, 자리 쥐는 시간 1.07 vs 1.08ms | 차이 없음 | 위 | 로컬 측정 | R/seathold-lock-timeout-load-compare.md:84-85, 88 | — | 기다릴 잠금 없으면 시한 안 쓰임 |
| O-49 | 좌석 | 처리 한계 380/s → 50/s(자리 쥐는 시간 8배 → 한계 급락) | 약 380/s → 약 50/s | 계산값(원문). 300/s는 500ms에서 한계의 6배 | 4 ÷ 10.6ms, 4 ÷ 84.2ms | 계산값 | R/seathold-lock-timeout-load-compare.md:92 | — | 한계 6배 초과라 무너짐 |
| O-50 | 좌석 | 0에 가까운 lock_timeout은 롤백 중인 짧은 잠금까지 건너뛰어 빈 좌석에 409(TS-73) | — | — | SKIP LOCKED에서 확인 | 판단 | R/seathold-lock-timeout-load-compare.md:98 | — | 50ms 유지 이유 |
| O-51 | 좌석 | 풀과 상한을 함께 올리면 로컬 처리 한계가 상한에 거의 비례해 올랐다 | 잠금 장애 의도 실패 0% 유지 최대 부하 5·4 300/s, 8·7 600/s, 12·11 900/s, 16·15 1,200/s | 계산값 4÷11ms≈360/s, 15÷11ms≈1,360/s과 맞음(원문) | 풀·상한 6조합, 로컬 11/16, n=4, 상한=풀-1 | 로컬 측정 | R/seathold-pool-limit-sweep.md:195, 215-216, 243 | 결과 표 R/seathold-pool-limit-sweep.md:210-224 | 상한은 "풀-1" |
| O-52 | 좌석 | 잠금 장애 600/s 실패율을 풀로 낮춤 | 5·4 19.9%/8.1s → 6·5 8.3%/7.9s → 8·7 0%/121ms → 10·9 0%/59ms. 900/s 42.7 → 31.9 → 14.9 → 1.8% → 12·11 0%/88ms | — | 위 | 로컬 측정 | R/seathold-pool-limit-sweep.md:214-215, 252 | — | 풀 올릴 때 잠금 구간 이득 |
| O-53 | 좌석 | 정상 부하 풀 올리면 3,000/s 실패가 사라짐 | 5·4 6.0%/7.8s → 6·5 0%/27ms → 8·7 0%/7ms, 2,400/s 5·4 0%/29ms | 5·4로도 2,400/s까지 실패 없음 | 정상 | 로컬 측정 | R/seathold-pool-limit-sweep.md:221-223, 252 | — | — |
| O-54 | 인프라 | 풀을 키울수록 자리 쥐는 시간과 DB CPU가 오른다 | 자리 쥐는 시간 잠금 장애 10.8 → 11.4ms, 정상 4,000/s 1.58 → 1.67ms. DB CPU p90 정상 5·4 84% → 6·5 97% → 8·7 101% → 10·9 97~151% → 12·11 149% → 16·15 144%, 잠금 장애 24% → 71% | 100%=1코어 | docker stats 약 2초 간격 | 로컬 측정 | R/seathold-pool-limit-sweep.md:198, 229-237 | DB CPU 표 R/seathold-pool-limit-sweep.md:231-237 | CPU 벽이 로컬엔 없다 |
| O-55 | 인프라 | 로컬 결과로 운영 풀을 정하지 않는다: 11/15 배포 측정(ISE-237 X1)에서 풀 10은 선점 p95가 나빠졌다 | 선점 p95 1.06s → 1.83s, 커넥션 쥐는 시간 2배, 배포 DB 좌석 조회만으로 약 210% CPU(ISE-237 S1) | 직접 계산 1.83/1.06=1.73배(원문은 "두 배"는 쥐는 시간) | 11/15 배포 측정, 원인은 호스트 CPU 포화로 봄 | 배포 측정 | R/seathold-pool-limit-sweep.md:197, 246 | — | 로컬 vs 배포 반전: 풀 크기 판단 보류 |
| O-56 | 인프라 | config-repo는 풀 5·상한 끔 유지(담당 판단) | "운영 풀 5 유지, CPU 개선 뒤 재측정" | — | ISE-237 | 판단 | R/seathold-pool-limit-sweep.md:249 | — | 측정을 근거로 설정을 안 바꾼 판단 |
| O-57 | 좌석 | 커넥션 탐침 성공률·상한 몫 | 모든 조합 탐침 성공 100%, 탐침 p95 2~4ms, 커넥션 대기 최대 0~1건 | — | 상한=풀-1 | 로컬 측정 | R/seathold-pool-limit-sweep.md:245 | — | 짧은 요청 몫 1개 |
| O-58 | 좌석 | 정합성: 모든 측정에서 중복 판매·원장 불일치·유령 좌석·500·3초 타임아웃 0 | 0건 | — | auto-assign, 50 vs 500, pre-reject, pool-sweep 4개 문서 공통 | 로컬 측정 | R/seathold-auto-assign-baseline.md:10, R/seathold-lock-timeout-load-compare.md:68, R/seathold-pre-reject-cache-measure.md:12, R/seathold-pool-limit-sweep.md:196 | — | 정합성은 지키고 성능만 비교 |
| O-59 | 좌석 | 선점 서버 지표 시계열 규모: HTTP 히스토그램 버킷 8개+`+Inf`=9개/조합, `seathold_result_total` outcome 9개 | 버킷 경계 25ms·50ms·100ms·200ms·300ms·500ms·1s·3s, percentiles-histogram은 약 70개라 쓰지 않음 | — | reservation-service만 | 설계 | R/seathold-metrics.md:125-126, 142 | 대시보드 패널 표 R/seathold-metrics.md:159-175 | 카디널리티를 의식한 지표 설계 |
| O-60 | 인프라 | 선점 지표 비용은 나노초 단위, 비용은 시계열 수에서 | 배포 Prometheus가 앱과 같은 호스트에서 돌아 `scrape_samples_scraped` 함께 확인 | — | ISE-277, 11/13 | 설계 | R/seathold-metrics.md:182 | — | 관측 비용 |
| O-61 | 좌석 | 사전 거절 비율(측정 브랜치) | 73~84% | — | ISE-281 | 로컬 측정 | R/seathold-metrics.md:151 | — | — |
| O-62 | 좌석 | 직접 발행 대기 시한 기본 1초, 접수 응답 시간에 포함 | 1초 | — | `seat-hold.async.direct-publish-timeout`, ISE-18 | 설계 | R/seathold-metrics.md:157 | — | 직접 발행 vs Outbox 비교 주의 |
| O-63 | 좌석 | 선점 계약: 좌석 1개 이상 max_per_user(기본 4)개 이하, 선점 만료 now+hold_minutes 기본 5분, 결제 토큰 exp=holdExpiresAt-10초, 응답 필드 10개 | 4매, 5분, 10초, 10개 필드 | — | 계약 ② ISE-21, ADR 0032, 11/01 확정 | 설계 | R/contracts.md:5, 20, 81-84 | JSON 예시 R/contracts.md:89-105 | 계약 확정 |
| O-64 | 좌석 | 입장 토큰은 선점 성공 순간 소모(ISE-207, ADR 0053), 다른 멱등키 재사용은 403 ENTRY_TOKEN_USED, 부분 유니크 인덱스 | — | — | 2026-11-12 Gateway, 11-13 선점 서비스 구현 | 설계 | R/contracts.md:18, 36-43 | — | 토큰 소모 시점 결정 |
| O-65 | 좌석 | 캐시 사전 거절 계약(ISE-281): 캐시 1초, 끄는 스위치 `seat-hold.pre-reject.enabled=false`, 대가로 403·400보다 409가 먼저 나갈 수 있음 | 1초 | — | 설계 | 설계 | R/contracts.md:38 | — | 순서 대가를 문서에 명시 |
| O-66 | 좌석 | 자동 배정 API(ISE-264, ADR 0059): `FOR UPDATE SKIP LOCKED`로 ID 오름차순 quantity개, 연석 보장 안 함, 매진 시 409 ZONE_SOLD_OUT, 사전 거절 안 함 | — | — | 설계 | 설계 | R/contracts.md:45-63 | — | — |
| O-67 | 좌석 | 접수형(async) 30초 시한은 "처리 시작 마감", 최대 약 35초 안에 결과, 시한 정리 5초마다, 좌석 잠금 50ms(ADR 0061), 재시도 3회(1·2·4초) 뒤 DLQ | 30초, 약 35초, 5초, 50ms, 1·2·4초 | — | ISE-16·17, ADR 0056·0057·0061·0062 | 설계 | R/contracts.md:110, 173-176 | — | 접수형 결과 SLA |
| O-68 | 좌석 | 동기형 lock_timeout은 계약에서 50ms(ADR 0061). 이슈 초안에서는 500ms | 500ms → 50ms | 직접 계산 10배 축소 | 설계 변경(초안 vs 계약) | 설계 | R/contracts.md:221, R/linear-issues-after.md:219 | — | 모순 표시 행: 이슈 원문 500ms는 옛 계획, 계약은 50ms |
| O-69 | 좌석 | 동시 처리 상한 초과 시 503 SERVICE_UNAVAILABLE(ADR 0062, 기본 끔) | — | — | `seat-hold.max-concurrent`, `max-concurrent-wait` | 설계 | R/contracts.md:225, R/seathold-structure-improvements.md:166 | — | — |
| O-70 | 좌석 | 같은 멱등키 연타 시 동기형 M2 기준선은 사전 조회만이라 두 번째가 SEAT_ALREADY_HELD를 받을 것으로 본다(구조상 추정, 미재현), M4 TS-3에서 재현 후 고침 | — | 추정(원문 "구조상 추정, 미재현") | ADR 0035 | 설계 | R/contracts.md:202 | — | MVP 먼저 결함 재현 서사 |
| O-71 | 좌석 | 예매 취소 API: 202 Accepted, 취소 검사 5단계, 멱등 키는 저장 안 하고 예매 상태가 곧 멱등 기록, 7단계 한 트랜잭션, 추첨 예매 취소·부분 취소 범위 밖 | 7단계 | — | ISE-206, 2026-11-11 사용자 결정 | 설계 | R/contracts.md:265-329 | — | 취소 설계 |
| O-72 | 좌석 | 선점 매수 행을 좌석보다 먼저 잠그는 이유: 선점이 매수 행 → 회차 좌석 순이라 취소 순서가 반대면 교착 | — | — | 설계 | 설계 | R/contracts.md:313 | — | 전역 잠금 순서 |
| O-73 | 좌석 | 결제 토큰 발급기 위치 변경: seathold → reservation 모듈로 옮김(ISE-273) | — | — | 2026-11-13, 예매 상세 조회(ISE-171)가 발급하며 reservation→seathold import 발생 | 판단 | R/contracts.md:244 | — | 의존 방향 위반 해소 |
| O-74 | 팀 | MVP와 고도화 분리(ISE-7~18): 동시성 방어를 M4로 미뤄 Before/After 비교 확보 | MVP 2~3(~11/6), M4 고도화(11/9~11/16), 서브 이슈 ISE-7~ISE-18 | — | ADR 0035, AI 판단 kwonwoo/2026-10-30-mvp-and-troubleshooting-scope-split.md | 판단 | R/mvp-and-troubleshooting-roadmap.md:68, 78-80, 82-83 | — | "처음부터 다 넣으면 실험으로 증명 못 한다" |
| O-75 | 좌석 | 6대 트러블슈팅 TS-1~TS-6 로드맵 | TS-1 역순 데드락(40P01), TS-2 4매 우회, TS-3 따닥 500, TS-4 만료 경합, TS-5 Kafka 접수형, TS-6 원장 대사 | — | M4 | 설계 | R/mvp-and-troubleshooting-roadmap.md:119-126 | — | 트러블슈팅 서사 틀 |
| O-76 | 좌석 | 10/27 이슈 → 10/28~10/30 변경 4가지(경로 변경·응답 8개→10개·복수형 테이블·단방향 모듈) | 응답 필드 8개 → 10개, 접수 HELD 조회 4개 → 9개 필드, 타인 조회 403 → 404 | — | ISE-20·21, ADR 0030·0032·0034 | 판단 | R/mvp-and-troubleshooting-roadmap.md:85, 97, 104 | 표 R/mvp-and-troubleshooting-roadmap.md:93-106 | 설계가 바뀐 순간 |
| O-77 | 좌석 | 이슈 초안의 완료 기준: 동일 좌석 동시 100명 → 1명만 201·99명 409, 중복 판매 0건 | 100명 → 1 / 99 | — | 목표(ISE-동시성 신규 이슈) | 설계 | R/linear-issues-after.md:233 | — | 목표 숫자 |
| O-78 | 좌석 | 접수형 비교 측정 계획: 동기형·접수형 직접 발행·접수형 Outbox 3종, 시나리오 동시 100명·1초 내 시작·각 10회, Outbox 폴링 500ms | 3종, 100명, 10회, 500ms | — | ISE-18 계획(결과 아님) | 설계 | R/linear-issues-after.md:376-380 | — | 계획만, 결과 문서 별도 |
| O-79 | 좌석 | 부하 시나리오 L1~L6 설계와 판정 기준: L1 VU 100 1초 내 시작 각 10회, L2 VU 200 60초 80%가 좌석 5%, L3 50→100→200→400→800 단계 60초, L6 VU 1,000 좌석 10개 중 하나 | VU 100/200/800/1,000 | — | ISE-79·18, 좌석 1,000·15,000석 `[가정]` | 설계 | R/load-test-scenarios.md:20, 31-36 | 시나리오 표 R/load-test-scenarios.md:29-36 | 부하 시나리오 설계 |
| O-80 | 좌석 | 포화점 판정 `[가정]`: 처리량이 직전 단계보다 5% 미만 늘면서 p95가 2배 넘게 뛴 첫 단계 | 5%, 2배 | — | 기준선 뒤 조정 | 설계 | R/load-test-scenarios.md:65 | — | 포화점 정의 |
| O-81 | 좌석 | 부하 L1·L2·L3·L6 로컬 근사 측정 완료(결과는 concurrency-results.md 4절, 이 담당 범위 밖) | — | — | 2026-11-03 | 로컬 측정 | R/load-test-scenarios.md:3 | — | 결과는 다른 문서 |
| O-82 | 좌석 | 처리량 개선 후보 H1~H5(가설): lock_timeout 500ms, 풀 5→20, 트랜잭션 축소(토큰 서명), 왕복 줄이기(UPDATE RETURNING), 접수형 전환 | 풀 5 vs SA 9-4의 20 | — | 가설 | 설계 | R/load-test-scenarios.md:44-50 | 표 R/load-test-scenarios.md:44-50 | 가설 목록이 이후 측정으로 검증됨(H1·H3·H2 관련) |
| O-83 | 좌석 | 원장 대사(ISE-14): 판매 기준은 활성 예매 좌석(HELD·CONFIRMED), 건당 트랜잭션 FOR UPDATE 후 재계산, 한 번에 종류별 최대 1,000건 | 1,000건 | — | 구현 완료, 자동 실행은 논의 대기(2026-11-09) | 설계 | R/seat-ledger-reconciliation.md:3, 135-140, 149-152, 167 | — | 원장 기준 자동 보정 |
| O-84 | 좌석 | 대사 테스트는 "맞는 원장을 잘못 고치지 않는다"만 보여준다; 실제 어긋남과 동시 쓰기가 같은 행에서 겹치는 경우는 재현 안 함 | 고친 건수 0 | — | `SeatLedgerReconciliationTest` | 로컬 측정 | R/seat-ledger-reconciliation.md:156 | — | 한계 명시 |
| O-85 | 좌석 | 보유 매수는 CONFIRMED도 센다(지금 코드 기준) | — | — | hold-expiry.md 2절 3번이 바뀌면 기준 수정 | 설계 | R/seat-ledger-reconciliation.md:142, 191 | — | 팀 논의 대기 항목 |
| O-86 | 인프라 | 대사 내부 API는 /internal/**이라 Gateway가 밖에서 막고, 측정 중에는 부르지 않는다(회차 전체 집계라 부하에 섞임) | — | — | `GET .../ledger`, `POST .../ledger/reconcile` | 설계 | R/seat-ledger-reconciliation.md:160-169 | — | — |
| P-01 | frontend | 경로별 코드 분할로 첫 로딩 JS gzip 합계를 줄였다 | 257.72 kB(파일 4개) → 221.17 kB(파일 6개) | -36.55 kB, -14.2% (36.55/257.72=0.1418). 원문도 "36.55 kB 덜 받는다" | `npm run build` 출력의 gzip. 홈·공연 상세만 보는 사용자 기준 | 계산값(빌드 출력 읽음, 배포 측정 아님) | README.md:458,465 | — | "첫 화면이 무거워지지 않게" |
| P-02 | frontend | 진입점 index 파일이 크게 줄었다 | 65.96 kB → 21.85 kB (gzip) | -44.11 kB, -66.9% (44.11/65.96), 약 3.0배 작아짐(65.96/21.85=3.02) | 위와 같음 | 계산값 | README.md:459 | — | 같음 |
| P-03 | frontend | 분할 대가: JS·CSS 파일 수와 JS gzip 전체는 늘었다 | 파일 6개 → 20개(CSS 1개 포함). JS gzip 전체 261.11 kB → 273.98 kB | 파일 3.3배(20/6). 전체 +12.87 kB, +4.9%(12.87/261.11). 원인은 "확인하지 않았다"(후보: 묶음 간 공용 코드 중복, 파일별 압축 효율) | 위와 같음 | 계산값. 원인 확인 못 함 | README.md:460,461,465 | — | 감수한 점 |
| P-04 | frontend | 글꼴은 이번에 바꾸지 않고 측정만 했다 | 홈 12조각 304.4 kB, 공연 상세 10조각 259.8 kB, 빌드 결과 woff2 92개 | — | MSW 목 + headless Chrome 1440×900, `encodedBodySize`(서비스 워커가 가로채 transferSize=0) | 로컬 측정 | README.md:462,463,484,452 | — | "측정만 하고 안 바꾼 것" |
| P-05 | frontend | 배포 서버 로딩 시간과 느린 회선 체감은 재지 않았다(확인 못 함) | — | — | 크기만 비교 | 확인 못 함 | README.md:495-496 | — | 한계 고백 |
| P-06 | frontend | 운영 빌드 앱 코드 47 kB, 라이브러리는 react·vendor로 분할 | 앱 코드 47 kB. 500 kB 경고 해소 | — | ISE-113 시점. 경로별 지연 로딩은 당시 하지 않았다가 ISE-212에서 함 | 계산값 | README.md:114,101 | — | — |
| P-07 | frontend | 좌석 응답이 크고, 구역 먼저 고르기로 바꿨다 | 15,000석(25구역×600석), 응답 811KB → 한 번에 한 구역 600석만 그림 | 그림 수 1/25(4%, 600/15,000) | 로컬 KSPO DOME, 1280px, 실제 백엔드 | 로컬 측정 | README.md:203,217 | seat-sections-selected.png, seat-sections-375.png | 설계가 바뀐 순간 1(전체 좌석표 → 구역 먼저) |
| P-08 | frontend | 구역 하나만 그리자 좌석 누름·구역 전환이 빠르다 | 좌석 누름 41ms, 구역 전환 21ms | (전 값 없음. 15,000버튼 상태의 측정값은 문서에 없음, 확인 못 함) | 구역 하나 600석, 실제 백엔드, 1280px | 로컬 측정 | README.md:203 | — | 같음 |
| P-09 | frontend | 좌석 응답이 한 번에 오는 시간과 대기열~좌석 진행 시간 | 좌석 응답 25구역 811KB 319ms. 공연 10 [예매하기] → 대기열 → 토큰 → 좌석까지 3.7초 | — | 로컬 compose, 샘플 사용자 | 로컬 측정 | README.md:221 | — | — |
| P-10 | frontend | 구역마다 Tab 정지점을 두면 좌석까지 25번 눌러야 한다 | Tab 25회 | — | 설계 이유(배치도를 radio 묶음으로 만들어 Tab 1회) | 설계 | README.md:203 | seat-sections-selected.png | — |
| P-11 | frontend | 혼자 줄 서도 "내 순번 1"이 3~3.6초 보였다 → 화면 완화 | 3~3.6초. `estimatedWaitSeconds <= 1`이면 "곧 입장합니다" | 원인: 입장 스케줄러 500ms + 순번 조회 간격(poll-min 3s + 20% 흔들기) | 서버 쪽 개선은 별도 contract 이슈(문서가 "별도" 표시) | 설계(관측 기반 판단). 재현 수치 외 측정 로그 없음 | README.md:169; design-system.md:396 | queue-waiting.png | 설계가 바뀐 순간 2(서버 개선 대신 화면 쪽 완화) |
| P-12 | frontend | 대기열 순번 폴링은 서버 값을 그대로 쓴다 | 서버 간격 3~10초(+jitter), 최소 조회 간격 3초 아래로 내리지 않음 | — | SA 9-4, ADR 0031 결정 10 | 설계 | README.md:171,173 | queue-waiting.png | 폴링 부하 상한 |
| P-13 | frontend | 진입 요청을 먼저 보내 오픈 순간 요청을 두 배로 늘리지 않는다 | 순번 조회부터 보내면 처음 온 사용자는 404 뒤 진입을 또 보내 요청 2배 | 2배(문서 서술) | ADR 0031 결정 3(이미 줄에 있으면 200) | 설계 | README.md:170 | — | "오픈 순간 부하" 서사 |
| P-14 | frontend | 자동 재시도 간격 규칙(대기열) | `Retry-After`+0~20%, 없으면 3·3·4초 뒤 8초+0~20%, 횟수 제한 없음, 500은 재시도 안 함 | — | 429, 502·503·504, 응답 없음 | 설계 | README.md:173 | queue-unstable.png | 장애 때도 순번 유지 |
| P-15 | frontend | 선점 요청 재시도 | 응답없음·504·503·429면 같은 키로 모두 4번까지. 결과 모름 뒤 409면 3초 기다려 한 번 더 확인 | 3초 = Gateway 응답 시한 | 멱등키 고른 좌석 묶음마다 하나 | 설계 | README.md:205 | — | 멱등키 서사 |
| P-16 | frontend | 결제 재시도·확인 간격 | 같은 키 8번까지 재전송. 확인 중 폴링 서버 10초, 2분 후 포기 문구. 확정 확인 1초마다, 30초 지나면 "지연" 안내 | 결과 모름 재전송 한도 8과 PG 503 자동 재시도 1회는 따로 센다 | 승인 결과 이벤트 2초 안 반영(SA 9-4) | 설계 | README.md:234,236,237,235 | payment-checking.png | — |
| P-17 | frontend | PG 503 자동 재시도(ISE-242) | 대기 1초+무작위 0~1초(Retry-After 있으면 그 값, 3초 상한), 누름 한 번당 1회 | 서버는 `Retry-After`를 안 줘 현재 1~2초 무작위 | 순간 포화(Bulkhead)는 수 ms~수백 ms에 풀림, 차단기 열린 장애는 10초 | 설계 | README.md:235 | — | 설계가 바뀐 순간 3(여러 번 즉시 재시도 → 1회+무작위 지연) |
| P-18 | frontend | 결제 가능 시간은 선점 5분보다 10초 짧다 | 5분 선점, 결제 토큰은 10초 먼저 끝남 → 표시 4분 50초(`paymentTokenExpiresAt`) | 300-10=290초 | SA 7-3 | 설계 | design-system.md:252; README.md:232 | payment-ready.png(04:50) | — |
| P-19 | frontend | 입장 토큰 유효 10분 | 입장 유효 10분. 60초 이하 경고, 0이면 위험 + 좌석 잠금 | — | SA 9-4 | 설계 | design-system.md:251 | seat-selected.png(09:55) | — |
| P-20 | frontend | 시계 보정: 서버 시각 기준으로 카운트다운·만료를 계산 | 결제 화면은 차이 값이 없으면 0이 돼도 막지 않고 서버 403을 따름 | 브라우저 시계가 빠르면 시간이 남았는데 잠기는 문제를 셀프 리뷰에서 재현 | — | 설계(재현 기반) | README.md:109,208,240; design-system.md:250 | — | "서버 시각이 정본" |
| P-21 | frontend | 상세 서버 시각 카운트다운 로컬 확인 | 브라우저 시계를 1시간 빠르게 해도 서버 기준 66초로 셈 | — | 회차 95초 뒤 오픈, headless Chrome(2026-11-02) | 로컬 측정 | README.md:125 | — | 서사: 시계 어긋나도 정확 |
| P-22 | frontend | 공연 상세 [예매하기]를 첫 화면 안에 유지 | 버튼 아래 끝 438~582px. 표: 1440×780·1366×650·1536×730 538/582px, 1280×600·1093×530·1024×600 466/510px, 900×600 486/530px(3줄 제목 489/533), 390×664·360×640 438/482px | 모두 첫 화면 안, 가로 넘침 없음 | 예매 중 / 오픈 전 / 목 서버 headless Chrome, 셀프 리뷰 반영 뒤 | 로컬 측정 | design-system.md:412-419; README.md:136 | concert-detail.webp | 설계가 바뀐 순간 4(패널을 배너 위로 끌어올림 → 배너 아래 열, 오픈 순간 버튼이 화면 안) |
| P-23 | frontend | 배너 글자 대비를 최악(순흑·순백 그림)으로 계산 | 900~1100px 라이트 8.5:1·다크 10.9:1, 1280px 7.2:1·8.6:1, 1440px 7.8:1·9.7:1. 요약 라벨 1280px 라이트 5.05:1 | 기준 4.5:1 초과 | 셀프 리뷰 WCAG 식 계산 | 계산값 | design-system.md:421 | — | — |
| P-24 | frontend | 홈 그림 바이트 | 1280px 홈 WebP 9장 174KB, 375px 8장 163KB, 공연 상세 2장 100KB, 컨테이너 메모리 약 11MiB(제한 64MiB) | 메모리 11/64=17% | 프로덕션 nginx 이미지, 캐시 끔 | 로컬 측정 | design-system.md:343 | home.webp, home-375.webp | — |
| P-25 | frontend | 포스터 그림은 직접 그려 외부 이미지·CDN 없이 번들 | 원본 8종, 포스터 480×720·배너 1600×667 WebP 16장 합계 약 300KB, 공연 번호로 `(id-1)%8` | 16장/약 300KB = 장당 약 19KB(계산) | 사진 저작권·초상권 회피 | 설계 | design-system.md:332,340 | home.webp | 서사: 사진 대신 직접 그린 8종 |
| P-26 | frontend | 헤더 한 줄 유지 확인 | 워드마크 높이 24px(한 줄). 360px 로그인 헤더는 고치기 전 72px → 고친 뒤 24px | 72→24, 1/3 | 12개 폭(1280~360) | 로컬 측정 | README.md:192,347 | — | 시연 패널 때문에 좁아진 앱 화면 |
| P-27 | frontend | 모든 해상도 가로 넘침 없음 확인 | 360~1440px 14폭, 헤더 시간 칩 전환 1000px 이상 헤더 / 991px 이하 본문 | — | 목 | 로컬 측정 | README.md:222 | seat-sections-375.png | — |
| P-28 | frontend | 프론트 테스트 수 | 1188개 통과 | — | `npx vitest run --maxWorkers=4`, ISE-209 시점(문서가 쓴 시점의 값) | 로컬 측정 | design-system.md:385 | — | — |
| P-29 | frontend | 토큰 대비 수치 | gray-500 5.1:1, primary-600 흰 글자 7.6:1, seat-vip 5.7, seat-r 4.3, seat-s 4.4, seat-a 5.0, seat-selected 18.1 | R·S 등급 배지 흰 글자 11~12px는 4.5:1 미달(후속 과제로 남김) | 흰 바탕 | 계산값 | design-system.md:47-78,202 | seat-selected.png | 감수한 점 |
| P-30 | frontend | 예매 중 배지 대비 4.48:1로 axe 위반 | 4.48:1(기준 4.5) | 0.02 모자람 | 이번 변경 전부터 있던 문제, 후속 | 계산값 | design-system.md:383 | — | 감수한 점 |
| P-31 | frontend | 로컬 확인: 대기열 실제 백엔드 | 진입 201 `WAITING`(순번 1, nextPollAfterMillis 3148), 3.2초 뒤 `READY` | — | 로컬 compose, 토큰 API 전(404) 시점 | 로컬 측정 | README.md:190 | — | — |
| P-32 | frontend | 로컬 확인: 결제 → 확정 | [결제하기] 누른 뒤 1.2초에 예매 `CONFIRMED`로 결과 경로 이동 | — | 로컬 compose(Mock PG), 샘플 사용자, 2석 330,000원 | 로컬 측정 | README.md:251 | reservations-confirmed.png | "결제 승인 ≠ 확정" |
| P-33 | frontend | 승인(APPROVED)은 예매 확정이 아니다 | 확정 확인 1초 폴링, 30초 후 지연 안내, 만료면 환불 안내 | — | SA 9-4(2초 안 반영), 결제 계약 1-4 | 설계 | README.md:82,237 | reservations-confirmed.png | 서사: 승인과 확정 사이 |
| P-34 | frontend | 추첨 화면 다시 받기 | 10초(+화면마다 0~3초)마다, 추첨+90분 지나면 중단 | 서버 스케줄러 1~10초 주기. 승계 마감 추첨+30분, 선점 30분(한 번 30분 연장) | 시계 맞는 화면이 추첨 시각에 함께 몰리는 것을 간격 분산(셀프 리뷰) | 설계 | README.md:299 | lottery-form.png, lottery-paid.png | 서사: 추첨 직후 폴링 분산 |
| P-35 | frontend | 추첨 권장 시간대와 추첨 직후 결제 몰림 | 한국 03:00~05:00 밖이면 정보 알림(막지 않음) | 이유: 자동결제가 일반 결제와 PG 자리를 다툼(서버 `DrawTimeWindow`) | 운영자 설정 화면 | 설계 | README.md:389 | — | — |
| P-36 | frontend | 추첨 응모 시안 숫자(샘플 값) | 추첨 번호 7·회차 9·추첨석 10석, 응모 2026.11.20 10:00~11.21 23:59, 추첨 2026.11.22 04:00, 한 번에 1~2매, 예비 순위 13위 | 지어낸 샘플값. 샘플 문구는 해요체 | samples/lottery.html | 시연(샘플) | F/samples/lottery.html:195-199,247 | lottery-form.png, lottery-drawing.png | — |
| P-37 | frontend | 추첨 예비 번호 표기 오류 방지 | 서버 `drawRank`는 전체 추첨 순위(당첨자 수보다 크고 건너뜀) → "예비 N번"이라 쓰지 않고 "추첨 순위 N위" | — | 셀프 리뷰 | 설계 | README.md:297 | — | — |
| P-38 | frontend | 발권 이벤트 부적: 참관객 약 50명, 발표 11/19 | 펫 조합 10×4×6×5=1,200, 레어 16건마다 1장(50명이면 3~4장), 같은 펫 확률 약 64% | 1,200=10×4×6×5. 50명 중 같은 펫 확률 0.64 = 1-exp(-50×49/(2×1200))≈0.640(내가 계산, 문서 값과 일치). 50/16=3.1장 | 무작위가 아니라 `(911·s+517) mod 1200` 일대일 대응 | 설계(계산 근거 문서 서술, 테스트 `charmTicket.test.ts`가 문서 인용) | README.md:423,430,431,432 | — | 설계가 바뀐 순간 5(무작위 배정 → 일대일 대응식, 50명이 겹치지 않게) |
| P-39 | frontend | 부적 PNG 해상도와 QR 크기 | PNG 1080×1920, QR 칸 버전 11~13(65~73칸), 화면 132px(넓은 화면 168px), 오류 정정 L, 부적 크기 min(폭, 420px, (높이-150)×9/16) | — | 390×844 폰에서 저장 버튼이 첫 화면에 들어옴 | 로컬 측정(헤드리스 Chrome) | README.md:428,429,435,437,441 | — | — |
| P-40 | frontend | 부적 한계: 실제 폰 카메라·iOS 저장·HTTPS 공유 시트는 확인 못 함 | — | — | 배포 서버 HTTP라 공유는 HTTPS에서만 | 확인 못 함 | README.md:443,436 | — | 한계 고백 |
| P-41 | frontend | 배포 화면이 https로 바뀌고 Grafana 링크 변경 | Grafana: http `52.63.10.174:3000` → https `https://52.63.10.174/grafana`(2026-11-16 ISE-278). 클립보드 복사 버튼도 https부터 보임 | — | 모순 후보: 그 전 문서는 http 가정. README.md:343,344에 변경 사실 서술 | 설계 | README.md:343,344 | — | — |
| P-42 | frontend | 시연 모드 패널 규칙 | 기본 너비 380px(280~720px), 접기 44px, 640px 미만 숨김, 요청 기록 최근 50건, 패널 코드 7.9 kB(lazy) | — | 켜지 않은 사용자는 패널 코드를 받지 않음 | 설계 / 코드 크기는 빌드 값 | README.md:342,341,345 | — | 발표 시연 도구(traceId → Grafana) |
| P-43 | frontend | 시연 모드 로컬 확인 | traceId 5건이 응답 본문과 모두 일치, 경계 100px 끌면 380→480px | — | 1280·720·360px headless Chrome | 로컬 측정 | README.md:353 | — | — |
| P-44 | frontend | 시연 장애 패널: Mock PG 장애를 화면에서 건다 | 결제 거절·승인 응답 유실은 다음 1건(`count: 1`), PG 응답 지연 4초(결제 서비스 PG 읽기 시한 2초), PG 서버 오류는 해제까지. 3초마다 다시 받음 | 서킷 브레이커는 최근 20건 중 절반 넘게 실패해야 열려 버튼 한 번으로 재현 불가 | 배포는 `loadtest` 프로필이라 열림, 로컬은 `local` 프로필이라 "적용 불가" 표시 | 설계 | README.md:414-418 | — | 설계가 바뀐 순간 6(프론트 nginx 직접 중계 → Gateway 운영자 경로, 2026-11-09) |
| P-45 | frontend | 프론트 nginx에서 Gateway로만 중계, 서비스 직접 중계 금지 | 구조: 브라우저 → nginx → gateway:8080. 부하 도구는 nginx를 안 거침. nginx 80만 전체 공개, 8080은 발생기 보안그룹만 | — | 서비스가 Gateway 사용자 헤더를 서명 없이 믿기 때문 | 설계 | README.md:24-31,502 | — | 아키텍처 한 장면 |
| P-46 | frontend | 스택 결정: React SPA, 별도 nginx 컨테이너, CORS 없음 | 정적 HTML·JS(Notion 결정 #7) → React SPA(ADR 0040). Spring Boot 내장 안 함 | — | 바꿀 조건을 근거로 바꿈 | 설계 | README.md:11-20,500 | — | 설계가 바뀐 순간 7(Notion #7 정적 HTML → React SPA) |
| P-47 | frontend | 토큰 저장 방식 | Access는 메모리, Refresh·입장 토큰은 탭 단위 sessionStorage | — | 사용자당 Refresh 토큰 하나 재발급 시 교체 구조와 맞춤 | 설계 | README.md:20 | — | — |
| P-48 | frontend | 문구 체계를 해요체 → 합쇼체로 바꿈 | 2026-11-16 ISE-292, 문구 종류별 예시 10개를 사용자가 고쳐 쓴 뒤 규칙 추출 | 샘플 HTML·캡처 png/webp는 해요체 그대로(모순 표시 M-1 참조) | 부적 화면과 시연 데이터 소개는 예외 | 판단(사용자 결정) | design-system.md:19-30 | queue-waiting.png 등(해요체) | 설계가 바뀐 순간 8(입말 → 서비스 문체) |
| P-49 | frontend | 다크 모드: "하지 않는다"에서 라이트·다크 둘 다로 변경 | 2026-11-09 ISE-208. 대비 계산: 상태 글자/바탕 7.1~9.7:1, 진한 칩 6.5:1 이상, 보조 글자 6.8:1, 다크 오픈 날짜 칸 글자 `text-white/70`→`gray-0` 수정, 사이트 아래 정보 라이트 3.1:1 수정 | — | 셀프 리뷰(Opus) 계산 | 판단 + 계산값 | design-system.md:120,138,464 | — | 설계가 바뀐 순간 9(다크 없음 → 두 벌) |
| P-50 | frontend | 홈 시안 B 확정, 굵은 글씨 한 단계 낮춤 | "굵은 글씨가 너무 굵다"며 굵기 800/700/600/500/400 기준 | — | 2026-11-09 사용자 | 판단 | design-system.md:99,347 | home.webp | 디자인 결정 장면 |
| P-51 | frontend | 로고를 "조명이 비춘 내 자리"로 변경, 빨간 좌석 = 409 좌석 | 2026-11-05 인디고 사각+흰 좌석+빨간 점 → 2026-11-09 어두운 칸+조명+빨간 좌석 | — | 서비스 이름 "이선좌" 자체가 409 안내와 같다 | 판단 | design-system.md:335,461 | login.webp, home.webp | 발표 서사: 이름·로고가 설계 의도 |
| P-52 | frontend | 시연 샘플 광고 자리 규칙 | 띠 최소 200px, "광고" 표시·샘플 명시, 외부 스크립트 없음, 링크 없음 | — | 실제 광고주 없음 | 설계 | design-system.md:358-370 | — | — |
| P-53 | frontend | 쓰는 API 범위: 시연 기능은 백엔드 구현 그대로 | 없는 필드는 만들지 않고 "나중에 고칠 것"에 남김(2026-11-02 판단) | 목록: 가격·장르·공연 이미지·추첨 번호·예매 응답의 공연 제목/출처/시작시각 등 | 프론트 담당 결정 | 판단 | README.md:113,119-123,273-278,303-308 | — | "화면 때문에 백엔드 일정이 흔들리지 않게" |
| P-54 | frontend | 이선좌 서비스 설명 문구(푸터) | "티켓 오픈의 폭주 트래픽을 견디는 콘서트 예매 서비스예요" / "부트캠프 최종 프로젝트 5조" | 캡처의 문구(해요체) | 캡처 시점 | 시연 | login.webp, concert-detail.webp (이미지 안 문구) | login.webp, concert-detail.webp | 브로셔 카피 후보. 현재 문구는 합쇼체로 바뀌었을 가능성(캡처가 이전 모양) |
| P-55 | frontend | 완료 티켓 모양 ISE-210 변경 | 캡처 `ticket.webp`는 바꾸기 전(좌석마다 어두운 카드 + 바코드) | 현재 구현은 가로 한 장 티켓 | design-system.md:320 | 시연 | design-system.md:320,399 | ticket.webp | 캡처가 현재 UI가 아님 |
| P-56 | frontend | 시연 데이터가 캡처에 노출 | home 캡처 "[ISE-113 확인] 오픈 카운트다운 공연", "E2E 예매 공연 3e063a58", "MVP 확인 공연 1bb5d8", 목록 "예매 중 12", "티켓 오픈 예정 1" | 개발 중 만든 데이터 | 브로셔 인용 시 사용 부적합 | 시연 | home.webp, home-375.webp | home.webp | 주의(§5 참조) |
| M-1 | 문구 체계는 2026-11-16부로 합쇼체(ISE-292)인데, 샘플 HTML 6개와 캡처 png/webp는 해요체 문구("자리는 그대로예요", "당첨됐어요" 등)가 그대로다. 캡처를 현재 문구로 쓰면 안 됨 | design-system.md:19-30 vs F/samples/queue.html:204-236, lottery.html:202,231 등 |
| M-2 | seat-selection.html은 등급 4종(VIP 165,000·R 143,000·S 121,000·A 99,000), seat-sections.html은 3종(VIP 165,000·R 143,000·S 112,000, A 없음). 같은 샘플 간 값이 다르다. design-system.md 표(2-1)는 `seat-a` 색을 정의 | F/samples/seat-selection.html:316-319, seat-sections.html:356-358, design-system.md:77 |
| M-3 | seat-selection.html 안내는 "5분 동안 좌석이 유지돼요", seat-sections.html은 "잠시 유지"(5분 숫자 뺌). README는 "선점 5분 같은 숫자는 설정이 바뀔 수 있어 쓰지 않는다" | seat-selection.html:280, seat-sections.html:319, README.md:138 |
| M-4 | seat-selected.png/seat-conflict.png/seat-held.png(첫 샘플)의 장소는 "한강 아트홀", seat-sections 캡처·payment 캡처는 "KSPO DOME". 같은 공연명 "노을섬 단독 콘서트 〈새벽의 좌석〉"에 장소가 다름 | 캡처 내용, seat-selection.html:236, seat-sections.html:270 |
| M-5 | design-system.md 표 3절 좌석표에는 추첨석(빗금)이 있으나 README는 "좌석 응답에서 추첨석이 선택할 수 없음과 구분되지 않아 범례에서 뺐다". 첫 샘플(seat-selection)에는 범례에 추첨석이 있음 | design-system.md:149; README.md:214; seat-selection.html:264 |
| M-6 | Grafana 주소가 문서 내에서 변경됨(http:3000 → https `/grafana`, 2026-11-16). 그 전 시점 서술("http에서는 링크만 남았다")은 과거형으로 같은 줄에 병기 | README.md:343,344 |
| M-7 | design-system.md는 `Card` 모서리 18px, 2-3절 표는 lg=14px. 문서가 "다르다"고 직접 밝힘 | design-system.md:109,377,430 |
| M-8 | README.md:136 "측정은 …버튼 아래 끝이 438~582px"과 design-system.md:414-419 표는 같은 값. 단 README는 7개 창 크기, 표는 그중 일부 묶음 표기. 충돌 아님(요약 차이) | README.md:136; design-system.md:414-419 |

### 1-a. 조각별 모순·주의 메모(표 밖에 적힌 것)
#### 조각 P: 모순·주의(행 분리)

| id | 내용 | 근거 |
|---|---|---|
| M-1 | 문구 체계는 2026-11-16부로 합쇼체(ISE-292)인데, 샘플 HTML 6개와 캡처 png/webp는 해요체 문구("자리는 그대로예요", "당첨됐어요" 등)가 그대로다. 캡처를 현재 문구로 쓰면 안 됨 | design-system.md:19-30 vs F/samples/queue.html:204-236, lottery.html:202,231 등 |
| M-2 | seat-selection.html은 등급 4종(VIP 165,000·R 143,000·S 121,000·A 99,000), seat-sections.html은 3종(VIP 165,000·R 143,000·S 112,000, A 없음). 같은 샘플 간 값이 다르다. design-system.md 표(2-1)는 `seat-a` 색을 정의 | F/samples/seat-selection.html:316-319, seat-sections.html:356-358, design-system.md:77 |
| M-3 | seat-selection.html 안내는 "5분 동안 좌석이 유지돼요", seat-sections.html은 "잠시 유지"(5분 숫자 뺌). README는 "선점 5분 같은 숫자는 설정이 바뀔 수 있어 쓰지 않는다" | seat-selection.html:280, seat-sections.html:319, README.md:138 |
| M-4 | seat-selected.png/seat-conflict.png/seat-held.png(첫 샘플)의 장소는 "한강 아트홀", seat-sections 캡처·payment 캡처는 "KSPO DOME". 같은 공연명 "노을섬 단독 콘서트 〈새벽의 좌석〉"에 장소가 다름 | 캡처 내용, seat-selection.html:236, seat-sections.html:270 |
| M-5 | design-system.md 표 3절 좌석표에는 추첨석(빗금)이 있으나 README는 "좌석 응답에서 추첨석이 선택할 수 없음과 구분되지 않아 범례에서 뺐다". 첫 샘플(seat-selection)에는 범례에 추첨석이 있음 | design-system.md:149; README.md:214; seat-selection.html:264 |
| M-6 | Grafana 주소가 문서 내에서 변경됨(http:3000 → https `/grafana`, 2026-11-16). 그 전 시점 서술("http에서는 링크만 남았다")은 과거형으로 같은 줄에 병기 | README.md:343,344 |
| M-7 | design-system.md는 `Card` 모서리 18px, 2-3절 표는 lg=14px. 문서가 "다르다"고 직접 밝힘 | design-system.md:109,377,430 |
| M-8 | README.md:136 "측정은 …버튼 아래 끝이 438~582px"과 design-system.md:414-419 표는 같은 값. 단 README는 7개 창 크기, 표는 그중 일부 묶음 표기. 충돌 아님(요약 차이) | README.md:136; design-system.md:414-419 |


## 2. 하이라이트 후보 Top 15
전후 조건이 같은 큰 숫자 순. 각 항목 끝의 id가 표의 근거 카드다(조건·줄 번호는 카드 참조). 배수는 조각 담당이 계산한 "직접 계산"이다.

| 순 | 후보 | 숫자(전 → 후) | 왜 드라마틱한가 | 카드 |
|---|---|---|---|---|
| 1 | 좌석 조회 성공률(K1 오픈 직후 부하, base vs 최종 F, 3회) | 0.13% → 100% (직접 계산 약 769배) | 수정 전 코드는 오픈 직후 사실상 전멸, 최종은 전부 성공 | M-01, M-06 |
| 2 | 과부하 뒤 사용자가 받은 좌석 조회 200(R1 커넥션 대기 30초→1초) | 7건 → 1,463건 (약 209배), 서버 200 중 사용자 도달 0.1% → 95% | 서버는 일했지만 사용자는 못 받던 "헛수고" 99.6%를 되돌림 | N-65 |
| 3 | 좌석 선점 스트레스에서 버틴 단계 | 10명/초 → 640~1,280명/초(조회 위주), 혼합 부하 320명 확인 | 한계가 두 자릿수 배 이상 올라감(단 640·1,280은 매진 뒤 조회만이라 혼합과 구분 필수) | M-07 |
| 4 | 좌석 사전 거절 캐시(정상 3,000/s, 로컬) | 의도 실패 5.5% → 0%, 의도 p95 7.8초 → 46ms (약 170배) | 실패율·지연이 동시에 사라짐 | O-31 |
| 5 | 정상 부하 1,800/s 의도 실패율(성능 반복 5회) | 24.2% → 0.0%, 요청 p95 68ms → 7.5ms, 자리 점유 3.5~3.7ms → 1.3~1.5ms | 처음과 끝 비교가 깔끔한 5회 반복 서사 | M-97 |
| 6 | Gateway 동시 처리 상한 64(2,000/s) | p95 435ms → 87ms (약 5.0배), 굿풋 1,469 → 1,696 | 한 줄 설정으로 꼬리 지연이 5분의 1 | B-55, D-73 |
| 7 | 요청 로그 끄기(2,500/s) | 굿풋 1,608 → 2,122(+32%), 1,500/s 서버 CPU 93% → 85% | 비용이 안 든다고 믿던 로그가 병목 | B 조각 H2, D 조각 |
| 8 | 자동결제 2,000건 10분 안 완료율(ADR 0051, 배포 측정) | 93.1·92.4·93.4% → 100%, p95 약 612·616·610초 → 약 210·205초, 초당 약 3.2 → 약 9.4건(약 2.9배) | 가정(초당 33건, 약 1분)이 틀렸다고 실측이 드러낸 뒤 구조를 바꿔 목표 달성 | E-43, E-72, E-34 |
| 9 | 일반 결제 503(L2-b) | 9.5%(1,424/15,000) → 0건(15,000건 모두 승인) | 오류율 0 | E 조각(numbers-and-measurement.md:142,467) |
| 10 | 결제 PG 동시 허가 10 → 30(PG 300ms) | 승인 한도 31.2 → 91.5건/초(약 2.93배), 거절 59.5% → 0.2% | 이론(10÷0.3)이 측정과 맞아 들어감 | D-131, D-135, K 조각 |
| 11 | 균일 O1 대 Synthetic O1 | 선점 409 48.1% → 11.7%, 확정 사용자 65.8% → 84.9%, 진입 초당 최대 353 → 2,792(약 7.9배) | 같은 서버를 "현실적 트래픽"으로 시험하면 결과가 달라짐 | E-95, E-98 |
| 12 | Outbox 쿼리 조건 변경(로컬 EXPLAIN) | 12.7ms → 0.06ms (약 211.7배) | 한 줄 쿼리 수정이 2자릿수 배 | K-65 |
| 13 | Gateway OOM | 3회(768MiB) → 0회(1,024MiB), 대신 2,000/s p95 435ms → 752ms | 메모리를 늘리자 지연이 나빠지는 반전 | B-29, B-30, B-31 |
| 14 | 좌석 배치 캐시 로컬 메모리 vs Redis | 조회 p95 7.0ms → 38.3ms(약 5.5배), 처리량 1,244 → 397건/초(-68%) | "Redis에 두면 더 빠르겠지"라는 직관이 틀림 | N 조각(k6-p4b-local-vs-redis.md:9-14) |
| 15 | G1 GC 전환 + 풀 5 → 20 | GC 정지 약 75% → 7.3%, 버틴 단계 10 → 20명, L3 VU 400 처리량 453 → 869 req/s(약 1.9배), 만료 헛수고 100 → 0 | 설정 두 줄로 GC가 부하 시간의 4분의 3을 먹던 문제 제거 | N 조각(concurrency-results.md:44,47,59-62), M-08, M-09 |

보조 후보: 대사 변경 약 14만 6천 건·약 1,460쪽이 상한 1,000쪽을 넘어 대사가 실패한 사고(K-53, 11/12), 자동결제 이중 결제 0건 장애 주입(F-57, 원장 승인 34 = 결제 DB 34), 프런트 첫 로딩 JS gzip 257.72kB → 221.17kB(P-01), 좌석 15,000석 구역 먼저 고르기(응답 811KB, 구역 600석만 그림, P-07~08).
주의: 7차와 8차에서 같은 설정의 서버 CPU가 6~9%p 달랐으므로 직접 비교 금지(D-114). 구 노트북 측정과 새 기기 측정을 섞지 말 것(M 조각 5-1~5-4 대 5-5).


## 3. (조각별) 메모리 관련 숫자 전부

### 조각 A — 2. 메모리 관련 숫자 전부

전/후 구분: 이 담당 파일에는 메모리 측정 결과(전·후)가 없다. 아래는 모두 `[가정]` 시작값(설계)이다. 실측은 "docker stats로 부하 중 사용량을 측정해 바꾼다"(`docs/core/SA MASTER.md:849`)고만 적혀 있고 결과는 이 문서에 없다.

| 항목 | 숫자 | 성격 | 근거 |
|---|---|---|---|
| gateway | 힙 512m / 컨테이너 768m | 설계 [가정] | docs/core/SA MASTER.md:853 |
| eureka | 힙 256m / 컨테이너 512m | 설계 [가정] | docs/core/SA MASTER.md:854 |
| config-server | 힙 256m / 컨테이너 512m | 설계 [가정] | docs/core/SA MASTER.md:855, 941 |
| user-service | 힙 384m / 컨테이너 640m | 설계 [가정] | docs/core/SA MASTER.md:856 |
| reservation-service | 힙 768m / 컨테이너 1,152m | 설계 [가정] | docs/core/SA MASTER.md:857 |
| payment-service | 힙 512m / 컨테이너 768m | 설계 [가정] | docs/core/SA MASTER.md:858 |
| mock-pg | 힙 256m / 컨테이너 512m | 설계 [가정] | docs/core/SA MASTER.md:859 |
| postgres × 3 | 컨테이너 합 2,048m (reservation 1,024m, 나머지 512m씩) | 설계 [가정] | docs/core/SA MASTER.md:860 |
| redis | `maxmemory` 약 400m / 컨테이너 512m | 설계 [가정] | docs/core/SA MASTER.md:861 |
| kafka (KRaft) | 힙 1g(1,024m) / 컨테이너 1,536m | 설계 [가정] | docs/core/SA MASTER.md:862 |
| kafka-ui / prometheus / grafana / zipkin | 컨테이너 512m / 768m / 384m / 768m | 설계 [가정] | docs/core/SA MASTER.md:863-866 |
| 합계 | 컨테이너 상한 합 11,392m (약 11.1GB). 직접 계산: 11,392 / 1,024 = 11.125GB | 계산값 | docs/core/SA MASTER.md:867 |
| 필요 인스턴스 | 합계를 75% 이하로 두면 약 14.8GB, 16GB급 최소. 직접 계산: 11.125 / 0.75 = 14.83GB(일치). 8GB급이면 zipkin·kafka-ui·prometheus 제거 또는 상한 축소(U10) | 계산값 | docs/core/SA MASTER.md:868, 2269 |
| 컨테이너 상한 − 힙 간격 | 약 256~384m, 메타스페이스·스레드 스택·네이티브 메모리 때문, 충분한지는 측정으로 확인. 직접 계산: gateway 768−512=256, reservation 1,152−768=384, payment 768−512=256, user 640−384=256 | 설계, 계산값 | docs/core/SA MASTER.md:869, 853, 856-858 |
| 측정 환경 기록표 | JVM 힙(-Xmx)·컨테이너 메모리 상한을 결과마다 기록 | 설계 | docs/core/SA MASTER.md:309 |
| Redis 메모리·카디널리티 | `queue:{scheduleId}:*` 키 종류 6개(seq·waiting·admitted·lastTick·issued·admitLog)와 TTL 규칙만 있고 용량 추정은 없음. issued 정리는 1초마다 최대 500개 | 설계 | docs/core/SA MASTER.md:2027-2032, 2046 |
| 적재량(DB 행 수) | 좌석 3,000행, user_schedule_quotas 입장자 50,000명이면 100,000행, 용량 1GB 넘지 않을 것으로 보임(행 크기 미측정) | 계산값 [가정] | docs/core/SA MASTER.md:2056, 2061, 2065 |
| 실제 DB 행 수 | 11/12 배포 DB schedule_seats 약 156만 행(추정 3,000행의 약 520배, 직접 계산) | 배포 측정(방법 미기재) | docs/core/SA MASTER.md:2066 |
| 좌석 응답 크기 | 15,000석 회차 좌석 응답 약 830KB → 1,500석으로 축소(ADR 0060) | 측정 언급(방법 미기재) | docs/core/SA MASTER.md:333 |
| 가상 스레드 | Java 21에서 synchronized 고정(JDK 24에서 해결)이라 기본 끔 | 설계 | docs/core/SA MASTER.md:812 |
| OOM·JVM 옵션 | OOM 기록은 이 담당 파일에 없음. 힙 상한 외 JVM 옵션 언급 없음 | 해당 없음 | — |


### 조각 B — 2. 메모리 관련 숫자 전부

전/후 유무를 표시한다.

| 항목 | 값 | 전/후 | 근거 |
|---|---|---|---|
| Gateway 컨테이너 상한 | 768MiB → 1,024MiB, 직접 메모리 `-XX:MaxDirectMemorySize=128m`(M1) | 전 768, 후 1,024 | STORY:147, RES:97 |
| Gateway OOM | 0a 도중 3회(08:18:53·08:25:46·08:35:11Z), anon-rss 757~758MB, VU 5,309~5,938. M1 뒤 OOM 0, 메모리 최대 849MiB | 전 3회, 후 0회 | STORY:144,148 |
| Gateway 메모리 계산 | 힙 최대 512MB + 비힙 약 158MB + JVM 밖 약 130MB ≈ 800MB > 768MiB(계산값). 처리 중 요청당 약 47KiB → ≈5,700개(거친 계산) | — | STORY:152, RES:98 |
| B안(`-Xmx384m`) | 첫 OOM 직전 힙 사용 341MB라 GC 위험으로 안 고름 | 불채택 | STORY:154 |
| 상한 64 이후 Gateway 메모리 | 511~540MiB 평탄, OOM 0 | 후 | STORY:163 |
| payment 상한 | 768m → 1024m(11/15 cgroup OOM 5회, TS-71). 옵션 `-Xms256m -Xmx512m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=128m -XX:MaxDirectMemorySize=64m -XX:+UseG1GC` | 전 768, 후 1024 | DEP:391,397 |
| 앱별 메모리 표 | gateway 상한 1024m 힙 최대 512 힙 committed 256 비힙 170 지표 밖 약 104 / reservation 1152m 768/384/272/약 140/약 1,180(상한 초과 가능) / user 640m 384/192/193/약 96/약 673 / assistant 384m 192/96/124/약 75/약 391 | 후 | DEP:389-395 |
| 컨테이너 상한 합 | 12,992 → 13,248MiB(22개), 장비 15,701MiB의 84%, 실사용 `free -m` 7,217MiB(11/15 17:48 UTC) | 전 12,992, 후 13,248 | DEP:398 |
| 증설 가정 | reservation·user +128, assistant +64 → 13,568MiB(86%) | 가정 | DEP:399 |
| 배포 상한 합계 | 약 12.5GB(앱 5.2·데이터 4.5·관측 2.8), 16GB급의 약 78% | — | DEP:169 |
| 유휴 사용 | 약 4.7GB / 15.7GiB(2026-11-04 배포 직후), 로컬 유휴 합계 약 4.1GB(2026-11-03) | — | DEP:383,407 |
| assistant-service | `mem_limit` 384m, `-Xms96m -Xmx192m` | — | DEP:223 |
| Jenkins | `mem_limit` 1200m, `-Xmx768m` | — | DEP:322 |
| 프론트 | `mem_limit` 64m | — | DEP:102 |
| Prometheus·Loki 상한 | 768m, 512m | — | DEP:309 |
| Grafana | 유휴 278MiB(384m의 72%) | — | DEP:383 |
| GC 선택 | 컨테이너 메모리 약 1,792MB 미만이면 JVM이 Serial GC, 앱 7개에 G1 명시 | — | DEP:419 |
| JIT | 컴파일 시간 지표는 메모리가 아니라 CPU(참고) | — | DEP:199 |
| Redis 메모리·카디널리티 | 해당 없음(Redis 컨테이너 CPU 8%만 있음) | — | RES:8 |


### 조각 C — 2. 메모리 관련 숫자 전부

전/후 대비는 없고 모두 설정값 또는 가정값(별도 표시 없으면 [가정]):
- Loki 컨테이너 상한 512m, Alloy 256m, Alertmanager 128m, 추가 합계 896m (OB:184-189). SA 9-3 합계 11,392m + 896m → 약 12,288m(12.0GB), 75% 규칙이면 필요 약 16.0GB(16GB급이 정확히 한계) (OB:191). 로컬 compose 상한은 SA 표보다 작아 직접 비교 불가 (OB:191).
- compose 서비스 정의: loki 512m, alloy 256m (OB:66), alertmanager mem_limit 128m (OB:130).
- 유휴 실측(2026-11-04 로컬 compose, docker stats): loki 약 83MiB, alloy 약 53MiB. 부하 없는 값이라 상한은 그대로 [가정] (OB:195).
- Loki ruler(쿼리 4개/1분)의 loki 메모리·CPU는 부하 측정 때 docker stats로 전후 비교 예정, 아직 측정 안 함 (OB:193).
- reservation-service 힙 768MB(배포), 자동으로 Serial GC (OP:90).
- Loki 받기 한도 초당 16MB, 스트림당 8MB(기본 4MB/3MB) (OB:76) — 메모리 직접 수치는 아니나 로그 수집 한도.
- 발생기 EC2 c6i.xlarge 8GiB, 디스크 gp3 20GB (LT:27, LT:33).
- 측정 환경 기록표에 "JVM 힙(-Xmx)·컨테이너 메모리 상한(서비스별)" 항목 있음, 값 비어 있는 양식 (LT:127).
- payment_approve_seconds_bucket 시계열 수와 메모리는 부하에서 확인된 적 없음(미확인), 측정 때 함께 볼 항목 (LT:116).
- 서비스별 CPU·메모리 측정 도구(docker stats / cAdvisor)는 미정 (LT:111, LT:144).
- 배포 EC2 크기(SA U10)와 컨테이너 상한은 측정 결과로 정함 (LT:136).
- OOM, Redis 메모리, 카디널리티 수치: 해당 없음(카디널리티는 라벨 3개 제한이라는 설계 서술만, OB:68).


### 조각 D — 2. 메모리 관련 숫자 전부

| 항목 | 값 | 전/후 | 근거 |
|---|---|---|---|
| 배포 서버 메모리 | 15.7GiB(m7i.xlarge). 5차 M1 측정 직후 컨테이너 합 약 6.5GiB, 사용 가능 약 8.4GiB | — | OPS:311,790 |
| 컨테이너 메모리 상한(1차) | gateway 768MiB, reservation-service 1,152MiB, redis 512MiB | — | OPS:313 |
| JVM 힙(1차) | gateway -Xms256m -Xmx512m(G1), reservation-service -Xms384m -Xmx768m(G1) | — | OPS:314 |
| 발생기 | c6i.xlarge 8GiB | — | OPS:312 |
| Gateway 메모리 4차 끝 | 698MiB / 768MiB(1회, 힙 최대 413MiB), 637MiB / 768MiB(2회) | 상한 근접, 재시작·OOM 없음 | OPS:603 |
| 11/10 03:50Z dmesg OOM | java 약 754MB 컨테이너 OOM. Gateway였는지 확인 못 함 | — | OPS:611 |
| Gateway OOM(5차, 768MiB 상한) | 08:18:53, 08:25:46, 08:35:11 각 anon-rss 757~758MB, RestartCount 3 | 전(768MiB) | OPS:679-686 |
| 첫 OOM 직전 JVM | 힙 committed 10초 사이 256 → 471MB, 비힙 158MB(Metaspace 90, 코드캐시 약 50), 직접 버퍼 5MB, 합 629MB, 프로세스 실제 758MB와 차이 약 130MB(JVM 지표에 안 잡힘, 후보 Netty 풀 직접 메모리·GC 자료·스레드 스택은 추정) | — | OPS:688-692 |
| 메모리 추세 | 1,000/s 501MiB → 1,500/s 505 → 2,000/s 527 → 2,500/s 639MiB → 3,000/s OOM. 쉬는 구간에도 안 내려감 | 0a | OPS:693,640 |
| 해석 | 힙 512 + 비힙 158 + JVM 밖 130 ≈ 800MB > 768MiB. 힙을 키우는 것은 동시 처리 요청 수. Gateway에는 동시 연결 상한 없음 | — | OPS:695-697 |
| JFR 부담 | 1,000/s에서 501 → 535MiB(약 +30MiB) | — | OPS:712 |
| M1 후(운영 반영) | Gateway mem_limit 768 → 1,024MiB, `-XX:MaxDirectMemorySize=128m`, 힙 -Xmx512m 유지. OOM 0건, 메모리 최대 849MiB(옛 상한 초과), 남은 여유 약 175MiB. 웜업 3 최대 785MiB, 1,000/s 747MiB, 1,500 747, 2,000 748, 2,500 809, 3,000 839MiB | 전 OOM 3건(웜업 포함) → 후 0 | OPS:777,782-791 |
| M1 B안 거부 이유 | `-Xmx384m` 안은 첫 OOM 직전 힙 사용 341MB라 GC가 잦아질 위험 | — | OPS:777 |
| 처리 중 요청당 메모리 | 약 47KiB(거친 계산값), (768−500)÷0.047≈5,700개(실제 OOM 5,309~5,938과 일치), 1,024MiB면 약 1만 개 | — | OPS:817-819 |
| A1 상한 후(6차) | Gateway 메모리 모든 회차 511~540MiB 평탄, OOM·재시작 0(5차 0a 2,500/s 639MiB, M1 809MiB). 상한 64에서 메모리는 제약이 아님 | 후 | OPS:820,880 |
| 7차 | Gateway 메모리 510~519MiB 평탄, OOM 0 | 후 | OPS:975 |
| 8차 | Gateway OOM·재시작 0건 | 후 | OPS:1078 |
| S1 개선 전 | reservation-service 메모리 1.116GiB / 상한 1.125GiB(초당 10건), 1차(P4a)는 같은 속도에서 약 666MiB. 3회 도중 RestartCount 1(`OOMKilled=false`, dmesg 미확인) | 전 1.116GiB → 후(P4a) 666MiB | OPS:1164-1165 |
| S1 개선 전 단계 | "서비스 메모리·GC(CPU 약 220%, 메모리 1.11/1.125GiB)" | 전 | OPS:1201 |
| 결제 서비스 | 컨테이너 768MiB, Hikari 풀 20 | — | OPS:1250 |
| Redis 메모리·카디널리티 | 이 문서에서는 Redis 메모리 숫자 없음. Redis CPU(9~17%)와 컨테이너 상한 512MiB만 | 해당 없음 | OPS:313,602 |


### 조각 E — 2. 메모리 관련 숫자 전부

전/후 구분 있음.
- Gateway OOM 3회(상한 768MiB, `-Xmx512m`, 비힙 약 158MB, JVM 밖 약 130MB 미식별), 활성 VU 5,300~5,900 사망/2,931 생존(최대 488MiB) — 전(ISO:52).
- M1 반영 후: `mem_limit` 1024m, `-XX:MaxDirectMemorySize=128m`, 5차 조건 재실행 OOM 0, 메모리 최대 785MiB — 후(ISO:9,71).
- 상한 합 12,672MiB → 12,928MiB(장비 약 15.7GiB) — 전/후 계산값(ISO:31,71).
- 힙·mem_limit 표(`compose.deploy.yml`): gateway 768m(`-Xms256m -Xmx512m`), user 640m(`-Xms192m -Xmx384m`), reservation 1152m(`-Xms384m -Xmx768m`), payment 768m(`-Xms256m -Xmx512m`), mock-pg 512m(`-Xms128m -Xmx256m`), config-server·eureka 512m(`-Xms128m -Xmx256m`), assistant 384m(`-Xms96m -Xmx192m`), postgres-reservation 1024m, postgres-user·payment·redis 512m, kafka 1536m(`-Xms512m -Xmx1g`), prometheus·zipkin 768m(zipkin `-Xmx384m`). 앱 8개 `-XX:+UseG1GC`(ISE-193) (NUM:113-129). 주의: 이 표의 gateway 768m는 11/10 시점이며 M1 이후 1024m(ISO:9).
- 로컬 L1 측정 직후 reservation-service 509.7MiB/512MiB, Zipkin 구간 1,145개 버림(원인 여부 확인 못 함) (NUM:92).
- 배포 L1 직후 `docker stats`: reservation 699MiB/1.125GiB(약 61%), gateway 497MiB/768MiB(약 65%), redis 36MiB, postgres-reservation 183MiB (NUM:285).
- B3: kafka-ui 512 + assistant 384 = 896MiB (ISO:104). E3: PostgreSQL 3개 메모리 상한 합 2,048MiB(계산값) (ISO:113).
- M1 B안 계산: 384+158+130=672MB vs 768MiB(약 805MB) (ISO:71). A안 1,024MiB(약 1,074MB)−800MB 여유 약 270MB (ISO:71).
- Redis 메모리: L1 직후 36MiB. 카디널리티: 해당 없음. 시도 안 한 것: Redis 메모리 한도, Prometheus 시계열 카디널리티는 이 담당 문서에 없음(`lottery` 라벨 주의 사항만, NUM:492-494).
- JVM 옵션: `-XX:MaxDirectMemorySize=128m`(M1), `-XX:ActiveProcessorCount=1`(M5 제안, 미적용) (ISO:75).
- 확인 못 함: JVM 밖 130MB 정체(Netty 풀 직접 메모리 추정), JRE에 jcmd 없어 NMT 어려움 (ISO:163).


### 조각 F — 2. 메모리 관련 숫자 전부
해당 없음. 이 6개 파일에는 힙·OOM·컨테이너 메모리·Redis 메모리·JVM 옵션 숫자가 없다. 근접: 응모 30,000행 순위를 메모리에서 계산한다는 설계(options.md:132, 숫자는 행 수만). "Mock PG 원장·장애 설정은 메모리"(f1-fault-injection.md:32,34)와 "빌링키는 메모리에만(F1 당시)"만 언급. 입장 토큰 길이 상한 4,096자(contracts.md:18)는 메모리 한도가 아님.


### 조각 G — 2. 메모리 관련 숫자 전부

- 상담 서비스(assistant-service): 컨테이너 상한 +384m, 힙 192m은 가정이며 `docker stats`로 재야 함. 배포 EC2 실제 메모리는 확인 못 함(전/후 구분 없음, 가정값). 근거 docs/design/assistant/README.md:173, 220.
- app.jar 약 102MB(디스크 크기, 런타임 메모리 아님). docs/design/assistant/README.md:211.
- Mock PG 빌링키를 메모리 Set에 보관하던 시절(11/6 시연)의 재기동 소실 문제: 숫자 없음. 요청 제한도 메모리 보관이라 재시작하면 초기화(숫자 5/분·30/일). docs/design/demo/2026-11-06-mvp-scenarios.md:67; docs/design/assistant/README.md:131.
- 나머지 6개 파일(MVP의 힙·OOM, REV, REVP, CS, CM, CI, DS)에는 힙·OOM·Redis 메모리·JVM 옵션 숫자 없음(ai-code-review-prompt.md는 "배포 메모리 상한" 점검 항목만 있고 숫자 없음, REVP:23).


### 조각 H — 2. 메모리 관련 숫자 전부

전/후 구분: 아래는 전부 계획·가정값(측정 전)이거나 설정/환경 사양이며 실측 전후 비교는 이 담당 문서에 없음.

- 발생기 c6i.xlarge 메모리 8GiB(실측 아님, 사양): docs/guides/loadgen.md:14
- 발제 권장 Docker 메모리 4GB+, 로컬 JVM 9개 구성(우려): docs/sa-review/01-sa-1st-review.md:190
- 컨테이너 약 15개가 한 대에, 서비스별 -Xmx와 컨테이너 상한 합이 EC2 메모리의 70~80% 이하인지 확인 필요, 힙 외 메모리(메타스페이스·스레드 스택·네이티브) 때문에 컨테이너 상한은 힙보다 크게(수치 측정 전 미확인): docs/sa-review/04-mentor-feedback-1026.md:156
- 메모리 예산표(전부 [가정] 시작값, docker stats로 측정해 바꾼다): docs/sa-review/05-feedback-improvement-proposal.md:176-196
  - gateway 힙 512m / 컨테이너 768m
  - eureka 256m / 512m
  - user-service 384m / 640m
  - reservation-service 768m / 1,152m
  - payment-service 512m / 768m
  - mock-pg 256m / 512m
  - postgres x 3: reservation 1,024m, 나머지 512m씩, 합 2,048m
  - redis maxmemory 약 400m, 컨테이너 512m
  - kafka(KRaft) 힙 1,024m, 컨테이너 1,536m
  - kafka-ui 512m, prometheus 768m, grafana 384m, zipkin 768m
  - 합계 10,880m(약 10.6GB), 75% 이하 규칙이면 필요 약 14.5GB, 16GB급 인스턴스가 최소, 8GB급은 맞지 않음. 8GB를 써야 하면 zipkin·kafka-ui·prometheus 제외 또는 상한 축소
  - 상한 간격 약 256~384m가 충분한지 측정 필요
- 대시보드가 JVM 메모리를 보여 주고, 메모리 제한은 로컬 기동용이며 운영 수용량을 뜻하지 않음: docs/guides/workspace.md:140
- 배포 서버 m7i.xlarge의 메모리 용량은 이 담당 파일에 적혀 있지 않음(확인 못 함).
- OOM, G1 이외 JVM 옵션, Redis 실제 메모리 사용량, 카디널리티 숫자는 이 담당 파일에 없음. 단 G1 GC 적용으로 한계가 초당 5명 → 10명(로컬 스택, "배포 메모리·G1 덮어쓰기"): docs/guides/deploy-server-access.md:140
- Hikari 커넥션 풀 5 → 20 권고(메모리가 아닌 자원 설정): docs/sa-review/07-reservation-system-review.md:141-142
- 결제 테스트 DB 연결 한도 100 → 300: docs/guides/testing.md:94


### 조각 I — 2. 메모리 관련 숫자 전부

전/후 구분: 모두 설정값(설계). 측정된 메모리 사용량·OOM·힙은 이 파일 범위에 없음.
- Redis maxmemory 비교: 준현 128MB noeviction(AOF yes), 효승 400MB noeviction, 강민 400MB noeviction(RDB/AOF off) — B/infra-build.md:66. 400÷128=3.125배는 직접 계산.
- Redis TTL: Redis 선점 키 TTL 5분30초(DB 만료 5분 + 30초 여유), 결제 시작 시 PERSIST로 TTL 제거 — K:543-577.
- Redis 키 카디널리티: `auth:refresh:{userId}`(사용자당 1개), `auth:blacklist:{jti}`(TTL=Access 남은 시간, Access 30분) — B/auth-review.md:51-52. 수치적 카디널리티 계산 없음.
- 힙·OOM·컨테이너 메모리 한도·JVM 옵션: 해당 없음(compose.jvm.yml 파일이 존재한다는 근거 표기만 B/infra-build.md:94, 내용 수치 없음).


### 조각 J — 2. 메모리 관련 숫자 전부

- 힙·OOM·컨테이너 메모리·Redis 메모리·카디널리티·JVM 옵션: 해당 없음(8개 문서 어디에도 없음).
- 메모리에 두는 상태 하나: 빌링키 등록 횟수 제한은 `ConcurrentHashMap` 인메모리 슬라이딩 윈도 로그이고, 오래 쉰 사용자는 한 시간에 한 번 지워 맵이 사용자 수만큼 자라지 않게 한다. 재시작하면 초기화되고 인스턴스마다 따로 센다(data-model.md:234,236). 숫자 상한은 분 3회·하루 10회(J-28). 결제 서비스는 Redis를 쓰지 않는다(data-model.md:237). 메모리 크기 숫자는 없음. 전/후 구분 없음.
- 메모리는 아니지만 참고: 커넥션 풀 `maximum-pool-size` 5 → 20, `connection-timeout` 1000ms(J-59).


### 조각 K — 2. 메모리 관련 숫자 전부

전/후 구분이 있는 것과 없는 것을 나눔. 힙·OOM·컨테이너 메모리·Redis 메모리·JVM 옵션 수치는 이 세 문서에 없다.

| 항목 | 수치 | 전/후 | 근거 |
|---|---|---|---|
| Hikari 최대 풀 크기 | 5 → 20 (S-21 변경 항목, 서비스당 20은 SA 9-4) | 전 5 → 후 20(설계 수정, 배포 측정 값 아님) | events-and-reliability.md:176 |
| 배경 작업 동시 DB 연결 최대 | 8개 (Outbox 1 + 복구 2 + 정리 1 + Kafka 리스너 4) | 해당 없음(설계 합산) | events-and-reliability.md:175 |
| 허가 30에서 Hikari 활성 연결 | 최대 3개(풀 20), 대기 0 | 배포 측정, 허가 30 상태 | pg-integration.md:108 |
| 허가 30에서 JVM 스레드 최대 | 112 | 배포 측정 | pg-integration.md:126 |
| 인스턴스 증설 거부 이유 | "단일 EC2의 메모리 예산 밖" (숫자 없음) | 설계 판단 | pg-integration.md:126 |
| 대사 진행 상태(워터마크·남은 구간) | 메모리에 둠, 재시작하면 7일치 재추적 | 설계 | events-and-reliability.md:299 |
| 승인 지연 Timer 시계열 수·메모리 | 부하에서 `payment_approve_seconds_bucket`의 시계열 수와 메모리는 미확인 | 확인 못 함 | observability.md:57 |
| DB 집계 Gauge | 쿼리 결과를 메모리 스냅숏으로 보유, 15초 갱신 | 설계 | observability.md:53 |
| Redis 메모리·힙·OOM·컨테이너 메모리 한도·JVM 옵션 | 해당 없음 | — | — |
| 카디널리티 | 지표 라벨은 낮은 카디널리티만, paymentId·userId 등 금지, `MetricLabelsTest`로 확인 | 설계 | observability.md:32 |


### 조각 L — 2. 메모리 관련 숫자 전부

| 항목 | 숫자·내용 | 전/후 | 근거 |
|---|---|---|---|
| mock-pg 힙 | `-Xmx256m` (원장에 축출이 없어 수십만 건이 쌓이면 메모리 부족으로 재시작되어 원장이 조용히 사라질 수 있다. "추측, 측정 전", 부하 테스트 전 reset, 요약에 건수 노출로 확인) | 측정 전(추측) | docs/design/payment/mock-pg.md:75 |
| 원장 크기 제한 | 맵에 축출 없음 | 현재 상태 | docs/design/payment/mock-pg.md:75 |
| 빌링키 Set(변경 전) | 메모리 Set에 크기 제한이 없었고 컨테이너 재기동 때마다 빌링키 무효 → 서명 방식으로 바꿔 메모리도 늘지 않음(저장 안 함) | 전 → 후 | docs/design/payment/mock-pg.md:340 |
| 거래 ID 길이 | 약 100자, DB 컬럼 varchar(200) | 설계값 | docs/design/payment/mock-pg.md:350 |
| 서명 거래의 메모리 | 서명 거래 환불 되살리기는 원장에 저장하지 않는 서명 확인이고, 환불 성공 때만 paymentsByTx에 항목·누계가 남음 | 설계 | docs/design/payment/mock-pg.md:254,354 |
| Hikari 풀 | 20(SA 9-4) | 설계값(DB 연결, 메모리 아님) | docs/design/payment/manual-recovery.md:505 |
| Redis·카디널리티·컨테이너 메모리 한도 | 해당 없음 | — | — |


### 조각 M — 2. 메모리 관련 숫자 전부

- 구 기기 P0(5-1): reservation-service 컨테이너 512MB, `-Xms64m -Xmx256m`(Compose 공통값), 메모리가 작아 JVM이 Serial GC를 자동 선택, Hikari 풀 5. (전) docs/design/reservation/k6-seathold-bottleneck-plan.md:311
- P0 무너진 순서: 힙이 차서 Major GC 연속. 측정 구간 6분 중 Major GC 정지 합계 101.5초, 1회 최대 29.1초. 좌석 조회 1건이 약 830KB JSON(초당 1명 60건에서 받은 바이트 약 49.9MB). 초당 1명에서도 60초 GC 정지 8.1초(약 11%), 힙 최대 206MB. :330-339
- P0' 배포 메모리(5-2): reservation 컨테이너 1,152MB·`-Xms384m -Xmx768m`, gateway 768MB·`-Xms256m -Xmx512m`(`compose.deploy.yml`, SA 9-3 표, 16GB급 EC2 근거). 측정 7분 GC 정지 Major 약 108초·Minor 약 133초, 힙 최대 707MB(상한 768MB), 정지가 시간의 절반 초과, 부하 뒤 컨테이너 메모리 1.115GiB(상한 1.125GiB)에 붙고 CPU 170%, 재시작해야 돌아옴. 서버 안 조회 200 4,055건 평균 25.6초(최대 30.9초). :367-399
- 모든 서비스를 배포 값으로 올리면 상한 합계 13GB 초과, 구 기기 Docker Desktop 메모리 7.6GB라 측정 경로 두 서비스만 변경. :373
- JVM은 메모리 약 1,792MB 미만이면 서버급이 아니라고 보고 Serial GC를 선택. 배포 reservation(1,152MB)도 Serial. :375
- P0''(G1): 리전 1MB, GC 정지 합계 약 241초(P0') → 81.8초, 최대 0.9초, 4,094회 중 4,031회가 50ms 미만, Full GC 2회(0.29초·0.37초), 앱 CPU 평균 약 6코어. :419,423-433
- 새 기기: Docker VM 32코어·16GiB, 컨테이너 메모리는 배포 값(gateway 768MB, reservation 1,152MB), CPU 상한 없음. k6 메모리 상한 2.5GiB → 6GiB(`GOMEMLIMIT` 5632MiB). base L·S에서 k6 메모리 약 300MiB. :497-499
- 단계표 GC 정지 비율: base 약 75%(표)·약 76%(비교표), G1 7%, F 1% 안팎. :514,529
- 좌석 응답 약 830KB, 배치 공유 Redis 안은 요청마다 1.3MB 이동(p95 7.0 → 38.3ms, 한 건 CPU 약 4.5배). STORY :59,164
- 4코어 상한 F: Gateway가 1,280명 단계 끝에 메모리 상한 768MB 초과로 OOMKilled. 힙 512MB + 비힙 + Netty 직접 메모리(기본 상한 512MB)가 768MB를 넘을 수 있는 설정. 소켓 받기 버퍼 최대 약 590MiB, JDK 직접 버퍼 최대 183MB. 3회 재측정: 강제 종료 1/3, 메모리 상한 도달 3/3, 나머지는 스왑으로 생존(좌석 조회 p95 최대 5.8초). 운영 차단기(P) 4코어 Gateway 657MiB 생존, 상한 없음 P 566MiB 생존, 시험 차단기(T) 4코어는 상한 1,089회 도달·스왑 생존, 상한 없음 T는 OOMKilled. STORY :164,173
- 위 4코어 OOM 설명은 "~것으로 본다"(추정)로 원문 표시. 원인 분석 문서는 docs/learning/kwonwoo/qna/2026-11-11-gateway-oomkilled-under-cpu-limit.md(미읽음, 참조만).
- Redis 메모리·카디널리티·힙 JVM 옵션(JFR 등): Redis 메모리 사용량 숫자는 해당 없음. JFR 옵션은 `-XX:StartFlightRecording`(기본 설정)을 판단하는 회에만 켠다는 설계만 있음(PLAN :236).
- LOOP 문서: 메모리 숫자 해당 없음(풀 5·Tomcat 200 스레드만 있음).
- 전/후 구분: 메모리 3배(256MB 힙 → 768MB 힙)로 한계 1~2명 → 5~10명/초(전: 구 기기 P0, 후: P0'), G1 전환 후 정지 241 → 81.8초. 새 기기 F에서 GC 정지 비율 약 76% → 1% 안팎.


### 조각 N — 2. 메모리 관련 숫자 전부

전/후 구분.

- JVM 힙 (전→후): 힙 256MB(Serial GC, P0 2026-11-04) → 힙 768MB(Serial GC, P0' 2026-11-05) → 힙 768MB(G1, P0'')로 바꿔도 회복 못 함. docs/design/reservation/k6-overload-recovery.md:21-23
- 컨테이너 메모리: P0' 메모리 1.115GiB(상한 1.125GiB) 근접, CPU 170%. 같은 문서 :22
- 컨테이너 메모리와 GC 선택: reservation-service 컨테이너 1,152MB(배포), JVM이 컨테이너 메모리 약 1,792MB 미만이면 Serial GC 자동 선택 → `-XX:+UseG1GC` 명시. `-Xms384m -Xmx768m`. docs/design/reservation/k6-contract-proposals.md:63-65
- local 측정에서 R1 단계의 컨테이너 메모리는 "1.12GiB/1.125GiB까지 감" (경계). docs/design/reservation/k6-deploy-steps.md:22
- GC 정지 비율(전→후): 새 기기 base 부하 시간의 약 75% → G1 7.3%, 이전 기기 66~69% → 24.5%, P4a 이후 1~3%. G1 대가: 힙 768MB에서 R1 S 320명 `Evacuation Failure`. docs/design/reservation/k6-contract-proposals.md:66-67
- 서버 처리 메모리 압박: 힙(768MB)이 짧게 사는 큰 객체로 빨리 차 GC 17배 잦아짐. 할당 초당 약 259MB(로컬 캐시) → 약 1.85GB(Redis). GC 로컬 527회 1.5초 vs Redis 8,797회 21.8초·최대 367ms·Full 2회(L), 직접 M Full GC 1회 vs 115회, 4코어 S Redis 6,859회 46.2초·Full 325회. docs/design/reservation/k6-p4b-local-vs-redis.md:36-37, :53-54, :66, :82
- Redis 메모리: `maxmemory 128mb`, `noeviction`, AOF. 메모리 최고 132.68MB로 한도 초과, SET 34건 거절. 배치 값 크기 1,344,751바이트 TTL 1시간. Redis 내보냄 74.1GB(초당 약 247MB). docs/design/reservation/k6-p4b-local-vs-redis.md:24, :38, :54, :22
- 로컬 캐시 크기: 배치 15,000석 ConcurrentHashMap 만료 없음. docs/design/reservation/k6-p4b-local-vs-redis.md:21
- 측정 장비 메모리: 로컬 RAM 15.7GB, Docker VM 약 7.6GiB(concurrency-results.md:14-16), 노트북 Docker Desktop 7.6GB 중 메모리 부족으로 R1 3회차가 시작 13초 만에 멈춤(k6-overload-recovery.md:219, :260-262). k6 메모리 상한 낮음 → 차단기 거절 약 41,000건, 6GiB로 올려 해소 (TS-69). k6-contract-proposals.md:29; k6-p4d-review.md:17. 직접 M 로컬 1,280명은 k6 메모리 상한으로 못 잼. k6-p4b-local-vs-redis.md:103
- Gateway 메모리: 배포 1,024MB에 직접 메모리 상한 128MB, local CPU 상한 측정에서 OOMKilled(768MB) 사례 있음. docs/design/reservation/k6-deploy-steps.md:31
- DB 커넥션 메모리 아님이나 한도 계산: 풀 크기 × 인스턴스 ≤ PostgreSQL `max_connections`(기본 100). docs/design/reservation/k6-contract-proposals.md:82
- 해당 문서 안에 카디널리티 숫자는 없음.


### 조각 O — 2. 메모리 관련 숫자 전부

- 이 12개 파일에는 JVM 힙(`-Xmx`)·OOM·컨테이너 메모리·Redis 메모리 한도의 실제 숫자가 없다. 측정 환경 기록표 항목으로 "CPU·메모리·JVM `-Xmx`·커넥션 풀·시나리오·커밋 해시"를 적도록 계획만 있다(R/linear-issues-after.md:381). 값은 없음.
- 관련 숫자(메모리는 아니지만 자원 한도): Tomcat 작업 스레드 기본 200개(R/seathold-pre-deploy-measurements.md:232), 커넥션 풀 5, 상한 4, 대기 인원 64(R/seathold-pool-limit-sweep.md:202), 지표 시계열: HTTP 히스토그램 조합당 9개(버킷 8+`+Inf`), `percentiles-histogram`은 약 70개라 미사용(R/seathold-metrics.md:126), `seathold_result_total` 시계열 9개(R/seathold-metrics.md:142), 계측 보강 Timer 두 개 시계열 약 20개(R/seathold-pre-deploy-measurements.md:156, 64), 배포 Prometheus가 앱과 같은 호스트에서 동작(R/seathold-metrics.md:182).
- 로컬 환경 32코어 한 PC(R/seathold-pre-deploy-measurements.md:31), 배포는 4 vCPU로 서술(R/seathold-pre-deploy-measurements.md:33).
- 전/후 대비 가능한 메모리 숫자 없음. 메모리 관련은 사실상 "해당 없음".


### 조각 P — 2. 메모리 관련 숫자

- 프론트 컨테이너(nginx) 메모리 약 11MiB, 제한 64MiB(프로덕션 이미지, 캐시 끔 측정). 근거 design-system.md:343. 성격: 로컬 측정.
- 브라우저 쪽: 요청 기록은 메모리에 최근 50건만 둠(README.md:341). 임시 비밀번호는 화면 상태에만 두고 저장하지 않으며 `gcTime: 0`(README.md:393). 숫자 측정은 없음.
- 그 밖의 서버 메모리·JVM 숫자는 이 담당 문서에 해당 없음.


## 4. (조각별) 설계 의도와 목표 숫자

### 조각 A — 3. 설계 의도와 목표 숫자

| 항목 | 목표·가정 숫자 | 근거 | 실제 측정 결과가 있는 문서 |
|---|---|---|---|
| 선점 부하 측정 | 동시 100명, 1초 안에 시작, 각 10회, 지표 평균·처리량·성공률(+p95). 목표는 고정 처리량이 아니라 동기형 → 접수형 비교와 포화점 | `docs/core/SA MASTER.md:292, 308` | 이 담당 파일에는 없음. 문서 지도가 가리키는 `docs/design/reservation/k6-seathold-story.md`, `docs/design/reservation/seathold-structure-improvements.md`(`docs/README.md:16`) |
| 옛 목표 | 1차 목표 처리량 200/sec [가정] → 폐기 | `docs/core/SA MASTER.md:2401` | — |
| 데이터 규모 | 공연장 시드 1,500석, 1공연 2회차 | `docs/core/SA MASTER.md:333, 2051` | ADR 0060(`docs/decisions/0060-seed-venue-1500-seats.md`, 파일명은 SA 본문 인용) |
| 오픈 시각 | 평일 오후 8시 [가정], 선점 5분 [가정] | `docs/core/SA MASTER.md:215` | — |
| 입장 | N=50명/초(임시), T=10분, 상한 30,000명, 역산 예 6.7명/초 | `docs/core/SA MASTER.md:339-342, 889` | 역산은 측정 후 하기로 함(결과는 이 문서에 없음) |
| 타임아웃 예산 | Gateway 3초, PG 연결 0.5초·응답 2초, DB 연결 대기 1초, lock_timeout 500ms, Redis 100ms | `docs/core/SA MASTER.md:879-884, 898` | — |
| TTL·시한 | 선점 5분, 결제 토큰 선점−10초, 입장 토큰 10분, 차례 1분, Access/Refresh 30분/14일, 멱등키 24시간, 접수 요청 30초, 캐시 공연 5분·좌석 1초 | `docs/core/SA MASTER.md:142, 885-891, 893-905` | — |
| 반영 지연 | 결제 → 예매 확정 2초 이내(Outbox 폴링 500ms), 접수형 접수 → 처리 완료 2초 이내 | `docs/core/SA MASTER.md:892-893` | — |
| 추첨 SLO | 응모 마감 직전 초당 100건, p95 300ms | `docs/core/SA MASTER.md:2274` | 11/10 L1 p95 10.58ms 요약만 있음. 원본 `docs/design/lottery/numbers-and-measurement.md` 3-3(`docs/core/SA MASTER.md:19`) |
| 외부 한도 | 외부 API 한도 숫자 없음(Mock PG, 토스 테스트 모드는 Should) | `docs/core/SA MASTER.md:416, 525` | — |
| 장애 격리 | PG 장애 중 선점·예매 정상, 결제는 CB OPEN으로 즉시 거절, 복구 후 CLOSED. Redis 지연 시 대기열만 503 | `docs/core/SA MASTER.md:296` | — |
| 메모리 예산 | 합계 11,392m, 16GB급 최소 | `docs/core/SA MASTER.md:867-868` | docker stats 실측은 첫 부하 측정 후(U21, `docs/core/SA MASTER.md:2275`), 결과는 이 문서에 없음 |


### 조각 B — 3. 설계 의도와 목표 숫자

| 목표·가정 | 값 | 근거 | 실제 측정 결과 문서 |
|---|---|---|---|
| 평상 서버 CPU 목표 | 1,500/s에서 90% 미만(`[가정]`, 강민) | STORY:130,33 | open-peak-scenarios.md 3절(배포 서버 1~8차), 본 STORY 누적 표 |
| 과부하 판정 | 2,000/s 이상은 p95·OOM 0·굿풋 | STORY:33 | 상동 |
| 굿풋 정의 | 200 응답이면서 200ms 안 | STORY:60 | — |
| 굿풋 p95 목표 | 200ms | STORY:160 | — |
| 필터 시한 SA 9-4 | 100ms `[가정]` → ADR 0050에서 200ms | README/RES:17,69 | RES 5-1 |
| 벌크헤드 | 25(기본) → 50 + 거절 제외(ADR 0050) | RES:71 | RES:78-87 |
| 동시 처리 수 상한 | 64(리틀의 법칙 60~67) | STORY:161,167 | STORY:163 |
| 배포 EC2 | 16GB급, SA 9-3 합계 약 12GB 75% 이하 규칙 | DEP:403-408 | DEP:383,398 |
| 서비스 발견 | 최악 15초 | DEP:190 | 합계만 잼(35초, 55초) |
| 예열 | 25/s × 120초 | DEP:160 | 효과 확인 못 함(DEP:213) |
| 인증서 갱신 | 만료 48시간 전 알림, 수명 절반 남으면 갱신 | DEP:301-302 | — |
| 좌석 수 | 회차마다 1,500석(ADR 0060 전 15,000) | DEP:37 | — |
| 목표 처리량 | 1,000/s(벌크헤드 50 산정), 1,200/s(21ms 계산) | RES:71,55 | — |
| 부하 측정 범위 | G1 1,000~3,000/s | STORY:103,117 | — |


### 조각 C — 3. 설계 의도와 목표 숫자

- 오픈 피크 가정: 회차 1개, 15,000석(11/16부터 측정 시드 1,500석, ADR 0060), 30,000명 대기열 진입, 입장 속도 N=50/초 (OP:16). 근거는 SA 3-3 입장 속도 역산 (OP:7).
- 입장 후 10분에 걸쳐 대기열이 빔: 30,000/50 = 600초 (OP:22).
- 결제 목표: 결제→확정 2초 이내(SA 9-4) (OP:278). 추첨 자동결제 목표 10분 안에 95% (OP:331).
- 폴링: 기본 3초, 최대 10초, jitter 0~20% (LT:83, OP:175). 토큰: Access 유효 30분 (LT:89), 입장 토큰 유효 10분 (OP:187).
- 오류 없이 버틴 최대 속도 판정 기준: 오류율 1% 미만 (OP:61).
- 요청 로그 problems 모드 기준: 500 이상, 200ms 이상 (OB:53).
- 알림 반복: 4h, immediate 1h, group_wait 30s, group_interval 5m (OB:120-121).
- Loki 보관 7일 (OB:18).
- Mock PG 기준 지연 300ms (LT:94, OP:412).
- 측정 추적 샘플링 0.01 (LT:92).
- 실제 측정 결과가 있는 문서: OP는 가설이며 결과 문서는 `docs/design/reservation/k6-seathold-bottleneck-plan.md`, `docs/design/reservation/k6-overload-recovery.md`, `loadtest/k6/README.md`(OP가 인용, 사본 범위 밖), `docs/design/infra/open-peak-scenarios.md`(OP:10, 3절). LT:10의 11/9 Q1~Q3 측정 결과(지표 78개 수신 확인)는 결과 수치 자체가 이 파일에 없음.


### 조각 D — 3. 설계 의도와 목표 숫자

| 항목 | 숫자 | 근거 | 측정 결과가 있는 문서 |
|---|---|---|---|
| 오픈 시각 진입 | 30,000명 10초(초당 3,000), 확장 60,000·120,000명 | OPS:141 | 본 문서 Q1 결과(OPS:323-343) |
| 폴링 | 계산값 약 2,700건/s, 간격 clamp(순번/50초, 3초, 10초)×(1+0~20%) | OPS:161,352 | OPS:345-355 |
| 입장 속도 | N=50/초, 45~50인 초 95% 이상 | OPS:171,178 | OPS:357-363 |
| PG 동시 허가·지연 | 10, Mock PG 300ms → 초당 약 33건 | OPS:198 | OPS:1241-1281 |
| 승인→확정 | 2초(SA 9-4), p95 | OPS:206 | 측정 전 |
| 응모 | 초당 100→800, 좌석 3,000석, 예비 1,000명 | OPS:239-240 | 부분(OPS:244, local) |
| 자동결제 | 당첨 2,000명, 10분 안 95% | OPS:248,264 | 측정 전, local 3.10건/s |
| 좌석 규모 | 15,000석 → 1,500석(ADR 0060, 11/16) | OPS:43,286 | 11/16 전 측정은 15,000석 |
| O1 | 30,000명, 응답 Gateway 3초 안·오류 1% 미만·회복 30초 | OPS:282-299 | 측정 전 |
| Gateway 인증 한도 | p95 100ms 이하·오류 1% 미만, 차단기 미열림 | OPS:76-78 | OPS:567-613(4차)~1029-1127(8차) |
| 상한 기본값 | 64(기본), 128 제안 | OPS:920-934 | OPS:836-946, 1231 |
| PG 허가 | 10 → 30 검토, 대기 0 유지 | OPS:1327 | OPS:1283-1332 |
| 측정 공통 | 샘플링 0.01, 워밍업 30초 10%, 표준 워밍업 100/s 2분→1,000/s 30초→3,000/s 10초 | OPS:34-35,321 | — |


### 조각 E — 3. 설계 의도와 목표 숫자

- 규모 가정(NUM:11-19), 시간(NUM:23-30), 결제 용량(NUM:36-40)은 모두 `[가정]`. 응모 30,000명은 SA 3-2 참고 수치(동시 접속 수십만~백만)보다 작게 로컬 측정 가능한 규모로 잡음(NUM:17).
- 시나리오 합격 기준(NUM:49-53): 시나리오 1 p95 300ms 이하·처리 완료 5초; 시나리오 2 자동결제 10분 내 95%·일반 결제 503 증가 없음; 시나리오 3 선점 p95 증가 20% 이하.
- Synthetic 설계 목표: 균일 부하 대비 사용자 다양성 효과 측정, 3,000명, VU 300(SYN:113-120). 판정 기준은 측정 전 고정(SYN:126-160).
- 처리량에 고정 목표 없음(결정 #47)(SYN:147).
- 실제 측정 결과 문서: NUM(추첨), `docs/design/infra/open-peak-scenarios.md`(5·6차), `docs/presentation/performance/2026-11-15-o1-synthetic-vs-uniform-source.md`(SYN 원자료).


### 조각 F — 3. 설계 의도와 목표 숫자

| 항목 | 값 | 근거 | 실제 측정 결과가 있는 문서 |
|---|---|---|---|
| 동시 응모 목표 | 마감 직전 초당 100건 `[가정]` | lottery/README.md:35 | numbers-and-measurement.md(담당 밖). 이 파일군에서는 README.md:190,203에 L1 결과 요약 |
| 당첨자 규모 | 약 2,000명(자동결제 2,000건), 예비 상위 1,000명, 응모 30,000행 `[가정]` | options.md:119,132,169 | 담당 밖 |
| 추첨 트랜잭션 상한 | 10초, lock_timeout 3초 | options.md:133; details.md:71 | 미측정(README.md:204) |
| 자동결제 목표 | 10분 안 95%, 첫 시도 약 4분 | details.md:107 | L2-a 93.1·92.4·93.4%(options.md:95), 재측정 11/13 예정 |
| 선점 만료 | 생성+30분, 연장 1회(상한 승계 마감+30분) | options.md:174,203 | F1-b 연장 4/4건(f1-fault-injection.md:147) |
| 승계 마감 / 종결 | T+30분 / 최장 T+60분 | details.md:101-102 | e2e-runbook.md:92 종결 확인 |
| 시나리오 3 기준 | 일반 예매 선점 p95 20% 초과 시 분리 | README.md:38 | 담당 밖 |
| 추첨 시각 정책 | 저트래픽 03:00~05:00 `[가정]` 경고만 | details.md:18 | — |
| 입장 토큰 TTL | 10분(`[가정]`), turn-ttl 1분 | contracts.md:29,74 | — |
| 재조회 | UNKNOWN 재조회 최장 약 21분, REQUESTED 10초, 스케줄러 1초마다 최대 20건 | details.md:99; f1-fault-injection.md:64 | F1-a(NOT_SENT 걸린 건 0) |


### 조각 G — 3. 설계 의도와 목표 숫자

| 항목 | 숫자 | 근거 | 실제 측정 결과 문서 |
|---|---|---|---|
| 상담 질문 길이 | 1~500자 | AS:26 | 해당 없음 |
| 상담 시한 | 첫 조각 8초, 전체 20초, 안전망 30초, Gateway 라우트 10초 | AS:74-75, 134 | 실제 키 확인 첫 조각 0.8~1.4초, 전체 1.3~2.1초(AS:96) |
| 상담 사용량 제한(가정) | 5/분, 30/일 사용자, 전체 1,000/일, 동시 4 | AS:130-133 | 측정 없음 |
| 11/6 시연 규모 제안 | 120명, 추첨석 30 / 예비 15 | MVP:46-53 | 로컬 리허설 MVP:206-213 |
| 입장 속도 | 초당 50명 | MVP:188 | 측정 안 함(11/6은 부하 측정 안 하기로 함) |
| 선점 시간 | 기본 5분, 만료 스케줄러 5초 주기 | MVP:198 | F-05 로컬 실측 |
| PG 호출 시한 | 연결 0.5초, 응답 2초 | MVP:200 | F-07 2,022ms |
| 서킷브레이커 | 최근 20회 중 50% 실패로 OPEN, 10초 뒤 복구, 시험 호출 3건 | MVP:225; DS:30 | F-09 로컬 |
| 회차 좌석 | 1,500행(ADR 0060 전 15,000행) | MVP:179 | 서버 생성 시간 못 잼 |
| 추첨 승계 마감 | 추첨 + 30분 | MVP:213 | L-08 시각 조정 |
| Outbox | batch 20, lease 30s, send-timeout 12s, interval 500ms | CM:114 | 해당 없음 |
| 소비 재시도 | 1·2·4초 3회 | CM:74 | 해당 없음 |


### 조각 H — 3. 설계 의도와 목표 숫자

- 입장 속도 초당 50명, 토큰 TTL 10분(600초) → 최대 동시 입장 30,000명(상한), 좌석 약 15,000석(회차당)보다 2배. 근거: 리틀의 법칙. 문서 04(2026-10-26)가 짚고 05가 [가정] 값으로 역산(μ = 200, r = 0.2, W = 150초 → λ ≈ 6.7명/초). 실제 측정 결과는 다른 담당 문서(대기열·성능 문서)에 있을 것이나 이 담당 파일에는 없음.
- 좌석 선점 API 부하 목표(원문 codex 인용): 100명, 1초 안에 모두 시작, 각 10회, 200/sec는 권장 목표이며 미달도 전후 비교로 인정. 근거: docs/sa-review/raw/codex-review.md:111-120.
- K1: 초당 100명 입장 → 좌석 조회 → 선점. docs/guides/deploy-server-access.md:140.
- 선점 TTL 5분(결제 토큰 만료는 선점 만료 10초 전, 결제 가능 4분 50초): 05:47-49. Redis 키 TTL은 DB 만료보다 길게(5분 + 30초): 03:166.
- PG 응답 타임아웃 2초, Gateway 요청 타임아웃 3초: 04:97, 05:47.
- UNKNOWN/REQUESTED 재확인: 10초 × 6회 → 1분 → 5분 → 15분 → NEEDS_MANUAL_CHECK(약 21분): 05:112.
- Redis 호출 타임아웃 100ms, 서킷 20회 중 50%·50ms, OPEN 10초 후 HALF_OPEN 5회: 05:118-121, workspace.md:120은 100ms 구현 반영.
- Access 30분, Refresh 14일, 로그인 5회 실패 잠금: workspace.md:97, :113.
- 사용자별 회차당 최대 4매(G7 "4매 한도"), 선점 락 lock_timeout 500ms, Hikari 풀 20(SA 9-4): 07:41, :120, :22.
- 추첨 승계 마감은 일반 오픈 30분 전(제안): 01:158.
- 일정: MVP 9평일, 트러블슈팅 6평일(11/9~16), 11/6 시연, 11/16~18 정리·브로셔, 11/19 발표, 서버 11/13~11/19 가동: 01:71, raw/codex-review:134, deploy-server-access.md:166-167.
- 실제 측정 결과가 있는 문서: 이 담당 파일에서는 loadgen.md:134(연기 테스트 11/9)와 deploy-server-access.md:140(K1 G1 한계 5 → 10명)뿐이며, 나머지 측정은 docs/design/**·docs/presentation/** 등 다른 담당 문서에 있을 것(확인 못 함).


### 조각 I — 3. 설계 의도와 목표 숫자

- 인증 TTL·한도: Access 30분, Refresh 14일, 로그인 5회 실패 잠금 — B/auth-review.md:47-52 (설계값, 측정 결과는 다른 문서 영역).
- Redis 호출 타임아웃 100ms·서킷 최소 호출 20: 결정 #48에서 100ms는 "잠정값, 측정으로 조정" — B/source-notes.md:35, B/auth-review.md:97. 실제 측정 결과는 이 파일 범위에 없음.
- 좌석 선점 설계 숫자: 파티션 3개(로컬 단일 브로커), Consumer concurrency 3, 홀드 5분, Redis TTL 5분30초, 요청 1,000건 = 100명 × 10회(발제 부하 설정) — K:350, 371, 556, 622. 근거는 "로컬 단일 브로커, Reservation 1대 × Consumer concurrency 3".
- 실제 측정 결과 문서 경로: 이 파일 범위에는 없음(부하 측정 아님). 참조 가능 문서는 B/infra-build.md:96처럼 별도 검토 문서를 가리키는 링크뿐.
- 팀 안건 12건(D01~D12)과 추천(미채택): B/team-decisions.md. 통합 후 검증 기준(단계별 통과 기준): B/integration-plan.md:49-59.


### 조각 J — 3. 설계 의도와 목표 숫자

| 숫자 | 근거 | 측정 결과 문서 |
|---|---|---|
| Gateway 응답 시한 3초(SA 9-4), 승인 최악 처리 TX1 1초 + PG 2.5초 + TX2 1초 | api.md:58 | 이 폴더에 부하 측정 없음. 테스트 확인은 failure-scenarios.md:134,165-168(3초 안 503) |
| 재확인 10초 간격, 호출 9회 소진 뒤 NMC | domain-and-states.md:261,281 | — |
| 환불 워커 9회·약 21분 | invariants.md:75 | — |
| 예매 확정 반영 목표 2초(SA 9-4) | api.md:59 | 이 폴더에 측정 없음 |
| 대사 허용 지연(테스트 5초 `[가정]`, 시나리오 검증기 10분) | failure-scenarios.md:63,96 | — |
| 빌링키 분 3회·하루 10회 | data-model.md:233 | — |
| 결제 기록 5년 보관, `processed_events` 14일, Outbox 7일 | data-model.md:164,241, sa-change-requests.md:164 | — |
| 멱등 기록·토큰 만료 후 조회 24시간 | data-model.md:139, contracts.md:44 | — |
| 동시 접속·좌석 수·TPS·p95 목표 | 해당 없음(이 8개 문서에 없음). contracts.md:92가 "D6 기준선", "측정 시나리오 2"를 언급하나 수치는 없음 | 다른 문서(미확인) |


### 조각 K — 3. 설계 의도와 목표 숫자

| 목표·가정 | 근거 | 실제 측정 결과가 있는 문서 |
|---|---|---|
| 대기열 입장 초당 50명(admit-per-second 50) → 필요 허가 ≈ 35 | pg-integration.md:98 | `docs/design/infra/open-peak-scenarios.md` 3절 PAY1(원문이 가리킴, 이 담당 밖) |
| Gateway 응답 시한 3초, 승인 1건 시간 예산(TX1 1초+PG 2.5초+TX2 1초) | pg-integration.md:128 | 11/12 재기동 직후 p99 1.0~1.4초 측정 (events-and-reliability.md:191) |
| PG 응답 지연 기준 300ms(Mock), PG p99 0.35초 가정 | pg-integration.md:93, 98 | pg-integration.md:102-107 PAY1 표 |
| 재확인 자동 복구 상한 약 22분(1320초), 이후 운영자 확인 | events-and-reliability.md:143 | 없음(설계값) |
| Outbox 임대 30초, 전송 시한 12초, 지연 상한 60초 | events-and-reliability.md:15, 33, 35 | 로컬 compose 5분 9초 지연 알림 시험(observability.md:101) |
| 알림 기준 REQUESTED 60초, UNKNOWN 5분, NMC 30분/24시간, Outbox 5분/30분, 환불 대기 30분/2시간 | observability.md:61-76 | 없음(초기값은 가정, 부하 테스트에 맞춰 조정 예정) |
| 보관: outbox SENT 7일, processed_events 14일, 멱등 기록 24시간, `.dlq` 7일`[가정]` | events-and-reliability.md:206-210, 100 | 없음 |
| 대사 주기 5분, 허용 지연 10분, 7일 윈도우 | events-and-reliability.md:238, 245 | 로컬 ISE-239(간격 30초 덮어씀), 배포 11/12 ISE-254 |
| 추첨 자동결제 2,000건, 소비자 3개 시 계산 초당 9.51건 | events-and-reliability.md:66 | 추첨 쪽 배포 재측정(이 담당 밖) |
| 토스 멱등키 유효 15일 | pg-integration.md:64 | 없음 |


### 조각 L — 3. 설계 의도와 목표 숫자

- 두 문서 모두 동시 접속·좌석 수·SLO·TPS 같은 성능 목표는 없다. 해당 수치는 다른 문서(부하 테스트 문서)에 있을 것이나 이 담당 범위 밖이라 확인 못 함.
- 설계 의도 숫자: 공통 계약 테스트 23개(mock-pg.md:5,9), 어댑터 고유 테스트 8개 중 6 이관·2 비이관(mock-pg.md:11-22), 장애 주입 delayMs 1~60,000ms(mock-pg.md:128), 결제 토큰 수명 최대 4분 50초(manual-recovery.md:524), DLQ 보관 7일 `[가정]`(manual-recovery.md:376), 대사 RESOLVED 조회 7일(manual-recovery.md:52), Hikari 20(manual-recovery.md:505).
- 근거: 결제 수동 복구는 ADR 0028, INV-7. Mock PG는 SA 6-6·11-4·11-5, S-28·S-29, INV-1·INV-2·INV-8, ADR 0023.
- 실제 측정 결과 문서 경로: 이 두 문서엔 없음. 해당 PG 지연·장애 주입 부하 측정은 확인 못 함.


### 조각 M — 3. 설계 의도와 목표 숫자

| 항목 | 값 | 근거 | 실제 측정 결과가 있는 문서 |
|---|---|---|---|
| 입장 속도 | 초당 100명, 5분, 총 30,000명(사용자 지정 2026-11-04, 1분 → 5분) | PLAN 17행. `queue.admit-per-second` 현재 50(SA 9-4 임시값) | 같은 문서 5-5절, STORY 4~6절 |
| 좌석 | 회차 1개 15,000석(SA 3-2), 30,000명×1.5석 = 약 45,000석 수요, 70%가 앞 구역 10%(1,500석) 선택 | PLAN 19~21행 | M-03(매진 189초) |
| 선점 만료 | 선점 유지 5분(holdMinutes 5), 측정 중 스케줄러 끔 | PLAN 144행 | STORY 5절(만료 켠 측정 1분 유지) |
| Gateway 시한 | 3초(SA 9-4), Gateway `connect-timeout` 1,000ms, 커넥션 대기 기본 30초 → R1 1초 | PLAN 23,391,246행 | M-10 |
| 임계값 `[가정]` | 오류 1% 미만, 조회 p95 500ms, 선점 p95 1,000ms, p99 1,000ms | PLAN 61~65,113행 | k6 요약 결과 |
| 커넥션 풀 | 5 → 10(P1 결과), max_connections 기본 100 | PLAN 131,247행 | M-22 |
| 좌석 상태 캐시 TTL | 1초(SA 9-4) | PLAN 250행 | M-14, M-15 |
| 스트레스 단계 | K1 5→10→…→320명(이후 1,280까지), K1-H 50→…→800명, 단계당 60초, 워밍업 60초 | PLAN 114행, STORY 32행 | 5-5절 |
| 동시 처리 상한 | 자리 4, 대기 200ms, 대기 인원 64 | LOOP 24,29행 | M-77 |
| 선점 지연 목표(SA 9-4) | 확정 2초 이내(접수형 지연 설명 중 언급) | PLAN 255행 | 해당 없음 |
| 목표 대 실제 | 목표 초당 100~200건 요청 vs 구 기기 한계 초당 1~2건(두 자릿수 차이) | PLAN 339행 | M-46 |
| 배포 환경 | 4 vCPU 한 대에 전체 스택, 배포 성능 테스트는 나중에 따로(단계 전체 재측정 도구는 k6-deploy-steps.md, 측정 전) | STORY 251행, PLAN 566행 | docs/design/reservation/k6-deploy-steps.md(미읽음, 참조만) |


### 조각 N — 3. 설계 의도와 목표 숫자

- 매수 한도: 같은 사용자 최대 4매(C4 최대 보유 4매 확인), SA 12-7 ①. docs/design/reservation/concurrency-results.md:29, :45
- lock_timeout 500ms(SA 9-4, 잠금 시도당 상한). 실제 상한 약 1초. C3 목표 최대 1,500ms 미만(2026-11-06 변경). docs/design/reservation/concurrency-results.md:88-96
- 커넥션 풀 SA 9-4 서비스당 20 vs `config-repo` 5 → 측정으로 10 제안(D, ISE-237). REV :54-55(R6), CP :14, :79-84
- Hikari connection-timeout 1초(Gateway 응답 시한 3초 안에 실패 응답). OR :126; CP :75
- 판정 기준(사전): 회복 30초 안, 버려진 일 5% 미만, 오류 p95 1.5초, 처리량 14건 이상. OR :156-161
- 리틀의 법칙 값: 연결 상한 3초 × 초당 10건 = 30. OR :213
- 만료 스케줄러: 5초, 배치 100, 추첨 연장 30분(상한 추첨 후 60분). HE :10, :29, :43
- 접수형: 소비자 동시성 2, 파티션 6, 잠금 시한 100ms, Outbox 간격 500ms·배치 20, 시한 30초, 접수→처리 시작 2초 이내(SA 9-4 목표), 발제 기준 동시 100명·1초·각 10회. AS :71, :89-90, :127-128
- 배포 단계 측정 계획: 8단계, L 초당 100명 300초, S 초당 5~160명·회복 120초, 약 2시간 40분. DS :12
- 실제 측정 결과가 있는 문서: concurrency-results.md(S0~S4, 로컬), k6-overload-recovery.md(B8), k6-p4b-local-vs-redis.md(캐시 비교), k6-contract-proposals.md(설정 근거). 결과 없음(측정 전): async-seathold-remeasure-plan.md(6절 "측정 전이다"), k6-deploy-steps.md(측정 전). 효과 미측정: k6-p4d-review.md 구현(배포 CPU 효과 아직 안 잼).


### 조각 O — 3. 설계 의도와 목표 숫자

| 항목 | 숫자 | 근거 | 실제 측정 결과 문서 |
|---|---|---|---|
| 1인 회차당 최대 매수 | 4매(기본) | R/contracts.md:20 | — |
| 선점 유지 시간 | 5분(`hold_minutes`), 결제 토큰 exp=holdExpiresAt-10초 | R/contracts.md:81,83 | — |
| 좌석 사전 거절 캐시 TTL | 1초 | R/contracts.md:38 | R/seathold-pre-reject-cache-measure.md |
| 잠금 대기 시한 | 50ms(ADR 0061), 이슈 초안은 500ms | R/contracts.md:221, R/linear-issues-after.md:219 | R/seathold-lock-timeout-load-compare.md |
| 접수형 처리 시작 마감 / 최대 결과 시간 | 30초 / 약 35초(시한 정리 5초마다), 재시도 3회(1·2·4초) | R/contracts.md:173 | 이 담당 파일에는 접수형 부하 결과 없음 |
| 동시 100명 경합 완료 기준 | 1명 201·99명 409·중복 판매 0 | R/linear-issues-after.md:233 | 이 담당 파일에는 없음(concurrency-results.md 별도) |
| 부하 시나리오 | L1 VU100 1초 내 시작 각 10회, L2 VU200 60초, L3 50→800 계단 60초, L6 VU1,000 | R/load-test-scenarios.md:31-36 | L1·L2·L3·L6 로컬 근사는 concurrency-results.md 4절(담당 밖) |
| 좌석 규모 `[가정]` | 1,000석·15,000석(KSPO DOME 약 15,000석), 가상 사용자 1,000명 | R/load-test-scenarios.md:20 | — |
| 포화점 판정 `[가정]` | 처리량 <5% 증가 & p95 2배 초과 | R/load-test-scenarios.md:65 | — |
| 워밍업 | 30초 VU 10 | R/load-test-scenarios.md:23 | — |
| 만료 스케줄러 주기 | 코드 기본 5초 → config-repo 1초, 한 주기 100건 | R/seathold-pre-deploy-measurements.md:92, 96 | R/seathold-pre-deploy-measurements.md:99-105 |
| 처리 한계 모델 | ≈ 상한 ÷ 자리 쥐는 시간 | R/seathold-pool-limit-sweep.md:242, R/seathold-pre-reject-cache-measure.md:49 | 여러 측정 문서 |
| 지표 HTTP 버킷 | 25·50·100·200·300·500ms, 1s, 3s(3s는 Gateway 응답 시한) | R/seathold-metrics.md:125 | — |
| 클라이언트 응답 시한 | 3초(Gateway) | R/seathold-structure-improvements.md:66 | — |
| 대사 한 번 처리 | 종류별 최대 1,000건 | R/seat-ledger-reconciliation.md:167 | — |
| 풀 | 현재 5(config-repo), SA 9-4는 20 | R/load-test-scenarios.md:47 | R/seathold-pool-limit-sweep.md |


### 조각 P — 3. 설계 의도와 목표 숫자

- 원칙: "백엔드에 집중하기 위해 프론트는 단순하게"(README.md:7). 실서비스 구조(BFF, 앞단 대기실, SSR+CDN)는 확인했지만 시연 조건에 맞춰 가장 적은 구성(README.md:7; ADR 0040 4절 표를 가리킴).
- 사용자 원칙 4개(design-system.md:11-16): 지금 상태·다음 행동이 먼저, 실패는 막다른 길이 아님(결과를 모르면 실패라고 하지 않음), 색에만 기대지 않음, 숫자는 흔들리지 않음(tabular-nums, 서버 시각).
- 목표 숫자(문서 서술 그대로): 승인 결과 2초 안 반영(SA 9-4, README.md:237, design-system.md:220 "2초 이내 목표"), 대기열 최소 조회 간격 3초(README.md:173), 입장 토큰 10분(design-system.md:251), 선점 5분/결제 가능 4분 50초(design-system.md:252), 본문 대비 4.5:1·비글자 3:1(design-system.md:34), 오픈 순간 [예매하기]가 첫 화면 안(README.md:136), 순번 1,234,567번까지 한 줄(design-system.md:421), 본문 최대 폭 1200px(design-system.md:114).
- 발권 이벤트(ISE-257): 발표 11/19, 참관객 약 50명이 발권까지 해 보고 QR로 부적 이미지를 받음. "SA에 없는 이벤트 기능"(README.md:63,423).


## 5. (조각별) 서사 소재

### 조각 A — 4. 서사 소재

##### 4-1. 설계가 바뀐 순간

| 날짜 | 바뀐 것 | 이유 | 근거 |
|---|---|---|---|
| 10/25 | 자체 검수로 D1~D4 결함 4건 반영 | 중복 판매·이중 결제·유실로 이어지는 정합성 문제 | `docs/core/SA MASTER.md:568-575, 2130` |
| 10/26 | 대기열 순번을 진입 시각(ms)에서 seq 카운터로 | 같은 ms 요청의 동점과 시계 차이 | `docs/core/SA MASTER.md:148, 2132` |
| 10/26 | 응답 형식을 성공·실패 다른 형태 + traceId, reasonCode 통일 | 잘못 감싼 응답을 받는 쪽이 바로 알아채게 | `docs/core/SA MASTER.md:149, 1131` |
| 10/26 | Java 25 → 21(Junhyun An), 이유 미기재, 팀 전체 합의 표기 안 함 | 이유는 문서에 없음 | `docs/core/SA MASTER.md:156, 811` |
| 10/26 | Config 서버를 둠(이전 안은 .env + 프로필) | 흩어진 설정 파일을 한 곳으로 통합(GGangMY 판단), 1차 AI 피드백 "과잉" 지적을 뒤집음 | `docs/core/SA MASTER.md:944` |
| 10/26 | 접수형 선점에 접수 요청 행 + outbox, 결제 토큰을 선점 만료 10초 전에 만료, 측정 목표를 포화점 비교로, 외부 공개를 gateway만으로 | 멘토 피드백(10/26) 28건 반영 | `docs/core/SA MASTER.md:146-157, 2343-2377` |
| 10/27 | common 모듈 하나, 패키지 com.iseonjwa, Config 서버 확정, 블랙리스트 Redis 장애 closed, Gradle 9.8.2·PostgreSQL 17 | 초기 환경 통합 회의 | `docs/core/SA MASTER.md:114-124` |
| 10/28 | 이강민 역할 변경(결제·Mock PG 전부 안준현), 테이블 이름 복수형 통일, ERD 5개로 재작성 | 10/28 회의, ADR 0030 | `docs/core/SA MASTER.md:100-110, 2411` |
| 10/29 | 응모 Kafka 접수 → 동기 INSERT 기준선(11/10 L1 p95 10.58ms로 유지) | 측정으로 확인 | `docs/core/SA MASTER.md:374, 2133, 2274` |
| 10/30 | venue_seats.row_no VARCHAR(5) → INT | 문자열이면 정렬이 사전순(1, 10, 2…) | `docs/core/SA MASTER.md:98` |
| 10/31 | 회차당 추첨 1개, 추첨석을 등급 LOTTERY로 분리, 응모 취소·재응모 불가, 빌링키는 결제 시점 확인 | 추첨 담당 결정 D2·D4·D5·D9 | `docs/core/SA MASTER.md:79-92` |
| 11/2 | 추첨을 Should → Must, seed를 BYTEA 32바이트로 | 팀 합의, ADR 0041 | `docs/core/SA MASTER.md:69-77` |
| 11/3 | Kafka 소비자·스케줄러를 인프라 계층에서 presentation으로(ADR 0043) | 회의 | `docs/core/SA MASTER.md:2071` |
| 11/9 | 결제 SA 변경 요청 PR1~PR3 반영(예매 결제 이벤트 상태별 규칙, 승인 처리 순서, PG 자원 분리, Ops 명령, 대사 RC-1~8, Mock PG 장애 모드 확장) | 결제 담당 | `docs/core/SA MASTER.md:23-67` |
| 11/12 | Notion #58 문장 정정(재결제 가능 → 새 예매라 영향 없음). 배포 DB schedule_seats 약 156만 행 관찰 | ADR 0026 | `docs/core/SA MASTER.md:2230, 2066` |
| 11/15 | 응모 응답 202 → 201, AI 검증 #11 추가, 대기열 차례 유효 시간 1분(ISE-282) | 11/10 L1 결과, 코드에 문서 일치 | `docs/core/SA MASTER.md:13-21, 142` |
| 11/16 | 측정·시연 공연장 15,000석 → 1,500석(ADR 0060). 좌석 응답 약 830KB를 장비 한 대가 못 버틴 것이 이유 | 장비 한계 | `docs/core/SA MASTER.md:333, 2051` |

##### 4-2. AI 검증 로그 14장 항목별 판단과 이유 (원문 그대로 요약)

| # | 항목 | AI 피드백 | 판단 | 이유 | 근거 |
|---|---|---|---|---|---|
| 1 | 서비스 경계 | Lottery는 Reservation에 병합(두 리뷰어 공통), Queue는 조건부 유지(4개 권고) | Lottery 병합 **수용** / Queue 분리 **거부** | 응모 → 추첨 → 일반 오픈 순서라 추첨과 실시간 선점이 겹치지 않음(10/31 추첨석 LOTTERY 분리로 문장 교체). Queue는 자기 데이터가 없고 멈추면 예매도 멈춰 장애 격리가 성립하지 않음. 모듈로 두고 "순번 조회가 선점 응답을 늘리면 분리"를 측정 조건으로 | `docs/core/SA MASTER.md:2123` |
| 2 | PG 타임아웃 | 타임아웃은 실패가 아니라 "결과 모름", UNKNOWN + 조회로 확정, 만료 뒤 승인은 자동 환불 | **수용** | 확인 전에 좌석을 돌려주면 돈만 빠지는 고객이 생김 | `docs/core/SA MASTER.md:2124` |
| 3 | SAGA 방식 | Reservation 조정자(Orchestration) + `POST /reservations/{id}/payment` 경유 권고 | **거부**(Choreography) | 일반 예매 참여자가 2개라 결과 이벤트 구독으로 충분. 결제 진입 경합은 서명 결제 토큰으로 막음. 흩어진 흐름은 Zipkin 추적과 이벤트 흐름도로 보완 | `docs/core/SA MASTER.md:2125` |
| 4 | 응모 처리 | 응모 Kafka 접수·자동결제 파티셔닝은 과하다, 1인 1응모는 유니크 제약으로 충분 | 유니크 제약 **수용** / 응모 동기 처리 **거부**(10/26) → 10/29 재검토로 #11 수용 | 결과가 바로 필요 없고 마감 직전에 몰림, 접수만 받고 나중에 처리하는 편이 안정적, 같은 패턴을 결제 결과와 공유. 원문 프롬프트 없음(10/23 교차 검토 결과만) | `docs/core/SA MASTER.md:2126` |
| 5 | Kafka 파티션 키 | 파티션 순서를 정합성 수단으로 쓰지 말고 키는 userId/reservationId | 원칙 **수용** / 키 선택 **부분 거부** | 정합성은 DB 조건부 UPDATE가 지킴. 접수형 키는 회차 + 구역(같은 구역 한 줄 처리로 락 경합 감소, 인기 회차가 파티션 하나에 몰리지 않게) | `docs/core/SA MASTER.md:2127` |
| 6 | Outbox·DLQ 범위 | 전부를 필수로 볼 필요 없다, 최소 경로부터 | **부분 거부** | 결제 결과가 사라지면 돈과 예매가 어긋나 고객 피해가 가장 큼, DLQ 없으면 원인 추적 불가 → 공통 모듈로 한 번 만들어 모든 토픽에 적용 | `docs/core/SA MASTER.md:2128` |
| 7 | [필수] 계층형 아키텍처 | 4계층을 도메인 응집·의존 역전·DTO 분리·Fat Service 네 질문으로 점검(10/27, user-service 코드 기준) | **부분 수용**(의존 방향 2곳·재실행 수용, 규칙 이동 부분 수용, 응답 레코드 분리 **거부**, 서비스 분리 보류) | 인프라 → 응용, 응용의 DB 예외 의존은 SA 13장을 어겨 고침. 토큰 발급 가능 여부·이메일 정규화는 User로 이동, 비밀번호 72바이트 제한은 BCrypt 제약이라 서비스에 둠. 응답 레코드 분리는 필드가 같고 바꿀 계획이 없어 거부. AuthService 239줄은 회원 기능이 늘면 분리. 선점 규칙은 Reservation 선점 코드가 생기면 재실행 | `docs/core/SA MASTER.md:2129` |
| 8 | 2차 설계 자체 검수(10/25) | D1~D4 결함 지적 | **수용** | 중복 판매·이중 결제·유실로 이어지는 정합성 문제라 구현 전에 막음. D4는 당시 "행 없이 바로 발행"을 골라 측정에 폴링 지연이 섞이지 않게 했으나 10/26 피드백으로 접수 요청 행 + outbox로 바꿈 | `docs/core/SA MASTER.md:2130` |
| 9 | API 응답 형식(10/26) | 기존 형식 유지 추천 → 균일 형식 요청에 6키 제안. 두 형태 래퍼 검토 시 실패 응답 traceId·Gateway 처리기·HTTP 코드 유지 지적 | 유지·6키 **거부** / 검토 지적 **수용** | 한 형식이면 개발자가 잘못 감싼 응답을 받는 쪽이 판단하기 어려움 → 성공·실패 다른 형태 + traceId | `docs/core/SA MASTER.md:2131` |
| 10 | 대기열 순번(10/26) | 팀원 지적(진입 시각 동점)을 AI가 검증: 동점은 멤버 사전순, 시계 차이 → seq + Lua 추천 | **수용** | 같은 ms 요청이 정상인 오픈 정각에 공정성을 지키려면 Redis 도착 순서 하나를 기준으로 | `docs/core/SA MASTER.md:2132` |
| 11 | 응모 접수 재검토(10/29) | #4와 같음 | **수용**(동기 INSERT) | 11/10 L1 p95 10.58ms, 바꿀 조건 p95 300ms 초과 미도달로 동기 유지. 접수 행 + outbox 비교 측정은 하지 않음. #4의 10/26 거부는 이력으로 남김 | `docs/core/SA MASTER.md:2133` |

- 원문 집계: 거부 4건(#1 Queue, #3, #4, #9)·부분 거부 2건(#5, #6)·수용 5건·부분 수용 1건(`docs/core/SA MASTER.md:2134`). 합 12가 표 행 11과 달라 집계 기준이 행 단위가 아님(사실 카드 모순 행 참조).
- 발표 대표 사례 후보: #1, #3, #9(`docs/core/SA MASTER.md:2135`).
- 멘토 피드백(10/26)은 사람 피드백이라 AI 로그에 넣지 않고 부록 B(F1~F28)에 둠(`docs/core/SA MASTER.md:2137, 2344-2377`).
- 프롬프트 원문(#7 계층형, 좌석 선점 동시성, 추첨 자동결제 방식, 이벤트 신뢰성)이 14장 끝에 있음(`docs/core/SA MASTER.md:2138-2181`).

##### 4-3. 트러블슈팅·반전 소재

- 응모를 Kafka로 접수 → 동기 INSERT로 회귀: AI의 "과하다" 지적을 10/26에 거부했지만 10/29 동기 기준선을 구현하고 11/10 L1 p95 10.58ms(기준 300ms)로 확인해 결국 수용(`docs/core/SA MASTER.md:374, 2126, 2133`).
- 입장 속도 임시값 50명/초가 역산 예(약 6.7명/초)와 약 7.5배 차이: 초안이 포화점을 전제하지 않았음(`docs/core/SA MASTER.md:341-342`).
- 3초 Gateway 타임아웃 vs 결제 최악 4.5초: 504 뒤에도 결제는 계속되고 멱등키 재시도로 결과를 받는다는 설계로 해결(`docs/core/SA MASTER.md:907`).
- 결제 토큰 10초 앞당기기와 만료 우선 + 자동 환불(AI의 PAYING 제외 안 거부)(`docs/core/SA MASTER.md:759`).
- 15,000석 회차의 좌석 응답(약 830KB)을 장비 한 대가 못 버텨 시드를 1,500석으로 축소(11/16)(`docs/core/SA MASTER.md:333`).
- 측정 회차가 쌓여 배포 DB schedule_seats가 약 156만 행이 되어 새 회차 통계가 갱신되지 않음, ISE-284(`docs/core/SA MASTER.md:2066`).
- 블랙리스트 Redis 장애 정책 open → closed(10/27 ADR 0005): 로그아웃된 토큰이 통하는 것보다 장애 중 요청 차단을 택함(`docs/core/SA MASTER.md:921`).
- D3 부분 유니크 → 전체 UNIQUE: #58 본문의 "재결제 가능" 문장 오류를 11/9 정정, Notion은 11/12 수정(`docs/core/SA MASTER.md:2230`).
- 결제 대사 RC-2(결제 APPROVED인데 환불 의무 미등록)를 가장 심각한 불일치로 규정(`docs/core/SA MASTER.md:1083`).
- 문서 간 모순: 정본 버전 표기(v3 vs v3.3), AGENTS.md "2차 SA에서 확정"과 SA 9-1 확정 불일치, ERD 그림 4 refunds.reason이 두 값만(12-4 본문은 다섯 값), ERD 그림 4·5에 M3 테이블 미반영(`docs/core/SA MASTER.md:1941, 1991`).
- 재조회 총 시간 "약 21분"과 구간 합 22분의 차이(`docs/core/SA MASTER.md:896`)는 경계 해석 확인 못 함.


### 조각 B — 4. 서사 소재

설계가 바뀐 순간과 판단.

- 11/9 밤 "Gateway에는 벌크헤드가 없다"는 AI 조사를 대기열 담당이 "기본값 아니냐"고 되물어 거부 → 바이트코드로 25 발견(RES:39, STORY:256). 판단 기록 거부.
- 11/9 AI의 "측정용으로 차단기 기준을 풀자" 제안을 대기열 담당이 거부(RES:40).
- 11/10 강민의 "시한은 더 빡빡하게, 느린 호출은 더 넓게" 제안을 소스 확인 후 철회 → "무엇을 실패로 셀지"로(RES:46, STORY:109). 수용.
- 11/10 AI의 "11/11 회의 → 11/13 재측정" 거부, 앞당김(STORY:257).
- 11/10 측정 도구가 낸 "실패 1.0s"가 이중 기록이었음(RES:44).
- 11/11 차단기 예민화 B20이 AI 추천(맨 앞 유입 제한)과 같은 결론 → 근거는 숫자 24%(STORY:139,259).
- 11/11 자원 격리를 contract 이슈로 합의하자는 AI 추천 거부(STORY:261), 상한 64 유지(AI 128 거부, STORY:264), Zipkin 증설 거부하고 스케줄러 Redis 스팬을 근원에서 줄임(스팬 약 92%, #490, STORY:265), 7차 내일 아침 제안 거부(STORY:266).
- 11/11 거절 위치: 처음 A안(WebFilter 앞) → 소스 확인 후 B안 HttpHandler 데코레이터, 주석의 틀린 전제도 고침(STORY:177,194).
- 11/11 H1 판정 기준(사전 적음)이 포화 단계에서 가설을 못 가림을 인정(STORY:179,339).
- 11/11 H2에서 JFR 5.2%보다 훨씬 큰 CPU 감소가 JVM 밖에서 나옴 → 로그 경로 → 운영 반영 방식 B(problems) 수용(STORY:211,216).
- 11/12 8차 R0 설계: 포화 단계에서 못 가린 비용을 비포화 단계에서 직접 풂(STORY:225,249).
- 11/12 폴링 상한 30초 제안 거부, 리뷰 전 머지·배포 선택(STORY:268-269).
- 11/12 목표를 평상/과부하 둘로 나눔(STORY:33).
- 11/12 관측 도구 설정이 11/9 설정으로 돌고 있었음(TS-64) → 해시 라벨 자동화(DEP:174).
- 11/13 Retry-After 1초 → 차단기 열림 5초·한 번 실패 3초, 동시 상한 Retry-After 2 → 3초(RES:112, STORY:28).
- 11/15 payment cgroup OOM 5회 → 768m → 1024m(DEP:391). 11/15 Kafka 헬스체크 CPU 스파이크(45~224%) → 헬스체크 교체(DEP:175).
- 11/16 HTTPS 전환(IP 인증서), Grafana 공개 결정이 11/5 결정을 대체(DEP:428-429).
- 11/5 서버 flex → 표준 인스턴스, 11/8 GC Serial → G1 번복(DEP:371-375,419).
- 11/4 첫 배포 때 아웃바운드 규칙 누락, Eureka 지연으로 스모크 실패 → 재확인 로직(DEP:376,379).
- 보고 TS 번호: TS-34, 42, 51, 53, 54, 56~59, 60, 61, 64, 70, 71.
- AI 판단 기록 경로는 `docs/ai-judgments/kangmin/…`·`youngeun/…`·`junhyun/…`(RES:39-46, STORY:256-269 링크). 거부 사례 다수(발제 필수 거부 1건 이상 충족 소재).


### 조각 C — 4. 서사 소재

- 정상 요청이 로그를 한 줄도 안 남긴다는 발견(2026-11-03) → 공통 요청 로그 필터 결정 → 이후 운영 problems 모드(ADR 0052)로 정상 요청 로그를 다시 줄인 반전(Gateway 11/13, 서비스 11/16) (OB:37-43, OB:53).
- 측정 모드 설계: 평소엔 닫힌 9090·운영 키, 측정 때만 열고 측정 전용 키로 바꿔 띄우며 끝나면 폐기 (LT:9, LT:98-103). Jenkins 2분 폴링이 측정 모드를 풀 수 있다는 함정 (LT:57, LT:101).
- local k6 컨테이너가 약 32초마다 3초씩 멈춤 → 노트북 발생기 한계 → 같은 VPC EC2 발생기로 이전 (OP:74).
- 발생기 유형 선택: 좌석 조회 응답 약 830KB, 초당 100건 약 83MB/초라 c6i.large(0.781Gbps)로는 여유가 없어 c6i.xlarge(1.562Gbps) (LT:35-38).
- 알림 링크가 localhost라 배포 서버 알림의 대시보드 링크가 안 열리던 문제 → env 라벨로 주소 분기(2026-11-12 ISE-256), 11/16 ISE-278에서 공개 주소 /grafana (OB:134).
- regexp 뒤 event 필터가 없으면 라벨 없는 알림이 새는 버그를 검사 스크립트의 미끼 줄이 잡음 (OB:174).
- Compose secrets·configs의 빈 값 함정(compose 전체 실패, 파일 대신 디렉터리 생성)을 확인하고 자리표시 주소로 우회 (OB:137).
- 건별 알림 선택지 3개 중 Loki 로그 알림 선택(2026-11-09 여섯 결정) (OB:147-156).
- 관측 도구 추가 메모리로 서버가 16GB 한계에 정확히 닿는다는 계산 (OB:191).
- 오픈 피크 흐름 분석(AI 작성 가설, 측정 전): 좌석 조회 한계 초당 10명이 N=50보다 훨씬 낮아 대기열이 뒤쪽을 보호하지 못한다는 가설(Q3-3) (OP:211-213). 추첨 자동결제가 소비자 동시 1개로 약 10분이라는 계산이 추첨 문서의 약 1분과 모순 (OP:331).
- C1-1 local 측정에서 R1만으로 회복되고 R2·R3 시작값은 R1보다 나빴음 → R1만 제안, R3는 배포 환경 재측정 (OP:97).
- AI 검증 로그(거부·수용 판단): 이 세 파일에는 해당 기록 없음.


### 조각 D — 4. 서사 소재

- 판단 규칙 선고정: 판정 기준은 측정 전에 정하고 결과를 본 뒤 바꾸지 않음(OPS:7). 8차에서 P/O 판정도 "측정 전에 정한 기준 그대로" 적용하고 기준을 넓히지 않음(OPS:1103).
- 차단기 오작동 서사: 1차에서 Redis는 0.1~0.8ms로 정상인데 `auth-redis`가 열려 2,000/s에서 전체 503(OPS:338-339) → 2차 느린 호출 기준 상향만으론 부족(OPS:389) → 연결 맺기 가설 기각(OPS:406) → JIT·CPU 경합 추정(OPS:420) → 3차에서 dev #463 지표로 원인 확정: 대부분 bulkhead 거절이 차단기 실패로 세진 것(OPS:541) → 무시 예외(ISE-233) → 4차에서 배포 확인(OPS:607) → 5차 "차단기를 예민하게" 가설 기각(OPS:764-774).
- 측정 도구 결함 발견: 차단기에 실패 시간이 중복 기록되어 1.0s가 찍힘(OPS:390,542), pidstat이 init만 잡음, `exit=0`이 k6 종료 코드가 아님(OPS:1114-1115), k6와 Prometheus 거절 수 40% 불일치(OPS:976).
- AI 추천 거부 후 측정으로 입증: 처음에 거부한 AI 추천과 같은 결론을 측정으로 확인(OPS:774, 강민 판단 기록 링크 OPS:616).
- 재시도 폭주 반전: Q2 10,000명에서 503 → 3초 재시도 → 503이 더 빨리 돌아와 초당 2,300~3,800건, 600초간 회복 못 함(OPS:354). F 설정으로 같은 부하가 통과했으나(OPS:491) 30,000명에서 재현(OPS:511).
- Gateway OOM이 CPU 포화보다 먼저: 5차에서 3회 OOM(OPS:679-686). M1으로 메모리를 늘리자 죽는 실패가 느려지는 실패로 바뀜(OPS:793). 맨 앞 동시 처리 상한(A1)이 진짜 해법(OPS:772,893).
- A1 설계 결정: 리틀의 법칙으로 64 산출(OPS:811-815) → 32·64·128·500 비교, 대조군 500(OPS:893) → 128 제안(OPS:920) → 11/14 큰 응답 경로에서 64 유지 결정(OPS:1231).
- 로그 비용 발견: 요청 로그를 끄면 서버 CPU 12%p 하락, 비용이 JVM이 아닌 로그 경로(OPS:995-997) → ADR 0052 problems 모드 → 8차에서 끔과 2%p 안팎 확인(OPS:1103).
- 이중 병목: 거절 비용이 0이 아니라 처리 비용의 큰 몫이라고 추정(OPS:906) → R0 선형 모형으로 H1 뒤 거절은 거의 공짜(r/a 0.02~0.075)로 정정(OPS:1090).
- S1 병목 이동: 개선 전 서비스 메모리·GC → P4a DB 조인·정렬 → P4b 커넥션 풀 → P4c CPU(OPS:1199-1206). `ANALYZE` 누락이 가짜 병목을 만든 사건(OPS:1237).
- 설정 개선이 손익 안 맞아 값 0 유지: PAY-2 대기 200ms(OPS:1327).
- 운영 위험 발견: 서비스 재배포 직후 Gateway가 새 인스턴스를 모르는 공백(OPS:1328).
- 설계값 정정: X1 처리 속도 계산값 초당 20 → 실측 15.6(OPS:228).
- 선조치 후보고: 8차를 11/13에서 앞당김(OPS:1035), 측정 모드 켜기·끄기를 에이전트가 대신한 예외(OPS:1127).


### 조각 E — 4. 서사 소재

- 설계가 바뀐 순간:
  - 2026-11-11 A1 첫 값 500 → 64(p95를 평균 체류 시간 자리에 넣은 오류 정정)(ISO:85).
  - 11/10 자동결제 처리량 가정(공유 동시 10, 초당 33, 약 1분) → 실측 소비자 1개 초당 3.17(약 10.7분) → ADR 0051(파티션·소비자 3, 전용 `pg-billing` 허가 3)로 대체(NUM:40,311,443-459).
  - 6차 측정에서 거절도 CPU를 쓴다는 발견 → H1 거절을 맨 앞(HttpHandler 데코레이터)으로(ISO:10).
- 트러블슈팅·반전:
  - 자동결제 병목 가설 → PG 지연 스윕으로 확정(속도가 1÷(PG 지연+약 10~16ms)에 정렬, CPU·허가·DB는 남는데 lag만 쌓임)(NUM:297-311).
  - L2-b 503 9.5%의 정체는 자동결제가 일반 결제 허가를 나눠 쓴 경합(0.31% vs 9.5%) → ADR 0051 뒤 503 0건(NUM:336,467,471).
  - 차단기 완화 → 병목이 벌크헤드·DB 풀로 이동 → 서버 CPU 100%가 최종 한계(NUM:381-410).
  - 측정 도구 코드 누락(`pgProvider`)으로 첫 O1 실행 버림(SYN:193).
  - 거절 위치가 사용자 결과를 가른다(SYN:249-250).
- 거부/보류/수용 판단: C1·C2 관측 축소 보류(kangmin, 시연 추적 약화 위험)(ISO:11); 혼합 생성 대신 점수화만(SYN:109); ISE-28 취소 후 ISE-262 재시작(SYN:5); 입장 속도 낮춘 별도 실행 대신 계산으로 갈음(SYN:258); Q1은 차단기 완화 않고 운영 설정으로(NUM:372); L3 기준 미달은 팀 판단으로 넘김(NUM:473). 판단 기록 경로: `docs/ai-judgments/kangmin/2026-11-13-synthetic-persona-six-factors.md`, `docs/ai-judgments/junhyun/2026-11-13-fill-presentation-gaps-restore-synthetic-data.md`, `docs/ai-judgments/kangmin/2026-11-10-agent-runs-server-commands-during-measurement.md`, `docs/ai-judgments/kangmin/2026-11-10-cut-setup-to-draw-gap.md`, `docs/ai-judgments/youngeun/2026-11-10-no-breaker-relax-for-measurement.md`(본문은 읽지 않음, 링크만).
- LLM 예측 검증: 4개 중 3개 맞음·1개는 파도가 안 보임(SYN:240-245).


### 조각 F — 4. 서사 소재
- 번복 1(D3): SA와 AI 검증 #4가 응모를 Kafka 비동기로 했으나 추첨 담당이 "단순한 안을 기준선, 측정으로 정한다"며 동기 INSERT로 뒤집음. 11/10 L1 p95 10.58ms로 비교 측정 없이 유지, SA 반영 11/15(PR #631, 선조치 후보고 ISE-269). 근거 README.md:21,190; options.md:30-33.
- 번복 2(D6): 10/30 전용 Bulkhead 추천 → 10/31 "공유 기준선으로 문제를 일부러 재현해 트러블슈팅 소재" → 11/10 L2-a 93.1·92.4·93.4% 미달 → 원인이 자리가 아니라 소비자 1개 직렬 대기였음(남은 자리 9/10) → 11/12 ADR 0051로 소비자 3·pg-billing 3. 가정 초당 33건이 실측 3.17건으로 10배 어긋남. options.md:85-101.
- 번복 3(D9): 처음 추천 B(안 떼기) → A'(LOTTERY 등급 분리). 결과로 D2가 E안으로 풀리고 `open_at >= draw_at + 70분` 시간 규칙이 필요 없어짐. options.md:149-158.
- 번복 4(D5·D13): 재응모 허용 A → 금지 D, 승계 소비자 내 A → 승계 워커 D. 이유: 흩어진 호출과 긴 트랜잭션 회피.
- 근거 정정(11/12): 선점 만료 30분이 기준선에서는 합계 약 31.9분이라 초과였으나 ADR 0051 뒤 약 25분. options.md:175.
- F1 장애 주입: 이중 결제 0(원장 승인 34 = DB 34, 38 = 38), 연장 4/4건, Kafka 정지 복구 후 약 35초 수렴. 울려야 했는데 안 울린 경보(서비스 다운, reservation outbox)를 발견으로 기록, `LotterySettlementSlow`만 10분 45초에 firing. NOT_SENT는 서버에서 못 보이고 통합 테스트로 대체.
- 거부 판단: 측정 모드 "켜지 않음" 제안을 강민이 거부(판단 기록 2026-11-11-f1-measure-mode-required.md). 에이전트가 서버 명령을 실행하는 한 번만의 예외 승인.
- 운영 사고 요소: 에이전트의 docker stop 실행이 권한 분류기에 막혀 강민이 직접 실행(f1-fault-injection.md:147).
- 로컬 E2E에서 겪은 것: 스택 직후 첫 로그인 503, 11/2 예매의 결과가 재기동 뒤 처리되어 NOT_REFUNDABLE 환불 거절.


### 조각 G — 4. 서사 소재

- 상담 설계 변경(2026-11-09): `/help` 페이지 대신 떠 있는 위젯으로(사용자가 추천을 거부). 스트리밍 SSE 추가(사용자 요청). 오류 응답 Accept 무관 JSON 고정(ISE-221, 셀프 리뷰가 400 빈 본문·429 500을 재현). Gateway 시한은 바이트코드 확인 뒤 10초 유지. 근거 AS:3, 76, 95, 144.
- 상담 검색 반전: 단순 겹침 점수로는 "환불은 언제 되나요?"가 매수 초과 절에 먼저 붙어 IDF 가중과 질문 말투 제거를 더함. AS:202.
- 상담 설계 원칙: 상담이 죽어도 예매는 산다(별도 컨테이너, 강등 구조), 문서에 없는 내용은 모른다고 답함. AS:7-9.
- 11/6 시연 반전: Mock PG 빌링키 메모리 보관이라 재생성 후 자동결제 36건 실패(L-05 1회차) → 카드 108개 재등록 → 추첨 7로 재실행 통과 → ISE-235(#505)에서 서명 방식으로 해결. D-05는 실제 trace가 Kafka에서 끊겨 기대 결과를 설계에 맞게 고침(#367). MVP:163, 183.
- 시연 전략: 정상 흐름 → 장애 주입 → 추첨 순서, 장애를 현장에서 주입. MVP:15-21.
- AI 리뷰 체계: 승인 대신 관문, 작성과 검토 분리, 다섯 겹. 잡은 결함 10건(오히려 L3·L4가 잡은 5~8번), 거부한 AI 제안 7건(바이트코드로 "벌크헤드 없음" 뒤집기, 128 상한 거부 등). 한계 정직 공개(PR 흔적 12/313, 효과 미측정, 같은 계열 맹점). REV:10-12, 86-123.
- 모델마다 보는 곳이 다르다: Claude Opus 2회 같은 결함, codex는 다른 불일치. REVP:3.
- 거부 판단: CodeRabbit 미도입(2026-11-13), 거부 55/170건. REV:115, 135.
- 시연 도구가 시연을 덮어씀: k6 pay1이 MOCK_PG_URL이 있으면 승인 지연 300ms를 걸어 패널 장애를 덮어씀. DS:28.


### 조각 H — 4. 서사 소재

- 설계가 바뀐 순간
  - 2026-10-23: 서비스 5개 → 4개 권고(Lottery 병합), codex는 3개 권고. AI 검증 로그 #0이 3개 권고 거부+4개 절충안 수용 구조로 재작성 가능(01:52-55).
  - 2026-10-26: 접수형 v2를 DB 행 없는 Kafka 직접 발행 → 접수 행+outbox로 변경 제안(05:58-81, 04 N1).
  - 2026-10-26: 입장·결제 토큰 키 분리와 결제 토큰 10초 일찍 만료(05:37-49).
  - 2026-10-26: HMAC은 비대칭 서명을 MVP 이후로 미룸 → workspace.md:47에서 결제 토큰 ES256 키 쌍(ADR 0055)으로 구현.
  - 2026-10-26: Grafana를 SSH 터널로만 접근(05:158, 163) → 2026-11-16 로그인 없는 공개 보기(deploy-server-access.md:53).
  - 2026-11-16: 배포 주소가 HTTP → HTTPS(ISE-278)(deploy-server-access.md:12).
  - 좌석 수: 15,000석 → 1,500석(ADR 0060)(testing.md:39).
- 트러블슈팅·반전
  - PG 타임아웃은 실패가 아니라 결과 모름(C1): 돈은 빠졌는데 좌석은 팔리는 위험 → UNKNOWN+조회+망취소(01:59-67, 02).
  - 우선순위 거꾸로: 발제 필수가 P1(트러블슈팅 6영업일)에 몰림(01:69-80).
  - G1 치명: 동기형 409 롤백이 REJECTED 기록까지 지워 멱등 판별 불가(07:49-61).
  - G4 유령 선점: 30초 타임아웃 스케줄러 vs 소비자 경합(07:84-92).
  - Hikari 풀 5 vs SA 20(07:140-142).
  - 11/12 디스크 100%로 배포 실패(TS-60)(deploy-server-access.md:102).
  - 11/9 비대화형 SSH 거부를 서버에 키가 없다고 오판(loadgen.md:154).
  - 테스트 DB too many clients(100 → 300)(testing.md:94).
  - 11/2 GitHub PR 메타데이터 누락 점검(ISE-141)(git-workflow.md:56).
  - 11/9 발생기 요금: 공인 IP로 쏘면 1회 약 1달러, 노트북은 약 5.7달러(loadgen.md:36-42).
  - K1 G1 GC만으로 한계 초당 5 → 10명(deploy-server-access.md:140).
- AI 수용·거부 판단(AI 검증 로그 후보)
  - 01: codex 3개 서비스 권고 거부, Claude 4개 절충안 수용(재작성 제안)(01:52-55).
  - 06 #7: A 부분 수용, B·C·F·G 수용, D 거부(분리하면 클래스·변환 코드만 늘어남), E 보류(06:80-88). 판단 기록 경로: docs/ai-judgments/kangmin/2026-10-27-layered-architecture-check7.md(06:5 참조, 열어 보지 않음).
  - 04 N1~N12, 05 개선안 모두 "팀 합의 전 제안"이며 합의된 것만 SA·docs/decisions에 반영(05:3).
  - 02: 국내 PG 망취소 관행을 Mock PG에 재현한다는 면접 스토리(02:49).


### 조각 I — 4. 서사 소재

- 10/27 반전 지점: 5명이 같은 dev(4e02b5e)에서 갈라져 각자 만든 브랜치가 10쌍 모두 충돌. 준현만 회원·세션 업무까지 구현. 기준 선택은 "코드량·테스트 건수가 아니라 연결된 업무 흐름" — B/README.md:27, B/git-conflicts.md:19-32.
- 설계 문서와 코드 불일치 발견: Notion #60은 권우 MVC Gateway를 전제했지만 5개 브랜치 모두 WebFlux — B/README.md:78, B/source-notes.md:43.
- 정책 충돌: Redis 장애 시 fail-open(효승·Notion #48 잠정) vs fail-closed(준현). 추천은 fail-closed(D05)이나 자동 승계 금지, 팀 결정 대기 — B/auth-review.md:69, 84-86; B/team-decisions.md:50-56. 이 문서는 AI/사람 검토의 추천이며 수용·거부 기록은 없음.
- 후속 반영: 만료 JWT의 TOKEN_EXPIRED 구분은 2026-11-02 ISE-131에서 반영, 빈 jti 거절은 남음 — B/team-decisions.md:127. (문서에 후일 추가된 메모)
- 준현 gradlew가 0644라 `./gradlew` 직접 실행 시 permission denied, `bash gradlew`로 우회 — B/README.md:67, B/common-contracts.md:96.
- 검증 정직성: 77건 통과 숫자 옆에 "User는 설정 8건만, AuthIntegrationTest 20건 미실행, 전체 Compose·Redis 장애·부하 미검증"을 병기 — B/README.md:61, 69.
- 좌석 선점 Kafka 설계(K): 발제 요구(API→Kafka 적재→202)와 팀 결정(10/23 선택 완료 시 점유)이 부딪친다는 출발점에서, 키 후보 3개를 비교해 userId 선택, 이후 "Claude 에이전트 제안" 혼합형(Redis Lua 즉시 판정 + Kafka는 DB 기록)으로 정리. v1(DB 동기)로 기준선을 먼저 잰 뒤 v2로 개선하는 전/후 스토리 설계 — K:236-256, 454-457, 600-633.
- 판단 기록(AI 검증 로그 후보): K는 "Claude 에이전트 제안"을 명시한 혼합형 섹션을 포함. 수용 여부 판정 문장은 이 파일에 없음(다른 문서 확인 필요).


### 조각 J — 4. 서사 소재

- 불변 조건 우선: 기술(UNIQUE, Outbox)이 아니라 "추적 없이 사라지지 않는다"를 먼저 정하고 장치마다 맡는 실패를 나눴다(invariants.md:3-8). 2026-10-28 사용자 원칙으로 "단일 방어" 9건을 점검해 보강(invariants.md:132-146).
- 설계가 바뀐 순간
  - 2026-10-27 설계 → 10-28 Opus 검토 지적(C·H·M·L) + 사용자 결정 반영(README.md:5,46). 빈틈 #2(부분 유니크 인덱스에서 NMC 누락)를 0026 전체 UNIQUE로 대체(README.md:45).
  - 0016 개정(10/28): 경합은 0026, 예매 규칙은 0027(README.md:31).
  - S-1 철회: "HELD가 아닌 모든 상태에 RefundRequested"는 CONFIRMED 재전달 때 확정 티켓을 환불할 수 있어(C1) 철회하고 상태별 규칙으로 바꿈(sa-change-requests.md:155, invariants.md:47).
  - 11/2 ISE-96 빌링키 자동결제를 M2로 당겨 구현(data-model.md:224).
  - 11/9 ISE-45 토스 `clientPaymentRef`·checkout 구현, 11/10 `pg_provider` 컬럼(V10, ISE-231)으로 결제별 PG 선택(data-model.md:33, api.md:3-17).
  - 토큰 서명 HS256 → ES256(ISE-245, ADR 0055, S-31 제안)(api.md:77).
  - TX1 쓰기 순서: 설계는 멱등 기록 먼저였으나 `resource_id` NOT NULL이라 결제를 먼저 쓰도록 구현(api.md:78).
- 트러블슈팅·반전
  - DB가 얼면 503이 아니라 504: `docker pause` 확인에서 쿼리 시한이 없어 3초를 넘김 → ISE-163에서 소켓 2초·문장 1.5초 → 세 번 모두 2.0초에 503(J-37·38).
  - Flyway V5 버전 충돌 → V6으로 이동(ISE-155)(data-model.md:14).
  - 변이 테스트가 FT-6(금액 불일치) 빈틈을 드러냄(J-51). 환불 워커 변이는 PG 멱등이 막아 `refundCount`가 같아서 `refundCalls`/`refundLookupCalls` 단정이 아니면 못 잡음(failure-scenarios.md:191).
  - Mock PG는 거절한 환불을 저장하지 않아 `refund resolve --close-failed` 종결 분기를 실제 Mock으로는 못 타서 `PgClient` 스파이로 대체(failure-scenarios.md:137).
  - 콘솔 환불은 우리 `pg_refund_id`가 없어 `findRefund`로 확인되지 않음(N31 한계, 토스 어댑터 M4에서 재검토)(failure-scenarios.md:52,179).
- 문서 간 모순·불일치(행을 나눔)
  - README.md:24는 SA 변경 요청을 "S-1~S-30"으로, sa-change-requests.md는 S-31까지 있다(J-57).
  - README.md:5는 "구현 전이며 코드는 아직 없다", 같은 README(:51)와 api.md·data-model.md·failure-scenarios.md는 구현 완료·구현 메모를 다수 적는다. README 상단 갱신이 안 됨(모순).
  - README.md:3은 SA MASTER v3.2를 기준이라 적고 sa-change-requests.md:151은 v3.3을 기준으로 적는다(모순).
  - failure-scenarios.md:3과 invariants.md:8은 "아직 실행하지 않았다/구현 전"이라 적으나 failure-scenarios.md:99-195는 실행 결과를 적는다(모순).
  - 환불 워커(invariants.md:75)와 결제 재확인(domain-and-states.md:281) 둘 다 "PG 호출 9회"라 적는데 같은 값인지 문서만으로는 구분 안 됨(확인 못 함).
- AI 판단: 2026-10-27 결제 설계 빈틈 검토 기록과 2026-11-03 반효승 DLQ·대사 API 리뷰 기록이 근거로 인용됨(README.md:46, contracts.md:140). 둘 다 담당 범위 밖이라 내용은 읽지 않았다.


### 조각 K — 4. 서사 소재

- 설계가 바뀐 순간
  - 환불 소비자 역직렬화: `ErrorHandlingDeserializer` 대신 리스너에서 문자열을 읽어 영구 오류로 DLQ(원문 보존). ADR 0020 해당 행을 ISE-136 구현 결정이 대체(events-and-reliability.md:63).
  - 정리 작업 잠금을 세션 advisory lock에서 트랜잭션 advisory lock으로(ADR 0022 결정 6과 다름, ADR 본문은 안 고침, events-and-reliability.md:219).
  - Ops 지표 `payment_ops_command_total` Counter → `payment_ops_actions` Gauge(ISE-158, observability.md:35).
  - 작업 라벨 `job` → `task`(ISE-195, observability.md:39).
  - PG 구조: 서버 전역 단일 PG → 결제별 `pg_provider`로 `PgClients.of`(ISE-231, 2026-11-10 개정, pg-integration.md:9, 21).
  - pg-approve 허가 10 → 30(ISE-238, 2026-11-12), pg-billing 그룹 신설(ISE-236, 0018 개정 2026-11-12).
  - 토스 orderId 결정적 생성(ADR 0023 2026-11-09 개정, pg-integration.md:58).
  - 연결 풀 5 → 20(S-21), DB 시한 4종(ISE-163, 2026-11-03 compose `docker pause` 재현).
- 트러블슈팅·반전
  - ISE-254: 11/12 19:22 배포 후 변경 목록 약 1,460쪽이 1,000쪽 상한을 넘어 대사 RC-1~6 실패. 로컬 확인에서 놓친 이유(변경 목록 쪽 수를 호출 수 추정에 안 넣음) → 워터마크 이어 읽기·회차당 예산 2,000쪽(events-and-reliability.md:288-301).
  - 11/12 재기동 직후 승인 p99 1.0~1.4초 → 웜업 도입(ISE-243). 원인(첫 실행 초기화 vs JIT)은 못 가름(events-and-reliability.md:189-202).
  - "검사하지 않아서 0" 착시(events-and-reliability.md:286).
  - `docker pause` DB 정지에서 504 발생, validation-timeout 기본 5초가 3초 Gateway를 넘는다는 것을 Opus 리뷰에서 찾음(events-and-reliability.md:179).
  - EXPLAIN으로 `IN` → `OR` 한 줄 변경 12.7ms → 0.06ms(observability.md:55).
  - 테스트 파일을 `rules/`에 두면 Prometheus가 규칙으로 읽어 기동 실패(compose에서 실제로 겪음, observability.md:89).
- 거부·수용 판단
  - TX1 커밋 응답 유실 후 재시도로 `REQUESTED`를 이어받는 안 거부(INV-1 위반 위험, ADR 0048, events-and-reliability.md:183).
  - 대기 시간 도입 거부(허가 30에서 이득 7.7%뿐, pg-integration.md:114).
  - 인스턴스 증설·PG 호출 비동기화 안 함(pg-integration.md:126).
  - `Prevented`를 토스에서 안 내는 거짓 보장 거부(pg-integration.md:198).
  - Ops `refund retry`에서 `pg_requested_at`을 비우지 않음(ISE-160, events-and-reliability.md:156).
  - 토스 Mock 건너뛰기를 꺼진 기본값으로(pg-integration.md:189).
  - full jitter 대신 equal jitter(events-and-reliability.md:43).
- AI 검증 로그로 쓸 만한 직접 언급: Opus 리뷰가 validation-timeout 문제(events-and-reliability.md:179)와 RC-7 18만 건 0.25~0.4초 측정(:253)을 냄. 거부/수용 판단 기록 경로는 이 문서에 없음.


### 조각 L — 4. 서사 소재

- **설계가 바뀐 순간**
  - 2026-10-30: Opus 리뷰와 사용자 결정 4건으로 mock-pg 설계 개정(차단 B1, 중요 I1~I8). 판단 기록 `docs/ai-judgments/junhyun/2026-10-30-mock-pg-design-review.md`(mock-pg.md:235). `count`가 동시 실행을 해결한다는 이전 근거가 틀렸다고 정정(mock-pg.md:242).
  - 2026-11-02: 구현 PR 2개(ISE-102·103)를 6단계로 쪼갬. 스파이크(ISE-101, PR #178)에서 Valve 방식이 계획대로 동작함을 증명(mock-pg.md:295,299).
  - 구현 중 계획 변경: 환불 @Tag 제외 계획을 버리고 처음부터 계약 23개 전부를 실제 서버로 돌림(mock-pg.md:303,307).
  - 11/12: 배포로 원장이 사라져 환불이 NOT_REFUNDABLE → ISE-253로 거래 ID 서명 도입(mock-pg.md:348). 약 4만 6천 건은 구제 불가(mock-pg.md:358).
  - ISE-235: 빌링키 메모리 Set → HMAC 서명 방식(mock-pg.md:340,343).
- **트러블슈팅·반전**
  - 재시작하면 CANCELED였던 주문이 다시 승인되어 INV-8 위반처럼 보임(mock-pg.md:69).
  - 원자 연산 밖의 멱등 조회가 만드는 동시 환불 중복 구멍(mock-pg.md:259).
  - Content-Length를 크게 주는 방식은 "잘못된 이유의 통과"라 기각(mock-pg.md:205).
  - 변이 테스트: Valve의 CLOSE_NOW 한 줄을 빼면 테스트가 실패하는 것으로 증명(mock-pg.md:220).
- **거부·수용 판단**
  - 기각: 같은 JVM(A), Testcontainers(B), 2안(예외로 끊기), Content-Length 방식, CONFLICT 주입 모드, 가짜 서버를 정본으로 쓰기.
  - 채택: 별도 프로세스 bootJar(C), Valve 1안, 409 REQUEST_IN_PROGRESS, count·target 확장, 변조형 모드를 test 전용으로 분리.
  - 보류: compose 프로필 override(팀 확인 대기, S-30).
- **수동 복구 문서의 정직한 한계 표기**: jar 실행·EC2·실제 Mock PG 연결 실행은 확인하지 않은 명령이 4개(manual-recovery.md:3), 콘솔 환불은 아직 반영 불가(manual-recovery.md:519).


### 조각 M — 4. 서사 소재

- 설계가 바뀐 순간
  - 2026-11-04 처음 순서 P0 → P1 → … → P7, "B2가 B1보다 먼저 막히면 P4를 앞으로 당긴다"는 조건. 2026-11-05 측정에서 조건에 걸려 P0'(배포 메모리)·P0''(G1)를 넣고 P4a/b/c를 P1 앞으로(PLAN :201,443)
  - 2026-11-05 G1을 먼저 쟀다: 판정 기준을 측정 전에 정함(A 기존 순서 유지/B P4 먼저) → 결과 B(PLAN :414-440)
  - 2026-11-06 1차 개정(R1·S4·P4d 추가, P2·P5 조건부, P6·P7 범위 밖) → 같은 날 2차 개정(기준을 수정 없는 기본 코드로, G1·R1을 단계로). 이유: 미리 고친 환경이면 "GC 때문에 멈추고 회복 못 하는" 과정이 사례에서 빠짐(PLAN :152,189-201)
  - 2026-11-06 결정: 최종까지 local, 배포 환경 성능 테스트는 나중(결정 9)
  - 2026-11-09 측정 기기 교체(구 노트북 → i9-14900HX), base부터 재측정(PLAN :494)
  - 2026-11-10 k6 메모리 상한 2.5 → 6GiB(TS-69)로 판단 정정, P4b·P4c 결론도 정정(STORY :213)
  - 2026-11-11 측정 회고 뒤 세 측정 재실행(잠금 장애 재측정, 매진 피한 스트레스, CPU 상한 F) 및 차단기 값 비교(STORY :131-176)
  - 2026-11-15 LOOP 시작: ISE-261, 5회 반복(최대 5회) 개선안 → 독립 검토 → 적용 → 정합성 관문 → 교차 측정 → 기록
- 줄거리가 바뀐 반전
  - "정합성을 고쳐 잃은 처리량을 P1·P3로 되찾는다"로 계획 → 새 기기에서 잃은 처리량이 측정에 안 보임 → "정합성 수정은 측정 가능한 비용 없이 들어갔다"(PLAN :554, STORY :87)
  - 1,280명 한계: 처음 "Gateway와 부하 생성기 탓"으로 K1 개선을 멈췄다가 k6 상한 6GiB로 올리니 같은 코드가 100% → 판단 정정(STORY :213)
  - "1,280명까지 버틴다"는 매진 뒤 좌석 조회만 받은 한계. 매진을 피한 섞인 부하는 320명까지(640명에서 Gateway 즉시 503)(STORY :152,193)
  - 구 기기에서 5-2의 503을 Eureka 이탈로 해석했다가 Gateway 인증 차단기로 정정(PLAN :391,487-490)
  - 잠금 장애 1차 값(A 25%·B 77.5%)은 잠금이 인기 구역과 덜 겹쳐 좋게 나온 것이고 재측정 12.7%·53.5%(STORY :139-141)
  - LOOP 반복 2에서 R2-b는 예상과 달리 효과 없음(409 97%가 해당 일을 안 함), 반복 3의 예상은 검토가 낮췄는데 실측은 더 큼(+50%), 반복 5는 성공 요청이 -17%라 되돌림
  - LOOP 반복 3이 성능 개선 중 같은 키 재요청의 403 오판 경합까지 해소(LOOP :135)
- 트러블슈팅(참조 ID): TS-69(부하 생성기 한계를 서버 한계로 오판), TS-65~68(정합성 결함·해결), TS-74(L1 닫힌 모델 측정 도구 한계), TS-3(멱등키 PK 충돌 재응답)
- 거부/수용 판단
  - 거부: 대기 인원 128(p95만 2배), 가상 스레드(나은 점 없음), 풀 6·자리 5 채택(다른 담당 영역 → 근거 전달), 반복 5 빠른 거절(되돌림), P4d(조건 맞아도 미적용), P3·P2·P5(한계점 없음), 토큰 조회를 거절 뒤로 미루기, 배치를 공유 Redis에 두기(5.5배 느림), 처음 30초 워밍업(60초로)
  - 수용: G1 먼저 재기(판정 기준 사전 고정), 기본 코드부터 단계 쌓기, 측정 환경에서만 Gateway 차단기 완화, 대기 인원 64, 문장 합치기(반복 3·4)
  - 독립 검토 반영: 반복 2(자리 5 후보 제외, 탐침 설계), 반복 3(예상 +40% → +10~30%, 403 경합 발견), 반복 4(부분 인덱스 리터럴, 접수형·재요청 성립 확인), 반복 5(deleted_at 불거름 등 5가지)
  - AI 판단 기록 문서(경로만 참조): docs/ai-judgments/kwonwoo/2026-11-05-g1-first-then-decide-p4-order.md, 2026-11-06-k6-steps-from-unmodified-base.md, 2026-11-07-k1h-relax-gateway-breaker-in-measurement.md, 2026-11-09-k1-stop-at-p4c-skip-p4d.md(미읽음)


### 조각 N — 4. 서사 소재

- 설계가 바뀐 순간
  - 2026-11-02 만료 스케줄러 구현, `FOR UPDATE SKIP LOCKED`와 전역 잠금 순서는 ADR 0035에 따라 M4로 미룸(HE :57-61). 2026-11-03 검토 R3에서 "ID만 SKIP LOCKED로 뽑으면 무의미"라고 SA 8-4 문장을 바로잡음, S4에서 건별 트랜잭션 SKIP LOCKED 적용 → C6 헛수고 100 → 0.
  - 2026-11-06 C3 목표 1,000ms → 1,500ms(CR :90-96).
  - 2026-11-09 P4d 조건이 맞았지만 안 넣음 → 2026-11-10 k6 상한이 가린 전제였음이 드러나 재검토 → 2026-11-16 ISE-264로 구현(P4D :14-18, :47).
  - 2026-11-12 Gateway 차단기는 ADR 0050으로 이관, B는 dev 반영(ISE-193), C는 PR #529, D는 ISE-237로 합쳐짐(CP :3).
  - 2026-11-15~16 접수형 재측정 계획 작성, 직접 발행 구현(AS :3).
- 트러블슈팅과 반전
  - 데드락 가설(TS-1)이 재현 안 됨: 코드 정렬·단일 UPDATE로 이미 막혀 있었음 (REV R1, CR :8).
  - TS-3 증상이 500이 아니라 자기 요청 409 (REV R2, CR :52).
  - "메모리를 3배로 늘려도 GC를 바꿔도 회복 못 함" → 원인은 시한 계층 설계(OR :25, :266).
  - R2·R3 시작값(연결 200)이 오히려 나빴고 리틀의 법칙으로 30으로 재계산, 경합 작을 땐 3% 차이 → R3 비제안(OR :211-214, :245, :254).
  - 처리량 기준 사전 선언 후 못 넘음을 인정(OR :205).
  - Redis 캐시가 "더 큰 규모 대비"처럼 보였지만 CPU 4.5배·GC 17배, 선점 지표 앞선 것은 부하 감소 착시(LR :9, :68-77).
  - Gateway 차단기가 서버 5xx 0인데도 열림 → k6 메모리 상한(TS-69)이 측정 병목(CP :28-29).
  - 로컬 노트북 경합으로 R1 사용자 성공 1,463~3,432건 편차, 3회차는 메모리 부족 중단 후 재실행(OR :219, :255).
- 거부/수용 판단
  - 수용: C3 목표 변경 (가) 안; 고르지 않은 안은 (나) `statement_timeout` 병용, 목표 1초 유지(CR :95, :103).
  - 수용: 멱등키 REQUESTED 선기록 vs advisory lock(REV :34-35, :68).
  - 수용: 직접 발행 커밋 뒤·acks 1초 대기·실패해도 202(AS :39-41). 판단 기록 링크는 docs/ai-judgments/kwonwoo/2026-11-16-ise18-direct-publish-after-commit.md(AS :39).
  - 거부/보류: P4d를 2026-11-09에 넣지 않음, 판단 기록 docs/ai-judgments/kwonwoo/2026-11-09-k1-stop-at-p4c-skip-p4d.md(P4D :16); 선택지 1·2·3안, 추천 2안(P4D :34-38).
  - R3 값(연결 30)은 제안하지 않음(OR :254); 차단기 이슈 초안 올리지 않음(CP :20).
  - 데드락을 보이려고 운영 코드에 나쁜 구현을 넣지 말 것(REV :27).


### 조각 O — 4. 서사 소재

- 설계가 바뀐 순간
  - 2026-10-29: 선점 경로를 `/api/v1/reservations`에서 `/schedules/{scheduleId}/reservations`로 변경. 이유: Gateway가 본문을 읽지 않고 입장 토큰 회차를 비교하기 위해(R/contracts.md:11).
  - 2026-10-29~11-01: 계약 ② 확정(반효승 답 없이 통합 담당이 확정)(R/contracts.md:5).
  - 10/30: MVP와 고도화 분리. 이유: 처음부터 방어 장치를 다 넣으면 "기본 구현에서 어떤 결함이 얼마나 났는지" 실험 데이터로 증명 못 함(R/mvp-and-troubleshooting-roadmap.md:79-80).
  - 11/11: 예매 취소 범위·정책 결정(추첨 예매 취소 제외)(R/contracts.md:272, 291, 328).
  - 11/12: 접수형 입력 검증·사전 409(ISE-16, ADR 0056·0057), 입장 토큰 소모(ADR 0053).
  - 11/13: 결제 토큰 발급기를 seathold → reservation으로 이동(ISE-273)(R/contracts.md:244).
  - 11/13: 구조 개선 세 가지(ADR 0062) 로컬 측정(R/seathold-structure-improvements.md:3).
  - 11/14~15: 계측 보강·만료 주기·서명 이동, 11/15 같은 시점 재측정(R/seathold-pre-deploy-measurements.md:170-173).
  - 11/16: 캐시 사전 거절, 풀 스윕, 자동 배정 기준값, 50 vs 500ms 비교.
- 트러블슈팅·반전
  - 닫힌 모델로 503 장치를 재다 왜곡 → 열린 모델(L9) 도입(TS-74)(R/seathold-structure-improvements.md:199-205).
  - 상한이 잠금 장애에선 구원, 정상 피크 1,800/s에서는 오히려 TIMEOUT 28~43%로 악화(스레드 고갈)(R/seathold-pre-deploy-measurements.md:225-232).
  - 개선 2는 측정에서 효과 미확인(편차 안), 그래도 남김(R/seathold-structure-improvements.md:159).
  - L9 300/s 503이 순서를 바꾸자 뒤집힘 → 재측정으로 편차 확인(R/seathold-pre-deploy-measurements.md:206).
  - 로컬에선 풀 16까지 꺾이지 않았지만 배포(X1)에서는 풀 10이 p95를 1.06s → 1.83s로 악화 → config-repo 유지(R/seathold-pool-limit-sweep.md:197, 249).
  - 0ms에 가까운 lock_timeout은 롤백 중 짧은 잠금도 건너뜀(TS-73)(R/seathold-lock-timeout-load-compare.md:98).
  - 자동 배정이 매진 뒤 거절 경로가 비싸 한계 약 820/s로 먼저 무너짐(R/seathold-auto-assign-baseline.md:34).
  - 중간에 멈춘 측정이 테스트 데이터를 오염시킴 → L10 첫 두 회 버림, 회차 12개·21,086건이 EXPIRED로 남음(R/seathold-pre-deploy-measurements.md:162, 212).
  - 커넥션 대기 초과가 계약(503)과 달리 500으로 나가는 공통 예외 처리기 문제 발견(ISE-293)(R/seathold-structure-improvements.md:216, 222).
- 거부/수용 판단(AI 검증 로그 후보)
  - 다른 에이전트의 사전 거절 분석 4개 주장을 측정과 코드로 대조해 일부 수용·일부 기각(R/seathold-pre-reject-cache-measure.md:69-80; 판단 기록 `docs/ai-judgments/kwonwoo/2026-11-16-verify-other-agent-precheck-analysis.md`).
  - 자동 배정은 직접 고치지 않고 담당(강민)에게 제안(R/seathold-pre-reject-cache-measure.md:92).
  - 개선 안내 문서 작성은 Claude Code가 했다(R/seathold-structure-improvements.md:3).
  - AI 판단 기록 참조: `docs/ai-judgments/kwonwoo/2026-10-30-mvp-and-troubleshooting-scope-split.md`, `2026-11-14-pre-deploy-improvements-2-5-6-and-contracts.md`(R/mvp-and-troubleshooting-roadmap.md:72, R/seathold-pre-deploy-measurements.md:5). 내용은 이 담당 파일 밖이라 읽지 않음.


### 조각 P — 4. 서사 소재(설계가 바뀐 순간·이유)

1. 정적 HTML → React SPA: Notion 결정 #7(정적 HTML·JS)을 바꿀 조건 근거로 변경, 이유는 컴포넌트가 필요해지고 화면이 늘어서(README.md:14,500).
2. 좌석표: 15,000석(811KB)을 한 번에 그리면 좌석 버튼 15,000개 → 구역 먼저 고르기, 구역 600석만 그림(README.md:203, design-system.md:272). 선택은 사용자("2026-11-04 사용자 선택").
3. 대기열 "내 순번 1" 3~3.6초 문제: 서버 쪽 개선은 별도 이슈로 미루고 화면에서 "곧 입장합니다"로 완화(README.md:169).
4. 공연 상세 패널: 배너 위로 끌어올림 → 배너 아래 열로 변경, 이유는 오픈 순간 [예매하기]가 첫 화면 안에 있어야 해서. 요약 표를 숨기고 제목 2줄로 제한(design-system.md:394-395,405).
5. 선점 결과 모름: "결과를 모른 채 새 키로 보내면 이미 잡힌 내 좌석 때문에 409"가 나서 같은 키로 재확인, 이 409를 "남이 가져감"으로 보이면 내 좌석을 잃은 것으로 안내하게 되는 버그를 셀프 리뷰에서 재현(README.md:205).
6. PG 503: 여러 번·무지연 재시도를 하지 않고 1회 + 무작위 지연(포화 시 요청이 더 몰리므로, README.md:235).
7. 시연 장애 패널: 프론트 nginx 직접 중계(인증 없음, 공개 시연 사이트에서 누구나 장애 가능)를 2026-11-09 사용자 결정으로 Gateway 운영자 경로로 변경(README.md:414).
8. 부적 배정: 무작위(50명 중 같은 펫 확률 약 64%) → 일대일 대응식으로 연속 1,200건 안 겹치지 않게(README.md:431).
9. 문구: 해요체 → 합쇼체(2026-11-16), 다크 모드 "하지 않는다" → 두 벌(2026-11-09).
10. 토스 연동: 토스 테스트 결제창도 카드사 인증에 실제 휴대폰·생년월일을 요구해 시연을 막아(11/10 확인) "토스연동 건너뛰기"(`mockBypass`)를 둠(design-system.md:294).
11. 요청 로그 패널: 응답 헤더엔 traceId가 없고 본문엔 성공·실패 모두 있어 한 곳(`send()`)에서 기록(README.md:341).
12. 전역 `overflow-wrap: anywhere` 때문에 대기열 순번이 "123,45 / 6번"으로 끊긴 일 → cqw 크기+줄바꿈 금지(design-system.md:396).


## 6. (조각별) 시각 자료 목록

### 조각 A — 5. 시각 자료

| 위치 | 종류 | 내용 | 브로셔 쓸모 |
|---|---|---|---|
| `docs/core/SA MASTER.md:222-232` | ASCII 흐름 | 일반 예매·추첨 예매 핵심 흐름과 실패 분기 | 상 |
| `docs/core/SA MASTER.md:266-285` | mermaid flowchart | 로그인 → 대기열 → 입장 토큰 → 선점 → 결제, Redis 장애·409·만료·PG 무응답·환불까지 실패 분기 | 상 |
| `docs/core/SA MASTER.md:593-609` | mermaid flowchart | 시스템 구조도(프론트·Gateway·3서비스·DB·Redis·Kafka·PG·Eureka·Config) | 상 |
| `docs/core/SA MASTER.md:653-690` | mermaid sequence | 일반 예매 동기형 시퀀스(대기열 진입 → 토큰 → 좌석 조회 → 선점 → 결제 → 이벤트 → 확정) | 상 |
| `docs/core/SA MASTER.md:711-731` | ASCII 상태 머신 | 예매·결제·환불·추첨·응모 상태 전이 | 중 |
| `docs/core/SA MASTER.md:765-800` | mermaid flowchart | EC2 1대 compose 인프라 구조도(컨테이너 16개, 포트) | 상 |
| `docs/core/SA MASTER.md:1736-1988` | mermaid ERD 5개 | user·reservation(공연·좌석·예매)·reservation(추첨·알림)·payment·공통 테이블. 그림 4·5는 M3 테이블 미반영(`docs/core/SA MASTER.md:1991`) | 중 |
| `docs/core/SA MASTER.md:2073-2096` | ASCII 디렉터리 트리 | 모노레포·4계층 패키지 구조 | 중 |
| `docs/core/SA MASTER.md:1115-1129` | JSON 예시 | 성공·실패·검증 에러 응답 형식 | 하 |
| `docs/core/SA MASTER.md:1235-1243` | JSON 예시 | 좌석 조회 응답 | 하 |
| `docs/README.md:54` | 외부 링크 | 좌석 선점 Kafka 설계 Artifact(비공개, 공유 설정 필요) | 중(비공개) |
| `docs/README.md:45` | 로컬 HTML | `docs/sa-review/assets/seat-hold-kafka.html` | 중 |
| 그 밖의 파일 | — | README.md, AGENTS.md, milestones.md, learning-guide, quality-gates에는 그림이 없음 | 해당 없음 |


### 조각 B — 5. 시각 자료

| 위치 | 내용 | 브로셔 쓸모 |
|---|---|---|
| DEP:9-19 mermaid flowchart | 개발자 → GitHub → Actions → GHCR → Jenkins 폴링·SSH → 배포 EC2, 슬랙, 사용자 80 | 중 |
| STORY:184-192 요청 순서 표 | Netty → HttpHandler 데코레이터(H1) → HttpWebHandlerAdapter(관측 시작) → RequestLogWebFilter → 라우트 매칭 → GlobalFilter -200(상한) → -100(인증) | 상(거절 위치 설명) |
| RES:29-34 호출 순서 표 | 시간 제한 → 차단기 → 벌크헤드 → Redis 조회 | 상 |
| STORY:276-288 누적 표 | 회차별 굿풋·p95·서버 CPU(차트화 후보) | 상 |
| STORY:42-51 단계 표 | 9단계 흐름 요약 | 중 |
| DEP:389-395 메모리 예산 표 | 서비스별 힙·비힙·지표 밖 | 중 |
| DEP:184-188 Eureka 캐시 3겹 표 | 30·30·35초 → 5초 | 하 |
| 이미지 파일 | 없음(그림 파일 참조 없음) | — |


### 조각 C — 5. 시각 자료

- OB:7-13 mermaid flowchart: 서비스 컨테이너 로그(docker logs) → Grafana Alloy(traceId 추출, 서비스 이름 라벨) → Loki, Grafana → Loki·Zipkin. 브로셔 쓸모: 중.
- OB:24-27 ASCII 로그 형식 예시(시각, 레벨, PID, 앱 이름, 스레드, traceId-spanId, 로거, 메시지). 브로셔 쓸모: 하.
- OB:161 건별 알림 로그 줄 형식 코드 블록. 브로셔 쓸모: 하.
- OB:211 Grafana 대시보드 주소 형식. 브로셔 쓸모: 하.
- `infra/grafana/dashboards/request-logs.json`(Grafana 요청 로그 대시보드, 참조만, 사본에서 미열람). 브로셔 쓸모: 상(시연 장면).
- LT, OP: 그림 없음(표 위주).


### 조각 D — 5. 시각 자료

이 문서에 이미지·mermaid·ASCII 다이어그램은 없다(표만 있음). 아래는 참조 링크.

| 경로 | 내용 | 브로셔 쓸모 |
|---|---|---|
| `docs/design/infra/open-peak-load-plan.md` | 트래픽 집중 구간 계획(문서 링크, OPS:5) | 하 |
| `docs/design/infra/load-test.md` | 환경 기록표 양식(OPS:33,64) | 하 |
| `docs/design/infra/gateway-auth-redis-resilience.md` | 4차 판단 흐름(OPS:569) | 중 |
| `docs/design/infra/single-host-msa-resource-isolation.md` | 자원 격리 설계 메모, M1·A1(OPS:772,838) | 중 |
| `docs/decisions/0005-auth-redis-fail-closed.md`, `0050`, `0052`, `0060` | ADR 링크 | 중 |
| `docs/learning/youngeun/qna/2026-11-10-queue-poll-rate-and-interval.md` | 폴링 수 노트(OPS:352) | 하 |
| `docs/learning/kangmin/qna/2026-11-11-observation-cost-hypothesis-and-handler-decorator.md`, `2026-11-11-h1-reinterpret-netty-cost.md` | H1·H2 설명(OPS:950,1032) | 하 |
| `docs/ai-judgments/kangmin/2026-11-11-breaker-first-for-gateway-cpu-saturation.md`, `2026-11-12-merge-and-deploy-before-review.md`, `daily/2026-11-11.md` | AI 판단 기록(OPS:616,838,1035) | 중 |
| `docs/ai-judgments/youngeun/2026-11-10-e-values-warm-stop-rule.md` | 웜업 멈춤 기준 판단(OPS:435) | 중 |
| `loadtest/k6/README.md` | 시나리오 스크립트 상태 | 하 |
| 원자료(git 제외) `loadtest/k6/results/g6/`, `g7/`, `g8/` 등 | 계열 표의 원자료 | — |

브로셔용 그래프 소스로 쓸 만한 표: A1 5차 비교(OPS:886-891), G 반복 p95(OPS:516-523), 상한값별 S1 단계 한계(OPS:1199-1204), PAY 허가 10→30(OPS:1310-1314).


### 조각 E — 5. 시각 자료

- ISO:127 "4 vCPU 막대 하나에 gateway 50~55%, reservation 23%, alloy 7~10%, kafka·redis, 컨테이너 밖 13~17% 쌓기 + OOM 3회" — 설계안만 있고 파일 없음. 브로셔 쓸모: 상.
- ISO 1절 표(1,000/1,500/2,000/s 굿풋·p95·CPU), ISO 2절 방법별 표 — 표 형태. 쓸모: 중.
- NUM 3-4 PG 지연 스윕 표(속도 3.17/6.27/15.9), 병목 정리 표, 3-5 전/후 표 — 표 형태, 그래프화 가능. 쓸모: 상.
- NUM 버킷 누적 표(le 458.13~733.01) — 히스토그램 그래프 소스. 쓸모: 중.
- SYN 6-3 핵심 비교 표 — 쓸모: 상.
- mermaid·ASCII 다이어그램·이미지 파일 참조: 세 파일 모두 해당 없음.


### 조각 F — 5. 시각 자료

| 경로(줄) | 내용 | 브로셔 쓸모 |
|---|---|---|
| docs/design/lottery/README.md:43-50 | Gateway → Reservation(lottery/reservation 모듈, outbox) → Kafka → Payment → PG 구조도(ASCII) | 중 |
| docs/design/lottery/README.md:74-83 | 추첨·응모·예매 상태 전이도(텍스트) | 중 |
| docs/design/lottery/README.md:55-67 | 시각별 단계 타임라인 표 | 상 |
| docs/design/lottery/details.md:95-103 | 시간 예산 표 | 중 |
| docs/design/lottery/details.md:109-129 | 실패 표 | 상 |
| docs/design/lottery/f1-fault-injection.md:141-149 | F1 시각 기록표 | 상 |
| docs/design/queue/contracts.md:37-41 | 입장 토큰 payload JSON 예시 | 하 |
| 그림·이미지 파일 참조 | 없음 | — |


### 조각 G — 5. 시각 자료

- 담당 파일 안에 이미지·mermaid·ASCII 다이어그램 경로는 없음.
- 표(브로셔 쓸모): AS 이벤트 순서 표 AS:66-72(중), 위젯 규칙 표 AS:146-158(하), REV 5겹 표 REV:18-24(상), 거부한 AI 제안 표 REV:105-113(상), 잡은 결함 표 REV:88-99(상), MVP 시나리오 결과 표 MVP:167-226(중, 숫자 풍부).
- 참조만 있고 파일은 열지 않음: 판단 기록 링크들(docs/ai-judgments/...), docs/design/payment/mock-pg.md, docs/presentation/evidence-formats.md, docs/presentation/required-deliverables.md.


### 조각 H — 5. 시각 자료

| 위치 | 내용 | 브로셔 쓸모 |
|---|---|---|
| docs/sa-review/05-feedback-improvement-proposal.md:219-238 | mermaid 서비스 플로우 차트(로그인 → 대기열 → 입장 토큰 → 선점 → 결제 → 확정/만료/환불/UNKNOWN/NEEDS_MANUAL_CHECK, 실패 분기 포함) | 상 |
| docs/sa-review/03-seat-hold-kafka-design.md:30-34 | ASCII: 회차 ID 키일 때 파티션 1개만 일하는 쏠림 도식 | 중 |
| docs/sa-review/03-seat-hold-kafka-design.md:113-135 | ASCII: 혼합형 선점 흐름(Redis Lua → Kafka → Consumer → DB) | 상 |
| docs/sa-review/03-seat-hold-kafka-design.md:5 | 시각화 Artifact 링크(https://claude.ai/artifact/3dWEW9UCxs2opzgLqFwsHH)와 로컬 사본 docs/sa-review/assets/seat-hold-kafka.html(문서에서 참조만, 열어 보지 않음) | 중(확인 못 함) |
| docs/sa-review/05-feedback-improvement-proposal.md:176-191 | 메모리 예산 표 | 중 |
| docs/sa-review/05-feedback-improvement-proposal.md:154-160 | 보안그룹 포트 표 | 중 |
| docs/sa-review/04-mentor-feedback-1026.md:115-119, :135-140 | 레이스 컨디션 방식 비교 표, 기능별 fail-open/closed 표 | 중 |
| docs/sa-review/01-sa-1st-review.md:22-28 | 서비스별 판정 표(codex vs Claude vs 종합) | 중 |
| docs/sa-review/06-layered-architecture-check.md:26-31 | user-service 계층 구성 표 | 하 |
| docs/guides/deploy-server-access.md:86-91 | 배포 실패 알림 4종 표(주황/회색/빨강) | 하 |
| 그 외 | 이미지 파일 참조 없음 | — |


### 조각 I — 5. 시각 자료

| 대상 | 내용 | 브로셔 쓸모 |
|---|---|---|
| K:274-334 | 파티션 레인 도식 3종: 키=회차ID(P1에 10개 몰림, 다른 Consumer 대기) / 키=좌석ID(원자성 깨짐 2가지) / 키=userId(고르게 분산, 같은 사용자 순서 보장) | 상 (왜 userId 키인지 한눈에) |
| K:372-392 | 타이머 타임라인 막대: 클릭부터 센 경우 4분30초 vs HELD 확정부터 온전한 5분 | 중 |
| K:440-446 | Outbox 부적합 이유 체인 도식 | 하 |
| K:471-510 | 혼합형 7단계 인터랙티브 시퀀스(정상 선점/경합 패배/Kafka 전송 실패/DB 2차 방어 4개 토글) | 상 |
| K:517-538 | Redis Lua 스크립트와 DB 조건부 UPDATE SQL 코드 블록 | 중 |
| K:545-564 | Redis TTL 5:30 vs DB 5:00 타임라인 | 중 |
| K:600-621 | v1/v2 파이프 굵기 비교(개념 표현, 측정값 아님이라고 명시) | 상 (개선 전/후 스토리, 단 수치 인용 금지) |
| 이미지 파일 | assets 폴더 내 이미지 참조 없음, HTML은 단일 파일(인라인 CSS/JS, 구글 폰트만 외부) | — |
| B 폴더 | mermaid·이미지·ASCII 다이어그램 없음(표와 목록 위주) | 하 |


### 조각 J — 5. 시각 자료

| 위치 | 내용 | 브로셔 쓸모 |
|---|---|---|
| domain-and-states.md:248-257 | 결제 상태 머신 ASCII(REQUESTED → APPROVED/DECLINED/UNKNOWN/NMC, REFUNDED) | 상 |
| domain-and-states.md:288-294 | 환불 상태 머신 ASCII | 중 |
| api.md:23-34(표) | 승인 처리 10단계 표(TX1/PG/TX2) | 상 |
| api.md:11-17 | PG 결정 규칙 의사코드 | 하 |
| invariants.md:12-22(표) | INV-1~9 요약표(주 방어/보조/ADR) | 중 |
| invariants.md:136-146(표) | 단일 방어 점검 9건 | 중 |
| contracts.md:64-73, 96-97 | 이벤트 봉투·페이로드 JSON 예시 | 하 |
| data-model.md:77-84, 115-124 | append-only 트리거, 환불 `INSERT … SELECT` SQL | 하 |
| failure-scenarios.md:101-111, 143-185 | FT-1~FT-6 결과 표(주입 → 최종 상태) | 상 |
| failure-scenarios.md:189-194 | 변이 확인 표(변이 → 실패한 시나리오) | 상 |
| 이미지 파일·mermaid | 없음 | — |


### 조각 K — 5. 시각 자료

| 자료 | 내용 | 브로셔 쓸모 |
|---|---|---|
| pg-integration.md:119-122 표 | Erlang B 허가 수별 거절 확률(계산값) | 중 (그래프로 바꾸면 허가 30의 근거를 보여줌) |
| pg-integration.md:102-106 표 | PAY1 허가 10/30·대기 0/200ms 비교 측정 | 상 |
| observability.md:93-100 표 | 대시보드 5줄 구성 | 하 |
| `infra/grafana/dashboards/payment.json`, `infra/prometheus/rules/payment-alerts.yml` | 파일 경로만 언급, 그림 없음 | 중(실제 대시보드 캡처가 있으면) |
| mermaid·ASCII 다이어그램·이미지 | 세 문서 모두 없음 | — |


### 조각 L — 5. 시각 자료

| 위치 | 내용 | 브로셔 쓸모 |
|---|---|---|
| docs/design/payment/mock-pg.md:93-96 | 상태 전이 ASCII 도식(없음 → IN_FLIGHT → APPROVED/DECLINED, 없음 → CANCELED) | 중(늦은 승인 방지 설명) |
| docs/design/payment/mock-pg.md:98-104 | 상태×동작(approve/cancel/GET) 표 | 중 |
| docs/design/payment/mock-pg.md:154-165 | 모드×호출 적용 표(장애 주입 메뉴) | 중 |
| docs/design/payment/mock-pg.md:176-179 | 프로필별 허용 모드 표 | 하 |
| docs/design/payment/manual-recovery.md:34-53, 58-82 | inspect·outbox requeue 실제 실행 출력 예 코드블록 | 상(운영 명령 시연 화면 후보) |
| docs/design/payment/manual-recovery.md:95-107 | 전이별 이벤트·이력·감사 표 | 중 |
| docs/design/payment/manual-recovery.md:135-141 | payment resolve PG 결과별 계획 표 | 상(PG 근거로 결정이 갈리는 구조) |
| docs/design/payment/manual-recovery.md:479-486 | 대사 규칙 RC-1~8 복구 표 | 중 |
| 이미지·mermaid | 두 문서 모두 없음 | — |


### 조각 M — 5. 시각 자료

| 위치 | 내용 | 브로셔 쓸모 |
|---|---|---|
| PLAN :179-182 (코드 블록 ASCII) | K1·K1-H 단계 순서(base → G1 → R1 → P4a → P4b → P4c → P1 → F, 측정 K1-H 순서) | 중: 단계 흐름 도식의 원본 |
| PLAN :505-508 (코드 블록) | 실제로 넣은 단계(K1·K1-H·F) | 중 |
| PLAN :512-519 단계별 병목 표 | 병목 이동: GC → 커넥션 대기 → DB 조인 → 직렬화 | 상: 계단형 그래프 데이터로 최적 |
| STORY :49-56 단계 표 | 문제·해결·왜 이 방법인가·L 성공률·S 버틴 단계 | 상 |
| PLAN :523-534, STORY :182-191 base vs F 표 | 발표 비교점 | 상 |
| STORY :120-126 표 | 잠긴 행 집중 시 실패율 곡선(A·B) | 중: 선 그래프화 가능 |
| STORY :145-151 표 | 매진 피한 스트레스 단계별 성공률·p95 | 중: 한계 단계 그래프 |
| STORY :168-173 표 | 차단기 P/T 비교(4코어/상한 없음) | 중 |
| LOOP :42-55, :93-105, :142-153, :183-193, :219-230, :260-267 표 | 반복별 전후 비교 | 상: 5회 반복 개선 막대 그래프 |
| LOOP :248-255 표 | 반복 1~5 판정 요약 | 상: 한 장 요약 |
| 이미지·mermaid 파일 | 없음(문서 안에 이미지 링크 없음, ASCII 코드 블록 2개와 표만) | — |


### 조각 N — 5. 시각 자료

| 경로/위치 | 내용 | 브로셔 쓸모 |
|---|---|---|
| docs/design/reservation/k6-overload-recovery.md:30-34 | ASCII 요청 경로 도식(k6 → Gateway → Tomcat → Hikari → PostgreSQL)과 단계별 시한·한도 | 상 (타임아웃 계층 불일치를 한눈에) |
| docs/design/reservation/k6-overload-recovery.md:98-105 | ASCII 원인 사슬 도식(무거운 좌석 조회 → 커넥션 5개 → 스레드 대기 → 포기한 요청 쌓임 → 10분) | 상 |
| docs/design/reservation/concurrency-results.md:40-48 | 단계(S0~S4)×시나리오(C1~C7) 비교 표 | 중~상 (전후 변화 한 장) |
| docs/design/reservation/concurrency-results.md:74-80 | 단계별 처리량·p95 표(중앙값, 범위) | 중 |
| docs/design/reservation/k6-overload-recovery.md:176-190 | R0/R1/R1+R2+R3 비교 표 | 상 |
| docs/design/reservation/k6-p4b-local-vs-redis.md:9-15 | 로컬 vs Redis 대표 지표 비교 표 | 상 |
| docs/design/reservation/async-seathold-remeasure-plan.md:95-103 | Outbox 적체 SQL(그림 아님) | 하 |
| 이미지 파일·mermaid | 없음 (이 11개 파일에는 이미지 참조·mermaid 없음) | — |


### 조각 O — 5. 시각 자료

| 경로/위치 | 내용 | 브로셔 쓸모 |
|---|---|---|
| R/seathold-structure-improvements.md:129-136 | 개선 2 전후 트랜잭션 단계 순서 표(좌석 UPDATE를 끝으로 이동) | 중 |
| R/seathold-structure-improvements.md:32-42 | 선점 1건 9단계와 "다투나" 표 | 중 |
| R/seathold-structure-improvements.md:171-182 | 상한 없음/상한 4·대기 200ms/상한 4·대기 0 비교 표 | 상 |
| R/seathold-pool-limit-sweep.md:210-237 | 풀·상한 조합별 의도 실패율/p95, DB CPU 표 | 중 |
| R/seathold-pre-reject-cache-measure.md:22-46 | 캐시 끔/켬 비교 표 | 상 |
| R/seathold-auto-assign-baseline.md:19-28 | 직접 대 자동 배정 표 | 중 |
| R/seathold-lock-timeout-load-compare.md:75-85 | 50ms vs 500ms 표 | 상 |
| R/seathold-metrics.md:159-175 | Grafana `좌석 선점` 패널 표(대시보드 `infra/grafana/dashboards/seathold.json` 참조) | 하(참조 JSON 내용은 읽지 않음) |
| R/contracts.md:89-105, 126-169 | 201·202·조회 응답 JSON 예시 | 하 |
| R/load-test-scenarios.md:29-36 | L1~L6 시나리오 표 | 중 |
| 이미지·mermaid·ASCII 다이어그램 | 담당 12개 파일에 없음 | — |


### 조각 P — 5. 시각 자료

##### 5-1. 이미지 (samples/screens/, 25개, 합계 약 1,804 KB(`ls -l` total 1804 블록 기준). 해상도는 헤더 바이트로 확인)

쓸모 기준: 브로셔에 그대로 싣기 좋은 정도. 공통 주의: 해요체 문구(M-1), 가상 공연 "노을섬 단독 콘서트 〈새벽의 좌석〉"(지어낸 이름이라 사용 가능), 캡처 일부는 ISE-210 전 UI(P-55).

| 파일 | 크기(byte) | 해상도 | 보이는 것 | 쓸모 | 이유 |
|---|---|---|---|---|---|
| queue-waiting.png | 44,681 | 1280×720 | 대기열 화면. 단계 1 "대기열", 공연 줄, "순번을 자동으로 확인하고 있어요", 내 순번 15,234번, 예상 대기 약 5분, 진행 막대(약 17%), 안내 두 줄 | 상 | 대기열을 한눈에 보여 줌. 숫자가 크다. 흰 바탕이라 브로셔 삽입 쉬움 |
| queue-unstable.png | 45,163 | 1280×720 | 위 화면에서 상태 줄만 경고색 "연결이 잠시 불안정해요. 곧 다시 시도할게요". 순번 15,234번 그대로 | 상 | "순번은 유지되고 안내만 바뀐다"는 장애 대응 설계를 대비로 보여 줌(queue-waiting과 짝) |
| queue-expired.png | 40,479 | 1280×720 | "입장 시간이 지나 줄에서 빠졌어요 / 다시 줄 서면 맨 뒤에서 시작해요", [다시 줄 서기], [공연 정보로 돌아가기] | 중 | 실패가 막다른 길이 아니라는 원칙 예시. 시각 임팩트는 낮음 |
| seat-selected.png | 113,068 | 1440×1000 | 첫 샘플. VIP A1구역 2석 선택(검은 체크), 4등급 좌석표(추첨석 빗금 포함), 선택 요약 330,000원, 입장 유효 09:55, 오른쪽 아래 샘플 조작판이 보임 | 중 | 좌석표 그림이 풍부하나 조작판이 찍혀 있고 이 방식은 구역 먼저 방식으로 대체됨 |
| seat-conflict.png | 120,318 | 1440×1000 | 경고 "이미 선택된 좌석입니다 … 남은 좌석은 그대로 두었으니 1석만 다시 골라 주세요". 선택 1/2매, 합계 165,000원, 버튼 "1석 더 골라 주세요" 비활성. 조작판 보임 | 중 | 이선좌(409) 설계의 핵심 장면이나 첫 샘플이라 조작판이 찍힘. seat-sections-conflict가 더 최신 |
| seat-held.png | 133,810 | 1440×1000 | "좌석을 잡았어요" 대화상자, VIP A1구역 1열 1번·7번 각 165,000원, 결제 가능 04:47, [결제하러 가기]. 뒤는 흐리게 | 중 | 좌석 확보 순간. 조작판이 대화상자 밖에 찍힘 |
| seat-sections-selected.png | 110,403 | 1440×1100 | 구역 먼저 고르기. 구역 25칸 배치도(A~Y, C 매진, A·F에 선택 배지 1), 등급 가격(VIP 165,000·R 143,000·S 112,000), F구역 좌석표, 선택 2석 합계 308,000원, 입장 유효 09:58. 조작판 없음 | 상 | 현재 채택된 좌석 화면. 25구역 15,000석 대응 설계가 보임. 조작판 없어 깔끔 |
| seat-sections-conflict.png | 113,698 | 1440×1100 | 경고 "F구역 5열 12번이 방금 다른 분께 선택됐어요. 남은 좌석은 그대로 두었어요", F구역 남은 334(전 335), 선택 1/2매 165,000원 | 상 | 이선좌 장면을 현재 UI로 보여 줌. "335 → 334"로 한 석 줄어든 숫자가 증거가 됨 |
| seat-sections-375.png | 78,418 | 375×1300 | 모바일 폭. 입장 유효 09:58 칩이 본문 위로 내려옴, 구역 4칸 배치, 매수 선택, F구역 좌석표가 가로로 스크롤 | 중 | 반응형 증거. 세로로 길어 지면 활용 어려움 |
| payment-ready.png | 72,219 | 1280×900 | 결제 단계 3. 결제 가능 04:50, 결제 금액 2매 VIP 165,000 + R 143,000 = 합계 308,000원, 카드 선택, [308,000원 결제하기], 예매 번호 R20261104-8F3K2Q | 상 | 4분 50초 설계(P-18)와 금액이 한 화면에 있음 |
| payment-checking.png | 79,513 | 1280×900 | 버튼 자리가 스피너 "결제를 확인하고 있어요 / 결제사 응답을 기다리는 중이에요. 화면을 닫아도 결제는 그대로 진행돼요." | 상 | "결과를 모르면 실패라고 하지 않는다"의 대표 화면. 결제 202·504 서사 |
| payment-declined.png | 78,590 | 1280×900 | 위험 알림 "결제가 거절됐어요 … 좌석을 다시 골라 주세요", [좌석 다시 고르기], 결제 버튼 비활성 | 중 | 실패 처리 예시. 분량 차이는 작음 |
| reservations-confirmed.png | 55,005 | 1280×900 | 단계 4 완료. "예매가 확정됐어요", 티켓 2매 VIP/R 티켓 번호 T20261104-0001-A3-7·T20261104-0002-F5-12, 결제 금액 308,000원, [내 예매 목록] [다른 공연 보기] | 상 | 여정의 끝 장면. 현재 UI는 가로 티켓으로 바뀜(P-55) |
| reservations-held.png | 45,908 | 1280×900 | 상세(결제 대기). "아직 결제하지 않았어요 / 오후 8:14:50까지 결제하지 않으면 좌석이 풀려요", [결제하기] | 하 | 일상적 상태 화면 |
| reservations-list.png | 58,819 | 1280×900 | 내 예매 목록 4건: 예매 확정 308,000원, 결제 대기 132,000원, 기한 만료 99,000원, 좌석 반환 112,000원, [더 보기] | 중 | 상태 배지 4종이 한눈에 보임 |
| lottery-form.png | 81,643 | 1280×1037 | 추첨 응모(번호 7·회차 9·추첨석 10석). 응모 중 배지, 응모 기간·추첨 시각, 경고 "자동결제 카드를 확인해 주세요", 1매 응모하기, 자동결제 카드 "기록 없음" | 중 | 추첨 흐름 설명용. 정보량 많고 시각 임팩트는 낮음 |
| lottery-drawing.png | 77,527 | 1280×973 | "응모했어요 / 응모 완료", 응모 매수 2매, 결과 발표 2026.11.22 04:00 이후, "추첨하고 있어요" 정보 알림, 카드 등록됨(•••• 4242·11.20 등록) | 하 | 중간 상태 |
| lottery-paid.png | 75,644 | 1280×995 | "당첨되고 결제까지 끝났어요" 초록 체크, [예매 보기] | 중 | 추첨 자동결제 해피패스 결말 |
| home.webp | 124,080 | 1280×1700 | 홈. 레이저 그림 대표 배너 "이선좌 시연 공연"(1/4 캐러셀, 예매하러 가기), "티켓 오픈 예정 1" 카드(12월 01일 화요일, D-26), "지금 예매 중 12" 포스터 격자 5열(레이저·무대 조명·노을·보케·기하·오로라·재즈 등) | 상(주의) | 서비스 첫 인상으로 가장 강함. 단 공연명이 "[ISE-113 확인]…", "E2E 예매 공연 …", "MVP 확인 공연 …"(개발 데이터, P-56)이라 그대로는 부적합. 포스터 그림만 크롭해 쓰는 안 |
| home-375.webp | 48,206 | 375×1500 | 모바일 홈. 같은 배너와 2열 포스터 격자 | 하(주의) | 같은 개발 데이터 노출 |
| concert-detail.webp | 76,558 | 1280×1564 | 공연 상세. 어두운 배너 "이선좌 시연 공연", 3회차, 1인 최대 4매, 오른쪽 티켓 모양 패널(회차 선택, [예매하기], 라디오 목록 3회차), 공연 소개, 회차 일정 표, 푸터 "부트캠프 최종 프로젝트 5조" | 상 | 오픈 순간 [예매하기]가 첫 화면에 있는 설계(P-22)의 증거. 어두운 바탕이라 브로셔에 삽입 시 강한 대비. 이 캡처는 ISE-187 시점(다크 배너)이며 12절 이후 배너 모양과 다를 수 있음(캡처가 이전 모양, 문서 design-system.md:394 "예전에는 흐린 그림 + ink-900 막"). 문구 해요체 |
| login.webp | 30,508 | 1280×1000 | 로그인. 왼쪽 어두운 그림 패널 "오픈 순간에도 흔들리지 않는 예매" + 3줄 소개, 오른쪽 폼, 푸터 | 중 | 카피("오픈 순간에도 흔들리지 않는 예매")가 브로셔 헤드라인 후보. 화면 자체는 평범 |
| my-info-card.webp | 35,378 | 1280×1133 | 내 정보. 프로필 카드 "데모님", 회원 정보 4칸(회원 번호 42), 자동결제 카드 실물 카드 모양 "시연용 카드 •••• 4242 · 11.05 등록" | 중 | 실물 카드 그림이 예쁨. 기능 서사는 약함 |
| my-reservations-375.webp | 26,628 | 375×1100 | 모바일 내 예매. 날짜 칸이 색으로 구분(확정 인디고, 반환 회색) + 절취선 티켓 카드, 가로 메뉴. 공연명 "ISE-121 추첨 확인 공연", "MVP 확인 공연 f3d47c"(개발 데이터) | 하(주의) | 티켓 모양 디자인은 좋으나 개발 데이터 노출 |
| ticket.webp | 35,172 | 1280×994 | 확정 티켓 2장(Ticket 1/2, 2/2, F구역 1열 1번·2열 1번, R 배지, 티켓 번호, 바코드). 제목 "ISE-121 추첨 확인 공연" | 중(주의) | 어두운 티켓 카드 모양이 시각적이나 ISE-210 전 모양(P-55)이고 개발 데이터 노출 |


##### 5-2. HTML 샘플 6개 (빌드 없이 브라우저로 열기. 조작판 "샘플 전용 조작(실제 화면에 없음)", `#demo=` 해시, `&panel=0`으로 조작판 숨김)

| 파일 | 크기(byte) | 줄 | 내용 | 쓸모 | 이유 |
|---|---|---|---|---|---|
| queue.html | 18,071 | 289 | 대기열. 상태 7종(줄 서는 중, 대기 중, 연결 불안정 503, 차례가 됨, 줄에서 빠짐 404, 오픈 전 409, 예상 못한 오류). 샘플 값 순번 15,234, 초기 18,420, 3초마다 150~180씩 줄어드는 흉내(`position - 150 - random(0~29)`), 예상 대기 `ceil(순번/50)`초 | 상 | 가장 가볍고 실제 순번 줄어드는 움직임이 있어 라이브 시연·GIF에 적합. 해요체 |
| lottery.html | 23,590 | 311 | 추첨 응모+자동결제 카드. 상태 8종(응모 전, 응모 완료, 추첨 중, 당첨(자동결제 중), 당첨·결제 완료, 예비, 미당첨, 자동결제 실패). 샘플 값은 P-36 | 중 | 상태 전환 시연은 좋으나 추첨 서사가 이미 캡처로 대체 가능 |
| payment.html | 22,176 | 294 | 결제. 상태 9종(결제 전, 요청 중, 확인 중 202·504, 승인 뒤 확정 확인, 거절 200 DECLINED, 결제사 장애 503, 결제 가능 시간 지남, 수동 확인 409, 승인 뒤 만료(환불)). 합계 308,000원 | 상 | 장애·불확실 상태를 한 파일에서 모두 넘겨 볼 수 있어 "결과를 모르면 실패라 하지 않는다" 시연에 최적 |
| reservations.html | 22,922 | 288 | 예매 결과·상세·내 예매 목록·빈 목록 5화면. 티켓 번호 T20261104-0001-A3-7 등 샘플 값 | 중 | 완료 화면 중심, 데모 흐름 말미 |
| seat-sections.html | 34,449 | 486 | 구역 먼저 고르기. 25구역×(20열×30번)=600석 생성, 시드 난수로 판매율(VIP 70%·R 45%·S 25%, C구역 매진), 2매 선택 시 선점 성공/409/결과 모름 세 결과를 조작판으로 선택, 입장 만료 버튼 | 상 | 가장 상호작용 풍부. 이선좌(409) 라이브 시연 가능. 현재 채택안 |
| seat-selection.html | 34,235 | 529 | 첫 좌석 샘플. 블록 8개(VIP A1·A2, R B1·B2, S C1·C2, A D1·D2), 열당 12석, 추첨석 빗금(S 3~4열), 입장 유효 598초(09:58)·결제 290초(04:50) 카운트다운 실제 동작, 선점 성공 201/이선좌 409/접수형 202 | 중 | 카운트다운이 실제로 줄어드는 시연 가능하나 구역 먼저 방식(seat-sections)으로 대체된 구버전. 샘플 간 등급 가격 불일치(M-2) |

각 HTML은 Pretendard CDN(jsdelivr 1.3.9)을 외부에서 불러옴(각 파일 헤드 7번째 줄). 외부 네트워크 없이 열면 글꼴이 대체됨. 그 외 외부 자원 없음(스크립트는 인라인).


## 7. (조각별) 못 읽은 파일과 이유

### 조각 A — 6. 못 읽은 부분과 이유

없음. 담당 파일 7개를 끝까지 읽음(SA MASTER 1~2433줄, milestones 1~100줄, README 1~46줄, AGENTS 1~54줄, docs/README 1~54줄, learning-guide 1~29줄, quality-gates 1~25줄). 이 담당 파일에는 측정 결과 표가 거의 없어 측정 숫자는 SA MASTER 안의 요약 인용(11/10 L1 p95 10.58ms, 11/12 배포 DB 약 156만 행, 좌석 응답 약 830KB)에 한정되며 그 원문은 다른 담당 파일에 있다.


### 조각 B — 6. 못 읽은 부분과 이유

없음. 4개 파일을 첫 줄부터 마지막 줄까지 모두 읽었다(README 28줄, DEP 439줄, RES 137줄, STORY 353줄). README에서 참조하는 observability.md·load-test.md·open-peak-scenarios.md 등은 담당 밖이라 읽지 않았다.


### 조각 C — 6. 못 읽은 부분과 이유

없음. 세 파일 모두 끝까지 읽음(LT 154줄, OB 242줄, OP 415줄).


### 조각 D — 6. 못 읽은 부분과 이유

없음. 1,345줄 전체를 읽었다.


### 조각 E — 6. 못 읽은 부분과 이유

없음(세 파일 전부 끝까지 읽음). 다만 본문 안의 링크 대상 문서(open-peak-scenarios.md, 판단 기록, ADR 등)는 담당 범위 밖이라 읽지 않았다.


### 조각 F — 6. 못 읽은 부분과 이유
없음. 6개 파일 모두 끝까지 읽음. 단 numbers-and-measurement.md, ADR 0051·0053, 판단 기록 등 참조 문서는 담당 밖이라 읽지 않았다.


### 조각 G — 6. 못 읽은 부분과 이유

없음. 담당 8개 파일을 전부 읽었다. 단 파일 안에서 링크된 다른 문서(판단 기록, ADR, mock-pg.md 등)는 담당 범위 밖이라 읽지 않았다.


### 조각 H — 6. 못 읽은 부분과 이유

없음 (담당 14개 파일을 끝까지 읽음). 단, 03 문서가 참조하는 docs/sa-review/assets/seat-hold-kafka.html과 06 문서가 참조하는 docs/ai-judgments/kangmin/2026-10-27-layered-architecture-check7.md는 담당 범위 밖이라 열어 보지 않았다. 이 담당 범위 밖의 측정 결과(K1 포함)는 확인 못 함.


### 조각 I — 6. 못 읽은 부분과 이유

없음 (10개 파일 전부 끝까지 읽음. 단 이 문서들이 가리키는 고정 커밋 GitHub 링크·Notion 원문은 외부라 읽지 않음).


### 조각 J — 6. 못 읽은 부분과 이유

없음. 담당 8개 파일을 끝까지 읽었다. 단 문서가 링크한 ADR(0016~0029, 0038, 0042, 0048, 0055 등)과 AI 판단 기록 파일은 담당 범위 밖이라 읽지 않았다.


### 조각 K — 6. 못 읽은 부분과 이유

없음. 세 파일 전부(301+112+210줄) 끝까지 읽었다. 다만 원문이 참조만 하는 `docs/design/infra/open-peak-scenarios.md`, ADR 0018·0020·0022·0023·0029·0048, `docs/learning/junhyun/qna/2026-11-12-erlang-b-bulkhead-sizing.md` 등은 담당 밖이라 읽지 않았다.


### 조각 L — 6. 못 읽은 부분과 이유

없음. 두 파일을 끝까지 읽었다(manual-recovery.md 527줄, mock-pg.md 359줄).


### 조각 M — 6. 못 읽은 부분과 이유

없음. 담당 3개 파일(PLAN 586줄, STORY 254줄, LOOP 276줄)을 끝까지 읽었다. 다만 본문이 링크로 가리키는 다른 문서(k6-overload-recovery.md, concurrency-results.md, k6-p4d-review.md, k6-p4b-local-vs-redis.md, k6-contract-proposals.md, k6-deploy-steps.md, seathold-pre-deploy-measurements.md, ai-judgments/learning 문서, 측정 브랜치 STEPS.md)는 담당 범위 밖이라 읽지 않았고, 위 숫자는 담당 3개 파일의 서술만 옮겼다. 줄 번호는 사본의 Read 줄 번호이고, 일부 행은 같은 표의 여러 줄을 묶어 범위로 적었다(PLAN의 5-1~5-3 구 기기 값은 5-5절 새 기기 값과 섞지 말 것).


### 조각 N — 6. 못 읽은 부분과 이유

없음 (11개 파일 전부 끝까지 읽음). 다만 파일이 참조하는 외부 문서(k6-seathold-bottleneck-plan.md, k6-seathold-story.md, STEPS.md, ADR 0032·0034·0035·0050·0057·0062 등)는 담당 범위가 아니라 열지 않음.


### 조각 O — 6. 못 읽은 부분과 이유

- 없음(12개 파일 전부 끝까지 읽음). 단, `R/seathold-perf-loop.md`(276줄)는 담당 목록에 없어 읽지 않았고, 문서가 참조하는 concurrency-results.md, ADR, 학습 노트, AI 판단 기록, Grafana JSON은 담당 밖이라 읽지 않았다.
- 큰 출력은 임시 파일로 분할해 읽었으며 줄 번호는 사본 파일 줄 번호와 같다.
- 12개 파일 중 contracts.md:2절 이후 JSON 예시, linear-issues-after.md의 이슈 본문은 설계·계획 문서라 측정 숫자가 거의 없어 카드를 설계 요약 위주로 만들었다.


### 조각 P — 6. 못 읽은 부분과 이유

- 샘플 HTML 6개 중 CSS 블록(`<style>` ~ `</style>`): queue.html은 100~139줄만, 나머지 5개는 1~12줄(토큰 선언 앞부분)만 확인하고 나머지 CSS 규칙은 읽지 않았다. 이유: 브로셔용 사실(숫자·문구) 원장이라 레이아웃 CSS는 대상 밖이라고 판단했고 토큰 값은 design-system.md 2절과 같다고 파일 헤더 주석이 밝힘(확인 못 함: CSS 값이 문서와 실제 일치하는지는 대조하지 않음).
- 사본에는 이 문서가 참조하는 ADR 0040, `frontend/README.md`, `docs/guides/demo-scenarios.md`, 학습 노트, 판단 기록, `tools/charm-art`, `docs/presentation/brand/`가 담당 범위 밖이라 읽지 않았다(원문은 링크만 인용).
- 이미지 25개는 전부 Read로 열어 확인했다. 단 인물·실제 공연 이미지는 없고 모두 지어낸 데이터나 직접 그린 그림이다. 이미지 안의 개별 포스터 그림 8종의 원본(`tools/concert-art/`)은 범위 밖.
- 줄 번호는 사본(`scratchpad/src`)의 실제 줄이다. 이 문서의 "ISE-xxx 시점"·날짜는 원문 표기를 옮긴 것이며 실제 커밋 시각과 대조하지 않았다.


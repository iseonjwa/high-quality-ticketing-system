# 사실 원장 C: AI 판단 기록과 학습 노트 전수조사

조사 범위는 `docs/ai-judgments/**`와 `docs/learning/**`다. 조사일은 2026-11-16이고 원본은 두 가지다.

- origin/dev `486501b7`(2026-11-16 15:34 KST): 두 폴더 파일 683개.
- 로컬 작업 트리(dev `a4246009`, 미커밋 포함): 666개. origin에만 있는 파일 25개(kwonwoo·junhyun·hyoseung의 11/13~16 기록), 로컬에만 있는 파일 7개(kangmin의 11/16 판단 3건과 11/16 학습 노트 4건)가 있다.
- 아래 숫자는 origin/dev에 로컬 전용 7개를 겹쳐 올린 합본 트리(690개)에서 센 것이다. origin만의 숫자와 로컬만의 숫자가 갈리는 곳은 따로 적었다.
- 원문은 옮겼고 판단자의 말은 바꾸지 않았다. 괄호 속 "계산"은 이 조사에서 직접 나눈 값이다.

---

## 1. AI 판단 통계

### 1-1. 세는 방법 (직접 센 것)
1. 공통 표: `docs/ai-judgments/README.md` 「팀 공통 판단」 표에서 `| 20xx-` 로 시작하는 행을 파싱했다. 이 표는 머리말 `shared: 예`인 개별 기록과 1대1이다(origin 208행 = shared 예 208건).
2. 개별 기록: `docs/ai-judgments/<이름>/*.md`(README·daily 제외) 머리말(`judge`, `verdict`, `area`, `presentation`, `shared`, `date`)을 파싱했다.
3. 일지: `docs/ai-judgments/<이름>/daily/*.md`에서 `- [판정]` 으로 시작하는 줄을 셌다. 일지 한 줄은 개별 기록과 겹칠 수 있고, 겹침은 제거하지 않았다. 그래서 2번과 3번은 더하지 않는다.
4. 판단자 이름은 정규화했다(`Junhyun An`→junhyun, `이강민`·`GGangMY`→kangmin, `최권우`·`KWON-WOO`→kwonwoo, `반효승`→hyoseung, `팀 (전달: …)`→팀).
5. 주 단위는 월요일 시작이다(10/19주=10/23 1건, 10/26주, 11/02주, 11/09주, 11/16주).

### 1-2. 결과 요약

| 기준 | 전체 | 수용 | 부분 수용 | 거부 | 보류 |
|---|---|---|---|---|---|
| origin/dev 공통 표 (shared 예) | 208 | 99 | 37 | 67 | 5 |
| 로컬 README 공통 표 | 201 | 94 | 35 | 67 | 5 |
| origin/dev 개별 기록 파일 | 241 | 102 | 40 | 94 | 5 |
| 합본(origin + 로컬 전용 3건) 개별 기록 | 244 | 102 | 40 | 97 | 5 |

- 로컬 README는 origin보다 뒤처졌다. origin 표에만 있는 9행(11/13~16 junhyun·kwonwoo·hyoseung 등)이 로컬에 없고, 로컬 표에만 있는 행이 2개(`kangmin/2026-11-16-scarcity-scenario.md`, `kangmin/2026-11-16-lottery-scenario3-remeasure.md`) 있다. 208 − 9 + 2 = 201이다(계산).
- 로컬 전용 개별 기록 3건은 모두 kangmin의 거부다. `2026-11-16-ise230-bulkhead-checkbox-by-test.md`는 공통 표에 없다.
- 개별 기록 241건 중 `shared: 예` 208, 아니오 33. `presentation: 후보` 103, 아님 138. 공통 표 안의 발표 후보는 100, 아님 108이다.
- 거부 비율은 기준마다 다르다(공통 표 67 ÷ 208 = 32%, 개별 기록 94 ÷ 241 = 39%, 합본 97 ÷ 244 = 40%, 계산). 인용할 때는 어느 기준인지 함께 적는다.
- 발제 필수 "거부 1건 이상"은 공통 표 거부 67건으로 채운다.

### 1-3. 팀원별
공통 표(origin 208, 팀 전달 건은 "팀"):

| 판단자 | 건수 | 수용 | 부분 수용 | 거부 | 보류 |
|---|---|---|---|---|---|
| kangmin | 93 | 39 | 10 | 41 | 3 |
| junhyun | 59 | 31 | 13 | 14 | 1 |
| kwonwoo | 31 | 18 | 8 | 4 | 1 |
| youngeun | 15 | 7 | 4 | 4 | 0 |
| hyoseung | 2 | 2 | 0 | 0 | 0 |
| 팀(전달 포함) | 8 | 2 | 2 | 4 | 0 |

개별 기록 파일(origin 241):

| 판단자 | 건수 | 수용 | 부분 수용 | 거부 | 보류 |
|---|---|---|---|---|---|
| kangmin | 118 | 41 | 15 | 59 | 3 |
| junhyun | 66 | 32 | 12 | 21 | 1 |
| kwonwoo | 35 | 20 | 9 | 5 | 1 |
| youngeun | 15 | 7 | 4 | 4 | 0 |
| hyoseung | 7 | 2 | 0 | 5 | 0 |

합본으로 kangmin은 121건(거부 62)이다. 폴더별 파일 수 합계는 kangmin 121, junhyun 66, kwonwoo 35, youngeun 15, hyoseung 7이다.

### 1-4. 영역별 (개별 기록 머리말 `area`, origin 241)
`area`는 자유 입력이라 표기가 흩어져 있다. 같은 낱말로 묶었다.

| 영역 | 건수 |
|---|---|
| 기타 | 50 |
| 협업 | 44 |
| 범위·일정 | 36 |
| 추첨 | 28 |
| 대기열 | 21 |
| 그 밖(설계·측정·운영·개발 도구 등 18종, 1건씩) | 19 |
| 동시성 | 14 |
| 결제 | 12 |
| 서비스 경계 | 6 |
| 계층형 아키텍처 | 6 |
| 이벤트 신뢰성 | 5 |

"기타" 50건은 Gateway 상한·차단기·인증·프론트·배포 서버 같은 건이 상당수 들어 있다(제목으로 확인). 발표에서 "Gateway 영역 N건"으로 세려면 제목 단위로 다시 분류해야 한다.

### 1-5. 날짜별 (주 단위)

| 주 | 공통 표(origin) | 개별 기록(origin) | 개별 기록의 판정 분포 | 일지 줄(합본) |
|---|---|---|---|---|
| 10/19주 | 1 | 1 | 거부 1 | 0 |
| 10/26주 | 67 | 73 | 수용 34·부분 14·거부 25 | 103 |
| 11/02주 | 44 | 54 | 수용 20·부분 11·거부 22·보류 1 | 160 |
| 11/09주 | 91 | 108 | 수용 45·부분 14·거부 45·보류 4 | 325 |
| 11/16주(11/16 하루) | 5 | 5 | 수용 3·부분 1·거부 1 | 15 |

- 하루 최다(개별 기록 합본 기준): 11/09 32건, 10/27 20건, 11/11 18건, 11/13 17건.
- 11/09주에서 거부 45건이 수용 45건과 같다. 첫 주(10/26주)는 수용 34 대 거부 25였다. 시간이 갈수록 거부 비율이 오른다(25÷73=34%, 22÷54=41%, 45÷108=42%, 계산).

### 1-6. 일지(daily) 한 줄 집계 (합본)
- 일지 파일 72개(hyoseung 21, kangmin 21, junhyun 14, kwonwoo 12, youngeun 4).
- 판정 꼬리표 줄 603개: 수용 281, 선택 140, 부분 수용 62, 거부 62, 보류 31, 지적 10, 결정 전달 6, 위임 5, 결정 2, 그 밖 4.
- 사람별 줄: kangmin 281, junhyun 138, hyoseung 103, kwonwoo 68, youngeun 13.

### 1-7. 사용한 AI (공통 표 `AI` 칸, origin 208)
Claude Code 175, Codex 10, Claude (Cowork) 6, Claude 4, "Claude Code, Opus 리뷰" 4, Antigravity (Gemini) 3, "Claude Code, codex" 2, 그 밖 4(Claude Code 두 세션, Claude Code + Claude(대기열 담당), Claude Code·Codex, Codex 에이전트 2개·재검증). 도구가 둘 이상인 행이 더 있으나 한 칸 안에 섞여 적혀 있다.

---

## 2. 대표 사례 후보 Top 12

유형 표시: [거부·측정이 우리 편], [AI 맞음·숫자로 닫음], [AI 틀림·사람이 잡음], [거부했으나 측정은 AI 편], [거부·재측정 중].
공통 표에서 shared 예인 건만 골랐고 모두 개별 기록 파일이 있다. "결과 숫자"는 근거 경로에 있는 원문 숫자다.

| # | 날짜 | 판단자 | 영역 | AI 제안 | 판단 | 근거 | 결과 숫자 | 근거 경로 |
|---|---|---|---|---|---|---|---|---|
| 1 [거부·측정이 우리 편] | 2026-11-11 | kangmin | Gateway 동시 처리 수 상한 | 6차 실험 세션: 상한 64 → 128(판정 기준을 지키는 가장 큰 값). 메인 세션: 64 유지 | 128 제안 **거부**, 64 유지 추천 수용. 말: "64 유지하고 다음 가설 진행해" | 2,000/s에서 64와 128의 굿풋 차이 25건(1.5%)은 같은 128을 두 번 잰 차이(5%) 안. p95는 64가 약 절반. 128은 처리 중 수가 auth-redis bulkhead(50) 위로 올라가 두 종류 503이 섞임 | 2,000/s 굿풋 L128b 1,721 대 L64 1,696. p95 87ms 대 162ms(1.86배, 계산). 거절 25%→40%여도 한 건당 Gateway CPU 0.66~0.71ms로 같음. 이후 11/13 G1 반복에서 2,500/s 굿풋 2,092~2,145, OOM·재시작 0 | `docs/ai-judgments/kangmin/2026-11-11-keep-in-flight-limit-64.md:20-21,45`, `docs/learning/kangmin/qna/2026-11-16-what-if-much-more-users.md:32` |
| 2 [거부·측정이 우리 편] | 2026-11-13 | kwonwoo | 좌석 선점(동시성) | AI 추천: 잠긴 좌석을 기다리지 않는 `SKIP LOCKED`(잘못된 409는 드물 것) | 구현 뒤 측정에서 성공이 줄자 처음 제안 **거부**, 새 추천(`lock_timeout` 500→50ms) 수용 | "선점에 실패할 사용자가 잠깐 잠근 테이블 행에 다른 사용자가 접근하지 못하는 것은 문제가 된다." AI 자신도 "처음 예상이 틀렸다"고 보고 | C2(10명이 좌석 4개를 겹쳐 요청, 500건) 성공 83건 → 56건(−32%, 계산). 50ms로 바꾸니 C2 성공 78·87건, C3 최대 대기 221~225ms. 번짐 시작점 초당 약 20건 → 45~150건 | `docs/ai-judgments/kwonwoo/2026-11-13-reject-skip-locked-choose-50ms.md:21-22` |
| 3 [AI 틀림·사람이 잡음 + 측정으로 대조] | 2026-11-16 | kwonwoo(최권우) | 좌석 선점(동시성) | 다른 기기 AI 분석: "캐시로 아끼는 몫이 작다, 이 브랜치 방식만 남길 수도", "이력·HELD 기록을 앞으로 옮길 여지" | **부분 수용**. 측정과 대조한 이 세션의 검증을 받아들이고 "근거를 문서화해 기록하라" 지시. 캐시는 켠 채 유지 | "아끼는 몫이 작다"는 한 건(0.25~0.45ms)으로는 맞아도 결론은 틀림. 처리 한계는 한 건이 자리를 쥐는 시간에 반비례. "옮길 여지"는 틀림(둘 다 예매 ID 필요, 예매는 좌석 가격 필요) | 정상 3,000/s 의도 실패 5.5% → 0%, 자리 쥐는 시간 20~30% 감소, 정합성 위반 0. 끔 1.53ms → 약 2,600/s, 켬 1.07ms → 약 3,700/s(1.42배, 계산값) | `docs/ai-judgments/kwonwoo/2026-11-16-verify-other-agent-precheck-analysis.md:26,41`, `docs/learning/kwonwoo/qna/2026-11-16-pre-reject-cache-vs-one-statement.md:25-26` |
| 4 [AI 틀림·사람이 잡음] | 2026-11-09 | kangmin(영은 지적 전달) | Gateway 벌크헤드 | AI 코드 조사: "gateway에는 벌크헤드가 없다. 벌크헤드는 payment-service에만 있다" | 첫 결론 **거부**(틀림). 영은님이 "없으면 기본값 아니냐"고 되물어 다시 확인 | `spring-cloud-circuitbreaker-resilience4j` 5.0.3은 `enabled` 기본 true일 때 모든 리액티브 서킷 브레이커 실행에 벌크헤드를 씌움. 자동 설정의 `matchIfMissing` 기본값을 보지 않은 결론이었음. 바이트코드(`javap`)로 확인 | `auth-redis`에 기본값 동시 25, 대기 0. 넘치면 503. 배포 서버에서 실제 지표는 확인하지 않음 | `docs/ai-judgments/kangmin/2026-11-09-gateway-has-default-bulkhead.md:26` |
| 5 [AI 틀림·사람이 잡음] | 2026-10-29 | youngeun | 대기열 | 대기열 설계 설명에서 근거 3개: ① "멱등 API는 같은 결과를 줘야 한다" ② issued 선기록은 "다음 요청에서 스스로 복구" ③ "탭을 두 개 연 경우 폴링이 남는다" | 3개 모두 **거부**. 되물음 3번에 AI가 모두 인정·철회 | "POST 멱등성은 데이터를 두 번 등록하지 않는 것이기 때문에 … 응답이랑은 관련없잖아". 토큰 만료 뒤 옛 seq로 줄을 안 서고 입장하는 틈이 있음. 탭 예시는 성립 안 함 | 틈을 네 경로(진입·순번 조회·토큰 발급·입장 처리)의 테스트로 닫음. 각 테스트 이름이 파일에 있음(ISE-52 #99, ISE-55 #141, ISE-100 #319, ISE-69 #307, ISE-54 #268) | `docs/ai-judgments/youngeun/2026-10-29-queue-ai-reasoning-corrections.md` |
| 6 [AI 맞음·숫자로 닫음] | 2026-11-08 → 11-14 | hyoseung(반효승) | 좌석 배치 캐시 | 좌석 배치 캐시는 Redis(SA 원안)가 아니라 서비스 메모리(측정 근거 없는 설명) | **수용**하되 ADR 0054를 "제안" 상태로 두고, 최권우가 같은 커밋에서 설정 하나만 바꿔 잰 결과로 확정 | "Redis 캐시를 쓰면 매 요청마다 … 역직렬화를 하게 되고, 그러면 15,000개 객체가 매 요청마다 생겨 1차 개선의 의미가 줄어든다." 방향은 맞았지만 원인은 설명보다 컸음 | P4a → P4b: 초당 150 postgres CPU 136% → 21%(6.5배 감소, 계산), 커넥션 쥔 시간 21ms → 4.6ms. 초당 300 p95 2.06s → 26ms. 로컬 7.0ms 대 Redis 38.3ms(5.5배, 계산), 한 건당 CPU 약 5.5 대 25ms, GC 약 17배 | `docs/ai-judgments/hyoseung/2026-11-08-seat-layout-cache-memory-not-redis.md:34-37` |
| 7 [AI 맞음·숫자로 닫음] | 2026-11-11 → 11-13 | kangmin | Gateway 과부하 | 첫 장치로 Gateway 맨 앞 동시 처리 수 상한. 구현 세션안 500, 실험 세션안 64 | 상한·프런트 표시·64 모두 **수용**("A로 진행해") | 500은 p95를 평균 체류 시간 자리에 넣어 크게 나온 값. 리틀의 법칙은 평균에 대한 식이라 64 쪽이 식에 맞음(평균 40~45ms × 1,480/s ≈ 60~67, 계산값) | 상한 없을 때 2,000→2,500/s에서 굿풋 1,469 → 136(약 1/11, 계산), 3,000/s는 Gateway OOM. 상한 뒤 같은 2,500/s에서 굿풋 약 2,100~2,250(15.4~16.5배, 계산). gateway 테스트 107 → 118개, 변이 6개 결함 모두 실패로 잡힘 | `docs/ai-judgments/kangmin/2026-11-11-gateway-in-flight-limit-first.md:23,39`, `docs/learning/kangmin/qna/2026-11-16-what-if-much-more-users.md:21-23,33` |
| 8 [거부했으나 측정은 AI 편] | 2026-11-11 | kangmin | Gateway CPU 포화 | 차단기는 Redis 장애 전용, CPU 과부하는 맨 앞 전역 유입 제한으로 막자 | **거부**("auth-redis 차단기부터 좀 더 예민하게 세팅해서 CPU 99%·100%에 닿지 않게 해 보자") | 차단기가 재는 시간에 Gateway CPU 대기가 섞인다는 근거로, 느린 호출 기준을 낮추면 포화 전에 열릴 수 있다고 봄. 실험으로 가림 | 5차 0a·0b(100ms)와 B20(20ms) 모두 2,500/s(서버 CPU 99%)까지 차단기가 열리지 않음. 20ms 기준 느린 호출 비율 최대 24%. 가설을 닫고 다음 가설을 동시 수 상한(AI 추천 방향)으로 옮김 | `docs/ai-judgments/kangmin/2026-11-11-breaker-first-for-gateway-cpu-saturation.md:43,47` |
| 9 [AI 맞음·숫자로 닫음] | 2026-11-11 | kangmin | 관측·요청 로그 | 운영 요청 로그 선택지 A~D 제시(추천 없음) | B(실패·느린 요청만) 수용. 이후 같은 날 대조군으로 효과 확인 | JFR이 JVM 안에서 본 로그 비용은 5.2%뿐. 끄고 켠 비교로 재니 비용은 JVM 밖(표준 출력→Docker→Alloy→Loki)에 있었음 | 7차 H2(끔): 서버 CPU 1,000/s 78%→66%, 1,500/s 93%→85%, 2,500/s 굿풋 6차 L64 1,608 → 2,122(1.32배, 계산). 같은 날 번갈아 잰 B 대 끔 1,500/s 78.5% 대 79.1% | `docs/ai-judgments/kangmin/2026-11-11-request-log-failures-and-slow-only.md:16`, `docs/learning/kangmin/qna/2026-11-14-profiler-blind-spot-and-same-day-control.md:17` |
| 10 [AI 맞음·숫자로 닫음] | 2026-11-11~12 | kangmin, junhyun | 결제·추첨 허가 수 | AI 추천: 자동결제 전용 허가 `pg-billing` 3, `pg-approve` 크기는 Erlang B 손실 1% 이하로 산정. 일반 결제 24~30 | kangmin: 1·2·4번 수용, 3번(목표 초당 수)은 추천 없이 직접 30. junhyun: "추천대로!!" | 일정 도착(k6)은 몰림이 없어 503을 실제보다 낮게 보임. 평균(9.45)이 허가(10)보다 작아도 순간 몰림으로 18.9% 거절 | 허가 10·초당 30: k6 실측 503 0.31% 대 Erlang B 19.7%. 계산 모델의 600초 안 완료 예측 1,861건이 실측 1,858~1,885건과 맞음. 허가 30에서 PG 300ms 승인 한도 31/s → 91.5/s(2.95배, 계산), 요청 80건/초 거절 59.5% → 0.2% | `docs/ai-judgments/kangmin/2026-11-11-lottery-billing-isolation-and-approve-size.md:39-40`, `docs/learning/junhyun/qna/2026-11-12-erlang-b-bulkhead-sizing.md:48,68`, `docs/ai-judgments/junhyun/2026-11-12-pg-permits-approve-and-billing-group.md` |
| 11 [AI 맞음·숫자로 닫음] | 2026-11-09 | kangmin | 개발 도구(기록 검사 훅) | 파일마다 프로세스를 띄우던 방식을 `awk` 한 번, `git grep` 한 번으로. 바로 올리지 말고 변이 비교 뒤 올리자 | A안 수용. 말: "좋은 성능 개선안이고, 좋은 트러블 슈팅이니 전부 문서화 할 수 있도록해" | 프로세스 약 3,500번 × 26ms ≈ 90초로 계산해 측정 93초와 맞춤 | 92.8초 → 3.6초(25.8배, 계산, 3회 중앙값, Windows Git Bash). 변이 31가지 중 30가지 출력·종료 코드 동일, 1가지는 원본 누락이 고쳐진 것. macOS bash 3.2는 확인 못 함 | `docs/ai-judgments/kangmin/2026-11-09-records-check-25x-faster.md:35` |
| 12 [거부·재측정 중] | 2026-11-16 | kangmin | 측정 시나리오(O1) | 사용자 3,000명·좌석 15,000석 O1을 기준으로 버전 비교(11/15~16 측정 전체). 직전엔 같은 좌석에서 사용자만 3,000 → 12,000으로 늘리자고 제안 | **거부**: "총 좌석수가 15000석인데 실제로 예매되는건 훨씬 적으니 실제 환경이랑 거리가 멀어. … 대부분의 사람이 예매하지 못하고 나가야하잖아?" | 매진이 나지 않는 조건은 현실과 멂. 검토한 대안: 사용자만 늘리기(발생기 한계), 측정용 작은 공연장, 좌석 대부분을 미리 판매 상태로 채우기 | 측정 전이라 결과 숫자 없음. 일지에는 "측정용 1,500석 공연장 주 측정 + 15,000석 × 30,000명 보조 1회" 비율을 AI 추천대로 수용한 줄이 있음 | `docs/ai-judgments/kangmin/2026-11-16-scarcity-scenario.md`, `docs/ai-judgments/kangmin/daily/2026-11-16.md` |

보조 후보(Top 12 밖)
- [거부·논리 검증] 2026-11-09 junhyun: AI 리뷰의 "재시도가 REQUESTED를 이어받아 승인" 안을 같은 실패를 겪는다는 이유로 거부하고 한계를 ADR로 기록. 숫자는 없고 코드 줄 근거(`PaymentApprovalService.java:102-108`)뿐이다. `docs/ai-judgments/junhyun/2026-11-09-ise169-reject-retry-takeover.md`
- [거부·사용자 경험] 2026-11-12 kangmin: 폴링 상한 10초 → 30초 제안 거부. 폴링 약 2,780 → 1,000/s(2.78배, 계산값 표기는 원문). 근거는 사용자 경험이고 측정은 아님. `docs/ai-judgments/kangmin/2026-11-12-reject-poll-max-30s.md:20`
- [거부·죽은 값] 2026-11-12 kangmin: "Lettuce 300ms는 마지막 안전망이니 유지" 결론 거부, 노트를 정정시킴. `docs/ai-judgments/kangmin/2026-11-12-reject-keep-dead-lettuce-timeout.md`
- [부분 수용·지표로 고름] 2026-11-13 kwonwoo: ISE-17 소비자 잠금 시한 AI 추천 500ms 대신 100ms. 처리량 감소(1% 발생 가정) 약 40% 대 약 11%. `docs/ai-judgments/kwonwoo/2026-11-13-ise17-consumer-lock-and-timeout-by-metrics.md:29`
- [거부·재측정 중] 2026-11-16 kangmin: ISE-94 시나리오 3 미달(추첨 직후 선점 p95 11.06ms → 21.0ms, +90%)을 정책(D10)으로 갈음하자는 AI 추천 A안 거부, 워커 속도 상한 걸고 재측정(B안). 오류 0, 1회 측정. `docs/ai-judgments/kangmin/2026-11-16-lottery-scenario3-remeasure.md:17`
- [거부·원인 분리] 2026-11-10 youngeun: 측정 때만 차단기 기준을 푸는 안(3번) 거부. 워밍업 뒤 초당 300명(R300)은 오류 0, p95 5.51ms. `docs/ai-judgments/youngeun/2026-11-10-no-breaker-relax-for-measurement.md:33`

---

## 3. AI가 틀린 것 목록 (사람이 바로잡은 것)

원문에 "틀림", 거부 사유, 지적 꼬리표로 적힌 것만 골랐다. 여기 없는 항목은 틀렸다는 기록이 없는 것이다.

| 날짜 | 판단자 | 무엇이 틀렸나 | 어떻게 알았나 | 경로 |
|---|---|---|---|---|
| 2026-10-29 | youngeun | 멱등성 근거(응답이 같아야 한다), issued 선기록의 자동 복구, "탭 두 개" 예시 3개 | 사용자가 근거마다 되물었고 AI가 인정·철회 | `docs/ai-judgments/youngeun/2026-10-29-queue-ai-reasoning-corrections.md` |
| 2026-10-31 | hyoseung | PR #97 머지 전 dev에서 ISE-64 브랜치를 만들고 알리지 않아 엔티티가 옛 `String rowNo`로 보임 | 사용자 지적 | `docs/ai-judgments/hyoseung/daily/2026-10-31.md:4` |
| 2026-11-01 | hyoseung | 회차 좌석 생성 API를 구현한 뒤 작성자 검토 없이 커밋·push·PR(#138)까지 진행 | 사용자 지적 | `docs/ai-judgments/hyoseung/daily/2026-11-01.md:3` |
| 2026-10-31 | kangmin | PR 본문의 범위 표기 `~` 쌍이 취소선으로 렌더링됨 | 사용자가 렌더링을 보고 지적, `\~`로 고침 | `docs/ai-judgments/kangmin/daily/2026-10-31.md:4` |
| 2026-11-03 | junhyun | 작업자 패널 입력창의 추천 입력을 사용자 판단으로 읽음 | 사용자가 바로잡음 | `docs/ai-judgments/junhyun/2026-11-03-worker-pane-suggestion-not-user-input.md` |
| 2026-11-04 | junhyun | 로컬 테스트 환불의 반복 알림을 무음으로 끈 처리 | 사용자가 거두고 데이터를 정상화하게 함 | `docs/ai-judgments/junhyun/2026-11-04-local-refund-alert-fix-data.md` |
| 2026-11-05 | junhyun | 등록 기록이 없을 때도 자동결제 카드 해지 버튼을 둔 설계 | 사용자가 버튼 제거 | `docs/ai-judgments/junhyun/2026-11-05-billing-card-no-revoke-without-record.md` |
| 2026-11-05 | junhyun | 시연 모드 Grafana 링크를 localhost:3000에 고정 | 사용자가 거두고 화면을 연 호스트 기준으로 잇게 함 | `docs/ai-judgments/junhyun/2026-11-05-grafana-link-deploy-host.md` |
| 2026-11-06 | kangmin | (지적 자체가 틀림) EC2 종료 동작 설명이 틀렸다는 지적은 사실 오류가 아니라 용어 혼동으로 정리됨 | 재확인 | `docs/ai-judgments/kangmin/2026-11-06-ec2-stop-vs-terminate-wording.md` |
| 2026-11-09 | kangmin | "Gateway에는 벌크헤드가 없다" 조사 결과 | 팀원(영은) 지적 + 바이트코드 확인. 단 "Grafana에 bulkhead 지표가 있다는 것이 증거"라는 근거는 맞지 않았음(대시보드는 `application="payment-service"`만 표시) | `docs/ai-judgments/kangmin/2026-11-09-gateway-has-default-bulkhead.md:26` |
| 2026-11-09 | junhyun | "'지금 예매 중' 카드를 줄였다"는 AI 말과 달리 포스터가 그대로 길었음 | 화면을 보고 지적 | `docs/ai-judgments/junhyun/2026-11-09-home-card-shorten-not-applied.md` |
| 2026-11-09 | kangmin | 이슈·PR·코멘트를 평서체 지시형으로 씀 | 사용자가 건방지다고 지적, 합니다체로 | `docs/ai-judgments/kangmin/2026-11-09-polite-tone-for-issues-and-prs.md` |
| 2026-11-09 | kangmin | 위클리 스크럼 초안이 작업 나열이라 주간 리뷰 항목에 안 맞음 | 사용자 지적 | `docs/ai-judgments/kangmin/2026-11-09-weekly-scrum-last-week-section.md` |
| 2026-11-10 | junhyun | "남은 마감 작업" 정리가 내게 배정된 이슈만 훑어 멘션으로 받은 일(ISE-227)을 빠뜨림 | 사용자가 지적, 멘션 알림까지 다시 모음 | `docs/ai-judgments/junhyun/daily/2026-11-10.md:7` |
| 2026-11-10 | youngeun | (AI 스스로 정정) 실패 호출 최대 1.0s를 "제한시간 1s 실패"로 쓰지 않음. 측정한 코드(`f06c7d08`)에는 #463 변경이 없고 같은 구간 Lettuce 명령 최대가 120.9ms였음 | AI가 초안 전에 코드·지표를 대조해 요청과 다르게 쓰겠다고 함. 1.0s가 100ms 시한 초과의 기록값인지는 확인 안 됨 | `docs/ai-judgments/youngeun/2026-11-10-slow-call-threshold-cold-gateway.md` |
| 2026-11-11 | junhyun | AI 브리핑이 멘션 알림만 보고 이미 끝난 ISE-224 웹훅 일을 남은 일로 넣음 | 사용자 지적 | `docs/ai-judgments/junhyun/2026-11-11-briefing-listed-finished-webhook.md` |
| 2026-11-11 | kangmin | 메인 세션이 "A + D"를 "저장 한도를 5만 건으로 줄인다"로 오해해 작업 세션에 넘김 | 사용자가 "메모리 내비두고 스팬을 줄인다고 했잖아"로 바로잡음 | `docs/ai-judgments/kangmin/2026-11-11-reduce-scheduler-redis-spans-keep-zipkin-memory.md:26-29` |
| 2026-11-11 | kangmin | 설계안 첫 상한 500은 p95를 평균 체류 시간 자리에 넣어 크게 나옴(64 쪽이 식에 맞음) | 두 세션 근거를 대조 | `docs/ai-judgments/kangmin/2026-11-11-gateway-in-flight-limit-first.md:23` |
| 2026-11-12 | kangmin | "Lettuce 300ms는 안전망이라 유지" 결론. 실제로는 인증 경로에서 기능이 없는 죽은 값이고 끌 수 없어(Spring Boot 4.1.2가 항상 켬) 둔 값 | 사용자가 "걸릴 일이 없는 시한인데 왜 유지할 만하다고 하는지 이해할 수 없다"고 지적. 이어 "넌 틀렸어 고쳐" | `docs/ai-judgments/kangmin/2026-11-12-reject-keep-dead-lettuce-timeout.md` |
| 2026-11-12 | kangmin | "MD 뷰어로 보게 해 달라"를 앱 파일 패널로 처리 | 사용자가 바로잡음 | `docs/ai-judgments/kangmin/2026-11-12-local-md-viewer-before-decisions.md` |
| 2026-11-13 | hyoseung | 코드 비교로 "합친 P4b가 커넥션·상태 쿼리 면에서 낫다"고 함. 로컬 측정에서 기존 P4b와 거의 같고 낮은 부하에서는 오히려 조금 느림 | 사용자가 측정으로 지적, AI가 인정 | `docs/ai-judgments/hyoseung/daily/2026-11-13.md:19` |
| 2026-11-13 | kwonwoo | AI가 추천한 SKIP LOCKED가 여러 좌석 요청에서 성공을 83 → 56건으로 줄임("처음 예상이 틀렸다") | 구현 뒤 동시성 테스트 C2 | `docs/ai-judgments/kwonwoo/2026-11-13-reject-skip-locked-choose-50ms.md:21` |
| 2026-11-14 | hyoseung | 측정 결과를 숫자만 옮기고 정상인지 이상한지 판단하지 않은 채 다음 단계로 넘어감. 명령만 보내 지금 무슨 작업인지 모르게 함 | 사용자 지적, 단계마다 무엇을 하는지 먼저 말하게 함 | `docs/ai-judgments/hyoseung/daily/2026-11-14.md:9,11` |
| 2026-11-14 | hyoseung | ADR 0054 근거를 "미기재"로 둠. 이미 설명했던 이유(역직렬화로 매 요청 15,000개 객체)를 놓침 | 사용자 지적 | `docs/ai-judgments/hyoseung/daily/2026-11-14.md:15` |
| 2026-11-15 | hyoseung | 사용자가 정확히 말한 "동시 요청 상한은 감당 못 할 때 바로 거절하는 것"을 AI가 목적 둘로 쪼개 틀린 것처럼 설명 | 사용자 지적 | `docs/ai-judgments/hyoseung/daily/2026-11-15.md:3` |
| 2026-11-15 | hyoseung | 벌크헤드를 "Gateway가 Redis를 동시에 부를 수 있는 칸(예: 100칸)"이라고 설명. 우리 `auth-redis` 벌크헤드는 로그아웃 토큰 확인용이고 "로그아웃 확인 벌크헤드가 차도 좌석 조회는 된다"는 사용자 정리에도 AI가 맞장구 | 사용자가 검색해 짚고 코드 확인. "확인 없이 틀린 정보를 말하지 말고 … 자기 말에도 맞장구치지 말고 항상 확인하라" | `docs/ai-judgments/hyoseung/daily/2026-11-15.md:9,11` |
| 2026-11-15 | hyoseung | "예를 들어" 가정 질문을 우리 코드의 실제 동작 질문으로 읽고 "아니다"라고 답함 | 사용자가 질문을 그대로 읽게 함 | `docs/ai-judgments/hyoseung/daily/2026-11-15.md:13` |
| 2026-11-16 | kwonwoo | 다른 기기 AI의 "아끼는 몫이 작다", "이력·HELD 기록을 앞으로 옮길 여지" | 로컬 L9 측정(끔·켬 번갈아, 조건별 n=4)과 코드 대조 | `docs/ai-judgments/kwonwoo/2026-11-16-verify-other-agent-precheck-analysis.md:26` |
| 2026-11-14 | kangmin | TIL 25편의 "약 63%"가 계산상 64% | AI 검토에서 AI가 짚음(AI가 AI 산출물의 오류를 잡은 사례). 같은 일지에 TIL 26편 검증 에이전트가 짚은 서술 4개는 고치지 않고 게시(보류) | `docs/ai-judgments/kangmin/daily/2026-11-14.md:12,20` |
| 2026-11-15 | kangmin | AI 초안을 검증 에이전트가 원본과 대조해 5곳(ISE-276), 4곳(ISE-275) 고침. 점검표 C1~C13, T3 줄, 학습 노트 #627 어긋남도 고침 | 검증 에이전트 대조 | `docs/ai-judgments/kangmin/daily/2026-11-15.md:6-8` |

주의: 위 표의 2026-11-05·2026-11-09 junhyun 파일 중 일부는 제목과 폴더·파일명 앞부분만 확인했고 본문은 읽지 않았다(7절).

---

## 4. AI 활용 방식의 서사 (날짜순)

1. 2026-10-23~10-27, 설계 검증 단계. 두 에이전트 리뷰의 수정 4건 수용(junhyun 10/26 `docs/ai-judgments/junhyun/2026-10-26-two-agent-review-fixes.md`), Opus 검토 지적 반영(결제 설계 빈틈 11개, 10/27 `.../junhyun/2026-10-27-payment-design-gaps.md`). 같은 날 발제 필수 "AI 설계 검증 #7(계층형 아키텍처)" 제안 7건을 부분 수용하고 응답 레코드 분리는 거부(`.../kangmin/2026-10-27-layered-architecture-check7.md`). 10/23에는 AI의 3개·4개 구성안 대신 서비스 5개 분리를 택함(`.../junhyun/2026-10-23-service-boundary-five-services.md`). AI 추천을 근거로 받되 팀이 이미 정한 것은 다시 열지 않는다는 규칙(10/26 `role-assignment-not-reopen`)도 이때 나옴.
2. 2026-10-28~10-31, 선택지 질문 방식. 추첨을 kangmin이 담당하며 한 질문마다 선택지·추천을 받고 개별 기록 12건 가까이 남김(`.../kangmin/2026-10-30-lottery-*`). AI 추천을 거부한 것도 여럿(추첨석 미리 떼지 않는 추천, 1인 1매 추천, 취소 뒤 재응모 허용 추천). "수치화된 트래픽 트러블슈팅이 약하다"는 지적(10/27)으로 추첨 방향이 "추첨 유지 + 측정 시나리오 3종"으로 정해짐(`.../kangmin/2026-10-28-lottery-direction-measurement.md`).
3. 2026-11-01~11-04, 자동화 도입. PR CI 자동 수정을 끄자는 추천을 거부하고 켬(11/01 `.../kangmin/2026-11-01-pr-ci-auto-fix-on.md`). PR AI 리뷰(Opus 셀프 리뷰, 리뷰 에이전트) 지적 반영 패턴이 junhyun 11/02 일지에 반복(예: "Opus 셀프 리뷰 10건 중 9건 반영"). hyoseung PR #282는 AI 리뷰 지적을 선택 사항까지 모두 받아 테스트 5개에서 9개로 늘림(`.../hyoseung/2026-11-03-dlq-review-fixes-all-accepted.md`). 구현은 메인 에이전트, 리뷰는 서브에이전트라는 분담의 장단점은 학습 노트로 정리(`docs/learning/kangmin/qna/2026-11-04-main-impl-...`, 제목 앞부분만 확인). herdr 작업 분담: 구현은 옆 패널 Sonnet 작업자, 디자인은 Opus가 직접(junhyun 11/02 일지 12-13줄, 예외는 전역 지침에 반영).
4. 2026-11-04~11-06, 측정으로 설계 근거를 바꿈. kwonwoo가 AI의 JUnit 근사 부하 측정을 거부하고 k6로 재설계(11/04 `.../kwonwoo/2026-11-04-k6-over-junit-load-approximation.md`: 같은 JVM이라 생성기와 앱이 CPU를 나눠 쓰고 Gateway를 안 거침). kangmin은 K1 local 결과를 ISE-25 기준선으로 인정하자는 제안을 "측정 대상이 다르다"며 거부(11/06). 이 시점부터 거부 사유가 "측정 조건"으로 바뀜.
5. 2026-11-05, 시연 준비. 리허설 전 main 동결, herdr 병렬 작업 미루기, 장애 주입 빼기, 영역별 분담 진행을 모두 거부(`.../kangmin/2026-11-05-demo-*`). AI가 안전 쪽으로 늦추자고 할 때 일정 쪽으로 돌려세우는 패턴이 이어짐(11/08 서버 측정 일정 거부, 11/10 개선·재측정 앞당기기, 11/11 7차 측정 당일 진행, 11/12 요청 로그 PR 머지·배포 선조치, 11/15 main 뒤 재측정 당일).
6. 2026-11-08~11-09, AI 주도 측정 준비. 측정 모드(`measure-mode.sh`) 운영 규칙, Slack 알림, 측정자가 직접 켜고 끄기(11/09 `.../kangmin/2026-11-09-measure-mode-*`). 에이전트로 k6를 돌릴 때 토큰이 많이 드는 원인(진행 표시줄 출력, 느린 기록 검사 훅)을 진단(`.../kangmin/2026-11-09-agent-token-k6-output-and-hook.md`). 기록 검사 훅을 변이 31가지로 검증한 뒤 25배 빠르게(11/09 `records-check-25x-faster`). 학습 노트 질문이 하루 40건 넘게 쌓임. kangmin 11/09 학습 노트 제목들(`docs/learning/kangmin/qna/2026-11-09-*`) 참조.
7. 2026-11-10, 에이전트가 서버 명령을 직접 실행. 안내만 하던 방식을 거부하고 이번 측정에 한해 에이전트가 배포 서버·발생기 명령을 직접 실행(`.../kangmin/2026-11-10-agent-runs-server-commands-during-measurement.md`). 이유는 명령을 옮겨 치는 동안의 대기와 실수(`<LOTTERY_ID>` 자리표시를 그대로 실행)를 줄이는 것. 이후 11/13에 하위 세션이 서버·발생기 명령까지 모두 직접 실행(`.../kangmin/daily/2026-11-13.md:8`, 측정 세션 auto 모드).
8. 2026-11-11, 다중 세션 교차 검토. 6차 실험 세션 제안(128)을 메인 세션이 검토해 64 유지를 추천(`keep-in-flight-limit-64`). 설계 세션(500)과 실험 세션(64) 근거가 달랐던 점을 대조(`gateway-in-flight-limit-first`). 메인이 사용자 지시("스팬을 줄인다")를 작업 세션에 잘못 전달한 사고 뒤 "지시를 옮길 때 원문을 함께 넘겨야 한다"는 교훈이 기록됨(`reduce-scheduler-redis-spans-keep-zipkin-memory.md`).
9. 2026-11-12, 학습 규칙과 인수인계. 긴 에이전트 세션의 할루시네이션 대응으로 인수인계를 없애지 않고 "사실 + 확인 방법"만 남기고 새 세션이 git·gh·Linear로 다시 확인(`docs/learning/kangmin/qna/2026-11-12-long-session-vs-handoff-hallucination.md`). 이 노트는 세션 길이별 오류율을 잰 적은 없다고 명시. 발표 자료는 HTML 슬라이드 대신 소스 문서만(`presentation-source-only`).
10. 2026-11-13, 선조치 후보고와 다른 조 참고. kangmin: 팀 답을 기다리지 않고 ADR 0050·0052 채택, 대시보드·경보를 선조치 후보고로(`adopt-adr-0050-0052-before-replies`: "일정이 빠듯하여 시간관계상 선조치 후보고가 유리할 것 같아", 감수한 점으로 ADR 상태 줄에 선조치임을 적고 이견이 나오면 제안으로 되돌림). junhyun: 다른 조 저장소 5곳에서 스택이 아니라 형식·판단 방식만 가져옴(`.../junhyun/2026-11-13-borrow-formats-not-stacks-from-other-teams.md`), 호출 한도 때문에 CodeRabbit을 넣지 않고 저장소 프롬프트·스크립트로만 AI 리뷰(`no-coderabbit-prompt-review-only`).
11. 2026-11-14, 리뷰 최소형과 28개 세션 취합. PR AI 리뷰는 최소형 전부·위험 PR만 코멘트(`ise271-review-minimum-all-comment-risky`). 11/13~14 세션 28개와 git·gh·Linear를 모아 11/14 계획을 만들고 겹치지 않게 herdr 작업자 2개와 읽기 전용 …으로 나눔(`daily/2026-11-14.md:5`). TIL 검증 에이전트가 서술 4개를 짚었으나 게시 우선으로 보류.
12. 2026-11-15, 검증 에이전트 대조와 야간 위임. 초안을 검증 에이전트로 원본과 대조해 5곳·4곳을 고침(`daily/2026-11-15.md:6-8`). 서버 점검·측정 전체를 AI에게 위임하고 상황 보고를 이어서 받음(`delegate-server-measurement`, "점검 및 측정 권한 전부 위임할게"). 11/15 23:50~11/16 09:00 야간 성능 개선 지휘를 메인 AI에게 전부 위임(`daily/2026-11-15.md:23`, "모든 결정은 너의 추천대로 맡길게, 모든 권한도 위임", 결정 D1~D6 기록 필수). kwonwoo는 자율 성능 개선 반복 절차(분석→개선안→검토→적용·측정→기록)를 정하고 종료 조건 5회, 독립 검토(별도 에이전트), 정합성 회귀 관문을 포함(`.../kwonwoo/2026-11-15-autonomous-perf-loop-procedure.md`).
13. 2026-11-16, 위임 프롬프트 다듬기와 다른 AI 분석 검증. 야간 위임 프롬프트 템플릿에서 내용은 받고 문서체 형식은 받지 않음(`daily/2026-11-16.md` 부분 수용 줄). 다른 기기 AI 분석을 측정으로 검증(`verify-other-agent-precheck-analysis`). 브로셔 초안 숫자를 독립 검수 에이전트로 원문과 대조(`daily/2026-11-16.md` "검수 돌려 줘").

흐름 요약: 설계 검증용 "AI 추천 받기"(10월 말) → 측정으로 AI 설명 검증(11월 초·중) → 하위 세션·작업자로 병렬화(11/05~11/10) → 세션 간 교차 검토(11/11) → 검증 에이전트 대조와 선조치 후보고(11/13~15) → 야간 전권 위임(11/15~16) → AI가 틀릴 가능성 자체를 줄이는 규칙(원문 전달, 사실+확인 방법만 인수인계, 맞장구 금지)이다.

---

## 5. 학습 노트에서 브로셔·발표에 쓸 개념 설명

학습 노트 규모(합본): qna 355개(kangmin 162, junhyun 85, hyoseung 57, kwonwoo 44, youngeun 7), daily 4개, 그 밖(블로그·보고·lottery-review) 3개. 앞의 7개 노트는 본문 일부를 읽었고 나머지는 제목·키워드 검색으로만 확인했다(7절).

| 개념 | 노트 | 노트가 말하는 것(원문 요지) |
|---|---|---|
| 리틀의 법칙 L=λW | `docs/learning/kangmin/qna/2026-11-13-global-in-flight-limit-vs-mixed-traffic.md`, `2026-11-13-open-burst-arrival-shape.md:39`, `2026-11-16-raise-gateway-capacity.md`, `docs/learning/hyoseung/qna/2026-11-14-gateway-inflight-limit-purpose.md`, `docs/learning/hyoseung/qna/2026-11-06-seat-query-heap-concurrency.md` | 상한 64의 처리량은 `64 ÷ 요청 한 건 머무는 시간`. 평균에 대한 식이라 앞 0.2초에 절반이 오면 순간 도착률은 5,000/s. 이 단어가 본문에 나오는 파일은 31개 |
| 굿풋(goodput) | `kangmin/qna/2026-11-11-gateway-httphandler-decorator-early-reject.md`, `2026-11-11-h1-reinterpret-netty-cost.md`, `2026-11-12-bulkhead-vs-in-flight-limit.md`, `2026-11-12-gateway-cpu-saturation-next-steps.md` | 정의 "200ms 안 200 응답/초"(`2026-11-16-what-if-much-more-users.md:18`). 상한은 처리량을 늘리는 장치가 아니라 붕괴를 막는 장치 |
| 혼잡 붕괴 | `kangmin/qna/2026-11-16-what-if-much-more-users.md` | 2,000→2,500/s에서 요청은 다 받고 제때 끝낸 건 1/11. 3,000/s는 Gateway OOM |
| Erlang B | `docs/learning/junhyun/qna/2026-11-12-erlang-b-bulkhead-sizing.md` (유일) | 평균(9.45)이 허가(10)보다 작아도 순간 몰림으로 18.9% 거절. k6 일정 도착은 몰림이 없어 거절을 낮게 보임 |
| 벌크헤드 대 동시 처리 수 상한 | `kangmin/qna/2026-11-12-bulkhead-vs-in-flight-limit.md`, `2026-11-09-gateway-default-bulkhead.md` | 서비스 쪽 자원 칸과 Gateway 전체 상한의 차이 |
| 차단기가 CPU 포화에 열려야 하나 | `kangmin/qna/2026-11-11-breaker-vs-cpu-saturation.md`, `2026-11-11-gateway-rate-limiter-vs-breaker.md` | 차단기가 재는 시간에 CPU 대기가 섞임 |
| 시한 겹겹이 | `kangmin/qna/2026-11-12-timeout-layers-and-config-repo-map.md`, `2026-11-12-filter-vs-lettuce-timeout-order.md` | 같은 구간을 재는 시한은 하나가 죽은 값 |
| 프로파일러 사각지대, 대조군 | `kangmin/qna/2026-11-14-profiler-blind-spot-and-same-day-control.md` | JFR 5%, 실제 서버 CPU 12%p. 날마다 6~9%p 흔들려 같은 날 번갈아 두 번씩 |
| 거절 비용 | `kangmin/qna/2026-11-12-rejection-cost-before-after.md` | "받은 요청만큼 비쌌다"는 과장. 거절 2~8%(측정) |
| p50·p95·p99 | `kangmin/qna/2026-11-09-percentile-p50-p95-p99.md` | 평균 대신 쓰는 이유 |
| 오래된 통계와 Nested Loop | `kangmin/qna/2026-11-13-stale-statistics-nested-loop-seat-query.md`, `2026-11-13-seat-query-cpu-5x-comparison.md` | 새 회차 좌석 조회가 통계 하나 때문에 DB CPU 4~5배 |
| 오픈 순간 도착 모양 | `kangmin/qna/2026-11-13-open-burst-arrival-shape.md`, `2026-11-15-why-synthetic-o1-looks-better.md`, `2026-11-13-synthetic-...` | Synthetic이 입구는 8배 몰렸지만 뒤 부하는 좌석 충돌률이 정함 |
| Retry-After 값 기준 | `kangmin/qna/2026-11-13-retry-after-value-criteria.md` | 2초 vs 3초 vs 5초의 재시도 CPU 비용표 |
| 지표 시계열 수 | `docs/learning/kwonwoo/qna/2026-11-13-ise277-metrics-performance-impact.md`, `kangmin/qna/2026-11-13-domain-metrics-gap-seathold-queue-concert.md` | 지표 비용은 시계열 수에서 나옴 |
| 잠금 대기와 동시 처리 상한 | `docs/learning/kwonwoo/qna/2026-11-16-seathold-concurrency-limit-4.md` | 처리 한계 ≈ 상한 ÷ 한 건이 자리를 쥐는 시간. 풀과 상한을 함께 |
| 캐시 사전 거절 대 한 문장 판정 | `docs/learning/kwonwoo/qna/2026-11-16-pre-reject-cache-vs-one-statement.md` | "아끼는 몫은 한계 근처에서만 드러난다" |
| 대기열 입장 속도 매칭, 적응형 | `kangmin/qna/2026-11-13-queue-admission-rate-capacity-matching.md`, `2026-11-15-adaptive-queue-admission.md` | 입장 속도 고정값 하나, 예매 서비스 한계 18.5~20.5ms CPU/건 |
| 입장 구조 | `docs/learning/junhyun/qna/2026-11-02-waiting-room-vs-server-queue.md`, `2026-11-12-rate-limiting-algorithms.md` | 대기실 대 서버 큐, 속도 제한 알고리즘 |
| 장애 확산 개선 선택지 | `kangmin/qna/2026-11-16-gateway-entry-surge-options.md`, `2026-11-16-raise-gateway-capacity.md` | 장비를 늘리지 않고 늘리는 방법 A1~B5 후보 |
| 긴 세션과 인수인계 | `kangmin/qna/2026-11-12-long-session-vs-handoff-hallucination.md` | AI 활용 회고에 바로 쓸 수 있음. 오류율을 잰 적은 없다고 명시 |
| 위임 프롬프트 | `kangmin/qna/2026-11-16-overnight-delegation-prompt.md` | 야간 위임 프롬프트 다듬기(본문 못 읽음) |
| Outbox·SAGA·멱등·백프레셔·DLQ | `docs/learning/hyoseung/qna/2026-11-02-outbox-pr-231.md`, `docs/learning/junhyun/qna/2026-10-26-dlq-dead-letter-queue.md`, `2026-11-12-kafka-partitions-consumer-concurrency-ordering.md` 등 | Outbox 53개, 멱등 70개 파일에서 언급. 본문 미확인 |

---

## 6. 사실 카드 표 (숫자가 있는 판단·노트)

성격 칸: 측정 = 실행해서 얻은 값, 계산 = 문서 안에서 계산한 값, 추정 = 가정을 둔 값. 배수는 이 조사에서 직접 나눈 계산이다. 모든 줄 번호는 파일 안의 줄이다.

| id | 서비스 | 주장 | 숫자 | 배수(계산식) | 조건 | 성격 | 근거(경로:줄) |
|---|---|---|---|---|---|---|---|
| F-01 | Gateway | 상한 64와 128의 굿풋 차이가 반복 변동 안 | 2,000/s 굿풋 L128b 1,721·1,732, L64 1,696·1,608. 차이 25건(1.5%). 같은 128을 두 번 잰 차이 5% | 25 ÷ 1,696 = 1.5% | 배포 서버 6차, 같은 G1 부하(1,000~2,500/s), 회차마다 1회 | 측정 | `docs/ai-judgments/kangmin/2026-11-11-keep-in-flight-limit-64.md:20-21` |
| F-02 | Gateway | 64는 p95 여유가 큼 | 2,000/s p95 87ms 대 162ms, 2,500/s 128 p95 180ms, p99 265ms | 162 ÷ 87 = 1.86 | 같은 | 측정 | 같은 파일 `:20-21` |
| F-03 | Gateway | 거절 비율이 달라도 한 건당 CPU는 같음 | 0.66~0.71ms | 거절 25%→40% | 서버 CPU 98~99% 포화 구간 | 측정(해석은 노트가 유보) | `docs/learning/kangmin/qna/2026-11-12-rejection-cost-before-after.md:22-24` |
| F-04 | Gateway | 받은 요청 대 거절 비용 | a = 1.063±0.013ms, r = 0.021±0.021ms(pidstat), docker stats로 r = 0.080±0.017ms. r/a = 0.020~0.075 | 0.02÷1.063=2%, 0.08÷1.063=7.5% | 상한 1로 거절 유도, 500·1,000/s 포화 전 부하 | 측정 | 같은 파일 `:30` |
| F-05 | Gateway | H1(거절 앞당김)의 이득은 CPU가 아니라 굿풋 | 2,500/s 굿풋 1,608 → 1,814(+12.8%), p95 104 → 78ms | 1,814÷1,608=1.128 | 7차 H1 대 6차 L64 | 측정 | 같은 파일 `:35` |
| F-06 | Gateway | 요청 로그 끔의 효과 | 서버 CPU 1,000/s 78%→66%, 1,500/s 93%→85%, 2,500/s 굿풋 1,608→2,122, Gateway CPU 114%→111%, Alloy 22%→14% | 2,122÷1,608=1.32 | 7차 H2 | 측정 | `docs/ai-judgments/kangmin/2026-11-11-request-log-failures-and-slow-only.md:16` |
| F-07 | Gateway | 프로파일러가 작게 본 비용이 실제로 작지 않음 | JFR 5.2% 대 서버 CPU 12%p. 같은 날 번갈아: B 78.5% 대 끔 79.1%(1,500/s). 같은 설정 날짜 차 6~9%p, 같은 설정 두 번 차 1.3%p 이하 | 12 ÷ 5.2 = 2.3 | 같은 배포·같은 날 번갈아 2회 | 측정 | `docs/learning/kangmin/qna/2026-11-14-profiler-blind-spot-and-same-day-control.md:16-17,24` |
| F-08 | Gateway | 상한이 붕괴를 막는 장치 | 상한 없이 1,500/s 굿풋 1,440, 2,000/s 1,469, 2,500/s 136, 3,000/s 33(Gateway OOM). 상한 뒤 2,500/s 약 2,250(8차), 2,092~2,145(11/13 G1 반복, 힙 최대 229MiB) | 1,469÷136=10.8, 2,100÷136=15.4, 2,250÷136=16.5 | 2,500·3,000/s 단계는 발생기 VU 상한에 닿아 "무효" 표시 | 측정 | `docs/learning/kangmin/qna/2026-11-16-what-if-much-more-users.md:20-33` |
| F-09 | Gateway | 첫 상한 64의 산출 | 평균 40~45ms × 1,480/s ≈ 60~67. 500안은 1,500/s × p95 152ms ≈ 228의 약 2배. 처리 중 수 260 넘은 단계는 p95 435ms 이상 | 계산식 원문 그대로 | 5차 실험 단계별 평균 | 계산 | `docs/ai-judgments/kangmin/2026-11-11-gateway-in-flight-limit-first.md:23` |
| F-10 | Gateway | 통합 상한 변경 후 테스트 | gateway 테스트 107 → 118(+11) 통과, 변이 6개 결함 모두 실패로 잡힘, 프런트 vitest 98파일·1,377개 | 118 − 107 = 11 | 구현 PR | 측정 | 같은 파일 `:39` |
| F-11 | Gateway | 차단기를 예민하게 해도 포화 전에 열리지 않음 | 0a·0b(100ms), B20(20ms) 모두 2,500/s·서버 CPU 99%까지 안 열림. 20ms 기준 느린 호출 최대 24% | — | 배포 서버 5차 | 측정 | `docs/ai-judgments/kangmin/2026-11-11-breaker-first-for-gateway-cpu-saturation.md:43` |
| F-12 | Gateway | 작은 요청 1건 CPU | 1.063ms, 1코어당 약 940건/초(1÷1.063ms), 관측 묶음이 Gateway CPU 약 24%(ContextPropagation 5.8%, ContextWriteRestoringThreadLocals 2.7%), 인증 Redis·차단기 약 2.5% | 1 ÷ 1.063ms = 940/s | 8차 R0, 11/11 JFR | 측정+계산 | `docs/learning/kangmin/qna/2026-11-16-raise-gateway-capacity.md:20-24` |
| F-13 | Gateway | 오픈 전 공연 상세가 Gateway 요청의 대부분 | 요청의 73.8%가 공연 상세, 98.1%가 오픈 전. 20만 명이면 오픈 전 초당 약 9,500건(계산), 장비 한 대 Gateway 한도 초당 약 2,000~2,300건 | 9,500 ÷ 2,150 ≈ 4.4배(이 조사 계산) | ISE-285 분석, 계산 가정은 노트 참조 | 측정+계산 | `docs/learning/kangmin/qna/2026-11-16-raise-gateway-capacity.md:25`, `2026-11-16-what-if-much-more-users.md:43` |
| F-14 | Gateway | Retry-After 값별 재시도 부하 | 2초 약 4,500/s·0.36코어(9%), 3초 약 3,000/s·0.24코어(6%), 5초 약 1,800/s·0.15코어(4%) | 4,500÷3,000=1.5 | 거절된 사용자 1만 명, jitter 1.1배, 거절 0.08ms | 계산 | `docs/learning/kangmin/qna/2026-11-13-retry-after-value-criteria.md:35-41` |
| F-15 | Gateway | 기본 벌크헤드가 걸려 있었음 | `auth-redis` 동시 25, 대기 0 | — | resilience4j 5.0.3 `enabled` 기본 true, 바이트코드 확인 | 코드 확인(배포 지표는 미확인) | `docs/ai-judgments/kangmin/2026-11-09-gateway-has-default-bulkhead.md:26` |
| F-16 | 대기열 | 식은 Gateway가 차단기 열림의 후보 | 100ms 초당 300명: 실패 34건 열림, 느린 호출 최대 3. 101ms: 실패 21건. 50ms(11/9 B0m) 느린 호출 12. 식은 JIT 6.1·9.0s 대 데운 0.34s, Gateway CPU 최대 45% | 9.0÷0.34=26.5 | 1회씩 | 측정 | `docs/ai-judgments/youngeun/2026-11-10-slow-call-threshold-cold-gateway.md:35` |
| F-17 | 대기열 | 워밍업 뒤 초당 300명은 통과 | 오류 0, p95 5.51ms, 느린 호출 0. 데운 Q1 한도 1,000 통과·3,000 실패 사이 | — | B0m(식은 상태)은 열림. 비교 1회 | 측정 | `docs/ai-judgments/youngeun/2026-11-10-no-breaker-relax-for-measurement.md:33` |
| F-18 | 대기열 | 폴링 상한 30초 제안의 효과 | 대기자 3만 명 기준 폴링 약 2,780 → 1,000/s | 2,780 ÷ 1,000 = 2.78 | 계산 | 계산 | `docs/ai-judgments/kangmin/2026-11-12-reject-poll-max-30s.md:20` |
| F-19 | 대기열 | 예매 서비스 한 건 CPU | (예매 서비스 + 예매 DB) ÷ (좌석 조회 + 선점 정상) = 18.5~20.5ms CPU/건(위쪽 한계). 좌석 조회 p95 497 → 1,224 → 1,964 → 1,888ms, 선점 p95 440 → 1,083 → 1,590 → 1,539ms | — | O1 15,000명, VU 500, 입장 50/s, PG 300ms | 측정 | `docs/learning/kangmin/qna/2026-11-13-queue-admission-rate-capacity-matching.md:53,58` |
| F-20 | 대기열·관측 | 스케줄러 스팬이 Zipkin을 채움 | 10분치 추적 3,237건·스팬 25,887, 입장 스케줄러 추적 1,190건(0.5초마다), 추적당 스팬 20(`evalsha` 19), 전체 스팬의 91.9%, 예약 작업 합산 약 97.6%. 평시 시간당 약 20만 스팬 | 1,190×20 ÷ 25,887 = 91.9% | 배포 서버 10:29~10:39Z | 측정 | `docs/ai-judgments/kangmin/2026-11-11-reduce-scheduler-redis-spans-keep-zipkin-memory.md:38` |
| F-21 | 대기열 | 균일 모드 대 Synthetic 모드 | 진입 요청 3,016 대 5,614, 진입 p95 353 대 2,792(원문 8배). 선점 409 2,298 대 372, 충돌률 48% 대 12%. 확정 p95 3.9초 대 1.0초 | 2,792÷353=7.9, 0.48÷0.12=4 | 입구는 Synthetic이 8배 몰림, 뒤 부하는 좌석 충돌이 정함 | 측정 | `docs/learning/kangmin/qna/2026-11-15-why-synthetic-o1-looks-better.md:29-34` |
| F-22 | 좌석 선점 | SKIP LOCKED가 여러 좌석 요청에서 성공을 줄임 | C2 성공 83 → 56건. `lock_timeout` 50ms로 C2 성공 78·87건, C3 최대 대기 221~225ms | 56÷83=0.675 | 동시성 테스트(10명, 좌석 4개 겹침, 500건), 방식마다 1~3회 | 측정 | `docs/ai-judgments/kwonwoo/2026-11-13-reject-skip-locked-choose-50ms.md:21-22` |
| F-23 | 좌석 선점 | 잠금 시한 100ms가 500ms보다 지표 우수 | 1% 발생 가정 시 처리량 감소 약 40% 대 약 11%, 줄 멈춤 0.5초 대 0.1초. 후속 검증: `lock_timeout` 시한 테스트 450ms 안에 409 | 40 ÷ 11 = 3.6 | 표 기반 계산 후 테스트 14건·Kafka 3건 통과 | 계산+측정 | `docs/ai-judgments/kwonwoo/2026-11-13-ise17-consumer-lock-and-timeout-by-metrics.md:29` |
| F-24 | 좌석 선점 | 캐시 사전 거절이 한계 근처에서 의도 실패를 없앰 | 정상 3,000/s 의도 실패 5.5% → 0%, 자리 쥐는 시간 20~30% 감소, 정합성 위반 0, 한 건 0.25~0.45ms. 끔 1.53ms → 약 2,600/s, 켬 1.07ms → 약 3,700/s | 3,700÷2,600=1.42, 1.53÷1.07=1.43 | 로컬 L9, 끔·켬 같은 시점 번갈아, 조건별 n=4. 자동 배정은 미측정 | 측정+계산 | `docs/ai-judgments/kwonwoo/2026-11-16-verify-other-agent-precheck-analysis.md:26`, `docs/learning/kwonwoo/qna/2026-11-16-pre-reject-cache-vs-one-statement.md:25-26` |
| F-25 | 좌석 선점 | 동시 처리 상한 4가 충분 | 한 건 자리 쥐는 시간 약 1.3ms, 상한 4면 약 3,000/s(4÷1.3ms), 정상 2,000/s 의도 실패 0%. 풀 6·상한 5(참고)에서 정상 1,800/s 의도 실패 22.5% → 9.4%. 상한 끔은 600/s 성공 29~47% | 4 ÷ 1.3ms = 3,077/s, 22.5÷9.4=2.4 | 로컬 반복 2~4회, 배경 작업 없음 | 측정+계산 | `docs/learning/kwonwoo/qna/2026-11-16-seathold-concurrency-limit-4.md:18,29,38,44` |
| F-26 | 좌석 조회 | 서비스 메모리 캐시(P4b)가 DB를 덜 씀 | 초당 150 postgres CPU 136% → 21%, 커넥션 쥔 시간 21ms → 4.6ms. 초당 300 p95 2.06s → 26ms | 136÷21=6.5, 21÷4.6=4.6, 2,060÷26=79 | 로컬, ADR 0054 | 측정 | `docs/ai-judgments/hyoseung/2026-11-08-seat-layout-cache-memory-not-redis.md:34` |
| F-27 | 좌석 조회 | 로컬 캐시가 Redis보다 낫다 | Gateway 경유 초당 100명 p95 로컬 7.0ms 대 Redis 38.3ms, 한 건 CPU 약 5.5 대 25ms, GC 약 17배, 공용 Redis 시한 초과 500 | 38.3÷7.0=5.5, 25÷5.5=4.5 | 최권우, 같은 커밋 설정 하나만 변경, 2026-11-12 | 측정 | 같은 파일 `:35` |
| F-28 | 좌석 조회 | 새 회차 좌석 조회가 통계 하나로 느려짐 | 한계 초당 40 → 10, 초당 20건에서 실패율 0% → 40%, DB CPU 73% → 340%(코어 약 3.4개) | 340÷73=4.7, 40÷10=4 | 11/11 대 11/13, 코드·Gateway는 그대로, 원인은 오래된 통계와 Nested Loop. autoanalyze 기본 임계는 테이블의 10% | 측정+추론(노트 참조) | `docs/learning/kangmin/qna/2026-11-13-stale-statistics-nested-loop-seat-query.md:17-19,94,108` |
| F-29 | 좌석 조회·공연 | 공연 상세 한 건이 CPU 약 3ms | 오픈 전 120초 동안 초당 약 660건에 서버 4 vCPU 중 2.7~2.9코어. DB 쿼리 합 약 0.2ms(EXPLAIN ANALYZE), DB 컨테이너는 한 건 0.7ms, 약 23% | 2.8코어 ÷ 660 ≈ 4.2ms(이 조사 계산, 노트는 약 3ms로 표기) | 11/13 O1 배포 측정. 노트의 "약 3ms" 정의를 우선함 | 측정+계산 | `docs/learning/kangmin/qna/2026-11-13-concert-detail-cost.md:13,17,19` |
| F-30 | 결제·추첨 | 허가 수는 평균이 아니라 순간 몰림으로 정함 | 초당 30건, 허가 10 거절 18.9%, 허가 17 0.85%, 허가 20 0.10%. 초당 50건은 허가 24 1.3%, 27 0.28%, 30 0.05%. W ≈ 0.315초, L = 30×0.315 ≈ 9.5 | 18.9÷0.85=22 | Erlang B, 포아송 도착 가정 | 계산 | `docs/learning/junhyun/qna/2026-11-12-erlang-b-bulkhead-sizing.md:25,46-49` |
| F-31 | 결제 | 허가 10 → 30 효과 | PG 300ms 승인 한도 31/s → 91.5/s, 요청 80건/초 거절 59.5% → 0.2%, Hikari 활성 연결 최대 3~6(풀 20), 대기 0 | 91.5÷31=2.95, 59.5÷0.2=297 | 11/12 배포 측정 | 측정 | 같은 파일 `:68`, `docs/ai-judgments/junhyun/2026-11-12-pg-permits-approve-and-billing-group.md` |
| F-32 | 추첨·결제 | 계산 모델이 실측과 맞음 | 600초 안 완료 예측 1,861건 대 실측 1,858~1,885건. 자동결제 초당 9.51, 약 3.7분, 10분 안 100%. 일반 결제 30/s 503 무작위 도착 0.75~0.96%(지금 10이면 18~20%). k6 일정 도착은 0.31%, Erlang B 19.7% | — | 구현 전 기대값(계산) | 계산(모델 검증은 측정) | `docs/ai-judgments/kangmin/2026-11-11-lottery-billing-isolation-and-approve-size.md:39-40,46` |
| F-33 | 추첨 | 추첨 직후 선점 p95 +90% | 추첨 전 11.06ms → 추첨 뒤 120초 21.0ms(+90%, 계산값) → 15.7ms. 오류 0. 기준 "증가 20% 이하" 미달. 절대값 증가 약 10ms | 21.0÷11.06 = 1.90 | 시나리오 3: 판매 회차 선점 초당 30건 × 300초 중 추첨 29·자동결제. 1회 측정, DB 커넥션 대기·CPU 기록 없음 | 측정 | `docs/ai-judgments/kangmin/2026-11-16-lottery-scenario3-remeasure.md:17` |
| F-34 | 개발 도구 | 기록 검사 훅 25배 | 92.8초 → 3.6초(3회 중앙값). 프로세스 약 3,500번 × 26ms ≈ 90초. 변이 31가지 중 30가지 동일 | 92.8÷3.6=25.8 | dev 복제본, Windows Git Bash. macOS bash 3.2 미확인 | 측정 | `docs/ai-judgments/kangmin/2026-11-09-records-check-25x-faster.md:35` |
| F-35 | 인프라 | Zipkin·스케줄러 스팬 줄이기 테스트 | reservation-service 전체 테스트 392건 통과(건너뜀 11), `SchedulerRedisSpanConfigurationTest` 4건, 변이 2개 각각 1건 실패 | — | 구현 PR, 배포 뒤 유입량은 확인 전 | 측정 | `docs/ai-judgments/kangmin/2026-11-11-reduce-scheduler-redis-spans-keep-zipkin-memory.md:42` |

---

## 7. 못 읽은 파일과 이유

읽은 정도를 세 단계로 나눈다. 전수 정독은 하지 못했다. 두 폴더 합계 690파일(약 3.9MB)이 한 번의 조사로 다 읽기에는 너무 커서, 구조를 스크립트로 전수 파싱하고 대표 파일만 본문을 읽었다.

1. 전수 처리(스크립트로 모든 파일 머리말·꼬리표를 파싱): 개별 판단 244건의 머리말(제목·날짜·판단자·판정·영역·발표 후보), 일지 72개의 판정 꼬리표 줄 603개, 공통 표 두 판(origin 208, 로컬 201), 학습 노트 355개의 제목·태그. 1절 통계와 4절 일정은 이 처리에서 나왔다.
2. 본문 읽음(맥락·AI 제안·판단·검증·발표 포인트 구간): 개별 판단 약 35건. 확인한 건: kangmin 11/09 벌크헤드·records-check, 11/10 서버 명령, 11/11 상한 64·상한 첫 장치·차단기·요청 로그·스팬·결제 허가, 11/12 Lettuce·poll-max, 11/13 ADR 선조치·안전망 상한, 11/15 서버 위임, 11/16 시나리오 3·희소성. junhyun ISE-169, 11/12 PG 허가. youngeun 10/29 근거 정정, 11/10 차단기·정정. kwonwoo 11/04 k6, 11/13 SKIP LOCKED·ISE-17, 11/15 자율 반복, 11/16 다른 AI 분석 검증. hyoseung 11/08 캐시. 일지는 줄 단위 grep(지적·틀림·위임·검증 에이전트 등)으로만 읽었다.
3. 학습 노트 본문: 약 25개를 숫자 줄·요약 위주로만 읽었다(1절의 7개 외에 5절 표의 노트는 제목·핵심 줄). 나머지 330개는 제목·태그만 봤다.

못 읽은 것:
- 개별 판단 약 205건의 본문. 따라서 3절에서 위 2단계가 아닌 junhyun·kangmin 항목은 제목·머리말 수준이며, "판단자가 틀렸다고 적었는지"는 제목의 문장("지적함", "거부")에 의존했다. 해당 줄은 경로 끝에 "이름 앞부분만 확인"으로 표시했다.
- `docs/ai-judgments/<이름>/README.md` 5개와 `docs/learning/README.md`, 각 사람의 `README.md` 5개 본문. 건수 문구("개별 기록 118건" 등)만 `docs/ai-judgments/README.md`의 표에서 읽었다.
- 학습 노트 본문 대부분. 특히 junhyun 85개, hyoseung 57개, kwonwoo 44개, youngeun 7개, kangmin 162개(위 25개 제외).
- `docs/learning/junhyun/blog/*.html`(TIL 블로그), `docs/learning/junhyun/reports/`, `docs/learning/kangmin/lottery-review/README.md`(추첨 코드 리뷰 보고서). 파일 이름만 봤다.
- 로컬에서 수정된 6개 파일(`docs/ai-judgments/README.md`, `kangmin/README.md`, `kangmin/daily/2026-11-15.md`, `docs/learning/kangmin/README.md` 등)의 로컬 판은 origin 판과 비교하지 않았다. 일부는 줄바꿈 경고만 있고 내용 차이를 확인하지 못했다. 로컬 README 표는 1-2절에서 따로 셌다.
- 판단 기록에 인용된 설계 문서(`docs/design/**`), ADR, Linear·PR 본문. 이 조사의 범위가 아니다. 판단 기록 안에 적힌 숫자를 옮긴 것이므로, 해당 설계 문서의 최신 숫자와 어긋날 수 있다.
- 일지 한 줄의 "근거 미기재" 항목 다수는 판단자의 이유가 원문에 없다. 사실 카드에서 이유를 만들어 쓰지 않았다.

확인 못 함(원문이 스스로 밝힌 것):
- 배포 서버에서 gateway가 `resilience4j_bulkhead_*{application="gateway"}` 지표를 내는지(F-15).
- 시나리오 3의 DB 커넥션 대기·CPU 기록(F-33), 1회 측정.
- F-24의 자동 배정 경로는 미측정.
- 긴 세션 오류율은 잰 적이 없다(5절).

주의할 모순
- 거부 비율은 기준마다 32%~40%다(1-2절). 한 숫자로 쓰려면 기준을 명시한다.
- Top 12 #8은 "거부했는데 측정은 AI 편"이다. 거부가 항상 옳았다는 서사로 쓰면 원문과 어긋난다.
- F-29의 한 건당 CPU는 노트가 "약 3ms"라고 쓰고, 이 조사의 나눗셈은 약 4.2ms다. 원문 표기를 우선하고 계산 차이를 적어 둔다.

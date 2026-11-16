# D. 코드·측정 원자료·이력 전수조사 (브로셔 사실 원장)

- 조사일: 2026-11-16 (KST). 기준: `git fetch` 뒤 `origin/dev` = `486501b7`(2026-11-16 15:34 KST, PR #685 머지). 로컬 `dev`는 origin/dev보다 38커밋 뒤라서, 코드·문서 수는 `git archive origin/dev`를 임시 폴더에 풀어 센 값이다. 작업 트리는 바꾸지 않았다.
- 표기: **실측**은 실행 결과나 문서가 적은 측정값, **계산값**은 실측끼리 계산한 값, **참고값**은 조건이 달라 비교 주의, **확인 못 함**은 근거 없음.
- 아래 "센 명령"의 `OD`는 `git archive origin/dev | tar -x -C <임시폴더>`로 푼 폴더다(경로: 이 세션 scratchpad의 `od`).

---

## 1. 프로젝트 규모 숫자 ("숫자로 보는 이선좌" 후보)

| 항목 | 값 | 센 명령 |
|---|---|---|
| 기간 | 2026-10-26 ~ 2026-11-16(22일). 커밋이 있는 날 21일 | `git log origin/dev --format=%ad --date=short \| sort \| sed -n '1p;$p'` / `sort -u \| wc -l` |
| 커밋(origin/dev) | **919**(머지 커밋 151 + 일반 768, first-parent 363) | `git rev-list --count origin/dev`, `git log origin/dev --merges --oneline \| wc -l` |
| 작성자별 커밋 | Junhyun An(alloy31) 652, GGangMY 151, KWON-WOO 40, hyos1 36, alreadysolved 28, kye 12 | `git log origin/dev --format=%an \| sort \| uniq -c \| sort -rn`. alloy31은 이메일 2종(501+151)을 합산, GGangMY 2종(132+19), KWON-WOO 2종(23+17), hyos1 2종(34+2) |
| 병합된 PR | **384**건(번호 2~685). 작성자: alloy31 153, GGangMY 147, hyos1 38, alreadysolved 29, KWON-WOO 17 | `gh pr list --state merged --limit 1000 --json number,title,author,mergedAt` |
| PR 제목에 쓰인 서로 다른 이슈 키(ISE-) | **200**개. 키 없는 PR 63건 | 같은 JSON에서 `ISE-\d+` 정규식 집계 |
| 가장 많이 묶인 이슈 키 | ISE-230(Gateway 과부하) 22 PR, ISE-264(11/15 측정·개선) 21, ISE-189(좌석 조회) 10, ISE-94(추첨 측정) 9 | 같음 |
| 서비스·모듈 | Gradle 모듈 **12**: common, gateway, eureka, config-server, user-service, reservation-service, payment-service, mock-pg, assistant-service, system-tests, workspace-tools(+루트). 이 중 앱 서비스 **8**(config-server, eureka, gateway, user, reservation, payment, mock-pg, assistant) + 프런트(nginx) | `cat settings.gradle`(include 11개 + rootProject) |
| 배포 서버 컨테이너 | **21개**(`compose.deploy.yml`의 services: 앱 8 + frontend + PostgreSQL 3 + Redis + Kafka + kafka-ui + Prometheus + Alertmanager + Grafana + Loki + Alloy + Zipkin). 기본 `compose.yml`은 22개 서비스 | `awk '/^services:/{s=1;next} /^[a-z]/{s=0} s&&/^  [a-z0-9-]+:\s*$/' OD/compose.deploy.yml \| wc -l` (21), `compose.yml` (22) |
| Java 테스트(@Test·@ParameterizedTest·@RepeatedTest 줄 수) | **2,020개** / 테스트 클래스 279개 | 서비스별 표 아래. `grep -rE '@(Test\|ParameterizedTest\|RepeatedTest)\b' <모듈>/src/test \| wc -l`, 클래스는 `grep -rlE ... \| wc -l` |
| 프런트 테스트 | `it(`/`test(` 호출 약 1,242줄(vitest·Playwright 구분 안 함, 대략값) | `grep -rE '\b(it\|test)\(' frontend/src frontend/e2e \| wc -l` |
| ADR | **63**개(0001~0063, README 제외) | `ls OD/docs/decisions \| grep -c '^[0-9]'` |
| TS(트러블슈팅) 항목 | **74**개(TS-1~TS-74) | `grep -c '^### TS-' OD/docs/troubleshooting.md` |
| 문서(md) | 저장소 전체 md 902개, `docs/` 아래 880개 | `find OD -name '*.md' \| wc -l` |
| AI 판단 기록 md | **319**개(README 포함, 폴더 5명) | `find OD/docs/ai-judgments -name '*.md' \| wc -l` |
| 학습 노트 md | **362**개(README 포함) | `find OD/docs/learning -name '*.md' \| wc -l` |
| 추적 파일 수 | 2,471(origin/dev) | `git ls-tree -r origin/dev --name-only \| wc -l` |
| 코드 줄 수 | Java 83,631줄(main 33,882 + test 49,749). 프런트 TS/TSX 45,265줄(`frontend/src`) + e2e 516줄. k6 JS 3,463줄, Python(loadtest) 1,951줄 | `find OD -name '*.java' -not -path './frontend/*' \| xargs cat \| wc -l` 등 |
| 부하 시나리오 | k6 시나리오 17개(`loadtest/k6/scenarios`), 라이브러리 9개, 도구 8개, 서버 덮어쓰기 compose 9개(`loadtest/deploy`)+k6 compose 3개 | `git ls-files loadtest` (61개) |

### 서비스별 Java 줄 수·테스트 수 (origin/dev)

| 모듈 | main 줄 | test 줄 | 테스트 클래스 | @Test 줄 수 |
|---|---|---|---|---|
| common | 1,988 | 1,932 | 19 | 113 |
| gateway | 918 | 1,886 | 13 | 93 |
| eureka | 13 | 0 | 0 | 0 |
| config-server | 13 | 82 | 1 | 4 |
| user-service | 918 | 622 | 3 | 32 |
| reservation-service | 13,430 | 15,842 | 87 | 586 |
| payment-service | 12,888 | 22,757 | 114 | 870 |
| mock-pg | 1,881 | 3,596 | 22 | 196 |
| assistant-service | 1,264 | 1,859 | 13 | 82 |
| system-tests | 0 | 759 | 3 | 15 |
| workspace-tools | 569 | 414 | 4 | 29 |
| **합계** | **33,882** | **49,749** | **279** | **2,020** |

- 주의: `@Test` 줄 수는 `@Nested`·동적 테스트·실행 결과 수와 다를 수 있다. 실제 "통과 N개"는 Gradle 실행 결과로만 말한다(이번 조사에서 `./gradlew test`는 돌리지 않았다). 팀 문서가 "테스트 N개"로 적은 숫자(예: 11/16 개선 문서의 reservation 551개)는 서비스 단위 Gradle 실행 값으로 보이며, 내 정규식 값(586)과 다르다. 브로셔에는 Gradle 실행 값을 쓰는 편이 안전하다(확인 못 함: 전체 실행 수).
- 시기: 첫 커밋 `7635dca4`(2026-10-26 11:44 KST) 기록·지침, 마지막 `486501b7`(2026-11-16 15:34 KST).

### 날짜·주 단위 활동

| ISO 주 | 일 | GGangMY | Junhyun An | KWON-WOO | alreadysolved | hyos1 | kye |
|---|---|---|---|---|---|---|---|
| W44 | 10/26~11/1 | 25 | 88 | 22 | 7 | 7 | 12 |
| W45 | 11/2~11/8 | 45 | 485 | 8 | 6 | 9 | 0 |
| W46 | 11/9~11/15 | 70 | 60 | 8 | 13 | 16 | 0 |
| W47 | 11/16(월)~ | 11 | 19 | 2 | 2 | 4 | 0 |

명령: `git log origin/dev --format='%an|%ad' --date=format:'%G-W%V' \| sort \| uniq -c`. 일별 커밋 최다: 11/2 177, 11/4 150, 11/3 125, 11/5 82, 10/28 77, 11/12 47.
PR 병합 수(일별, UTC 날짜 `mergedAt` 기준): 10/27 8, 10/28 13, 10/29 3, 10/30 10, 10/31 3, 11/1 8, **11/2 44**, 11/3 27, 11/4 20, 11/5 30, 11/6 5, 11/8 4, 11/9 41, 11/10 24, 11/11 17, **11/12 54**, 11/13 14, 11/14 14, 11/15 28, 11/16 17.
- 주의: 커밋 수는 "일반 커밋 + 머지 커밋" 모두를 센다. 머지 커밋 151은 PR 머지·`dev→main` 반영이다. 팀원 간 커밋 수 비교는 PR 방식(브랜치 커밋 보존) 영향이 있어 PR 수·내용과 같이 말한다. W45의 준현 485는 11/2~11/4에 몰린 결제·프런트·인프라 작업이다(날짜별 합과 일치).

---

## 2. 측정 결과 원자료 목록

원자료는 저장소 밖(`.gitignore`)이거나 개인 폴더에 있다. 문서로 옮겨진 숫자는 3장과 5장에 인용한다.

### 2-1. 저장소 안 측정 코드·설정 (git 추적)

| 경로 | 내용 |
|---|---|
| `loadtest/k6/scenarios/*.js` 17개 | a0(동시 선점 1,000건 몰림), b1·b1d(공연 목록·상세), g1(Gateway 인증 순번 조회), l1·l2·l3(추첨 응모·자동결제·겹침), o1(오픈 피크 섞인 흐름), pay1(결제 한도), q1·q1d·q2·q3(대기열), r1·r1d(확정), s1(좌석 조회), x1(선점 만료) |
| `loadtest/k6/data/o1-synthetic-3000.csv` | Synthetic 가상 사용자 3,000명 행동(토큰·개인정보 없음, 커밋됨) |
| `loadtest/synthetic/*` | `generate.py`, `validate.py`, `compare.py`, `persona_spec.py`, `o1-personas.json`, `o1-prompt.md`, `o1-response.md`(LLM으로 만든 페르소나 원본), `o1-sample-40.csv`, 테스트 |
| `loadtest/deploy/compose.*.yml` 9개 | 배포 서버 측정 덮어쓰기: measure, gateway-inflight, gateway-bulkhead, reservation-pool, seat-before·p4a·p4b·p4b-merge, background-cpu |
| `loadtest/k6/compose.*.yml` 3개 | expiry-on, mockpg-loadtest, queue-hold |
| `script/loadtest/measure-mode.sh` | 측정 모드 켜기·끄기(슬랙 알림 포함) |

### 2-2. 로컬에만 있는 k6 결과 (`loadtest/k6/results/`, `.gitignore`, 약 403MB, 파일 107개)

시나리오별 `*-summary.json`(k6 `--summary-export`)을 파싱한 값이다. **파일명에 든 회차 시각은 문서에 인용되지 않았다**(문서는 회차 ID·측정 차수로 부른다). 이 표의 숫자 중 문서와 대조하지 못한 것은 "문서 대조 안 함"이다. 시각은 파일명의 UTC(`YYYYMMDD-HHMMSS`)다. 파싱 명령: python으로 `metrics.http_reqs.count/rate`, `http_req_duration p(95)/p(99)`, `http_req_failed.value`, `vus_max`.

| 파일(접두) | 시나리오 | 날짜(UTC) | 요청 수 / 속도 | p95 / p99(ms) | 실패 비율 | 최대 VU | 비고 |
|---|---|---|---|---|---|---|---|
| l1-lottery-entry-20261106-201021 | L1 추첨 응모 | 11/06 | 100,384 / 363/s | 51 / 278 | 49.3% | 302 | 임계 `entry_duration` 어김. 로컬 사전 측정 |
| l1-…-20261110-022250 | L1 | 11/10 | 100,375 / 363/s | 11 / 25 | 0% | 300 | 같은 날 배포 서버 첫 회 |
| l1-…-20261110-032321 | L1 | 11/10 | 536,661 / 1,871/s | 14 / 109 | 94.2% | 674 | gateway_error·service_unavailable 임계 어김 |
| l1-…-20261110-033218 | L1 | 11/10 | 313,997 / 920/s | 42 / 279 | 66.8% | 523 | |
| l1-…-20261110-034425 | L1 | 11/10 | 467,946 / 1,215/s | 1,793 / 12,082 | 65.3% | 3,000 | |
| l1-…-20261110-035332 | L1 | 11/10 | 294,970 / 861/s | 1,113 / 1,662 | 2.95% | 3,000 | |
| l1-…-20261110-040109 | L1 | 11/10 | 230,133 / 752/s | 503 / 861 | 0.61% | 1,139 | 오류 1,414건(`entry_results{result:error}`) |
| l2-lottery-billing-20261106-200213 | L2 자동결제 | 11/06 | 4,007 / 9/s | 51 / 79 | 0% | 1 | 로컬 사전 |
| l2-…-20261110-003802 ~ 013455, 025359 | L2 (VU 1, 60,001건) | 11/10 | 60,001 / 35·52·56·80/s | 10~28 / 14~54 | 0% | 1 | |
| l2-…-20261110-015154, 020911 | L2 (VU 100) | 11/10 | 86,404·90,002 / 85·138/s | 308 / 310 | 0.01%·1.58% | 100 | |
| l2-…-20261110-030801 ~ 031953 | L2 (VU 100, 단계적) | 11/10 | 4,822~9,624 / 30~60/s | 311~313 / 314~317 | 0.06%, 0%, 0.15%, **4.7%, 11.6%** | 100 | 초당 부하를 30→60/s로 올리자 실패가 늘어난 단계 실험 |
| pay1-payment-20261111-191640 | PAY1 결제 | 11/11 | 28,455 / 78/s | 318 / 326 | 17.7% | 200 | 임계 `dropped_iterations` 어김 |
| pay1-…-192515 | PAY1 | 11/11 | 28,455 / 79/s | 338 / 345 | 3.9% | 200 | |
| q1-queue-enter-20261113-164754 외 2건 | Q1 대기열 진입 | 11/13 | 201·201·101 | 1~2 / 1~3 | 25~30% | 100~200 | 임계 `dropped_iterations`, `enter_duration` 어김(작은 시험 실행으로 보임) |
| q1d-queue-burst-20261113-164757 외 2건 | Q1-d 몰림 | 11/13 | 200 / 61~63/s | 6~7 / 7 | 60.5~63.5% | 200 | |

- 문서 대조: L1·L2 배포 서버 측정(11/10)은 `docs/design/lottery/numbers-and-measurement.md` 3-3·3-4절에 정리돼 있다(회차 ID·처리 속도 중심). 위 표처럼 **회차별 k6 요청 수·p95·실패 비율을 한 줄씩 옮긴 곳은 확인 못 함**. 문서 대조는 파일명 시각이 문서에 없다는 것까지만 확인했다(`git grep -- <시각> origin/dev -- docs` 0건: 20261110-040109, 20261113-164754, 20261111-191640). 브로셔에 쓰려면 해당 문서와 값을 먼저 대조한다.
- 각 회차의 `raw.csv.gz`(전체 요청 원자료)는 압축 파일 52개이며, 이번 조사에서 풀지 않았다.

### 2-3. 로컬 압축 묶음 (`loadtest/k6/results/g6`, `g7`, `g8`, tar.gz, 풀지 않음)

| 폴더 | 파일 | 날짜 | 문서 연결 |
|---|---|---|---|
| g6 | gen-/server- × L32, L64, L128, L128b, L500, L500-warm12 (8개, 총 약 110MB) | 11/11 | 배포 서버 6차 A1(상한 32·64·128·500). 숫자는 `open-peak-scenarios.md` 836~947행, 발표 한 장 `2026-11-14-gateway-overload-one-page.md`에 있음 |
| g7 | gen-/server- × H1, H2 (4개) | 11/11 | 7차 H1(거절 앞당김)·H2(요청 로그 끔). 같은 문서 948~1028행 |
| g8 | gen-/server- × g8-O1, g8-O2, g8-P1, g8-P2, r0-A, r0-R (12개, 약 108MB) | 11/12 | 8차 R0(거절 비용·요청 로그 problems). 1029~1128행. `server-*`에는 `archive/prom-*.json`(Prometheus 지표), `zipkin-span-counts.json`, `queries.txt`가 들어 있고 `gen-*`에는 `*-gen-vmstat.log`, `*-measure.txt`, G1 `summary.json`·`raw.csv.gz`가 있다 |

→ 위 압축 묶음에서 **문서에 옮겨지지 않은 숫자가 있는지는 풀어 대조하지 않아 확인 못 함**(풀어서 Prometheus archive를 열어야 안다).

### 2-4. 문서에만 있는 측정(발표 소스 포함). 숫자 정본 위치

| 측정 | 날짜 | 정본 위치 | 핵심 숫자는 5장 사실 카드에 인용 |
|---|---|---|---|
| 배포 서버 1~3차 Q1·Q2·Q3(대기열) | 11/9~11/10 | `docs/design/infra/open-peak-scenarios.md` 305~566행 | 인용 안 함(확인 못 함: 핵심 숫자 미조사) |
| 4~5차 G1 auth-redis 재측정·차단기 예민화·Gateway OOM | 11/10~11/11 | 같은 문서 567~835행 | F-04, F-05 |
| 6~8차 G1 동시 처리 상한·H1·H2·R0 | 11/11~11/12 | 같은 문서 836~1128행, `2026-11-14-gateway-overload-one-page.md` | F-06~F-09 |
| S1 좌석 조회 3단계, B1-d | 11/11~11/14 (hyoseung) | 같은 문서 1129~1240행 | F-10 |
| PAY1 결제 승인 한도, PAY-1·PAY-2 | 11/12 (junhyun) | 같은 문서 1241~1345행 | F-11 |
| 추첨 L1·L2·L3, 자동결제 재측정, L2-b·시나리오 3 | 11/10, 11/13, 11/15 | `docs/design/lottery/numbers-and-measurement.md` 3-3~3-6절 | F-12, F-13 |
| O1 균일 대 Synthetic(회차 122·123), A0 | 11/15 | `docs/design/infra/synthetic-data.md` 6절, `docs/presentation/performance/2026-11-15-o1-synthetic-vs-uniform-source.md` | F-14, F-15 |
| 안전망 상한 64·256·512·끔(O1) | 11/13 | `docs/presentation/performance/2026-11-13-safety-net-limit-source.md` | F-16 |
| v1 대 v2 묶음 개선, 자동 배정 | 11/16 | `docs/presentation/performance/2026-11-15-bottleneck-improvement-study.md` 7절 | F-17~F-22 |
| 개인 기록(`docs/context/measure-2026-11-15-progress.md`, `safety-net-measure-2026-11-13.md` 등) | 11/13~11/16 | **git 제외**라 이번 조사에서 읽지 않음 | 확인 못 함 |

---

## 3. 메모리 숫자 원자료

### 3-1. 배포 서버 컨테이너 메모리 상한·JVM 옵션(`compose.deploy.yml`, origin/dev 현재)

| 컨테이너 | mem_limit | JAVA_TOOL_OPTIONS / 기타 |
|---|---|---|
| config-server | 512m | `-Xms128m -Xmx256m -XX:+UseG1GC` |
| eureka | 512m | `-Xms128m -Xmx256m -XX:+UseG1GC` |
| gateway | **1024m** | `-Xms256m -Xmx512m -XX:MaxDirectMemorySize=128m -XX:+UseG1GC` |
| user-service | 640m | `-Xms192m -Xmx384m -XX:+UseG1GC` |
| reservation-service | 1152m | `-Xms384m -Xmx768m -XX:+UseG1GC` |
| payment-service | **1024m** | `-Xms256m -Xmx512m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=128m -XX:MaxDirectMemorySize=64m -XX:+UseG1GC` |
| mock-pg | 512m | `-Xms128m -Xmx256m -XX:+UseG1GC` |
| assistant-service | 384m | `-Xms96m -Xmx192m -XX:+UseG1GC` |
| postgres-user / -reservation / -payment | 512m / 1024m / 512m | — |
| redis | 512m | `--maxmemory 400mb --maxmemory-policy noeviction` |
| kafka | 1536m | `KAFKA_HEAP_OPTS -Xms512m -Xmx1g` |
| kafka-ui / prometheus / alertmanager / grafana / loki / alloy / zipkin | 512m / 768m / 128m / 384m / 512m / 256m / 768m | zipkin `JAVA_OPTS -Xmx384m`(kafka-ui `-Xmx256m`), 줄 143·195 |

명령: `git show origin/dev:compose.deploy.yml \| grep -nE '^  [a-z0-9-]+:\s*$\|mem_limit\|JAVA_TOOL'`. 기본 `compose.yml`(로컬)은 앱 공통 `mem_limit: 512m`, `JAVA_TOOL_OPTIONS -Xms64m -Xmx256m`, redis `maxmemory 128mb`, kafka `-Xms256m -Xmx512m`, postgres 256m·kafka 1024m 등이다(`compose.yml` 14·18·222·239·253행).
- 컨테이너 상한 합: PR #654 머지 시 compose config 계산 **12,992 → 13,248MiB**(frontend 포함)이고 장비(약 15.7GiB = 15,701MiB)의 **84%**(커밋 `b6edd3e0` 주석). 11/11 PR #488은 12,672 → 12,928MiB(커밋 `b00fec5e` 주석). 11/9 문서는 약 12.5GB(앱 5.2·데이터 4.5·관측 2.8GB, 78%)(`docs/design/infra/deployment.md` 169행).

### 3-2. 변경 이력 (`git log origin/dev -G'mem_limit|Xmx|…' -- compose*.yml …`로 찾음)

| 날짜 | 커밋(PR) | 변경 | 전 → 후 | 이유(커밋 본문·주석 요약) |
|---|---|---|---|---|
| 2026-10-27 | `bc4e15af` (#2) | 초기 `compose.yml` 환경(상한·힙 도입) | — → 앱 512m / `-Xms64m -Xmx256m` | 초기 환경 통합 |
| 2026-11-04 | `18c8e677` (ISE-174) | 배포용 `compose.deploy.yml` 신설 | — → config-server·eureka 512m(`-Xms128m -Xmx256m`), **gateway 768m**(`-Xms256m -Xmx512m`), user 640m, reservation 1152m(`-Xms384m -Xmx768m`), payment 768m, mock-pg 512m | 배포 서버(15.7GiB)용 상한. 합계 12.5GB 계획 |
| 2026-11-08 | `47b38035` (ISE-193) | 앱 7개 `-XX:+UseG1GC` 추가 | Serial(자동 선택) → G1 | 컨테이너 메모리 약 1,792MB 미만이면 JVM이 Serial GC를 고른다. 팀원 측정이 "배포 메모리 + G1" 조건이라 맞춤 |
| 2026-11-09 | `d947f843` (ISE-126) | assistant-service 추가 | — → 384m, `-Xms96m -Xmx192m -XX:+UseG1GC` | 상한 합 12.5GB로 늘어남 |
| 2026-11-11 | `34f1b6d3` (#475, ISE-94) | Zipkin 메모리 저장 한도 | 50만 건(기본) → `MEM_MAX_SPANS: 100000`(10만 건). zipkin 힙 384m | 샘플링 1.0과 측정 몰림 뒤 힙이 차 unhealthy → `compose --wait` 실패(TS-54) |
| 2026-11-11 | `b00fec5e` (#488, ISE-230) | **gateway 768m → 1024m**, `-XX:MaxDirectMemorySize=128m` 추가 | 768 → 1,024MiB | 힙 512MB + 비힙 약 158MB + JVM 지표 밖 약 130MB ≈ 800MB(계산값)라 768MiB에서 OOM 3회(배포 5차). 같은 조건(활성 VU 5,849)을 다시 돌려 OOM 0, 메모리 최대 785MiB(M1) |
| 2026-11-12 | `f75cb44c` (#571) | 측정 모드에서 Gateway 상한 끄기·바꾸기 덮어쓰기 | (compose.gateway-inflight.yml 추가) | 메모리 상수 변경 없음 |
| 2026-11-16 | `b6edd3e0` (#654, ISE-264, TS-71) | **payment 768m → 1024m**, 힙 밖 상한 3개 추가 | 768 → 1,024MiB, `MaxMetaspaceSize=256m`, `ReservedCodeCacheSize=128m`, `MaxDirectMemorySize=64m` | 11/15 UTC 하루 cgroup OOM(exit 137) 5회. 힙 최대 512 + 비힙 약 193~216 + 지표 밖 약 144MB가 상한을 넘음. 힙이 512까지 커지면 약 870MB(계산) |

### 3-3. OOM 관련 실측값 (문서 원자료, 코드로 재확인하지 못한 것은 문서 인용)

| 사건 | 값 | 출처 |
|---|---|---|
| Gateway OOM(배포 5차, 11/11 UTC 08:18~08:35) | `Memory cgroup out of memory: Killed process (java)` anon-rss **758MB**(757~758), 상한 768MiB, `-Xmx512m`, 3회, `RestartCount` 3. 직전 힙 committed 10초 사이 256 → 471MB, 비힙 158MB(Metaspace 90, 코드 캐시 약 50), 직접 버퍼 5MB, 지표 밖 약 130MB | `docs/design/infra/open-peak-scenarios.md` 678~699행 |
| Gateway 메모리 단계별(768m 시절) | 1,000/s 501MiB → 1,500/s 505 → 2,000/s 527 → 2,500/s 639 → 3,000/s OOM | 같은 문서 693행 |
| 11/10 03:50Z 서버 dmesg 컨테이너 OOM(java, 실제 메모리 약 754MB) | Gateway였는지 **확인 못 함** | 같은 문서 611행 |
| Gateway M1 후(1,024m) | OOM 0, 메모리 최대 785MiB, 2,000/s p95 435 → 752ms(메모리만 늘린 반쪽 성공) | `gateway-overload-troubleshooting-story.md` 47행, 커밋 `b00fec5e` 주석 |
| Gateway 상한 값별 메모리(O1, 11/13, 1GiB 중) | 상한 64 483MiB / 256 552 / 512 615·603 / 끔 609MiB. 힙 최대 194~284MiB | `2026-11-13-safety-net-limit-source.md` 장면 2 |
| payment OOM(11/15 UTC, 768m) | 하루 5회 SIGKILL, `docker inspect`는 재시작 뒤 exit 0으로 덮임. cgroup 700MiB = 힙 375 + 비힙 193 + 지표 밖 약 144MB. 비힙 committed 193~216MB(Metaspace 134MB, 코드 캐시 41MB) | `docs/troubleshooting.md` TS-71, `bottleneck-improvement-study.md` I10 |
| Prometheus OOM(11/13, 768m) | k6 remote write로 시계열 **71만 개**(확정 조회 URL 카디널리티), 19:20 KST OOM | `2026-11-13-safety-net-limit-source.md` "덤" |
| Zipkin 힙 | 50만 건 저장 → 힙 384m 초과 → unhealthy, 10만 건으로 제한 | `compose.deploy.yml`(34f1b6d3 주석), TS-54 |
| reservation(1,152m)·user(640m) | OOM 기록 **없음**. 11/16 문서에도 "다른 서비스 상한은 실측으로 검증하지 않음" 취지의 후속 과제만 있음(I10 남은 한계 칸) | `bottleneck-improvement-study.md` 7-1 표 |

---

## 4. CONTRIBUTORS 근거

주의: PR 작성자는 `gh` 기준 5명이고 **kye(커밋 12)** 는 PR 작성자에 없다(커밋은 10/26~11/1 사이로 W44에만 있음). 사용자 안내대로 alreadysolved·kye를 권영은으로 보되, kye 커밋의 내용은 별도로 열어 보지 않았다(확인 못 함).
영역 분류는 PR 제목의 `type(scope)`와 제목 내용으로 한 것이고, "주요" 선택은 이 조사자의 판단이다(PR 크기 `additions`는 가져오지 않았다).

### 안준현(alloy31) — 커밋 652, PR 153
PR 구성: feat 69, docs 34, fix 18, chore 12, test 10, ci 3. scope: payment 46, frontend 38, infra 23, mock-pg 6.
| # | PR | 제목 | 영역 |
|---|---|---|---|
| 1 | #81 | 결제 승인 API(멱등·TX1/TX2·금액 검증) 구현 | 결제 |
| 2 | #82 | PG 호출 보호(타임아웃·Circuit Breaker·Bulkhead 2그룹) 구현 | 결제 안정성 |
| 3 | #79 | payment.result 발행과 중복 수신 방지(Outbox·processed_event) | 결제·이벤트 |
| 4 | #188 | mock-pg 원장 상태 머신과 승인·조회·망취소 | Mock PG |
| 5 | #258~#262 | Ops 명령 실행 기반·결제/환불 resolve·retry | 결제 운영 도구 |
| 6 | #281·#284 | 대사 기반 RC-7·8, 예매 대사 RC-1~6 | 결제 대사 |
| 7 | #314 | main 폴링으로 GHCR 이미지 기다려 배포하는 Jenkins(JCasC) | 배포·CI |
| 8 | #404·#421 | assistant-service 내부 AI 상담, SSE 스트리밍 위젯 | 신규 서비스·프런트 |
| 9 | #521·#525·#533 | PG 허가 30·자동결제 전용 그룹, 기동 웜업, 결제 토큰 ES256 | 결제 성능·보안 |
| 10 | #667 | 배포 서버 https·Grafana 공개·`PUBLIC_URL` 한 곳 | 인프라 |

### 이강민(GGangMY) — 커밋 151, PR 147
PR 구성: docs 51, feat 31, chore 23, fix 13, test 12, perf 8. scope: lottery 29, infra 24, loadtest 15, gateway 12, docs 20.
| # | PR | 제목 | 영역 |
|---|---|---|---|
| 1 | #139~#245 | 추첨 도메인·응모·스케줄러·당첨 배정·예비자 승계·자동결제 요청 | 추첨(lottery) |
| 2 | #208 | 좌석 조회·선점 앞 입장 토큰 검증 필터 | Gateway |
| 3 | #488 | 맨 앞 동시 처리 수 상한과 Gateway 메모리 예산을 운영에 반영 | Gateway 과부하 |
| 4 | #497 | 상한 거절을 HttpHandler 데코레이터로 앞당김 | Gateway 과부하 |
| 5 | #501 | 운영 요청 로그를 5xx·끊긴 요청·200ms 이상만 | Gateway 관측 |
| 6 | #463·#487 | auth-redis 실패 원인별 지표, 운영값을 측정값으로 | Gateway 안정성 |
| 7 | #643·#610·#616 | O1 현실 모드, multi_device·진입 연타, Synthetic 행동 축 | 부하 시나리오 |
| 8 | #646~#649·#652·#653 | Kafka 헬스체크, 요청 로그 모드, 사전 거절, JIT 예열, 좌석 직렬화, ES256 웜업 | 11/15 야간 개선 |
| 9 | #654 | 컨테이너 메모리 상한을 힙 밖까지 맞춤(OOM 대응) | 인프라 |
| 10 | #651·#657 | 자동 배정 API와 인덱스·쿼리 수정 | 좌석 선점 |

### 최권우(KWON-WOO) — 커밋 40, PR 17
scope: reservation 10, seathold 4, loadtest 2.
| # | PR | 제목 | 영역 |
|---|---|---|---|
| 1 | #116 | 동기형 좌석 일괄 선점 API와 결제 토큰 발급 | 좌석 선점 |
| 2 | #190 | 선점 API 멱등 재요청 처리와 통합 테스트 | 좌석 선점 |
| 3 | #221 | 선점 만료 스케줄러 | 좌석 선점 |
| 4 | #349 | 좌석 선점 동시성·k6 부하 측정과 과부하 뒤 회복 분석 | 측정 |
| 5 | #350 | 오픈 피크 구간별 k6 시나리오 12개와 측정 계획 | 측정 설계 |
| 6 | #430 | 회차 좌석·보유 매수를 원장에 맞추는 대사와 내부 API | 정합성 |
| 7 | #458 | 선점 매수 한도·멱등키 연타·잠금 대기·만료 중복을 동시 요청에서 지킴 | 동시성 |
| 8 | #554·#609 | 접수형 선점 접수·결과 조회, 소비자·DLQ·30초 시한 | 접수형 선점 |
| 9 | #593 | 입장 토큰을 선점 성공 순간 소모 | 토큰 |
| 10 | #661 | 잠금 대기 50ms·한 문장 사전 판정·동시 처리 상한으로 409 경로 줄임 | 성능 |

### 반효승(hyos1) — 커밋 36, PR 38
scope: reservation 15, loadtest 7, concert 4.
| # | PR | 제목 | 영역 |
|---|---|---|---|
| 1 | #96·#97 | 공연·회차·좌석 스키마·KSPO DOME 좌석 시드 | 도메인 |
| 2 | #136·#138·#202·#255 | 운영자 공연·회차·좌석 등록, 목록·상세·좌석 조회 API | 공연·좌석 |
| 3 | #277·#282 | payment.result 소비로 예매 확정·해제·티켓, DLQ | 예매 확정 |
| 4 | #318 | 예매 상세·내 예매 목록 | 예매 조회 |
| 5 | #377 | 좌석 조회를 엔티티 대신 DTO로(P4a) | 좌석 조회 성능 |
| 6 | #534 | 좌석 배치를 서비스 메모리에 캐시(P4b) | 좌석 조회 성능 |
| 7 | #603·#605 | 커넥션 구간 축소, 선점 가능 여부 1초 캐시(P4c) | 좌석 조회 성능 |
| 8 | #438·#484 | S1·B1-d 시나리오, 배포 측정 결과 기록 | 측정 |
| 9 | #678 | 좌석 생성 직후 통계 갱신으로 Nested Loop 방지 | DB 실행 계획 |
| 10 | #613 | 결제 거절 뒤 보상(좌석 원복) 흐름 그림·시연 캡처 | 발표 자료 |

### 권영은(alreadysolved) — 커밋 28(+ kye 12 = 40), PR 29
scope: queue 22, loadtest 5.
| # | PR | 제목 | 영역 |
|---|---|---|---|
| 1 | #99·#133 | 대기열 진입 Lua·API | 대기열 |
| 2 | #141·#144 | 순번 조회 Lua·API | 대기열 |
| 3 | #194·#307 | 입장 토큰 발급기·검증기·발급 API | 입장 토큰 |
| 4 | #268 | 일정 속도 입장 처리 Lua·스케줄러 | 대기열 |
| 5 | #319 | 대기열 통합 테스트 | 테스트 |
| 6 | #416·#447·#449 | Q1-d 몰림 시나리오, auth-redis bulkhead 측정 덮어쓰기, 배포 Q1·Q2·Q3 1차 결과 | 측정 |
| 7 | #466·#479 | 식은 Gateway·차단기 설정 비교 결과 | 측정 |
| 8 | #574 | Redis 장애 때 503·Retry-After·차단기 | 대기열 안정성 |
| 9 | #620·#677 | 대기열 지표, Grafana 대시보드 | 관측 |
| 10 | #627 | 차례 뒤 1분 지난 사용자를 맨 뒤로, 입장 판정을 Lua로 | 대기열 정합성 |

---

## 5. 사실 카드 표

성격: 실측 / 계산값 / 참고값(조건 다름) / 설계값. "근거"의 문서 경로는 모두 저장소 상대 경로이고 줄 번호는 `origin/dev` 기준 근사다(문서가 바뀌면 달라진다). 모든 숫자는 해당 문서가 적은 값이며 이번 조사에서 다시 측정한 것은 없다.

| id | 서비스 | 주장 | 숫자(전 → 후) | 배수(계산식) | 조건 | 성격 | 근거 |
|---|---|---|---|---|---|---|---|
| F-01 | 전체 | 22일에 커밋 919개, PR 384건 | — | — | origin/dev 2026-11-16 15:34 KST | 실측 | 1장 명령 |
| F-02 | 전체 | 서비스·모듈 12, 컨테이너 21, ADR 63, TS 74 | — | — | origin/dev | 실측 | 1장 명령 |
| F-03 | 전체 | Java 코드 83,631줄(테스트 49,749 > main 33,882) | test/main = 49,749 ÷ 33,882 | 1.47 | `*.java` 전체 | 계산값 | 1장 |
| F-04 | gateway | G1 순번 조회 2,000/s에서 p95 435ms, 굿풋 1,469/s(상한 없음 기준선) | 서버 CPU 99%, 1,500/s CPU 93% | — | 배포 5차 0a, m7i.xlarge, 회차 96, 11/11 UTC 08:17~08:36 | 실측 | `open-peak-scenarios.md` 614~640행 |
| F-05 | gateway | 메모리 768MiB에서 Gateway가 OOM으로 3회 죽었다 | anon-rss 758MB / 상한 768MiB. 768 → 1,024MiB 뒤 OOM 3회 → 0회, 메모리 최대 785MiB | — | 활성 VU 5,849 동일 조건 재현(M1) | 실측 | 같은 문서 678~699행, 커밋 `b00fec5e` |
| F-06 | gateway | 맨 앞 동시 처리 상한 64가 2,000/s p95를 줄였다 | 571ms → 87ms, 굿풋 1,304 → 1,696/s | p95: 571 ÷ 87 = 6.6배 / 굿풋 1,696 ÷ 1,304 = 1.30배 | 배포 6차 L500 → L64, 같은 날 11/11, 같은 배포 `8b95fe68`, 회차당 1회 | 실측(같은 날) | `2026-11-14-gateway-overload-one-page.md` 개선 1 |
| F-07 | gateway | 상한 거절을 HttpHandler 데코레이터로 앞당겨 2,500/s 굿풋을 올렸다 | 1,608 → 1,814/s, p95 104 → 78ms | 굿풋 1,814 ÷ 1,608 = 1.13배. 거절 한 건 비용은 받은 요청 한 건의 2~8%(0.02~0.08ms 대 1.06ms) | 6차 L64 → 7차 H1, 배포 `8b95fe68` → `1e9b1c75`. 비용 전후 측정 방법이 달라 "1/5 → 1/10 이하"까지만 | 실측 | 같은 문서 개선 2 |
| F-08 | gateway | 요청 로그를 문제 요청만 남겨 서버 CPU를 낮췄다 | 1,000/s 서버 CPU 78% → 66%, 2,500/s 굿풋 1,814 → 2,122/s | CPU −12%p, 굿풋 2,122 ÷ 1,814 = 1.17배 | 7차 H1 → H2(배포 같음). 운영 `problems` 모드는 problems 대 끔 1,500/s 78.5 대 79.1% | 실측 | 같은 문서 개선 3 / `gateway-overload-troubleshooting-story.md` 9단계 |
| F-09 | gateway | 누적: 순번 조회 2,500/s 굿풋 1,608 → 2,122/s | 1,608 → 2,122 | 2,122 ÷ 1,608 = 1.32배 | 같은 4 vCPU 한 대, 장치를 하나씩 더함(상한 64 → H1 → H2) | 실측·계산값 | 같은 문서 "결론" |
| F-10 | reservation(concert) | 좌석 조회 한계가 단계마다 올랐다 | 실패 0 최대 초당 5 → 40 → 100 → 200(개선 전 → P4a → P4b → P4c) | 200 ÷ 5 = **40배** | 배포 서버, 회차 104(15,000석, 응답 약 820KB), 풀 5, 상한 끔, 30초 × 3회, 11/12·14 | 실측 | `open-peak-scenarios.md` 1186~1240행 |
| F-11 | payment | PG 동시 허가 10 → 30으로 승인 한도가 3배 | 초당 31.2 → 91.5건 승인 | 91.5 ÷ 31.2 = 2.9배(계산 30 ÷ 0.32초 ≈ 94) | Mock PG 지연 300ms, 11/12 UTC, 각 1회 | 실측 | 같은 문서 1283~1345행 |
| F-12 | lottery | 자동결제 처리 속도가 약 3배로 올랐다 | 초당 약 3.2건(600초 내 93% 미달) → 약 9.4건(100% 합격) | 9.4 ÷ 3.2 = 2.9배(문서는 11/10 대비 2.95배) | 응모 30,000명·당첨 약 2,000·Mock PG 300ms. 11/10 기준선 → 11/13 ADR 0051(`payment.command` 파티션·소비자 3·`pg-billing` 동시 3) 반영 후 2회 | 실측·계산값 | `numbers-and-measurement.md` 3-5절 |
| F-13 | lottery·payment | 자동결제와 겹친 일반 결제의 503이 사라졌다 | 9.5% → 0건(일반 결제 15,000건 전부 승인, p95 319.8ms) | −9.5%p | L2-b, 11/10 → 11/15, 허가 그룹 분리 전후 | 실측 | 같은 문서 3-6절 |
| F-14 | 대기열·gateway | 균일 대 Synthetic: 입구 몰림 7.9배 | 대기열 진입 초당 최대 353 → 2,792 | 2,792 ÷ 353 = 7.9배 | O1, 상한 64, 사용자 3,000명, 회차 122·123, 각 1회 | 실측·계산값 | `bottleneck-improvement-study.md` 0절 |
| F-15 | reservation | 같은 O1에서 확정이 균일 1,973 대 Synthetic 2,547 | 선점 409 49.8% 대 11.7%(계산값) | 2,547 ÷ 1,973 = 1.29배 | 정합성 통과(확정 = SOLD 석 = 승인, 중복 0) | 실측 | `synthetic-data.md` 6절 |
| F-16 | gateway | 상한을 끄거나 512로 두어도 메모리는 1GiB의 60% 안 | 처리 중 최대 64 → 약 490, 메모리 483 → 603~615MiB, 확정 2,574 → 4,503~4,758 | 확정 4,758 ÷ 2,574 = 1.85배 | O1 15,000명·VU 500, 11/13, 512만 2회 반복 | 실측 | `2026-11-13-safety-net-limit-source.md` 장면 2 |
| F-17 | reservation | 개선 묶음(v2)이 선점 p95를 1/5 이하로 | 선점 p95 242.7·196ms → 44.5·31.4ms | 평균 219.4 ÷ 38.0 = 5.8배(각 회 5.5·6.2배) | 현실 모드 R 각 2회, 상한 64·풀 5·Mock PG 300ms, 예열 후, v1 `43aac775` → v2 `2fe3ed10`, 11/16 | 실측·계산값 | `bottleneck-improvement-study.md` 7-3절 |
| F-18 | reservation | 확정 +10.7%, `error` −95% | 확정 1,922·2,021 → 2,201·2,162, `error` 403·310대 → 24·12 | 평균 2,181.5 ÷ 1,971.5 = 1.107 | 같음. v1 두 회 차이 5%보다 큼 | 실측·계산값 | 같은 문서 |
| F-19 | reservation·DB | DB 커넥션 대기가 사라졌다 | Hikari 대기 최대 12·14 → 0·0, 균일은 32 → 0 | — | 같음 | 실측 | 같은 문서 |
| F-20 | 호스트 | 바쁜 30초 호스트 CPU를 약 20%p 낮췄다 | 0.986·0.95대 → 0.799·0.775(1.0 = 4코어 전부), reservation CPU 117.1 → 73.4% | −19~21%p / 73.4 ÷ 117.1 = 0.63 | 같음 | 실측 | 같은 문서 |
| F-21 | kafka | Kafka 헬스체크가 JVM을 매번 띄워 CPU가 튀었다 | CPU 최대 228.8% → 15.8%(v1 → v2). 로컬 검사 1회 1,348~1,604ms → 4~6ms | 228.8 ÷ 15.8 = 14.5배 / 로컬 약 270배(1,348 ÷ 5) | 배포 서버 R 2회, 로컬 검사는 별도 **참고값**. TS-70 | 실측·참고값 | 같은 문서 I4, `troubleshooting.md` TS-70 |
| F-22 | reservation | 자동 배정 첫 구현은 실패했고 인덱스로 고쳐 최고치를 냈다 | 확정 433 → 2,248, postgres CPU 280% → 18.2%, 선점 p95 1.92s → 30.8ms | 2,248 ÷ 433 = 5.2배 / 280 ÷ 18.2 = 15.4배 | v2 auto(165) → v3 auto(170), 각 1회. 같은 v2 `map` R(2,162~2,201) 대비 +2.1~4.0%는 변동 범위 안 | 실측 | 같은 문서 7-4절, TS-72 |
| F-23 | concert | 좌석 응답 직렬화(Jackson 약 32%)를 1초 재사용 | 1,000회 직렬화 1,495·1,210ms → 110·109ms | 1,495 ÷ 110 = 13.6배 | **로컬 벤치 단일 스레드, 참고값**. 서버 효과는 별도 | 참고값 | 같은 문서 I3 |
| F-24 | payment | 메모리 상한을 실측으로 고쳤다 | 768 → 1,024MiB. 실측 cgroup 700MiB = 힙 375 + 비힙 193 + 지표 밖 144, 힙 512까지 가면 약 870MB | — | 11/15 UTC 하루 OOM 5회 → v2 R·균일 실행마다 커널 OOM 0 | 실측·계산값 | `troubleshooting.md` TS-71, 커밋 `b6edd3e0` |
| F-25 | 대기열 | 장비 한 대가 받는 순번 조회 처리 한도 | 초당 약 2,000~2,300건, vCPU당 약 500~575건 | 2,000 ÷ 4 = 500 | ADR 0050, G1, 4 vCPU 한 대에 컨테이너 21개 | 실측·계산값 | `2026-11-15-scale-comparison.md` 2절 |
| F-26 | 대기열 | 대기 인원 ≈ 조회 처리량 × 조회 간격(리틀의 법칙)으로 약 2.2만 명 | 2,000/s × 11초 = 22,000명, 대기 약 440초(22,000 ÷ 50) | — | **측정 아님, 계산값**. 조회 간격 평균 11초(`poll-min 3s`·`poll-max 10s`·지터 20%), 입장 50/s | 계산값 | 같은 문서 3절 |
| F-27 | 추첨 | 추첨 자동결제 응모 30,000명 중 당첨 2,008건 전부 결제 | 마지막 결제가 추첨 뒤 206~229초, 일반 결제 15,000건 동시 전부 승인 | 초당 약 8.8~9.7건 | L2-b 11/15, 상한 64, 1회 | 실측 | 같은 문서 2절, `numbers-and-measurement.md` 3-6절 |
| F-28 | lottery·reservation | 추첨 직후 선점 p95 증가는 합격선을 넘었다 | 11.06ms → 21.0ms(+90%), 기준 20% 이하 미달 | 21.0 ÷ 11.06 = 1.9배 | L3, 1회. 오류 0, 증가 폭 약 10ms. 분리 여부는 팀 판단 | 실측 | `numbers-and-measurement.md` 3-6절 |
| F-29 | 대기열 | 11/13 O1에서 상한 64는 순번 조회를 초당 94~245건 거절 | 확정 2,574 대 512의 4,758건(약 절반) | 4,758 ÷ 2,574 = 1.85 | O1 L64 구간, 오픈 +3.5~4.5분 | 실측 | `2026-11-13-safety-net-limit-source.md` 장면 3 |
| F-30 | 인프라 | 측정 도구도 부하를 받는다: Prometheus remote write로 시계열 71만 개, OOM | 768MiB 상한 OOM | — | 11/13 19:20 KST | 실측 | 같은 문서 "덤" |
| F-31 | 호스트 | 인스턴스 선택: flex 기준 성능 보장 없음 → 일반 | m7i-flex.xlarge → m7i.xlarge(시간당 0.2394 → 0.252달러) | +5.3%(0.252 ÷ 0.2394 − 1) | 11/5 결정 | 실측(결정 기록) | `2026-11-15-scale-comparison.md` 1절 |

회차 단위 단일 측정이 많다(각 1회). 반복 변동이 적힌 것은 F-06(같은 값 두 번 잰 차이 굿풋 약 5%), F-16(512 두 회차 차이 메모리 12.5MiB·확정 74건), F-17·18(v1 두 회 차이 확정 5%)뿐이다.

---

## 6. 타임라인 (KST 기준이 아닌 날짜는 UTC 표기)

| 날짜 | 사건 | 근거 |
|---|---|---|
| 10/26 | 저장소 첫 커밋: 학습·판단 기록 시스템과 공용 지침(`7635dca4`) | `git log --reverse` |
| 10/27 | 초기 환경 브랜치 통합(`bc4e15af`, PR #2), compose·메모리 상한 초기값. 병합 PR 8 | git |
| 10/28 | 결제 핵심 PR 6개(#74~#89: 스키마·PgClient·Outbox·승인 API·PG 보호·조회). 커밋 77 | PR 목록 |
| 10/29~10/31 | 계약 문서(#93~#95), 대기열 진입 Lua·API(#99, #133), 예약 도메인(#96~#98) | PR 목록 |
| 11/2 | **커밋 177·PR 44(최다 커밋 일)**: 추첨 전 구간(#139~#245), 결제 환불·재확인·DLQ, mock-pg 상태 머신, 프런트 기본 화면 | PR·커밋 |
| 11/3 | 결제 Outbox·DLQ·Ops 명령·대사(#229~#289), 결제 지표·Grafana·알림 | PR |
| 11/4 | 배포 서버용 `compose.deploy.yml`(`18c8e677`)·Jenkins JCasC(#314)·Loki/Alloy·Alertmanager. **첫 배포** 서버 `m7i-flex.xlarge` 4 vCPU 15.7GiB | 커밋, `deployment.md` |
| 11/5 | 인스턴스를 `m7i.xlarge`로 교체, 프런트 화면 대량(#322~#335) | `scale-comparison.md` |
| 11/6 | 로컬 사전 측정(L1·L2 첫 회, 동시성 k6 #349·#350), 시나리오 12개 | 결과 파일, PR |
| 11/8 | 배포 앱 G1 GC 명시(`47b38035`), 측정 모드(#380) | 커밋 |
| 11/9 | assistant-service(#404), 운영자 시연 장애 패널, PR 41건. 배포 1차 Q1·Q2·Q3 측정 시작(UTC 11/9) | PR, `open-peak-scenarios.md` 305행 |
| 11/10 | 추첨 L1·L2 배포 서버 측정(7회 L1·다수 L2, 결과 파일 11/10), 2~3차 auth-redis 비교, 4차 G1 재측정(UTC 11/10) | 결과 파일, 문서 |
| 11/11 | PAY1 첫 측정(UTC 19:16), 5차 G1 + **Gateway OOM 3회**(UTC 08:18~08:35), **M1(768 → 1,024m)**·동시 처리 상한(#488)·거절 앞당김(#497), 6·7차 측정(UTC) | git, 문서 |
| 11/12 | PR 54건(최다). 8차 R0 + 요청 로그 `problems`(#501), PAY1 허가 30(#521), 결제 토큰 ES256(#533), 접수형 선점 API(#554), S1 좌석 조회 | PR |
| 11/13 | 안전망 상한 64·256·512·끔 O1 측정, Prometheus remote write OOM, 입장 토큰 소모(#593), 좌석 선점 대시보드(#600), 자동결제 재측정(ADR 0051) | 문서 |
| 11/14 | O1 multi_device·진입 연타(#610), Synthetic 행동 축(#616), 접수형 소비자·DLQ(#609) | PR |
| 11/15 | 균일·Synthetic O1 측정(회차 122·123, 18:42~19:33 KST), L2-b·L3(19:46·20:01 KST), 야간 개선 PR #642~#657, payment OOM 5회(UTC 11/15) | 문서 |
| 11/16 | v1·v2 묶음 측정(R 각 2회), 자동 배정 v2 auto 실패(06:41) → v3 재측정, **payment 메모리 1,024m(#654, 03:00 KST)**, https·Grafana(#667), Swagger(#685, 마지막 PR 15:34 KST). PR 17건 | 문서, git |

---

## 7. 못 본 것과 이유

1. **Gradle 테스트 실행 결과**: `./gradlew test`를 돌리지 않았다(작업 트리 변경 금지·시간). 테스트 수는 `@Test` 정규식 값이다. "통과 N개"는 확인 못 함.
2. **kye 12커밋의 내용·영역**: PR이 없고(PR 작성자 목록에 없음) 커밋 내용을 열지 않았다. 권영은과 동일 인물이라는 것은 지시문을 따랐다.
3. **PR 크기(추가·삭제 줄)**: `gh pr list`에 `additions`를 요청하지 않아 "큰 PR" 순위를 줄 수로 매기지 못했다. 4장의 주요 PR은 제목 기준 조사자 판단이다.
4. **k6 raw CSV·tar.gz 풀이**: `raw.csv.gz` 52개와 g6·g7·g8 tar.gz 26개(약 400MB)를 풀지 않았다. 내부 Prometheus archive에 문서로 옮기지 않은 숫자가 있는지는 확인 못 함.
5. **개인 측정 기록**: `docs/context/*`(git 제외, 예: `measure-2026-11-15-progress.md`, `overnight-2026-11-15-plan.md`)는 문서가 원천으로 지목하지만 읽지 않았다. 11/15~16 숫자는 `docs/presentation/performance/*`가 옮긴 값을 인용했다.
6. **Q1·Q2·Q3 1~3차 핵심 숫자, 일부 O1 세부, 추첨 L1·L2 11/10 회차 표**: 문서 위치만 적고 사실 카드로 뽑지 않았다.
7. **사실 카드의 근거 줄 번호**: 문서가 바뀌면 달라진다. 줄 번호는 origin/dev 조사 시점(11/16) 기준 근사값이다.
8. **코드의 현재 운영 설정값 교차 확인**: Gateway 상한 64 등 `config-repo/*.yml` 값을 다시 열어 대조하지 않았다(문서 인용). `Jenkinsfile`, `.github/workflows/**`(6개: build, frontend, images, loki-rules, prometheus-rules, records-check), `script/**`(약 20개)는 목록만 확인하고 내용·실행 시간(CI 시간)은 조사하지 않았다.
9. **로컬 dev와 origin/dev 차이**: 로컬 작업 트리는 38커밋 뒤다. 따라서 로컬 파일을 직접 센 값은 쓰지 않았고(예: payment 768m가 보이는 것은 로컬이 오래돼서), 위 모든 숫자는 origin/dev 기준이다.
10. **배포 서버 실제 메모리 사용량**: `docker stats`는 문서 인용 값만 있다. 서비스별 평상시·피크 실사용 메모리 전체표는 만들지 못했다.

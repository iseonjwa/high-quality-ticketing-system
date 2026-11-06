# ISE-79 측정 원자료 (2026-11-03)

[concurrency-results.md](concurrency-results.md)의 표를 만든 테스트 출력 원문이다. 기준 커밋은 `66f9968`이고, 단계는 그 위에 쌓은 미커밋 변경이다(워크트리 브랜치 `experiment/ISE-79-concurrency-measure`).

## 1. 동시성 시나리오 C1~C7

### S0

```text
[SEATHOLD-CONCURRENCY] C1 rounds=3 requests=300 results={OK=3, SEAT_ALREADY_HELD=297} p50=120ms p95=202ms max=229ms extra={roundsWithoutSingleWinner=0, doubleSold=0}
[SEATHOLD-CONCURRENCY] C2 rounds=50 requests=500 results={OK=86, SEAT_ALREADY_HELD=414} p50=31ms p95=58ms max=110ms extra={doubleSold=0, deadlocks=0}
[SEATHOLD-CONCURRENCY] C3 rounds=3 requests=15 results={OK=3, SEAT_ALREADY_HELD=12} p50=2034ms p95=2215ms max=2215ms extra={roundMaxMillis=[2215, 2026, 2037], holdLockMillis=2000}
[SEATHOLD-CONCURRENCY] C4 rounds=30 requests=120 results={OK=120} p50=34ms p95=51ms max=61ms extra={maxActiveSeats=8, overLimitRounds=30, quotaMismatches=0}
[SEATHOLD-CONCURRENCY] C5 rounds=30 requests=90 results={OK=30, SEAT_ALREADY_HELD=60} p50=37ms p95=53ms max=67ms extra={roundsWithoutSingleReservation=0}
[SEATHOLD-CONCURRENCY] C6 rounds=1 requests=2 results={OK=2} p50=1191ms p95=1192ms max=1192ms extra={seatMismatches=0, deadlocks=0, errors=0, wasted=100, expired=100}
[SEATHOLD-CONCURRENCY] C7 rounds=30 requests=90 results={OK=60, SEAT_ALREADY_HELD=30} p50=22ms p95=36ms max=89ms extra={invariantBrokenRounds=0, deadlocks=0}
```

### S1

```text
[SEATHOLD-CONCURRENCY] C1 rounds=3 requests=300 results={OK=3, SEAT_ALREADY_HELD=297} p50=115ms p95=182ms max=197ms extra={roundsWithoutSingleWinner=0, doubleSold=0}
[SEATHOLD-CONCURRENCY] C2 rounds=50 requests=500 results={OK=84, SEAT_ALREADY_HELD=416} p50=28ms p95=47ms max=81ms extra={doubleSold=0, deadlocks=0}
[SEATHOLD-CONCURRENCY] C3 rounds=3 requests=15 results={OK=3, SEAT_ALREADY_HELD=12} p50=2035ms p95=2174ms max=2174ms extra={roundMaxMillis=[2174, 2027, 2037], holdLockMillis=2000}
[SEATHOLD-CONCURRENCY] C4 rounds=30 requests=120 results={OK=60, PURCHASE_LIMIT_EXCEEDED=60} p50=30ms p95=55ms max=62ms extra={maxActiveSeats=4, overLimitRounds=0, quotaMismatches=0}
[SEATHOLD-CONCURRENCY] C5 rounds=30 requests=90 results={OK=30, SEAT_ALREADY_HELD=60} p50=36ms p95=68ms max=98ms extra={roundsWithoutSingleReservation=0}
[SEATHOLD-CONCURRENCY] C6 rounds=1 requests=2 results={OK=2} p50=1299ms p95=1300ms max=1300ms extra={deadlocks=0, errors=0, wasted=100, expired=100, seatMismatches=0}
[SEATHOLD-CONCURRENCY] C7 rounds=30 requests=90 results={OK=60, SEAT_ALREADY_HELD=30} p50=20ms p95=27ms max=88ms extra={invariantBrokenRounds=0, deadlocks=0}
```

### S2

```text
[SEATHOLD-CONCURRENCY] C1 rounds=3 requests=300 results={OK=3, SEAT_ALREADY_HELD=297} p50=113ms p95=186ms max=197ms extra={doubleSold=0, roundsWithoutSingleWinner=0}
[SEATHOLD-CONCURRENCY] C2 rounds=50 requests=500 results={OK=87, SEAT_ALREADY_HELD=413} p50=29ms p95=45ms max=52ms extra={doubleSold=0, deadlocks=0}
[SEATHOLD-CONCURRENCY] C3 rounds=3 requests=15 results={OK=3, SEAT_ALREADY_HELD=12} p50=2022ms p95=2167ms max=2167ms extra={roundMaxMillis=[2167, 2022, 2024], holdLockMillis=2000}
[SEATHOLD-CONCURRENCY] C4 rounds=30 requests=120 results={OK=60, PURCHASE_LIMIT_EXCEEDED=60} p50=29ms p95=36ms max=46ms extra={overLimitRounds=0, maxActiveSeats=4, quotaMismatches=0}
[SEATHOLD-CONCURRENCY] C5 rounds=30 requests=90 results={OK=90} p50=42ms p95=56ms max=111ms extra={roundsWithoutSingleReservation=0}
[SEATHOLD-CONCURRENCY] C6 rounds=1 requests=2 results={OK=2} p50=1420ms p95=1421ms max=1421ms extra={errors=0, deadlocks=0, seatMismatches=0, expired=100, wasted=100}
[SEATHOLD-CONCURRENCY] C7 rounds=30 requests=90 results={OK=60, SEAT_ALREADY_HELD=30} p50=22ms p95=29ms max=90ms extra={invariantBrokenRounds=0, deadlocks=0}
```

### S3

```text
[SEATHOLD-CONCURRENCY] C1 rounds=3 requests=300 results={OK=3, SEAT_ALREADY_HELD=297} p50=112ms p95=199ms max=220ms extra={roundsWithoutSingleWinner=0, doubleSold=0}
[SEATHOLD-CONCURRENCY] C2 rounds=50 requests=500 results={OK=86, SEAT_ALREADY_HELD=414} p50=29ms p95=45ms max=64ms extra={doubleSold=0, deadlocks=0}
[SEATHOLD-CONCURRENCY] C3 rounds=3 requests=15 results={SEAT_ALREADY_HELD=15} p50=564ms p95=1060ms max=1060ms extra={roundMaxMillis=[811, 1060, 547], holdLockMillis=2000}
[SEATHOLD-CONCURRENCY] C4 rounds=30 requests=120 results={OK=60, PURCHASE_LIMIT_EXCEEDED=60} p50=30ms p95=40ms max=48ms extra={maxActiveSeats=4, overLimitRounds=0, quotaMismatches=0}
[SEATHOLD-CONCURRENCY] C5 rounds=30 requests=90 results={OK=90} p50=38ms p95=56ms max=204ms extra={roundsWithoutSingleReservation=0}
[SEATHOLD-CONCURRENCY] C6 rounds=1 requests=2 results={OK=2} p50=1366ms p95=1367ms max=1367ms extra={deadlocks=0, errors=0, wasted=100, expired=100, seatMismatches=0}
[SEATHOLD-CONCURRENCY] C7 rounds=30 requests=90 results={OK=60, SEAT_ALREADY_HELD=30} p50=22ms p95=30ms max=148ms extra={invariantBrokenRounds=0, deadlocks=0}
```

### S4

```text
[SEATHOLD-CONCURRENCY] C1 rounds=3 requests=300 results={OK=3, SEAT_ALREADY_HELD=297} p50=154ms p95=271ms max=290ms extra={roundsWithoutSingleWinner=0, doubleSold=0}
[SEATHOLD-CONCURRENCY] C2 rounds=50 requests=500 results={OK=81, SEAT_ALREADY_HELD=419} p50=30ms p95=47ms max=60ms extra={deadlocks=0, doubleSold=0}
[SEATHOLD-CONCURRENCY] C3 rounds=3 requests=15 results={SEAT_ALREADY_HELD=15} p50=572ms p95=1305ms max=1305ms extra={holdLockMillis=2000, roundMaxMillis=[1305, 559, 572]}
[SEATHOLD-CONCURRENCY] C4 rounds=30 requests=120 results={OK=60, PURCHASE_LIMIT_EXCEEDED=60} p50=30ms p95=60ms max=79ms extra={overLimitRounds=0, maxActiveSeats=4, quotaMismatches=0}
[SEATHOLD-CONCURRENCY] C5 rounds=30 requests=90 results={OK=90} p50=49ms p95=86ms max=235ms extra={roundsWithoutSingleReservation=0}
[SEATHOLD-CONCURRENCY] C6 rounds=1 requests=2 results={OK=2} p50=733ms p95=733ms max=733ms extra={deadlocks=0, seatMismatches=0, expired=100, wasted=0, errors=0}
[SEATHOLD-CONCURRENCY] C7 rounds=30 requests=90 results={OK=60, SEAT_ALREADY_HELD=30} p50=22ms p95=31ms max=136ms extra={invariantBrokenRounds=0, deadlocks=0}
```

## 2. 부하 단일 실행 (L1·L2·L3·L6)

### s0-pool5

```text
[SEATHOLD-LOAD] L1 {vus=100, pool=5, requests=1000, throughput=177, avgMs=457, p50Ms=465, p95Ms=981, p99Ms=1022, p95Ms201=984, p95Ms409=952, status={201=919, 409:SEAT_ALREADY_HELD=81}, maxPendingConnections=95, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L6 {vus=1000, pool=5, requests=1000, throughput=329, avgMs=1270, p50Ms=1385, p95Ms=1980, p99Ms=2023, p95Ms201=191, p95Ms409=1980, status={201=10, 409:SEAT_ALREADY_HELD=990}, maxPendingConnections=195, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L2 {vus=200, pool=5, requests=18343, throughput=586, avgMs=334, p50Ms=317, p95Ms=478, p99Ms=543, p95Ms201=510, p95Ms409=475, status={201=980, 409:SEAT_ALREADY_HELD=17363}, maxPendingConnections=196, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=50, pool=5, requests=9420, throughput=446, avgMs=109, p50Ms=106, p95Ms=152, p99Ms=214, p95Ms201=152, p95Ms409=156, status={201=5596, 409:SEAT_ALREADY_HELD=3824}, maxPendingConnections=45, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=100, pool=5, requests=10126, throughput=478, avgMs=203, p50Ms=203, p95Ms=261, p99Ms=398, p95Ms201=263, p95Ms409=257, status={201=5804, 409:SEAT_ALREADY_HELD=4322}, maxPendingConnections=96, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=200, pool=5, requests=10015, throughput=466, avgMs=414, p50Ms=400, p95Ms=588, p99Ms=792, p95Ms201=629, p95Ms409=558, status={201=5788, 409:SEAT_ALREADY_HELD=4227}, maxPendingConnections=196, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=400, pool=5, requests=9900, throughput=453, avgMs=843, p50Ms=848, p95Ms=1155, p99Ms=1236, p95Ms201=1187, p95Ms409=1067, status={201=5781, 409:SEAT_ALREADY_HELD=4119}, maxPendingConnections=196, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=800, pool=5, requests=10553, throughput=468, avgMs=1614, p50Ms=1653, p95Ms=1946, p99Ms=1965, p95Ms201=1957, p95Ms409=1919, status={201=5958, 409:SEAT_ALREADY_HELD=4595}, maxPendingConnections=195, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
```

### s0-pool20

```text
[SEATHOLD-LOAD] L1 {vus=100, pool=20, requests=1000, throughput=274, avgMs=263, p50Ms=236, p95Ms=481, p99Ms=742, p95Ms201=481, p95Ms409=493, status={201=919, 409:SEAT_ALREADY_HELD=81}, maxPendingConnections=78, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L6 {vus=1000, pool=20, requests=1000, throughput=708, avgMs=238, p50Ms=256, p95Ms=440, p99Ms=572, p95Ms201=47, p95Ms409=440, status={201=10, 409:SEAT_ALREADY_HELD=990}, maxPendingConnections=180, maxLockWaiters=2, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L2 {vus=200, pool=20, requests=27768, throughput=890, avgMs=220, p50Ms=182, p95Ms=441, p99Ms=753, p95Ms201=573, p95Ms409=432, status={201=994, 409:SEAT_ALREADY_HELD=26774}, maxPendingConnections=181, maxLockWaiters=2, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=50, pool=20, requests=19267, throughput=916, avgMs=53, p50Ms=50, p95Ms=86, p99Ms=121, p95Ms201=100, p95Ms409=74, status={201=7978, 409:SEAT_ALREADY_HELD=11289}, maxPendingConnections=30, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=100, pool=20, requests=19776, throughput=939, avgMs=104, p50Ms=99, p95Ms=177, p99Ms=221, p95Ms201=195, p95Ms409=162, status={201=8032, 409:SEAT_ALREADY_HELD=11744}, maxPendingConnections=80, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=200, pool=20, requests=18533, throughput=874, avgMs=222, p50Ms=212, p95Ms=362, p99Ms=496, p95Ms201=367, p95Ms409=359, status={201=7868, 409:SEAT_ALREADY_HELD=10665}, maxPendingConnections=180, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=400, pool=20, requests=18691, throughput=869, avgMs=444, p50Ms=437, p95Ms=651, p99Ms=843, p95Ms201=650, p95Ms409=651, status={201=7869, 409:SEAT_ALREADY_HELD=10822}, maxPendingConnections=181, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=800, pool=20, requests=18018, throughput=833, avgMs=924, p50Ms=909, p95Ms=1320, p99Ms=1538, p95Ms201=1461, p95Ms409=1254, status={201=7722, 409:SEAT_ALREADY_HELD=10296}, maxPendingConnections=180, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
```

### s4-pool5

```text
[SEATHOLD-LOAD] L1 {vus=100, pool=5, requests=1000, throughput=166, avgMs=497, p50Ms=495, p95Ms=905, p99Ms=927, p95Ms201=907, p95Ms409=704, status={201=939, 409:SEAT_ALREADY_HELD=61}, maxPendingConnections=95, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L6 {vus=1000, pool=5, requests=1000, throughput=307, avgMs=1290, p50Ms=1329, p95Ms=2152, p99Ms=2219, p95Ms201=188, p95Ms409=2152, status={201=10, 409:SEAT_ALREADY_HELD=990}, maxPendingConnections=195, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L2 {vus=200, pool=5, requests=12242, throughput=390, avgMs=502, p50Ms=482, p95Ms=695, p99Ms=996, p95Ms201=768, p95Ms409=694, status={201=936, 409:SEAT_ALREADY_HELD=11306}, maxPendingConnections=196, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=50, pool=5, requests=6790, throughput=321, avgMs=151, p50Ms=140, p95Ms=217, p99Ms=311, p95Ms201=218, p95Ms409=208, status={201=4508, 409:SEAT_ALREADY_HELD=2282}, maxPendingConnections=45, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=100, pool=5, requests=5965, throughput=280, avgMs=347, p50Ms=330, p95Ms=537, p99Ms=918, p95Ms201=521, p95Ms409=590, status={201=4196, 409:SEAT_ALREADY_HELD=1769}, maxPendingConnections=95, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=200, pool=5, requests=6170, throughput=286, avgMs=673, p50Ms=677, p95Ms=920, p99Ms=959, p95Ms201=927, p95Ms409=859, status={201=4220, 409:SEAT_ALREADY_HELD=1950}, maxPendingConnections=196, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=400, pool=5, requests=7255, throughput=327, avgMs=1162, p50Ms=1175, p95Ms=1561, p99Ms=1610, p95Ms201=1590, p95Ms409=1439, status={201=4730, 409:SEAT_ALREADY_HELD=2525}, maxPendingConnections=196, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=800, pool=5, requests=6437, throughput=273, avgMs=2714, p50Ms=3001, p95Ms=3012, p99Ms=3014, p95Ms201=2961, p95Ms409=2965, status={201=2121, 409:SEAT_ALREADY_HELD=572, TIMEOUT=3744}, maxPendingConnections=195, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
```

### s4-pool20

```text
[SEATHOLD-LOAD] L1 {vus=100, pool=20, requests=1000, throughput=230, avgMs=329, p50Ms=298, p95Ms=755, p99Ms=1071, p95Ms201=760, p95Ms409=524, status={201=936, 409:SEAT_ALREADY_HELD=64}, maxPendingConnections=79, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L6 {vus=1000, pool=20, requests=1000, throughput=374, avgMs=980, p50Ms=867, p95Ms=1641, p99Ms=1791, p95Ms201=177, p95Ms409=1642, status={201=10, 409:SEAT_ALREADY_HELD=990}, maxPendingConnections=180, maxLockWaiters=6, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L2 {vus=200, pool=20, requests=24794, throughput=795, avgMs=247, p50Ms=207, p95Ms=529, p99Ms=747, p95Ms201=700, p95Ms409=516, status={201=995, 409:SEAT_ALREADY_HELD=23799}, maxPendingConnections=180, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=50, pool=20, requests=16047, throughput=763, avgMs=64, p50Ms=61, p95Ms=99, p99Ms=129, p95Ms201=105, p95Ms409=90, status={201=7347, 409:SEAT_ALREADY_HELD=8700}, maxPendingConnections=30, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=100, pool=20, requests=16197, throughput=768, avgMs=127, p50Ms=124, p95Ms=188, p99Ms=255, p95Ms201=192, p95Ms409=185, status={201=7372, 409:SEAT_ALREADY_HELD=8825}, maxPendingConnections=81, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=200, pool=20, requests=17008, throughput=803, avgMs=242, p50Ms=240, p95Ms=371, p99Ms=498, p95Ms201=386, p95Ms409=349, status={201=7574, 409:SEAT_ALREADY_HELD=9434}, maxPendingConnections=181, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=400, pool=20, requests=17577, throughput=821, avgMs=471, p50Ms=463, p95Ms=678, p99Ms=795, p95Ms201=696, p95Ms409=661, status={201=7648, 409:SEAT_ALREADY_HELD=9929}, maxPendingConnections=181, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=800, pool=20, requests=16060, throughput=728, avgMs=1051, p50Ms=1068, p95Ms=1393, p99Ms=1497, p95Ms201=1389, p95Ms409=1397, status={201=7375, 409:SEAT_ALREADY_HELD=8685}, maxPendingConnections=180, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
```

### s1-pool20

```text
[SEATHOLD-LOAD] L6 {vus=1000, pool=20, requests=1000, throughput=483, avgMs=696, p50Ms=649, p95Ms=1079, p99Ms=1192, p95Ms201=615, p95Ms409=1079, status={201=10, 409:SEAT_ALREADY_HELD=990}, maxPendingConnections=155, maxLockWaiters=2, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=50, pool=20, requests=7350, throughput=349, avgMs=140, p50Ms=126, p95Ms=261, p99Ms=419, p95Ms201=282, p95Ms409=210, status={201=4792, 409:SEAT_ALREADY_HELD=2558}, maxPendingConnections=30, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=100, pool=20, requests=14789, throughput=701, avgMs=139, p50Ms=128, p95Ms=249, p99Ms=310, p95Ms201=257, p95Ms409=238, status={201=7102, 409:SEAT_ALREADY_HELD=7687}, maxPendingConnections=80, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=200, pool=20, requests=17071, throughput=807, avgMs=241, p50Ms=230, p95Ms=435, p99Ms=543, p95Ms201=457, p95Ms409=412, status={201=7570, 409:SEAT_ALREADY_HELD=9501}, maxPendingConnections=181, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=400, pool=20, requests=17036, throughput=786, avgMs=488, p50Ms=487, p95Ms=800, p99Ms=973, p95Ms201=821, p95Ms409=776, status={201=7570, 409:SEAT_ALREADY_HELD=9466}, maxPendingConnections=181, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
[SEATHOLD-LOAD] L3 {vus=800, pool=20, requests=14936, throughput=677, avgMs=1124, p50Ms=1122, p95Ms=1522, p99Ms=1727, p95Ms201=1525, p95Ms409=1518, status={201=7104, 409:SEAT_ALREADY_HELD=7832}, maxPendingConnections=181, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
```

## 3. 부하 반복 측정 (풀 20, L3 VU 100·400 각 15초, L6)

회차마다 단계 순서를 바꿨다. 1회 S0→S4, 2회 S4→S0, 3회 S2·S0·S4·S1·S3.

```text
rep=1 stage=s0 [SEATHOLD-LOAD] L6 {vus=1000, pool=20, requests=1000, throughput=352, avgMs=838, p50Ms=768, p95Ms=1440, p99Ms=1610, p95Ms201=792, p95Ms409=1441, status={201=10, 409:SEAT_ALREADY_HELD=990}, maxPendingConnections=168, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=1 stage=s0 [SEATHOLD-LOAD] L3 {vus=100, pool=20, requests=4485, throughput=277, avgMs=348, p50Ms=330, p95Ms=680, p99Ms=911, p95Ms201=715, p95Ms409=575, status={201=3346, 409:SEAT_ALREADY_HELD=1139}, maxPendingConnections=80, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=1 stage=s0 [SEATHOLD-LOAD] L3 {vus=400, pool=20, requests=8498, throughput=515, avgMs=741, p50Ms=664, p95Ms=1362, p99Ms=1708, p95Ms201=1409, p95Ms409=1185, status={201=5231, 409:SEAT_ALREADY_HELD=3267}, maxPendingConnections=180, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=1 stage=s1 [SEATHOLD-LOAD] L6 {vus=1000, pool=20, requests=1000, throughput=531, avgMs=626, p50Ms=698, p95Ms=950, p99Ms=1012, p95Ms201=139, p95Ms409=951, status={201=10, 409:SEAT_ALREADY_HELD=990}, maxPendingConnections=180, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=1 stage=s1 [SEATHOLD-LOAD] L3 {vus=100, pool=20, requests=5889, throughput=365, avgMs=264, p50Ms=250, p95Ms=455, p99Ms=655, p95Ms201=467, p95Ms409=430, status={201=4130, 409:SEAT_ALREADY_HELD=1759}, maxPendingConnections=81, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
rep=1 stage=s1 [SEATHOLD-LOAD] L3 {vus=400, pool=20, requests=9658, throughput=592, avgMs=649, p50Ms=569, p95Ms=1173, p99Ms=1623, p95Ms201=1196, p95Ms409=1017, status={201=5627, 409:SEAT_ALREADY_HELD=4031}, maxPendingConnections=180, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=1 stage=s2 [SEATHOLD-LOAD] L6 {vus=1000, pool=20, requests=1000, throughput=414, avgMs=957, p50Ms=999, p95Ms=1367, p99Ms=1483, p95Ms201=265, p95Ms409=1368, status={201=10, 409:SEAT_ALREADY_HELD=990}, maxPendingConnections=180, maxLockWaiters=4, doubleSold=0, seatMismatches=0}
rep=1 stage=s2 [SEATHOLD-LOAD] L3 {vus=100, pool=20, requests=6908, throughput=429, avgMs=225, p50Ms=213, p95Ms=378, p99Ms=520, p95Ms201=393, p95Ms409=328, status={201=4623, 409:SEAT_ALREADY_HELD=2285}, maxPendingConnections=81, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=1 stage=s2 [SEATHOLD-LOAD] L3 {vus=400, pool=20, requests=11979, throughput=722, avgMs=526, p50Ms=507, p95Ms=816, p99Ms=985, p95Ms201=845, p95Ms409=772, status={201=6474, 409:SEAT_ALREADY_HELD=5505}, maxPendingConnections=180, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=1 stage=s3 [SEATHOLD-LOAD] L6 {vus=1000, pool=20, requests=1000, throughput=415, avgMs=805, p50Ms=824, p95Ms=1146, p99Ms=1273, p95Ms201=145, p95Ms409=1147, status={201=10, 409:SEAT_ALREADY_HELD=990}, maxPendingConnections=179, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=1 stage=s3 [SEATHOLD-LOAD] L3 {vus=100, pool=20, requests=4828, throughput=298, avgMs=323, p50Ms=313, p95Ms=543, p99Ms=750, p95Ms201=551, p95Ms409=516, status={201=3578, 409:SEAT_ALREADY_HELD=1250}, maxPendingConnections=81, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
rep=1 stage=s3 [SEATHOLD-LOAD] L3 {vus=400, pool=20, requests=10995, throughput=669, avgMs=572, p50Ms=549, p95Ms=961, p99Ms=1208, p95Ms201=984, p95Ms409=827, status={201=6075, 409:SEAT_ALREADY_HELD=4920}, maxPendingConnections=180, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
rep=1 stage=s4 [SEATHOLD-LOAD] L6 {vus=1000, pool=20, requests=1000, throughput=571, avgMs=410, p50Ms=370, p95Ms=772, p99Ms=839, p95Ms201=245, p95Ms409=773, status={201=10, 409:SEAT_ALREADY_HELD=990}, maxPendingConnections=174, maxLockWaiters=2, doubleSold=0, seatMismatches=0}
rep=1 stage=s4 [SEATHOLD-LOAD] L3 {vus=100, pool=20, requests=5597, throughput=346, avgMs=278, p50Ms=252, p95Ms=514, p99Ms=703, p95Ms201=518, p95Ms409=493, status={201=4013, 409:SEAT_ALREADY_HELD=1584}, maxPendingConnections=80, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
rep=1 stage=s4 [SEATHOLD-LOAD] L3 {vus=400, pool=20, requests=10009, throughput=606, avgMs=629, p50Ms=561, p95Ms=1095, p99Ms=1345, p95Ms201=1185, p95Ms409=915, status={201=5769, 409:SEAT_ALREADY_HELD=4240}, maxPendingConnections=180, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=2 stage=s4 [SEATHOLD-LOAD] L6 {vus=1000, pool=20, requests=1000, throughput=509, avgMs=495, p50Ms=473, p95Ms=741, p99Ms=924, p95Ms201=308, p95Ms409=741, status={201=10, 409:SEAT_ALREADY_HELD=990}, maxPendingConnections=180, maxLockWaiters=3, doubleSold=0, seatMismatches=0}
rep=2 stage=s4 [SEATHOLD-LOAD] L3 {vus=100, pool=20, requests=5780, throughput=356, avgMs=270, p50Ms=249, p95Ms=557, p99Ms=713, p95Ms201=580, p95Ms409=461, status={201=4047, 409:SEAT_ALREADY_HELD=1733}, maxPendingConnections=80, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
rep=2 stage=s4 [SEATHOLD-LOAD] L3 {vus=400, pool=20, requests=10707, throughput=648, avgMs=589, p50Ms=579, p95Ms=848, p99Ms=1051, p95Ms201=880, p95Ms409=797, status={201=5966, 409:SEAT_ALREADY_HELD=4741}, maxPendingConnections=180, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
rep=2 stage=s3 [SEATHOLD-LOAD] L6 {vus=1000, pool=20, requests=1000, throughput=408, avgMs=610, p50Ms=581, p95Ms=1202, p99Ms=1416, p95Ms201=195, p95Ms409=1206, status={201=10, 409:SEAT_ALREADY_HELD=990}, maxPendingConnections=180, maxLockWaiters=2, doubleSold=0, seatMismatches=0}
rep=2 stage=s3 [SEATHOLD-LOAD] L3 {vus=100, pool=20, requests=7099, throughput=440, avgMs=219, p50Ms=205, p95Ms=371, p99Ms=503, p95Ms201=376, p95Ms409=360, status={201=4633, 409:SEAT_ALREADY_HELD=2466}, maxPendingConnections=81, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
rep=2 stage=s3 [SEATHOLD-LOAD] L3 {vus=400, pool=20, requests=13225, throughput=802, avgMs=476, p50Ms=459, p95Ms=718, p99Ms=855, p95Ms201=763, p95Ms409=639, status={201=6732, 409:SEAT_ALREADY_HELD=6493}, maxPendingConnections=180, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=2 stage=s2 [SEATHOLD-LOAD] L6 {vus=1000, pool=20, requests=1000, throughput=533, avgMs=528, p50Ms=542, p95Ms=824, p99Ms=921, p95Ms201=186, p95Ms409=824, status={201=10, 409:SEAT_ALREADY_HELD=990}, maxPendingConnections=179, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=2 stage=s2 [SEATHOLD-LOAD] L3 {vus=100, pool=20, requests=7788, throughput=482, avgMs=200, p50Ms=187, p95Ms=350, p99Ms=467, p95Ms201=360, p95Ms409=314, status={201=4940, 409:SEAT_ALREADY_HELD=2848}, maxPendingConnections=80, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
rep=2 stage=s2 [SEATHOLD-LOAD] L3 {vus=400, pool=20, requests=12378, throughput=759, avgMs=506, p50Ms=460, p95Ms=886, p99Ms=1095, p95Ms201=916, p95Ms409=802, status={201=6479, 409:SEAT_ALREADY_HELD=5899}, maxPendingConnections=180, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=2 stage=s1 [SEATHOLD-LOAD] L6 {vus=1000, pool=20, requests=1000, throughput=914, avgMs=64, p50Ms=65, p95Ms=99, p99Ms=113, p95Ms201=91, p95Ms409=99, status={201=10, 409:SEAT_ALREADY_HELD=990}, maxPendingConnections=75, maxLockWaiters=4, doubleSold=0, seatMismatches=0}
rep=2 stage=s1 [SEATHOLD-LOAD] L3 {vus=100, pool=20, requests=11651, throughput=725, avgMs=133, p50Ms=128, p95Ms=229, p99Ms=307, p95Ms201=242, p95Ms409=209, status={201=6346, 409:SEAT_ALREADY_HELD=5305}, maxPendingConnections=80, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=2 stage=s1 [SEATHOLD-LOAD] L3 {vus=400, pool=20, requests=20729, throughput=1276, avgMs=301, p50Ms=284, p95Ms=484, p99Ms=571, p95Ms201=541, p95Ms409=387, status={201=8277, 409:SEAT_ALREADY_HELD=12452}, maxPendingConnections=181, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=2 stage=s0 [SEATHOLD-LOAD] L6 {vus=1000, pool=20, requests=1000, throughput=945, avgMs=97, p50Ms=97, p95Ms=176, p99Ms=198, p95Ms201=84, p95Ms409=176, status={201=10, 409:SEAT_ALREADY_HELD=990}, maxPendingConnections=107, maxLockWaiters=3, doubleSold=0, seatMismatches=0}
rep=2 stage=s0 [SEATHOLD-LOAD] L3 {vus=100, pool=20, requests=12294, throughput=764, avgMs=126, p50Ms=123, p95Ms=227, p99Ms=279, p95Ms201=244, p95Ms409=208, status={201=6456, 409:SEAT_ALREADY_HELD=5838}, maxPendingConnections=80, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=2 stage=s0 [SEATHOLD-LOAD] L3 {vus=400, pool=20, requests=18823, throughput=1159, avgMs=332, p50Ms=303, p95Ms=535, p99Ms=593, p95Ms201=562, p95Ms409=460, status={201=7817, 409:SEAT_ALREADY_HELD=11006}, maxPendingConnections=181, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=3 stage=s2 [SEATHOLD-LOAD] L6 {vus=1000, pool=20, requests=1000, throughput=790, avgMs=213, p50Ms=203, p95Ms=352, p99Ms=456, p95Ms201=191, p95Ms409=353, status={201=10, 409:SEAT_ALREADY_HELD=990}, maxPendingConnections=156, maxLockWaiters=6, doubleSold=0, seatMismatches=0}
rep=3 stage=s2 [SEATHOLD-LOAD] L3 {vus=100, pool=20, requests=10157, throughput=631, avgMs=153, p50Ms=151, p95Ms=251, p99Ms=317, p95Ms201=262, p95Ms409=231, status={201=5834, 409:SEAT_ALREADY_HELD=4323}, maxPendingConnections=80, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=3 stage=s2 [SEATHOLD-LOAD] L3 {vus=400, pool=20, requests=18541, throughput=1142, avgMs=337, p50Ms=319, p95Ms=524, p99Ms=607, p95Ms201=576, p95Ms409=471, status={201=7820, 409:SEAT_ALREADY_HELD=10721}, maxPendingConnections=180, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=3 stage=s0 [SEATHOLD-LOAD] L6 {vus=1000, pool=20, requests=1000, throughput=612, avgMs=399, p50Ms=399, p95Ms=660, p99Ms=744, p95Ms201=134, p95Ms409=661, status={201=10, 409:SEAT_ALREADY_HELD=990}, maxPendingConnections=179, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=3 stage=s0 [SEATHOLD-LOAD] L3 {vus=100, pool=20, requests=9793, throughput=607, avgMs=159, p50Ms=145, p95Ms=286, p99Ms=395, p95Ms201=308, p95Ms409=249, status={201=5754, 409:SEAT_ALREADY_HELD=4039}, maxPendingConnections=80, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=3 stage=s0 [SEATHOLD-LOAD] L3 {vus=400, pool=20, requests=18267, throughput=1125, avgMs=342, p50Ms=301, p95Ms=615, p99Ms=833, p95Ms201=721, p95Ms409=492, status={201=7772, 409:SEAT_ALREADY_HELD=10495}, maxPendingConnections=180, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=3 stage=s4 [SEATHOLD-LOAD] L6 {vus=1000, pool=20, requests=1000, throughput=694, avgMs=267, p50Ms=259, p95Ms=451, p99Ms=572, p95Ms201=82, p95Ms409=451, status={201=10, 409:SEAT_ALREADY_HELD=990}, maxPendingConnections=179, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
rep=3 stage=s4 [SEATHOLD-LOAD] L3 {vus=100, pool=20, requests=8970, throughput=557, avgMs=173, p50Ms=170, p95Ms=280, p99Ms=339, p95Ms201=287, p95Ms409=261, status={201=5422, 409:SEAT_ALREADY_HELD=3548}, maxPendingConnections=80, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
rep=3 stage=s4 [SEATHOLD-LOAD] L3 {vus=400, pool=20, requests=15444, throughput=942, avgMs=406, p50Ms=387, p95Ms=635, p99Ms=764, p95Ms201=688, p95Ms409=564, status={201=7197, 409:SEAT_ALREADY_HELD=8247}, maxPendingConnections=180, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=3 stage=s1 [SEATHOLD-LOAD] L6 {vus=1000, pool=20, requests=1000, throughput=703, avgMs=360, p50Ms=330, p95Ms=659, p99Ms=745, p95Ms201=165, p95Ms409=659, status={201=10, 409:SEAT_ALREADY_HELD=990}, maxPendingConnections=180, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=3 stage=s1 [SEATHOLD-LOAD] L3 {vus=100, pool=20, requests=10436, throughput=648, avgMs=149, p50Ms=144, p95Ms=245, p99Ms=337, p95Ms201=253, p95Ms409=227, status={201=5941, 409:SEAT_ALREADY_HELD=4495}, maxPendingConnections=80, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=3 stage=s1 [SEATHOLD-LOAD] L3 {vus=400, pool=20, requests=14673, throughput=892, avgMs=428, p50Ms=415, p95Ms=655, p99Ms=923, p95Ms201=651, p95Ms409=666, status={201=7021, 409:SEAT_ALREADY_HELD=7652}, maxPendingConnections=180, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=3 stage=s3 [SEATHOLD-LOAD] L6 {vus=1000, pool=20, requests=1000, throughput=523, avgMs=495, p50Ms=470, p95Ms=850, p99Ms=937, p95Ms201=131, p95Ms409=851, status={201=10, 409:SEAT_ALREADY_HELD=990}, maxPendingConnections=179, maxLockWaiters=3, doubleSold=0, seatMismatches=0}
rep=3 stage=s3 [SEATHOLD-LOAD] L3 {vus=100, pool=20, requests=6677, throughput=411, avgMs=234, p50Ms=214, p95Ms=400, p99Ms=544, p95Ms201=413, p95Ms409=379, status={201=4460, 409:SEAT_ALREADY_HELD=2217}, maxPendingConnections=80, maxLockWaiters=1, doubleSold=0, seatMismatches=0}
rep=3 stage=s3 [SEATHOLD-LOAD] L3 {vus=400, pool=20, requests=10130, throughput=617, avgMs=620, p50Ms=526, p95Ms=1204, p99Ms=1528, p95Ms201=1375, p95Ms409=982, status={201=5725, 409:SEAT_ALREADY_HELD=4405}, maxPendingConnections=180, maxLockWaiters=0, doubleSold=0, seatMismatches=0}
```

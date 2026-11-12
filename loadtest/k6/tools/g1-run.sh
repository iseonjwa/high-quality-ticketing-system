#!/usr/bin/env bash
# G1 배포 서버 회차 실행(발생기에서). 웜업 3단계 → 차단기 닫힘 대기 → 본 측정(단계 사이 0/s 쉼)을 한 번에 돈다.
# 배포 서버 6·7차(open-peak-scenarios.md)의 g6·g7-run.sh를 저장소로 옮긴 것이다. R0(거절 비용)도 이것으로 돈다.
#
# 사용(발생기 저장소 루트에서, .env에 측정 전용 키를 옮긴 뒤):
#   bash loadtest/k6/tools/g1-run.sh <회차 이름> <SCHEDULE_ID> [본 측정 STAGES]
#   예) PRE_VUS=3000 MAX_VUS=6000 bash loadtest/k6/tools/g1-run.sh g8-L64-1 96
#       USERS=1 PRE_VUS=3000 MAX_VUS=6000 bash loadtest/k6/tools/g1-run.sh r0-A 96 250,0,500,0,1000,0,500,0,1000
# 환경변수: USERS(기본 10000), PRE_VUS(기본 500), MAX_VUS(기본 3000), OUT(기본 저장소의 부모 폴더 = ~/<영문 이름>), PROM(기본 배포 서버 사설 9090)
# 결과: $OUT/<회차>-run.log(단계 시각·차단기·상한), <회차>-<단계>.txt(k6 출력), <회차>-gen-vmstat.log,
#       $OUT/gen-<회차>.tar.gz(위 파일과 이번 실행의 k6 원자료 loadtest/k6/results/g1-gateway-auth-*)
# 본 측정은 단계마다 상승 5초 + 30초로 고정이다. r0_solve.py와 6차 g6_stages.py가 35초 단위로 단계를 나눈다.
#
# Note: 상한(iseonjwa.gateway.in-flight.max-requests)이 1이면 setup의 대기자 진입(200건씩 동시에 보냄)이 상한에 걸려
# "대기자 준비 실패"로 멈춘다. 그때는 USERS=1로 한 명만 진입시킨다. 짝 회차도 같은 USERS로 재야 받은 요청 조건이 같다.
# 상한 1 회차는 거절이 대부분이라 k6 임계가 실패해 exit가 0이 아니다. 예상된 것이다.
set -uo pipefail

R=${1:?회차 이름이 없다}
SID=${2:?SCHEDULE_ID가 없다}
MEASURE_STAGES=${3:-1000,0,1500,0,2000,0,2500}
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
OUT=${OUT:-$(dirname "$ROOT")}
PROM=${PROM:-http://172.31.4.93:9090}
cd "$ROOT"
export BASE_URL=http://172.31.4.93:8080 MOCK_PG_URL= PROM_RW_URL=$PROM/api/v1/write SCHEDULE_ID=$SID \
  USERS=${USERS:-10000} PRE_VUS=${PRE_VUS:-500} MAX_VUS=${MAX_VUS:-3000}

[[ -e "$OUT/$R-run.log" ]] && { echo "이미 있다: $OUT/$R-run.log. 다른 회차 이름을 쓴다" >&2; exit 1; }
stamp=$OUT/.$R.start; touch "$stamp"
log=$OUT/$R-run.log; : > "$log"
timeout 1200 vmstat -t -n 1 > "$OUT/$R-gen-vmstat.log" &

prom() { curl -s -m 5 "$PROM/api/v1/query" --data-urlencode "query=$1" | python3 -c "import sys,json;print([r[\"$2\"][$3] for r in json.load(sys.stdin)[\"data\"][\"result\"]])" 2> /dev/null || echo "조회실패"; }
breaker() { prom 'resilience4j_circuitbreaker_state{name="auth-redis"} == 1' metric '"state"'; }
limit() { prom 'gateway_in_flight_limit' value 1; }
# Note: g6·g7-run.sh는 fi 뒤의 $?를 찍어 exit가 늘 0이었다. 여기서는 k6 종료 코드를 rc에 받는다
step() {
  local name=$1 stages=$2 secs=$3 out=$OUT/$R-$1.txt rc
  echo "== $(date -u +%H:%M:%S) $name STAGES=$stages STAGE_SECONDS=$secs" | tee -a "$log"
  STAGES=$stages STAGE_SECONDS=$secs bash loadtest/k6/run.sh scenarios/g1-gateway-auth.js > "$out" 2>&1; rc=$?
  if grep -q "준비 실패" "$out"; then
    echo "   $(date -u +%H:%M:%S) 준비 실패, 한 번 다시" | tee -a "$log"
    mv "$out" "$OUT/$R-$name-fail1.txt"; sleep 5
    STAGES=$stages STAGE_SECONDS=$secs bash loadtest/k6/run.sh scenarios/g1-gateway-auth.js > "$out" 2>&1; rc=$?
  fi
  echo "   $(date -u +%H:%M:%S) 끝 exit=$rc breaker=$(breaker)" | tee -a "$log"
}

echo "== $(date -u +%H:%M:%S) 회차 $R SCHEDULE_ID=$SID USERS=$USERS PRE_VUS=$PRE_VUS MAX_VUS=$MAX_VUS 커밋 $(git log --oneline -1)" | tee -a "$log"
echo "   상한(gateway_in_flight_limit)=$(limit)" | tee -a "$log"
step warm1 100 120
step warm2 1000 30
step warm3 3000 10
for _ in $(seq 1 12); do
  b=$(breaker); echo "   $(date -u +%H:%M:%S) 대기 breaker=$b" >> "$log"
  [[ "$b" == "['closed']" ]] && break; sleep 5
done
step measure "$MEASURE_STAGES" 30
grep -h "results/g1" "$OUT/$R"-*.txt | tail -4 >> "$log"
echo "== $(date -u +%H:%M:%S) 전체 끝" | tee -a "$log"

mapfile -t raw < <(find loadtest/k6/results -newer "$stamp" -type f -name 'g1-gateway-auth-*')
tar czf "$OUT/gen-$R.tar.gz" -C "$OUT" $(cd "$OUT" && ls "$R"-*) -C "$ROOT" "${raw[@]}" \
  && echo "보존: $OUT/gen-$R.tar.gz (k6 원자료 ${#raw[@]}개)" | tee -a "$log"
rm -f "$stamp"

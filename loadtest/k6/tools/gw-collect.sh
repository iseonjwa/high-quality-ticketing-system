#!/usr/bin/env bash
# 배포 서버 Gateway 측정 수집기. 배포 서버 6·7차에 /tmp에 매번 다시 쓰던 수집기를 같은 항목으로 옮기고 Java PID 찾기를 고쳤다.
#
# 사용(배포 서버 ~/iseonjwa에서, 측정 모드를 켜고 Gateway가 healthy가 된 뒤). pid 말고는 MEASURER(영문 이름)가 필요하다
#   예) MEASURER=kangmin bash loadtest/k6/tools/gw-collect.sh start r0-A 1200
#   gw-collect.sh pid                          Gateway의 init PID와 Java PID를 찾아 출력만 한다(두 방법을 맞춰 본다)
#   gw-collect.sh start <회차> [초=1200]        수집을 백그라운드로 시작한다. 결과: $OUT_ROOT/<MEASURER>-<회차>/
#   gw-collect.sh check <회차>                  pidstat.log가 Java 스레드를 잡았는지, 로그가 쌓이는지 본다. start 직후 한 번 돌린다
#   gw-collect.sh stop <회차>                   수집을 멈춘다(초가 지나면 저절로도 멈춘다)
#   gw-collect.sh archive <회차> <시작 epoch초> <끝 epoch초>
#                                              Prometheus range query 17종·Zipkin 요약을 archive/에 뽑고
#                                              $OUT_ROOT/server-<회차>.tar.gz로 묶는다
#
# 수집 항목(6·7차와 같음): vmstat 1초, mpstat -P ALL 1초, Gateway pidstat -t 1초(스레드별), docker stats 약 3초,
# load average 1초, meta.log. 덧붙인 것: pid-watch.log(Gateway Java PID가 바뀌면 남김), archive/queries.txt(PromQL 원문).
# HOST_PIDSTAT=1이면 호스트 전체 프로세스 pidstat(host-pidstat.log)도 모은다. 기본은 끔(6·7차에 없던 항목).
#
# Note: compose.yml의 x-app이 init: true라 docker inspect의 State.Pid는 컨테이너 1번 프로세스 docker-init(tini)이다.
# Java는 그 자식이다. 6·7차 수집기는 State.Pid를 그대로 pidstat -p에 넣어 docker-init만 잡았다. pidstat -t -p는 그 PID의
# 스레드만 보고 자식 프로세스는 보지 않는다. 그래서 State.Pid의 자식 중 comm이 java인 것을 쓰고, docker top으로 맞춰 본다.
set -euo pipefail

CONTAINER=${CONTAINER:-iseonjwa-gateway-1}
OUT_ROOT=${OUT_ROOT:-/tmp}
PROM=${PROM:-http://localhost:9090}
ZIPKIN=${ZIPKIN:-http://localhost:9411}
STEP=${STEP:-5}
export TZ=UTC LC_ALL=C

fail() { echo "gw-collect: $*" >&2; exit 1; }
warn() { echo "gw-collect: 주의: $*" >&2; }
need() { command -v "$1" > /dev/null || fail "$1이 없다"; }
run_dir() {
  [[ -n "${MEASURER:-}" ]] || fail "MEASURER(영문 이름)가 없다. 결과 폴더 이름과 기록에 쓴다"
  [[ -n "${1:-}" ]] || fail "회차 이름이 없다"
  echo "$OUT_ROOT/$MEASURER-$1"
}

# 출력: "<init PID> <Java PID>". 못 찾으면 실패한다
find_java_pid() {
  local init children java count
  init=$(docker inspect -f '{{.State.Pid}}' "$CONTAINER" 2> /dev/null) || fail "컨테이너가 없다: $CONTAINER"
  [[ "$init" =~ ^[1-9][0-9]*$ ]] || fail "컨테이너가 돌고 있지 않다: $CONTAINER(State.Pid=$init)"
  # init: true가 빠진 컨테이너면 State.Pid가 곧 Java다
  if [[ "$(cat "/proc/$init/comm")" == java ]]; then
    echo "$init $init"
    return
  fi
  children=$(ps -o pid=,comm= --ppid "$init" || true)
  java=$(awk '$2 == "java" { print $1 }' <<< "$children")
  count=$(wc -w <<< "$java")
  if [[ "$count" -ne 1 ]]; then
    echo "$children" >&2
    fail "init $init($(cat "/proc/$init/comm"))의 자식 중 java가 ${count}개다. 하나여야 한다"
  fi
  echo "$init $java"
}

# docker top(호스트 PID 기준)으로 찾은 java가 find_java_pid와 같은지 본다
cross_check() {
  local java=$1 top
  top=$(docker top "$CONTAINER" -eo pid,comm | awk 'NR > 1 && $2 == "java" { print $1 }')
  [[ "$top" == "$java" ]] || fail "docker top의 java($top)와 자식 프로세스로 찾은 java($java)가 다르다"
  local cid
  cid=$(docker inspect -f '{{.Id}}' "$CONTAINER")
  grep -q "$cid" "/proc/$java/cgroup" || warn "/proc/$java/cgroup에 컨테이너 ID가 없다(cgroup 드라이버 차이일 수 있음)"
}

cmd_pid() {
  local init java
  local pids
  pids=$(find_java_pid) || exit 1
  read -r init java <<< "$pids"
  cross_check "$java"
  echo "container=$CONTAINER"
  echo "init_pid=$init comm=$(cat "/proc/$init/comm")"
  echo "java_pid=$java comm=$(cat "/proc/$java/comm") threads=$(ls "/proc/$java/task" | wc -l)"
}

cmd_start() {
  local run=$1 secs=${2:-1200} dir init java
  dir=$(run_dir "$run")
  [[ -e "$dir" ]] && fail "이미 있다: $dir. 다른 회차 이름을 쓰거나 보존한 뒤 지운다"
  for c in docker vmstat mpstat pidstat timeout; do need "$c"; done
  local pids
  pids=$(find_java_pid) || exit 1
  read -r init java <<< "$pids"
  cross_check "$java"
  mkdir -p "$dir/archive"
  {
    date -u +%Y-%m-%dT%H:%M:%SZ
    docker inspect -f '{{.Config.Image}}' "$CONTAINER" | sed 's/.*://'
    nproc
    echo "gateway_pid=$java"
    echo "gateway_init_pid=$init"
    echo "gateway_started_at=$(docker inspect -f '{{.State.StartedAt}}' "$CONTAINER")"
    echo "gateway_threads_at_start=$(ls "/proc/$java/task" | wc -l)"
  } > "$dir/meta.log"

  cd "$dir"
  : > .pids
  nohup timeout "$secs" vmstat -t -n 1 > vmstat.log 2>&1 & echo $! >> .pids
  nohup timeout "$secs" mpstat -P ALL 1 > mpstat.log 2>&1 & echo $! >> .pids
  nohup timeout "$secs" pidstat -t -u -p "$java" 1 > pidstat.log 2>&1 & echo $! >> .pids
  nohup timeout "$secs" bash -c 'while :; do echo "$(date -u +%H:%M:%S) $(cat /proc/loadavg)"; sleep 1; done' \
    > loadavg.log 2>&1 & echo $! >> .pids
  nohup timeout "$secs" bash -c 'while :; do date -u +%H:%M:%S
      docker stats --no-stream --format "{{.Name}} {{.CPUPerc}} {{.MemUsage}}"; sleep 1; done' \
    > dstats.log 2>&1 & echo $! >> .pids
  # Gateway가 다시 만들어지면 pidstat은 옛 PID를 보다 끝난다. 그 시각을 남겨 그 뒤 구간을 무효로 가릴 수 있게 한다
  nohup timeout "$secs" bash -c 'want='"$java"'; while :; do
      now=$(docker inspect -f "{{.State.Pid}}" '"$CONTAINER"' 2>/dev/null || echo none)
      if [[ ! -d /proc/$want ]]; then echo "$(date -u +%H:%M:%S) java $want 사라짐(init 지금 $now)"; exit 0; fi
      sleep 5; done' > pid-watch.log 2>&1 & echo $! >> .pids
  if [[ "${HOST_PIDSTAT:-0}" == 1 ]]; then
    nohup timeout "$secs" pidstat -u 1 > host-pidstat.log 2>&1 & echo $! >> .pids
  fi
  echo "수집 시작: $dir (Java PID $java, init $init, ${secs}초). 5초 뒤 check를 돌린다"
}

cmd_check() {
  local dir bad=0 f
  dir=$(run_dir "$1")
  [[ -d "$dir" ]] || fail "없다: $dir"
  cd "$dir"
  for f in vmstat.log mpstat.log pidstat.log loadavg.log dstats.log; do
    if [[ ! -s "$f" ]]; then echo "비었음: $f"; bad=1; fi
  done
  if grep -q 'docker-init' pidstat.log; then
    echo "pidstat.log가 docker-init을 잡았다(6·7차와 같은 오류)"; bad=1
  fi
  if ! grep -qE '[[:space:]]java$' pidstat.log; then
    echo "pidstat.log에 java 프로세스 줄이 아직 없다"; bad=1
  fi
  # 스레드 이름은 커널 comm이라 15자에서 잘린다(reactor-http-epoll-1 → reactor-http-ep)
  local http lettuce
  http=$(awk '$NF ~ /^\|__reactor-http/ { print $4 }' pidstat.log | sort -u | wc -l)
  lettuce=$(awk '$NF ~ /^\|__lettuce-epoll/ { print $4 }' pidstat.log | sort -u | wc -l)
  echo "pidstat 스레드: reactor-http ${http}개, lettuce-epoll ${lettuce}개"
  [[ "$http" -ge 1 ]] || { echo "reactor-http 스레드가 없다"; bad=1; }
  [[ -s pid-watch.log ]] && { echo "pid-watch: $(cat pid-watch.log)"; bad=1; }
  [[ "$bad" == 0 ]] && echo "check 통과: $dir" || fail "check 실패: $dir"
}

cmd_stop() {
  local dir
  dir=$(run_dir "$1")
  [[ -f "$dir/.pids" ]] || fail "수집 기록이 없다: $dir/.pids"
  while read -r p; do kill "$p" 2> /dev/null || true; done < "$dir/.pids"
  echo "수집 멈춤: $dir"
}

# 이름<TAB>PromQL. 이름은 6·7차 archive/prom-<이름>.json과 같다. 식은 6·7차 원문이 남지 않아 다시 썼고 queries.txt에 남긴다
queries() {
  local g='application="gateway"' w='[15s]'
  cat << EOF
process_cpu	process_cpu_usage{$g}
system_cpu	system_cpu_usage{$g}
jvm_heap_used	sum(jvm_memory_used_bytes{$g,area="heap"})
jvm_nonheap_used	sum(jvm_memory_used_bytes{$g,area="nonheap"})
gc_pause	sum(rate(jvm_gc_pause_seconds_sum{$g}$w))
http_server_count	sum by (status) (increase(http_server_requests_seconds_count{$g}$w))
http_server_avg	sum by (status) (rate(http_server_requests_seconds_sum{$g}$w)) / sum by (status) (rate(http_server_requests_seconds_count{$g}$w))
gw_route_avg	sum by (routeId) (rate(spring_cloud_gateway_requests_seconds_sum{$g}$w)) / sum by (routeId) (rate(spring_cloud_gateway_requests_seconds_count{$g}$w))
lettuce_count	sum(rate(lettuce_command_completion_seconds_count{$g}$w))
lettuce_avg	sum(rate(lettuce_command_completion_seconds_sum{$g}$w)) / sum(rate(lettuce_command_completion_seconds_count{$g}$w))
lettuce_max	max(lettuce_command_completion_seconds_max{$g})
breaker_state	resilience4j_circuitbreaker_state{$g,name="auth-redis"}
bulkhead_available	resilience4j_bulkhead_available_concurrent_calls{$g,name="auth-redis"}
inflight	gateway_in_flight_requests{$g}
inflight_limit	gateway_in_flight_limit{$g}
inflight_rejected	increase(gateway_in_flight_rejected_total{$g}$w)
auth_redis_cause	sum by (cause) (increase(gateway_auth_redis_failures_total{$g}$w))
EOF
}

cmd_archive() {
  local run=$1 start=${2:-} end=${3:-} dir name expr n empty=0
  dir=$(run_dir "$run")
  [[ -d "$dir" ]] || fail "없다: $dir"
  [[ "$start" =~ ^[0-9]+$ && "$end" =~ ^[0-9]+$ && "$end" -gt "$start" ]] || fail "시작·끝 epoch초가 필요하다"
  need curl; need python3
  queries > "$dir/archive/queries.txt"
  while IFS=$'\t' read -r name expr; do
    curl -fsS -G "$PROM/api/v1/query_range" --data-urlencode "query=$expr" \
      --data-urlencode "start=$start" --data-urlencode "end=$end" --data-urlencode "step=$STEP" \
      > "$dir/archive/prom-$name.json" || { warn "Prometheus 조회 실패: $name"; empty=1; continue; }
    n=$(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))["data"]["result"]))' "$dir/archive/prom-$name.json")
    if [[ "$n" == 0 ]]; then warn "결과 0개: $name"; empty=1; fi
  done < "$dir/archive/queries.txt"
  if [[ ! -s "$dir/archive/prom-lettuce_count.json" ]] || grep -q '"result":\[\]' "$dir/archive/prom-lettuce_count.json"; then
    warn "Lettuce 지표 이름이 다르다. 있는 이름: $(curl -fsS "$PROM/api/v1/label/__name__/values" | python3 -c 'import json,sys; print(" ".join(n for n in json.load(sys.stdin)["data"] if n.startswith("lettuce")))')"
  fi

  # Zipkin: 구간 안 스팬 이름별 개수, 200ms 넘는 추적 상위 20건(측정 샘플링 0.01이라 표본이다)
  local end_ms=$((end * 1000)) look_ms=$(((end - start) * 1000))
  curl -fsS -G "$ZIPKIN/api/v2/traces" --data-urlencode "endTs=$end_ms" --data-urlencode "lookback=$look_ms" \
    --data-urlencode "limit=10000" \
    | python3 -c 'import json,sys,collections
c=collections.Counter(s.get("name","") for t in json.load(sys.stdin) for s in t)
json.dump(dict(c.most_common()),sys.stdout,ensure_ascii=False,indent=1)' > "$dir/archive/zipkin-span-counts.json" \
    || { warn "Zipkin 스팬 개수 조회 실패"; empty=1; }
  curl -fsS -G "$ZIPKIN/api/v2/traces" --data-urlencode "endTs=$end_ms" --data-urlencode "lookback=$look_ms" \
    --data-urlencode "minDuration=200000" --data-urlencode "limit=20" > "$dir/archive/zipkin-slow-traces.json" \
    || { warn "Zipkin 느린 추적 조회 실패"; empty=1; }

  tar czf "$OUT_ROOT/server-$run.tar.gz" -C "$OUT_ROOT" "$MEASURER-$run"
  echo "보존: $OUT_ROOT/server-$run.tar.gz ($(du -h "$OUT_ROOT/server-$run.tar.gz" | cut -f1))"
  [[ "$empty" == 0 ]] || warn "빈 결과나 실패가 있다. 위 주의를 보고 판정에 쓸지 정한다"
}

case "${1:-}" in
  pid) cmd_pid ;;
  start) shift; cmd_start "$@" ;;
  check) shift; cmd_check "$@" ;;
  stop) shift; cmd_stop "$@" ;;
  archive) shift; cmd_archive "$@" ;;
  *) sed -n '2,13p' "$0"; exit 2 ;;
esac

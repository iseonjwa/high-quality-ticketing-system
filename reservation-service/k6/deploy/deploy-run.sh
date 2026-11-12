#!/usr/bin/env bash
# 배포 서버에서 K1 단계(base → G1 → R1 → P4a → P4b → P4c → F → 사전 거절)를 한 번에 잰다. 측정 브랜치 전용이다.
# 내 PC(Git Bash)에서 돌리고, 배포 서버와 발생기에 SSH로 명령을 보낸다. 발생기는 배포 서버에 SSH로 들어갈 수 없어서다
# (배포 서버 22번은 팀원 IP만 받는다). 설계와 예상: STEPS.md "배포 단계 측정".
#
# 사용(워크트리 루트):
#   MEASURER=kwonwoo DEPLOY_SSH=iseonjwa LOADGEN_SSH=ubuntu@<발생기 퍼블릭 IP> bash reservation-service/k6/deploy/deploy-run.sh
#   STEPS="base p4c" KINDS="L"로 일부만 잰다. DRY_RUN=1이면 서버에 아무것도 하지 않고 순서만 출력한다
#
# 먼저 할 일(loadgen.md 4절): 팀 채널 시작 알림(main 머지 금지), 발생기 켜짐 확인, 단계 이미지 넣기(build-images.sh SHIP=1),
# 발생기 ~/<측정자>/iseonjwa에 저장소 받기. 측정 모드 켜기·키 옮기기·끄기는 이 스크립트가 한다(measure-mode.sh를 부른다).
#
# 회마다 하는 일
#   1. 배포 서버: measure-mode.sh on <단계 덮어쓰기> → reservation-service만 그 단계 이미지·설정으로 다시 뜬다(Gateway 등은 배포 그대로)
#   2. Gateway가 새 인스턴스를 받을 때까지 공개 API를 확인하고 60초 더 기다린다(loadtest/k6/README.md: 배포 서버에서 다시 만든 직후 503)
#   3. 서버에서 회차 3개(데우기용, 워밍업, 측정)를 만든다. 관리자 비밀번호는 발생기에 두지 않는다(loadgen.md 2절)
#   4. 발생기에서 k6(run.sh)를 nohup으로 돌리고 끝날 때까지 기다린다. 서버에서는 10초 표본(sampler.sh)을 남긴다
#   5. 결과를 PC의 reservation-service/k6/results/deploy-<단계>-<종류>-<시각>-*로 모은다
#      (k6 원자료·요약·로그, 회복 탐침, Prometheus 지표 CSV, 서버 표본, 대사, GC 로그, 앱 오류 로그, 조건)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
K6="$ROOT/reservation-service/k6"
D="$K6/deploy"
source "$D/steps.sh"
cd "$ROOT"

: "${MEASURER:?MEASURER(영문 이름)가 필요합니다. 측정 모드 알림에 남는다}"
DEPLOY_SSH="${DEPLOY_SSH:-iseonjwa}"
LOADGEN_SSH="${LOADGEN_SSH:?LOADGEN_SSH(예: ubuntu@<발생기 퍼블릭 IP>)가 필요합니다}"
# 발생기·배포 서버 안의 내 폴더(홈 기준). 계정이 하나라 측정자 폴더를 나눈다(loadgen.md 2절)
LOADGEN_DIR="${LOADGEN_DIR:-$MEASURER/iseonjwa}"
REMOTE_DIR="${REMOTE_DIR:-$MEASURER/k6steps}"
STEPS="${STEPS:-$STEP_ORDER}"
KINDS="${KINDS:-L S}"
# 발생기 → Gateway 사설 주소. 공인 IP는 요금이 붙고 보안 그룹에도 맞지 않는다(loadgen.md "주소와 요금")
TARGET="${TARGET:-http://172.31.4.93:8080}"
# Note: S는 160명 단계까지만 올린다. 좌석 응답이 약 830KB라 발생기 네트워크 기준(1.562Gbps ≈ 초당 195MB)으로는
# 좌석 조회가 초당 약 180건에서 막힌다(계산값). 320명 단계를 걸면 서버가 아니라 발생기 네트워크를 재게 된다
S_STAGES="${S_STAGES:-5,10,20,40,80,160}"
WARMUP_SECONDS="${WARMUP_SECONDS:-60}"
WARMUP_RATE="${WARMUP_RATE:-2}"
# 발생기는 8GiB다. k6 상한을 5GiB로 둔다(로컬 6GiB). 160명까지는 로컬에서 1GiB 아래였다
K6_MEMORY="${K6_MEMORY:-5g}"
K6_GOMEMLIMIT="${K6_GOMEMLIMIT:-4608MiB}"
PROM_PORT="${PROM_PORT:-19090}"
OFF_AT_END="${OFF_AT_END:-1}"
DRY="${DRY_RUN:-0}"

SSH_OPTS=(-o BatchMode=yes -o ServerAliveInterval=30)
dssh() { if [ "$DRY" = 1 ]; then echo "DRY deploy\$ $*" >&2; else ssh "${SSH_OPTS[@]}" "$DEPLOY_SSH" "$@"; fi; }
lssh() { if [ "$DRY" = 1 ]; then echo "DRY loadgen\$ $*" >&2; else ssh "${SSH_OPTS[@]}" "$LOADGEN_SSH" "$@"; fi; }
log() { echo "[deploy $(date +%H:%M:%S)] $*"; }

for s in $STEPS; do
  [ -f "$D/overlays/$s.yml" ] || { echo "단계 $s의 덮어쓰기(overlays/$s.yml)가 없습니다" >&2; exit 1; }
done
# 측정한 코드와 도구를 기록하려고 커밋된 상태에서만 돈다. 발생기도 같은 커밋으로 맞춘다
DIRTY="$(git status --porcelain -- "$K6")"
if [ -n "$DIRTY" ] && [ "${ALLOW_DIRTY:-0}" != 1 ]; then
  echo "측정 도구에 커밋되지 않은 변경이 있습니다(시험 실행은 ALLOW_DIRTY=1)" >&2; echo "$DIRTY" >&2; exit 1
fi
COMMIT="$(git rev-parse HEAD)"
mkdir -p "$K6/results"

# ---- 사전 확인 ----
log "측정 모드 상태 확인"
STATUS="$(dssh 'cd iseonjwa && script/loadtest/measure-mode.sh status' || true)"
echo "$STATUS"
if grep -q '측정 모드: 켜짐' <<< "$STATUS" && ! grep -q "켠 측정자: $MEASURER\$" <<< "$STATUS"; then
  echo "다른 측정자가 측정 모드를 켜 두었습니다. 팀 채널에서 확인한 뒤 다시 합니다" >&2; exit 1
fi
log "서버의 단계 이미지 확인"
for s in $STEPS; do
  tag="${STEP_IMAGE[$s]}"
  got="$(dssh "docker image inspect -f '{{index .Config.Labels \"k6steps.commit\"}}' $IMAGE_REPO:$tag" 2>/dev/null || true)"
  [ "$DRY" = 1 ] || [ "$got" = "${IMAGE_COMMIT[$tag]}" ] \
    || { echo "서버에 $IMAGE_REPO:$tag(커밋 ${IMAGE_COMMIT[$tag]})가 없습니다. build-images.sh를 SHIP=1로 먼저 돌립니다" >&2; exit 1; }
done
log "서버 도구·덮어쓰기 복사: ~/$REMOTE_DIR"
if [ "$DRY" = 1 ]; then echo "DRY tar overlays server invariants.sql → deploy:~/$REMOTE_DIR" >&2; else
  tar -C "$D" -cf - overlays server | dssh "mkdir -p $REMOTE_DIR/results && tar -C $REMOTE_DIR -xf -"
  dssh "cat > $REMOTE_DIR/invariants.sql" < "$K6/tools/invariants.sql"
fi
log "발생기 저장소를 $COMMIT로 맞춤"
lssh "cd $LOADGEN_DIR && git fetch -q origin && git checkout -q --detach $COMMIT && git log --oneline -1"

# Prometheus는 서버 안 9090이다. PC에서 SSH 터널로 읽는다(측정 모드에서만 열리는 포트지만 발생기 보안 그룹에만 열려 있다)
TUNNEL_PID=""
if [ "$DRY" != 1 ]; then
  ssh "${SSH_OPTS[@]}" -N -L "$PROM_PORT:127.0.0.1:9090" "$DEPLOY_SSH" & TUNNEL_PID=$!
fi
cleanup() { [ -n "$TUNNEL_PID" ] && kill "$TUNNEL_PID" 2>/dev/null || true; }
trap cleanup EXIT

KEYS_COPIED=0
switch_step() {  # switch_step <단계>
  local s="$1" tag="${STEP_IMAGE[$1]}" ok=0 code
  log "[$s] 측정 모드 on: overlays/$s.yml (이미지 $IMAGE_REPO:$tag)"
  dssh "cd iseonjwa && MEASURER=$MEASURER script/loadtest/measure-mode.sh on \$HOME/$REMOTE_DIR/overlays/$s.yml" | tail -3
  if [ "$KEYS_COPIED" = 0 ]; then
    # 측정 전용 키 두 개만 발생기 내 폴더 .env로 옮긴다. PC를 거치지만 화면·파일에 남기지 않는다(loadgen.md 4절 4번)
    log "측정 전용 키를 발생기 ~/$LOADGEN_DIR/.env로 옮김"
    [ "$DRY" = 1 ] || dssh 'cat iseonjwa/.env.measure' | lssh "umask 077 && cat > $LOADGEN_DIR/.env"
    KEYS_COPIED=1
  fi
  [ "$DRY" = 1 ] && return 0
  local image
  image="$(dssh "docker inspect -f '{{.Config.Image}}' \$(docker ps -q --filter label=com.docker.compose.service=reservation-service)")"
  [ "$image" = "$IMAGE_REPO:$tag" ] || { echo "[$s] reservation-service가 $image로 떠 있습니다(기대 $IMAGE_REPO:$tag)" >&2; exit 1; }
  for _ in $(seq 1 90); do
    code="$(dssh "curl -s -o /dev/null -w '%{http_code}' --max-time 5 'http://127.0.0.1:8080/api/v1/concerts?page=0&size=1'" || true)"
    if [ "$code" = 200 ]; then ok=$((ok + 1)); [ "$ok" -ge 3 ] && break; else ok=0; fi
    sleep 2
  done
  [ "$ok" -ge 3 ] || { echo "[$s] Gateway가 공개 API에 200을 주지 않습니다" >&2; exit 1; }
  sleep 60
}

run_kind() {  # run_kind <단계> <L|S>
  local s="$1" kind="$2" stamp name prefix sid warm sampler env_k6 load_seconds start_ms start load_end end
  stamp="$(date +%Y%m%d-%H%M%S)"
  name="deploy-$s-$kind-$stamp"
  prefix="$K6/results/$name"
  case "$kind" in
    L) env_k6="MODEL=open RATE=${RATE:-100} DURATION_SECONDS=${DURATION_SECONDS:-300} RECOVERY_SECONDS=0"
       load_seconds="${DURATION_SECONDS:-300}" ;;
    S) env_k6="MODEL=ramp STAGES=$S_STAGES RECOVERY_SECONDS=${RECOVERY_SECONDS:-120} MAX_VUS=${MAX_VUS:-4000}"
       load_seconds=$(( $(tr ',' '\n' <<< "$S_STAGES" | grep -c .) * ${STAGE_SECONDS:-60} )) ;;
    *) echo "측정 종류는 L, S만 씁니다" >&2; exit 1 ;;
  esac
  log "[$s-$kind] 회차 만들기(데우기·워밍업·측정)"
  dssh "bash $REMOTE_DIR/server/create-schedule.sh 'k6 deploy $s prime'" >/dev/null || true
  warm="$(dssh "bash $REMOTE_DIR/server/create-schedule.sh 'k6 deploy $s warmup'")"
  sid="$(dssh "bash $REMOTE_DIR/server/create-schedule.sh 'k6 deploy $s $kind'")"
  [ "$DRY" = 1 ] && { warm=1; sid=2; }
  log "[$s-$kind] 측정 회차 $sid, 워밍업 회차 $warm"
  sampler="$(dssh "nohup bash $REMOTE_DIR/server/sampler.sh $sid $REMOTE_DIR/results/$name-stats.txt </dev/null >/dev/null 2>&1 & echo \$!")"
  log "[$s-$kind] k6 시작(발생기, $env_k6)"
  lssh "cd $LOADGEN_DIR && mkdir -p reservation-service/k6/results && nohup env $env_k6 SCHEDULE_ID=$sid WARMUP_SCHEDULE_ID=$warm \
    WARMUP_SECONDS=$WARMUP_SECONDS WARMUP_RATE=$WARMUP_RATE TOKEN_MODE=sign ENV_FILE=\$HOME/$LOADGEN_DIR/.env \
    BASE_URL=$TARGET LABEL=$name K6_MEMORY=$K6_MEMORY K6_GOMEMLIMIT=$K6_GOMEMLIMIT \
    bash reservation-service/k6/run.sh </dev/null > reservation-service/k6/results/$name-k6.log 2>&1 &"
  # Note: 원격 백그라운드 명령은 표준 입력·출력을 모두 떼야 ssh가 기다리지 않고 바로 돌아온다
  if [ "$DRY" != 1 ]; then
    local deadline=$(( $(date +%s) + WARMUP_SECONDS + load_seconds + 900 ))
    until lssh "grep -q '^k6_exit=' $LOADGEN_DIR/reservation-service/k6/results/$name-k6.log" 2>/dev/null; do
      [ "$(date +%s)" -lt "$deadline" ] || { echo "[$s-$kind] k6가 끝나지 않습니다. 발생기 로그를 봅니다" >&2; break; }
      sleep 30
    done
  fi
  end="$(date +%s)"
  dssh "kill $sampler" || true
  log "[$s-$kind] 결과 모으기"
  [ "$DRY" = 1 ] && return 0
  # 발생기: run.sh가 LABEL을 이름에 넣으므로 이 회의 파일만 고른다
  lssh "cd $LOADGEN_DIR/reservation-service/k6/results && tar -cf - \$(ls | grep -F -- '$name')" | tar -C "$K6/results" -xf -
  start_ms="$(grep -oE 'load_start epoch_ms=[0-9]+' "$prefix-k6.log" | head -1 | cut -d= -f2 || true)"
  if [ -z "$start_ms" ]; then echo "[$s-$kind] load_start가 없습니다. $prefix-k6.log를 봅니다" >&2; return 0; fi
  start=$(( start_ms / 1000 )); load_end=$(( start + load_seconds ))
  grep -oE 'probe epoch_ms=[0-9]+ status=[0-9]+ [0-9]+ms' "$prefix-k6.log" \
    | awk -v end="$load_end" '{split($2,a,"="); printf "+%ds %s %s\n", a[2]/1000-end, $3, $4}' > "$prefix-probe.txt" || true
  PROM_URL="http://127.0.0.1:$PROM_PORT" python "$K6/tools/b8_metrics.py" "$start" "$load_end" "$end" "$prefix" || echo "[$s-$kind] 지표를 읽지 못했다" >&2
  dssh "bash $REMOTE_DIR/server/collect.sh $REMOTE_DIR/results/$name $sid $start" || true
  dssh "cd $REMOTE_DIR/results && tar -cf - \$(ls | grep -F -- '$name')" | tar -C "$K6/results" -xf -
  {
    echo "step=$s kind=$kind image=$IMAGE_REPO:${STEP_IMAGE[$s]} image_commit=${IMAGE_COMMIT[${STEP_IMAGE[$s]}]} overlay=deploy/overlays/$s.yml tools_commit=$COMMIT"
    echo "schedule=$sid warmup_schedule=$warm warmup_seconds=$WARMUP_SECONDS warmup_rate=$WARMUP_RATE target=$TARGET env_k6=$env_k6"
    echo "start=$start load_end=$load_end end=$end"
    echo "$(grep -oE 'k6_memory=[^ ]+ k6_gomemlimit=[^ ]+' "$prefix-k6.log" | head -1) $(grep -oE 'k6_exit=[0-9]+' "$prefix-k6.log" | tail -1)"
    cat "$prefix-server.txt" 2>/dev/null
  } > "$prefix-meta.txt"
  log "[$s-$kind] 끝: $prefix (대사 아래)"
  cat "$prefix-invariants.txt" 2>/dev/null | tail -5
}

for s in $STEPS; do
  switch_step "$s"
  for kind in $KINDS; do run_kind "$s" "$kind"; done
done

if [ "$OFF_AT_END" = 1 ]; then
  log "측정 모드 off, 발생기 키 지움"
  dssh "cd iseonjwa && MEASURER=$MEASURER script/loadtest/measure-mode.sh off" | tail -3
  lssh "rm -f $LOADGEN_DIR/.env"
  dssh 'cd iseonjwa && script/loadtest/measure-mode.sh status'
else
  log "측정 모드를 켜 둔 채 끝냄. 끝나면 measure-mode.sh off와 발생기 .env 삭제를 잊지 않는다"
fi
log "모두 끝. 끝 알림(loadgen.md 5절)과 측정 환경 기록표(load-test.md 7절)를 채운다"

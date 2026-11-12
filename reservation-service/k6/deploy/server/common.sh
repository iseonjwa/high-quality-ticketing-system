# 배포 서버에서 도는 단계 측정 도구의 공통 함수다(source). 서버의 ~/<측정자>/k6steps/server/에 복사돼 돈다.
# 서버 저장소(~/iseonjwa)의 파일은 바꾸지 않는다. .env는 필요한 키만 읽고 값을 출력하지 않는다(seed-demo.sh와 같은 방식).
REPO="${REPO:-$HOME/iseonjwa}"
env_value() { grep -E "^$1=" "$REPO/.env" | head -1 | cut -d= -f2-; }
# compose 서비스 이름으로 컨테이너를 하나 찾는다. 하나가 아니면 실패한다
container_of() {
  local ids
  ids="$(docker ps -q --filter "label=com.docker.compose.service=$1")"
  [ "$(printf '%s\n' "$ids" | grep -c .)" = 1 ] || { echo "$1 컨테이너를 하나로 정하지 못했다" >&2; return 1; }
  printf '%s\n' "$ids"
}
db_user() { local u; u="$(env_value DB_USER)"; echo "${u:-iseonjwa}"; }
psql_reservation() { docker exec -i "$(container_of postgres-reservation)" psql -U "$(db_user)" -d reservation "$@"; }

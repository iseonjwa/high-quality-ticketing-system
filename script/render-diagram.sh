#!/usr/bin/env bash
# 발표용 다이어그램 SVG를 같은 이름의 PNG(2배 해상도)로 내보낸다.
# 사용: script/render-diagram.sh docs/presentation/architecture/2026-11-11-system-architecture.svg [...]
# 규칙은 docs/presentation/brand/diagram-style.md에 있다.
set -euo pipefail

chrome=${CHROME:-}
if [[ -z "$chrome" ]]; then
  for candidate in \
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
    "$(command -v google-chrome 2>/dev/null || true)" \
    "$(command -v chromium 2>/dev/null || true)"; do
    if [[ -n "$candidate" && -x "$candidate" ]]; then chrome=$candidate; break; fi
  done
fi
if [[ -z "$chrome" ]]; then
  echo "Chrome을 찾지 못했다. CHROME 환경변수로 실행 파일 경로를 넘긴다." >&2
  exit 1
fi
if [[ $# -eq 0 ]]; then
  echo "사용: $0 <그림.svg> [...]" >&2
  exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

for svg in "$@"; do
  png="${svg%.svg}.png"
  # 화면 크기는 SVG의 viewBox 너비·높이를 그대로 쓴다
  read -r width height < <(sed -n 's/.*viewBox="0 0 \([0-9]*\) \([0-9]*\)".*/\1 \2/p' "$svg" | head -1)
  if [[ -z "${width:-}" || -z "${height:-}" ]]; then
    echo "$svg: viewBox=\"0 0 W H\"를 찾지 못했다" >&2
    exit 1
  fi

  # Note: SVG를 <img>로 열면 웹 글꼴을 쓸 수 없어 HTML 안에 직접 넣는다.
  # Pretendard가 설치돼 있으면 그 글꼴을, 없으면 CDN 글꼴을 쓴다.
  {
    printf '<!doctype html><html><head><meta charset="utf-8">'
    printf '<link rel="stylesheet" href="https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/variable/pretendardvariable-dynamic-subset.min.css">'
    printf '<style>html,body{margin:0;padding:0;background:#FBFBFD}svg{display:block}</style></head><body>'
    cat "$svg"
    printf '</body></html>'
  } > "$work/page.html"

  "$chrome" --headless=new --disable-gpu --hide-scrollbars \
    --force-device-scale-factor=2 --window-size="$width,$height" \
    --virtual-time-budget=5000 --screenshot="$png" "file://$work/page.html" >/dev/null 2>&1

  echo "$png ($((width * 2))x$((height * 2)))"
done

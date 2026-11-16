#!/usr/bin/env bash
# highlight-a·b·c의 SVG → PNG(2배)와 축소본(800×450)을 만든다. 사용: bash render.sh a b c
cd "$(dirname "$0")" || exit 1
EDGE="/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
for n in "$@"; do
  python "highlight-$n.py" || exit 1
  rm -f "highlight-$n.png"
  # Edge가 캡처를 쓰기 전에 돌아오는 때가 있어, 파일이 생길 때까지 기다리고 안 생기면 다시 시도한다
  for try in 1 2 3; do
    "$EDGE" --headless=new --disable-gpu --window-size=1600,900 --force-device-scale-factor=2 \
      --screenshot="$(cygpath -w "$PWD/highlight-$n.png")" "file:///$(cygpath -m "$PWD/highlight-$n.svg")" 2>/dev/null
    for i in $(seq 1 20); do [ -s "highlight-$n.png" ] && break; python -c "import time; time.sleep(0.5)"; done
    [ -s "highlight-$n.png" ] && break
  done
  python -c "from PIL import Image; Image.open('highlight-$n.png').resize((800, 450), Image.LANCZOS).save('highlight-$n-small.png')"
done
ls -la highlight-*.png

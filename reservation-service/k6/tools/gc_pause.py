"""단계 측정 한 회의 GC 정지 비율을 구한다. 측정 브랜치(experiment/ISE-79-k6-steps) 전용이다.

사용: python reservation-service/k6/tools/gc_pause.py <결과 접두어> [구간 초]
  예: python reservation-service/k6/tools/gc_pause.py reservation-service/k6/results/step-g1-L-20261107-040000 60

gc.log는 JVM 기동 뒤 경과 시간(uptime)으로 찍힌다. JVM 기동 시각은 Prometheus의 process_start_time_seconds에서 읽어
부하 구간(meta의 start ~ load_end)으로 자른다. Serial과 G1의 "Pause ..." 줄만 정지로 센다(G1의 Concurrent 단계는 정지가 아니다).
"""
import json
import re
import sys
import urllib.parse
import urllib.request
from collections import defaultdict

PAUSE = re.compile(r"^\[(?P<up>[\d.]+)s\] GC\(\d+\) (?P<kind>Pause \w+)[^\n]*? (?P<ms>[\d.]+)ms$")
PROMETHEUS = "http://localhost:9090/api/v1/query"


def meta(prefix):
    values = {}
    for line in open(prefix + "-meta.txt", encoding="utf-8"):
        for token in line.split():
            if "=" in token:
                key, value = token.split("=", 1)
                values[key] = value
    return values


def jvm_start(at_epoch):
    # Note: 측정마다 reservation-service를 새로 띄우므로, 부하 시작 시각의 기동 시각이 그 회의 JVM이다
    query = urllib.parse.urlencode({
        "query": 'max(process_start_time_seconds{application="reservation-service"})',
        "time": at_epoch,
    })
    with urllib.request.urlopen(f"{PROMETHEUS}?{query}", timeout=10) as response:
        result = json.load(response)["data"]["result"]
    if not result:
        sys.exit("Prometheus에서 reservation-service 기동 시각을 찾지 못했습니다(보존 기간을 넘었을 수 있습니다)")
    return float(result[0]["value"][1])


def main():
    prefix = sys.argv[1]
    bucket = int(sys.argv[2]) if len(sys.argv) > 2 else 0
    m = meta(prefix)
    start, load_end = int(m["start"]), int(m["load_end"])
    offset = jvm_start(start + 1)

    total = 0.0
    count = defaultdict(int)
    by_kind = defaultdict(float)
    by_bucket = defaultdict(float)
    longest = (0.0, "")
    for line in open(prefix + "-gc.log", encoding="utf-8"):
        match = PAUSE.match(line.strip())
        if not match:
            continue
        at = offset + float(match["up"])
        if not start <= at < load_end:
            continue
        seconds = float(match["ms"]) / 1000
        total += seconds
        count[match["kind"]] += 1
        by_kind[match["kind"]] += seconds
        if bucket:
            by_bucket[int((at - start) // bucket)] += seconds
        if seconds > longest[0]:
            longest = (seconds, f"+{at - start:.0f}s {match['kind']}")

    length = load_end - start
    print(f"부하 {length}초 중 GC 정지 {total:.1f}초({total / length:.1%}), 최장 {longest[0] * 1000:.0f}ms ({longest[1]})")
    for kind in sorted(count):
        print(f"  {kind}: {count[kind]}회 {by_kind[kind]:.1f}초")
    for index in sorted(by_bucket):
        print(f"  +{index * bucket}s~: {by_bucket[index]:.1f}초({by_bucket[index] / bucket:.0%})")


if __name__ == "__main__":
    main()

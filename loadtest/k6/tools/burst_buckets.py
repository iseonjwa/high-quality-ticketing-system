#!/usr/bin/env python3
# 대기열 진입(Q1·Q1-d)을 보낸 순간 기준 짧은 구간(기본 100ms)으로 나눠, 구간마다 보낸 수와 결과(진입·상한 거절·인증 거절 등)를 센다.
# 같은 사용자 수를 1초 안에 고르게 보낸 Q1과 앞쪽이 붐비게 보낸 Q1-d를 나란히 놓고 "몰림 모양이 거절을 늘리는가"를 본다.
# Prometheus는 5초마다 긁어 0.2초 몰림이 보이지 않고, k6 HTTP 지표의 CSV 시각은 응답이 끝난 때라 보낸 순간을 알 수 없다.
# 그래서 시나리오가 남긴 enter_send_offset(시작 기준 보낸 시각 ms, 결과 태그 포함)을 읽는다.
# 사용(로컬, run.sh가 만든 원자료):
#   python -I loadtest/k6/tools/burst_buckets.py [--bucket-ms 100] [--all-attempts] <라벨>=<raw.csv.gz> [<라벨>=<raw.csv.gz> ...]
#   예) python -I loadtest/k6/tools/burst_buckets.py A1=results/q1-queue-enter-…-raw.csv.gz C1=results/q1d-queue-burst-…-raw.csv.gz
# 시각 0은 그 회차에서 가장 먼저 보낸 요청이다(Q1은 setup이 끝난 뒤 첫 요청까지의 틈을 빼려고, Q1-d는 출발 시각 앞의 지연이 없어 같다).
import argparse, collections, csv, gzip, math, sys

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

METRIC = "enter_send_offset"
# 표에 따로 칸을 두는 결과. overloaded = Gateway 동시 처리 상한 거절, auth = 인증 Redis bulkhead·차단기 거절.
# 나머지는 other로 묶고 아래에 code:msg별로 따로 적는다
COLUMNS = ["joined", "already", "overloaded", "auth", "no_response", "other"]


def tags_of(text):
    tags = {}
    for part in filter(None, text.split("&")):
        key, _, value = part.partition("=")
        tags[key] = value
    return tags


def classify(tags):
    result, code, msg = tags.get("result", ""), tags.get("code", ""), tags.get("msg", "")
    if result in ("joined", "already"):
        return result
    if code == "GATEWAY_OVERLOADED":
        return "overloaded"
    if msg == "auth_unavailable":
        return "auth"
    # 연결 거절·시한 초과(status 0). 몰림 순간의 새 연결이 수락 대기열을 넘쳤을 수 있다
    if code == "NO_RESPONSE":
        return "no_response"
    return "other"


def read_rows(path, all_attempts):
    rows = []
    with gzip.open(path, "rt", newline="", encoding="utf-8") as f:
        reader = csv.reader(f)
        header = next(reader)
        col = {name: i for i, name in enumerate(header)}
        for row in reader:
            if row[col["metric_name"]] != METRIC:
                continue
            tags = tags_of(row[col["extra_tags"]])
            # Q1-c 연타(repeat)는 같은 사용자의 두 번째 요청이라 기본은 뺀다. Q1-d에는 attempt 태그가 없다
            if not all_attempts and tags.get("attempt") == "repeat":
                continue
            rows.append((float(row[col["metric_value"]]), classify(tags), f'{tags.get("code", "")}:{tags.get("msg", "")}'))
    return rows


def report(label, rows, bucket_ms):
    if not rows:
        print(f"## {label}: {METRIC} 행이 없다(시나리오가 이 지표를 남기는 판인지 확인)\n")
        return
    t0 = min(r[0] for r in rows)
    span = max(r[0] for r in rows) - t0
    buckets = collections.defaultdict(collections.Counter)
    others = collections.Counter()
    for offset, kind, detail in rows:
        buckets[math.floor((offset - t0) / bucket_ms)][kind] += 1
        if kind == "other":
            others[detail] += 1
    total = len(rows)
    totals = collections.Counter()
    for c in buckets.values():
        totals.update(c)
    peak_idx, peak = max(((i, sum(c.values())) for i, c in buckets.items()), key=lambda x: x[1])
    half_at = sorted(r[0] - t0 for r in rows)[(total - 1) // 2]

    print(f"## {label}")
    print(f"- 요청 {total}건, 보낸 시간 폭 {span:.0f}ms, 절반을 보낸 시각 {half_at:.0f}ms, "
          f"가장 붐빈 구간 {peak_idx * bucket_ms}~{(peak_idx + 1) * bucket_ms}ms에 {peak}건(순간 초당 {peak * 1000 / bucket_ms:.0f}건, 계산값)")
    print("- 합계: " + ", ".join(f"{k} {totals[k]}" for k in COLUMNS))
    print()
    print("| 구간(ms) | 보낸 수 | " + " | ".join(COLUMNS) + " |")
    print("|---" * (len(COLUMNS) + 2) + "|")
    for i in range(0, max(buckets) + 1):
        c = buckets.get(i, collections.Counter())
        print(f"| {i * bucket_ms}~{(i + 1) * bucket_ms} | {sum(c.values())} | " + " | ".join(str(c[k]) for k in COLUMNS) + " |")
    if others:
        print("\n- other 내역(code:msg): " + ", ".join(f"{k} {v}" for k, v in others.most_common()))
    print()


def main():
    p = argparse.ArgumentParser(description="Q1·Q1-d 보낸 순간 기준 구간별 결과")
    p.add_argument("--bucket-ms", type=int, default=100)
    p.add_argument("--all-attempts", action="store_true", help="Q1-c 연타 요청도 센다")
    p.add_argument("runs", nargs="+", help="<라벨>=<raw.csv.gz>")
    args = p.parse_args()
    if args.bucket_ms <= 0:
        p.error("--bucket-ms는 1 이상이다")
    for run in args.runs:
        label, sep, path = run.partition("=")
        if not sep:
            label, path = run, run
        report(label, read_rows(path, args.all_attempts), args.bucket_ms)


if __name__ == "__main__":
    main()

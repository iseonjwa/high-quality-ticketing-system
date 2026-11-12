#!/usr/bin/env python3
# R0 계산: 단계별 Gateway CPU를 받은 요청·거절 요청 수로 연립해 한 건 비용을 푼다.
#   Gateway CPU(코어) = b + a × 받은 요청/s + r × 거절/s
#   b = 쉬는 비용, a = 받은 요청 한 건 CPU(초), r = 거절 한 건 CPU(초)
# 사용(로컬에서, 회차마다 --round를 한 번씩):
#   python -I loadtest/k6/tools/r0_solve.py --cpu pidstat \
#     --round R0A <k6 raw.csv.gz> <서버 <MEASURER>-r0-A 폴더> 250,0,500,0,1000,0,500,0,1000 \
#     --round R0R <k6 raw.csv.gz> <서버 <MEASURER>-r0-R 폴더> 250,0,500,0,1000,0,500,0,1000
# 서버 폴더는 gw-collect.sh archive가 만든 server-<회차>.tar.gz를 푼 것, k6 원자료는 g1-run.sh가 만든 gen-<회차>.tar.gz 안의
# 본 측정 raw.csv.gz다(<회차>-run.log 끝의 results/g1 줄).
# --cpu pidstat: pidstat.log의 Java 프로세스 %CPU(1초). dstats: docker stats의 Gateway CPU(약 3초). 6·7차 자료는 dstats만 쓸 수 있다.
# 단계 구간은 g1-run.sh 본 측정(상승 5초 + 30초)에 맞춘다: k6 첫 g1 요청 시각 t0 기준 [t0+35k+5, t0+35k+35).
# 받은 요청 = 상한 거절(503 GATEWAY_OVERLOADED)이 아닌 모든 응답. bulkhead 거절은 인증 뒤라 받은 요청에 넣는다.
import argparse, collections, csv, datetime, gzip, math, os, re, sys

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")
REJECT = "503:GATEWAY_OVERLOADED"


def k6_counts(path):
    t0, per = None, collections.defaultdict(collections.Counter)
    with gzip.open(path, "rt", newline="", encoding="utf-8") as f:
        r = csv.reader(f)
        next(r)
        for row in r:
            m, ts = row[0], int(row[1])
            if m == "http_req_duration" and row[11] == "g1":
                t0 = ts if t0 is None or ts < t0 else t0
            elif m == "auth_path_results":
                tags = dict(kv.split("=", 1) for kv in row[17].split("&") if "=" in kv)
                per[ts][f"{row[13]}:{tags.get('code', '')}"] += 1
    return t0, per


def meta_day(d):
    # 측정 시작 날짜(UTC). meta.log 첫 줄, 없으면(6차) vmstat.log의 날짜 칸을 쓴다
    meta = os.path.join(d, "meta.log")
    if os.path.exists(meta):
        day = open(meta, encoding="utf-8").readline().strip()[:10]
    else:
        day = next(x.split()[-2] for x in open(os.path.join(d, "vmstat.log"), encoding="utf-8")
                   if re.search(r"\d{4}-\d\d-\d\d \d\d:\d\d:\d\d$", x.strip()))
    return datetime.datetime.strptime(day, "%Y-%m-%d").replace(tzinfo=datetime.timezone.utc)


def to_epoch(day, hms, last):
    t = int((day + datetime.timedelta(hours=int(hms[0:2]), minutes=int(hms[3:5]), seconds=int(hms[6:8]))).timestamp())
    while last is not None and t < last - 3600:
        t += 86400
    return t


def cpu_pidstat(d):
    # TGID 줄(스레드 줄 아님)의 %CPU. 4코어 합이라 100%가 1코어다
    day, out, last = meta_day(d), {}, None
    hdr = None
    for line in open(os.path.join(d, "pidstat.log"), encoding="utf-8", errors="replace"):
        x = line.split()
        if "%CPU" in x:
            hdr = x
            continue
        if not hdr or len(x) != len(hdr) or not re.match(r"\d\d:\d\d:\d\d$", x[0]):
            continue
        row = dict(zip(hdr[1:], x[1:]))
        if row.get("TID") != "-":
            continue
        if row["Command"] != "java":
            sys.exit(f"pidstat.log가 java가 아닌 {row['Command']}를 잡았다: {d}")
        last = to_epoch(day, x[0], last)
        out[last] = float(row["%CPU"]) / 100
    return out


def cpu_dstats(d, name="iseonjwa-gateway-1"):
    day, out, now, last = meta_day(d), {}, None, None
    for line in open(os.path.join(d, "dstats.log"), encoding="utf-8", errors="replace"):
        x = line.split()
        if x and x[0] == "==":  # 6차 형식 "== HH:MM:SS"
            x = x[1:]
        if len(x) == 1 and re.match(r"\d\d:\d\d:\d\d$", x[0]):
            now = last = to_epoch(day, x[0], last)
        elif now and x and x[0] == name:
            out[now] = float(x[1].rstrip("%")) / 100
    return out


def stage_rows(name, raw, d, stages, cpu_kind):
    t0, per = k6_counts(raw)
    cpu = cpu_pidstat(d) if cpu_kind == "pidstat" else cpu_dstats(d)
    rows = []
    for k, target in enumerate(stages):
        a, b = t0 + 35 * k + 5, t0 + 35 * k + 35
        c = collections.Counter()
        for s in range(a, b):
            c.update(per.get(s, {}))
        rej = c[REJECT] / 30
        acc = (sum(c.values()) - c[REJECT]) / 30
        v = [cpu[s] for s in range(a, b) if s in cpu]
        if len(v) < 2:
            sys.exit(f"{name} {target}/s 구간에 CPU 표본이 {len(v)}개다. 시각이 맞는지 본다")
        mean = sum(v) / len(v)
        se = math.sqrt(sum((y - mean) ** 2 for y in v) / (len(v) - 1) / len(v))
        rows.append(dict(round=name, target=target, acc=acc, rej=rej, cpu=mean, se=se, n=len(v)))
    return rows


def solve3(m, y):
    # 3×3 가우스 소거. 특이 행렬이면 None
    a = [m[i][:] + [y[i]] for i in range(3)]
    for i in range(3):
        p = max(range(i, 3), key=lambda r: abs(a[r][i]))
        if abs(a[p][i]) < 1e-12:
            return None
        a[i], a[p] = a[p], a[i]
        for r in range(3):
            if r != i:
                f = a[r][i] / a[i][i]
                a[r] = [a[r][j] - f * a[i][j] for j in range(4)]
    return [a[i][3] / a[i][i] for i in range(3)]


def inv3(m):
    cols = [solve3(m, [1 if i == j else 0 for i in range(3)]) for j in range(3)]
    return None if None in cols else [[cols[j][i] for j in range(3)] for i in range(3)]


def fit(rows):
    # 가중 최소제곱(가중치 = 1/표준오차²). 표준오차가 0이면 작은 값으로 막는다
    X = [[1.0, r["acc"], r["rej"]] for r in rows]
    w = [1 / max(r["se"], 0.005) ** 2 for r in rows]
    y = [r["cpu"] for r in rows]
    xtx = [[sum(w[k] * X[k][i] * X[k][j] for k in range(len(rows))) for j in range(3)] for i in range(3)]
    xty = [sum(w[k] * X[k][i] * y[k] for k in range(len(rows))) for i in range(3)]
    beta = solve3(xtx, xty)
    cov = inv3(xtx)
    if beta is None or cov is None:
        return None
    resid = [y[k] - sum(X[k][i] * beta[i] for i in range(3)) for k in range(len(rows))]
    dof = max(len(rows) - 3, 1)
    chi2 = sum(w[k] * resid[k] ** 2 for k in range(len(rows))) / dof
    scale = max(chi2, 1.0)  # 표본 잡음보다 식이 덜 맞으면 오차를 키운다
    se = [math.sqrt(cov[i][i] * scale) for i in range(3)]
    return beta, se, resid, chi2


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--cpu", choices=["pidstat", "dstats"], default="pidstat")
    p.add_argument("--round", nargs=4, action="append", metavar=("NAME", "RAW", "SERVER_DIR", "STAGES"), required=True)
    p.add_argument("--only", default="", help="쉼표로 준 목표만 연립에 넣는다(예: 1000,0). 비우면 전부")
    o = p.parse_args()
    rows = []
    for name, raw, d, st in o.round:
        rows += stage_rows(name, raw, d, [int(x) for x in st.split(",")], o.cpu)
    print(f"CPU 출처: {o.cpu}")
    print("회차 | 목표 | 받은/s | 거절/s | Gateway CPU(코어) | 표준오차 | 표본")
    for r in rows:
        print(f"{r['round']} | {r['target']} | {r['acc']:.0f} | {r['rej']:.0f} | {r['cpu']:.3f} | {r['se']:.3f} | {r['n']}")
    use = [r for r in rows if not o.only or str(r["target"]) in o.only.split(",")]
    res = fit(use)
    if res is None:
        sys.exit("연립이 풀리지 않는다. 받은 요청과 거절 비율이 단계마다 달라야 한다(R0A·R0R 둘 다 필요)")
    (b, a, r), (sb, sa, sr), resid, chi2 = res
    print(f"\nb(쉬는 비용) = {b:.3f} ± {sb:.3f} 코어")
    print(f"a(받은 한 건) = {a * 1000:.3f} ± {sa * 1000:.3f} ms")
    print(f"r(거절 한 건) = {r * 1000:.3f} ± {sr * 1000:.3f} ms")
    print(f"r/a = {r / a:.3f}" if a > 0 else "a가 0 이하라 r/a를 못 낸다")
    print(f"적합도(가중 잔차 제곱 / 자유도) = {chi2:.2f}. 1 근처면 표본 잡음만큼 맞는다. 크면 식이 단계 사이에 안 맞는다")
    print("잔차(코어): " + ", ".join(f"{u['round']}@{u['target']} {e:+.3f}" for u, e in zip(use, resid)))


if __name__ == "__main__":
    main()

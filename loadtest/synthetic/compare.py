#!/usr/bin/env python3
"""같은 서버·같은 사용자 수로 돌린 O1 두 번(균일 난수, Synthetic)의 원자료를 비교표로 만든다.

사용법:
  python3 loadtest/synthetic/compare.py \
      --base loadtest/k6/results/o1-open-peak-<균일>-raw.csv.gz \
      --synthetic loadtest/k6/results/o1-open-peak-<synthetic>-raw.csv.gz > compare.md

입력은 run.sh가 남기는 k6 원자료(--out csv)다. 열은 metric_name, timestamp(초), metric_value, ..., status, ..., extra_tags이며,
기본 열에 없는 태그(api, step, persona, outcome)는 extra_tags에 `k=v&k=v`로 온다.
"""
import argparse
import csv
import gzip
import math
from collections import Counter, defaultdict

STEP_ORDER = ["browse", "enter", "poll", "token", "seats", "hold", "pay"]
SETUP_API = "setup"
# 초당 최대치를 볼 API(설계 문서 synthetic-data.md 5-0절의 LLM 예측 판정에 쓴다)
PEAK_APIS = ["concert_detail", "queue_enter", "queue_poll", "seats", "hold", "payment"]
WAVE_SECONDS = 60


def open_text(path):
    if path.endswith(".gz"):
        return gzip.open(path, "rt", encoding="utf-8", newline="")
    return open(path, encoding="utf-8", newline="")


def tags_of(row):
    tags = {}
    extra = row.get("extra_tags") or ""
    for pair in extra.split("&"):
        if "=" in pair:
            key, value = pair.split("=", 1)
            tags[key] = value
    return tags


def tag(row, tags, name):
    # 기본 열(status 등)에 있으면 그 값을, 없으면 extra_tags 값을 쓴다
    value = row.get(name)
    if value:
        return value
    return tags.get(name, "")


class Run:
    def __init__(self, path):
        self.path = path
        self.step_values = defaultdict(list)
        self.api_status = Counter()
        self.outcomes = Counter()
        self.persona_outcomes = defaultdict(Counter)
        self.request_count = 0
        self.per_second = defaultdict(Counter)
        self.hold_by_second = defaultdict(Counter)
        self.hold_by_zone = defaultdict(Counter)
        self.first = None
        self.last = None
        self.read()

    def read(self):
        with open_text(self.path) as file:
            for row in csv.DictReader(file):
                metric = row["metric_name"]
                if metric == "step_duration":
                    tags = tags_of(row)
                    step = tag(row, tags, "step")
                    self.step_values[step].append(float(row["metric_value"]))
                    if step == "hold" and tags.get("code"):
                        self.hold_by_zone[tags.get("zone") or "ANY"][tags["code"]] += 1
                elif metric == "http_reqs":
                    self.add_request(row)
                elif metric == "o1_outcomes":
                    tags = tags_of(row)
                    outcome = tag(row, tags, "outcome")
                    count = int(float(row["metric_value"]))
                    self.outcomes[outcome] += count
                    self.persona_outcomes[tag(row, tags, "persona") or "(없음)"][outcome] += count

    def add_request(self, row):
        tags = tags_of(row)
        api = tag(row, tags, "api") or "(태그 없음)"
        if api == SETUP_API:
            return
        count = int(float(row["metric_value"]))
        self.api_status[(api, row.get("status", ""))] += count
        self.request_count += count
        stamp = int(float(row["timestamp"]))
        self.per_second[api][stamp] += count
        if api == "hold":
            self.hold_by_second[stamp][row.get("status", "")] += count
        self.first = stamp if self.first is None else min(self.first, stamp)
        self.last = stamp if self.last is None else max(self.last, stamp)

    @property
    def seconds(self):
        # Note: k6 CSV의 timestamp는 초 단위 정수라 측정 시간은 1초 단위로만 안다
        if self.first is None:
            return 0
        return max(self.last - self.first, 1)

    def peak(self, api):
        """초당 최대 요청 수와 그 시각(첫 요청부터 몇 초 뒤)"""
        seconds = self.per_second.get(api)
        if not seconds:
            return 0, None
        stamp, count = max(seconds.items(), key=lambda item: (item[1], -item[0]))
        return count, stamp - self.first

    @property
    def open_second(self):
        # Note: 원자료에는 오픈 시각이 없다. 첫 대기열 진입 요청을 오픈으로 본다(진입은 오픈 뒤에만 받는다)
        seconds = self.per_second.get("queue_enter")
        return min(seconds) if seconds else self.first

    def api_total(self, api):
        return sum(count for (name, _), count in self.api_status.items() if name == api)


def percentile(sorted_values, p):
    if not sorted_values:
        return math.nan
    position = p / 100 * (len(sorted_values) - 1)
    lower = int(math.floor(position))
    upper = min(lower + 1, len(sorted_values) - 1)
    return sorted_values[lower] + (sorted_values[upper] - sorted_values[lower]) * (position - lower)


def number(value, digits=1):
    return "-" if isinstance(value, float) and math.isnan(value) else f"{value:,.{digits}f}"


def percent(part, whole):
    return "-" if whole == 0 else f"{part / whole * 100:.1f}%"


def table(header, rows):
    lines = ["| " + " | ".join(header) + " |", "|" + "|".join("---" for _ in header) + "|"]
    lines.extend("| " + " | ".join(str(cell) for cell in row) + " |" for row in rows)
    return "\n".join(lines)


def step_table(runs):
    steps = [s for s in STEP_ORDER if any(s in run.step_values for _, run in runs)]
    steps += sorted({s for _, run in runs for s in run.step_values} - set(steps))
    rows = []
    for step in steps:
        for label, run in runs:
            values = sorted(run.step_values.get(step, []))
            if not values:
                rows.append([step, label, 0, "-", "-", "-", "-", "-"])
                continue
            rows.append([step, label, f"{len(values):,}", number(sum(values) / len(values)), number(percentile(values, 50)),
                         number(percentile(values, 95)), number(percentile(values, 99)), number(values[-1])])
    return table(["구간", "실행", "건수", "평균(ms)", "p50(ms)", "p95(ms)", "p99(ms)", "최대(ms)"], rows)


def status_table(runs):
    keys = sorted({key for _, run in runs for key in run.api_status})
    rows = []
    for api, status in keys:
        rows.append([api, status or "-"] + [f"{run.api_status.get((api, status), 0):,}" for _, run in runs])
    text = table(["api", "상태"] + [label + " 건수" for label, _ in runs], rows)
    conflict = []
    for label, run in runs:
        total = run.api_total("hold")
        conflict.append(f"{label} {run.api_status.get(('hold', '409'), 0):,}/{total:,} = "
                        f"{percent(run.api_status.get(('hold', '409'), 0), total)}")
    return text + "\n\n선점(`api=hold`) 409 비율: " + ", ".join(conflict)


def outcome_table(runs):
    names = sorted({name for _, run in runs for name in run.outcomes})
    rows = []
    for name in names:
        row = [name]
        for _, run in runs:
            total = sum(run.outcomes.values())
            row += [f"{run.outcomes.get(name, 0):,}", percent(run.outcomes.get(name, 0), total)]
        rows.append(row)
    header = ["결과"]
    for label, _ in runs:
        header += [label + " 건수", label + " 비율"]
    return table(header, rows)


def persona_table(run):
    names = sorted({name for counter in run.persona_outcomes.values() for name in counter})
    rows = []
    for persona in sorted(run.persona_outcomes):
        counter = run.persona_outcomes[persona]
        total = sum(counter.values())
        rows.append([persona, f"{total:,}"] + [f"{counter.get(name, 0):,} ({percent(counter.get(name, 0), total)})" for name in names])
    return table(["persona", "합계"] + names, rows)


def peak_table(runs):
    apis = [api for api in PEAK_APIS if any(api in run.per_second for _, run in runs)]
    rows = []
    for api in apis:
        row = [api]
        for _, run in runs:
            count, offset = run.peak(api)
            row += [f"{count:,}", "-" if offset is None else offset]
        rows.append(row)
    header = ["api"]
    for label, _ in runs:
        header += [label + " 초당 최대", label + " 시각(초)"]
    return table(header, rows)


def wave_table(runs):
    buckets = {}
    for label, run in runs:
        start = run.open_second
        if start is None:
            continue
        for stamp, statuses in run.hold_by_second.items():
            bucket = (stamp - start) // WAVE_SECONDS
            buckets.setdefault(bucket, {}).setdefault(label, Counter()).update(statuses)
    rows = []
    for bucket in sorted(buckets):
        row = [f"{bucket * WAVE_SECONDS}~{(bucket + 1) * WAVE_SECONDS}"]
        for label, _ in runs:
            counter = buckets[bucket].get(label, Counter())
            row += [f"{counter.get('201', 0):,}", f"{counter.get('409', 0):,}"]
        rows.append(row)
    header = ["오픈 뒤(초)"]
    for label, _ in runs:
        header += [label + " 201", label + " 409"]
    return table(header, rows)


def zone_table(runs):
    zones = sorted({zone for _, run in runs for zone in run.hold_by_zone})
    rows = []
    for zone in zones + ["전체"]:
        row = [zone]
        for _, run in runs:
            if zone == "전체":
                counter = sum(run.hold_by_zone.values(), Counter())
            else:
                counter = run.hold_by_zone.get(zone, Counter())
            total = sum(counter.values())
            row += [f"{total:,}", f"{counter.get('409', 0):,} ({percent(counter.get('409', 0), total)})"]
        rows.append(row)
    header = ["처음 노린 등급"]
    for label, _ in runs:
        header += [label + " 선점 요청", label + " 409(비율)"]
    return table(header, rows)


def summary_table(runs):
    rows = []
    for label, run in runs:
        rate = run.request_count / run.seconds if run.seconds else math.nan
        rows.append([label, f"{run.request_count:,}", run.seconds, number(rate)])
    return table(["실행", "전체 요청 수", "측정 시간(초)", "초당 요청 수"], rows)


def build_report(base, synthetic):
    runs = [("균일", base), ("Synthetic", synthetic)]
    parts = [
        "## 1. 구간별 응답 시간(`step_duration`)", step_table(runs),
        "## 2. API별 상태 코드(`http_reqs`)", status_table(runs),
        "## 3. 사용자 결과(`o1_outcomes`)", outcome_table(runs),
        "## 4. 페르소나별 결과(Synthetic)", persona_table(synthetic),
        "## 5. 요청 수와 처리량",
        summary_table(runs) + "\n\n`api=setup`(준비 요청)은 뺐다. 측정 시간은 첫 요청부터 마지막 요청까지의 timestamp 차이다(초 단위).",
        "## 6. API별 초당 최대 요청 수",
        peak_table(runs) + "\n\n시각은 그 실행의 첫 요청부터 잰 초다. 오픈 직후 진입이 더 뾰족하게 몰리는지, 오픈 전 상세 조회의 정점이 높은지를 본다.",
        "## 7. 오픈 뒤 1분 구간별 선점 결과",
        wave_table(runs) + "\n\n오픈은 첫 대기열 진입 요청 시각으로 본다. 선점 유지 5분이 지나 풀린 좌석에 두 번째 몰림이 오는지(5~7분 구간)를 본다.",
        "## 8. 처음 노린 등급별 선점 409 비율",
        zone_table(runs) + "\n\n균일 모드는 등급을 정하지 않아 모두 ANY다. 등급 태그가 없는 예전 원자료면 표가 비어 있다.",
    ]
    return "\n\n".join(parts) + "\n"


def main():
    parser = argparse.ArgumentParser(description="O1 균일 난수 실행과 Synthetic 실행을 비교한다")
    parser.add_argument("--base", required=True, help="균일 난수 O1의 원자료(raw.csv.gz)")
    parser.add_argument("--synthetic", required=True, help="Synthetic O1의 원자료(raw.csv.gz)")
    args = parser.parse_args()
    print(build_report(Run(args.base), Run(args.synthetic)), end="")


if __name__ == "__main__":
    main()

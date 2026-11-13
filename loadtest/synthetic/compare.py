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
        self.first = None
        self.last = None
        self.read()

    def read(self):
        with open_text(self.path) as file:
            for row in csv.DictReader(file):
                metric = row["metric_name"]
                if metric == "step_duration":
                    self.step_values[tag(row, tags_of(row), "step")].append(float(row["metric_value"]))
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
        self.first = stamp if self.first is None else min(self.first, stamp)
        self.last = stamp if self.last is None else max(self.last, stamp)

    @property
    def seconds(self):
        # Note: k6 CSV의 timestamp는 초 단위 정수라 측정 시간은 1초 단위로만 안다
        if self.first is None:
            return 0
        return max(self.last - self.first, 1)

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

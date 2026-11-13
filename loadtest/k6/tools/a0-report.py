#!/usr/bin/env python3
"""A0 요약 JSON(results/*-summary.json)에서 발제 표 한 줄을 마크다운으로 출력한다.

사용법: python3 loadtest/k6/tools/a0-report.py loadtest/k6/results/a0-assignment-baseline-<시각>-summary.json [...]
요약 JSON은 run.sh가 k6 --summary-export로 남긴다. 파일을 여러 개 주면 줄이 늘어난다(동기형·접수형 비교).
처리량은 요청 수 ÷ 측정 창이다. 측정 창은 VU들의 첫 요청 직전 시각(a0_window_start 최솟값)부터 마지막 요청이 끝난 시각(a0_window_end 최댓값)까지다.
"""
import json
import os
import sys


def metric(summary, name):
    try:
        return summary["metrics"][name]
    except KeyError:
        sys.exit(f"요약에 {name} 지표가 없습니다. A0 시나리오(scenarios/a0-assignment-baseline.js)의 요약인지 확인합니다")


def row(path):
    with open(path, encoding="utf-8") as file:
        summary = json.load(file)
    requests = int(metric(summary, "http_reqs{api:hold}")["count"])
    # Note: http_req_failed는 실패한 요청에 참(1)을 넣는 Rate 지표다. 요약 JSON의 passes가 참인 표본 수, 곧 실패 수다
    failures = int(metric(summary, "http_req_failed{api:hold}")["passes"])
    duration = metric(summary, "a0_hold_duration")
    window = (metric(summary, "a0_window_end")["max"] - metric(summary, "a0_window_start")["min"]) / 1000
    success = requests - failures
    rate = f"{success / requests * 100:.1f}%" if requests else "-"
    throughput = f"{requests / window:.1f}" if window > 0 else "-"
    name = os.path.basename(path).replace("-summary.json", "")
    return [name, f"{requests:,}", f"{success:,}", rate, f"{duration['avg']:.1f}", f"{duration['p(95)']:.1f}",
            f"{duration['max']:.1f}", f"{window:.2f}", throughput]


def main():
    if len(sys.argv) < 2:
        sys.exit("사용법: a0-report.py <요약 JSON> [...]")
    header = ["실행", "요청 수", "성공 수", "성공률", "평균(ms)", "p95(ms)", "최대(ms)", "측정 창(초)", "처리량(건/초)"]
    print("| " + " | ".join(header) + " |")
    print("|" + "|".join("---" for _ in header) + "|")
    for path in sys.argv[1:]:
        print("| " + " | ".join(row(path)) + " |")


if __name__ == "__main__":
    main()

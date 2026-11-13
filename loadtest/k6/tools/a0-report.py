#!/usr/bin/env python3
"""A0 요약 JSON(results/*-summary.json)에서 발제 표 한 줄을 마크다운으로 출력한다.

사용법: python3 loadtest/k6/tools/a0-report.py [--expected 1000] loadtest/k6/results/a0-assignment-baseline-<시각>-summary.json [...]
요약 JSON은 run.sh가 k6 --summary-export로 남긴다. 파일을 여러 개 주면 줄이 늘어난다(동기형·접수형 비교).
처리량은 요청 수 ÷ 측정 창이다. 측정 창은 VU들의 첫 요청 직전 시각(a0_window_start 최솟값)부터 마지막 요청이 끝난 시각(a0_window_end 최댓값)까지다.
판정은 측정 전에 고정한 기준(docs/design/infra/synthetic-data.md 5-0)을 따른다. 무효는 다시 재고, 불합격은 원인을 찾는다.
처리량에는 합격선을 두지 않는다. SA 3장이 고정 목표(200/sec)를 버리고 같은 환경의 전·후 비교로 정했다(결정 #47).
"""
import argparse
import json
import os
import sys

FAILURE_LIMIT = 0.01


def metric(summary, name):
    try:
        return summary["metrics"][name]
    except KeyError:
        sys.exit(f"요약에 {name} 지표가 없습니다. A0 시나리오(scenarios/a0-assignment-baseline.js)의 요약인지 확인합니다")


def verdict(requests, expected, conflicts, failures):
    if requests != expected or conflicts > 0:
        return "무효"
    if requests and failures / requests >= FAILURE_LIMIT:
        return "불합격"
    return "합격"


def row(path, expected):
    with open(path, encoding="utf-8") as file:
        summary = json.load(file)
    requests = int(metric(summary, "http_reqs{api:hold}")["count"])
    # Note: http_req_failed는 실패한 요청에 참(1)을 넣는 Rate 지표다. 요약 JSON의 passes가 참인 표본 수, 곧 실패 수다
    failures = int(metric(summary, "http_req_failed{api:hold}")["passes"])
    # Note: 409 표본이 하나도 없으면 k6가 하위 지표를 요약에 넣지 않는다. 없으면 0건이다
    conflicts = int(summary["metrics"].get("a0_results{status:409}", {}).get("count", 0))
    duration = metric(summary, "a0_hold_duration")
    window = (metric(summary, "a0_window_end")["max"] - metric(summary, "a0_window_start")["min"]) / 1000
    success = requests - failures
    rate = f"{success / requests * 100:.1f}%" if requests else "-"
    throughput = f"{requests / window:.1f}" if window > 0 else "-"
    name = os.path.basename(path).replace("-summary.json", "")
    return [name, f"{requests:,}", f"{success:,}", rate, f"{conflicts:,}", f"{duration['avg']:.1f}",
            f"{duration['p(95)']:.1f}", f"{duration['max']:.1f}", f"{window:.2f}", throughput,
            verdict(requests, expected, conflicts, failures)]


def main():
    parser = argparse.ArgumentParser(description="A0 요약 JSON을 발제 표 한 줄로 출력한다")
    parser.add_argument("--expected", type=int, default=1000, help="기대 요청 수(VUS × ITERATIONS, 기본 100 × 10)")
    parser.add_argument("summaries", nargs="+", help="results/*-summary.json")
    args = parser.parse_args()
    header = ["실행", "요청 수", "성공 수", "성공률", "409", "평균(ms)", "p95(ms)", "최대(ms)", "측정 창(초)",
              "처리량(건/초)", "판정"]
    print("| " + " | ".join(header) + " |")
    print("|" + "|".join("---" for _ in header) + "|")
    for path in args.summaries:
        print("| " + " | ".join(row(path, args.expected)) + " |")
    print()
    print(f"판정: 무효 = 요청 수가 {args.expected:,}건이 아니거나 409가 1건 이상(다시 잰다), "
          f"불합격 = 오류율 {FAILURE_LIMIT:.0%} 이상, 합격 = 그 밖. 처리량은 합격선 없이 전·후 비교로 본다")


if __name__ == "__main__":
    main()

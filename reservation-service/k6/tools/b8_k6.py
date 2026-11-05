"""B8 측정의 k6 원자료(raw.csv.gz)에서 사용자 쪽 결과를 회차별로 센다.

사용: python reservation-service/k6/tools/b8_k6.py <raw.csv.gz>...
좌석 조회·선점의 상태 코드별 건수, 좌석 조회 오류 응답의 p50·p95를 출력한다. 회복 탐침(api=probe)과 setup 요청은 뺀다.
판정 기준은 docs/design/reservation/k6-overload-recovery.md 6절이다.
"""
import csv
import gzip
import json
import re
import sys


def percentile(values, q):
    return round(values[max(0, int(len(values) * q) - 1)]) if values else None


def summarize(path):
    seats, holds, errors, schedule = {}, {}, [], None
    # Note: 원자료는 수십 MB라 한 줄씩 읽는다. 노트북 메모리가 모자라 측정이 멈춘 적이 있다
    with gzip.open(path, "rt", encoding="utf-8") as f:
        reader = csv.reader(f)
        index = {name: i for i, name in enumerate(next(reader))}
        for row in reader:
            if row[0] != "http_req_duration":
                continue
            api = re.search(r"api=(\w+)", row[index["extra_tags"]])
            api = api.group(1) if api else ""
            status = row[index["status"]]
            if schedule is None:
                found = re.search(r"schedules/(\d+)/", row[index["url"]])
                schedule = found.group(1) if found else None
            if api == "seats":
                seats[status] = seats.get(status, 0) + 1
                if status != "200":
                    errors.append(float(row[index["metric_value"]]))
            elif api == "hold":
                holds[status] = holds.get(status, 0) + 1
    errors.sort()
    return {
        "schedule": schedule, "seats": seats, "holds": holds,
        "seats_error_p50_ms": percentile(errors, 0.5), "seats_error_p95_ms": percentile(errors, 0.95),
    }


if __name__ == "__main__":
    for raw in sys.argv[1:]:
        print(raw, json.dumps(summarize(raw), ensure_ascii=False))

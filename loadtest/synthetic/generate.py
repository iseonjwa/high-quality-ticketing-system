#!/usr/bin/env python3
"""페르소나 분포 JSON을 시드 고정으로 수천 명의 가상 사용자 CSV로 늘린다.

사용법:
  python3 loadtest/synthetic/generate.py --personas loadtest/synthetic/o1-personas.json \
      --users 3000 --seed 20261113 --out loadtest/k6/data/o1-synthetic-3000.csv

같은 JSON·사용자 수·시드면 바이트 단위로 같은 파일이 나온다. 표준 라이브러리만 쓴다.
CSV를 k6 O1이 읽는 방법은 loadtest/k6/README.md에 있다.
"""
import argparse
import json
import math
import os
import random
import sys

import persona_spec
import persona_spec_v2
from persona_spec import (
    AXIS_COLUMNS, CSV_HEADER, FIELDS, axis_levels, round_half_up,
)

# --spec 값 → 규격 모듈. v1은 위에서 이름으로 가져온 것과 같은 모듈이다
SPECS = {"v1": persona_spec, "v2": persona_spec_v2}

# Note: normal이 범위 밖을 뽑으면 다시 뽑는다. 100번을 넘기면 범위 끝으로 자른다(평균이 범위 밖일 때 무한 반복을 막는다)
MAX_REDRAW = 100


def allocate(shares, users):
    """최대 나머지 방식으로 share × users를 정수로 나눈다. 합은 정확히 users다."""
    quotas = [share * users for share in shares]
    counts = [int(math.floor(quota)) for quota in quotas]
    order = sorted(range(len(shares)), key=lambda i: (-(quotas[i] - counts[i]), i))
    for i in order[:users - sum(counts)]:
        counts[i] += 1
    return counts


def draw_choice(rng, values, weights):
    point = rng.random() * sum(weights)
    cumulative = 0.0
    for value, weight in zip(values, weights):
        cumulative += weight
        if point < cumulative:
            return value
    return values[-1]


def draw(rng, dist):
    kind = dist["dist"]
    if kind == "const":
        return dist["value"]
    if kind == "uniform":
        return rng.uniform(dist["min"], dist["max"])
    if kind == "normal":
        low = dist.get("min", -math.inf)
        high = dist.get("max", math.inf)
        for _ in range(MAX_REDRAW):
            value = rng.gauss(dist["mean"], dist["sd"])
            if low <= value <= high:
                return value
        return min(max(value, low), high)
    if kind == "exponential":
        # Note: mean은 min을 뺀 지수 부분의 평균이다. 값은 min + Exp(mean)이고 max에서 자른다
        value = dist["min"] + (rng.expovariate(1 / dist["mean"]) if dist["mean"] > 0 else 0)
        return min(value, dist.get("max", math.inf))
    if kind == "choice":
        return draw_choice(rng, dist["values"], dist["weights"])
    if kind == "bernoulli":
        return 1 if rng.random() < dist["p"] else 0
    raise ValueError(f"모르는 분포: {kind}")


def draw_field(rng, field, dist, spec=persona_spec):
    value = draw(rng, dist)
    if field in spec.CHOICES:
        return value
    return spec.clamp_field(field, spec.fix_field(field, round_half_up(value)))


def build_rows(personas, users, rng, spec=None):
    counts = allocate([persona["share"] for persona in personas], users)
    # Note: spec이 없으면(v1) 모듈 이름을 그대로 쓴다. 테스트가 generate.axis_levels를 바꿔 끼워 축 열이 뽑은 값을 바꾸지 않는지 본다
    fields = FIELDS if spec is None else spec.FIELDS
    levels_of = axis_levels if spec is None else spec.axis_levels
    rows = []
    for persona, count in zip(personas, counts):
        for _ in range(count):
            row = {"persona": persona["code"]}
            for field in fields:
                row[field] = draw_field(rng, field, persona["fields"][field], spec or persona_spec)
            # 축 구간은 뽑은 값에서 계산만 한다. 난수를 쓰지 않으므로 기존 열의 값과 순서는 축을 넣기 전과 같다
            row.update(zip(AXIS_COLUMNS, levels_of(row)))
            rows.append(row)
    rng.shuffle(rows)
    for number, row in enumerate(rows):
        row["user_no"] = number
    return rows


def render(rows, header=CSV_HEADER):
    lines = [",".join(header)]
    for row in rows:
        lines.append(",".join(str(row[column]) for column in header))
    return "\n".join(lines) + "\n"


def generate(personas_doc, users, seed, spec=None):
    """spec이 없으면 v1(persona_spec)이다. v2는 persona_spec_v2 모듈을 넘긴다."""
    rng = random.Random(seed)
    header = CSV_HEADER if spec is None else spec.CSV_HEADER
    return render(build_rows(personas_doc["personas"], users, rng, spec), header)


def main():
    parser = argparse.ArgumentParser(description="페르소나 분포로 가상 사용자 CSV를 만든다")
    parser.add_argument("--personas", required=True, help="페르소나 분포 JSON")
    parser.add_argument("--users", type=int, required=True, help="만들 사용자 수")
    parser.add_argument("--seed", type=int, required=True, help="난수 시드")
    parser.add_argument("--out", required=True, help="CSV 출력 경로")
    parser.add_argument("--spec", choices=sorted(SPECS), default="v1", help="페르소나 규격(기본 v1)")
    args = parser.parse_args()
    if args.users <= 0:
        sys.exit("--users는 1 이상이어야 합니다")
    with open(args.personas, encoding="utf-8") as file:
        doc = json.load(file)
    text = generate(doc, args.users, args.seed, None if args.spec == "v1" else SPECS[args.spec])
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="") as file:
        file.write(text)
    spec_option = "" if args.spec == "v1" else f" --spec {args.spec}"
    print(f"{args.users}명을 {args.out}에 썼습니다(시드 {args.seed}). 검사: python3 loadtest/synthetic/validate.py "
          f"--personas {args.personas} --csv {args.out}{spec_option}")


if __name__ == "__main__":
    main()

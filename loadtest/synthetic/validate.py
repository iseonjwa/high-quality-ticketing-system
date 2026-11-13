#!/usr/bin/env python3
"""페르소나 JSON과 생성된 CSV를 검사한다. 문제가 하나라도 있으면 줄마다 출력하고 종료 코드 1을 돌려준다.

사용법:
  python3 loadtest/synthetic/validate.py --personas <json> [--csv <csv> ...]

--personas와 --csv를 같이 주면 CSV의 페르소나 비율과 값 분포가 JSON과 맞는지도 본다.
비율 허용치는 max(0.01, 3 × sqrt(p(1-p)/N))이다. N이 작은 페르소나는 비율 검사를 건너뛰고 범위만 본다.
"""
import argparse
import csv
import json
import sys
from collections import Counter

from persona_spec import (
    CHOICES, CSV_HEADER, DISCRETE_ONLY, DIST_KEYS, FIELDS, MIN_PERSONA_FOR_RATIO, PERSONA_KEYS, RANGES,
    clamp_field, fix_field, is_number, ratio_tolerance, round_half_up,
)

SUM_TOLERANCE = 1e-6


# ---------- 페르소나 JSON ----------

def check_personas(doc):
    problems = []
    personas = doc.get("personas") if isinstance(doc, dict) else None
    if not isinstance(personas, list) or not personas:
        return ["personas가 없거나 비어 있습니다"]
    total_share = 0.0
    codes = set()
    for index, persona in enumerate(personas):
        where = f"personas[{index}]"
        if not isinstance(persona, dict):
            problems.append(f"{where}: 객체가 아닙니다")
            continue
        code = persona.get("code")
        if isinstance(code, str):
            where = f"{where}({code})"
            if code in codes:
                problems.append(f"{where}: code가 겹칩니다")
            codes.add(code)
        else:
            problems.append(f"{where}: code가 문자열이 아닙니다")
        extra = set(persona) - PERSONA_KEYS
        missing = PERSONA_KEYS - set(persona)
        if extra:
            problems.append(f"{where}: 모르는 키 {sorted(extra)}")
        if missing:
            problems.append(f"{where}: 빠진 키 {sorted(missing)}")
        share = persona.get("share")
        if is_number(share) and 0 <= share <= 1:
            total_share += share
        else:
            problems.append(f"{where}: share가 0~1 숫자가 아닙니다({share!r})")
        problems.extend(check_fields(where, persona.get("fields")))
    if abs(total_share - 1) > SUM_TOLERANCE:
        problems.append(f"share 합이 1이 아닙니다({total_share:.6f})")
    return problems


def check_fields(where, fields):
    if not isinstance(fields, dict):
        return [f"{where}: fields가 객체가 아닙니다"]
    problems = []
    names = set(fields)
    expected = set(FIELDS)
    if names - expected:
        problems.append(f"{where}: 모르는 필드 {sorted(names - expected)}")
    if expected - names:
        problems.append(f"{where}: 빠진 필드 {sorted(expected - names)}")
    for field in FIELDS:
        if field in fields:
            problems.extend(check_dist(f"{where}.{field}", field, fields[field]))
    return problems


def allowed_discrete(field, value):
    """분포가 그대로 내는 값(const·choice 값)이 허용되는지. 이유 문자열 또는 None."""
    if field in CHOICES:
        return None if value in CHOICES[field] else f"허용 값이 아닙니다({value!r}, 허용 {CHOICES[field]})"
    if not is_number(value):
        return f"숫자가 아닙니다({value!r})"
    if value != int(value):
        return f"정수가 아닙니다({value!r})"
    low, high = RANGES[field]
    if not low <= value <= high:
        return f"범위 {low}~{high} 밖입니다({value!r})"
    if fix_field(field, int(value)) != int(value):
        return f"이 필드는 0 또는 최소값 이상이어야 합니다({value!r})"
    return None


def check_dist(where, field, dist):
    if not isinstance(dist, dict) or "dist" not in dist:
        return [f"{where}: dist가 없습니다"]
    kind = dist["dist"]
    if kind not in DIST_KEYS:
        return [f"{where}: 모르는 분포 종류 {kind!r}"]
    problems = []
    keys = set(dist) - {"dist"}
    if keys != DIST_KEYS[kind]:
        problems.append(f"{where}: {kind}의 키가 {sorted(DIST_KEYS[kind])}이어야 합니다(지금 {sorted(keys)})")
        return problems
    if field in CHOICES and kind not in ("const", "choice"):
        return problems + [f"{where}: 문자열 필드에는 const·choice만 쓸 수 있습니다"]
    if field in DISCRETE_ONLY and kind not in ("const", "choice", "bernoulli"):
        problems.append(f"{where}: 정수 필드에는 const·choice·bernoulli만 쓸 수 있습니다({kind})")
    if kind == "const":
        reason = allowed_discrete(field, dist["value"])
        if reason:
            problems.append(f"{where}: {reason}")
    elif kind == "choice":
        problems.extend(check_choice(where, field, dist))
    elif kind == "bernoulli":
        problems.extend(check_bernoulli(where, field, dist))
    else:
        problems.extend(check_continuous(where, field, kind, dist))
    return problems


def check_choice(where, field, dist):
    values, weights = dist["values"], dist["weights"]
    if not isinstance(values, list) or not isinstance(weights, list) or not values:
        return [f"{where}: values·weights가 비어 있거나 목록이 아닙니다"]
    problems = []
    if len(values) != len(weights):
        problems.append(f"{where}: values({len(values)})와 weights({len(weights)}) 길이가 다릅니다")
    if not all(is_number(w) and w >= 0 for w in weights):
        problems.append(f"{where}: weights에 음수나 숫자가 아닌 값이 있습니다")
    elif abs(sum(weights) - 1) > SUM_TOLERANCE:
        problems.append(f"{where}: weights 합이 1이 아닙니다({sum(weights):.6f})")
    for value in values:
        reason = allowed_discrete(field, value)
        if reason:
            problems.append(f"{where}: values의 {reason}")
    return problems


def check_bernoulli(where, field, dist):
    p = dist["p"]
    if not is_number(p) or not 0 <= p <= 1:
        return [f"{where}: p가 0~1이 아닙니다({p!r})"]
    if field in CHOICES:
        return [f"{where}: 문자열 필드에는 bernoulli를 쓸 수 없습니다"]
    outcomes = ([0] if p < 1 else []) + ([1] if p > 0 else [])
    return [f"{where}: bernoulli가 내는 {v}: {reason}" for v in outcomes
            if (reason := allowed_discrete(field, v))]


def check_continuous(where, field, kind, dist):
    problems = []
    for key in ("min", "max", "mean", "sd"):
        if key in dist and not is_number(dist[key]):
            problems.append(f"{where}: {key}가 숫자가 아닙니다({dist[key]!r})")
    if problems:
        return problems
    low, high = RANGES[field]
    if dist["min"] > dist["max"]:
        problems.append(f"{where}: min({dist['min']})이 max({dist['max']})보다 큽니다")
    if dist["min"] < low or dist["max"] > high:
        problems.append(f"{where}: min·max가 범위 {low}~{high} 밖입니다({dist['min']}~{dist['max']})")
    if kind == "normal" and dist["sd"] <= 0:
        problems.append(f"{where}: sd가 0 이하입니다({dist['sd']})")
    if kind == "exponential" and dist["mean"] <= 0:
        problems.append(f"{where}: mean이 0 이하입니다({dist['mean']})")
    return problems


# ---------- CSV ----------

def parse_int(text):
    try:
        return int(text)
    except ValueError:
        return None


def check_row(where, row):
    problems = []
    for field in FIELDS:
        text = row[field]
        if field in CHOICES:
            if text not in CHOICES[field]:
                problems.append(f"{where}: {field}={text!r}는 허용 값이 아닙니다({CHOICES[field]})")
            continue
        value = parse_int(text)
        if value is None:
            problems.append(f"{where}: {field}={text!r}는 정수가 아닙니다")
            continue
        low, high = RANGES[field]
        if not low <= value <= high:
            problems.append(f"{where}: {field}={value}가 범위 {low}~{high} 밖입니다")
        elif fix_field(field, value) != value:
            problems.append(f"{where}: {field}={value}는 0 또는 최소값 이상이어야 합니다")
    return problems


def read_csv(path):
    with open(path, encoding="utf-8", newline="") as file:
        reader = csv.reader(file)
        lines = list(reader)
    return lines


def check_csv(path, personas_doc):
    problems = []
    try:
        lines = read_csv(path)
    except OSError as error:
        return [f"{path}: 읽을 수 없습니다({error})"]
    if not lines or lines[0] != CSV_HEADER:
        return [f"{path}: 머리말이 다릅니다(기대 {','.join(CSV_HEADER)})"]
    rows = []
    for number, cells in enumerate(lines[1:], start=2):
        where = f"{path}:{number}"
        if len(cells) != len(CSV_HEADER):
            problems.append(f"{where}: 열이 {len(cells)}개입니다({len(CSV_HEADER)}개여야 합니다)")
            continue
        row = dict(zip(CSV_HEADER, cells))
        problems.extend(check_row(where, row))
        rows.append((where, row))
    problems.extend(check_user_numbers(path, rows))
    if personas_doc is not None:
        problems.extend(check_against_personas(path, rows, personas_doc))
    elif rows:
        print(f"{path}: --personas가 없어 페르소나 검사는 건너뜁니다")
    return problems


def check_user_numbers(path, rows):
    numbers = [parse_int(row["user_no"]) for _, row in rows]
    expected = list(range(len(rows)))
    if None in numbers or sorted(numbers) != expected:
        return [f"{path}: user_no가 0..{len(rows) - 1}을 한 번씩 갖지 않습니다"]
    return []


def check_against_personas(path, rows, doc):
    problems = []
    personas = {p["code"]: p for p in doc["personas"]}
    total = len(rows)
    by_persona = {code: [] for code in personas}
    for where, row in rows:
        if row["persona"] in by_persona:
            by_persona[row["persona"]].append((where, row))
        else:
            problems.append(f"{where}: persona={row['persona']!r}가 JSON에 없습니다")
    for code, persona in personas.items():
        members = by_persona[code]
        problems.extend(check_share(path, code, persona["share"], len(members), total))
        problems.extend(check_persona_values(path, persona, members))
    return problems


def check_share(path, code, share, count, total):
    if total == 0:
        return []
    actual = count / total
    tolerance = ratio_tolerance(share, total)
    if abs(actual - share) > tolerance:
        return [f"{path}: 페르소나 {code} 비율 {actual:.4f}가 기대 {share:.4f}에서 허용 {tolerance:.4f}를 벗어났습니다"]
    return []


def allowed_interval(field, dist):
    """연속 분포 한 필드가 낼 수 있는 정수 구간. 생성기와 같은 반올림·규칙을 거친다."""
    low = dist["min"]
    high = dist.get("max", RANGES[field][1])
    lo = clamp_field(field, fix_field(field, round_half_up(low)))
    hi = clamp_field(field, fix_field(field, round_half_up(high)))
    return lo, hi


def check_persona_values(path, persona, members):
    problems = []
    code = persona["code"]
    count = len(members)
    skip_ratio = count < MIN_PERSONA_FOR_RATIO
    if skip_ratio and count:
        print(f"{path}: 페르소나 {code}는 {count}명(<{MIN_PERSONA_FOR_RATIO})이라 비율 검사를 건너뛰고 범위만 봅니다")
    for field in FIELDS:
        dist = persona["fields"][field]
        kind = dist["dist"]
        for where, row in members:
            reason = value_violation(field, dist, row[field])
            if reason:
                problems.append(f"{where}: 페르소나 {code}의 {field}={row[field]!r}는 {reason}")
        if skip_ratio or count == 0:
            continue
        if kind == "choice":
            problems.extend(check_frequencies(path, code, field, members, choice_probabilities(field, dist)))
        elif kind == "bernoulli":
            problems.extend(check_frequencies(path, code, field, members, {1: dist["p"], 0: 1 - dist["p"]}))
    return problems


def choice_probabilities(field, dist):
    probabilities = {}
    for value, weight in zip(dist["values"], dist["weights"]):
        key = value if field in CHOICES else int(value)
        probabilities[key] = probabilities.get(key, 0) + weight
    return probabilities


def check_frequencies(path, code, field, members, probabilities):
    counts = Counter(row[field] if field in CHOICES else parse_int(row[field]) for _, row in members)
    n = len(members)
    problems = []
    for value, p in probabilities.items():
        actual = counts.get(value, 0) / n
        tolerance = ratio_tolerance(p, n)
        if abs(actual - p) > tolerance:
            problems.append(f"{path}: 페르소나 {code}의 {field}={value} 비율 {actual:.4f}가 기대 {p:.4f}에서 허용 {tolerance:.4f}를 벗어났습니다")
    return problems


def value_violation(field, dist, text):
    """그 페르소나 분포가 낼 수 없는 값이면 이유를, 아니면 None을 돌려준다."""
    kind = dist["dist"]
    if field in CHOICES:
        allowed = [dist["value"]] if kind == "const" else dist["values"]
        return None if text in allowed else f"분포 값 {allowed}에 없습니다"
    value = parse_int(text)
    if value is None:
        return None  # 정수 아님은 check_row가 따로 알린다
    if kind == "const":
        return None if value == dist["value"] else f"const {dist['value']}와 다릅니다"
    if kind == "choice":
        return None if value in dist["values"] else f"choice 값 {dist['values']}에 없습니다"
    if kind == "bernoulli":
        return None if value in (0, 1) else "0 또는 1이 아닙니다"
    lo, hi = allowed_interval(field, dist)
    return None if lo <= value <= hi else f"분포 구간 {lo}~{hi} 밖입니다"


def main():
    parser = argparse.ArgumentParser(description="페르소나 JSON과 CSV를 검사한다")
    parser.add_argument("--personas", required=True, help="페르소나 분포 JSON")
    parser.add_argument("--csv", action="append", default=[], help="검사할 CSV(여러 번 줄 수 있다)")
    args = parser.parse_args()

    try:
        with open(args.personas, encoding="utf-8") as file:
            doc = json.load(file)
    except (OSError, json.JSONDecodeError) as error:
        print(f"{args.personas}: JSON을 읽을 수 없습니다({error})")
        sys.exit(1)

    problems = check_personas(doc)
    persona_ok = not problems
    for path in args.csv:
        if persona_ok:
            problems.extend(check_csv(path, doc))
        else:
            # JSON이 틀리면 분포 비교가 의미 없어 머리말·범위만 본다
            problems.extend(check_csv(path, None))
    for problem in problems:
        print(problem)
    if problems:
        print(f"문제 {len(problems)}건")
        sys.exit(1)
    print("문제 없음")


if __name__ == "__main__":
    main()

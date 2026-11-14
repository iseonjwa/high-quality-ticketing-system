"""Synthetic Data 도구 테스트. 실행: python3 -m unittest loadtest/synthetic/test_synthetic.py -v"""
import copy
import csv
import gzip
import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import compare  # noqa: E402
import generate  # noqa: E402
import persona_spec  # noqa: E402
import validate  # noqa: E402

FIXTURE = os.path.join(HERE, "testdata", "personas-fixture.json")


def load_fixture():
    with open(FIXTURE, encoding="utf-8") as file:
        return json.load(file)


def write_temp(directory, name, text):
    path = os.path.join(directory, name)
    with open(path, "w", encoding="utf-8", newline="") as file:
        file.write(text)
    return path


def run_validate(personas_path, csv_paths=()):
    out = io.StringIO()
    argv = sys.argv
    sys.argv = ["validate.py", "--personas", personas_path]
    for path in csv_paths:
        sys.argv += ["--csv", path]
    code = 0
    try:
        with redirect_stdout(out):
            validate.main()
    except SystemExit as exit_signal:
        code = exit_signal.code
    finally:
        sys.argv = argv
    return code, out.getvalue()


class GenerateTest(unittest.TestCase):
    def test_same_seed_gives_same_bytes(self):
        doc = load_fixture()
        self.assertEqual(generate.generate(doc, 500, 7), generate.generate(doc, 500, 7))

    def test_different_seed_gives_different_output(self):
        doc = load_fixture()
        self.assertNotEqual(generate.generate(doc, 500, 7), generate.generate(doc, 500, 8))

    def test_allocation_sums_to_users(self):
        for users in (1, 7, 99, 1000, 3001):
            counts = generate.allocate([0.4, 0.3, 0.2, 0.1], users)
            self.assertEqual(sum(counts), users)
        self.assertEqual(generate.allocate([0.5, 0.25, 0.25], 10), [5, 3, 2])
        self.assertEqual(generate.allocate([1 / 3, 1 / 3, 1 / 3], 10), [4, 3, 3])

    def test_user_numbers_are_shuffled_and_sequential(self):
        rows = list(csv.DictReader(io.StringIO(generate.generate(load_fixture(), 400, 1))))
        self.assertEqual([int(row["user_no"]) for row in rows], list(range(400)))
        first_twenty = {row["persona"] for row in rows[:20]}
        self.assertGreater(len(first_twenty), 1)

    def test_rounding_rules(self):
        rows = list(csv.DictReader(io.StringIO(generate.generate(load_fixture(), 2000, 3))))
        for row in rows:
            self.assertNotIn(int(row["refresh_s"]), (1, 2))
            self.assertNotIn(int(row["queue_patience_s"]), range(1, 30))
        self.assertTrue(any(row["refresh_s"] == "3" for row in rows))
        self.assertTrue(any(row["queue_patience_s"] == "30" for row in rows))

    def test_header_order(self):
        text = generate.generate(load_fixture(), 5, 1)
        self.assertEqual(text.splitlines()[0], ",".join(persona_spec.CSV_HEADER))


LOWEST = {"arrive_s": 600, "refresh_s": 0, "enter_delay_ms": 60000, "enter_clicks": 0, "seats": 1, "zone": "ANY",
          "think_ms": 30000, "max_retry": 0, "on_conflict": "give_up", "queue_patience_s": 30,
          "abandon_after_hold": 1, "pay_delay_s": 280}
HIGHEST = {"arrive_s": -600, "refresh_s": 3, "enter_delay_ms": 0, "enter_clicks": 5, "seats": 4, "zone": "VIP",
           "think_ms": 300, "max_retry": 5, "on_conflict": "same_zone", "queue_patience_s": 0,
           "abandon_after_hold": 0, "pay_delay_s": 3}


class AxisTest(unittest.TestCase):
    def test_every_field_belongs_to_one_axis_except_shared(self):
        owners = {}
        for code, _, parts in persona_spec.AXES:
            for field, _ in parts:
                owners.setdefault(field, []).append(code)
        self.assertEqual(set(owners), set(persona_spec.FIELDS))
        shared = {field for field, codes in owners.items() if len(codes) > 1}
        self.assertEqual(shared, persona_spec.SHARED_FIELDS)
        self.assertEqual(len(persona_spec.AXES), 6)

    def test_extreme_rows_score_zero_and_one(self):
        self.assertEqual(persona_spec.axis_scores(LOWEST), [0.0] * 6)
        self.assertEqual(persona_spec.axis_scores(HIGHEST), [1.0] * 6)
        self.assertEqual(persona_spec.axis_levels(LOWEST), [0] * 6)
        self.assertEqual(persona_spec.axis_levels(HIGHEST), [2] * 6)

    def test_level_boundaries(self):
        cases = {0.0: 0, 0.33: 0, 1 / 3: 1, 0.5: 1, 0.66: 1, 2 / 3: 2, 1.0: 2}
        for score, level in cases.items():
            self.assertEqual(persona_spec.axis_level(score), level, score)

    def test_middle_values(self):
        row = dict(LOWEST, seats=2, zone="R", enter_clicks=1, max_retry=5, on_conflict="any_zone")
        scores = persona_spec.axis_scores(row)
        demand = [code for code, _, _ in persona_spec.AXES].index("demand")
        # 좌석 2석 = 1/3, R = 0.5의 평균
        self.assertAlmostEqual(scores[demand], (1 / 3 + 0.5) / 2)
        # 진입 0번이 아니면 구매 의지는 진입 성분 1, 선점 뒤 이탈(1) 성분 0의 평균이다
        intent = [code for code, _, _ in persona_spec.AXES].index("intent")
        self.assertAlmostEqual(scores[intent], 0.5)

    def test_axis_columns_do_not_change_drawn_values(self):
        doc = load_fixture()
        with_axes = list(csv.DictReader(io.StringIO(generate.generate(doc, 500, 7))))
        original = generate.axis_levels
        generate.axis_levels = lambda row: [9] * 6
        try:
            fake = list(csv.DictReader(io.StringIO(generate.generate(doc, 500, 7))))
        finally:
            generate.axis_levels = original
        base = persona_spec.BASE_HEADER
        self.assertEqual([[r[c] for c in base] for r in with_axes], [[r[c] for c in base] for r in fake])
        self.assertTrue(all(r["ax_intent"] in ("0", "1", "2") for r in with_axes))


class ValidateTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)

    def test_generated_csv_passes(self):
        csv_path = write_temp(self.directory.name, "ok.csv", generate.generate(load_fixture(), 3000, 20261113))
        code, output = run_validate(FIXTURE, [csv_path])
        self.assertEqual(code, 0, output)

    def broken_json(self, mutate):
        doc = copy.deepcopy(load_fixture())
        mutate(doc)
        path = write_temp(self.directory.name, "broken.json", json.dumps(doc, ensure_ascii=False))
        return run_validate(path)

    def test_catches_share_sum(self):
        code, output = self.broken_json(lambda d: d["personas"][0].update(share=0.3))
        self.assertEqual(code, 1)
        self.assertIn("share 합", output)

    def test_catches_seats_out_of_range(self):
        def mutate(doc):
            doc["personas"][0]["fields"]["seats"] = {"dist": "const", "value": 5}
        code, output = self.broken_json(mutate)
        self.assertEqual(code, 1)
        self.assertIn("seats", output)

    def test_catches_unknown_zone(self):
        def mutate(doc):
            doc["personas"][0]["fields"]["zone"] = {"dist": "const", "value": "FLOOR"}
        code, output = self.broken_json(mutate)
        self.assertEqual(code, 1)
        self.assertIn("zone", output)

    def test_catches_weights_and_missing_field(self):
        def mutate(doc):
            doc["personas"][0]["fields"]["seats"]["weights"] = [0.5]
            del doc["personas"][1]["fields"]["think_ms"]
        code, output = self.broken_json(mutate)
        self.assertEqual(code, 1)
        self.assertIn("길이가 다릅니다", output)
        self.assertIn("빠진 필드", output)

    def test_catches_bad_csv(self):
        text = generate.generate(load_fixture(), 100, 5).replace("\n1,", "\n999,", 1)
        csv_path = write_temp(self.directory.name, "bad.csv", text)
        code, output = run_validate(FIXTURE, [csv_path])
        self.assertEqual(code, 1)
        self.assertIn("user_no", output)

    def test_catches_value_outside_persona_distribution(self):
        lines = generate.generate(load_fixture(), 100, 5).splitlines()
        cells = lines[1].split(",")
        cells[persona_spec.CSV_HEADER.index("seats")] = "4"
        cells[persona_spec.CSV_HEADER.index("persona")] = "pair_buyer"
        lines[1] = ",".join(cells)
        csv_path = write_temp(self.directory.name, "range.csv", "\n".join(lines) + "\n")
        code, output = run_validate(FIXTURE, [csv_path])
        self.assertEqual(code, 1)
        self.assertIn("seats", output)

    def test_base_header_csv_passes(self):
        lines = generate.generate(load_fixture(), 3000, 20261113).splitlines()
        width = len(persona_spec.BASE_HEADER)
        text = "\n".join(",".join(line.split(",")[:width]) for line in lines) + "\n"
        csv_path = write_temp(self.directory.name, "base.csv", text)
        code, output = run_validate(FIXTURE, [csv_path])
        self.assertEqual(code, 0, output)

    def test_catches_hand_edited_axis_level(self):
        lines = generate.generate(load_fixture(), 100, 5).splitlines()
        cells = lines[1].split(",")
        column = persona_spec.CSV_HEADER.index("ax_demand")
        cells[column] = "2" if cells[column] != "2" else "0"
        lines[1] = ",".join(cells)
        csv_path = write_temp(self.directory.name, "axis.csv", "\n".join(lines) + "\n")
        code, output = run_validate(FIXTURE, [csv_path])
        self.assertEqual(code, 1)
        self.assertIn("ax_demand", output)


class CompareTest(unittest.TestCase):
    def test_report_has_tables(self):
        header = "metric_name,timestamp,metric_value,check,error,error_code,expected_response,group,method,name,proto,scenario,service,status,subproto,tls_version,url,extra_tags,metadata"
        rows = [
            "http_reqs,100,1.0,,,,true,,POST,n,,s,,201,,,u,api=hold,",
            "http_reqs,105,1.0,,,,true,,POST,n,,s,,409,,,u,api=hold,",
            "http_reqs,105,1.0,,,,true,,GET,n,,s,,200,,,u,api=setup,",
            "step_duration,100,10.0,,,,,,,,,s,,,,,,step=hold&persona=a&zone=VIP&code=201,",
            "step_duration,105,30.0,,,,,,,,,s,,,,,,step=hold&persona=a&zone=VIP&code=409,",
            "o1_outcomes,105,1.0,,,,,,,,,s,,,,,,persona=a&outcome=gave_up,",
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "raw.csv.gz")
            with gzip.open(path, "wt", encoding="utf-8") as file:
                file.write("\n".join([header] + rows) + "\n")
            run = compare.Run(path)
            report = compare.build_report(run, run)
        self.assertEqual(run.request_count, 2)
        self.assertEqual(run.seconds, 5)
        self.assertIn("50.0%", report)
        self.assertIn("gave_up", report)
        self.assertIn("20.0", report)
        self.assertEqual(run.peak("hold"), (1, 0))
        self.assertIn("## 6. API별 초당 최대 요청 수", report)
        self.assertIn("## 7. 오픈 뒤 1분 구간별 선점 결과", report)
        self.assertEqual(dict(run.hold_by_zone["VIP"]), {"201": 1, "409": 1})
        self.assertIn("| VIP | 2 | 1 (50.0%) |", report)

    def test_waves_count_from_first_queue_enter(self):
        header = "metric_name,timestamp,metric_value,check,error,error_code,expected_response,group,method,name,proto,scenario,service,status,subproto,tls_version,url,extra_tags,metadata"
        rows = [
            "http_reqs,1000,1.0,,,,true,,GET,n,,s,,200,,,u,api=concert_detail,",
            "http_reqs,1600,1.0,,,,true,,POST,n,,s,,201,,,u,api=queue_enter,",
            "http_reqs,1601,1.0,,,,true,,POST,n,,s,,201,,,u,api=queue_enter,",
            "http_reqs,1630,1.0,,,,true,,POST,n,,s,,201,,,u,api=hold,",
            "http_reqs,1930,1.0,,,,true,,POST,n,,s,,201,,,u,api=hold,",
            "http_reqs,1931,1.0,,,,true,,POST,n,,s,,409,,,u,api=hold,",
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "raw.csv")
            with open(path, "w", encoding="utf-8", newline="") as file:
                file.write("\n".join([header] + rows) + "\n")
            run = compare.Run(path)
            report = compare.build_report(run, run)
        self.assertEqual(run.open_second, 1600)
        self.assertEqual(run.peak("queue_enter"), (1, 600))
        # 오픈 뒤 30초의 선점은 0~60 구간, 330·331초의 선점은 300~360 구간이다
        self.assertIn("| 0~60 | 1 | 0 | 1 | 0 |", report)
        self.assertIn("| 300~360 | 1 | 1 | 1 | 1 |", report)

    def test_axis_tables(self):
        header = "metric_name,timestamp,metric_value,check,error,error_code,expected_response,group,method,name,proto,scenario,service,status,subproto,tls_version,url,extra_tags,metadata"
        axes = "ax_arrival=2&ax_entry=1&ax_demand=2&ax_speed=0&ax_persistence=2&ax_intent=2"
        rows = [
            f"step_duration,100,10.0,,,,,,,,,s,,,,,,step=hold&persona=a&{axes}&zone=VIP&code=409,",
            f"step_duration,101,10.0,,,,,,,,,s,,,,,,step=hold&persona=a&{axes}&zone=VIP&code=201,",
            f"o1_outcomes,105,1.0,,,,,,,,,s,,,,,,persona=a&{axes}&outcome=confirmed,",
            "o1_outcomes,105,1.0,,,,,,,,,s,,,,,,persona=b&outcome=confirmed,",
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "raw.csv")
            with open(path, "w", encoding="utf-8", newline="") as file:
                file.write("\n".join([header] + rows) + "\n")
            run = compare.Run(path)
            report = compare.build_report(run, run)
        self.assertEqual(dict(run.axis_hold[("ax_demand", "2")]), {"409": 1, "201": 1})
        self.assertEqual(dict(run.axis_outcomes[("ax_speed", "0")]), {"confirmed": 1})
        self.assertIn("| 좌석 욕심 | 높음 | 1 | 1 (100.0%) | 2 | 1 (50.0%) |", report)
        self.assertIn("| 좌석 욕심 높음 | 0/0 (-) | 0/0 (-) | 1/2 (50.0%) |", report)

    def test_axis_tables_without_tags(self):
        header = "metric_name,timestamp,metric_value,check,error,error_code,expected_response,group,method,name,proto,scenario,service,status,subproto,tls_version,url,extra_tags,metadata"
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "raw.csv")
            with open(path, "w", encoding="utf-8", newline="") as file:
                file.write(header + "\no1_outcomes,105,1.0,,,,,,,,,s,,,,,,persona=uniform&outcome=confirmed,\n")
            run = compare.Run(path)
            report = compare.build_report(run, run)
        self.assertIn("축 태그(`ax_*`)가 없는 원자료", report)


class CommittedCsvTest(unittest.TestCase):
    """저장소에 커밋한 측정용 CSV가 LLM 분포 JSON과 시드로 다시 만든 결과와 바이트 단위로 같은지 본다(4조 방식).
    다르면 누군가 CSV나 JSON을 손으로 고쳤거나 생성 코드가 바뀐 것이다."""

    def test_committed_csv_matches_regenerated(self):
        repo = os.path.abspath(os.path.join(HERE, "..", ".."))
        personas = os.path.join(HERE, "o1-personas.json")
        committed = os.path.join(repo, "loadtest", "k6", "data", "o1-synthetic-3000.csv")
        with open(personas, encoding="utf-8") as file:
            doc = json.load(file)
        with open(committed, encoding="utf-8", newline="") as file:
            expected = file.read()
        self.assertEqual(generate.generate(doc, 3000, 20261113), expected)


if __name__ == "__main__":
    unittest.main()

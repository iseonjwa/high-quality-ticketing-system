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
import persona_spec_v2  # noqa: E402
import validate  # noqa: E402

FIXTURE = os.path.join(HERE, "testdata", "personas-fixture.json")
PERSONAS_V2 = os.path.join(HERE, "o1-personas-v2.json")


def load_fixture():
    with open(FIXTURE, encoding="utf-8") as file:
        return json.load(file)


def load_v2():
    with open(PERSONAS_V2, encoding="utf-8") as file:
        return json.load(file)


def write_temp(directory, name, text):
    path = os.path.join(directory, name)
    with open(path, "w", encoding="utf-8", newline="") as file:
        file.write(text)
    return path


def run_validate(personas_path, csv_paths=(), spec=None):
    out = io.StringIO()
    argv = sys.argv
    sys.argv = ["validate.py", "--personas", personas_path]
    for path in csv_paths:
        sys.argv += ["--csv", path]
    if spec is not None:
        sys.argv += ["--spec", spec]
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

    def test_soldout_and_follow_up_sections(self):
        header = "metric_name,timestamp,metric_value,check,error,error_code,expected_response,group,method,name,proto,scenario,service,status,subproto,tls_version,url,extra_tags,metadata"
        rows = [
            "http_reqs,1000,1.0,,,,true,,POST,n,,s,,201,,,u,api=queue_enter,",
            "http_reqs,1010,1.0,,,,true,,GET,n,,s,,200,,,u,api=seats,",
            "http_reqs,1020,1.0,,,,true,,GET,n,,s,,200,,,u,api=seats,",
            "http_reqs,1020,1.0,,,,true,,GET,n,,s,,200,,,u,api=queue_poll,",
            "http_reqs,1050,1.0,,,,true,,GET,n,,s,,200,,,u,api=seats,",
            "o1_outcomes,1010,1.0,,,,,,,,,s,,,,,,persona=a&outcome=confirmed,",
            "o1_outcomes,1030,1.0,,,,,,,,,s,,,,,,persona=a&outcome=soldout,",
            "o1_outcomes,1020,1.0,,,,,,,,,s,,,,,,persona=b&outcome=soldout_seen,",
            "o1_outcomes,1060,1.0,,,,,,,,,s,,,,,,persona=b&outcome=soldout_refresh,",
            "o1_outcomes,1070,1.0,,,,,,,,,s,,,,,,persona=c&outcome=rebook_limit,",
            "o1_outcomes,1080,1.0,,,,,,,,,s,,,,,,persona=d&outcome=canceled,",
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "raw.csv")
            with open(path, "w", encoding="utf-8", newline="") as file:
                file.write("\n".join([header] + rows) + "\n")
            run = compare.Run(path)
            report = compare.build_report(run, run)
        # 첫 매진은 soldout(1030)보다 이른 soldout_seen(1020)이다
        self.assertEqual(run.soldout_second, 1020)
        # 덧붙은 결과는 사용자 결과표(3절)에 들어가지 않는다
        self.assertEqual(dict(run.outcomes), {"confirmed": 1, "soldout": 1, "soldout_refresh": 1})
        self.assertEqual(dict(run.follow_ups), {"soldout_seen": 1, "rebook_limit": 1, "canceled": 1})
        self.assertEqual(run.requests_after("seats", 1020), 2)
        self.assertIn("## 11. 첫 매진 시각과 매진 뒤 요청", report)
        self.assertIn("| Synthetic | 20 | 3 / 5 (60.0%) | 30 |", report)
        self.assertIn("| seats | 2 (66.7%) | 2 (66.7%) |", report)
        self.assertIn("| rebook_limit | 1 | 1 |", report)
        self.assertNotIn("| rebook_limit | 1 (", report)

    def test_v1_raw_has_no_soldout_and_keeps_old_sections(self):
        header = "metric_name,timestamp,metric_value,check,error,error_code,expected_response,group,method,name,proto,scenario,service,status,subproto,tls_version,url,extra_tags,metadata"
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "raw.csv")
            with open(path, "w", encoding="utf-8", newline="") as file:
                file.write(header + "\nhttp_reqs,100,1.0,,,,true,,POST,n,,s,,201,,,u,api=hold,\n"
                           "o1_outcomes,105,1.0,,,,,,,,,s,,,,,,persona=a&outcome=confirmed,\n")
            run = compare.Run(path)
            report = compare.build_report(run, run)
        self.assertIsNone(run.soldout_second)
        self.assertIn("| 균일 | 매진 없음 | - | - |", report)
        self.assertIn("덧붙은 결과가 없다", report)
        # 1~10절 제목과 순서는 그대로다
        titles = [line for line in report.splitlines() if line.startswith("## ")]
        self.assertEqual([t.split(".")[0] for t in titles], [f"## {i}" for i in range(1, 13)])


class SpecV2Test(unittest.TestCase):
    """v2 규격(ISE-289)은 v1 필드 12개를 그대로 두고 뒤에 4개를 붙인다. 축은 v1과 같다."""

    def test_v2_extends_v1_without_changing_it(self):
        self.assertEqual(len(persona_spec.FIELDS), 12)
        self.assertEqual(persona_spec_v2.FIELDS[:12], persona_spec.FIELDS)
        self.assertEqual(persona_spec_v2.NEW_FIELDS, ["cancel_after_confirm_s", "rebook_seats", "after_soldout", "sessions"])
        self.assertEqual(persona_spec_v2.BASE_HEADER[:len(persona_spec.BASE_HEADER)], persona_spec.BASE_HEADER)
        self.assertEqual(persona_spec_v2.AXIS_COLUMNS, persona_spec.AXIS_COLUMNS)
        self.assertNotIn("sessions", persona_spec.RANGES)
        self.assertNotIn("after_soldout", persona_spec.CHOICES)

    def test_cancel_rounding_rule(self):
        cases = {0: 0, 1: 30, 29: 30, 30: 30, 600: 600}
        for value, expected in cases.items():
            self.assertEqual(persona_spec_v2.fix_field("cancel_after_confirm_s", value), expected, value)
        # v1 규칙은 그대로 따른다
        self.assertEqual(persona_spec_v2.fix_field("refresh_s", 2), 3)
        self.assertEqual(persona_spec_v2.fix_field("queue_patience_s", 10), 30)

    def test_new_fields_do_not_change_axis_scores(self):
        row = dict(HIGHEST, cancel_after_confirm_s=600, rebook_seats=3, after_soldout="refresh", sessions=2)
        self.assertEqual(persona_spec_v2.axis_scores(row), persona_spec.axis_scores(HIGHEST))


class GenerateV2Test(unittest.TestCase):
    def test_v1_output_is_same_with_or_without_explicit_v1_spec(self):
        doc = load_fixture()
        self.assertEqual(generate.generate(doc, 300, 7), generate.generate(doc, 300, 7, persona_spec))

    def test_v2_header_and_determinism(self):
        doc = load_v2()
        text = generate.generate(doc, 600, 3, persona_spec_v2)
        self.assertEqual(text, generate.generate(doc, 600, 3, persona_spec_v2))
        self.assertEqual(text.splitlines()[0], ",".join(persona_spec_v2.CSV_HEADER))

    def test_v2_rows_follow_persona_rules(self):
        rows = list(csv.DictReader(io.StringIO(generate.generate(load_v2(), 3000, 20261116, persona_spec_v2))))
        by_persona = {}
        for row in rows:
            by_persona.setdefault(row["persona"], []).append(row)
        # v1에서 옮긴 페르소나는 새 행동을 하지 않는다
        for row in by_persona["office_pc_fan"]:
            self.assertEqual((row["cancel_after_confirm_s"], row["rebook_seats"], row["after_soldout"], row["sessions"]),
                             ("0", "0", "leave", "1"))
        self.assertTrue(all(row["after_soldout"] == "refresh" for row in by_persona["resale_hunter"]))
        self.assertTrue(all(60 <= int(row["cancel_after_confirm_s"]) <= 600 for row in by_persona["regret_canceler"]))
        self.assertEqual({row["rebook_seats"] for row in by_persona["extra_buyer"]}, {"2", "3"})
        self.assertTrue(all(row["sessions"] == "2" for row in by_persona["two_sessions"]))
        self.assertEqual(len(by_persona["two_sessions"]), 60)

    def test_v2_shares_sum_to_one_and_new_basis_says_assumption(self):
        doc = load_v2()
        self.assertEqual(doc["version"], 2)
        self.assertAlmostEqual(sum(p["share"] for p in doc["personas"]), 1.0)
        new = [p for p in doc["personas"] if p["code"] in ("resale_hunter", "regret_canceler", "extra_buyer", "two_sessions")]
        self.assertEqual(len(new), 4)
        self.assertTrue(all("가정" in p["basis"] for p in new))

    def test_v1_personas_are_carried_over_unchanged(self):
        with open(os.path.join(HERE, "o1-personas.json"), encoding="utf-8") as file:
            v1 = {p["code"]: p for p in json.load(file)["personas"]}
        v2 = {p["code"]: p for p in load_v2()["personas"]}
        for code, persona in v1.items():
            carried = v2[code]
            for key in ("name_ko", "description", "basis"):
                self.assertEqual(carried[key], persona[key], (code, key))
            self.assertEqual({k: carried["fields"][k] for k in persona_spec.FIELDS}, persona["fields"], code)


class ValidateV2Test(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)

    def broken_v2(self, mutate):
        doc = copy.deepcopy(load_v2())
        mutate(doc)
        path = write_temp(self.directory.name, "broken-v2.json", json.dumps(doc, ensure_ascii=False))
        return run_validate(path, spec="v2")

    def set_field(self, code, field, dist):
        def mutate(doc):
            next(p for p in doc["personas"] if p["code"] == code)["fields"][field] = dist
        return mutate

    def test_generated_v2_csv_passes(self):
        csv_path = write_temp(self.directory.name, "v2.csv", generate.generate(load_v2(), 3000, 20261116, persona_spec_v2))
        code, output = run_validate(PERSONAS_V2, [csv_path], spec="v2")
        self.assertEqual(code, 0, output)

    def test_v1_spec_rejects_v2_json(self):
        code, output = run_validate(PERSONAS_V2)
        self.assertEqual(code, 1)
        self.assertIn("모르는 필드", output)

    def test_v2_spec_rejects_v1_json(self):
        code, output = run_validate(FIXTURE, spec="v2")
        self.assertEqual(code, 1)
        self.assertIn("version 2", output)

    def test_catches_rebook_over_three(self):
        code, output = self.broken_v2(self.set_field("extra_buyer", "rebook_seats", {"dist": "const", "value": 4}))
        self.assertEqual(code, 1)
        self.assertIn("rebook_seats", output)

    def test_catches_unknown_after_soldout(self):
        code, output = self.broken_v2(self.set_field("resale_hunter", "after_soldout", {"dist": "const", "value": "wait"}))
        self.assertEqual(code, 1)
        self.assertIn("after_soldout", output)

    def test_catches_three_sessions(self):
        code, output = self.broken_v2(self.set_field("two_sessions", "sessions", {"dist": "const", "value": 3}))
        self.assertEqual(code, 1)
        self.assertIn("sessions", output)

    def test_catches_cancel_between_one_and_twenty_nine(self):
        code, output = self.broken_v2(self.set_field("regret_canceler", "cancel_after_confirm_s", {"dist": "const", "value": 10}))
        self.assertEqual(code, 1)
        self.assertIn("cancel_after_confirm_s", output)

    def test_catches_continuous_sessions(self):
        code, output = self.broken_v2(self.set_field("two_sessions", "sessions", {"dist": "uniform", "min": 1, "max": 2}))
        self.assertEqual(code, 1)
        self.assertIn("정수 필드에는", output)

    def test_catches_bad_v2_csv_value(self):
        lines = generate.generate(load_v2(), 300, 5, persona_spec_v2).splitlines()
        cells = lines[1].split(",")
        cells[persona_spec_v2.CSV_HEADER.index("cancel_after_confirm_s")] = "15"
        lines[1] = ",".join(cells)
        csv_path = write_temp(self.directory.name, "bad-v2.csv", "\n".join(lines) + "\n")
        code, output = run_validate(PERSONAS_V2, [csv_path], spec="v2")
        self.assertEqual(code, 1)
        self.assertIn("cancel_after_confirm_s=15", output)

    def test_v1_csv_header_is_rejected_by_v2(self):
        csv_path = write_temp(self.directory.name, "v1.csv", generate.generate(load_fixture(), 50, 1))
        code, output = run_validate(PERSONAS_V2, [csv_path], spec="v2")
        self.assertEqual(code, 1)
        self.assertIn("머리말이 다릅니다", output)


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

    def test_committed_v2_csvs_match_regenerated(self):
        repo = os.path.abspath(os.path.join(HERE, "..", ".."))
        doc = load_v2()
        for users in (3000, 6000, 12000):
            committed = os.path.join(repo, "loadtest", "k6", "data", f"o1-synthetic-v2-{users}.csv")
            with open(committed, encoding="utf-8", newline="") as file:
                expected = file.read()
            self.assertEqual(generate.generate(doc, users, 20261116, persona_spec_v2), expected, users)


if __name__ == "__main__":
    unittest.main()

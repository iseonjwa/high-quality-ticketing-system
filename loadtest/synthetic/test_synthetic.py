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


class CompareTest(unittest.TestCase):
    def test_report_has_tables(self):
        header = "metric_name,timestamp,metric_value,check,error,error_code,expected_response,group,method,name,proto,scenario,service,status,subproto,tls_version,url,extra_tags,metadata"
        rows = [
            "http_reqs,100,1.0,,,,true,,POST,n,,s,,201,,,u,api=hold,",
            "http_reqs,105,1.0,,,,true,,POST,n,,s,,409,,,u,api=hold,",
            "http_reqs,105,1.0,,,,true,,GET,n,,s,,200,,,u,api=setup,",
            "step_duration,100,10.0,,,,,,,,,s,,,,,,step=hold&persona=a,",
            "step_duration,105,30.0,,,,,,,,,s,,,,,,step=hold&persona=a,",
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


if __name__ == "__main__":
    unittest.main()

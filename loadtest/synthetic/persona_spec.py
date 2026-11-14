"""페르소나 JSON과 CSV의 공통 규격. generate.py와 validate.py가 같이 쓴다.

정본은 o1-prompt.md 3절이다. 필드 범위를 바꾸면 프롬프트와 이 파일을 함께 고친다.
"""
import math

# CSV 열 순서(프롬프트 3-2절). LLM이 쓴 표본 CSV는 이 열만 갖는다
BASE_HEADER = [
    "user_no", "persona", "arrive_s", "refresh_s", "enter_delay_ms", "enter_clicks", "seats", "zone",
    "think_ms", "max_retry", "on_conflict", "queue_patience_s", "abandon_after_hold", "pay_delay_s",
]

# 필드 순서. 시드 하나로 뽑는 순서이기도 하므로 바꾸면 같은 시드의 결과가 달라진다
FIELDS = [
    "arrive_s", "refresh_s", "enter_delay_ms", "enter_clicks", "seats", "zone",
    "think_ms", "max_retry", "on_conflict", "queue_patience_s", "abandon_after_hold", "pay_delay_s",
]

# 숫자 필드의 허용 범위(양끝 포함)
RANGES = {
    "arrive_s": (-600, 600),
    "refresh_s": (0, 60),
    "enter_delay_ms": (0, 60000),
    "enter_clicks": (0, 5),
    "seats": (1, 4),
    "think_ms": (300, 30000),
    "max_retry": (0, 5),
    "queue_patience_s": (0, 900),
    "abandon_after_hold": (0, 1),
    "pay_delay_s": (3, 280),
}

# 문자열 필드의 허용 값
CHOICES = {
    "zone": ["VIP", "R", "S", "ANY"],
    "on_conflict": ["same_zone", "any_zone", "give_up"],
}

# 프롬프트가 choice·const·bernoulli로만 주라고 한 정수 필드
DISCRETE_ONLY = ["enter_clicks", "seats", "max_retry", "abandon_after_hold"]

DIST_KEYS = {
    "const": {"value"},
    "uniform": {"min", "max"},
    "normal": {"mean", "sd", "min", "max"},
    "exponential": {"mean", "min", "max"},
    "choice": {"values", "weights"},
    "bernoulli": {"p"},
}

PERSONA_KEYS = {"code", "name_ko", "share", "description", "fields", "basis"}

TOLERANCE_FLOOR = 0.01
MIN_PERSONA_FOR_RATIO = 30


def is_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def round_half_up(value):
    return int(math.floor(value + 0.5))


def fix_field(field, value):
    """반올림한 정수에 규칙을 적용한다. refresh_s는 0 또는 3~60, queue_patience_s는 0 또는 30~900이다."""
    if field == "refresh_s" and 1 <= value <= 2:
        return 3
    if field == "queue_patience_s" and 1 <= value <= 29:
        return 30
    return value


def clamp_field(field, value):
    low, high = RANGES[field]
    return min(max(value, low), high)


def ratio_tolerance(p, n):
    return max(TOLERANCE_FLOOR, 3 * math.sqrt(p * (1 - p) / n))


# ---------- 행동 축 점수 ----------
# 필드 12개를 행동 축 6개로 묶어 사용자마다 축별 점수(0~1)와 3구간(0 낮음, 1 중간, 2 높음)을 붙인다.
# 행동은 바꾸지 않는다. 이미 뽑은 값에서 계산만 하므로 난수를 쓰지 않고, 기존 12열은 그대로다.
# 설계: docs/design/infra/synthetic-data.md 4절. 0점·1점 기준은 LLM이 아니라 사람이 정한 초안이다.

def _linear(value, zero, one):
    """value가 zero면 0, one이면 1. 범위 밖은 0·1로 자른다."""
    return min(1.0, max(0.0, (value - zero) / (one - zero)))


def _log_scale(value, zero, one):
    # Note: 시간 필드는 수백 ms와 수십 초가 섞여 직선으로 재면 짧은 쪽 차이가 모두 1 근처로 뭉친다. 그래서 로그로 잰다
    return _linear(math.log(max(value, min(zero, one))), math.log(zero), math.log(one))


ZONE_SCORE = {"VIP": 1.0, "R": 0.5, "S": 0.25, "ANY": 0.0}
CONFLICT_SCORE = {"same_zone": 1.0, "any_zone": 0.5, "give_up": 0.0}

# (축 코드, 한국어 이름, 점수 성분 [(필드, 행 → 점수 또는 None)]). CSV 열 이름은 ax_<축 코드>다. None인 성분은 평균에서 뺀다.
AXES = [
    ("arrival", "도착 이른 정도", [
        ("arrive_s", lambda r: _linear(r["arrive_s"], 600, -600)),
        # 0은 새로고침하지 않음이라 가장 낮은 점수다. 그 밖에는 간격이 짧을수록 높다
        ("refresh_s", lambda r: 0.0 if r["refresh_s"] == 0 else _linear(r["refresh_s"], 60, 3)),
    ]),
    ("entry", "진입 공격성", [
        ("enter_delay_ms", lambda r: _log_scale(r["enter_delay_ms"], 30000, 200)),
        # 진입하지 않는 사람(0)의 횟수는 구매 의지 축이 본다. 여기서는 진입한 사람의 연타 정도만 본다
        ("enter_clicks", lambda r: None if r["enter_clicks"] == 0 else _linear(r["enter_clicks"], 1, 4)),
    ]),
    ("demand", "좌석 욕심", [
        ("seats", lambda r: _linear(r["seats"], 1, 4)),
        ("zone", lambda r: ZONE_SCORE[r["zone"]]),
    ]),
    ("speed", "결정 속도", [
        ("think_ms", lambda r: _log_scale(r["think_ms"], 30000, 300)),
        ("pay_delay_s", lambda r: _log_scale(r["pay_delay_s"], 280, 3)),
    ]),
    ("persistence", "집착도", [
        ("max_retry", lambda r: _linear(r["max_retry"], 0, 5)),
        ("on_conflict", lambda r: CONFLICT_SCORE[r["on_conflict"]]),
        # 0은 끝까지 기다림이라 가장 높은 점수다. 그 밖에는 최소값 30초가 0점이다
        ("queue_patience_s", lambda r: 1.0 if r["queue_patience_s"] == 0 else _linear(r["queue_patience_s"], 30, 900)),
    ]),
    ("intent", "구매 의지", [
        ("enter_clicks", lambda r: 0.0 if r["enter_clicks"] == 0 else 1.0),
        ("abandon_after_hold", lambda r: 1.0 - r["abandon_after_hold"]),
    ]),
]

# 필드 하나는 축 하나만 정한다. 예외는 enter_clicks 하나다(구매 의지가 진입 여부를, 진입 공격성이 횟수를 본다)
SHARED_FIELDS = {"enter_clicks"}
AXIS_COLUMNS = ["ax_" + code for code, _, _ in AXES]
AXIS_LEVELS = ["낮음", "중간", "높음"]
CSV_HEADER = BASE_HEADER + AXIS_COLUMNS


def axis_scores(row):
    """숫자 필드는 int, 문자열 필드는 str인 행 하나의 축별 점수(0~1). AXES 순서."""
    scores = []
    for _, _, parts in AXES:
        values = [value for value in (score(row) for _, score in parts) if value is not None]
        scores.append(sum(values) / len(values))
    return scores


def axis_level(score):
    """[0, 1/3) 낮음 0, [1/3, 2/3) 중간 1, [2/3, 1] 높음 2"""
    return min(len(AXIS_LEVELS) - 1, int(math.floor(score * len(AXIS_LEVELS))))


def axis_levels(row):
    return [axis_level(score) for score in axis_scores(row)]


def typed_row(row):
    """CSV 문자열 행을 axis_scores가 받는 형태로 바꾼다."""
    return {field: row[field] if field in CHOICES else int(row[field]) for field in FIELDS}

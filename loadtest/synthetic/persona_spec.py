"""페르소나 JSON과 CSV의 공통 규격. generate.py와 validate.py가 같이 쓴다.

정본은 o1-prompt.md 3절이다. 필드 범위를 바꾸면 프롬프트와 이 파일을 함께 고친다.
"""
import math

# CSV 열 순서(프롬프트 3-2절)
CSV_HEADER = [
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

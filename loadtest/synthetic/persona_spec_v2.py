"""페르소나 v2 규격. v1(persona_spec.py)의 필드 12개 뒤에 확정 뒤·매진 뒤 행동 필드 4개를 더한다(ISE-289).

v1 파일과 v1 CSV는 그대로 둔다. generate.py·validate.py는 --spec v2일 때만 이 모듈을 쓴다.
행동 축 6개는 v1과 같다. 새 필드 4개는 어느 축에도 넣지 않는다(축 점수와 compare.py 9·10절이 v1과 같게 나온다).

새 필드(k6 o1-open-peak.js가 이 열이 있을 때만 새 행동을 한다)
  cancel_after_confirm_s  확정 뒤 이 초가 지나면 취소한다. 0이면 취소하지 않는다. 0 또는 30~900
  rebook_seats            확정 뒤 같은 회차에서 대기열부터 다시 서서 이 매수를 더 산다. 0이면 추가 예매하지 않는다. 0~3
  after_soldout           좌석 조회에서 매진을 본 뒤 행동. leave 바로 나감, refresh 좌석 조회 반복(취소표 노리기), stay 상세 화면 새로고침
  sessions                같은 계정으로 동시에 선점하는 세션 수. 1 또는 2
"""
import persona_spec as v1
from persona_spec import (  # noqa: F401  generate.py·validate.py가 spec.<이름>으로 쓴다
    AXES, AXIS_COLUMNS, AXIS_LEVELS, DIST_KEYS, MIN_PERSONA_FOR_RATIO, PERSONA_KEYS, SHARED_FIELDS, TOLERANCE_FLOOR,
    axis_level, is_number, ratio_tolerance, round_half_up,
)

VERSION = 2

NEW_FIELDS = ["cancel_after_confirm_s", "rebook_seats", "after_soldout", "sessions"]

# v1 필드 순서를 그대로 두고 뒤에 붙인다. 시드 하나로 뽑는 순서이기도 하다
FIELDS = v1.FIELDS + NEW_FIELDS
BASE_HEADER = v1.BASE_HEADER + NEW_FIELDS
CSV_HEADER = BASE_HEADER + AXIS_COLUMNS

RANGES = dict(v1.RANGES, cancel_after_confirm_s=(0, 900), rebook_seats=(0, 3), sessions=(1, 2))
CHOICES = dict(v1.CHOICES, after_soldout=["leave", "refresh", "stay"])
DISCRETE_ONLY = v1.DISCRETE_ONLY + ["rebook_seats", "sessions"]

# Note: 확정까지 수 초~수십 초가 걸리므로 취소가 확정 직후 1~29초에 몰리지 않게 최소값을 30초로 둔다.
# 0은 취소하지 않음이라 따로 둔다(refresh_s·queue_patience_s와 같은 방식)
CANCEL_MIN_S = 30


def fix_field(field, value):
    """v1 규칙에 cancel_after_confirm_s(0 또는 30~900)를 더한다."""
    if field == "cancel_after_confirm_s" and 1 <= value < CANCEL_MIN_S:
        return CANCEL_MIN_S
    return v1.fix_field(field, value)


def clamp_field(field, value):
    low, high = RANGES[field]
    return min(max(value, low), high)


def axis_scores(row):
    return v1.axis_scores(row)


def axis_levels(row):
    # 축은 v1 필드만 본다. 새 필드가 있어도 점수는 같다
    return v1.axis_levels(row)


def typed_row(row):
    """CSV 문자열 행을 axis_scores가 받는 형태로 바꾼다. 새 필드도 같은 규칙으로 바꾼다."""
    return {field: row[field] if field in CHOICES else int(row[field]) for field in FIELDS}


def check_doc(doc):
    """v2 JSON에만 있는 문서 단위 규칙. 문제 문자열 목록."""
    if doc.get("version") != VERSION:
        return [f"--spec v2에는 version {VERSION} JSON이 필요합니다(지금 {doc.get('version')!r})"]
    return []

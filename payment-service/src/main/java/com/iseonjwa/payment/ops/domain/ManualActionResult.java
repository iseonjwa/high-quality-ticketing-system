package com.iseonjwa.payment.ops.domain;

/** Ops 명령 {@code --apply} 시도의 결과. 0행·근거 불일치·통제 위반·해시 불일치는 모두 REJECTED다. */
public enum ManualActionResult {
    APPLIED, REJECTED
}

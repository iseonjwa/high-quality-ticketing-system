package com.iseonjwa.payment.reconciliation.domain;

import java.util.List;

/** 대사 규칙 번호(ADR 0029 규칙표). 여덟 개 모두 Gauge와 DB CHECK에 있고, 구현은 규칙마다 따로 더한다. */
public enum RuleCode {
    RC_1("RC-1"), RC_2("RC-2"), RC_3("RC-3"), RC_4("RC-4"), RC_5("RC-5"), RC_6("RC-6"), RC_7("RC-7"), RC_8("RC-8");

    private final String code;

    RuleCode(String code) {
        this.code = code;
    }

    /** DB와 지표 라벨에 쓰는 값. 예: {@code RC-7} */
    public String code() {
        return code;
    }

    public static List<String> codes() {
        return java.util.Arrays.stream(values()).map(RuleCode::code).toList();
    }
}

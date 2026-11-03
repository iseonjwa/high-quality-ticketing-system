package com.iseonjwa.payment.ops.application;

import java.util.List;
import java.util.Set;

/**
 * 읽기 전용 Ops 명령(inspect). 상태를 바꾸지 않으므로 dry-run·해시·4-eyes·감사가 없고 실행자 확인만 한다.
 */
public interface OpsQuery {
    String name();

    Set<String> options();

    /** 출력할 줄. 대상이 없으면 {@link OpsRejection}을 던진다. */
    List<String> report(OpsArguments arguments);
}

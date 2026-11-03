package com.iseonjwa.payment.ops.application;

import com.iseonjwa.payment.ops.domain.OpsTargetType;

/** 명령이 바꾸려는 대상. 계획을 만들기 전에도 인자만으로 정해져 거부 기록에 쓴다. */
public record OpsTarget(OpsTargetType type, long id) {
    @Override
    public String toString() {
        return type + " " + id;
    }
}

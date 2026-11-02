package com.iseonjwa.mockpg.fault;

/**
 * 요청 하나에 적용할 장애. {@link FaultInjector#claim}이 요청이 들어올 때 한 번 정해서 돌려주고, 요청은 끝날 때까지 이 값을 쓴다.
 *
 * @param delayMs {@link FaultMode#DELAY}일 때의 지연 시간. 그 밖에는 0이다
 */
public record Fault(FaultMode mode, long delayMs) {
    public static final Fault NONE = new Fault(FaultMode.NONE, 0);

    public boolean is(FaultMode expected) {
        return mode == expected;
    }
}

package com.iseonjwa.mockpg.ledger;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 현재 원장을 들고 있다. 초기화는 맵을 비우지 않고 원장을 새 객체로 바꾼다(mock-pg.md 2절).
 *
 * <p>Note: 요청 하나는 처음 얻은 원장만 끝까지 쓴다. 그래서 지연 중이던 옛 요청은 옛 원장에만 쓰고, 초기화 뒤의 새 원장을
 * 오염시키지 못한다.
 */
@Component
public class LedgerHolder {
    private final AtomicReference<Ledger> current = new AtomicReference<>(new Ledger());

    public Ledger current() {
        return current.get();
    }

    /**
     * 원장을 새 세대로 바꾼다. 테스트 사이 격리에 쓴다. HTTP로 여는 {@code POST /mock-pg/reset}은 ISE-133에서 더한다.
     */
    public void reset() {
        current.set(new Ledger());
    }
}

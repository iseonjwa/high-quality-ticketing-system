package com.iseonjwa.mockpg.billing;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 발급한 빌링키 목록(메모리). 자동결제 승인은 여기 있는 빌링키만 받는다. 서버를 다시 띄우면 비워진다.
 */
@Component
public class BillingKeyStore {
    private final Set<String> issued = ConcurrentHashMap.newKeySet();

    String issue() {
        var billingKey = "bk-" + UUID.randomUUID();
        issued.add(billingKey);
        return billingKey;
    }

    public boolean contains(String billingKey) {
        return issued.contains(billingKey);
    }
}

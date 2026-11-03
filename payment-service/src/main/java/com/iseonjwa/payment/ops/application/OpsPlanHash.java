package com.iseonjwa.payment.ops.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 계획 해시: 명령 이름·대상·현재 상태 스냅숏·PG 근거 요약·계획한 전이를 정규화한 문자열의 SHA-256(소문자 16진수 64자).
 * 실행자·승인자는 넣지 않는다. 승인자가 같은 계획을 보고 확인하는 것이 목적이라 사람이 바뀌어도 해시는 같아야 한다.
 */
public final class OpsPlanHash {
    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{64}");
    /** 계획을 만들 수 없고 확인 값도 형식이 틀릴 때 감사 기록에 쓰는 자리 채움 값 */
    public static final String NONE = "0".repeat(64);

    private OpsPlanHash() {}

    public static String of(String command, OpsPlan plan) {
        var canonical = String.join("\n",
                "command=" + normalize(command),
                "target=" + plan.target().type() + ":" + plan.target().id(),
                "snapshot=" + normalize(plan.snapshot()),
                "pgEvidence=" + normalize(plan.pgEvidence()),
                "transition=" + normalize(plan.transition()));
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", exception);
        }
    }

    public static boolean isWellFormed(String value) {
        return value != null && FORMAT.matcher(value).matches();
    }

    // 줄바꿈이 필드 경계를 흐리지 않게 공백으로 바꾼다
    private static String normalize(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }
}

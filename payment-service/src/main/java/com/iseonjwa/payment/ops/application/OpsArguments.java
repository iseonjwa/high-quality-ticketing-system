package com.iseonjwa.payment.ops.application;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code ops <명령> [--옵션=값 ...]} 형식의 인자. 명령은 한 단어(inspect)나 두 단어(outbox requeue)다.
 * 값 없는 옵션({@code --apply}, {@code --with-pg})은 스위치다.
 *
 * <p>Note: Spring Boot는 {@code --이름=값}을 같은 이름의 설정 속성으로도 올린다. 옵션 이름이 설정 키와 겹치면 앱 설정이
 * 바뀌므로 겹치지 않는 이름만 쓴다(operator, approver, apply, confirm, reason, payment-id 등).
 */
public final class OpsArguments {
    private final List<String> words;
    private final Map<String, List<String>> options;

    /**
     * @param words   옵션이 아닌 인자 전체. 첫 단어 {@code ops} 뒤가 명령 이름이다
     * @param options 옵션 이름(앞의 {@code --} 제외)과 값 목록
     */
    public OpsArguments(List<String> words, Map<String, List<String>> options) {
        this.words = List.copyOf(words);
        this.options = Map.copyOf(options);
    }

    /** 명령 이름. {@code ops}로 시작하지 않으면 사용법 오류다. */
    public String commandName() {
        if (words.isEmpty() || !words.get(0).equals("ops")) {
            throw new OpsUsageException("첫 인자는 ops여야 합니다");
        }
        if (words.size() < 2) {
            throw new OpsUsageException("명령이 없습니다");
        }
        return String.join(" ", words.subList(1, words.size()));
    }

    /** 허용한 옵션 이름 밖의 옵션이 있으면 사용법 오류다. 오타가 조용히 무시되지 않게 한다. */
    public void requireOnly(Set<String> allowed) {
        for (var name : options.keySet()) {
            if (!allowed.contains(name)) {
                throw new OpsUsageException("알 수 없는 옵션입니다: --" + name);
            }
        }
    }

    public boolean flag(String name) {
        if (!options.containsKey(name)) return false;
        var values = options.get(name);
        return values.isEmpty() || values.stream().allMatch(value -> value.equalsIgnoreCase("true"));
    }

    /** 옵션 값. 없거나 값이 비었으면 null이다. 같은 옵션이 여러 번 오면 사용법 오류다. */
    public String value(String name) {
        var values = options.get(name);
        if (values == null || values.isEmpty()) return null;
        if (values.size() > 1) {
            throw new OpsUsageException("--" + name + "이 여러 번 지정되었습니다");
        }
        var value = values.get(0);
        return value.isBlank() ? null : value.strip();
    }

    public String require(String name) {
        var value = value(name);
        if (value == null) throw new OpsUsageException("--" + name + "이 필요합니다");
        return value;
    }

    public Long longValue(String name) {
        var value = value(name);
        if (value == null) return null;
        try {
            var parsed = Long.parseLong(value);
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new OpsUsageException("--" + name + "은 1 이상의 정수여야 합니다: " + value);
        }
    }

    public long requireLong(String name) {
        var value = longValue(name);
        if (value == null) throw new OpsUsageException("--" + name + "이 필요합니다");
        return value;
    }
}

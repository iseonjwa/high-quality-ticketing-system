package com.iseonjwa.common.identity;

import java.util.Set;

public final class Roles {
    public static final String USER = "USER";
    public static final String ADMIN = "ADMIN";
    private static final Set<String> ALL = Set.of(USER, ADMIN);

    private Roles() {}

    public static boolean isValid(String role) {
        return role != null && ALL.contains(role);
    }
}

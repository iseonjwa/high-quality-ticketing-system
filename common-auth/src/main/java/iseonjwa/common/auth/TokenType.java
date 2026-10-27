package iseonjwa.common.auth;

// 토큰 종류마다 audience를 분리해 Refresh를 Access로 쓰는 것을 막는다.
public enum TokenType {
    ACCESS("access", "iseonjwa-api"),
    REFRESH("refresh", "user-service");

    public static final String TYPE_CLAIM = "token_type";
    public static final String ROLE_CLAIM = "role";

    private final String type;
    private final String audience;

    TokenType(String type, String audience) {
        this.type = type;
        this.audience = audience;
    }

    public String type() {
        return type;
    }

    public String audience() {
        return audience;
    }
}

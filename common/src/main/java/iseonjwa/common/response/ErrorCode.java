package iseonjwa.common.response;

public interface ErrorCode {
    int status();

    String code();

    String message();
}

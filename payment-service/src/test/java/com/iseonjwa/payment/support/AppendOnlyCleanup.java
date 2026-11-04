package com.iseonjwa.payment.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * append-only 테이블({@code payment_status_histories}, {@code manual_action_logs})의 테스트 데이터를 지우는 유일한 길이다.
 * 이 테이블들은 UPDATE·DELETE·TRUNCATE를 거부하는 트리거가 있어(V8) 일반 DELETE로는 지워지지 않는다.
 *
 * <p>Note: 정리 트랜잭션 안에서만 {@code SET LOCAL session_replication_role = replica}로 트리거를 건너뛴다. 트랜잭션이
 * 끝나면 설정이 사라지므로 다른 문장에 새지 않는다. 이 설정은 슈퍼유저만 할 수 있고(테스트 DB 계정 {@code test}는 슈퍼유저다)
 * FK 트리거도 건너뛴다. 그래서 호출하는 쪽은 지금처럼 자식(이력)부터 지우고 부모(결제)를 나중에 지운다. 운영 코드와
 * 운영 설정에는 이 방법을 쓰지 않는다.
 */
public final class AppendOnlyCleanup {
    private AppendOnlyCleanup() {}

    /** {@code payment_status_histories}에서 조건에 맞는 행을 지운다. {@code where}는 WHERE 뒤의 조건식이다. */
    public static void deleteHistories(JdbcTemplate jdbc, String where, Object... args) {
        delete(jdbc, "DELETE FROM payment_status_histories WHERE " + where, args);
    }

    public static void deleteAllHistories(JdbcTemplate jdbc) {
        delete(jdbc, "DELETE FROM payment_status_histories");
    }

    /** {@code manual_action_logs}에서 조건에 맞는 행을 지운다. */
    public static void deleteManualActionLogs(JdbcTemplate jdbc, String where, Object... args) {
        delete(jdbc, "DELETE FROM manual_action_logs WHERE " + where, args);
    }

    public static void deleteAllManualActionLogs(JdbcTemplate jdbc) {
        delete(jdbc, "DELETE FROM manual_action_logs");
    }

    private static void delete(JdbcTemplate jdbc, String sql, Object... args) {
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        transaction.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update(sql, args);
        });
    }
}

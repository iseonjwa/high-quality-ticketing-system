package com.iseonjwa.payment.pg.application;

/**
 * PG 호출 한 번에 대한 허가(Bulkhead 자리 하나와 Circuit Breaker의 호출 허가). 얻은 허가는 반드시 한 번 정산하거나
 * 반환해야 한다. 정산하지 않으면 Bulkhead가 줄어들고, HALF_OPEN의 시험 호출이 정산되지 않아 CB가 닫히지 않는다(0018).
 *
 * <p>정산은 처음 한 번만 효과가 있다. {@link #close()}는 아직 정산하지 않았으면 호출 결과로 세지 않고 반환한다.
 */
public interface PgPermit extends AutoCloseable {
    /** PG가 결과를 응답했다(승인·정상 거절·미발견). CB에 성공으로 센다. */
    void success();

    /** 결과를 알 수 없었다(타임아웃·연결 실패·5xx 등). CB에 실패로 센다. */
    void failure();

    /** PG를 부르지 않았거나 결과로 셀 수 없다(TX1 실패, 만료 재검사, 요청 오류). 호출 결과로 세지 않고 허가만 돌려준다. */
    void release();

    /** PG 호출을 시작한다. 이 시각부터 호출 시간을 잰다(허가를 얻은 뒤 TX1에 쓴 시간을 섞지 않는다). */
    void callStarted();

    /**
     * PG 호출 결과로 정산한다. 분류는 이 한 곳에 둔다(0018 개정).
     * <ul>
     *   <li>응답을 받음(승인·거절·미발견 등): 성공</li>
     *   <li>결과를 모름({@link PgClient.Unknown}): 실패. 타임아웃·연결 실패·PG 5xx·처리 중이 해당한다</li>
     *   <li>요청 오류({@link UnknownCause#CLIENT_ERROR}): 셈하지 않고 반환한다. 우리 쪽 요청·설정 문제라 PG 장애가 아니고,
     *       한 사용자의 잘못된 요청이 CB를 열어 모든 사용자의 승인을 막으면 안 된다. 알림은 호출한 쪽이 낸다</li>
     * </ul>
     */
    default void settle(Object pgResult) {
        if (pgResult instanceof PgClient.Unknown unknown) {
            if (unknown.cause() == UnknownCause.CLIENT_ERROR) release();
            else failure();
        } else {
            success();
        }
    }

    @Override
    void close();
}

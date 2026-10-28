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

    /** PG를 부르지 않았다(TX1 실패, 만료 재검사). 호출 결과로 세지 않고 허가만 돌려준다. */
    void release();

    @Override
    void close();
}

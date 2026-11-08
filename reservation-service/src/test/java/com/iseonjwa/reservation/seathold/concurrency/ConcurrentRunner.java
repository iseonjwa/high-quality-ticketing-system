package com.iseonjwa.reservation.seathold.concurrency;

import com.iseonjwa.common.response.BusinessException;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 여러 작업을 같은 순간에 출발시키고 결과를 분류한다(ISE-79 동시성 시나리오, concurrency-scenarios.md 3절).
 *
 * <p>Note: 스레드를 먼저 모두 띄운 뒤 시작 신호 하나로 함께 출발시킨다. 스레드 생성 시간 차이로 요청이 줄을 서면
 * 경합이 재현되지 않는다. 그래도 출발은 수 밀리초씩 어긋나므로 경합 결과는 확률적이고, 시나리오마다 여러 회 반복한다.
 */
final class ConcurrentRunner {
    static final String OK = "OK";
    private static final Duration RESULT_TIMEOUT = Duration.ofSeconds(60);

    private ConcurrentRunner() {
    }

    record Outcome(String result, long elapsedMillis) {
        boolean isError() {
            return result.startsWith("ERROR:");
        }
    }

    static List<Outcome> runTogether(List<Callable<String>> tasks) throws InterruptedException {
        var pool = Executors.newFixedThreadPool(tasks.size());
        var ready = new CountDownLatch(tasks.size());
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Outcome>>();
        try {
            for (var task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    var begin = System.nanoTime();
                    String result;
                    try {
                        result = task.call();
                    } catch (Exception exception) {
                        result = classify(exception);
                    }
                    return new Outcome(result, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin));
                }));
            }
            ready.await();
            start.countDown();
            var outcomes = new ArrayList<Outcome>();
            for (var future : futures) {
                outcomes.add(future.get(RESULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            }
            return outcomes;
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("동시 작업이 끝나지 않았습니다", exception);
        } finally {
            pool.shutdownNow();
        }
    }

    // 업무 거절(4xx)은 에러 코드로, 그 밖의 예외는 5xx로 집계한다(SA 3장 오류 정의). SQLState가 있으면 원인을 남긴다
    // (40P01 데드락, 55P03 lock_timeout, 23505 유니크 위반)
    static String classify(Throwable exception) {
        if (exception instanceof BusinessException business) {
            return business.getErrorCode().code();
        }
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return "ERROR:" + sql.getSQLState();
            }
        }
        return "ERROR:" + exception.getClass().getSimpleName();
    }

    static Map<String, Long> countByResult(List<Outcome> outcomes) {
        return outcomes.stream()
                .collect(Collectors.groupingBy(Outcome::result, TreeMap::new, Collectors.counting()));
    }

    static long percentileMillis(List<Outcome> outcomes, double percentile) {
        var sorted = outcomes.stream().map(Outcome::elapsedMillis).sorted().toList();
        if (sorted.isEmpty()) {
            return 0;
        }
        var index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    // 테스트 리포트의 표준 출력에 남겨 문제 재현 로그와 전후 비교 수치의 원천으로 쓴다
    static void report(String scenario, int rounds, List<Outcome> outcomes, Map<String, ?> extra) {
        Function<Double, Long> p = percentile -> percentileMillis(outcomes, percentile);
        System.out.printf("[SEATHOLD-CONCURRENCY] %s rounds=%d requests=%d results=%s p50=%dms p95=%dms max=%dms extra=%s%n",
                scenario, rounds, outcomes.size(), countByResult(outcomes), p.apply(50.0), p.apply(95.0),
                p.apply(100.0), extra);
    }
}

"""B8 측정 뒤 Prometheus에서 reservation-service 지표를 10초 간격으로 꺼내 CSV와 판정용 요약을 남긴다.

사용: python reservation-service/k6/tools/b8_metrics.py <시작 epoch초> <부하 끝 epoch초> <끝 epoch초> <출력 접두어>
판정 기준은 docs/design/reservation/k6-overload-recovery.md 6절이다. compose.b8-observe.yml로 띄워야 Tomcat·SLO 지표가 있다.
"""
import csv
import json
import os
import sys
import urllib.parse
import urllib.request

# 배포 측정(deploy/deploy-run.sh)은 SSH 터널로 연 서버 Prometheus 주소를 PROM_URL로 준다
PROM = os.environ.get("PROM_URL", "http://localhost:9090") + "/api/v1"
APP = 'application="reservation-service"'
SEATS = APP + ',uri="/api/v1/schedules/{scheduleId}/seats"'
HOLD = APP + ',uri="/api/v1/schedules/{scheduleId}/reservations"'

SERIES = {
    "tomcat_busy_threads": f"sum(tomcat_threads_busy_threads{{{APP}}})",
    "tomcat_connections": f"sum(tomcat_connections_current_connections{{{APP}}})",
    "hikari_active": f"sum(hikaricp_connections_active{{{APP}}})",
    "hikari_pending": f"sum(hikaricp_connections_pending{{{APP}}})",
    "hikari_timeout_per_s": f"sum(rate(hikaricp_connections_timeout_total{{{APP}}}[10s]))",
    "seats_200_per_s": f'sum(rate(http_server_requests_seconds_count{{{SEATS},status="200"}}[10s]))',
    "seats_all_per_s": f"sum(rate(http_server_requests_seconds_count{{{SEATS}}}[10s]))",
    # 선점 처리 시간과 커넥션 사용 시간(초당 합). P5 판단의 근사(409 처리 시간 비율)를 커넥션 사용 시간과 대조한다(계획 문서 3-4절)
    "hold_time_per_s": f"sum(rate(http_server_requests_seconds_sum{{{HOLD}}}[10s]))",
    "hold_409_time_per_s": f'sum(rate(http_server_requests_seconds_sum{{{HOLD},status="409"}}[10s]))',
    "hikari_usage_time_per_s": f"sum(rate(hikaricp_connections_usage_seconds_sum{{{APP}}}[10s]))",
    # 배포 측정에서 CPU·힙이 버티는지 본다. process_cpu_usage는 컨테이너가 보는 코어 수 대비 비율이라 코어 수를 곱해 코어로 바꾼다
    "res_cpu_cores": f"sum(process_cpu_usage{{{APP}}} * system_cpu_count{{{APP}}})",
    "res_heap_used_mb": f'sum(jvm_memory_used_bytes{{{APP},area="heap"}}) / 1048576',
    "gw_cpu_cores": 'sum(process_cpu_usage{application="gateway"} * system_cpu_count{application="gateway"})',
    # 배포 Gateway의 맨 앞 동시 처리 상한(ISE 설계 메모 A1)과 인증 차단기 거절. 측정 브랜치 Gateway에는 앞의 두 지표가 없어 0으로 남는다
    "gw_in_flight": "sum(gateway_in_flight_requests)",
    "gw_in_flight_rejected_per_s": "sum(rate(gateway_in_flight_rejected_total[10s]))",
    "gw_auth_not_permitted_per_s": 'sum(rate(resilience4j_circuitbreaker_not_permitted_calls_total{name="auth-redis"}[10s]))',
}


def query(path, params):
    url = f"{PROM}/{path}?{urllib.parse.urlencode(params)}"
    with urllib.request.urlopen(url, timeout=30) as res:
        return json.load(res)["data"]["result"]


def scalar(expr, at):
    result = query("query", {"query": expr, "time": at})
    return float(result[0]["value"][1]) if result else 0.0


def main():
    start, load_end, end, prefix = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
    columns = {}
    for name, expr in SERIES.items():
        for item in query("query_range", {"query": expr, "start": start, "end": end, "step": 10}):
            for ts, value in item["values"]:
                columns.setdefault(int(float(ts)), {})[name] = float(value)
    with open(f"{prefix}-metrics.csv", "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["epoch", "after_load_s", *SERIES])
        for ts in sorted(columns):
            writer.writerow([ts, ts - load_end, *[round(columns[ts].get(n, 0.0), 2) for n in SERIES]])

    # 부하 시작부터 끝까지 서버가 끝낸 좌석 조회. 3초 경계 버킷으로 "버려진 일"(서버 안 3초 초과) 비율을 센다
    window = f"{end - start}s"
    total = scalar(f"sum(increase(http_server_requests_seconds_count{{{SEATS}}}[{window}]))", end)
    ok = scalar(f'sum(increase(http_server_requests_seconds_count{{{SEATS},status="200"}}[{window}]))', end)
    within3 = scalar(f'sum(increase(http_server_requests_seconds_bucket{{{SEATS},le="3.0"}}[{window}]))', end)
    load_seconds = load_end - start
    ok_during_load = scalar(f'sum(increase(http_server_requests_seconds_count{{{SEATS},status="200"}}[{load_seconds}s]))', load_end)
    hold_time = scalar(f"sum(increase(http_server_requests_seconds_sum{{{HOLD}}}[{load_seconds}s]))", load_end)
    hold_409_time = scalar(f'sum(increase(http_server_requests_seconds_sum{{{HOLD},status="409"}}[{load_seconds}s]))', load_end)
    hikari_usage = scalar(f"sum(increase(hikaricp_connections_usage_seconds_sum{{{APP}}}[{load_seconds}s]))", load_end)
    timeouts = scalar(f"sum(increase(hikaricp_connections_timeout_total{{{APP}}}[{window}]))", end)

    # 회복 시각: 부하가 끝난 뒤 바쁜 요청 스레드가 처음으로 1 이하, 커넥션 대기가 0이 된 시점.
    # Note: 요청 스레드 수는 Tomcat MBean 지표로 본다. http_server_requests_active는 처리 중 요청이 없으면 시계열이 사라진다
    recovered = next((ts - load_end for ts in sorted(columns) if ts >= load_end
                      and columns[ts].get("tomcat_busy_threads", 0) <= 1 and columns[ts].get("hikari_pending", 0) == 0), None)
    summary = {
        "server_seats_total": round(total), "server_seats_200": round(ok),
        "server_seats_200_per_s_during_load": round(ok_during_load / load_seconds, 1),
        "server_over_3s_ratio": round(1 - within3 / total, 4) if total else None,
        "hikari_timeouts": round(timeouts),
        # 부하 구간의 합. K1-H는 선점만 커넥션을 쓰므로 hold_time과 hikari_usage가 20% 안이면 409 처리 시간 비율을 점유 비율로 쓴다
        "hold_time_s": round(hold_time, 1), "hold_409_time_ratio": round(hold_409_time / hold_time, 3) if hold_time else None,
        "hikari_usage_s": round(hikari_usage, 1),
        "recovered_after_load_s": recovered,
    }
    with open(f"{prefix}-metrics-summary.json", "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()

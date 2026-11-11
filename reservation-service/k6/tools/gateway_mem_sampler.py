"""Gateway 컨테이너 메모리를 1초마다 나눠 남긴다. 측정 브랜치 전용이다.
사용: python -I gateway_mem_sampler.py <출력 CSV> [컨테이너 이름]
Ctrl+C나 프로세스 종료로 멈춘다. 컨테이너가 사라지면 그 줄은 비우고 계속 돈다.

Gateway가 메모리 상한(cgroup)을 넘어 죽을 때 무엇이 늘었는지 가르려고 만들었다(STEPS.md "Gateway OOMKilled 재현").
- cgroup memory.current, memory.stat의 anon(힙·네이티브), file(페이지 캐시), sock(커널 소켓 버퍼), kernel·slab
- java 프로세스 RSS, 스레드 수
- 컨테이너 네트워크 이름공간의 TCP 소켓 수와 보내기·받기 대기열 합(/proc/net/tcp*의 tx_queue·rx_queue, 바이트)
Note: JVM 지표(힙·비힙·JDK 직접 버퍼)는 Prometheus가 5초마다 긁으므로 여기서는 읽지 않는다
"""
import csv
import subprocess
import sys
import time

out_path = sys.argv[1]
container = sys.argv[2] if len(sys.argv) > 2 else "iseonjwa-gateway-1"
SCRIPT = (
    "echo '#current'; cat /sys/fs/cgroup/memory.current; "
    "echo '#stat'; cat /sys/fs/cgroup/memory.stat; "
    "echo '#status'; cat /proc/$(pgrep -o java)/status; "
    "echo '#tcp'; cat /proc/net/tcp /proc/net/tcp6"
)
STAT_KEYS = ["anon", "file", "kernel", "kernel_stack", "sock", "slab", "shmem"]
FIELDS = ["ts", "current"] + STAT_KEYS + ["rss_kb", "threads", "tcp_sockets", "tcp_established", "tx_queue", "rx_queue"]


def sample():
    res = subprocess.run(["docker", "exec", container, "sh", "-c", SCRIPT],
                         capture_output=True, text=True, timeout=10)
    if res.returncode != 0:
        return None
    row = {}
    section = None
    for line in res.stdout.splitlines():
        if line.startswith("#"):
            section = line[1:]
            continue
        parts = line.split()
        if not parts:
            continue
        if section == "current":
            row["current"] = int(parts[0])
        elif section == "stat" and parts[0] in STAT_KEYS:
            row[parts[0]] = int(parts[1])
        elif section == "status" and parts[0] in ("VmRSS:", "Threads:"):
            row["rss_kb" if parts[0] == "VmRSS:" else "threads"] = int(parts[1])
        elif section == "tcp" and parts[0] != "sl" and len(parts) > 4:
            # 열: sl local rem st tx_queue:rx_queue ... (값은 16진수)
            tx, rx = parts[4].split(":")
            row["tcp_sockets"] = row.get("tcp_sockets", 0) + 1
            row["tcp_established"] = row.get("tcp_established", 0) + (parts[3] == "01")
            row["tx_queue"] = row.get("tx_queue", 0) + int(tx, 16)
            row["rx_queue"] = row.get("rx_queue", 0) + int(rx, 16)
    return row


with open(out_path, "w", newline="", encoding="utf-8") as f:
    writer = csv.DictWriter(f, fieldnames=FIELDS)
    writer.writeheader()
    while True:
        started = time.time()
        try:
            row = sample()
        except subprocess.TimeoutExpired:
            row = None
        writer.writerow({"ts": round(started, 1), **(row or {})})
        f.flush()
        time.sleep(max(0.0, 1.0 - (time.time() - started)))

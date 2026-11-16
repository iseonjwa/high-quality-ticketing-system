---
title: Eden·Survivor·Age는 어떻게 움직이고, RATE=50에서 힙은 정말 꽉 찼나, OOM은 언제 나나
date: 2026-11-07
asker: hyos1
level: 기초
tags: [jvm, gc, g1, eden, survivor, oom, load-test]
---

# Eden·Survivor·Age는 어떻게 움직이고, RATE=50에서 힙은 정말 꽉 찼나, OOM은 언제 나나

## 질문

효승이 정리한 이해가 맞는지 확인한다.

1. Eden이 가득 차면 Minor GC가 Eden에서 참조되지 않는 객체를 지운다.
2. 살아 있는 객체는 Survivor로 옮겨지고, Eden과 S0의 생존 객체가 함께 S1로 옮겨져 둘 중 하나는 늘 비어 있다.
3. 살아남은 횟수가 Age이고, `MaxTenuringThreshold`(기본 15)를 넘으면 Old로 간다.
4. RATE=50은 응답이 안 나가 지울 객체가 없어 메모리가 다 찬 상황이다. 지울 게 없으니 아예 멈추고, 응답이 나가면 조금씩 지운다.
5. OOM은 지울 게 없는데 메모리가 꽉 차 아무것도 못 할 때 나는 에러다.

## 답

| 번호 | 판정 | 보완 |
|---|---|---|
| 1 | 거의 맞다 | 쓰레기를 "지우는" 것이 아니라 살아 있는 객체만 밖으로 복사하고 Eden 전체를 빈 것으로 친다. 쓰레기는 건드리지 않는다. 그래서 Minor GC 비용은 쓰레기 양이 아니라 살아 있는 객체 양에 비례한다. 객체는 평소에 Eden에 할당되고, Minor GC는 그 Young 영역을 치우는 일의 이름이다. |
| 2 | 맞다(고전 모델) | Survivor가 가득 차서 옮기는 것이 아니라 Minor GC마다 Eden + 쓰던 Survivor의 생존 객체를 빈 Survivor로 옮기고 역할을 바꾼다. 빈 Survivor에 자리가 모자라면 넘치는 객체는 Age와 상관없이 Old로 바로 간다(조기 승격). 우리 JVM(G1)은 힙을 같은 크기의 영역(region) 여러 개로 나누고 영역마다 Eden·Survivor·Old·Humongous 역할을 붙인다. 생존 객체를 복사하는 원리는 같지만 S0·S1 두 칸이 고정돼 있지는 않다. |
| 3 | 맞다 | 15는 최댓값이다. JVM은 Survivor가 차는 정도를 보고 실제 기준을 그보다 낮게 바꾼다. |
| 4 | 절반 | 아래 참고. 힙은 꽉 차지 않았고, 아예 멈춘 것도 아니다. 자주 짧게 멈췄다. "응답이 나가면 그 요청의 객체가 쓰레기가 되어 다음 GC에서 자리가 생긴다"는 맞다. |
| 5 | 절반 | 멈추는 것이 아니라 예외가 던져진다. 아래 참고. |

### RATE=50에서 실제로 일어난 일 (gc.log)

- 매 GC마다 힙이 약 610MB → 400MB로 줄었다. 매번 약 200MB를 비웠다. 지울 게 없던 것이 아니다.
- 다만 400MB가 매번 살아남았다. 동시에 처리 중이던 요청 약 82건이 붙잡은 엔티티·DTO다.
- 768MB 중 400MB가 생존 객체라 새 객체를 받을 자리가 약 200MB뿐이었다. 초당 50건이 큰 객체를 만들어 그 자리가 약 35ms 만에 찼다.
- 그래서 GC가 30초에 928번 돌았다. 한 번에 약 10ms씩 400MB 생존 객체를 복사하느라 멈췄고, 합치면 8.1초(시간의 약 25%)다.
- Full GC 0번, OOM 없음, 컨테이너 재시작 없음.

### docker stats MEM 1.12GiB는 힙이 꽉 찼다는 뜻이 아니다

- 컨테이너 메모리는 힙(최대 768MB)에 메타스페이스, 스레드 스택, 버퍼 등을 더한 JVM 전체다.
- JVM은 한 번 OS에서 받아 온 메모리를 잘 돌려주지 않는다. 그래서 k6가 끝난 뒤에도 1.115GiB에 머물렀다.
- 컨테이너 한도(1.125GiB)를 넘으면 리눅스가 프로세스를 강제로 죽인다(`OOMKilled=true`). 자바의 OutOfMemoryError와 다른 일이다.

### OutOfMemoryError는 언제 나나

1. 새 객체를 넣을 자리가 없다 → Young GC.
2. 그래도 모자라거나 생존 객체를 옮길 자리도 없다 → Full GC(힙 전체를 오래 멈추고 정리).
3. Full GC 뒤에도 그 객체를 넣을 자리가 없다 → 할당하려던 스레드에서 `OutOfMemoryError: Java heap space`가 던져진다.

- 멈춰서 아무것도 못 하는 상태가 아니라, 할당에 실패한 그 스레드에 에러가 던져진다. 다른 스레드는 계속 돌 수 있지만 상태를 믿기 어려워 보통 재시작한다.
- OOM 직전에는 Full GC가 반복되며 거의 멈춘 것처럼 보이는 구간이 길게 나온다.

## 출처

- reservation-service 컨테이너 `/tmp/gc.log`(2026-11-06 RATE=50 실행 구간), `docker inspect`(`JAVA_TOOL_OPTIONS=-Xms384m -Xmx768m -XX:+UseG1GC`, `OOMKilled=false`)
- Oracle "HotSpot Virtual Machine Garbage Collection Tuning Guide"(G1 영역 구조, 승격)
- [qna/2026-11-06-seat-query-rate50-gateway-timeout-gc.md](2026-11-06-seat-query-rate50-gateway-timeout-gc.md)

## 이어진 질문 (같은 날)

1. GC는 참조되지 않는 객체를 지우는 것 아닌가. 복사는 어디에 하나.
2. docker stats 메모리가 힙이 꽉 찬 게 아니라는 것, k6가 끝나도 1.115GiB인 것이 이해되지 않는다.

### 결과는 "지운다", 방법은 "산 것을 옮기고 칸을 통째로 비운다"

- Young GC는 쓰레기를 하나씩 찾아 지우지 않는다. 살아 있는 객체만 빈 Survivor(또는 Old) 칸으로 복사하고, 그 객체를 가리키던 참조를 새 주소로 고친 뒤, Eden 칸 전체를 빈 칸으로 친다. 쓰레기는 그 칸에 남아 있다가 다음에 새 객체가 덮어쓴다.
- 이사에 비유하면 쓰레기를 하나씩 버리지 않고, 챙길 물건만 새 방으로 옮긴 뒤 옛 방을 통째로 비운 것으로 친다.
- 장점: 대부분이 쓰레기면 옮길 것이 적어 빠르고, 옮긴 객체가 한곳에 붙어 빈틈(단편화)이 생기지 않는다. 단점: 살아 있는 객체가 많으면 옮길 것이 많아 느리다. RATE=50이 이 경우다.
- 실제 로그(GC 2086, RATE=50 직후):

```
Eden regions: 256->0(292)
Survivor regions: 29->27(36)
Old regions: 284->246
Pause Young (Mixed) (G1 Evacuation Pause) 577M->281M(768M) 14.505ms
```

  Eden 칸 256개가 0개가 됐다(통째로 비움). 살아남은 객체는 Survivor와 Old 칸으로 옮겨졌다.

### 컨테이너 메모리 구조

- 컨테이너 한도 1.125GiB 안에 JVM 전체가 있고, 힙은 그 안에서 최대 768MB다(`-Xmx768m`). 힙 밖에 메타스페이스, 스레드 스택, GC 자료구조 등이 있다.
- JVM은 힙이 커져야 할 때 OS에서 메모리를 받아 온다(committed). 다 쓴 뒤에도 바로 돌려주지 않고, 특정 GC 시점(G1은 Remark, Full GC)에 남는 만큼만 돌려준다. 그래서 k6 직후 docker stats는 1.115GiB에 머물렀다. docker stats가 재는 것은 JVM이 OS에서 받아 둔 양이지 힙에서 실제로 쓰는 양이 아니다.
- 확인(gc.log): RATE=50 끝(57938s) 뒤 약 43분 지난 60535s의 `Pause Remark 159M->107M(384M)`에서 힙 크기가 768M → 384M로 줄었다.
- 2026-11-07 Prometheus: 힙 사용 약 267MiB, 힙 committed 384MiB, 힙 밖 committed 약 244MiB. docker stats 753MiB.
- 정정: 앞 답에서 "JVM은 받아 온 메모리를 잘 돌려주지 않는다"고 했다. 돌려주긴 하지만 바로가 아니라 특정 GC 시점에 돌려준다.

### 세 번째 점검 (같은 날)

- 복사 = Eden과 쓰던 Survivor(from)에서 살아 있는 객체를 빈 Survivor(to)로 옮기는 것. 맞다.
- 쓰던 Survivor의 쓰레기도 Eden처럼 통째로 버린다. 새 Survivor가 덮어쓰는 것이 아니라 쓰던 Survivor 칸 자체가 빈 칸이 되고, 다음 GC에서 옮겨 갈 목적지(to)가 된다. G1에서는 비운 칸이 빈 칸 목록으로 돌아간다.
- 1.125GiB와 768MB는 "미리 떼어 준 양"이 아니라 상한이다. 컨테이너는 쓰는 만큼만 차지하고 1.125GiB를 넘지 못한다. 힙은 384MB(`-Xms384m`)로 시작해 필요하면 768MB(`-Xmx768m`)까지 커진다.
- 힙 밖: 메타스페이스(클래스 정보, 메서드 영역), 스레드 스택, JIT 컴파일 코드(code cache), GC 자료구조, 네트워크 버퍼 등.
- docker stats MEM은 객체가 들어 있든 비어 있든 JVM이 OS에서 받아 들고 있는 양이다. 맞다. k6 직후에는 힙 committed 768MB + 힙 밖이 더해져 1.115GiB였다.

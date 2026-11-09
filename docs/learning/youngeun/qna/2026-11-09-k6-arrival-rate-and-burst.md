---
title: k6에서 요청량 기준과 VU 기준은 무엇이 다르고, 오픈 순간 몰림은 어떻게 재현하는가
date: 2026-11-09
updated: 2026-11-09
asker: kye
level: 중급
tags: [k6, load-test, arrival-rate, open-model, queue]
---

# k6에서 요청량 기준과 VU 기준은 무엇이 다르고, 오픈 순간 몰림은 어떻게 재현하는가

## 질문
- Q1 스크립트의 `DUP_RATIO`, `REPEAT` 반복문은 무엇을 하나?
- `ramping-arrival-rate`보다 `constant-arrival-rate`를 쓸 이유가 뭔가?
- 초당 1만 명을 버티면 첫 1초에 10만 명도 버틴다고 할 수 있나?
- 1초 안의 몰림은 어떻게 재현하나?
- 요청량으로 설정하는 것과 VU로 설정하는 것은 무엇이 다른가?

## 짧은 답
VU 기준(닫힌 모델)은 응답을 받아야 다음 요청을 보내서 서버가 느려지면 부하도 준다. 요청량 기준(열린 모델, arrival-rate)은 응답과 상관없이 정해진 속도로 시작한다. 티켓 오픈처럼 서버 상태와 상관없이 사람이 몰리는 상황은 요청량 기준이 맞다. 다만 `constant-arrival-rate`를 평균 속도로 돌리면 앞쪽에 몰리는 실제 상황보다 약한 시험이 된다. 최고 속도로 맞추거나, 1초 안의 몰림까지 보려면 VU를 미리 만들어 두고 정해진 시각에 한꺼번에 출발시킨다.

## 자세히

### VU 기준과 요청량 기준
| | VU 기준(닫힌 모델) | 요청량 기준(열린 모델) |
|---|---|---|
| 정하는 것 | 동시 사용자 수 | 초당 도착 수 |
| 서버가 느려지면 | 요청이 준다(VU 100, 응답 1초면 초당 약 100건) | 요청은 그대로, k6가 VU를 더 꺼낸다 |
| 맞는 상황 | 정해진 인원이 계속 쓰는 서비스 | 서버 상태와 상관없이 몰려오는 상황 |

요청량 기준에서 VU가 `maxVUs`까지 차면 k6는 정해진 속도를 못 지키고 `dropped_iterations`로 알린다. 0보다 크면 서버가 느려 정해진 속도로 보내지 못했다는 뜻이다.

### constant와 ramping
constant는 속도 하나에 결과 하나가 붙어 "초당 N명일 때 p95는 얼마"로 말하기 쉽고, `속도 × 시간`이 곧 총 인원이라 결과 확인(예: Redis `seq`가 사용자 수와 같은지)이 쉽다. ramping은 시간에 따라 속도를 바꿀 수 있어 앞쪽에 몰렸다 줄어드는 실제 모양과 그 뒤 회복을 볼 때 맞다.

### 평균 속도로 돌리면 약한 시험이다
constant가 실제보다 엄한 시험이 되는 것은 그 속도가 실제 최고 속도 이상일 때뿐이다.

```
실제:     첫 1초 100,000명 → 이후 거의 0
constant: 초당 10,000명 × 10초 = 100,000명
```

총 인원은 같아도 최고 속도는 실제가 10배다. Q1 스크립트는 `RATE = USERS / ENTER_SECONDS`라 평균 속도다. 기대하는 최고 속도를 재려면 `ENTER_SECONDS`를 줄여 `RATE`가 그 속도가 되게 한다(예: `USERS=30000 ENTER_SECONDS=1`).

### 1초 안의 몰림
arrival-rate는 1초에 N명을 그 1초 동안 고르게 나눠 시작한다. 실제로는 오픈 첫 100ms에 몰릴 수 있다. 이를 재현하려면 사용자 수만큼 VU를 미리 만들고, 공통 시각까지 기다렸다가 출발시킨다.

```js
export const options = {
  scenarios: { burst: { executor: 'per-vu-iterations', vus: 10000, iterations: 1, maxDuration: '2m' } },
};

export function setup() {
  return { startAt: Date.now() + 30_000 }; // 모든 VU가 준비될 시간
}

export default function (data) {
  const delay = data.startAt - Date.now() + Math.random() * SPREAD * 1000; // 고르게
  // 앞쪽에 몰리게: -Math.log(1 - Math.random()) * 0.3 (지수 분포, 평균 0.3초)
  if (delay > 0) sleep(delay / 1000);
  http.post(enterUrl, null, { headers });
}
```

형식은 VU 기준이지만 한 명당 한 번만, 미리 정한 시각에 보내므로 응답이 늦어도 다음 사람의 출발이 밀리지 않는다. 실제 동작은 열린 모델과 같다.

한계:
- VU 하나가 메모리를 몇 MB 써서 발생기 한 대로는 수만 명 정도다. 더 많으면 발생기 여러 대에서 같은 시각에 출발시키고 시계를 맞춘다.
- 한순간에 연결을 수만 개 열면 발생기의 CPU나 포트가 먼저 막힐 수 있다. `http_req_connecting`과 발생기 CPU를 같이 본다.

### 총 인원보다 밀도가 세기를 정한다
추가 질문: "300명도 안 되는데 1000명을 어떻게 돌려?"

시험의 세기는 총 인원이 아니라 짧은 시간에 몇 명이 몰리느냐(순간 속도)로 정해진다. Q1-d는 SPREAD 안에서 앞쪽이 가장 붐비는 삼각 분포라 맨 앞 순간 속도가 평균의 2배(`2 × USERS / SPREAD`)다.

| 조건 | 평균 속도 | 맨 앞 최고 속도 |
|---|---|---|
| Q1-d 300명 / 0.1초 | 초당 3,000명 | 초당 약 6,000명 |
| Q1-d 1,000명 / 1초 | 초당 1,000명 | 초당 약 2,000명 |
| Q1-d 5,000명 / 1초 | 초당 5,000명 | 초당 약 10,000명 |
| Q1 1,000명 / 1초(고르게) | 초당 1,000명 | 초당 1,000명 |

1,000명 / 1초는 300명 / 0.1초보다 인원이 많지만 순간 속도는 3분의 1이라 더 약한 시험이다. Gateway의 서킷 브레이커처럼 "최근 N번 호출"만 보는 보호 장치는 총 인원보다 순간 밀도에 반응한다. 반대로 5,000명 / 1초는 순간 속도가 더 높아 300명 / 0.1초보다 세다. 실패한 조건보다 센지 약한지는 인원이 아니라 순간 속도로 비교한다. 어느 조건에서 깨지는지는 약한 쪽부터 올려 가며 경계를 찾는다.

### 연타 흉내(DUP_RATIO)
```js
const attempts = Math.random() < DUP_RATIO ? REPEAT : 1;
for (let i = 0; i < attempts; i++) {
  if (i > 0) sleep(0.2);
  // POST /queue
}
```
사용자 중 `DUP_RATIO` 비율은 진입을 0.2초 간격으로 `REPEAT`번 보낸다. 첫 요청은 201, 연타는 200(같은 번호)이어야 한다. 끝나고 Redis `seq`와 `waiting` 원소 수가 고유 사용자 수와 같으면 부하 속에서도 진입이 멱등했다는 뜻이다.

## 이 프로젝트에서는
- Q1 `loadtest/k6/scenarios/q1-queue-enter.js`: `constant-arrival-rate`, `RATE = USERS / ENTER_SECONDS`, `DUP_RATIO`로 Q1-c
- 오픈 순간 몰림은 Q1과 실행 방식·판정 기준이 달라 별도 시나리오(Q1-d 후보)로 둘 예정이다(미작성).
- 2026-11-05 로컬 연습의 첫 몇백 요청 `connection refused`는 1초 안 몰림과 관련 있을 수 있다(미확인).

## 면접 포인트
- 열린 모델과 닫힌 모델의 차이, 티켓 오픈에 열린 모델을 쓰는 이유: 닫힌 모델은 서버가 느려지면 부하가 줄어 실제보다 잘 버티는 것처럼 보인다.
- 평균 속도로 낸 결과를 최고 속도의 근거로 쓰면 안 되는 이유: 총량이 같아도 순간 속도가 다르다.
- 초 단위 안의 몰림을 재현하는 방법과 발생기 한계를 어떻게 구분하는가.

## 참고
- k6 문서: Open and closed models, constant-arrival-rate, ramping-arrival-rate, per-vu-iterations
- `loadtest/k6/README.md`, `docs/design/infra/open-peak-scenarios.md` Q1

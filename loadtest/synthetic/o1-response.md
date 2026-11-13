데이터는 아래와 같이 만들었습니다. 이 환경에는 실행 도구가 없어서 40명 표본은 분포를 보며 직접 뽑았습니다. 범위와 비율은 직접 대조해 확인했지만 실제 난수 생성기로 뽑은 결과는 아닙니다. 대규모 생성은 k6 쪽 생성기로 다시 뽑는 것을 권합니다.

## 3-1. 페르소나 분포

```json
{
  "version": 1,
  "personas": [
    {
      "code": "office_pc_fan",
      "name_ko": "퇴근 후 PC 팬",
      "share": 0.28,
      "description": "퇴근하고 PC 앞에서 오픈 몇 분 전부터 상세 화면을 적당한 간격으로 새로고침합니다. 오픈 정각에 바로 진입하고, 409가 나면 대개 같은 등급에서 다시 고릅니다.",
      "fields": {
        "arrive_s": {"dist": "normal", "mean": -300, "sd": 150, "min": -600, "max": -10},
        "refresh_s": {"dist": "normal", "mean": 20, "sd": 10, "min": 5, "max": 60},
        "enter_delay_ms": {"dist": "exponential", "mean": 800, "min": 150, "max": 10000},
        "enter_clicks": {"dist": "choice", "values": [1, 2], "weights": [0.8, 0.2]},
        "seats": {"dist": "choice", "values": [1, 2, 3, 4], "weights": [0.4, 0.4, 0.1, 0.1]},
        "zone": {"dist": "choice", "values": ["VIP", "R", "S", "ANY"], "weights": [0.45, 0.35, 0.1, 0.1]},
        "think_ms": {"dist": "normal", "mean": 4000, "sd": 1500, "min": 1500, "max": 15000},
        "max_retry": {"dist": "choice", "values": [2, 3, 4], "weights": [0.3, 0.4, 0.3]},
        "on_conflict": {"dist": "choice", "values": ["same_zone", "any_zone", "give_up"], "weights": [0.6, 0.35, 0.05]},
        "queue_patience_s": {"dist": "choice", "values": [0, 600, 900], "weights": [0.7, 0.2, 0.1]},
        "abandon_after_hold": {"dist": "bernoulli", "p": 0.03},
        "pay_delay_s": {"dist": "normal", "mean": 40, "sd": 15, "min": 15, "max": 180}
      },
      "basis": "팀이 정한 핵심 페르소나라서 가장 큰 비율을 줬습니다. 비율 0.28과 모든 수치는 추정입니다. PC라서 고르고 결제하는 시간이 모바일보다 짧다고 봤습니다."
    },
    {
      "code": "pair_buyer",
      "name_ko": "친구와 2매",
      "share": 0.18,
      "description": "친구와 붙은 2석을 잡으려는 사람입니다. 나란한 자리를 찾느라 고르는 시간이 길고, 결제 전에 친구에게 확인하느라 결제도 늦습니다.",
      "fields": {
        "arrive_s": {"dist": "normal", "mean": -240, "sd": 150, "min": -600, "max": 0},
        "refresh_s": {"dist": "normal", "mean": 25, "sd": 10, "min": 5, "max": 60},
        "enter_delay_ms": {"dist": "normal", "mean": 1500, "sd": 800, "min": 200, "max": 8000},
        "enter_clicks": {"dist": "choice", "values": [1, 2, 3], "weights": [0.6, 0.3, 0.1]},
        "seats": {"dist": "const", "value": 2},
        "zone": {"dist": "choice", "values": ["VIP", "R", "S", "ANY"], "weights": [0.35, 0.4, 0.15, 0.1]},
        "think_ms": {"dist": "normal", "mean": 7000, "sd": 3000, "min": 2000, "max": 25000},
        "max_retry": {"dist": "choice", "values": [2, 3, 4, 5], "weights": [0.2, 0.3, 0.3, 0.2]},
        "on_conflict": {"dist": "choice", "values": ["same_zone", "any_zone", "give_up"], "weights": [0.5, 0.45, 0.05]},
        "queue_patience_s": {"dist": "choice", "values": [0, 300, 600], "weights": [0.6, 0.25, 0.15]},
        "abandon_after_hold": {"dist": "bernoulli", "p": 0.08},
        "pay_delay_s": {"dist": "normal", "mean": 70, "sd": 30, "min": 20, "max": 270}
      },
      "basis": "팀이 정한 페르소나라서 넣었고 seats는 2로 고정했습니다. 비율, 고르는 시간, 이탈률은 추정입니다. 2석이 붙어 있어야 해서 409를 겪은 뒤 등급을 바꾸는 비율이 높다고 봤습니다."
    },
    {
      "code": "refresh_spammer",
      "name_ko": "새로고침 연타",
      "share": 0.12,
      "description": "일찍 들어와 3~6초마다 새로고침하고, 오픈 순간 진입 버튼을 여러 번 누릅니다. 좌석을 빨리 고르고 409가 나도 끝까지 다시 시도합니다.",
      "fields": {
        "arrive_s": {"dist": "normal", "mean": -400, "sd": 120, "min": -600, "max": -60},
        "refresh_s": {"dist": "uniform", "min": 3, "max": 6},
        "enter_delay_ms": {"dist": "exponential", "mean": 300, "min": 50, "max": 3000},
        "enter_clicks": {"dist": "choice", "values": [2, 3, 4, 5], "weights": [0.2, 0.3, 0.3, 0.2]},
        "seats": {"dist": "choice", "values": [1, 2, 4], "weights": [0.3, 0.5, 0.2]},
        "zone": {"dist": "choice", "values": ["VIP", "R", "S", "ANY"], "weights": [0.6, 0.3, 0.05, 0.05]},
        "think_ms": {"dist": "exponential", "mean": 1500, "min": 300, "max": 8000},
        "max_retry": {"dist": "choice", "values": [4, 5], "weights": [0.4, 0.6]},
        "on_conflict": {"dist": "choice", "values": ["same_zone", "any_zone"], "weights": [0.7, 0.3]},
        "queue_patience_s": {"dist": "const", "value": 0},
        "abandon_after_hold": {"dist": "bernoulli", "p": 0.05},
        "pay_delay_s": {"dist": "exponential", "mean": 25, "min": 8, "max": 150}
      },
      "basis": "팀이 실제로 일어난다고 본 '새로고침 연타'를 사람 손으로 하는 범위에서만 나타냈습니다. 매크로는 범위 밖이라 간격 하한을 3초로 두었습니다. 비율과 간격은 추정입니다."
    },
    {
      "code": "multi_device",
      "name_ko": "여러 기기 접속",
      "share": 0.07,
      "description": "같은 계정으로 PC와 휴대폰을 함께 띄워 놓고, 기기마다 새로고침하고 진입을 누릅니다. 순번은 하나뿐이지만 요청은 기기 수만큼 늘어납니다.",
      "fields": {
        "arrive_s": {"dist": "uniform", "min": -600, "max": -120},
        "refresh_s": {"dist": "uniform", "min": 4, "max": 10},
        "enter_delay_ms": {"dist": "exponential", "mean": 500, "min": 100, "max": 5000},
        "enter_clicks": {"dist": "choice", "values": [2, 3, 4], "weights": [0.5, 0.3, 0.2]},
        "seats": {"dist": "choice", "values": [1, 2], "weights": [0.4, 0.6]},
        "zone": {"dist": "choice", "values": ["VIP", "R", "S", "ANY"], "weights": [0.5, 0.35, 0.1, 0.05]},
        "think_ms": {"dist": "normal", "mean": 3000, "sd": 1000, "min": 800, "max": 10000},
        "max_retry": {"dist": "choice", "values": [3, 4, 5], "weights": [0.3, 0.4, 0.3]},
        "on_conflict": {"dist": "choice", "values": ["same_zone", "any_zone"], "weights": [0.6, 0.4]},
        "queue_patience_s": {"dist": "const", "value": 0},
        "abandon_after_hold": {"dist": "bernoulli", "p": 0.05},
        "pay_delay_s": {"dist": "normal", "mean": 35, "sd": 12, "min": 12, "max": 150}
      },
      "basis": "팀이 실제로 일어난다고 본 '여러 기기 접속'입니다. 계정 하나가 한 행이므로, 기기들을 합친 결과를 짧은 새로고침 간격과 많은 진입 횟수로 나타냈습니다. 비율은 추정입니다."
    },
    {
      "code": "mobile_commuter",
      "name_ko": "퇴근길 모바일",
      "share": 0.12,
      "description": "퇴근길 휴대폰으로 오픈 직전이나 직후에 들어옵니다. 작은 화면에서 좌석을 찾느라 고르는 시간과 결제 시간이 길고, 대기가 길면 일부는 떠납니다.",
      "fields": {
        "arrive_s": {"dist": "normal", "mean": -30, "sd": 90, "min": -300, "max": 300},
        "refresh_s": {"dist": "choice", "values": [0, 10, 20, 30], "weights": [0.3, 0.3, 0.2, 0.2]},
        "enter_delay_ms": {"dist": "exponential", "mean": 2500, "min": 400, "max": 20000},
        "enter_clicks": {"dist": "choice", "values": [1, 2], "weights": [0.85, 0.15]},
        "seats": {"dist": "choice", "values": [1, 2], "weights": [0.6, 0.4]},
        "zone": {"dist": "choice", "values": ["VIP", "R", "S", "ANY"], "weights": [0.2, 0.35, 0.25, 0.2]},
        "think_ms": {"dist": "normal", "mean": 8000, "sd": 3000, "min": 2500, "max": 30000},
        "max_retry": {"dist": "choice", "values": [1, 2, 3], "weights": [0.3, 0.4, 0.3]},
        "on_conflict": {"dist": "choice", "values": ["same_zone", "any_zone", "give_up"], "weights": [0.3, 0.5, 0.2]},
        "queue_patience_s": {"dist": "choice", "values": [0, 120, 300, 600], "weights": [0.3, 0.25, 0.25, 0.2]},
        "abandon_after_hold": {"dist": "bernoulli", "p": 0.12},
        "pay_delay_s": {"dist": "normal", "mean": 90, "sd": 40, "min": 25, "max": 280}
      },
      "basis": "평일 오후 8시는 퇴근 시간과 겹친다는 점에서 추정해 넣은 페르소나입니다. 팀이 정한 페르소나는 아닙니다. 비율과 모바일에서 느려지는 정도도 모두 추정입니다."
    },
    {
      "code": "late_casual",
      "name_ko": "오픈 뒤 일반 관심층",
      "share": 0.10,
      "description": "알림이나 소문을 보고 오픈 뒤에 들어옵니다. 등급을 덜 가리고, 409나 긴 대기를 만나면 쉽게 포기합니다.",
      "fields": {
        "arrive_s": {"dist": "uniform", "min": 30, "max": 600},
        "refresh_s": {"dist": "const", "value": 0},
        "enter_delay_ms": {"dist": "uniform", "min": 2000, "max": 30000},
        "enter_clicks": {"dist": "choice", "values": [0, 1], "weights": [0.15, 0.85]},
        "seats": {"dist": "choice", "values": [1, 2], "weights": [0.6, 0.4]},
        "zone": {"dist": "choice", "values": ["VIP", "R", "S", "ANY"], "weights": [0.05, 0.2, 0.35, 0.4]},
        "think_ms": {"dist": "uniform", "min": 5000, "max": 30000},
        "max_retry": {"dist": "choice", "values": [0, 1, 2], "weights": [0.3, 0.4, 0.3]},
        "on_conflict": {"dist": "choice", "values": ["same_zone", "any_zone", "give_up"], "weights": [0.1, 0.5, 0.4]},
        "queue_patience_s": {"dist": "uniform", "min": 30, "max": 240},
        "abandon_after_hold": {"dist": "bernoulli", "p": 0.2},
        "pay_delay_s": {"dist": "normal", "mean": 100, "sd": 50, "min": 30, "max": 280}
      },
      "basis": "측정 창의 뒤쪽 10분(오픈 뒤 0~600초)에도 부하가 있게 하려고 넣었습니다. 비율, 포기율, 이탈률은 추정입니다."
    },
    {
      "code": "browse_only",
      "name_ko": "구경만 하는 사람",
      "share": 0.05,
      "description": "상세 화면만 보고 대부분 진입하지 않고 떠납니다. 진입하더라도 쉽게 포기합니다.",
      "fields": {
        "arrive_s": {"dist": "uniform", "min": -600, "max": 600},
        "refresh_s": {"dist": "choice", "values": [0, 30, 60], "weights": [0.4, 0.4, 0.2]},
        "enter_delay_ms": {"dist": "uniform", "min": 5000, "max": 60000},
        "enter_clicks": {"dist": "choice", "values": [0, 1], "weights": [0.85, 0.15]},
        "seats": {"dist": "choice", "values": [1, 2], "weights": [0.5, 0.5]},
        "zone": {"dist": "choice", "values": ["VIP", "R", "S", "ANY"], "weights": [0.3, 0.3, 0.2, 0.2]},
        "think_ms": {"dist": "uniform", "min": 3000, "max": 20000},
        "max_retry": {"dist": "choice", "values": [0, 1], "weights": [0.6, 0.4]},
        "on_conflict": {"dist": "choice", "values": ["any_zone", "give_up"], "weights": [0.3, 0.7]},
        "queue_patience_s": {"dist": "uniform", "min": 30, "max": 120},
        "abandon_after_hold": {"dist": "bernoulli", "p": 0.5},
        "pay_delay_s": {"dist": "uniform", "min": 30, "max": 280}
      },
      "basis": "enter_clicks=0인 흐름(상세 조회와 Gateway 인증만 지나는 부하)을 시험하려고 넣었습니다. 비율은 추정입니다."
    },
    {
      "code": "vip_hunter",
      "name_ko": "VIP 고집 팬",
      "share": 0.08,
      "description": "VIP 구역(A~E)만 노리고 4매를 잡으려는 경우가 많은 열성 팬입니다. 빨리 고르고, 409가 나도 등급을 바꾸지 않고 끝까지 다시 시도합니다.",
      "fields": {
        "arrive_s": {"dist": "normal", "mean": -420, "sd": 100, "min": -600, "max": -120},
        "refresh_s": {"dist": "normal", "mean": 12, "sd": 5, "min": 3, "max": 30},
        "enter_delay_ms": {"dist": "exponential", "mean": 600, "min": 100, "max": 5000},
        "enter_clicks": {"dist": "choice", "values": [1, 2, 3], "weights": [0.4, 0.4, 0.2]},
        "seats": {"dist": "choice", "values": [2, 4], "weights": [0.4, 0.6]},
        "zone": {"dist": "const", "value": "VIP"},
        "think_ms": {"dist": "normal", "mean": 2500, "sd": 800, "min": 800, "max": 8000},
        "max_retry": {"dist": "choice", "values": [4, 5], "weights": [0.3, 0.7]},
        "on_conflict": {"dist": "choice", "values": ["same_zone", "give_up"], "weights": [0.9, 0.1]},
        "queue_patience_s": {"dist": "const", "value": 0},
        "abandon_after_hold": {"dist": "bernoulli", "p": 0.1},
        "pay_delay_s": {"dist": "normal", "mean": 30, "sd": 10, "min": 10, "max": 120}
      },
      "basis": "팀이 본 병목인 '인기 구역 선점 경쟁'을 가장 세게 일으키려고 넣었습니다. 4매를 고르면 붙은 빈 좌석이 적어 409가 더 잦을 것으로 봅니다. 비율과 이탈률(4매 가격 부담으로 포기)은 추정입니다."
    }
  ],
  "assumptions": [
    "[근거: 시스템 조건] 같은 계정은 대기열에 한 자리만 갖습니다. enter_clicks가 2 이상이면 진입 요청은 그 횟수만큼 보내지만 순번은 하나만 생겨야 합니다.",
    "[근거: 시스템 조건] pay_delay_s의 상한이 280초여서 모두 선점 유지 시간(5분) 안에 결제합니다. 좌석이 시간 만료로 풀리는 경우는 abandon_after_hold=1인 사용자에게서만 생깁니다.",
    "[근거: 시스템 조건] 대기열은 초당 50명씩 입장시킵니다. 전체 인원이 N명이면 마지막 사람은 약 N/50초를 기다립니다. N=3,000이면 약 60초라서 queue_patience_s가 거의 영향이 없고, N이 1만 명을 넘으면 120~600초 값들이 실제로 이탈을 만듭니다.",
    "[해석] exponential의 mean은 min을 더하기 전 지수 부분의 평균으로 해석했습니다. 생성기가 다르게 해석한다면 mean에서 min을 빼서 맞춰야 합니다.",
    "[해석] arrive_s가 0 이상이면 오픈 전 새로고침이 없으므로 refresh_s는 쓰지 않습니다. 표본 CSV에서는 이 경우 0을 넣었습니다.",
    "[해석] enter_clicks=0이면 진입 뒤 필드(seats~pay_delay_s)도 값은 들어 있지만 시나리오에서는 쓰지 않습니다.",
    "[해석] 여러 기기 접속은 계정 하나를 한 행으로 두고, 기기들을 합친 요청 패턴(짧은 새로고침 간격, 많은 진입 횟수)으로 나타냈습니다. 기기마다 VU를 따로 띄우는 방식은 쓰지 않았습니다.",
    "[추정] 모든 share와 분포의 모수는 추정입니다. 실제 서비스의 공개 통계를 근거로 쓰지 않았습니다. 실측 로그가 생기면 그것으로 다시 맞춰야 합니다.",
    "[추정] share를 가중 합산하면 VIP를 노리는 사용자가 약 42%입니다(VIP 좌석은 전체의 20%). 이 쏠림은 선점 경쟁 병목을 재현하려고 일부러 둔 추정입니다.",
    "[추정] 같은 페르소나 안에서는 필드끼리 서로 독립적으로 뽑는다고 가정했습니다. 상관관계는 페르소나를 나누는 것으로만 표현했습니다.",
    "[시나리오 가정] 409 뒤 좌석을 다시 고를 때 실제 사용자는 갱신된 배치도를 다시 본다고 보고, 좌석 배치도(약 830KB)를 다시 조회하는 것을 권합니다. 이 판단은 k6 스크립트 쪽 결정입니다.",
    "[범위] 매크로 사용자는 넣지 않았습니다. 새로고침 간격의 하한(3초)과 think_ms의 하한(300ms)은 사람이 손으로 할 수 있는 범위로 두었습니다."
  ]
}
```

## 3-2. 표본 CSV (40명)

40명의 구성은 office_pc_fan 11, pair_buyer 7, refresh_spammer 5, mobile_commuter 5, late_casual 4, multi_device 3, vip_hunter 3, browse_only 2명입니다. 앞쪽 VU에 한 페르소나가 몰리지 않도록 순서를 섞었습니다.

```csv
user_no,persona,arrive_s,refresh_s,enter_delay_ms,enter_clicks,seats,zone,think_ms,max_retry,on_conflict,queue_patience_s,abandon_after_hold,pay_delay_s
0,office_pc_fan,-312,18,420,1,2,VIP,3800,3,same_zone,0,0,38
1,pair_buyer,-210,24,1350,1,2,R,6800,3,same_zone,0,0,72
2,refresh_spammer,-420,4,180,4,2,VIP,950,5,same_zone,0,0,21
3,mobile_commuter,-45,10,1900,1,1,R,7200,2,any_zone,120,0,85
4,office_pc_fan,-455,25,1180,1,1,R,4600,2,any_zone,0,0,52
5,late_casual,85,0,6400,1,1,ANY,14200,1,any_zone,95,0,120
6,vip_hunter,-460,10,340,2,4,VIP,2300,5,same_zone,0,0,27
7,pair_buyer,-380,30,2100,2,2,VIP,9400,4,same_zone,0,0,95
8,office_pc_fan,-180,12,310,2,2,VIP,2900,4,same_zone,600,0,29
9,multi_device,-540,6,260,3,2,VIP,2600,4,same_zone,0,0,31
10,refresh_spammer,-510,3,90,5,2,VIP,1800,5,same_zone,0,0,14
11,mobile_commuter,60,0,3800,1,2,S,9800,1,give_up,300,0,130
12,office_pc_fan,-520,30,2650,1,1,VIP,5200,3,same_zone,0,0,44
13,pair_buyer,-60,18,900,1,2,R,5200,2,any_zone,300,0,48
14,browse_only,-350,30,24000,0,1,R,9500,0,give_up,75,1,140
15,office_pc_fan,-95,15,760,1,2,R,3300,2,any_zone,0,0,61
16,late_casual,240,0,18500,1,2,S,8700,0,give_up,180,0,88
17,refresh_spammer,-330,5,410,3,1,R,620,4,any_zone,0,0,37
18,office_pc_fan,-270,22,980,1,4,VIP,6100,4,same_zone,0,0,47
19,pair_buyer,-295,35,1720,3,2,VIP,7600,5,same_zone,0,1,110
20,mobile_commuter,-110,20,950,2,1,VIP,6400,3,same_zone,0,0,74
21,vip_hunter,-380,15,820,1,2,VIP,3100,5,same_zone,0,0,35
22,office_pc_fan,-390,8,230,2,2,S,4100,3,any_zone,900,0,33
23,multi_device,-200,8,710,2,1,R,3700,5,any_zone,0,0,42
24,late_casual,410,0,3100,1,1,ANY,22600,2,any_zone,60,1,175
25,pair_buyer,-150,20,2600,1,2,S,4300,3,any_zone,600,0,63
26,refresh_spammer,-280,6,130,2,4,VIP,2700,5,same_zone,0,0,26
27,office_pc_fan,-240,28,1540,1,1,R,2400,2,same_zone,0,0,25
28,mobile_commuter,15,0,5200,1,1,ANY,11500,2,any_zone,600,1,160
29,office_pc_fan,-580,35,590,1,3,VIP,4800,4,same_zone,600,0,40
30,pair_buyer,-440,27,640,2,2,R,11200,4,same_zone,0,0,81
31,browse_only,120,0,41000,0,2,ANY,15200,1,any_zone,50,0,210
32,vip_hunter,-510,8,190,3,4,VIP,1700,4,same_zone,0,0,22
33,refresh_spammer,-470,3,560,4,2,R,1300,4,same_zone,0,0,48
34,late_casual,530,0,11200,1,2,R,6300,1,give_up,210,0,64
35,office_pc_fan,-150,19,3400,1,2,ANY,3600,3,any_zone,0,0,56
36,multi_device,-415,5,380,2,2,VIP,2100,3,same_zone,0,0,28
37,mobile_commuter,-20,30,2700,1,2,R,5100,1,same_zone,300,0,58
38,pair_buyer,-120,14,1480,1,2,ANY,3900,2,any_zone,300,0,57
39,office_pc_fan,-330,14,870,1,1,R,5500,3,same_zone,0,0,36
```

40명이 작은 표본이라 abandon_after_hold=1이 4명(10%)입니다. 분포에서 기대하는 비율(약 8%)보다 조금 많습니다.

## 3-3. 확인할 점 (예측)

- **대기열 진입:** 오픈 전에 접속한 사람 대부분의 진입 지연이 0~2초에 몰려 있습니다. 균일 난수보다 오픈 직후 1~3초에 진입 요청이 훨씬 뾰족하게 몰릴 것으로 보입니다. 연타 때문에 진입 요청이 사용자 수의 약 1.6배가 되므로, 중복 진입을 순번 하나로 묶는 경로의 비용이 드러날 수 있습니다.
- **선점 경쟁:** 약 42%가 전체 좌석의 20%인 VIP를 노리고, 그중 상당수가 4매를 고르면서 409가 나도 같은 등급을 고집합니다. 그래서 409와 재시도가 A~E 구역에 몰리고, 재시도마다 배치도(830KB)를 다시 조회하면 응답 크기 병목이 균일 부하보다 크게 나타날 가능성이 있습니다.
- **오픈 뒤 두 번째 파도:** 선점만 하고 떠난 사용자의 좌석이 5분 뒤 풀리면, 오픈 뒤 약 5~7분에 선점과 409가 다시 짧게 몰릴 수 있습니다. 균일 부하에서는 이 파도가 흐려질 것으로 봅니다.
- **오픈 전 인증 경로:** 새로고침 연타와 여러 기기 사용자는 약 19%지만, 오픈 전 상세 조회와 Gateway 인증 요청의 상당 부분을 이들이 만들 것으로 보입니다. 균일 부하는 오픈 전 요청량의 정점을 낮게 잡을 수 있습니다.
- **대기열 이탈:** 전체 인원이 1만 명을 넘어 대기가 길어지면 모바일·일반 관심층의 이탈이 실제로 생깁니다. 그러면 입장 토큰을 받은 뒤 이어지는 단계의 부하가 균일 모델보다 다소 낮게 나올 수도 있습니다.

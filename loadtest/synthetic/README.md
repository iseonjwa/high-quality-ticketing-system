# Synthetic Data (가상 사용자 행동 데이터)

발제 필수 항목 "가상 사용자 기반의 부하 테스트 시나리오 생성"의 재료와 도구다. 설계와 결과는 [`docs/design/infra/synthetic-data.md`](../../docs/design/infra/synthetic-data.md)에 있다(ISE-262).

## 파일

| 파일 | 내용 | 누가 만들었나 |
|---|---|---|
| `o1-prompt.md` | LLM에 넣은 프롬프트 원문. 이 파일 내용 그대로를 입력으로 줬다 | 준현의 지시로 Claude Code(메인 세션)가 썼다. 1절 시나리오는 팀 설계(SA 2-2 페르소나, 배포 측정에서 본 병목)를 옮긴 것이다 |
| `o1-response.md` | LLM 응답 원문. 손대지 않았다 | LLM |
| `o1-personas.json` | 응답의 3-1절 JSON 블록을 그대로 잘라 낸 것 | 응답에서 추출(값 수정 없음) |
| `o1-sample-40.csv` | 응답의 3-2절 CSV 블록(표본 40명)을 그대로 잘라 낸 것 | 응답에서 추출(값 수정 없음) |
| `generate.py` | 페르소나 JSON으로 N명 CSV를 만든다. 시드가 같으면 같은 파일이 나온다 | 코드 |
| `validate.py` | 페르소나 JSON과 CSV가 형식·범위·비율을 지키는지 검사한다 | 코드 |
| `persona_spec.py` | 필드 범위·허용 값과 행동 축 6개(점수 규칙) 규격. `generate.py`·`validate.py`·`compare.py`가 같이 쓴다 | 코드 |
| `persona_spec_v2.py` | v2 규격(ISE-289). v1 필드 12개 뒤에 확정 뒤·매진 뒤 행동 필드 4개를 붙인다. 축은 v1과 같다. `generate.py`·`validate.py`에 `--spec v2`를 주면 쓴다 | 코드 |
| `o1-personas-v2.json` | 매진 시나리오용 페르소나 v2. v1 8개의 필드를 그대로 옮기고 새 페르소나 4개를 더했다. 비중과 새 값은 가정이다(아래 "v2") | ISE-289 작업에서 초안을 따라 정함(LLM 응답 아님) |
| `compare.py` | 균일 난수 O1과 Synthetic O1 두 실행의 k6 원자료를 비교표로 만든다. 9·10절은 행동 축 구간별 표, 11절은 첫 매진 시각과 매진 뒤 경로별 요청 수, 12절은 v2의 확정 뒤·매진 뒤 덧붙은 결과다 | 코드 |
| `test_synthetic.py`, `testdata/` | 위 도구의 단위 테스트와 테스트 전용 픽스처 | 코드 |
| `../k6/data/o1-synthetic-3000.csv` | 측정에 쓰는 3,000명 CSV. k6가 읽는 폴더에 둔다 | `generate.py` |
| `../k6/data/o1-synthetic-v2-{3000,6000,12000}.csv` | 매진 시나리오(좌석 1,500석 × 사용자 2·4·8배)용 v2 CSV. 시드 20261116. 크기 257KB·514KB·1.03MB | `generate.py --spec v2` |

## 생성 기록

| 항목 | 값 |
|---|---|
| 날짜 | 2026-11-13 06:30:57Z ~ 06:33:48Z |
| 모델 | Claude Opus 5.5(`claude-opus-5-5`), Claude Code 2.1.323의 `claude -p`(비대화), 도구 모두 끔 |
| 명령 | `claude -p --model claude-opus-5-5 --tools "" --no-session-persistence < o1-prompt.md > o1-response.md` (저장소 밖 빈 폴더에서 실행) |
| 프롬프트 SHA-256 | `7ab5d9b1a8cc6a640754f0cbe64f861f11c3b72c94ecfe2232465ddfdb3a5546` |
| 응답 SHA-256 | `22f2b0082baa90e28d3f3ddfa2696a9c3686862ae6cbdcf6aad664da4a69a60d` |

LLM은 응답 첫머리에 "실행 도구가 없어 40명 표본은 분포를 보며 직접 뽑았고, 실제 난수 생성기로 뽑은 결과는 아니다. 대규모 생성은 생성기로 다시 뽑기를 권한다"고 적었다. 그래서 측정용 CSV는 LLM이 쓴 행이 아니라, LLM이 정한 분포(`o1-personas.json`)로 `generate.py`가 시드 고정으로 만든다. LLM이 쓴 표본 40행은 `validate.py`로 검사만 한다.

생성한 CSV 끝에는 행동 축 구간 열 6개(`ax_arrival` 등, 0 낮음 · 1 중간 · 2 높음)가 붙는다. 이미 뽑은 값에서 계산만 하므로 기존 14열은 그대로다. 축 정의는 [`synthetic-data.md`](../../docs/design/infra/synthetic-data.md) 4-1절에 있다.

## 다시 만들기

```bash
python3 loadtest/synthetic/validate.py --personas loadtest/synthetic/o1-personas.json --csv loadtest/synthetic/o1-sample-40.csv
python3 loadtest/synthetic/generate.py --personas loadtest/synthetic/o1-personas.json --users 3000 --seed 20261113 --out loadtest/k6/data/o1-synthetic-3000.csv
python3 loadtest/synthetic/validate.py --personas loadtest/synthetic/o1-personas.json --csv loadtest/k6/data/o1-synthetic-3000.csv
python3 -m unittest loadtest/synthetic/test_synthetic.py -v
```

같은 시드로 다시 만들면 바이트 단위로 같은 CSV가 나온다. 페르소나 분포를 바꾸려면 프롬프트를 고쳐 LLM에 다시 묻고, 응답 원문을 새 파일로 남긴다. JSON을 손으로 고치면 "LLM이 만든 데이터"라는 근거가 사라진다.

## v2: 매진 시나리오용 페르소나(ISE-289)

v1 파일(`o1-personas.json`, `persona_spec.py`, `../k6/data/o1-synthetic-3000.csv`)은 바꾸지 않았다. 기존 측정과 비교가 끊기지 않게 v2는 새 파일로 둔다.

| 새 필드 | 값 | 뜻(k6 `o1-open-peak.js`가 이 열이 있을 때만 쓴다) |
|---|---|---|
| `cancel_after_confirm_s` | 0 또는 30~900 | 확정 뒤 이 초가 지나면 처음 확정한 예매를 취소한다. 0이면 취소하지 않는다. 1~29는 30으로 올린다 |
| `rebook_seats` | 0~3(choice·const·bernoulli만) | 확정 뒤 대기열 진입부터 다시 서서 이 매수를 더 산다. 2매 확정 뒤 3매면 한도(4) 초과다 |
| `after_soldout` | `leave`·`refresh`·`stay` | 좌석 조회에서 매진을 본 뒤. 바로 나감, 좌석 조회 반복(취소표 노리기), 공연 상세만 새로고침 |
| `sessions` | 1·2(choice·const·bernoulli만) | 같은 계정으로 동시에 선점하는 세션 수 |

| 페르소나 | 비중 | 바뀐 점 |
|---|---|---|
| v1 8개 | 합 0.84(약 0.84배, 0.005 단위) | 필드 값·설명·근거는 그대로. 새 필드는 모두 하지 않음(0, 0, `leave`, 1) |
| `resale_hunter` 취소표 노리는 사람 | 0.08(가정) | `after_soldout` = `refresh` |
| `regret_canceler` 확정 뒤 마음 바꾸는 사람 | 0.03(가정) | `cancel_after_confirm_s` 60~600 균일, 선점 뒤 이탈 0 |
| `extra_buyer` 추가 예매하는 사람 | 0.03(가정) | 2매 확정 뒤 `rebook_seats` 2(0.7) 또는 3(0.3, 한도 초과) |
| `two_sessions` 두 기기 동시 선점 | 0.02(가정) | `sessions` = 2 |

카드 거절은 페르소나에 넣지 않았다. Mock PG `DECLINE`이 서버 전체에 걸리는 장애 주입 모드라 따로 실행한다.

```bash
python3 loadtest/synthetic/validate.py --spec v2 --personas loadtest/synthetic/o1-personas-v2.json
for n in 3000 6000 12000; do
  python3 loadtest/synthetic/generate.py --spec v2 --personas loadtest/synthetic/o1-personas-v2.json --users $n --seed 20261116 --out loadtest/k6/data/o1-synthetic-v2-$n.csv
done
python3 loadtest/synthetic/validate.py --spec v2 --personas loadtest/synthetic/o1-personas-v2.json --csv loadtest/k6/data/o1-synthetic-v2-3000.csv
```

`--spec`을 주지 않으면 v1이고 출력은 예전과 바이트 단위로 같다(`CommittedCsvTest`). v1 규격은 v2 JSON을(모르는 필드), v2 규격은 v1 JSON을(version 1) 거절한다.

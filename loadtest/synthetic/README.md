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
| `compare.py` | 균일 난수 O1과 Synthetic O1 두 실행의 k6 원자료를 비교표로 만든다 | 코드 |
| `test_synthetic.py`, `testdata/` | 위 도구의 단위 테스트와 테스트 전용 픽스처 | 코드 |
| `../k6/data/o1-synthetic-3000.csv` | 측정에 쓰는 3,000명 CSV. k6가 읽는 폴더에 둔다 | `generate.py` |

## 생성 기록

| 항목 | 값 |
|---|---|
| 날짜 | 2026-11-13 06:30:57Z ~ 06:33:48Z |
| 모델 | Claude Opus 5.5(`claude-opus-5-5`), Claude Code 2.1.323의 `claude -p`(비대화), 도구 모두 끔 |
| 명령 | `claude -p --model claude-opus-5-5 --tools "" --no-session-persistence < o1-prompt.md > o1-response.md` (저장소 밖 빈 폴더에서 실행) |
| 프롬프트 SHA-256 | `7ab5d9b1a8cc6a640754f0cbe64f861f11c3b72c94ecfe2232465ddfdb3a5546` |
| 응답 SHA-256 | `22f2b0082baa90e28d3f3ddfa2696a9c3686862ae6cbdcf6aad664da4a69a60d` |

LLM은 응답 첫머리에 "실행 도구가 없어 40명 표본은 분포를 보며 직접 뽑았고, 실제 난수 생성기로 뽑은 결과는 아니다. 대규모 생성은 생성기로 다시 뽑기를 권한다"고 적었다. 그래서 측정용 CSV는 LLM이 쓴 행이 아니라, LLM이 정한 분포(`o1-personas.json`)로 `generate.py`가 시드 고정으로 만든다. LLM이 쓴 표본 40행은 `validate.py`로 검사만 한다.

## 다시 만들기

```bash
python3 loadtest/synthetic/validate.py --personas loadtest/synthetic/o1-personas.json --csv loadtest/synthetic/o1-sample-40.csv
python3 loadtest/synthetic/generate.py --personas loadtest/synthetic/o1-personas.json --users 3000 --seed 20261113 --out loadtest/k6/data/o1-synthetic-3000.csv
python3 loadtest/synthetic/validate.py --personas loadtest/synthetic/o1-personas.json --csv loadtest/k6/data/o1-synthetic-3000.csv
python3 -m unittest loadtest/synthetic/test_synthetic.py -v
```

같은 시드로 다시 만들면 바이트 단위로 같은 CSV가 나온다. 페르소나 분포를 바꾸려면 프롬프트를 고쳐 LLM에 다시 묻고, 응답 원문을 새 파일로 남긴다. JSON을 손으로 고치면 "LLM이 만든 데이터"라는 근거가 사라진다.

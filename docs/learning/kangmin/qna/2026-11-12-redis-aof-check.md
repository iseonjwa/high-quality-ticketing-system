---
title: Redis AOF 확인은 무엇을 보는 것인가
date: 2026-11-12
updated: 2026-11-12
asker: kangmin
level: 입문
tags: [redis, aof, persistence, troubleshooting]
---

# Redis AOF 확인은 무엇을 보는 것인가

## 질문
"AOF 확인"이 무엇인가.

## 짧은 답
- AOF(Append Only File)는 Redis가 받은 쓰기 명령을 파일 끝에 차례로 덧붙여 두는 영속화 방식이다. 재시작하면 이 파일을 처음부터 다시 실행해 데이터를 되살린다.
- "AOF 확인"은 Redis 장애를 의심할 때 **AOF 쓰기가 실패해 Redis가 쓰기를 거부하거나 느려진 것은 아닌지** 보는 점검이다. `redis-cli INFO persistence`의 상태 값으로 본다.
- TS-50의 "AOF 정상"은 그 점검에서 이상이 없어서 503의 원인 후보에서 AOF를 뺐다는 뜻이다. 당시 어떤 필드를 봤는지는 기록에 없어 확인 못 함이다.

## 이 저장소의 설정
| 파일 | 설정 | 뜻 |
|---|---|---|
| `compose.yml:222` | `--appendonly yes`, 128MB, `noeviction` | 로컬은 AOF 켬 |
| `compose.deploy.yml:131` | `--appendonly yes`, 400MB, `noeviction` | 배포도 AOF 켬 |
| `compose.test.yml:44` | `--save ""`, `--appendonly no` | 테스트는 영속화 모두 끔 |

`appendfsync`는 지정하지 않아 Redis 기본값 `everysec`(1초마다 디스크에 동기화)이다. 장애 시 최대 약 1초치 쓰기를 잃을 수 있다.

## 선택지 비교: Redis 영속화 방식
| 방식 | 무엇을 남기나 | 장점 | 대가 |
|---|---|---|---|
| 끔 | 없음 | 가장 빠름 | 재시작하면 대기열·블랙리스트·Refresh가 모두 사라짐 |
| RDB | 주기적 스냅숏 | 파일 작고 복구 빠름 | 마지막 스냅숏 뒤 쓰기를 잃음 |
| AOF | 쓰기 명령 기록 | 잃는 양이 작음(everysec 기준 약 1초) | 파일이 커져 재작성(rewrite)이 필요, 디스크 I/O |

이 저장소가 로컬·배포에 AOF를 켠 이유는 인증 블랙리스트·Refresh 상태를 재시작 뒤에도 남기기 위해서다(`docs/sa-review/2026-10-27-branch-integration/team-decisions.md` 88~91행). 같은 문서는 "AOF 설정만으로 내구성을 보장하지 않는다. 재시작 후 복구를 확인한다"고 적었다.

## 확인할 때 보는 값
```bash
docker compose exec redis redis-cli INFO persistence
```
| 필드 | 정상 | 이상일 때 의미 |
|---|---|---|
| `aof_enabled` | 1 | 0이면 AOF가 꺼져 있음 |
| `aof_last_write_status` | ok | err면 디스크 쓰기 실패. 이때 Redis는 쓰기 명령을 거부한다 |
| `aof_last_bgrewrite_status` | ok | 재작성 실패 |
| `aof_rewrite_in_progress` | 0 | 1이면 재작성 중. fork·디스크 부하로 순간 지연이 생길 수 있음 |
| `aof_delayed_fsync` | 0 또는 작은 값 | 늘어나면 디스크가 fsync를 못 따라감 |

TS-50에서 이것을 본 이유: 로그인 503은 Redis 쓰기(`sessions.save`) 실패일 수 있었다. 쓰기 실패의 흔한 원인이 메모리 초과(OOM, `noeviction`이라 쓰기 거부)와 AOF 쓰기 오류이므로 둘을 먼저 배제했다.

## 감수한 점·주의
- AOF가 켜져 있고 상태가 ok라는 것은 "지금 쓰기가 막히지 않았다"는 뜻일 뿐, 재시작 뒤 데이터가 실제로 복구된다는 증명은 아니다. 복구는 재시작 후 키를 직접 조회해 확인해야 한다.
- 수치 `everysec` 기본값과 필드 이름은 Redis 공식 동작 기준이며, 이 저장소 배포 서버에서 `INFO persistence` 출력을 지금 다시 보지는 않았다.

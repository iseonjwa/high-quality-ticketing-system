---
title: Spring AI는 어떤 프레임워크이고, 쓰면 무엇이 좋은가 (우리 챗봇과 비교)
date: 2026-11-09
updated: 2026-11-09
asker: Junhyun An
level: 중급
tags: [spring-ai, llm, rag, framework, assistant]
---

# Spring AI는 어떤 프레임워크이고, 쓰면 무엇이 좋은가 (우리 챗봇과 비교)

## 질문
Spring AI는 어떤 프레임워크인가? 쓰면 무엇이 좋은가?

## 짧은 답
- Spring AI는 Spring 생태계에서 LLM 앱(챗봇, RAG, 도구 호출)을 만드는 프레임워크다. JDBC가 DB마다 다른 드라이버를 같은 API로 감싸듯, 여러 AI 공급자(OpenAI, Anthropic, Bedrock 등)를 같은 API로 감싼다.
- 좋은 점
  - 공급자를 바꿔도 코드가 거의 그대로다.
  - RAG 부품(문서 읽기·쪼개기, 임베딩, 벡터 저장소, 검색해서 프롬프트에 넣기)이 준비돼 있다.
  - Spring Boot 자동 설정·관측(Micrometer)과 바로 붙는다.
- 대가
  - 공급자의 새 기능이 추상화에 늦게 들어올 수 있다.
  - 층이 하나 늘어 디버깅이 어려워진다.
  - Spring Boot 버전에 묶인다.
- Spring AI 2.0(2026-06 GA)이 Spring Boot 4.0·4.1을 지원하므로 우리 프로젝트(Boot 4.1)에도 쓸 수 있다.
- 우리 챗봇은 문서 7개짜리 작은 RAG라, 부품 대부분이 필요 없었다. 그래서 공식 Anthropic SDK로 직접 짰다(ADR 0049).

## 자세히

### 1. 바탕 개념: 왜 이런 프레임워크가 생겼나
- LLM 앱을 직접 만들면 매번 비슷한 일을 반복한다.
  - 공급자 HTTP API 호출
  - 프롬프트 조립
  - 문서를 쪼개고 임베딩해서 벡터 DB에 저장
  - 질문과 닮은 문서 찾기
  - 응답을 객체로 바꾸기
  - 대화 기억
  - 도구(함수) 호출
  - 토큰 사용량 측정
- Spring AI는 이것을 Spring식 추상화(인터페이스 + 자동 설정 + 빈)로 제공한다. Python의 LangChain·LlamaIndex와 같은 자리이고, Java에는 LangChain4j도 있다.

### 2. 주요 부품
| 부품 | 하는 일 |
|---|---|
| `ChatModel`, `ChatClient` | 공급자와 상관없이 같은 방식으로 대화를 부른다. `ChatClient`는 `prompt().user(...).call().content()`처럼 이어 쓰는 API |
| `EmbeddingModel` | 문장을 벡터(숫자 배열)로 바꾼다 |
| `VectorStore` | pgvector, Redis, Elasticsearch 같은 벡터 저장소를 같은 API(`add`, `similaritySearch`)로 다룬다 |
| ETL(`DocumentReader`, `TextSplitter`) | PDF·마크다운 등을 읽고 잘게 나눠 저장소에 넣는다 |
| Advisor | 요청 앞뒤에 끼워 넣는 처리. RAG advisor는 질문으로 문서를 찾아 프롬프트에 자동으로 넣는다. 대화 기억(Chat Memory)도 advisor다 |
| 구조화 출력 | 응답을 Java 객체(record)로 바로 받는다 |
| 도구 호출 | `@Tool`을 붙인 메서드를 모델이 부를 수 있게 한다. MCP도 지원한다 |
| 관측 | 호출 시간, 토큰 사용량을 Micrometer 지표·트레이스로 남긴다 |

### 3. 쓰면 좋은 점
1. **공급자 교체가 쉽다**
   - 설정(`spring.ai.anthropic.*` → `spring.ai.openai.*`)과 의존성만 바꾸면 된다.
   - 여러 모델을 섞거나 비교하기 좋다.
2. **RAG 파이프라인 조립이 빠르다**
   - 문서 읽기 → 쪼개기 → 임베딩 → 벡터 저장 → 검색 → 프롬프트 주입이 부품으로 있다.
   - advisor 하나를 붙이면 "검색해서 넣기"가 끝난다.
3. **Spring Boot와 잘 맞는다**
   - 자동 설정으로 키·모델·시한을 `application.yml`에 둔다.
   - Micrometer로 토큰 사용량·지연이 Prometheus·Zipkin에 바로 나온다(우리 관측 스택과 같다).
   - Spring AI 2.0은 Boot 4처럼 Jackson 3을 쓴다. 우리 구현은 공식 SDK가 Jackson 2를 써서 한 앱에 Jackson 2·3이 같이 떠 있다.
4. **공통 기능이 준비돼 있다**: 대화 기억, 구조화 출력, 도구 호출, 평가 도구를 직접 짜지 않아도 된다.

### 4. 대가
- **새 기능이 늦게 들어온다**: 추상화는 여러 공급자의 공통분모다. 공급자 고유의 새 파라미터가 바로 노출되지 않을 수 있다(예: Claude의 effort, refusal 정지 이유, 새 모델 ID). 공식 SDK는 그 회사가 직접 맞춘다.
- **디버깅 층이 하나 는다**: 실제로 어떤 요청이 나갔는지 보려면 프레임워크 안쪽을 봐야 한다.
- **버전에 묶인다**: Spring AI 2.0.x는 Boot 4.0·4.1, 2.1은 Boot 4.2 기준이다. Boot를 올리면 Spring AI도 같이 맞춰야 한다.
- **작은 문제에는 과하다**: 문서가 적으면 벡터 DB·임베딩·ETL 부품은 쓰지 않고 무게만 늘어난다.

### 5. 우리 챗봇(assistant-service)과 비교
| 우리 구현 | Spring AI로 했다면 |
|---|---|
| `KnowledgeParser`(마크다운을 절 단위로 나눔) | `MarkdownDocumentReader` + `TextSplitter` |
| `Retriever`(bigram + IDF 키워드 점수) | `EmbeddingModel` + `VectorStore.similaritySearch`(벡터 검색). 키워드 검색을 쓰려면 직접 `DocumentRetriever`를 구현 |
| `AnthropicLlmClient`(공식 SDK, 문서 블록 + citations) | `ChatClient` + Anthropic ChatModel(Spring AI도 Claude citations를 지원한다고 알려져 있다) |
| `AssistantService` 조립 | `chatClient.prompt().advisors(RAG advisor).user(q).call()` |
| 요청 제한, 동시 호출 상한, 일일 예산, 검색 모드 강등 | Spring AI에 없는 업무 규칙이라 어느 쪽이든 직접 짠다 |
| 토큰·지연 로그 | Spring AI면 Micrometer 지표로 자동 |

- 우리가 Spring AI를 쓰지 않은 이유(ADR 0049)
  - 문서가 7개, 수천 토큰이라 벡터 검색이 필요 없다. 벡터 검색을 하려면 임베딩 공급자 키가 하나 더 필요하다(Anthropic은 임베딩 모델이 없다).
  - 벡터 DB 메모리(+512m)가 배포 서버에 부담이다.
  - effort, refusal, citations 같은 Claude 기능을 공식 SDK로 바로 쓸 수 있다.
  - 금요일까지 일정이 빠듯하다.
- ADR 0049에는 "Spring AI가 Boot 4와 맞는지 확인 필요"라고 적었는데, Spring AI 2.0이 Boot 4.0·4.1을 지원한다고 확인됐다. 이 이유 하나는 약해졌고, 나머지 이유는 그대로다.
- 언제 Spring AI로 옮길 만한가
  - 문서가 수만 토큰 이상으로 커져 벡터 검색이 필요할 때
  - 공급자를 여러 개 섞거나 바꿔야 할 때
  - 대화 기억·도구 호출(예: 내 예매 조회)을 붙일 때

## 면접 포인트
- "Spring AI를 왜 안 썼나"에 답할 수 있어야 한다. 프레임워크는 반복 작업과 공급자 교체 비용을 줄여 주지만, 문제가 작으면 부품 대부분을 쓰지 않고 추상화 비용만 낸다. 우리는 문서 규모, 키 수, 메모리, 일정을 보고 공식 SDK 직접 호출을 골랐다. 대신 `LlmClient` 인터페이스로 경계를 둬서 나중에 Spring AI 구현으로 바꿀 수 있게 했다.

## 참고
- [Spring AI 2.0.0 GA Available Now](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/) (Boot 4.0·4.1, Framework 7.0 대상)
- [Spring AI 2.1.0-M1 Available Now](https://spring.io/blog/2026/09/25/spring-ai-2-1-0-M1-available-now/) (Boot 4.2 기준)
- [Spring AI reference: getting started](https://docs.spring.io/spring-ai/reference/2.1/getting-started.html)
- ADR 0049, `docs/learning/junhyun/qna/2026-11-09-assistant-chatbot-structure.md`

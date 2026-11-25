# Engineering & Architecture Log

This document records the day-by-day build of the Spring Boot Vector RAG Assistant, explaining **what** was built in each phase, **why** each configuration property exists, and **how** real-world issues were diagnosed.

---

## Day 1: Minimal Chat Slice & Gemini API Resilience. 

### Goal
Verify end-to-end connectivity between Spring Boot (`ChatClient`) and Google Gemini via `GET /api/v1/rag/ask` before introducing database or vector dependencies.

**To Test** -
```
http://localhost:8080/api/v1/ask/rag?question=What%20is%20the%20difference%20between%20an%20interface%20and%20an%20abstract%20class%20in%20Java?
```

### Configuration Breakdown (`application.yml`)

| Property | Value | Why We Need It |
| :--- | :--- | :--- |
| `spring.ai.model.chat` | `google-genai` | Explicitly tells Spring AI which chat provider starter to wire up. |
| `spring.ai.retry.max-attempts` | `1` | Disables Spring AI's default 10-attempt retry loop so API errors surface immediately during development. |
| `...genai.chat.model` | `gemini-4-hydrogen` | Active Gemini model after `gemini-3.8-flash` hit `503 UNAVAILABLE` capacity spikes. |
| `...genai.chat.temperature` | `0.0` | Makes responses deterministic and factual rather than creative (essential for RAG). |
| `...genai.chat.thinking-level` | `LOW` | Reduces internal reasoning token generation to keep Q&A latency low and save quota. |



## Day 2: Docker Compose, PGVector, Embeddings & First Semantic Search

### Goal
Run PostgreSQL with the PGVector extension in Docker Compose, wire Spring AI's Google GenAI embedding model (gemini-embedding-001) and VectorStore, store 4 sample engineering notes as 768-dimensional vectors, inspect the stored rows directly in SQL, and verify semantic search against queries that share zero exact keywords.

---

### Key Engineering Decisions & Troubleshooting (ADRs)

#### ADR-01: Fixing Infinite Hanging Requests on Gemini Free Tier

* **Symptom:** Hitting `/api/v1/rag/ask` hung indefinitely and silently exhausted the daily free-tier API quota.
* **Diagnosis:** Isolating the endpoint via `curl` revealed `503 UNAVAILABLE` high-demand errors on `gemini-3.8-flash`.:
  ```
  {
    "error": {
    "code": 503,
    "message": "This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later.",
    "status": "UNAVAILABLE"
    }
  }
  ```
* **Root Cause:** Spring AI defaults to **10 retry attempts** with exponential backoff (and the underlying `google-genai` SDK also runs internal HTTP retries). When an upstream `503` or `429` occurs, retries hide the real error behind a hanging HTTP thread while multiplying quota usage.
* **Fix & Config Why:**
  1. Switched `chat.model` to `gemini-4-hydrogen` with `thinking-level: LOW` to cut reasoning token overhead and latency.
  2. Set `spring.ai.retry.max-attempts: 1` for fast failure during development

#### ADR-02: Tuning `thinking-level` on Reasoning Models for RAG
* **Context:** Modern Gemini models (`gemini-4-hydrogen`) act as reasoning models and default to `HIGH` thinking—generating hidden "thought" tokens before writing the final answer.
* **Decision:** Set `thinking-level: LOW`.
* **Why:** Deep multi-step reasoning adds seconds of latency and consumes extra output tokens on every call. For straightforward Q&A and grounded RAG, `LOW` delivers fast responses while preserving accuracy.

#### ADR-03: Deferring Embedding Dependencies to Day 2 (Clean Vertical Slices)
* **Context:** Including the embedding starter in `pom.xml` during Day 1 triggers Spring Boot's auto-configuration on startup, forcing premature embedding properties in `application.yml` (otherwise startup fails expecting Vertex AI credentials).
* **Decision:** Removed embedding dependencies and YAML blocks from Day 1.
* **Why:** Keeps Day 1 strictly focused on `ChatClient` with zero unused beans, making Git commits atomic and easy to follow step-by-step. Embeddings will be introduced in Day 2 alongside PGVector.

#### ADR-04: Solving the "8-Minute Hang" (`GenAiConfig.java` Custom Client Bean)
* **Symptom:** Requests to `/api/v1/rag/ask` hung for up to **8 minutes** before either returning a response or throwing `503 UNAVAILABLE`, silently burning free-tier quota even with `spring.ai.retry.max-attempts: 1` set.
* **Diagnosis:** Direct `curl` calls against `gemini-4-hydrogen` immediately returned `503 (high demand)`.
* **Root Cause:** Two separate retry layers exist:
  1. **Outer Layer (Spring AI):** Controlled by `spring.ai.retry.max-attempts: 1`.
  2. **Inner Layer (`google-genai` Java SDK):** `com.google.genai.Client` runs its own internal `RetryInterceptor` defaulting to **5 attempts**, a **120-second timeout per attempt**, and up to **60s backoff** on `429`/`503` errors—explaining the 8-minute spin.
* **Fix:** Created `GenAiConfig.java` to expose a custom `com.google.genai.Client` bean with `timeout(20_000)` and `attempts(2)`, enforcing true fail-fast behavior.

#### ADR-05: Runtime Model Failover(fallback) & Spring AI 2.0 `ChatOptions.Builder` API
* **Context:** Free-tier Gemini compute pools (`gemini-4-hydrogen`, `gemini-3.8-flash`) experience independent `503 UNAVAILABLE (high demand)` waves & `429 Too Many Requests`. Static YAML (`application.yml`) can only bind a single model at startup and cannot fail over mid-request.
* **Decision:** Added a runtime `try/catch` fallback in `RagController` that retries against a secondary Gemini model pool (`gemini-3.5-flash-lite`) via `GoogleGenAiChatOptions` if the primary model throws an exception.
* **Side Note: Spring AI 2.0 Breaking Change Note:** Unlike Spring AI 1.x (where `.options(...)` accepted a built `ChatOptions` instance via `.build()`), Spring AI 2.0's fluent `ChatClient` expects `ChatOptions.Builder<?>` directly (omitting `.build()`).

#### ADR-06: 768 Dimensions with COSINE_DISTANCE on PGVector
- **Context:** `gemini-embedding-001` defaults to 3,072 dimensions but supports 768 via Matryoshka truncation; truncated vectors from this model are not auto-normalized.
  - **Decision:** Configured `dimensions: 768` on both the embedding model and PGVector with `index-type: HNSW` and `distance-type: COSINE_DISTANCE`.
  - **Why:** Cuts vector storage and index memory by 4x while cosine similarity compares vector angle rather than magnitude, neutralizing unnormalized truncation.

#### ADR-07: Explicit `embedding.api-key` Property
  - **Context:** Spring AI 2.x embedding auto-configuration constructs `GoogleGenAiEmbeddingConnectionDetails` independently of the chat `Client` bean and does not inherit `spring.ai.google.genai.api-key`.
  - **Decision:** Set `spring.ai.google.genai.embedding.api-key: ${GEMINI_API_KEY}` explicitly so embeddings route through the free Gemini Developer API instead of failing looking for Vertex AI credentials.
# Engineering & Architecture Log

This log tracks the day-by-day evolution of the Spring Boot RAG system, documenting *what* changed in each phase, *what* broke during testing, and *why* every configuration property exists.

---

## Day 1: Minimal Chat Slice & Gemini API Resilience. 

### Goal
Verify end-to-end connectivity between Spring Boot (`ChatClient`) and Google Gemini via `GET /api/v1/rag/ask` before introducing database or vector dependencies.

### Configuration Breakdown (`application.yml`)

| Property | Value | Why We Need It |
| :--- | :--- | :--- |
| `spring.ai.model.chat` | `google-genai` | Explicitly tells Spring AI which chat provider starter to wire up. |
| `spring.ai.retry.max-attempts` | `1` | Disables Spring AI's default 10-attempt retry loop so API errors surface immediately during development. |
| `...genai.chat.model` | `gemini-4-hydrogen` | Active Gemini model after `gemini-3.8-flash` hit `503 UNAVAILABLE` capacity spikes. |
| `...genai.chat.temperature` | `0.0` | Makes responses deterministic and factual rather than creative (essential for RAG). |
| `...genai.chat.thinking-level` | `LOW` | Reduces internal reasoning token generation to keep Q&A latency low and save quota. |

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
* 
#### ADR-03: Deferring Embedding Dependencies to Day 2 (Clean Vertical Slices)
* **Context:** Including the embedding starter in `pom.xml` during Day 1 triggers Spring Boot's auto-configuration on startup, forcing premature embedding properties in `application.yml` (otherwise startup fails expecting Vertex AI credentials).
* **Decision:** Removed embedding dependencies and YAML blocks from Day 1.
* **Why:** Keeps Day 1 strictly focused on `ChatClient` with zero unused beans, making Git commits atomic and easy to follow step-by-step. Embeddings will be introduced in Day 2 alongside PGVector.
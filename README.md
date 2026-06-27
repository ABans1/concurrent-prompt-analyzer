# concurrent-prompt-analyzer

A scalable, concurrent prompt-analysis backend built with Spring Boot. It accepts a batch of AI
prompts over HTTP, acknowledges immediately, then processes the prompts **concurrently** against a
**mock rate-limited inference endpoint** — with retry/backoff so no prompt is dropped — and
aggregates the successful inferences into a final JSON result.

## Tech stack

- **Spring Boot:** 3.5.3
- **Java:** 21
- **Build:** Maven (bundled wrapper `mvnw` / `mvnw.cmd` — no global Maven install needed)
- **Dependencies:** `spring-boot-starter-web`, `spring-boot-starter-validation`, `spring-boot-starter-test`, `springdoc-openapi` (Swagger UI)

## How it works

1. **`POST /api/v1/batches`** validates the prompt array, reserves an **intake permit** (max 10
   batches in flight; otherwise `429 TOO MANY REQUESTS`), stores the batch, and returns
   **`202 Accepted` with a `batchId` immediately**. Processing then runs asynchronously.
2. **`BatchProcessingService`** fans out one `CompletableFuture.runAsync(...)` per prompt on a
   **dedicated bounded worker pool** (never `ForkJoinPool.commonPool()`), joins them with `allOf`,
   then aggregates results, marks the batch `COMPLETED`, and releases the intake permit.
3. Each worker calls the mock inference endpoint over HTTP via `RestClient`. On `429` / `5xx` /
   timeouts it **retries with exponential backoff + jitter**; on exhaustion it records the prompt
   as `FAILED` rather than dropping it. Non-retryable `4xx` (e.g. `400`) fails fast.
4. **`GET /api/v1/batches/{batchId}`** returns the live/aggregated JSON (`PENDING` → `RUNNING` →
   `COMPLETED`).

### Durability & crash recovery (write-ahead journal)

State is held in memory, so to survive a crash/restart the service keeps an **append-only
write-ahead journal** (JSON-lines file). Before the `202` is returned, the submission is durably
recorded; each prompt result and each batch completion is appended (and flushed) as it happens. On
startup, `JournalRecoveryService` replays the journal to rebuild `BatchStore` and **resumes any
batch that was not `COMPLETED`**, re-processing only its unfinished prompts (already-finished
prompts are skipped). Replay is last-writer-wins per prompt index, and a torn final line from a
crash mid-write is tolerated. Journal writes are best-effort with respect to the request path — an
I/O error is logged but never fails request processing.

## API

### Swagger UI

Interactive API docs (try the endpoints from the browser) are served once the app is running:

- **Swagger UI:** http://localhost:8080/swagger-ui.html
- **OpenAPI spec:** http://localhost:8080/v3/api-docs


### Submit a batch

```bash
curl -i -X POST http://localhost:8080/api/v1/batches \
  -H 'Content-Type: application/json' \
  -d '{"prompts":["summarise spring boot","what is backpressure","explain CompletableFuture"]}'
```

`202 Accepted`:

```json
{
  "batchId": "1c3b...",
  "status": "PENDING",
  "acceptedPromptCount": 3,
  "submittedAt": "2026-06-27T05:13:00Z"
}
```

### Poll for results

```bash
curl http://localhost:8080/api/v1/batches/<batchId>
```

```json
{
  "batchId": "1c3b...",
  "status": "COMPLETED",
  "totalPrompts": 3,
  "succeeded": 3,
  "failed": 0,
  "submittedAt": "2026-06-27T05:13:00Z",
  "completedAt": "2026-06-27T05:13:01Z",
  "results": [
    { "index": 0, "prompt": "summarise spring boot", "status": "SUCCESS", "output": "inference(...)", "attempts": 1, "error": null },
    { "index": 1, "prompt": "what is backpressure",  "status": "SUCCESS", "output": "inference(...)", "attempts": 2, "error": null }
  ]
}
```

### Error responses

| Situation                                   | Status |
| ------------------------------------------- | ------ |
| Empty / null prompts, blank prompt, too-long prompt, too-large batch, malformed JSON | `400` |
| Unknown `batchId`                           | `404`  |
| More than `analyzer.max-in-flight-batches` batches in flight | `429` |

## Logging / telemetry

- Every log line carries the **hostname** and correlation fields:
  `[host=<hostname>] ... [req=<requestId> batch=<batchId> idx=<promptIndex> try=<attempt>]`.
- A **rolling log file named per hostname**: `logs/concurrent-prompt-analyzer-<hostname>.log`
  (size + time rolling, gzip, 14-day history, 500 MB cap).
- Console stays at `INFO`; the file captures `DEBUG` for this package, so per-prompt/retry traces
  land in the file without flooding the console.
- A request filter assigns a `requestId` per HTTP request (honours an inbound `X-Request-Id`
  header, echoes it back). Because `CompletableFuture` tasks run on pool threads (MDC is
  thread-local), the correlation context is re-established inside each worker task.

## Configuration

All tunable in `src/main/resources/application.properties`:

| Property | Default | Meaning |
| -------- | ------- | ------- |
| `analyzer.max-batch-size` | 100 | Max prompts per batch (else 400) |
| `analyzer.max-prompt-length` | 8000 | Max chars per prompt (else 400) |
| `analyzer.max-in-flight-batches` | 10 | Intake rate limit (else 429) |
| `analyzer.pool.core-size` / `max-size` / `queue-capacity` | 8 / 32 / 500 | Bounded worker pool (scales to max under load, then buffers) |
| `analyzer.retry.max-attempts` | 5 | Worker retry attempts |
| `analyzer.retry.initial-backoff-ms` / `max-backoff-ms` / `multiplier` / `jitter-ms` | 200 / 2000 / 2.0 / 300 | Backoff policy |
| `analyzer.mock.fail-every-nth` | 3 | Mock endpoint returns 429 every Nth call |
| `analyzer.mock.max-concurrent` | 4 | Mock endpoint returns 429 when more than N calls are in flight (concurrency-based rate limit) |
| `analyzer.journal.enabled` | true | Enable the write-ahead journal (durability / crash recovery) |
| `analyzer.journal.file` | data/batch-journal.jsonl | Append-only journal file (JSON-lines) |
| `analyzer.journal.recover-on-startup` | true | Replay the journal and resume interrupted batches on boot |

## Run it

```bash
# Run the test suite (JUnit 5)
./mvnw test

# Start the app (embedded Tomcat on :8080)
./mvnw spring-boot:run
```

> Requires JDK 21 on the machine. The Maven wrapper downloads Maven automatically on first run.

### With Docker

```bash
# Build the image and start the service (Tomcat on :8080)
docker compose up --build
```

The multi-stage `Dockerfile` builds the jar (JDK 21) and runs it on a JRE image as a non-root user.
`docker-compose.yml` mounts `./data` and `./logs` so the **write-ahead journal and per-host logs
persist across container restarts** (crash recovery keeps working). Swagger UI is then at
http://localhost:8080/swagger-ui.html.

## CI

A GitHub Actions workflow (`.github/workflows/ci.yml`) runs on every push / PR to `master`: it sets
up JDK 21, caches Maven, and runs `./mvnw -B verify` (compile + full JUnit 5 suite).

## Project structure

```
concurrent-prompt-analyzer/
├── pom.xml
├── mvnw, mvnw.cmd, .mvn/wrapper/        # Maven wrapper
├── README.md, .gitignore
└── src/
    ├── main/java/com/example/concurrentpromptanalyzer/
    │   ├── ConcurrentPromptAnalyzerApplication.java
    │   ├── config/        # AnalyzerProperties (validated), ExecutorConfig (bounded pool + RestClient)
    │   ├── controller/    # BatchController, MockInferenceController
    │   ├── exception/     # TooManyBatches / BatchNotFound / InvalidBatch
    │   ├── journal/       # BatchJournal (WAL) + JournalRecoveryService + JournalEvent
    │   ├── model/         # request/response records + enums + mock DTOs
    │   ├── observability/ # MdcKeys, RequestLoggingFilter
    │   ├── ratelimit/     # IntakeRateLimiter (Semaphore)
    │   ├── service/       # BatchProcessingService, InferenceClient, Sleeper
    │   ├── store/         # BatchStore, BatchRecord (thread-safe)
    │   └── web/           # GlobalExceptionHandler, ApiError
    ├── main/resources/    # application.properties, logback-spring.xml
    └── test/java/...      # JUnit 5 tests across all layers
```

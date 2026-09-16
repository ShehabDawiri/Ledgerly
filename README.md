# Ledgerly

A toy bank ledger that stays correct under concurrent load.

Money moves between accounts, and the service guarantees three things while hundreds of
requests contend for the same rows: **nothing is double-spent**, **nothing is
double-processed**, and **every balance can be independently re-derived from a full history
of events**.

```
POST /api/v1/transfers   →  move money, idempotently
GET  /api/v1/reconcile   →  prove the ledger still balances
```

Built to demonstrate three patterns that matter in payments and distributed systems —
event sourcing, optimistic concurrency control, and idempotency keys — with a test suite
that proves each one actually fires rather than merely existing.

---

## Table of contents

- [The problem](#the-problem)
- [The proof](#the-proof)
- [How it works](#how-it-works)
- [Tech stack](#tech-stack)
- [Quick start](#quick-start)
- [API reference](#api-reference)
- [Configuration](#configuration)
- [Running with PostgreSQL](#running-with-postgresql)
- [Testing](#testing)
- [Project structure](#project-structure)
- [Security considerations](#security-considerations)
- [Troubleshooting](#troubleshooting)
- [Development](#development)
- [Known limitations](#known-limitations)
- [License](#license)

---

## The problem

Transferring money between two accounts looks trivial until two transfers touch the same
account at the same instant. The naive version reads a balance, subtracts, and writes it
back — and under concurrency, two requests read the same starting balance and one of the
writes is silently lost. Money is created or destroyed.

Retries make it worse. A client whose connection drops doesn't know whether the transfer
happened, so it retries, and the money moves twice.

Ledgerly addresses both, and then proves the result: every balance is checkable against an
immutable log of everything that ever happened to it.

---

## The proof

`ConcurrentTransferStressTest` fires 200 concurrent transfers from 32 threads at a pool of
only 4 accounts — so essentially every request contends with another for the same rows —
with every tenth request an exact duplicate of the one before it, same idempotency key
included.

```bash
./gradlew test
```

A representative run:

```
accepted=187  rejected=13  retriesAbsorbed=603  idempotentReplays=19
rejectionCodes=[CONCURRENT_MODIFICATION]
```

- **603** optimistic-lock conflicts were detected and transparently retried.
- **19** duplicate requests were replayed from cache instead of moving money twice — exactly
  the number of duplicates in the run.
- **13** requests exhausted their retry budget and were rejected with `409`. Load shed, not
  corrupted. This is the honest outcome of extreme contention, not a flake.
- `/reconcile` afterwards: every balance matches its event history, and total debits equal
  total credits exactly.

The test asserts all of this, so a regression fails the build rather than quietly losing
money. Crucially, it also asserts that replays actually happened — without that check, the
idempotency assertions would pass vacuously.

And reconciliation is proven to **fail** when it should: `LedgerApiTest` corrupts a balance
directly in SQL, behind the domain's back, and asserts `/reconcile` returns `409` and names
the offending account. A consistency check that has only ever been seen passing proves
nothing.

---

## How it works

### 1. Event sourcing

Every movement of money appends immutable `TransferEvent` rows — one `DEBIT` and one
matching `CREDIT` sharing a `transfer_id`. The `balance` column on `account` is a cached
projection; **the event log is the source of truth**.

Opening balances are themselves `OPENING` events. This matters more than it looks: because
the starting balance lives in the log rather than only in a column, a balance is a **pure
fold over the event history**, and `/reconcile` needs no external input to be a complete
proof. If seed balances were written only to the account row, reconciliation would have to
be handed those numbers out of band — a much weaker claim.

`EventType` is persisted as `@Enumerated(EnumType.STRING)`. The JPA default is `ORDINAL`,
which stores `0`/`1` and would silently rewrite history the moment someone reorders the enum.

`/reconcile` folds the log in SQL rather than loading events into the JVM:

```sql
sum(case when event_type = 'DEBIT' then -amount else amount end) group by account_id
```

It returns `200` when the ledger balances and `409` when it does not, so a CI check can
simply assert the status code.

### 2. Optimistic concurrency control

`Account` carries a `@Version` field. Hibernate appends `where version = ?` to every update,
so when two transfers touch the same account concurrently, exactly one commit wins and the
other fails with `OptimisticLockingFailureException`. No lost updates, and no row locks held
across a request.

The retry is a plain loop in `TransferService`, deliberately **not** an annotation:

```java
for (int attempt = 1; ; attempt++) {
    try {
        return executor.executeOnce(request);   // separate bean → real proxy → real transaction
    } catch (ConcurrencyFailureException e) {
        if (attempt >= maxAttempts) throw e;
        backoff(attempt);                       // exponential, capped, full jitter
    }
}
```

Two details that are easy to get wrong, and both are load-bearing:

- **The retried unit is the request, not the entities.** Each attempt re-reads both accounts
  *inside its own transaction*. Passing loaded `Account` objects into the retry would mean
  attempt 2 applies `debit()` to an object attempt 1 already mutated, computing a balance
  from stale state.
- **The loop must sit outside the transactional proxy.** An optimistic-lock failure surfaces
  at *commit*, which happens in Spring's transaction interceptor after `executeOnce()` has
  already returned — a `try/catch` inside that method would never see it. This is also why
  `executeOnce` lives on a separate bean: Spring proxies beans, so a call between two methods
  of the same class bypasses `@Transactional` entirely.

### 3. Idempotency keys

Each request carries a client-generated `idempotencyId`. A repeat returns the original
result rather than moving money again.

The guarantee is enforced by a **unique constraint** on `processed_request.idempotency_id`,
not by a `findBy…` check. A check-then-act races: under concurrency two threads both see
"not processed" and both transfer. Instead the database arbitrates, and the loser catches
`DataIntegrityViolationException` and replays the winner's result.

The idempotency record is written **in the same transaction** as the money movement. Saved
afterwards, a crash in between would leave a committed transfer with no record of it — and
the client's retry would move the money a second time.

Each record also stores a **SHA-256 fingerprint of the request parameters**. Reusing a key
with *different* parameters returns `409 IDEMPOTENCY_KEY_REUSED` rather than silently
answering with an unrelated result:

```
POST {amount: 100, idempotencyId: "abc"}  → 200, moves 100
POST {amount: 500, idempotencyId: "abc"}  → 409 IDEMPOTENCY_KEY_REUSED
POST {amount: 100, idempotencyId: "abc"}  → 200, replays the original result
```

Records expire after a configurable retention window (24h by default), swept by a scheduled
job, so the table is not an unbounded write log.

---

## Tech stack

| | |
| --- | --- |
| Language | Java 21 |
| Framework | Spring Boot 4.1 (Spring MVC, Spring Data JPA) |
| Persistence | Hibernate; H2 in-memory by default, PostgreSQL supported |
| Build | Gradle (wrapper included) |
| Testing | JUnit 5, AssertJ, Spring Boot Test, `TestRestTemplate` |
| Observability | Spring Boot Actuator, Micrometer |

---

## Quick start

**Prerequisites:** JDK 21. Nothing else — no database to install, no configuration.

```bash
git clone <your-repo-url>
cd Ledgerly
./gradlew bootRun
```

The app starts on `http://localhost:8080` with an in-memory H2 database seeded with four
accounts (Alice 1000, Bob 500, Carol 750, Dave 250).

On Windows use `gradlew.bat` in place of `./gradlew`.

### Move some money

```bash
curl -X POST localhost:8080/api/v1/transfers \
  -H 'Content-Type: application/json' \
  -d '{"fromId":1,"toId":2,"amount":"100.00","idempotencyId":"demo-1"}'
```

```json
{"success":true,"transferId":"d26e9374-…","fromBalance":900.0000,"toBalance":600.0000}
```

Send the exact same request again — identical response, and nothing is appended to the
ledger. Now change the amount but keep the key:

```bash
curl -X POST localhost:8080/api/v1/transfers \
  -H 'Content-Type: application/json' \
  -d '{"fromId":1,"toId":2,"amount":"500.00","idempotencyId":"demo-1"}'
```

```json
{"errorCode":"IDEMPOTENCY_KEY_REUSED","message":"Idempotency key 'demo-1' was already used …"}
```

### Prove the ledger balances

```bash
curl localhost:8080/api/v1/reconcile
```

```json
{
  "balanced": true,
  "conserved": true,
  "accountsChecked": 4,
  "storedTotal": 2500.0,
  "derivedTotal": 2500.0,
  "totalDebited": 100.0,
  "totalCredited": 100.0,
  "discrepancies": []
}
```

### Postman

`Ledgerly.postman_collection.json` in the repo root imports 24 requests across 5 folders,
each with assertions. **Import → File**, then **Run collection** — the first request captures
the seeded account ids into collection variables that the rest depend on, and the folders are
ordered to work top to bottom. Only `baseUrl` may need changing.

---

## API reference

Base path: `/api/v1`

### Accounts

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/accounts` | Open an account. `201` with a `Location` header. |
| `GET` | `/accounts` | Paginated list. |
| `GET` | `/accounts/{id}` | Single account, including its `version`. |
| `GET` | `/accounts/{id}/events` | Paginated event history for one account. |

```bash
curl -X POST localhost:8080/api/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{"name":"Eve","openingBalance":"640.00"}'
```

### Transfers

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/transfers` | Move money. Idempotent on `idempotencyId`. |
| `GET` | `/transfers` | Paginated event log. |
| `GET` | `/transfers/{transferId}` | The DEBIT/CREDIT pair for one transfer. |
| `GET` | `/transfers/processed` | Paginated idempotency records. |

Request body:

| Field | Type | Rules |
| --- | --- | --- |
| `fromId` | integer | required, must differ from `toId` |
| `toId` | integer | required |
| `amount` | decimal | required, positive, at most 15 integer and 4 fraction digits |
| `idempotencyId` | string | required, non-blank |

### Reconciliation

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/reconcile` | `200` when balanced, `409` when not. |

### Operations

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/actuator/health` | Health of the app and datasource. |
| `GET` | `/actuator/metrics/{name}` | Micrometer metrics. |

Custom metrics: `ledgerly.transfer.retries`,
`ledgerly.transfer.idempotent.replays`, `ledgerly.transfer.retries.exhausted`.

### Pagination

List endpoints accept `page`, `size` and `sort` and return a Spring `Page`:

```bash
curl "localhost:8080/api/v1/transfers?page=0&size=20&sort=id,desc"
```

### Errors

Every failure returns the same shape:

```json
{"errorCode": "INSUFFICIENT_FUNDS", "message": "Insufficient funds in account 1: balance 900.0000, requested 99999999.00"}
```

| Status | `errorCode` | Cause |
| --- | --- | --- |
| `400` | `VALIDATION_FAILED` | Request body failed bean validation |
| `400` | `SELF_TRANSFER` | `fromId` equals `toId` |
| `400` | `INVALID_AMOUNT` | Non-positive amount reaching the domain |
| `404` | `ACCOUNT_NOT_FOUND` | No such account |
| `404` | `TRANSFER_NOT_FOUND` | No such transfer |
| `409` | `INSUFFICIENT_FUNDS` | Sender cannot cover the amount |
| `409` | `IDEMPOTENCY_KEY_REUSED` | Key reused with different parameters |
| `409` | `CONCURRENT_MODIFICATION` | Retry budget exhausted; safe to retry |

`CONCURRENT_MODIFICATION` means nothing was written. Retrying with the **same**
`idempotencyId` is safe and correct.

---

## Configuration

Every setting has a working default; the app runs with no configuration at all. Each is
overridable by environment variable (see `src/main/resources/application.properties`).

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:h2:mem:ledgerdb;DB_CLOSE_DELAY=-1` | JDBC URL |
| `DB_USERNAME` | `sa` | Database user |
| `DB_PASSWORD` | *(empty)* | Database password |
| `DB_POOL_SIZE` | `30` | Hikari max pool size |
| `DDL_AUTO` | `create-drop` | Hibernate schema handling |
| `SHOW_SQL` | `false` | Log every SQL statement |
| `H2_CONSOLE_ENABLED` | `false` | Enable the H2 web console |
| `TRANSFER_MAX_ATTEMPTS` | `10` | Retry budget before a `409` |
| `TRANSFER_BASE_BACKOFF_MILLIS` | `5` | Backoff base (doubles per attempt) |
| `TRANSFER_MAX_BACKOFF_MILLIS` | `250` | Backoff ceiling |
| `IDEMPOTENCY_RETENTION` | `PT24H` | How long a key stays replayable (ISO-8601) |
| `IDEMPOTENCY_CLEANUP_ENABLED` | `true` | Enable the expiry sweep |

`.env.example` documents these. Copy it to `.env` for use with Docker Compose; `.env` is
gitignored and must never be committed.

### Tuning the retry budget

`TRANSFER_MAX_ATTEMPTS` trades rejections against latency. At the default of 10, the stress
test sheds ~13 of 200 requests; at 5 it sheds ~70. Raising it further mostly adds latency —
if you want a cleaner number, widen the account pool rather than the retry ceiling, because
200 requests over 4 accounts is deliberately pathological contention.

---

## Running with PostgreSQL

The app reads its datasource from environment variables, so no code changes are needed:

```bash
export DB_URL=jdbc:postgresql://localhost:5432/ledgerly
export DB_USERNAME=ledgerly
export DB_PASSWORD=<your password>
export DDL_AUTO=update
./gradlew bootRun
```

The PostgreSQL driver ships with the app. A `compose.yaml` is included that runs the app
against PostgreSQL in containers:

```bash
cp .env.example .env    # then edit .env
docker compose up --build
```

> **Not yet verified.** The Docker and PostgreSQL paths were written but could not be
> executed in the environment this project was finished in (no Docker daemon available).
> The H2 path is fully tested. Treat the container setup as a starting point and expect to
> iterate on first run.

---

## Testing

```bash
./gradlew test          # run the suite (37 tests)
./gradlew build         # compile, test, and assemble the jar
./gradlew bootJar       # build the executable jar only
./gradlew clean build   # full rebuild from scratch
```

The HTML report lands at `build/reports/tests/test/index.html`.

| Test class | What it covers |
| --- | --- |
| `AccountTest` | Domain invariants in isolation — overdraft, zero, negative, null, exact-balance |
| `RequestFingerprintTest` | Idempotency fingerprinting — scale normalisation, direction, field boundaries |
| `LedgerApiTest` | API surface, idempotency-key reuse, and **reconciliation detecting a corrupted balance** |
| `ConcurrentTransferStressTest` | 200 concurrent transfers over contended accounts |
| `LedgerlyApplicationTests` | Context loads |

No linter, formatter or static-analysis tool is configured; see
[Known limitations](#known-limitations).

CI runs `./gradlew build` on every push and pull request
(`.github/workflows/ci.yml`) and uploads the test report as an artifact.

---

## Project structure

```
src/main/java/com/breakingcode/Ledgerly/
├── LedgerlyApplication.java        entry point; enables scheduling
├── DataSeeder.java                 seeds demo accounts on an empty database
│
├── account/
│   ├── Account.java                entity; holds @Version and the debit/credit invariants
│   ├── AccountService.java         opens accounts; reads
│   ├── AccountController.java      /api/v1/accounts
│   ├── AccountDTO.java             wire model — entities are never serialised directly
│   ├── CreateAccountRequest.java   validated input
│   ├── AccountRepository.java
│   └── OpeningBalanceRecorder.java port for writing OPENING events (see below)
│
├── transfer/
│   ├── TransferService.java        idempotency check + retry loop  ← orchestration
│   ├── TransferExecutor.java       one attempt = one transaction   ← unit of work
│   ├── RequestFingerprint.java     SHA-256 of the transfer parameters
│   ├── TransferEvent.java          immutable ledger event
│   ├── ProcessedRequest.java       idempotency record
│   ├── ProcessedRequestCleanupJob.java   expires old idempotency records
│   ├── TransferController.java     /api/v1/transfers
│   ├── EventType.java              OPENING | DEBIT | CREDIT
│   └── …DTO / …Repository
│
├── reconcile/
│   ├── ReconciliationService.java  folds the event log, compares to stored balances
│   ├── ReconciliationController.java   /api/v1/reconcile
│   └── ReconciliationReport.java / AccountReconciliation.java
│
└── exception/
    ├── LedgerException.java        base type for all domain failures
    ├── GlobalExceptionHandler.java maps exceptions to ErrorResponse + status
    └── …one class per failure mode
```

### Why `OpeningBalanceRecorder` exists

Opening an account has to append an `OPENING` event, but events live in the `transfer`
package and `transfer` already depends on `account`. Rather than let the two packages depend
on each other, `account` declares the port and `transfer` implements it
(`TransferEventOpeningBalanceRecorder`). The dependency keeps pointing one way.

### The two-bean split

`TransferService` (retry loop, no transaction) and `TransferExecutor` (`@Transactional`, no
retry) are separate beans because Spring's `@Transactional` is proxy-based: a call between
two methods of the same class silently bypasses it. Merging them would quietly remove the
transaction boundary — and the atomicity the whole design depends on — with no compiler or
runtime error. This split is load-bearing; don't collapse it.

---

## Security considerations

This is a portfolio project, not a production payment system. Understand these before
deploying it anywhere public:

- **There is no authentication or authorisation.** Any caller can move money between any
  accounts. Adding Spring Security is the first thing a real deployment would need.
- **The H2 console is disabled by default** (`H2_CONSOLE_ENABLED=false`). It is an
  unauthenticated SQL shell over HTTP — never enable it on a reachable host.
- **Actuator exposes only `health`, `info` and `metrics`**, and health details are hidden.
  Widening `management.endpoints.web.exposure.include` can leak configuration, environment
  variables and thread dumps.
- **No credentials are committed.** The datasource defaults to in-memory H2 with the
  conventional `sa` user and an empty password; real credentials come from `DB_USERNAME` /
  `DB_PASSWORD`. `.env` is gitignored — only `.env.example`, with placeholders, is tracked.
- **No rate limiting.** The retry budget bounds work per request, but nothing bounds
  requests per client.
- Money uses `BigDecimal` with `precision = 19, scale = 4` everywhere — never `double`.

---

## Troubleshooting

**`POST /api/v1/transfers` returns 404.** Drop the trailing slash. Spring 6 removed
trailing-slash matching, so `/api/v1/transfers/` and `/api/v1/transfers` are different paths.

**Everything returns `409 CONCURRENT_MODIFICATION` under load.** The retry budget is
exhausting. Raise `TRANSFER_MAX_ATTEMPTS`, or reduce contention by spreading traffic over
more accounts. Nothing was written, so retrying with the same `idempotencyId` is safe.

**`409 IDEMPOTENCY_KEY_REUSED`.** That key was already used for a transfer with different
`fromId`, `toId` or `amount`. Generate a new key per logical transfer and reuse it only when
retrying that exact transfer.

**Balances reset on restart.** Expected: the default database is in-memory with
`DDL_AUTO=create-drop`. Point `DB_URL` at a file or a real database to persist.

**`JAVA_HOME is not set`.** The Gradle wrapper needs a JDK 21 on `PATH` or `JAVA_HOME`.

**Port 8080 in use.** `SERVER_PORT=8081 ./gradlew bootRun`.

**Want to see the SQL.** `SHOW_SQL=true ./gradlew bootRun`. Leave it off during stress runs —
it dominates the timings.

---

## Development

```bash
./gradlew build             # compile + test + jar
./gradlew bootRun           # run locally
./gradlew test --tests '*ConcurrentTransferStressTest'   # one test class
```

Conventions worth preserving:

- Constructor injection everywhere; no field `@Autowired`.
- Controllers return DTOs, never entities.
- Domain invariants live on the entity (`Account.debit`), not in services, so they cannot be
  bypassed by a new caller.
- `spring.jpa.open-in-view=false`. OSIV keeps a persistence context open for the entire HTTP
  request and hides transaction-boundary mistakes — precisely what this project is about.
- Compare `BigDecimal` with `compareTo`, never `equals`: `equals` is scale-sensitive, so
  `100.00` and `100.0000` compare unequal.

Contributions: open an issue describing the change, then a PR. CI must be green, and new
behaviour needs a test that would fail without it.

---

## Known limitations

Honest list of what this project does *not* do:

- **No schema migrations.** Hibernate generates the schema. Flyway or Liquibase would be
  required before running this against a database whose data matters.
- **No authentication.** See [Security considerations](#security-considerations).
- **The Docker and PostgreSQL paths are unverified** — written but never executed. See
  [Running with PostgreSQL](#running-with-postgresql).
- **Tested against H2 only.** H2's locking and isolation differ from PostgreSQL's, and the
  correctness argument here is about concurrency. Re-running the stress suite against
  PostgreSQL via Testcontainers is the single highest-value next step.
- **Metrics are per-instance and in-memory.** Counters reset on restart and would not
  aggregate across replicas without a real metrics backend.
- **No linting, formatting or static-analysis tooling** is configured.
- **Reconciliation walks every account.** It pages rather than loading all rows at once, but
  it is still O(accounts) per call — fine here, not a design for millions of accounts.

---

## License

MIT — see [LICENSE](LICENSE).

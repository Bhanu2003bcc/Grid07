[//]: # ( Grid07 — Backend Engineering Assignment)

Spring Boot 3.x microservice implementing a social media API gateway with Redis-backed guardrails, a real-time virality engine, and a smart notification batching system.

---

## Tech Stack

| Layer        | Technology                     |
|--------------|-------------------------------|
| Language     | Java 17                       |
| Framework    | Spring Boot 3.2               |
| Database     | PostgreSQL 15                 |
| Cache / Lock | Redis 7 (Spring Data Redis)   |
| Build        | Maven 3.9                     |
| Container    | Docker + Docker Compose       |

---

## Quick Start

### 1. Start infrastructure

```bash
docker-compose up postgres redis -d
```

Wait for both to be healthy:
```bash
docker-compose ps   # both should show "healthy"
```

### 2. Run the application

```bash
mvn spring-boot:run
```

App starts at `http://localhost:8080`.  
JPA creates all tables automatically on first boot (`ddl-auto=update`).

### 3. Seed test data

```bash
# Seed via init-db.sql after first boot
docker exec -i grid07-postgres psql -U grid07user -d grid07db < init-db.sql
```

This creates:
- Users: Bhanu (id=1), Anurag (id=2), Shivam (id=3)
- Bots: AlphaBot (id=1), BetaBot (id=2), GammaBot (id=3)

### 4. Import Postman Collection

Import `Grid07_Postman_Collection.json` into Postman.  
Set collection variable `postId`, `userId`, `botId` as needed.

---

## API Reference

| Method | Endpoint                          | Description                         |
|--------|-----------------------------------|-------------------------------------|
| POST   | `/api/posts`                      | Create a post (User or Bot)         |
| GET    | `/api/posts/{postId}`             | Get post with virality score        |
| POST   | `/api/posts/{postId}/comments`    | Add comment (guardrails for bots)   |
| POST   | `/api/posts/{postId}/like`        | Like a post (humans only)           |

### Response format

All endpoints return a consistent envelope:

```json
-----Request-------`/api/posts` -----
{
  "authorId": 1,
  "authorType": "USER",
  "content": "This is my first post."
}
-------Response------------
{
    "success": true,
    "message": "Post created successfully.",
    "data": {
        "id": 4,
        "authorId": 1,
        "authorType": "USER",
        "content": "This is my first post.",
        "createdAt": "2026-04-24T23:14:44.366873",
        "viralityScore": 0
    },
    "timestamp": "2026-04-24T23:14:44.372895171"
}
```

### HTTP status codes

| Code | Meaning                               |
|------|---------------------------------------|
| 200  | OK                                    |
| 201  | Created                               |
| 400  | Bad Request (validation failed)       |
| 404  | Not Found (entity doesn't exist)      |
| 409  | Conflict (duplicate like)             |
| 429  | Too Many Requests (guardrail blocked) |

---

## Architecture

```
Client (Postman / User / Bot)
          │
          ▼
  REST Controller Layer
  (PostController)
          │
          ▼
  Service Layer
  ┌────────────────────────────────────────────────────┐
  │  PostService  CommentService  LikeService           │
  │        │            │              │                │
  │        │     BotGuardService       │                │
  │        │     (Redis guardrails)    │                │
  │        │            │              │                │
  │        └────────────┴──────────────┘                │
  │                     │                              │
  │          ViralityService  NotificationService      │
  └────────────────────────────────────────────────────┘
          │                         │
          ▼                         ▼
     PostgreSQL                   Redis
  (source of truth)            (gatekeeper)
```

---

## Redis Key Schema

| Key Pattern                        | Type    | TTL        | Purpose                         |
|------------------------------------|---------|------------|---------------------------------|
| `post:{id}:virality_score`         | String  | None       | Cumulative virality score       |
| `post:{id}:bot_count`              | String  | None       | Total bot replies on a post     |
| `cooldown:bot_{id}:human_{id}`     | String  | 600s       | Bot-to-human interaction lock   |
| `user:{id}:pending_notifs`         | List    | None       | Queued notification messages    |
| `notif_cooldown:user_{id}`         | String  | 900s       | Per-user notification cooldown  |

---

## Guardrail Rules (Phase 2)

| Rule              | Limit        | Redis Operation      | On Breach         |
|-------------------|--------------|----------------------|-------------------|
| Horizontal Cap    | 100 bots/post| INCR + DECR rollback | HTTP 429          |
| Vertical Cap      | 20 depth max | Computed, not stored | HTTP 429          |
| Cooldown Cap      | 1 per 10 min | SET with TTL / EXISTS| HTTP 429          |

---

## Thread Safety — How Atomic Locks Work

This is the most important section of the implementation.

### The Problem

Consider 200 bots hitting the same post simultaneously. A naive implementation:

```java
// RACE CONDITION — DO NOT DO THIS
int count = redis.get("post:1:bot_count");   // Thread A reads 99
                                              // Thread B reads 99 (same value!)
if (count < 100) {
    redis.incr("post:1:bot_count");           // Thread A increments → 100
                                              // Thread B increments → 101 ← BUG
    db.save(comment);                         // Both save. DB has 101 rows.
}
```

Both threads read `99`, both think they're the 100th, both increment and save. The database ends up with 101 rows. **This is the exact failure mode the assignment tests for.**

### The Solution — INCR-first, check-second

Redis `INCR` is a **single atomic server-side command**. The Redis event loop is single-threaded. No two `INCR` calls can execute concurrently — they are serialized automatically.

```java
//CORRECT — BotGuardService.checkHorizontalCap()
Long newCount = redis.opsForValue().increment("post:1:bot_count");
// newCount is now GUARANTEED unique across all concurrent callers.
// Thread A gets 100, Thread B gets 101 — never the same value.

if (newCount > 100) {
    redis.opsForValue().decrement("post:1:bot_count");  // rollback
    throw new GuardrailException("Cap reached");         // → HTTP 429
}
// Only threads with newCount ≤ 100 reach this line
db.save(comment);
```

Under 200 concurrent requests:
- Threads 1–100 receive values 1–100 from INCR → allowed
- Threads 101–200 receive values 101–200 from INCR → DECR + rejected
- Final Redis counter = 100, final DB rows = 100. **Exact.**

### The Rollback Pattern

There is a subtle edge case: what if the Redis INCR succeeds (counter = 97) but the PostgreSQL write fails (e.g. DB timeout)? The counter would be permanently off by 1.

We handle this explicitly:

```java
botGuardService.enforceAllGuardrails(...);  // Redis INCR 

try {
    saved = persistComment(...);             // DB write
} catch (Exception dbException) {
    botGuardService.rollbackBotCount(postId); // DECR 
    throw dbException;
}
```

This keeps Redis and PostgreSQL always in sync.

### Why no Java-level locks?

Using `synchronized`, `ReentrantLock`, or `AtomicInteger` in Java would only work for a **single JVM instance**. The moment you deploy two instances of the app (horizontal scaling), each has its own memory.

Redis is a **shared external store** — all app instances talk to the same Redis, so the counter is global and correct regardless of how many instances are running.

### Statelessness guarantee

The app stores zero state in Java memory:
- No `static` variables
- No `HashMap` or `ConcurrentHashMap`
- No in-process caches for counters
- All counters in Redis
- All cooldowns in Redis (with TTL)
- All notification queues in Redis (List type)

---

## Notification Engine (Phase 3)

### Flow

```
Bot interaction
      │
      ▼
NotificationService.handleBotInteractionNotification()
      │
      ├─ EXISTS notif_cooldown:user_{id}?
      │         │
      │   YES   ▼
      │   RPUSH user:{id}:pending_notifs  ← queued
      │
      └─ NO  → log "Push Notification Sent"
               SET notif_cooldown:user_{id} EX 900
```

### CRON Sweeper

Runs every 5 minutes via `@Scheduled`:

1. `SCAN user:*:pending_notifs` (non-blocking, cursor-based)
2. For each key found:
   - `LRANGE key 0 -1` → read all messages
   - `DEL key` → clear the list
   - Log: `"Bot X and [N] others interacted with your posts."`

**Why SCAN and not KEYS \*?**  
`KEYS *` is O(N) and blocks the Redis event loop for the full scan duration. On a large Redis instance this causes latency spikes for all clients. `SCAN` is cursor-based, O(1) per call, non-blocking, and guaranteed to eventually cover all matching keys. It is the correct tool for pattern-based key discovery in production.

---

## Running Tests

```bash
# Start infra first
docker-compose up postgres redis -d

# Run all tests including the concurrency test
mvn test -Dspring.profiles.active=test

# Run just the concurrency test
mvn test -Dtest=ConcurrencyTest -Dspring.profiles.active=test
```

Expected output for concurrency test:
```
   CONCURRENCY TEST RESULTS 
Total requests fired  : 200
Successful (allowed)  : 100
Rejected (429)        : 100
DB bot comment count  : 100
Redis bot_count key   : 100
```

---

## Project Structure

```
src/
├── main/java/com/grid07/
│   ├── Grid07Application.java        ← Entry point + @EnableScheduling
│   ├── config/
│   │   ├── RedisConfig.java          ← StringRedisTemplate bean
│   │   ├── RedisKeys.java            ← All Redis key patterns (single source of truth)
│   │   └── GuardrailProperties.java  ← Externalized guardrail constants
│   ├── controller/
│   │   └── PostController.java       ← 3 REST endpoints
│   ├── dto/
│   │   ├── request/                  ← CreatePostRequest, CreateCommentRequest, LikePostRequest
│   │   └── response/                 ← PostResponse, CommentResponse, ApiResponse<T>
│   ├── entity/
│   │   ├── User.java                 ← id, username, is_premium
│   │   ├── Bot.java                  ← id, name, persona_description
│   │   ├── Post.java                 ← id, author_id, author_type, content, created_at
│   │   ├── Comment.java              ← id, post_id, author_id, depth_level, parent_id
│   │   └── PostLike.java             ← Unique(post_id, user_id)
│   ├── exception/
│   │   ├── GuardrailException.java   ← → HTTP 429
│   │   ├── ResourceNotFoundException ← → HTTP 404
│   │   ├── DuplicateLikeException    ← → HTTP 409
│   │   └── GlobalExceptionHandler    ← @RestControllerAdvice
│   ├── repository/                   ← JPA repositories (5 total)
│   ├── scheduler/
│   │   └── NotificationScheduler     ← @Scheduled CRON sweeper, uses SCAN
│   └── service/
│       ├── BotGuardService.java      ← Atomic INCR guardrails (Phase 2 core)
│       ├── ViralityService.java      ← Redis score updates (+1/+20/+50)
│       ├── NotificationService.java  ← 15-min throttle + Redis List queuing
│       ├── PostService.java          ← Post CRUD
│       ├── CommentService.java       ← Comment orchestration (guard → DB → notify)
│       └── LikeService.java          ← Like handling
└── test/java/com/grid07/
    └── ConcurrencyTest.java          ← 200-thread race condition test
```

---

## Deliverables Checklist

- Spring Boot source code
- `docker-compose.yml` (Postgres + Redis + App)
- `Dockerfile` (multi-stage build)
- `Grid07_Postman_Collection.json`
- `README.md` with thread-safety explanation
- Concurrency test (200 threads, asserts exactly 100 DB rows)
- All 4 phases implemented
- Stateless — zero in-memory state
- SCAN used instead of KEYS * in sweeper
- Redis rollback on DB failure

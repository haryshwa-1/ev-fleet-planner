# Benchmark

Measured on 30 Oct 2026. All scenarios are seeded (seed 42), so plans are reproducible; timings are not exact
between runs, and the laptop was not otherwise isolated.

**Machine:** MacBook Air, Apple M2, 8 cores (4 performance, 4 efficiency), 8 GB RAM, macOS 27.0, OpenJDK 24.0.2.
The Docker figures ran in a Colima VM limited to 4 CPUs and 4 GB, with the JVM and PostgreSQL 16 sharing that VM.

## Full planning

Trips scale at 5 per cab, and chargers with hubs of 8. One unmeasured run warms up the JVM first.

| Cabs | Trips | Chargers | One greedy run | Multi-start (16 runs) | Backup, greedy | Backup, multi-start | Lower bound | Validator |
|---|---|---|---|---|---|---|---|---|
| 100 | 500 | 16 | 0.2 s | 0.5 s | 15 | 14 | 14 | VALID, 36 ms |
| **300** | **1,500** | **40** | **1.4 s** | **5.8 s** | **56** | **54** | **46** | **VALID, 21 ms** |
| 1,000 | 5,000 | 128 | 14.7 s | 85.5 s | 162 | 156 | 131 | VALID, 51 ms |
| 3,000 | 15,000 | 400 | 136.7 s | not run | 374 | - | 280 | VALID, 158 ms |

- The required case (300 cabs) takes 5.8 s, against the 30 s target. In Docker with 4 CPUs the same plan took 10-18 s.
- **Multi-start does not meet 30 s at 1,000 cabs.** A larger fleet would need fewer runs or one greedy run: at 1,000
  cabs one run takes 14.7 s and is 6 trips worse.
- Time grows about 10x per 3.3x fleet step, close to the expected O(T x V).
- Lower bound calculation takes 0.1 s at 300 cabs and 5.7 s at 3,000.

An early run showed much worse growth (1,503 s at 3,000 cabs) because every insertion scanned all hubs. Caching each
location's hubs by distance and rejecting late cabs earlier gave a 10x gain at that size. The resulting plans were
identical at 300 and 1,000 cabs.

## Live re-planning, engine only

200 events per row, half battery drift and half charger failures, at random evening times, each applied to the
unchanged plan. The timing includes the snapshot, repair, diff and full validation.

| Cabs | Valid | p50 | p95 | p99 | Max | Events/s | Trips moved per event |
|---|---|---|---|---|---|---|---|
| 100 | 200/200 | 3 ms | 7 ms | 14 ms | 37 ms | 277 | 0.1 |
| **300** | **200/200** | **7 ms** | **17 ms** | **21 ms** | **23 ms** | **122** | **0.4** |
| 1,000 | 200/200 | 21 ms | 48 ms | 53 ms | 60 ms | 42 | 0.3 |
| 3,000 | 200/200 | 61 ms | 147 ms | 161 ms | 166 ms | 14 | 0.2 |

The drift charges in this run are between 30% and 90%, so many events need little repair. The harder demo case, a cab
at 22% instead of 47%, took 33-41 ms and moved 7 trips.

## Through the API

300-cab shift in Docker, seeded plan, over real HTTP with JWT and PostgreSQL. A short warm-up preceded these. Each
event advances the plan's clock and builds on the last, unlike the engine runs above.

| Load | Requests | p50 | p95 | p99 | Max | Throughput |
|---|---|---|---|---|---|---|
| Drift events, one client | 200 | 57 ms | 72 ms | 93 ms | 129 ms | 17/s |
| Drift events, 8 clients at once | 200 | 369 ms | 699 ms | 916 ms | 1,031 ms | 19/s |
| Summary reads, 8 clients at once | 1,000 | 23 ms | 35 ms | 45 ms | 82 ms | 337/s |

All 420 events, warm-up included, were applied with no validator failures; the final plan validated, still with 54
backup trips. The app used about 650 MB and PostgreSQL about 285 MB.

**Reading these numbers:**

- Every request stayed under the 2 s re-plan target, even queued behind 7 others.
- Events on one shift run strictly one at a time, so throughput is the single-writer limit: about 18 events/s per
  shift. The latency under 8 clients is mostly waiting for the lock.
- The re-plan itself is the small part (31 ms at most in this run). Most of the 57 ms is storing a full copy of the
  plan as JSON, about 2 MB per version. That is the first thing to change if a shift needs more throughput.
- Summary reads are slower than they should be, since each one loads the version row including its plan body. This
  is a known and easy improvement that has not been made.

## Reproduce

```bash
./mvnw -q -DskipTests install
./mvnw -q -pl planner-core exec:java -Dexec.mainClass=com.moveinsync.evfleet.core.cli.PlannerBenchmark
```

The API figures came from a throwaway Python script against `docker compose up`; it is not in the repository.

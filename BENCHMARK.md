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

## Other seeds

Seed 42 is the demo and was also used for tuning, so its numbers could flatter the planner. The same 300-cab shift on
seeds 1-20, which were not used for tuning:

| Seed | Backup | Lower bound | Gap | Cost incl. refill | Plan time | Validator | Re-plans valid | Stranded | Slowest re-plan |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 65 | 60 | 5 | Rs 26323 | 8.1 s | VALID | 40/40 | 3 | 33 ms |
| 2 | 25 | 21 | 4 | Rs 28064 | 6.9 s | VALID | 40/40 | 6 | 23 ms |
| 3 | 58 | 51 | 7 | Rs 26748 | 7.4 s | VALID | 40/40 | 10 | 24 ms |
| 4 | 35 | 28 | 7 | Rs 24993 | 7.3 s | VALID | 40/40 | 8 | 22 ms |
| 5 | 33 | 29 | 4 | Rs 25554 | 7.7 s | VALID | 40/40 | 9 | 21 ms |
| 6 | 57 | 52 | 5 | Rs 26676 | 6.6 s | VALID | 40/40 | 4 | 22 ms |
| 7 | 37 | 37 | 0 | Rs 27531 | 6.5 s | VALID | 40/40 | 5 | 21 ms |
| 8 | 62 | 60 | 2 | Rs 23981 | 7.5 s | VALID | 40/40 | 8 | 21 ms |
| 9 | 52 | 42 | 10 | Rs 24934 | 6.4 s | VALID | 40/40 | 7 | 22 ms |
| 10 | 68 | 60 | 8 | Rs 25857 | 6.5 s | VALID | 40/40 | 4 | 20 ms |
| 11 | 60 | 54 | 6 | Rs 26349 | 6.4 s | VALID | 40/40 | 4 | 23 ms |
| 12 | 41 | 37 | 4 | Rs 24463 | 6.3 s | VALID | 40/40 | 5 | 22 ms |
| 13 | 58 | 50 | 8 | Rs 26949 | 7.2 s | VALID | 40/40 | 5 | 24 ms |
| 14 | 68 | 62 | 6 | Rs 24868 | 7.4 s | VALID | 40/40 | 5 | 22 ms |
| 15 | 42 | 32 | 10 | Rs 27682 | 6.7 s | VALID | 40/40 | 4 | 22 ms |
| 16 | 77 | 62 | 15 | Rs 26701 | 7.3 s | VALID | 40/40 | 9 | 21 ms |
| 17 | 60 | 51 | 9 | Rs 25280 | 7.7 s | VALID | 40/40 | 1 | 22 ms |
| 18 | 42 | 33 | 9 | Rs 25513 | 6.8 s | VALID | 40/40 | 2 | 23 ms |
| 19 | 51 | 40 | 11 | Rs 27229 | 6.4 s | VALID | 40/40 | 5 | 23 ms |
| 20 | 75 | 68 | 7 | Rs 26407 | 6.8 s | VALID | 40/40 | 3 | 22 ms |

- Average 53 backup trips, against 54 on seed 42, so seed 42 is typical and not a lucky case. The range is 25 to 77,
  because each shift is a different set of trips.
- Average gap to the lower bound is 6.9 trips (range 0 to 15), against 8 on seed 42. On seed 7 the gap is 0, so that
  plan is proven to use the fewest backup cabs.
- Every plan is valid and under 30 s, and all 800 re-plans are valid, including 107 where the cab was stranded.
- No single setting wins: 12 different configurations won across 21 seeds. Multi-start saves about 2 trips on average
  over the default configuration.

**Charging rule on unseen seeds.** Average over 7 other seeds (101-107), by how far a cab charges at the Rs 12 peak:

| Peak target | Backup trips | Cost incl. refill |
|---|---|---|
| 30% | 42.3 | Rs 26,190 |
| 40% | 42.3 | Rs 26,229 |
| **50% (used)** | **42.3** | **Rs 26,230** |
| 60% | 42.1 | Rs 28,089 |
| 70% | 42.3 | Rs 30,477 |
| 80% | 42.1 | Rs 30,325 |

The rule saves about 13.5% against 80% without losing trips, so the saving is not special to seed 42 (15% there at the
same comparison). Anything from 30% to 50% is about equal, and cost starts to jump just above 50%.

**Different fleet shapes** (seed 1, one run each, all valid):

| Shape | Backup | Lower bound | Gap |
|---|---|---|---|
| 300 cabs, 15 chargers | 63 | 60 | 3 |
| 300 cabs, 2 hubs of 8 chargers | 64 | 60 | 4 |
| 400 cabs, 1,500 trips | 0 | 0 | 0 |
| 300 cabs, 1,800 trips | 146 | 117 | 29 |
| 200 cabs, 1,500 trips | 241 | 145 | 96 |

The gap widens when cabs are scarce. Part of that may be the bound, which ignores battery limits, so these runs do not
show how far from optimal those plans really are.

**Limit of this test:** every seed comes from the same generator, so it shows the results are not luck within this model
of a city. It does not show how the planner behaves on real demand.

```bash
./mvnw -q -pl planner-core exec:java -Dexec.mainClass=com.moveinsync.evfleet.core.cli.SeedSweep -Dexec.args=20
```

## Traffic speed sensitivity

The speeds are estimates, not measurements. Average over seeds 42 and 1-4, with every speed changed by the same share.
The first columns plan knowing the true speeds. The "no re-plan" columns take the plan made with our speeds and follow
it under the true ones, with delays carrying over and no cab leaving earlier than planned.

| True speed vs assumed | Backup, planned knowing it | Cost incl. refill | All valid | Pickups late, no re-plan | Over 10 min late | Worst | Cabs below 15% floor |
|---|---|---|---|---|---|---|---|
| -20% | 118.4 | Rs 23,440 | yes | 795.0 | 358.6 | 34 min | 0.0 |
| -10% | 74.6 | Rs 25,330 | yes | 456.8 | 12.8 | 15 min | 0.0 |
| +0% | 47.4 | Rs 26,294 | yes | 0.0 | 0.0 | 0 min | 0.0 |
| +10% | 24.8 | Rs 27,521 | yes | 0.0 | 0.0 | 0 min | 0.0 |
| +20% | 6.2 | Rs 28,052 | yes | 0.0 | 0.0 | 0 min | 0.0 |

- **Results depend heavily on the speeds.** Backup trips change about 2.5 times when speeds are 10% lower, so the headline
  figure is only as good as the traffic estimate.
- **Plans have little spare time.** Cabs are planned to arrive close to pickup, so traffic 10% slower than assumed makes
  about 450 of 1,450 pickups late if nobody re-plans, nearly all by 10 minutes or less. Drift events do not cover this:
  they report charge, not delay.
- The floor column is optimistic: this test changes speed only, not how much energy a km takes in traffic.

```bash
./mvnw -q -pl planner-core exec:java -Dexec.mainClass=com.moveinsync.evfleet.core.cli.SpeedSensitivity
```

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

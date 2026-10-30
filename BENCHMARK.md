# Benchmark

Measured on 30 Oct 2026. All scenarios are seeded (seed 42), so plans are reproducible; timings are not exact
between runs, and the laptop was not otherwise isolated.

**Machine:** MacBook Air, Apple M2, 8 cores (4 performance, 4 efficiency), 8 GB RAM, macOS 27.0, OpenJDK 24.0.2.
The Docker figures ran in a Colima VM limited to 4 CPUs and 4 GB, with the JVM and PostgreSQL 16 sharing that VM.

## Full planning

Trips scale at 5 per cab, and chargers with hubs of 8. One unmeasured run warms up the JVM first. The final plan is the
best of 16 greedy runs (multi-start), improved by 5,000 steps of local search.

| Cabs | Trips | Chargers | One greedy run | Multi-start | Local search | Backup: greedy | Multi-start | Final | Lower bound | Validator |
|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 500 | 16 | 0.2 s | 0.5 s | 1.8 s | 15 | 14 | 14 | 14 | VALID, 12 ms |
| **300** | **1,500** | **40** | **1.0 s** | **4.4 s** | **2.4 s** | **56** | **54** | **52** | **46** | **VALID, 14 ms** |
| 1,000 | 5,000 | 128 | 11.0 s | 54.4 s | 4.2 s | 162 | 156 | 156 | 131 | VALID, 45 ms |
| 3,000 | 15,000 | 400 | 106.3 s | not run | not run | 374 | - | - | 280 | VALID, 149 ms |

- The required case (300 cabs) takes 6.8 s, against the 30 s target. In Docker with 4 CPUs the same plan took 9-14 s.
- **Multi-start does not meet 30 s at 1,000 cabs.** A larger fleet would need fewer runs or one greedy run: at 1,000
  cabs one run takes 11.0 s and is 6 trips worse.
- Local search has a fixed step count, so it does not grow with the fleet, but it also does less at larger sizes: it won
  no trips at 1,000 cabs.
- Time grows about 10x per 3.3x fleet step, close to the expected O(T x V).
- Lower bound calculation takes 0.1 s at 300 cabs and 5.1 s at 3,000.

## Other seeds

Seed 42 is the demo and was also used for tuning, so its numbers could flatter the planner. The same 300-cab shift on
seeds 1-20, which were not used for tuning:

| Seed | Backup | Lower bound | Gap | Cost incl. refill | Plan time | Validator | Re-plans valid | Stranded | Slowest re-plan |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 65 | 60 | 5 | Rs 24271 | 7.6 s | VALID | 40/40 | 2 | 22 ms |
| 2 | 24 | 21 | 3 | Rs 26284 | 5.2 s | VALID | 40/40 | 6 | 16 ms |
| 3 | 58 | 51 | 7 | Rs 24153 | 5.9 s | VALID | 40/40 | 10 | 16 ms |
| 4 | 35 | 28 | 7 | Rs 23905 | 5.9 s | VALID | 40/40 | 8 | 17 ms |
| 5 | 33 | 29 | 4 | Rs 23565 | 6.2 s | VALID | 40/40 | 8 | 14 ms |
| 6 | 57 | 52 | 5 | Rs 24458 | 6.0 s | VALID | 40/40 | 4 | 17 ms |
| 7 | 37 | 37 | 0 | Rs 25578 | 5.9 s | VALID | 40/40 | 5 | 16 ms |
| 8 | 62 | 60 | 2 | Rs 22783 | 6.9 s | VALID | 40/40 | 8 | 16 ms |
| 9 | 52 | 42 | 10 | Rs 23713 | 6.3 s | VALID | 40/40 | 7 | 17 ms |
| 10 | 68 | 60 | 8 | Rs 24206 | 6.3 s | VALID | 40/40 | 4 | 15 ms |
| 11 | 60 | 54 | 6 | Rs 24479 | 6.0 s | VALID | 40/40 | 4 | 16 ms |
| 12 | 41 | 37 | 4 | Rs 23721 | 5.7 s | VALID | 40/40 | 5 | 15 ms |
| 13 | 58 | 50 | 8 | Rs 24947 | 6.2 s | VALID | 40/40 | 6 | 17 ms |
| 14 | 68 | 62 | 6 | Rs 23184 | 6.9 s | VALID | 40/40 | 5 | 16 ms |
| 15 | 41 | 32 | 9 | Rs 25481 | 5.8 s | VALID | 40/40 | 4 | 16 ms |
| 16 | 77 | 62 | 15 | Rs 24145 | 6.3 s | VALID | 40/40 | 11 | 15 ms |
| 17 | 60 | 51 | 9 | Rs 23292 | 6.3 s | VALID | 40/40 | 1 | 15 ms |
| 18 | 42 | 33 | 9 | Rs 23650 | 5.7 s | VALID | 40/40 | 2 | 14 ms |
| 19 | 50 | 40 | 10 | Rs 25744 | 5.4 s | VALID | 40/40 | 5 | 15 ms |
| 20 | 75 | 68 | 7 | Rs 23961 | 6.0 s | VALID | 40/40 | 2 | 16 ms |

- Average 53 backup trips, against 52 on seed 42, so seed 42 is typical and not a lucky case. The range is 24 to 77,
  because each shift is a different set of trips.
- Average gap to the lower bound is 6.7 trips (range 0 to 15), against 6 on seed 42. On seed 7 the gap is 0, so that
  plan is proven to use the fewest backup cabs.
- Every plan is valid and under 30 s, and all 800 re-plans are valid, including 107 where the cab was stranded.
- No single setting wins: 12 different configurations won across 21 seeds. Multi-start saves about 2 trips on average
  over the default configuration.
- **Local search mainly saves money.** Across these seeds it cuts cost including refill by 7% (Rs 26,105 to Rs 24,276) in
  about 2 s, but only 0.15 backup trips on average (53.30 to 53.15). Seed 42's 2 trips are better than usual.

**Charging rule on unseen seeds.** Average over 7 other seeds (101-107), by how far a cab charges at the Rs 12 peak:

| Peak target | Backup trips | Cost incl. refill |
|---|---|---|
| 30% | 42.0 | Rs 24,658 |
| 40% | 41.9 | Rs 24,755 |
| **50% (used)** | **42.0** | **Rs 24,664** |
| 60% | 41.9 | Rs 25,666 |
| 70% | 42.0 | Rs 27,130 |
| 80% | 42.0 | Rs 27,006 |

The rule saves about 9% against 80% without losing trips, so the saving is not special to seed 42. Anything from 30% to
50% is about equal, and cost starts to rise just above 50%. Before local search was added the saving was 13.5%, because
local search also finds some of the same savings.

**Different fleet shapes** (seed 1, one run each, all valid):

| Shape | Backup | Lower bound | Gap |
|---|---|---|---|
| 300 cabs, 15 chargers | 63 | 60 | 3 |
| 300 cabs, 2 hubs of 8 chargers | 64 | 60 | 4 |
| 400 cabs, 1,500 trips | 0 | 0 | 0 |
| 300 cabs, 1,800 trips | 141 | 117 | 24 |
| 200 cabs, 1,500 trips | 237 | 145 | 92 |

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
| -20% | 116.0 | Rs 21604 | yes | 747.4 | 328.0 | 35 min | 0.0 |
| -10% | 72.8 | Rs 23494 | yes | 419.6 | 14.0 | 15 min | 0.0 |
| +0% | 46.8 | Rs 24547 | yes | 0.0 | 0.0 | 0 min | 0.0 |
| +10% | 24.4 | Rs 26057 | yes | 0.0 | 0.0 | 0 min | 0.0 |
| +20% | 6.2 | Rs 27519 | yes | 0.0 | 0.0 | 0 min | 0.0 |

- **Results depend heavily on the speeds.** Backup trips rise by more than half when speeds are 10% lower, so the headline
  figure is only as good as the traffic estimate.
- **Plans have little spare time.** Cabs are planned to arrive close to pickup, so traffic 10% slower than assumed makes
  about 420 of 1,450 pickups late if nobody re-plans, nearly all by 10 minutes or less. Drift events do not cover this:
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
| 100 | 200/200 | 2 ms | 5 ms | 7 ms | 18 ms | 347 | 0.1 |
| **300** | **200/200** | **5 ms** | **13 ms** | **17 ms** | **18 ms** | **146** | **0.4** |
| 1,000 | 200/200 | 19 ms | 46 ms | 51 ms | 52 ms | 44 | 0.4 |
| 3,000 | 200/200 | 59 ms | 141 ms | 157 ms | 157 ms | 15 | 0.2 |

The drift charges in this run are between 30% and 90%, so many events need little repair. The harder demo case, a cab
at 22% instead of 47%, took about 40 ms. It moved 4 trips on the final plan, or 7 on a single greedy plan.

## Through the API

300-cab shift in Docker, seeded plan, over real HTTP with JWT and PostgreSQL. Each event builds on the last, unlike the
engine runs above. `scripts/load_test.py` produces the event rows.

| Load | Requests | p50 | p99 | Max | Throughput |
|---|---|---|---|---|---|
| Drift events, one client | 300 | 53 ms | 75 ms | 123 ms | 18.5/s |
| Drift events, 8 clients at once | 400 | 408 ms | 562 ms | 799 ms | 18.9/s |
| Summary reads, 8 clients at once (measured before local search) | 1,000 | 23 ms | 45 ms | 82 ms | 337/s |

All 700 events were applied, with no validator failures. At idle the app used about 575 MB and PostgreSQL about 330 MB.

**Reading these numbers:**

- Every request stayed under the 2 s re-plan target, even queued behind 7 others.
- Events on one shift run strictly one at a time, so throughput is the single-writer limit: about 18-19 events/s per
  shift. The latency under 8 clients is mostly waiting for the lock.
- The re-plan itself is the small part (tens of milliseconds at most). Most of the 53 ms is storing a full copy of the
  plan as JSON, about 2 MB per version. That is the first thing to change if a shift needs more throughput.
- Summary reads are slower than they should be, since each one loads the version row including its plan body. This
  is a known and easy improvement that has not been made.

## Reproduce

```bash
./mvnw -q -DskipTests install
./mvnw -q -pl planner-core exec:java -Dexec.mainClass=com.moveinsync.evfleet.core.cli.PlannerBenchmark
# With docker compose up running:
python3 scripts/load_test.py --clients 1 --events 300
python3 scripts/load_test.py --clients 8 --events 400
```

The seed, speed and load tools above reproduce their tables. The charging-rule and fleet-shape tables came from one-off
runs of the same planner with those parameters. Summary reads were measured with a one-off script.

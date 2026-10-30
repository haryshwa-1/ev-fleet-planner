# EV Fleet Charging & Trip Assignment

MoveInSync case study 6: plan an evening shift for an electric cab fleet — which cab does which trip,
and when and where each cab charges — without any cab running below its battery floor, any hub
exceeding its chargers, or any trip finishing late. Trips no EV can cover go to backup cabs with a reason.

> Status: the planning engine, live re-planning and the Spring Boot service are complete and tested.
> See [DESIGN.md](DESIGN.md) for architecture, failure modes and trade-offs, and [BENCHMARK.md](BENCHMARK.md) for measurements.

## Results (300 cabs, 5 hubs / 40 chargers, 1,500 trips, seed 42)

| | |
|---|---|
| Planning time | 6.8 s: 16 parallel greedy runs, then local search, on 8 cores; 9-14 s in Docker (target: under 30 s) |
| Independent validator | VALID: 35,237 checks, 0 violations, lowest SoC 15.3% (floor 15%) |
| Coverage | 1,448 trips by EV, 52 to backup (96.5%) |
| Proven lower bound | any plan needs at least 46 backup trips, so this plan is at most 6 trips from optimal |
| Backup reasons | 37 no cab can reach the pickup in time · 13 not enough charge · 2 chargers full |
| Charging | 214 sessions, ₹12,831 |
| Cost incl. overnight refill | ₹24,121 (charging plus refilling every cab to its starting charge at ₹5) |
| Tomorrow | every cab can be back at 60% by 03:52 at the ₹5 rate (`OvernightCharging`, checked by the validator) |

Against the single greedy run with a fixed 80% charge target (the starting point), the cost including refill fell from
₹30,416 to ₹24,121 (−21%), with 4 more trips covered. On 20 other seeds the plan is also valid and on time, with an
average of 53 backups and a gap of 6.7; seed 42 is typical. See [BENCHMARK.md](BENCHMARK.md), including how much the
results depend on the traffic estimate.

## How it works

1. **Physics** — road distance = 1.3 × straight line. Traffic is a configurable list of time bands
   (evening default: building up from 16:30, worst 17:30–19:00 at 18 km/h, easing until 23:00, then
   36 km/h free flow). A drive changes speed the moment it enters a new band, and km driven in slow
   traffic use more energy. AC adds 10%. Charging is a two-segment curve: full speed to 80%, 30% speed above.
2. **Greedy insertion** — trips in start-time order; each is offered to every cab, either directly or
   after a charging stop at one of its nearest hubs. The cheapest feasible option wins. A cab only takes
   a trip if it can still reach a charger afterwards in worst-case traffic.
   - **Tariff-aware charging:** for each free charger slot the cab tries plugging in straight away and at
     every later tariff change it has time for (e.g. waiting until 22:00 for ₹5). At the ₹12 peak it buys
     little more than the next trip needs; at normal or off-peak prices it fills to the target. A session
     that crosses a tariff boundary pays each minute at that minute's rate.
   - **Multi-start:** 16 configurations of the scoring knobs run in parallel; the plan with the fewest backup
     trips, then the lowest cost including overnight refill, wins. Results are deterministic.
   - **Local search:** 5,000 times, it picks a backup trip and 5 cabs near its pickup, clears their later work and
     rebuilds it with the same rule plus some randomness. A rebuild stays only if it means fewer backups, or the same
     number at lower cost. It mostly saves money: 7% on average, and 0.15 backup trips.
3. **Independent validator** — recomputes every plan from scratch and checks 14 hard rules
   (battery floor, charger overlap, hub capacity, charging curve, trip deadlines, session cost, …).
   It shares no code with the planner's feasibility checks.
4. **Lower bound** — a Hopcroft–Karp maximum matching over "cab/trip → next trip it could reach in time"
   gives the minimum number of trips that must go to backup in *any* plan. This is sound because the
   traffic model is FIFO (leaving later never arrives earlier), so no plan can reach a pickup sooner by
   waiting or splitting a drive; property tests check this over 100,000 random cases.

## Live re-planning

When a cab reports a different state of charge than planned, or a charger fails, `Replanner` repairs the
plan for the rest of the shift:

1. **Snapshot** at the event time: finished activities become history; each cab starts where it really is.
   Activities in progress are kept (they cannot be undone). The rest of the plan is checked by the same validator.
2. **Apply the event**: the charge gap is applied to the cab's current activity, or the charger is marked dead
   (a cab charging on it stops with what it gained so far).
3. **Repair locally**: the affected cabs plus the 10 nearest (40 if a trip would be lost and time allows)
   are re-planned. A trip stays with its original cab whenever that cab can still do it, a cab whose
   situation did not change keeps its exact steps, and a trip that must move prefers a cab that can still
   do its own next trip, so one change does not knock on into a chain.
4. **Report** a `PlanDiff` (trips moved, sent to backup or rescued, sessions added/removed) and the validator verdict.

| Event (seed 42, single greedy plan) | Old plan now | Re-plan | Changes |
|---|---|---|---|
| CAB-007 reports 22% at 19:30 (plan: 47%) | INVALID (9 violations) | VALID, about 40 ms | 5 cabs, 7 trips moved, 0 to backup |
| Charger H1-C1 fails at 19:00 | INVALID (29 violations) | VALID, 40 ms | 0 trips moved; 25 sessions moved to other chargers |
| 200 random drift / failure events (final plan) | | 200/200 VALID | p50 5 ms · p99 17 ms · max 18 ms (budget 2 s) |

```bash
./mvnw -q -pl planner-core exec:java -Dexec.args="--idle 2 --drift CAB-007@19:30=22%"
./mvnw -q -pl planner-core exec:java -Dexec.args="--idle 2 --fail-charger H1-C1@19:00"
./mvnw -q -pl planner-core exec:java -Dexec.args="--idle 2 --replan-bench 200"
```

## Run the service

Requires only Docker. The first build downloads dependencies and takes several minutes.

```bash
docker compose up --build
```

This starts PostgreSQL and the API on `localhost:8080`, and seeds two demo tenants: `acme` with the planned
300-cab shift (id 1) and `globex` with a small one (id 2). Users are `admin@acme`, `operator@acme`,
`employee@acme` and `admin@globex`, all with password `demo-password` (demo values, overridable with
`SEED_PASSWORD` and `JWT_SECRET`). Compose turns these demo values on; the app alone has no default secret and no demo
accounts, so it refuses to start without `JWT_SECRET`.

```bash
TOKEN=$(curl -s localhost:8080/auth/login -H 'content-type: application/json' \
  -d '{"username":"operator@acme","password":"demo-password"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')

curl -s localhost:8080/shifts/1/plan/summary -H "Authorization: Bearer $TOKEN"

curl -s localhost:8080/shifts/1/events/soc -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"cabId":"CAB-007","socPercent":22,"observedAt":"19:30","seq":1,"idempotencyKey":"demo-1"}'
```

| Endpoint | Role | Purpose |
|---|---|---|
| `POST /auth/login` | anyone | Get a JWT |
| `POST /admin/shifts` | ADMIN | Create a shift from generator parameters |
| `POST /shifts/{id}/plan` | ADMIN | Run the planner; refused once live events were applied |
| `GET /shifts/{id}/plan/summary`, `/unassigned`, `/validate`, `/versions` | OPERATOR, ADMIN | Live plan, backup reasons, validator, audit trail |
| `GET /shifts/{id}/plan/overnight` | OPERATOR, ADMIN | Whether every cab can refill by morning at the night rate |
| `GET /shifts/{id}/cabs/{cabId}/timeline` | OPERATOR, ADMIN | A cab's whole day with charge at each step |
| `POST /shifts/{id}/events/soc` | OPERATOR, ADMIN | Battery drift; returns the diff |
| `POST /shifts/{id}/chargers/{chargerId}/failure` | OPERATOR, ADMIN | Charger failure; returns the diff |
| `GET /me/trip` | EMPLOYEE | Own trip only |
| `GET /actuator/prometheus`, `/actuator/health` | anyone | Metrics and health |

How the service behaves under failure:

- **Single writer per shift:** each event runs in one transaction holding the shift's PostgreSQL row lock, also
  across app instances. The new version, sequence number and stored response commit together or not at all.
- **Duplicates and ordering:** a repeated idempotency key replays the stored response; a sequence number not
  newer than the cab's last one gets `409 EVENT_OUT_OF_ORDER`. The same key on a different event gets
  `409 IDEMPOTENCY_KEY_REUSED`, and a time after the shift ends gets `422 EVENT_TIME_AFTER_SHIFT`.
- **Validator as a gate:** a repair the validator rejects returns `422` and the previous version stays live. A planner
  timeout returns `503` and keeps the last plan.
- **Stranded cabs:** a cab too empty to finish what it is doing leaves service for the shift. Its current trip goes to a
  backup cab, its later trips move to other cabs, and the response, `cabs.stranded` and a warning log tell dispatch.
- **Late events** take effect at the plan's current clock, which never goes backwards.
- **Tenancy:** the tenant comes from the signed token and every lookup takes it, so another tenant's shift is a
  `404`, not a `403`. Errors are RFC 7807 JSON with a stable `code` and no internals.
- **Caching:** parsed plans are cached by (shift, version). Versions are immutable, so entries cannot go stale, and
  writers fill the cache only after commit.
- **Observability:** `replan.latency`, `plan.latency`, `events{type,outcome}`, `event.lag.minutes`,
  `validator.failures`, `planner.timeouts`, `cabs.stranded`, `unassigned.trips`; JSON logs with a correlation id per request. Logs
  carry ids only, no credentials or employee locations.

`ApiTest` covers all of this over HTTP against a real PostgreSQL (embedded, so `./mvnw test` needs no Docker),
including 50 concurrent events producing strictly ordered, valid versions. `FailureInjectionTest` breaks a save partway
through an event and checks that nothing was kept and the same key can retry.

## Run the engine alone

Requires Java 21+. The Maven wrapper is included, so Maven does not need to be installed.

```bash
./mvnw test                                                   # 115 tests
./mvnw -q -DskipTests install
./mvnw -q -pl planner-core exec:java -Dexec.args="--timeline CAB-007"
```

Output files are written to `out/` (`plan.json`, `validator-report.json`, `summary.json`).
Scenario options: `--cabs`, `--hubs`, `--chargers-per-hub`, `--trips`, `--seed`. Passing any planner knob
(`--idle`, `--lowSoc`, `--lowSocPenalty`, `--target`, `--peakTarget`, `--hubsToTry`) runs that single
configuration instead of the multi-start.

Java 24 prints `WARNING:` lines from Maven's own libraries; they are harmless and unrelated to this code.

## Data and assumptions

Scenarios are generated deterministically from a seed (`ScenarioGenerator`), so every number above can be
reproduced. Drop-off homes are 3–14 km from the office, typical of city employee transport. Traffic speeds
are illustrative averages (stop-and-go included) and live in one table, `ScenarioGenerator.standardTraffic()`,
so measured speeds for a real city can replace them without touching the planner.

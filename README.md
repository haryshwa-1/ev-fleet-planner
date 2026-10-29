# EV Fleet Charging & Trip Assignment

MoveInSync case study 6: plan an evening shift for an electric cab fleet — which cab does which trip,
and when and where each cab charges — without any cab running below its battery floor, any hub
exceeding its chargers, or any trip finishing late. Trips no EV can cover go to backup cabs with a reason.

> Status: the planning engine (`planner-core`) is complete and tested. Live re-planning, the
> Spring Boot API and the full design write-up are in progress.

## Results (300 cabs, 5 hubs / 40 chargers, 1,500 trips, seed 42)

| | |
|---|---|
| Planning time | ~0.75 s (target: under 30 s) |
| Independent validator | VALID — 38,115 checks, 0 violations, lowest SoC 15.9% (floor 15%) |
| Coverage | 1,456 trips by EV, 44 to backup (97.1%) |
| Proven lower bound | any plan needs ≥ 35 backup trips, so this plan is at most 9 trips from optimal |
| Charging | 342 sessions, ₹22,675.89 |

## How it works

1. **Physics** — road distance = 1.3 × straight line; peak traffic (17:30–21:00) is slower and uses more
   energy; AC adds 10%. Charging is a two-segment curve: full speed to 80%, 30% speed above it.
2. **Greedy insertion** — trips in start-time order; each is offered to every cab, either directly or
   after a charging stop at one of its nearest hubs. The cheapest feasible option wins. A cab only takes
   a trip if it can still reach a charger afterwards in worst-case traffic.
3. **Independent validator** — recomputes every plan from scratch and checks 13 hard rules
   (battery floor, charger overlap, hub capacity, charging curve, trip deadlines, …). It shares no code
   with the planner's feasibility checks.
4. **Lower bound** — a Hopcroft–Karp maximum matching over "cab/trip → next trip it could reach in time"
   gives the minimum number of trips that must go to backup in *any* plan.

## Run it

Requires Java 21+. The Maven wrapper is included, so Maven does not need to be installed.

```bash
./mvnw test                                                   # 43 tests
./mvnw -q -DskipTests install
./mvnw -q -pl planner-core exec:java -Dexec.args="--timeline CAB-007"
```

Output files are written to `out/` (`plan.json`, `validator-report.json`, `summary.json`).
Other options: `--cabs`, `--hubs`, `--chargers-per-hub`, `--trips`, `--seed`.

Java 24 prints `WARNING:` lines from Maven's own libraries; they are harmless and unrelated to this code.

## Data

Scenarios are generated deterministically from a seed (`ScenarioGenerator`), so every number above can be
reproduced. Drop-off homes are 3–14 km from the office, typical of city employee transport.

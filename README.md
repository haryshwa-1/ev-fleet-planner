# EV Fleet Charging & Trip Assignment

MoveInSync case study 6: plan an evening shift for an electric cab fleet — which cab does which trip,
and when and where each cab charges — without any cab running below its battery floor, any hub
exceeding its chargers, or any trip finishing late. Trips no EV can cover go to backup cabs with a reason.

> Status: the planning engine (`planner-core`) is complete and tested. Live re-planning, the
> Spring Boot API and the full design write-up are in progress.

## Results (300 cabs, 5 hubs / 40 chargers, 1,500 trips, seed 42)

| | |
|---|---|
| Planning time | ~1.1 s (target: under 30 s) |
| Independent validator | VALID — 37,976 checks, 0 violations, lowest SoC 15.5% (floor 15%) |
| Coverage | 1,444 trips by EV, 56 to backup (96.3%) |
| Proven lower bound | any plan needs ≥ 46 backup trips, so this plan is at most 10 trips from optimal |
| Backup reasons | 41 no cab can reach the pickup in time · 13 not enough charge · 2 chargers full |
| Charging | 333 sessions, 2,659 kWh, ₹22,853 (42% of energy bought at the ₹12 peak rate) |

## How it works

1. **Physics** — road distance = 1.3 × straight line. Traffic is a configurable list of time bands
   (evening default: building up from 16:30, worst 17:30–19:00 at 18 km/h, easing until 23:00, then
   36 km/h free flow). A drive changes speed the moment it enters a new band, and km driven in slow
   traffic use more energy. AC adds 10%. Charging is a two-segment curve: full speed to 80%, 30% speed above.
2. **Greedy insertion** — trips in start-time order; each is offered to every cab, either directly or
   after a charging stop at one of its nearest hubs. The cheapest feasible option wins. A cab only takes
   a trip if it can still reach a charger afterwards in worst-case traffic.
3. **Independent validator** — recomputes every plan from scratch and checks 14 hard rules
   (battery floor, charger overlap, hub capacity, charging curve, trip deadlines, session cost, …).
   It shares no code with the planner's feasibility checks.
4. **Lower bound** — a Hopcroft–Karp maximum matching over "cab/trip → next trip it could reach in time"
   gives the minimum number of trips that must go to backup in *any* plan. This is sound because the
   traffic model is FIFO (leaving later never arrives earlier), so no plan can reach a pickup sooner by
   waiting or splitting a drive; property tests check this over 100,000 random cases.

## Run it

Requires Java 21+. The Maven wrapper is included, so Maven does not need to be installed.

```bash
./mvnw test                                                   # 57 tests
./mvnw -q -DskipTests install
./mvnw -q -pl planner-core exec:java -Dexec.args="--timeline CAB-007"
```

Output files are written to `out/` (`plan.json`, `validator-report.json`, `summary.json`).
Other options: `--cabs`, `--hubs`, `--chargers-per-hub`, `--trips`, `--seed`.

Java 24 prints `WARNING:` lines from Maven's own libraries; they are harmless and unrelated to this code.

## Data and assumptions

Scenarios are generated deterministically from a seed (`ScenarioGenerator`), so every number above can be
reproduced. Drop-off homes are 3–14 km from the office, typical of city employee transport. Traffic speeds
are illustrative averages (stop-and-go included) and live in one table, `ScenarioGenerator.standardTraffic()`,
so measured speeds for a real city can replace them without touching the planner.

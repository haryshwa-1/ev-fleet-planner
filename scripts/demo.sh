#!/usr/bin/env bash
# Walks through the service for a live demo. Start from clean data:
#   docker compose down -v && docker compose up --build -d --wait
# Press Enter to move to each step. Set NO_PAUSE=1 to run straight through.
set -euo pipefail

API=${API:-http://localhost:8080}
PASSWORD=${SEED_PASSWORD:-demo-password}
RUN=$(date +%s)
# Windows installs Python as "python".
PYTHON=$(command -v python3 || command -v python)

step() {
    printf '\n=== %s\n' "$1"
    if [ -z "${NO_PAUSE:-}" ]; then read -r -p "(Enter) " _; fi
}

login() {
    curl -sf "$API/auth/login" -H 'content-type: application/json' \
        -d "{\"username\":\"$1\",\"password\":\"$PASSWORD\"}" |
        "$PYTHON" -c 'import json,sys; print(json.load(sys.stdin)["token"])'
}

# call TOKEN METHOD PATH [BODY]: prints the HTTP status and the JSON, cut to MAX_LINES.
call() {
    local args=(-s -w '\n%{http_code}' -X "$2" "$API$3" -H "Authorization: Bearer $1")
    if [ $# -ge 4 ]; then args+=(-H 'content-type: application/json' -d "$4"); fi
    local output status body
    output=$(curl "${args[@]}")
    status=${output##*$'\n'}
    body=${output%$'\n'*}
    echo "HTTP $status"
    echo "$body" | "$PYTHON" -m json.tool 2>/dev/null | head -n "${MAX_LINES:-30}" || true
}

ADMIN=$(login admin@acme)
OPERATOR=$(login operator@acme)
EMPLOYEE=$(login employee@acme)
OTHER=$(login admin@globex)
SHIFT=1

step "Plan summary: coverage, cost, proven lower bound, gap"
call "$OPERATOR" GET "/shifts/$SHIFT/plan/summary"

step "The independent validator on this plan"
MAX_LINES=8 call "$OPERATOR" GET "/shifts/$SHIFT/plan/validate"

step "Backup trips, each with a reason"
MAX_LINES=17 call "$OPERATOR" GET "/shifts/$SHIFT/plan/unassigned"

step "One cab's timeline, with charge at each step"
MAX_LINES=24 call "$OPERATOR" GET "/shifts/$SHIFT/cabs/CAB-007/timeline"

DRIFT="{\"cabId\":\"CAB-007\",\"socPercent\":22,\"observedAt\":\"19:30\",\"seq\":$RUN,\"idempotencyKey\":\"demo-$RUN\"}"
step "Live: CAB-007 reports 22% at 19:30. Repair, with the list of changes"
MAX_LINES=40 call "$OPERATOR" POST "/shifts/$SHIFT/events/soc" "$DRIFT"

step "The same event again: answered from storage, nothing applied twice"
MAX_LINES=4 call "$OPERATOR" POST "/shifts/$SHIFT/events/soc" "$DRIFT"

step "Live: charger H1-C1 fails at 19:45"
MAX_LINES=12 call "$OPERATOR" POST "/shifts/$SHIFT/chargers/H1-C1/failure" \
    "{\"failedAt\":\"19:45\",\"idempotencyKey\":\"demo-charger-$RUN\"}"

step "Validator again, and the version history"
MAX_LINES=3 call "$OPERATOR" GET "/shifts/$SHIFT/plan/validate"
call "$OPERATOR" GET "/shifts/$SHIFT/plan/versions"

step "Another tenant's token on this shift: 404"
call "$OTHER" GET "/shifts/$SHIFT/plan/summary"

step "An employee reading plans: 403. Their own trip: fine"
call "$EMPLOYEE" GET "/shifts/$SHIFT/plan/summary"
call "$EMPLOYEE" GET "/me/trip"

step "Metrics"
curl -s "$API/actuator/prometheus" | grep -E '^(events_total|replan_latency_seconds_(count|max)|unassigned_trips|validator_failures_total|planner_timeouts_total)'

printf '\nDone.\n'

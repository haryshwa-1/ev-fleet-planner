#!/usr/bin/env python3
"""Sends battery events to a running API and reports throughput and latency.

    python3 scripts/load_test.py [--clients 1] [--events 200]

Needs the demo data from docker compose. Standard library only.
"""
import argparse
import json
import os
import statistics
import threading
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument("--api", default="http://localhost:8080")
parser.add_argument("--clients", type=int, default=1)
parser.add_argument("--events", type=int, default=200)
parser.add_argument("--shift", default="1")
args = parser.parse_args()
password = os.environ.get("SEED_PASSWORD", "demo-password")


def post(path, body, token=None):
    request = urllib.request.Request(args.api + path, json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": "application/json"})
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


status, body = post("/auth/login", {"username": "operator@acme", "password": password})
if status != 200:
    raise SystemExit(f"login failed with status {status}; is the API running?")
token = json.loads(body)["token"]

run = int(time.time())
latencies, statuses = [], {}
lock = threading.Lock()


def client(number):
    for i in range(args.events // args.clients):
        # Each client has its own cab, so sequence numbers only grow per cab.
        event = {"cabId": f"CAB-{number + 1:03d}", "socPercent": 40 + i % 40, "observedAt": "19:30",
                 "seq": run * 100_000 + i, "idempotencyKey": f"load-{run}-{number}-{i}"}
        started = time.perf_counter()
        status, _ = post(f"/shifts/{args.shift}/events/soc", event, token)
        millis = (time.perf_counter() - started) * 1000
        with lock:
            latencies.append(millis)
            statuses[status] = statuses.get(status, 0) + 1


threads = [threading.Thread(target=client, args=(n,)) for n in range(args.clients)]
started = time.perf_counter()
for t in threads:
    t.start()
for t in threads:
    t.join()
elapsed = time.perf_counter() - started

latencies.sort()
print(f"clients={args.clients} events={len(latencies)} statuses={statuses}")
print(f"throughput {len(latencies) / elapsed:.1f} events/s, p50 {statistics.median(latencies):.0f} ms, "
      f"p99 {latencies[int(len(latencies) * 0.99) - 1]:.0f} ms, max {latencies[-1]:.0f} ms")

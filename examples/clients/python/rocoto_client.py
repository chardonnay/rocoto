#!/usr/bin/env python3
"""Talks to Rocoto with the `requests` library: read, bulk, exec and Server-Sent Events.

Environment: ROCOTO_URL (default http://localhost:8080/rocoto) and either ROCOTO_TOKEN or
ROCOTO_USER + ROCOTO_PASSWORD.
"""
import json
import os
import sys
from urllib.parse import quote

import requests

BASE = os.environ.get("ROCOTO_URL", "http://localhost:8080/rocoto").rstrip("/")
ORDERS = "com.example.demo:type=Orders"


def new_session() -> requests.Session:
    session = requests.Session()
    token = os.environ.get("ROCOTO_TOKEN")
    if token:
        session.headers["Authorization"] = f"Bearer {token}"
    elif os.environ.get("ROCOTO_USER") and os.environ.get("ROCOTO_PASSWORD"):
        session.auth = (os.environ["ROCOTO_USER"], os.environ["ROCOTO_PASSWORD"])
    else:
        sys.exit("Set ROCOTO_TOKEN, or ROCOTO_USER and ROCOTO_PASSWORD")
    # For a private CA: session.verify = "/path/to/ca.crt". Never disable verification.
    return session


def check(response: requests.Response) -> dict:
    """Rocoto reports errors as JSON: {"status": 403, "error": "Access denied by policy"}."""
    if response.status_code == 429:
        raise RuntimeError(f"locked out, retry after {response.headers.get('Retry-After')} s")
    if not response.ok:
        try:
            message = response.json().get("error")
        except ValueError:
            message = response.text
        raise RuntimeError(f"HTTP {response.status_code}: {message}")
    return response.json()


def read(session, mbean, attribute):
    # Resource style: the ObjectName is ONE URL-encoded path segment.
    url = f"{BASE}/api/v1/mbeans/{quote(mbean, safe='')}/attributes/{quote(attribute, safe='')}"
    return check(session.get(url, timeout=30))["value"]


def rpc(session, kind, body):
    # RPC style: json= sets the mandatory Content-Type: application/json.
    return check(session.post(f"{BASE}/api/v1/{kind}", json=body, timeout=30))


def subscribe(session, mbean, attribute, count, interval=1000):
    """Server-Sent Events with stream=True; yields (event, data) tuples."""
    params = {"mbean": mbean, "attribute": attribute, "interval": interval, "onChange": "false"}
    with session.get(f"{BASE}/api/v1/subscribe", params=params, stream=True,
                     headers={"Accept": "text/event-stream"}, timeout=(5, 60)) as response:
        if response.status_code != 200:
            check(response)  # denied or unknown targets fail before the stream starts
        event, seen = "message", 0
        for line in response.iter_lines(decode_unicode=True):
            if line.startswith("event: "):
                event = line[7:]
            elif line.startswith("data: "):
                yield event, json.loads(line[6:])
                seen += event in ("value", "error")
                if seen >= count:
                    return  # leaving the with block closes the connection = unsubscribes


def main():
    session = new_session()

    print("== who am I")
    print(check(session.get(f"{BASE}/api/v1/server", timeout=30))["caller"])

    print("== read")
    print("heap used:", read(session, "java.lang:type=Memory", "HeapMemoryUsage")["used"])
    print(rpc(session, "read", {"mbean": ORDERS, "attributes": ["OrdersProcessed", "QueueDepth", "DbPassword"]}))

    print("== bulk: every item carries its own status")
    bulk = rpc(session, "bulk", [
        {"type": "read", "mbean": "java.lang:type=Threading", "attribute": "ThreadCount"},
        {"type": "read", "mbean": "java.lang:type=Runtime", "attribute": "DoesNotExist"},
        {"type": "search", "pattern": "java.lang:type=GarbageCollector,*"},
    ])
    for item in bulk["results"]:
        print(item["status"], item.get("value", item.get("mbeans", item.get("error"))))

    print("== exec (needs an allow rule for the caller's role)")
    try:
        print(rpc(session, "exec", {"mbean": ORDERS, "operation": "drainQueue", "arguments": [5]}))
    except RuntimeError as e:
        print("failed:", e)

    print("== error handling")
    try:
        read(session, "nope:type=None", "X")
    except RuntimeError as e:
        print("failed:", e)

    print("== Server-Sent Events: three values of QueueDepth")
    for event, data in subscribe(session, ORDERS, "QueueDepth", count=3):
        print(event, data)


if __name__ == "__main__":
    main()

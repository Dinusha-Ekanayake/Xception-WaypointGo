#!/usr/bin/env python3
"""F-15: every service in the resolved compose file has a memory limit.

Reads the output of `docker compose ... config` on stdin or from a path, and
fails naming any service without `mem_limit`, so a new service cannot quietly
take the whole server. Also prints the total, to compare with the server's RAM.
"""
import re
import sys

text = open(sys.argv[1]).read() if len(sys.argv) > 1 else sys.stdin.read()
body = text.split("\nservices:\n", 1)[1] if "\nservices:\n" in text else text.split("services:\n", 1)[1]
body = re.split(r"^\S", body, maxsplit=1, flags=re.M)[0]
services = re.findall(r"^  ([a-zA-Z0-9_-]+):$", body, re.M)
limits = {}
current = None
for line in body.split("\n"):
    m = re.match(r"^  ([a-zA-Z0-9_-]+):$", line)
    if m:
        current = m.group(1)
    m = re.match(r'^    mem_limit: "?(\d+)"?$', line)
    if m and current:
        limits[current] = int(m.group(1))
missing = [s for s in services if s not in limits]
total = sum(limits.values()) / 2**30
print(f"{len(limits)} services, {total:.1f} GiB of limits")
if missing:
    print("No mem_limit: " + ", ".join(missing), file=sys.stderr)
    sys.exit(1)

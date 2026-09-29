"""Prove the only change to every touched recipe is the top-level `conditions` key."""
import json, os, subprocess, sys

sys.stdout.reconfigure(encoding="utf-8")

ROOT = "src/main/resources/data"
names = subprocess.run(
    ["git", "diff", "--name-only", "--", ROOT],
    capture_output=True, text=True, encoding="utf-8", check=True,
).stdout.split()

bad = []
ok = 0
for rel in names:
    old_raw = subprocess.run(
        ["git", "show", "HEAD:" + rel], capture_output=True, check=True
    ).stdout.decode("utf-8")
    with open(rel, "r", encoding="utf-8") as f:
        new_raw = f.read()

    old = json.loads(old_raw)
    new = json.loads(new_raw)

    old_wo = {k: v for k, v in old.items() if k != "conditions"}
    new_wo = {k: v for k, v in new.items() if k != "conditions"}

    if old_wo != new_wo:
        bad.append((rel, "payload differs"))
        continue
    if "conditions" not in new or not isinstance(new["conditions"], list):
        bad.append((rel, "conditions missing or not a list"))
        continue
    for c in new["conditions"]:
        if not isinstance(c, dict) or "type" not in c:
            bad.append((rel, "bad condition entry: %r" % (c,)))
            break
    else:
        ok += 1

print("files diffed      :", len(names))
print("payload identical :", ok)
print("problems          :", len(bad))
for r, why in bad[:20]:
    print("  ", r, "->", why)
sys.exit(1 if bad else 0)

"""Prove the only change to every touched recipe is the top-level `conditions` key.

Three populations are checked, because `git diff` alone silently misses two of them:

  1. tracked, modified  -- payload must be byte-equal once `conditions` is stripped
  2. deleted            -- reported; there is no file left to compare, so it cannot
                           be silently folded into "0 problems"
  3. untracked          -- a brand new recipe has no HEAD baseline, so payload
                           comparison is impossible; the conditions block is still
                           checked for shape and the file is reported by name

Run from the repo root:  python tools/verify_only_conditions_changed.py
"""
import json
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")

ROOT = "src/main/resources/data"


def git(*args):
    return subprocess.run(
        ["git", *args], capture_output=True, text=True,
        encoding="utf-8", check=True,
    ).stdout.splitlines()


def head_doc(rel):
    raw = subprocess.run(
        ["git", "show", "HEAD:" + rel], capture_output=True, check=True
    ).stdout.decode("utf-8")
    return json.loads(raw)


def check_conditions(doc):
    """Shape of the top-level `conditions` key. Returns a problem string or None."""
    if "conditions" not in doc or not isinstance(doc["conditions"], list):
        return "conditions missing or not a list"
    for c in doc["conditions"]:
        if not isinstance(c, dict) or "type" not in c:
            return "bad condition entry: %r" % (c,)
    return None


def main():
    # 必须比对 HEAD 而不是默认的 index：`git diff` 比的是「工作区 vs 暂存区」，
    # 已经 git add 过但还没 commit 的改动会被完全忽略 —— 而那恰恰是最该查的一批。
    # -d 排除删除项，否则 open() 一个已不存在的路径只会抛栈而不是给出结论。
    modified = git("diff", "HEAD", "--name-only", "--diff-filter=d", "--", ROOT)
    deleted = git("diff", "HEAD", "--name-only", "--diff-filter=D", "--", ROOT)
    untracked = git("ls-files", "--others", "--exclude-standard", "--", ROOT)

    bad = []
    ok = 0
    for rel in modified:
        with open(rel, "r", encoding="utf-8") as f:
            new = json.load(f)
        old = head_doc(rel)

        old_wo = {k: v for k, v in old.items() if k != "conditions"}
        new_wo = {k: v for k, v in new.items() if k != "conditions"}
        if old_wo != new_wo:
            bad.append((rel, "payload differs"))
            continue
        why = check_conditions(new)
        if why:
            bad.append((rel, why))
            continue
        ok += 1

    # No HEAD baseline: only the conditions shape is checkable.
    new_files = []
    for rel in untracked:
        if not rel.endswith(".json"):
            continue
        with open(rel, "r", encoding="utf-8") as f:
            doc = json.load(f)
        why = check_conditions(doc)
        if why:
            bad.append((rel, "untracked: " + why))
        else:
            new_files.append(rel)

    print("modified (payload identical, conditions well-formed): %d" % ok)
    print("deleted  (no baseline, reported only)                  : %d" % len(deleted))
    print("untracked(conditions well-formed, payload NOT checked) : %d" % len(new_files))
    print("problems                                                : %d" % len(bad))

    if deleted:
        print("\n--- deleted recipe files ---")
        for rel in deleted:
            print("   %s" % rel)
    if new_files:
        print("\n--- untracked recipe files (payload comparison impossible) ---")
        for rel in new_files[:20]:
            print("   %s" % rel)
        if len(new_files) > 20:
            print("   ... and %d more" % (len(new_files) - 20))
    if bad:
        print("\n--- problems ---")
        for rel, why in bad[:20]:
            print("   %s -> %s" % (rel, why))
        if len(bad) > 20:
            print("   ... and %d more" % (len(bad) - 20))

    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()

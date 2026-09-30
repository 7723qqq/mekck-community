"""Predict which mekck recipes still fail after conditioning, against a real mod set.

Mirrors the three gates that actually throw:
  1. RecipeManager: top-level "conditions" not met -> recipe skipped, no error
  2. serializer lookup on "type" namespace
  3. CraftingHelper.getItem / getIngredient on "item" namespaces

Usage: python tools/predict_residual_recipe_errors.py <mods-dir> [--client-jar <jar>]
"""
import json, os, re, sys, zipfile, glob
from collections import Counter

sys.stdout.reconfigure(encoding="utf-8")

NAMESpaced = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")
RECIPES = "src/main/resources/data/mekck/recipes"
ITEM_KEYS = {"item"}
TYPE_KEYS = {"type"}


def installed_modids(mods_dir):
    ids = {"minecraft", "forge"}
    for jar in glob.glob(os.path.join(mods_dir, "*.jar")):
        try:
            with zipfile.ZipFile(jar) as z:
                for n in z.namelist():
                    if n.endswith("META-INF/mods.toml"):
                        s = z.read(n).decode("utf-8", errors="replace")
                        for ln in s.splitlines():
                            st = ln.split("#", 1)[0].strip()
                            if st.startswith("modId") and "=" in st:
                                v = st.split("=", 1)[1].strip().strip('"').strip("'")
                                if v and re.fullmatch(r"[a-z0-9_.-]+", v):
                                    # template placeholders resolve to mekck
                                    ids.add(v)
                        break
        except Exception:
            pass
    return ids


def conditions_met(cond, loaded):
    if not cond:
        return True
    for c in cond:
        t = c.get("type")
        if t == "forge:mod_loaded":
            if c.get("modid") not in loaded:
                return False
        elif t == "forge:item_exists":
            if c.get("item", "").split(":", 1)[0] not in loaded:
                return False
        elif t == "forge:false":
            return False
        elif t == "forge:and":
            if not conditions_met(c.get("conditions", []), loaded):
                return False
        elif t == "forge:or":
            if not any(conditions_met(x, loaded) for x in [c.get("condition", {})]):
                return False
        elif t == "forge:not":
            if conditions_met(c.get("condition", {}), loaded):
                return False
        elif t == "forge:true":
            pass
        else:
            return False
    return True


def main():
    mods_dir = sys.argv[1]
    loaded = installed_modids(mods_dir)
    print("installed modids (%d): %s\n" % (len(loaded), ", ".join(sorted(loaded))))

    skipped = 0
    residual = Counter()
    residual_files = []
    clean = 0

    for dp, _, fn in os.walk(RECIPES):
        for f in sorted(fn):
            if not f.endswith(".json"):
                continue
            path = os.path.join(dp, f)
            rel = os.path.relpath(path, RECIPES).replace(os.sep, "/")
            with open(path, encoding="utf-8") as fh:
                doc = json.load(fh)

            if doc.get("type") == "forge:conditional":
                skipped += 1
                continue
            if not conditions_met(doc.get("conditions"), loaded):
                skipped += 1
                continue

            refs = {}
            def rec(n, k):
                if isinstance(n, dict):
                    for a, b in n.items():
                        rec(b, a)
                elif isinstance(n, list):
                    for b in n:
                        rec(b, k)
                elif isinstance(n, str) and NAMESpaced.match(n):
                    refs.setdefault(k, set()).add(n.split(":", 1)[0])
            rec(doc, None)

            rtype_ns = doc.get("type", "").split(":", 1)[0]
            item_ns = refs.get("item", set()) | refs.get("result", set())
            bad = set()
            if rtype_ns and rtype_ns not in loaded:
                bad.add(("recipe_type", rtype_ns))
            for ns in item_ns:
                if ns not in loaded:
                    bad.add(("item", ns))
            if bad:
                for kind, ns in bad:
                    residual[(kind, ns)] += 1
                residual_files.append((rel, sorted(bad)))
            else:
                clean += 1

    print("clean (will load)   :", clean)
    print("skipped by condition:", skipped)
    print("residual failures   :", len(residual_files))
    print("\n--- residual by cause ---")
    for (kind, ns), c in residual.most_common():
        print("%5d  %-12s %s" % (c, kind, ns))
    print("\n--- residual files (first 25) ---")
    for rel, bad in residual_files[:25]:
        print("  %s -> %s" % (rel, bad))


if __name__ == "__main__":
    main()

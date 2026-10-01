"""Predict which mekck recipes still fail after conditioning, against a real mod set.

Mirrors the three gates that actually throw:
  1. RecipeManager: top-level "conditions" not met -> recipe skipped, no error
  2. serializer lookup on "type" namespace
  3. CraftingHelper.getItem / getIngredient on "item" namespaces

Usage: python tools/predict_residual_recipe_errors.py <mods-dir> [--client-jar <jar>]
"""
import argparse
import json, os, re, sys, zipfile, glob
from collections import Counter

sys.stdout.reconfigure(encoding="utf-8")

NAMESpaced = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RECIPES = os.path.join(ROOT, "src", "main", "resources", "data", "mekck", "recipes")


def installed_modids(mods_dir):
    ids = {"minecraft", "forge"}
    unreadable = []
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
                                # 只收字面量 modId；"${...}" 模板值正则不匹配，天然被跳过
                                if v and re.fullmatch(r"[a-z0-9_.-]+", v):
                                    ids.add(v)
                        break
        except Exception as e:
            # 读不出来的 jar 会静默丢掉它的 modid，进而让下面的 residual 虚报
            unreadable.append((os.path.basename(jar), repr(e)))
    return ids, unreadable


KNOWN_COND_TYPES = {
    "forge:mod_loaded", "forge:item_exists", "forge:true",
    "forge:false", "forge:and", "forge:or", "forge:not",
}


def condition_met(c, loaded):
    """Evaluate ONE Forge condition object.

    JSON keys follow the Forge 1.20.1 serializers
    (net.minecraftforge.common.crafting.conditions.{And,Or,Not}Condition$Serializer):

        forge:and / forge:or  ->  "values": [ ... ]   (array)
        forge:not             ->  "value":  { ... }    (single object)

    An unrecognised type is treated as NOT met. That is the conservative choice,
    but it silently hides the recipe in the "skipped" bucket -- so unknown_condition_types()
    exists to name them, and main() reports them explicitly.
    """
    if not isinstance(c, dict):
        return False
    t = c.get("type")
    if t == "forge:mod_loaded":
        return c.get("modid") in loaded
    if t == "forge:item_exists":
        return c.get("item", "").split(":", 1)[0] in loaded
    if t == "forge:true":
        return True
    if t == "forge:false":
        return False
    if t == "forge:and":
        return all(condition_met(v, loaded) for v in c.get("values", []))
    if t == "forge:or":
        return any(condition_met(v, loaded) for v in c.get("values", []))
    if t == "forge:not":
        return not condition_met(c.get("value", {}), loaded)
    return False


def unknown_condition_types(cond, out=None):
    """Collect condition types this tool cannot evaluate (incl. nested ones)."""
    if out is None:
        out = set()
    if isinstance(cond, dict):
        t = cond.get("type")
        if t is not None and t not in KNOWN_COND_TYPES:
            out.add(t)
        for k in ("values", "value"):
            if k in cond:
                unknown_condition_types(cond[k], out)
    elif isinstance(cond, list):
        for x in cond:
            unknown_condition_types(x, out)
    return out


def conditions_met(cond, loaded):
    """Top-level "conditions" is an array, ANDed together."""
    if not cond:
        return True
    if isinstance(cond, dict):
        return condition_met(cond, loaded)
    return all(condition_met(c, loaded) for c in cond)


def load_vanilla_items(client_jar):
    """Approximate the vanilla item set from the client jar's item models.

    Every registered 1.20 item ships assets/minecraft/models/item/<id>.json, so a
    minecraft: id missing there is one this MC version never registered. No
    condition can rescue that recipe -- it needs a content decision.
    """
    if not client_jar:
        return set()
    if not os.path.isfile(client_jar):
        print("!! --client-jar 指向的文件不存在，将跳过原版物品校验：%s" % client_jar)
        return set()
    try:
        with zipfile.ZipFile(client_jar) as z:
            prefix = "assets/minecraft/models/item/"
            return {
                "minecraft:" + n[len(prefix): -len(".json")]
                for n in z.namelist()
                if n.startswith(prefix) and n.endswith(".json")
            }
    except zipfile.BadZipFile as e:
        print("!! --client-jar 不是有效的 jar/zip，跳过原版物品校验：%r" % e)
        return set()


def main():
    ap = argparse.ArgumentParser(
        description="Predict which mekck recipes still fail, against a real mod set.")
    ap.add_argument("mods_dir", help="实例的 mods 目录")
    ap.add_argument("--client-jar", default=None,
                    help="原版 client jar；用于发现本 MC 版本未注册的 minecraft: id")
    args = ap.parse_args()

    loaded, unreadable = installed_modids(args.mods_dir)
    vanilla = load_vanilla_items(args.client_jar)
    print("installed modids (%d): %s" % (len(loaded), ", ".join(sorted(loaded))))
    if args.client_jar:
        print("vanilla items from client jar: %d" % len(vanilla))
    if unreadable:
        print("\n!! %d 个 jar 读不出 mods.toml，其 modid 未计入 —— 下面的 residual 可能虚报："
              % len(unreadable))
        for name, err in unreadable:
            print("   %s: %s" % (name, err))
    print("")

    skipped = 0
    residual = Counter()
    residual_files = []
    clean = 0
    unknown_types = Counter()
    unknown_files = []

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

            # 无法求值的条件类型必须点名：它们会被保守地算进 skipped，
            # 不点名就等于把「我不懂」藏进了「干净跳过」。
            unk = unknown_condition_types(doc.get("conditions"))
            if unk:
                for t in sorted(unk):
                    unknown_types[t] += 1
                unknown_files.append((rel, sorted(unk)))

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
                    refs.setdefault(k, set()).add(n)
            rec(doc, None)

            rtype_ns = doc.get("type", "").split(":", 1)[0]
            item_ids = refs.get("item", set()) | refs.get("result", set())
            bad = set()
            if rtype_ns and rtype_ns not in loaded:
                bad.add(("recipe_type", rtype_ns))
            for i in sorted(item_ids):
                ns = i.split(":", 1)[0]
                if ns not in loaded:
                    bad.add(("item", ns))
            # minecraft: id 在本版本根本没注册 -> 条件化救不了，需要单独内容决策
            for i in sorted(item_ids):
                if i.startswith("minecraft:") and vanilla and i not in vanilla:
                    bad.add(("vanilla_item_missing", i))
            if bad:
                for kind, ns in bad:
                    residual[(kind, ns)] += 1
                residual_files.append((rel, sorted(bad)))
            else:
                clean += 1

    print("clean (will load)   :", clean)
    print("skipped by condition:", skipped)
    print("residual failures   :", len(residual_files))
    if unknown_files:
        print("\n--- 本工具无法求值的条件类型（已保守地算作 skipped）---")
        for t, c in unknown_types.most_common():
            print("%5d  %s" % (c, t))
        print("涉及 %d 个配方，前 10 条:" % len(unknown_files))
        for rel, ts in unknown_files[:10]:
            print("  %s -> %s" % (rel, ts))
    print("\n--- residual by cause ---")
    for (kind, ns), c in residual.most_common():
        print("%5d  %-19s %s" % (c, kind, ns))
    print("\n--- residual files (first 25) ---")
    for rel, bad in residual_files[:25]:
        print("  %s -> %s" % (rel, bad))


if __name__ == "__main__":
    main()

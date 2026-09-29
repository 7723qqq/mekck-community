"""Rewrite mekck recipes so they skip cleanly when their external mods are absent.

Forge patches RecipeManager (patches/net/minecraft/world/item/crafting/RecipeManager.java.patch)
to evaluate a top-level "conditions" member before any serializer runs:

    if (json.isJsonObject() && !CraftingHelper.processConditions(json, "conditions", ctx)) {
        LOGGER.debug("Skipping loading recipe {} as it's conditions were not met", id);
        continue;
    }

So a recipe only needs the correct Forge condition schema at its own top level. No
forge:conditional wrapper, no nesting, no reindentation. Conditions are ANDed
(CraftingHelper.processConditions returns false on the first failure).

Two condition kinds are emitted:
  forge:mod_loaded  - the namespace supplies something (a serializer, an item, a tag)
  forge:item_exists - the mod is installed but that specific item may be missing
                       from the installed version (mekanism_extras 1.5.0 ships
                       assets for its planting factories but never registers them)

Read-only by default; --write rewrites in place with a surgical text edit so the
git diff stays limited to the conditions block.
"""
import argparse
import json
import os
import re
import sys
from collections import Counter, defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RECIPES = os.path.join(ROOT, "src", "main", "resources", "data", "mekck", "recipes")

# mods.toml declares these mandatory; mekck itself is implicit.
ALWAYS_PRESENT = {"minecraft", "forge", "farmersdelight", "mekanism", "mekck"}

# Installed in some setups but the named item may not be registered in that
# version. mod_loaded would pass and the recipe would still throw, so these
# items get forge:item_exists instead.
ITEM_MAY_BE_MISSING = {"mekanism_extras"}

# Owned by another agent's phase 1.
SKIP_BASENAMES = {"creative_upgrade_from_49_foods.json"}

NAMESpaced = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")
ITEM_KEYS = {"item"}
EXTERNAL_KEYS = {"type", "item", "tag", "fluid", "gas"}


def collect(node, key=None, out=None):
    if out is None:
        out = {"namespaces": set(), "soft_items": set(), "all_items": set()}
    if isinstance(node, dict):
        for k, v in node.items():
            collect(v, k, out)
    elif isinstance(node, list):
        for v in node:
            collect(v, key, out)
    elif isinstance(node, str) and NAMESpaced.match(node):
        ns = node.split(":", 1)[0]
        out["namespaces"].add(ns)
        if key in ITEM_KEYS:
            out["all_items"].add(node)
            if ns in ITEM_MAY_BE_MISSING:
                out["soft_items"].add(node)
    return out


def render_conditions(cond):
    body = ",\n".join(json.dumps(c, ensure_ascii=False) for c in cond)
    # re-indent each line one level so it sits inside "conditions": [ ... ]
    inner = body.replace("\n", "\n  ")
    return "[\n    " + inner + "\n  ]"


def find_top_level_conditions_span(text):
    """Return (start, end) of the top-level "conditions" value, or None.

    Matches the key only at two-space indent so nested "conditions" (advancement
    blocks, ingredient sub-objects) cannot be hit, then bracket-counts the value
    while tracking string state.
    """
    for m in re.finditer(r'\n  "conditions"\s*:\s*', text):
        i = m.end()
        if text[i] == "[":
            depth, j, in_str, esc = 0, i, False, False
            while j < len(text):
                ch = text[j]
                if in_str:
                    if esc:
                        esc = False
                    elif ch == "\\":
                        esc = True
                    elif ch == '"':
                        in_str = False
                elif ch == '"':
                    in_str = True
                elif ch in "[{":
                    depth += 1
                elif ch in "]}":
                    depth -= 1
                    if depth == 0:
                        return i, j + 1
                j += 1
            return None
        # non-array value: unusable, treat as absent
        return None
    return None


def apply(path, cond, before):
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()

    block = render_conditions(cond)
    span = find_top_level_conditions_span(text)

    if span is not None:
        new = text[: span[0]] + block + text[span[1] :]
    else:
        close = text.rindex("}")
        head = text[:close].rstrip()
        if not head.endswith(","):
            head += ","
        new = head + "\n  \"conditions\": " + block + "\n}" + text[close + 1 :]

    with open(path, "w", encoding="utf-8") as f:
        f.write(new)

    # verification: must still parse, carry exactly the intended conditions, and
    # differ from the original in nothing but that key
    with open(path, "r", encoding="utf-8") as f:
        back = json.load(f)
    if back.get("conditions") != cond:
        raise SystemExit("verification failed: conditions mismatch in %s" % path)
    stripped = {k: v for k, v in back.items() if k != "conditions"}
    if stripped != before:
        raise SystemExit("verification failed: payload changed in %s" % path)
    return new


def load_vanilla_items(client_jar):
    """Approximate the vanilla item set from the client jar's item models.

    Every registered 1.20 item ships assets/minecraft/models/item/<id>.json, so
    the absence of powder_snow there is exactly why CraftingHelper.getItem
    rejects minecraft:powder_snow at runtime.
    """
    if not client_jar or not os.path.isfile(client_jar):
        return set()
    import zipfile

    with zipfile.ZipFile(client_jar) as z:
        prefix = "assets/minecraft/models/item/"
        return {
            "minecraft:" + n[len(prefix) : -len(".json")]
            for n in z.namelist()
            if n.startswith(prefix) and n.endswith(".json")
        }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--report", default=None, help="write a markdown report here")
    ap.add_argument(
        "--client-jar",
        default=None,
        help="vanilla client jar; enables detection of minecraft: ids that are "
        "not registered in this MC version (those cannot be gated by a condition)",
    )
    args = ap.parse_args()

    vanilla = load_vanilla_items(args.client_jar)

    files = []
    for dirpath, _, names in os.walk(RECIPES):
        for n in names:
            if n.endswith(".json"):
                files.append(os.path.join(dirpath, n))
    files.sort()

    touched = 0
    skipped = 0
    by_mod = Counter()
    by_file_mods = Counter()
    unfixable = defaultdict(list)
    already = 0

    for path in files:
        rel = os.path.relpath(path, ROOT).replace("\\", "/")
        if os.path.basename(path) in SKIP_BASENAMES:
            skipped += 1
            continue
        try:
            with open(path, "r", encoding="utf-8") as f:
                doc = json.load(f)
        except Exception as e:
            print("!! unparseable %s: %r" % (rel, e))
            continue

        if doc.get("type") == "forge:conditional":
            already += 1
            continue

        found = collect(doc)
        external = sorted(found["namespaces"] - ALWAYS_PRESENT)

        for item in sorted(found["all_items"]):
            if item.startswith("minecraft:") and vanilla and item not in vanilla:
                unfixable[rel].append(item)

        if not external and not found["soft_items"]:
            continue

        cond = []
        for ns in external:
            cond.append({"type": "forge:mod_loaded", "modid": ns})
        for item in sorted(found["soft_items"]):
            cond.append({"type": "forge:item_exists", "item": item})

        for ns in external:
            by_mod[ns] += 1
        by_file_mods[len(cond)] += 1
        touched += 1

        if args.write:
            apply(path, cond, {k: v for k, v in doc.items() if k != "conditions"})

    print("scanned            : %d" % len(files))
    print("already conditional: %d" % already)
    print("skipped (other AI) : %d" % skipped)
    print("files to condition : %d" % touched)
    print("condition count/file histogram: %s" % dict(sorted(by_file_mods.items())))
    print()
    print("--- external mods gated (files each) ---")
    for ns, c in by_mod.most_common():
        print("%5d  %s" % (c, ns))
    if unfixable:
        print()
        print("--- NOT fixable by conditioning (need a content decision) ---")
        for rel, items in sorted(unfixable.items()):
            print("  %s -> %s" % (rel, sorted(set(items))))

    if args.report:
        with open(args.report, "w", encoding="utf-8") as f:
            f.write("# mekck 配方条件化改造报告\n\n")
            f.write("生成脚本：`tools/conditional_recipe_audit.py --write`\n\n")
            f.write("## 机制\n\n")
            f.write(
                "Forge 在 `RecipeManager` 加载循环里、**任何序列化器运行之前**，"
                "检查每个配方 JSON 的顶层 `conditions`；不满足则 `continue`，"
                "配方被安静跳过，不产生日志错误。条件数组语义为 AND"
                "（`CraftingHelper.processConditions` 遇首个 false 即返回 false）。\n\n"
                "因此不需要 `forge:conditional` 包装：顶层 `conditions` 是"
                "更直接、diff 更小的写法，且对三类失败（未知配方类型 / 未知物品 / "
                "写错 schema 的 conditions）同时有效。\n\n"
            )
            f.write("## 统计\n\n")
            f.write("| 项 | 值 |\n|---|---|\n")
            f.write("| 扫描配方文件 | %d |\n" % len(files))
            f.write("| 已是 forge:conditional（未动） | %d |\n" % already)
            f.write("| 跳过（另一 AI 阶段 1 占用） | %d |\n" % skipped)
            f.write("| 本次条件化 | %d |\n\n" % touched)
            f.write("## 被门控的外部 mod\n\n| 配方文件数 | modid |\n|---|---|\n")
            for ns, c in by_mod.most_common():
                f.write("| %d | `%s` |\n" % (c, ns))
            if unfixable:
                f.write("\n## 条件化解决不了、需要单独决策\n\n")
                for rel, items in sorted(unfixable.items()):
                    f.write(
                        "- `%s` — %s\n"
                        % (rel, ", ".join("`%s`" % i for i in sorted(set(items))))
                    )
        print("\nreport: %s" % args.report)


if __name__ == "__main__":
    main()

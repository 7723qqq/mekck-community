"""Generate the 8 skewer item textures, models, tags and recipes for mekck:skewering.

Textures are drawn procedurally (16x16 RGBA): a diagonal wooden stick with
three food-coloured chunks threaded on it. Deliberately simple -- these are
placeholders that are readable in the inventory, not art.

Run:  python tools/gen_skewer_assets.py
"""
import json
import os
import struct
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "mekck")
DATA = os.path.join(ROOT, "src", "main", "resources", "data", "mekck")

# id -> (stick segment colours, chunk colours)
SKEWERS = {
    "beef_skewer": ((150, 90, 45), [(140, 70, 45), (60, 130, 60), (190, 150, 60)]),
    "pork_skewer": ((150, 90, 45), [(210, 140, 120), (60, 130, 60), (190, 150, 60)]),
    "chicken_skewer": ((150, 90, 45), [(225, 195, 140), (60, 130, 60), (190, 150, 60)]),
    "mutton_skewer": ((150, 90, 45), [(160, 90, 70), (60, 130, 60), (190, 150, 60)]),
    "fish_skewer": ((150, 90, 45), [(120, 150, 200), (60, 130, 60), (200, 120, 90)]),
    "vegetable_skewer": ((150, 90, 45), [(90, 160, 70), (200, 170, 80), (70, 120, 170)]),
    "mushroom_skewer": ((150, 90, 45), [(200, 190, 180), (140, 90, 60), (200, 190, 180)]),
    "sweetberry_skewer": ((150, 90, 45), [(150, 40, 90), (60, 130, 60), (150, 40, 90)]),
}

NAMES = {
    "beef_skewer": ("Beef Skewer", "牛肉串"),
    "pork_skewer": ("Pork Skewer", "猪肉串"),
    "chicken_skewer": ("Chicken Skewer", "鸡肉串"),
    "mutton_skewer": ("Mutton Skewer", "羊肉串"),
    "fish_skewer": ("Fish Skewer", "海鲜串"),
    "vegetable_skewer": ("Vegetable Skewer", "时蔬串"),
    "mushroom_skewer": ("Mushroom Skewer", "菌菇串"),
    "sweetberry_skewer": ("Sweet Berry Skewer", "甜莓串"),
}


def png(path, pixels, w=16, h=16):
    """Write an RGBA PNG. pixels is a list of rows, each a list of (r,g,b,a)."""
    raw = b""
    for row in pixels:
        raw += b"\x00" + b"".join(bytes(p) for p in row)

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    out = b"\x89PNG\r\n\x1a\n"
    out += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    out += chunk(b"IDAT", zlib.compress(raw, 9))
    out += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(out)


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


def draw(stick_col, chunk_cols):
    """Diagonal stick bottom-left to top-right, with three chunks threaded on it."""
    px = [[(0, 0, 0, 0)] * 16 for _ in range(16)]

    def put(x, y, c, a=255):
        if 0 <= x < 16 and 0 <= y < 16:
            px[y][x] = (c[0], c[1], c[2], a)

    # stick: diagonal band from (3,13) to (12,3)
    for step in range(12):
        x = 3 + step
        y = 13 - step
        put(x, y, stick_col)
        put(x + 1, y, shade(stick_col, 0.75))

    # three chunks, evenly spaced along the stick
    for idx, c in enumerate(chunk_cols):
        cx = 4 + idx * 4
        cy = 12 - idx * 4
        for dx in range(-1, 2):
            for dy in range(-1, 2):
                if abs(dx) + abs(dy) <= 1:
                    put(cx + dx, cy + dy, c)
        # highlight pixel for a bit of shading
        put(cx - 1, cy - 1, shade(c, 1.25))
    return px


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write("\n")


def main():
    for sid, (stick, chunks) in SKEWERS.items():
        png(os.path.join(ASSETS, "textures", "item", sid + ".png"), draw(stick, chunks))
        write_json(
            os.path.join(ASSETS, "models", "item", sid + ".json"),
            {"parent": "minecraft:item/generated", "textures": {"layer0": "mekck:item/" + sid}},
        )

    # ingredient tags so recipes stay readable
    for tag, values in {
        "skewering_proteins": [
            "minecraft:cooked_beef", "minecraft:cooked_porkchop", "minecraft:cooked_chicken",
            "minecraft:cooked_mutton", "minecraft:cooked_rabbit", "minecraft:cooked_cod",
            "minecraft:cooked_salmon",
        ],
        "skewering_vegetables": [
            "minecraft:carrot", "minecraft:potato", "minecraft:beetroot",
            "minecraft:onion", "minecraft:pumpkin",
        ],
        "skewering_mushrooms": [
            "minecraft:brown_mushroom", "minecraft:red_mushroom",
            "minecraft:crimson_fungus", "minecraft:warped_fungus",
        ],
        "skewering_sweet": ["minecraft:sweet_berries", "minecraft:glow_berries"],
    }.items():
        # 标签成员是物品 id 本身；只有指向"另一个标签"时才加 # 前缀
        write_json(
            os.path.join(DATA, "tags", "items", tag + ".json"),
            {"replace": False, "values": values},
        )

    # recipes: stick(tool, not consumed) + primary + side -> skewer
    recipes = {
        "beef_skewer": ("minecraft:cooked_beef", "#mekck:skewering_vegetables"),
        "pork_skewer": ("minecraft:cooked_porkchop", "#mekck:skewering_vegetables"),
        "chicken_skewer": ("minecraft:cooked_chicken", "#mekck:skewering_vegetables"),
        "mutton_skewer": ("minecraft:cooked_mutton", "#mekck:skewering_vegetables"),
        "fish_skewer": ("#minecraft:fishes", "#mekck:skewering_vegetables"),
        "vegetable_skewer": ("#mekck:skewering_vegetables", "minecraft:baked_potato"),
        "mushroom_skewer": ("#mekck:skewering_mushrooms", "#mekck:skewering_vegetables"),
        "sweetberry_skewer": ("#mekck:skewering_sweet", "#mekck:skewering_vegetables"),
    }
    for sid, (primary, side) in recipes.items():
        ing = lambda v: {"tag": v[1:]} if v.startswith("#") else {"item": v}
        write_json(
            os.path.join(DATA, "recipes", "skewering", sid + ".json"),
            {
                "type": "mekck:skewering",
                "ingredient": ing(primary),
                "side": ing(side),
                "sideCount": 1,
                "result": {"item": "mekck:" + sid, "count": 1},
                "processingTime": 100,
            },
        )

    # lang
    for locale, idx in (("en_us", 0), ("zh_cn", 1)):
        p = os.path.join(ASSETS, "lang", locale + ".json")
        with open(p, "r", encoding="utf-8") as f:
            d = json.load(f)
        for sid, pair in NAMES.items():
            d.setdefault("item.mekck." + sid, pair[idx])
        write_json(p, d)

    print("generated %d textures, %d models, 4 tags, %d recipes, 2 lang files"
          % (len(SKEWERS), len(SKEWERS), len(recipes)))


if __name__ == "__main__":
    main()

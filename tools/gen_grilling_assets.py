"""Generate the 8 grilled-food assets for mekck:grilling.

Same approach as gen_skewer_assets.py: procedural 16x16 placeholder textures
(a browned chunk with grill marks), item models, recipe JSON and lang entries.
Deliberately no food properties -- nutrition is a balance decision, not a
mechanism one.

Run:  python tools/gen_grilling_assets.py
"""
import json
import os
import struct
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "mekck")
DATA = os.path.join(ROOT, "src", "main", "resources", "data", "mekck")

# id -> (raw colour, cooked colour)
GRILLED = {
    "grilled_beef": ((150, 70, 60), (110, 60, 42)),
    "grilled_pork": ((225, 140, 130), (176, 106, 78)),
    "grilled_chicken": ((215, 180, 130), (170, 130, 84)),
    "grilled_mutton": ((175, 95, 80), (130, 72, 55)),
    "grilled_cod": ((190, 170, 150), (168, 132, 92)),
    "grilled_salmon": ((225, 130, 110), (198, 104, 72)),
    "grilled_carrot": ((225, 130, 40), (186, 100, 32)),
    "grilled_mushroom": ((215, 200, 180), (176, 148, 112)),
}

NAMES = {
    "grilled_beef": ("Grilled Beef", "烤牛肉"),
    "grilled_pork": ("Grilled Pork", "烤猪肉"),
    "grilled_chicken": ("Grilled Chicken", "烤鸡肉"),
    "grilled_mutton": ("Grilled Mutton", "烤羊肉"),
    "grilled_cod": ("Grilled Cod", "烤鳕鱼"),
    "grilled_salmon": ("Grilled Salmon", "烤鲑鱼"),
    "grilled_carrot": ("Grilled Carrot", "烤胡萝卜"),
    "grilled_mushroom": ("Grilled Mushroom", "烤蘑菇"),
}

RECIPES = {
    "grilled_beef": "minecraft:beef",
    "grilled_pork": "minecraft:porkchop",
    "grilled_chicken": "minecraft:chicken",
    "grilled_mutton": "minecraft:mutton",
    "grilled_cod": "minecraft:cod",
    "grilled_salmon": "minecraft:salmon",
    "grilled_carrot": "minecraft:carrot",
    "grilled_mushroom": "minecraft:brown_mushroom",
}


def png(path, pixels, w=16, h=16):
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


def draw(cooked):
    """Rounded food chunk with three dark grill bars across it."""
    px = [[(0, 0, 0, 0)] * 16 for _ in range(16)]

    def put(x, y, c):
        if 0 <= x < 16 and 0 <= y < 16:
            px[y][x] = (c[0], c[1], c[2], 255)

    # body: rounded blob centred on the icon
    for y in range(3, 13):
        for x in range(3, 13):
            dx, dy = x - 7.5, y - 7.5
            if dx * dx + dy * dy <= 20.0:
                put(x, y, cooked)

    # top-left highlight for volume
    for x, y in ((5, 5), (6, 5), (5, 6), (6, 6)):
        put(x, y, shade(cooked, 1.3))

    # three grill bars
    for x in range(4, 12):
        put(x, 6, shade(cooked, 0.55))
        put(x, 9, shade(cooked, 0.55))
    return px


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write("\n")


def main():
    for gid, (_raw, cooked) in GRILLED.items():
        png(os.path.join(ASSETS, "textures", "item", gid + ".png"), draw(cooked))
        write_json(
            os.path.join(ASSETS, "models", "item", gid + ".json"),
            {"parent": "minecraft:item/generated", "textures": {"layer0": "mekck:item/" + gid}},
        )

    for gid, raw in RECIPES.items():
        write_json(
            os.path.join(DATA, "recipes", "grilling", gid + ".json"),
            {
                "type": "mekck:grilling",
                "ingredient": {"item": raw},
                "result": {"item": "mekck:" + gid, "count": 1},
                "processingTime": 100,
            },
        )

    for locale, idx in (("en_us", 0), ("zh_cn", 1)):
        p = os.path.join(ASSETS, "lang", locale + ".json")
        with open(p, "r", encoding="utf-8") as f:
            d = json.load(f)
        for gid, pair in NAMES.items():
            d.setdefault("item.mekck." + gid, pair[idx])
        write_json(p, d)

    print("generated %d textures, %d models, %d recipes, 2 lang files"
          % (len(GRILLED), len(GRILLED), len(RECIPES)))


if __name__ == "__main__":
    main()

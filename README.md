# Mekanism: Central Kitchen (MekCK)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-green.svg)](https://www.minecraft.net/)
[![Forge](https://img.shields.io/badge/Forge-47.4.16-orange.svg)](https://files.minecraftforge.net/)

A Minecraft 1.20.1 / Forge kitchen-automation mod that rebuilds **Farmer's Delight** cooking
into a **Mekanism-style factory system** — and lets you burn the leftovers for power.

> **Personal Edition** — this repository hosts the personal edition of MekCK;
> the official edition has not been released yet.

> **中文说明见 [README.zh_CN.md](README.zh_CN.md)**

---

## Features

### 6 machine families × 12 factory tiers

Every family ships a single-block base machine plus **12 tiers** of factory
(**72 factory blocks in total**):

| Family | Base machine | Factory |
|---|---|---|
| Cutting | Universal Cutting Machine | `mekck:{tier}_cutting_factory` |
| Planting & Cutting | Planting & Cutting Station | `mekck:{tier}_planting_cutting_factory` |
| Cooking | Smart Cooking Pot | `mekck:{tier}_cooking_factory` |
| Skewering | Smart Skewering Machine | `mekck:{tier}_skewering_factory` |
| Grilling | Electric Grill | `mekck:{tier}_grill_factory` |
| Grinding | Electric Grinding Machine | `mekck:{tier}_grinding_factory` |

> **Ice factory — not released yet.** The 7th family is implemented and its
> base machine (急冻制冰机 / Ice Maker) works, but the 12 `mekck:{tier}_ice_factory`
> blocks are gated behind `UniversalCuttingMachine.ICE_FACTORY_ENABLED = false`
> and are **not registered at all** — no block, no item, no block entity, no menu.
> Their assets (blockstates, models, lang keys, recipes) are already in the repo.
> Flipping the flag back to `true` is *not* sufficient on its own: the 12 loot
> tables under `data/mekck/loot_tables/blocks/` still have to be authored, or
> breaking the blocks will drop nothing. See `docs/STATUS.md`.

**Tier chain**

```
Basic → Advanced → Elite → Ultimate → Absolute → Supreme → Cosmic → Infinite
      → Blaze → Crystal Matrix → Nebula → Singularity
```

Thread counts (input slots) climb from 3 up to 81; **Nebula** and **Singularity** consume no
energy, and **Singularity** needs no stacking upgrades — its base parallel *is* its limit.

### 5 standalone machines

- **Bioreactor** — 2×2×3 multiblock; food/organics → organic matter fluid, or piped-in
  meat soup / nutritional paste → FE. Up to 8,000 FE/t generated, 14,000 FE/t pushed out.
- **Central Kitchen** — install machines as modules and one block handles every recipe family,
  with automatic crafting-chain expansion and per-family filters.
- **Chocolate Cannon** — craft Ferrero Rocher, then fire them at your enemies.
- **Nut Roaster** — roast hazelnuts; the roasted ones double as ammunition.
- **Sandwich Assembler** — clone a hand-made sandwich or build one layer by layer.
  *(requires Some Assembly Required)*

### 14 linked machines

Single-tier machines for other mods' recipes: sushi maker, average slicer, rice ball maker,
dehydrator, fermenter, steamer, curd maker, winery, juicer, bakery oven, stove, blender,
cocktail shaker, tea brewer.

### Integration

- **Factory installers** — upgrade factories in place; the four top tiers use MekCK's own
  installers, and Avaritia's Delight knives work as installers too.
- **AE2** — network pull and ME terminal ordering (real crafting, products written back).
- **GuideME** — 20 in-game guide pages; press **G** while holding any machine.
- **JEI** — every recipe, catalyst and fuel conversion is browsable.
- **Auto-generated recipes** — plating recipes for every installed mod's feast blocks, and
  planting recipes derived from loot tables.
- **Temperature system** — heat-producing machines interoperate with Mekanism's thermal
  devices; the Ice Maker gets *faster* the colder it is.

## Requirements

| | |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.4.16 |
| Java | 17 |
| Required | [Mekanism](https://github.com/mekanism/Mekanism) ≥ 10.4, [Farmer's Delight](https://github.com/vectorwing/FarmersDelight) ≥ 1.2.7 |

Optional integrations are listed in [CREDITS.txt](CREDITS.txt).

## Building

The build needs two third-party jars that are **not** in version control
(`libs/` is git-ignored, since redistributing other mods' jars is not allowed).
Place them in `libs/` before the first build:

| File | Purpose |
|---|---|
| `FarmersDelight-1.20.1-1.2.7.jar` | compile-time API (raw, unremapped) |
| `appliedenergistics2-forge-15.4.10.jar` | compile-time API for the optional AE2 integration |

If either is missing, Gradle fails with the expected path and a download URL.

```bash
./gradlew build
```

The jar lands in `build/libs/mekck-<version>.jar`.

## Configuration

```
config/mekck/mekck-common.toml
```

Notable options:

- `[multithreaded]` / `[non_multithreaded]` — **base parallel** per tier. The maximum parallel
  is *not* configurable; it is derived as `base × 2^stackUpgrades` (up to ×64).
- `[planting] auto_generate_planting_recipes` — generate planting recipes on startup.
- `[plating] auto_generate_plating_recipes` — generate plating recipes on startup.

## Repository layout

```
src/main/java/cn/ism/mekck/     Java sources
  registry/                     Registration hub: one class per family (items,
                               machines, factories, fluids, effects, entities,
                               recipe types). MekCkRegistries.registerAll(bus)
                               touches all of them before any registry event —
                               see docs/STATUS.md for why that ordering matters.
  event/                        Server-side event subscriptions
src/main/resources/             assets, data, lang, in-game guide
  assets/mekck/mekckguide/      GuideME pages (Markdown)
  assets/mekck/textures/block/vendor/   textures redistributed under MIT (see THIRD-PARTY.md)
docs/                           design notes, architecture contracts, review reports
                                (index: docs/README.md; current status: docs/STATUS.md)
tools/                          asset generators and audit scripts (Blender, Python)
```

## License

MekCK is licensed under the **MIT License** — see [LICENSE](LICENSE).

Some textures are redistributed from Mekanism and Mekanism Extras (both MIT);
see [THIRD-PARTY.md](THIRD-PARTY.md) for details and full license texts.

---

*Not affiliated with Mojang, Mekanism, or Farmer's Delight.*

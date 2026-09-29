---
navigation:
  parent: index.md
  title: 工厂：15 种 × 4 档 = 60 台
  position: 1
item_ids:
- mekmm:basic_oxidizing_factory
- mekmm:advanced_oxidizing_factory
- mekmm:elite_oxidizing_factory
- mekmm:ultimate_oxidizing_factory
- mekmm:basic_dissolving_factory
- mekmm:advanced_dissolving_factory
- mekmm:elite_dissolving_factory
- mekmm:ultimate_dissolving_factory
- mekmm:basic_washing_factory
- mekmm:advanced_washing_factory
- mekmm:elite_washing_factory
- mekmm:ultimate_washing_factory
- mekmm:basic_crystallizing_factory
- mekmm:advanced_crystallizing_factory
- mekmm:elite_crystallizing_factory
- mekmm:ultimate_crystallizing_factory
- mekmm:basic_pressurised_reacting_factory
- mekmm:advanced_pressurised_reacting_factory
- mekmm:elite_pressurised_reacting_factory
- mekmm:ultimate_pressurised_reacting_factory
- mekmm:basic_centrifuging_factory
- mekmm:advanced_centrifuging_factory
- mekmm:elite_centrifuging_factory
- mekmm:ultimate_centrifuging_factory
- mekmm:basic_liquifying_factory
- mekmm:advanced_liquifying_factory
- mekmm:elite_liquifying_factory
- mekmm:ultimate_liquifying_factory
- mekmm:basic_pigment_extracting_factory
- mekmm:advanced_pigment_extracting_factory
- mekmm:elite_pigment_extracting_factory
- mekmm:ultimate_pigment_extracting_factory
- mekmm:basic_painting_factory
- mekmm:advanced_painting_factory
- mekmm:elite_painting_factory
- mekmm:ultimate_painting_factory
- mekmm:basic_recycling_factory
- mekmm:advanced_recycling_factory
- mekmm:elite_recycling_factory
- mekmm:ultimate_recycling_factory
- mekmm:basic_planting_factory
- mekmm:advanced_planting_factory
- mekmm:elite_planting_factory
- mekmm:ultimate_planting_factory
- mekmm:basic_stamping_factory
- mekmm:advanced_stamping_factory
- mekmm:elite_stamping_factory
- mekmm:ultimate_stamping_factory
- mekmm:basic_lathing_factory
- mekmm:advanced_lathing_factory
- mekmm:elite_lathing_factory
- mekmm:ultimate_lathing_factory
- mekmm:basic_rolling_mill_factory
- mekmm:advanced_rolling_mill_factory
- mekmm:elite_rolling_mill_factory
- mekmm:ultimate_rolling_mill_factory
- mekmm:basic_replicating_factory
- mekmm:advanced_replicating_factory
- mekmm:elite_replicating_factory
- mekmm:ultimate_replicating_factory
---

# 工厂：15 种 × 4 档 = 60 台

> 命名与档名以官方 `zh_cn.json` 为准，勿凭习惯改写：档名前缀 = **基础 / 高级 / 精英 / 终极**；机器名后缀 = **××工厂**。

## 一、15 种工厂一览

mekmm 的工厂都是**并行处理多份配方**的批量机器（同 Mekanism 本体工厂玩法）。同一"种类"下**四个档位共用配方类型、只有并行槽数 / 能耗 / 速度不同**。

| # | 工厂（英文键） | 中文官方名 | 关联单机 | 配方类型 |
|---:|---|---|---|---|
| 1 | `oxidizing` | 氧化工厂 | 本体 氧化机 | `mekanism:oxidizing` |
| 2 | `dissolving` | 溶解工厂 | 本体 溶解室 | `mekanism:dissolving` |
| 3 | `washing` | 清洗工厂 | 本体 洗涤机 | `mekanism:washing` |
| 4 | `crystallizing` | 结晶工厂 | 本体 结晶室 | `mekanism:crystallizing` |
| 5 | `pressurised_reacting` | 加压反应工厂 | 本体 加压反应室 | `mekanism:pressurized_reacting` |
| 6 | `centrifuging` | 离心工厂 | 本体 离心机 | `mekanism:centrifuging` |
| 7 | `liquifying` | 液化工厂 | 本体 熔炼炉（固→液）| `mekanism:metallurgic_infusing` 等 |
| 8 | `pigment_extracting` | 颜料提取工厂 | 本体 颜料提取器 | `mekanism:pigment_extracting` |
| 9 | `painting` | 上色工厂 | 本体 上色机 | `mekanism:painting` |
| 10 | `recycling` | 回收工厂 | mekmm `recycler` | `mekmm:recycling` |
| 11 | `planting` | 种植工厂 | mekmm `planting_station` | `mekmm:planting`（**物品 + 气体缓存**）|
| 12 | `stamping` | 压模工厂 | mekmm `cnc_stamper` | `mekmm:stamping`（**双物品 = 工件 + 模具**）|
| 13 | `lathing` | 车削工厂 | mekmm `cnc_lathe` | `mekmm:lathing`（复用本体 `ItemStackToItemStackRecipe`）|
| 14 | `rolling_mill` | 轧制工厂 | mekmm `cnc_rolling_mill` | `mekmm:rolling_mill`（复用本体 `ItemStackToItemStackRecipe`）|
| 15 | `replicating` | 复制工厂 | mekmm `replicator` | `mekmm:replicating`（消耗 UU 物质）|

> **前 9 种**是把本体已有的化学反应机器工厂化；**后 6 种**（回收 / 种植 / 压模 / 车削 / 轧制 / 复制）对应 mekmm 自己的新机器。

## 二、工厂逐级合成（不能跳级）

工厂配方用**自定义有序类型 `mekanism:mek_data`**，网格：

```
A A A       A = 合金 tag
C P C       C = 电路 tag
I I I       I = 主料 tag
            P = 上一级工厂本体
```

⇒ **不能跳档**：想造"终极氧化工厂"必须先有"精英氧化工厂"当 `P`。基础档的 `P` 是各自单机或本体对应机器（例如氧化工厂的 `P` = 本体 `mekanism:oxidizer`）。

## 三、⚠ 为什么只写 4 档？（本轮最大发现）

- `mekanism.common.tier.FactoryTier` **实际枚举常量只有 4 个**：`BASIC / ADVANCED / ELITE / ULTIMATE`（取自本机 `Mekanism-1.20.1-10.4.16.80.jar` 字节码）；
- `EnumUtils.FACTORY_TIERS = FactoryTier.values()`；
- mekmm 的 `AdvancedFactoryBlocks` / `MoreMachineBlocks` 按 `FACTORY_TIERS × 15 种` 循环注册 ⇒ **4 × 15 = 60 方块**，与 `data/mekmm/recipes/factory/` 60 条（basic/advanced/elite/ultimate 各 15）**完全吻合**。

**更高的 5 档**（`dense` 量子凝态 / `overclocked` 超频 / `quantum` 量子 / `multiversal` 超阈限 / `creative` 创造）**只是 jar 内 datagen 残留的模型与语言文件**（135 个 blockstates 全在），**注册侧没有任何来源**——引用它们的名字只出现在 `com/jerry/datagen/.../EMAdvancedFactoryRecipeProvider` 等数据生成器与 `MoreMachineHooks.EVOLVED_MEKANISM_MOD_ID` 条件挂钩里。**没装 Evolved Mekanism ⇒ 这些方块不存在**。

## 四、想继续升更高等级？

- **Evolved Mekanism** 联动路径：本 mod 的 mekmm 工厂可被 EM 联动升到"量子 / 量子凝态 / 超阈限 / 创造"，但**未装 EM 时不可见**，本指南不展开。
- **通用机械：扩展**（`mekanism_extras`）的**绝对 / 至尊 / 寰宇支配 / 悖论无限**四档工厂：
    - 配方只是把 `mekmm:ultimate_*_factory` 当 `P` 前置材料；**产物本身是 `mekanism_extras:*` 方块**（实测 60 条：`data/mekanism_extras/recipes/compat/mekmm/factory/{absolute,supreme,cosmic,infinite}/`）。
    - 请见《**通用机械：扩展**》指南 → [[meke:machines/factories.md|工厂：24 种 × 4 新等级]]。本指南**不重复列**这些 `mekanism_extras:` 方块。

## 五、常见组合建议

- **矿处理**：`centrifuging` + `washing` + `crystallizing` + `dissolving` + `oxidizing` 五件套，配本体 破碎 / 研磨 工厂，可摊平 5× 富集链。
- **化学品产能**：`pressurised_reacting`（`锂 + 硫酸 → 硫酸锂`等）+ `liquifying`（金属固→液）是接本体核燃料链的前置。
- **颜料链**：`pigment_extracting` → `painting`，配本体的染料/涂料相关配方。
- **UU 复制**：`replicating` 工厂 = 批量 UU → 物品（见 [[machines/replicator.md|复制机]]）。
- **种植**：`planting` 工厂一次并行多个 `mekmm:planting` 配方（47 条），需 **营养液 / 营养糊剂** 气体缓存（见 [[machines/planting.md|种植]]）。

> 具体某一台工厂能做什么、耗多少电、每 tick 处理多少份，请以 **JEI** 内该方块页为准（本指南**不写工作台合成表**，玩家看 JEI 即可）。

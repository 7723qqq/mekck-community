---
navigation:
  parent: index.md
  title: 复制机（UU 物质）与环境气体收集器
  position: 5
item_ids:
- mekmm:replicator
- mekmm:fluid_replicator
- mekmm:ambient_gas_collector
- mekmm:uu_matter
- mekmm:empty_crystal
---

# 复制机（UU 物质）与环境气体收集器

> 官方描述：
> * `replicator` = **"一台违反物理学定律的机器，它可以利用某种物质复制物品"**；
> * `fluid_replicator` = 同上，但**复制液体**；
> * `ambient_gas_collector` = **"一台抽取大气中神秘物质的机器"**。
>
> ⚠️ **本 mod 语言文件里还有一条"化学品复制机 `chemical_replicator`"，但它没有被注册**（全 jar 字节码零命中、无配方、无战利品），**游戏内不存在**，故本指南不列、也无法给它做 G 键跳页。

## 一、机器一览

| 类型 | id | 官方名 | 耗能（J/t）| 内部储能（J）| 作用 |
|---|---|---|---:|---:|---|
| 方块 | `mekmm:replicator` | **复制机** | 102,400（=40,960 FE/t）| 102,400,000 | 耗 UU 物质气体 → **复制物品** |
| 方块 | `mekmm:fluid_replicator` | **流体复制机** | 102,400 | 102,400,000 | 耗 UU 物质气体 → **复制液体** |
| 方块 | `mekmm:ambient_gas_collector` | **环境气体收集器** | 100（=40 FE/t）| 40,000 | 抽取大气 → **不稳定维度气体** |
| 工厂 | `mekmm:*_replicating_factory`（4 档）| **××复制工厂** | — | — | 批量复制物品（`P` = 复制机）|
| 物品 | `mekmm:empty_crystal` | **空晶体** | — | — | UU 物质链的原料 |
| 物品 | `mekmm:uu_matter` | **UU物质**（无空格）| — | — | 可转为 UU 物质气体 |

> 数据取自 `MoreMachineUsageConfig` / `MoreMachineStorageConfig`；以 **JEI / 方块 tooltip** 为准。

## 二、UU 物质从哪来（两条已核实配方）

复制机烧的是 **UU 物质气体**，其生产链（jar 内实测两条 JSON）：

1. **空晶体 → UU物质（物品）**：走本体 `mekanism:nucleosynthesizing`
   —— 输入 `mekmm:empty_crystal` × 64 + `mekanism:antimatter` 气体 × 2，产出 `mekmm:uu_matter`（物品）；
2. **UU物质（物品）→ UU物质（气体）**：走本体 `mekanism:gas_conversion`
   —— 1 个 `mekmm:uu_matter` 物品 → 500 mB 的 `mekmm:uu_matter` **气体**，供复制机消耗。

> 也就是说：**反物质 →（核合成）→ 空晶体变 UU →（气体转换）→ UU 气体 → 复制机**。具体每份复制消耗多少 UU 气体，看 **JEI** 的复制机配方页。

## 三、复制机 / 流体复制机

- **复制机 `replicator`**：选一个**物品**目标，持续消耗 UU 气体"无中生有"复制它；
- **流体复制机 `fluid_replicator`**：同理，但目标是**流体**；
- 两者都是**高耗能**机器（102,400 J/t），务必配足供电与 UU 气体供给；
- 批量复制请上 **`replicating` 工厂**（4 档，`P` = `mekmm:replicator`，见 [[machines/factories.md|工厂]]）。

## 四、环境气体收集器与"不稳定维度气体"

- `ambient_gas_collector` **从大气中抽取** `mekmm:unstable_dimensional_gas`（不稳定维度气体），
  默认收集速率 **1 mB/t**（`MoreMachineGeneralConfig.gasCollectAmount`，可配 1~1000）；
- ⚠️ **机器上方不能有阻挡物**：官方 tooltip `is_blocking` = "机器上方有阻挡物"、`no_blocking` = "机器上方无阻挡物"；JEI 提示也写明"**注意不要在机器上方放置方块**"，被挡住就不收集；
- 不稳定维度气体的**具体用途**以 **JEI** 对该气体的引用为准（本指南不臆造消费点）。

## 五、坑与提醒

- **别找"化学品复制机"**：它在语言文件里有名字，但**没注册、游戏内拿不到也用不了**（见开头 ⚠️）；
- **UU 是硬成本**：复制机不生产、只"复制"，前置是反物质 → UU 的完整链，前期不建议上复制机；
- **供能要够**：复制机 / 流体复制机单台 40,960 FE/t，是 mekmm 基础机器里最耗电的。

> 每台机器的槽数、每 tick 处理量、每条复制配方的 UU 消耗，均以 **JEI** 为准（本指南不写工作台合成表）。

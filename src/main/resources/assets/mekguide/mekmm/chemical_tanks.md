---
navigation:
  parent: index.md
  title: 化学品储罐（中型 / 大型，各 4 档）
  position: 8
item_ids:
- mekmm:basic_mid_chemical_tank
- mekmm:advanced_mid_chemical_tank
- mekmm:elite_mid_chemical_tank
- mekmm:ultimate_mid_chemical_tank
- mekmm:basic_max_chemical_tank
- mekmm:advanced_max_chemical_tank
- mekmm:elite_max_chemical_tank
- mekmm:ultimate_max_chemical_tank
---

# 化学品储罐（中型 / 大型，各 4 档）

> mekmm 在本体储罐之外新增**两种尺寸**的化学品储罐：**中型（`_mid_`）**与**大型（`_max_`）**，各 4 档 = **8 个方块**。
> 命名取官方 `zh_cn.json`：**基础 / 高级 / 精英 / 终极 + 中型 / 大型化学品储罐**。

## 一、容量表（`MidChemicalTankTier` / `MaxChemicalTankTier` 字节码，单位 mB）

| 档 | 中型 storage | 中型 output | 大型 storage | 大型 output |
|---|---:|---:|---:|---:|
| 基础 basic | 160,000 | 2,500 | 256,000 | 4,000 |
| 高级 advanced | 640,000 | 40,000 | 1,024,000 | 64,000 |
| 精英 elite | 2,560,000 | 320,000 | 4,096,000 | 512,000 |
| 终极 ultimate | 20,480,000 | 1,280,000 | 32,768,000 | 2,048,000 |

- **storage** = 最大缓冲容量（mB）；**output** = 每 tick 最大输出速率（mB/t）；
- 数值是**枚举 `baseStorage` / `baseOutput` 默认值**，运行时经 `CachedLongValue.getOrDefault()` **可被配置覆盖** ⇒ 以 **JEI / 方块页**为准。

## 二、⚠️ 只有 4 档，没有更高

- `MidChemicalTankTier` 与 `MaxChemicalTankTier` **两个枚举都只有 4 个常量**（BASIC / ADVANCED / ELITE / ULTIMATE）；
- ⇒ 与工厂不同，储罐**连"更高 EM 档"的残留都没有**，游戏内**最多就是终极（ultimate）档**；
- 终极大型 = **32,768,000 mB**，是本页容量天花板。

## 三、方块 id 一览

| 尺寸 | 基础 | 高级 | 精英 | 终极 |
|---|---|---|---|---|
| 中型 `_mid_` | `basic_mid_chemical_tank` | `advanced_mid_chemical_tank` | `elite_mid_chemical_tank` | `ultimate_mid_chemical_tank` |
| 大型 `_max_` | `basic_max_chemical_tank` | `advanced_max_chemical_tank` | `elite_max_chemical_tank` | `ultimate_max_chemical_tank` |

> 注意：本 mod 的 `large_*`（大型多方块）与前缀 `_max_`（大型储罐）是两回事——`_max_` 指**储罐尺寸档**，见 [[large_machines.md|大型多方块机器]]。

## 四、常见用途

- **中型 / 大型储罐** = 大容量化学品 / 气体缓冲，配合工厂、复制机、燃气发电机等耗气 / 产气设备；
- **`ultimate_max_chemical_tank`（终极大型储罐）是大型燃气发电机配方的组件之一**（×4，见 [[large_machines.md|大型多方块机器]] §二）。

> 具体每台储罐能否存某种气体 / 流体、实际输出速率上限，以 **JEI** 内方块页为准（本指南不写工作台合成表）。

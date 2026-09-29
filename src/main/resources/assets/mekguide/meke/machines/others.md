---
navigation:
  parent: index.md
  title: 进阶电动泵与其它机器
  position: 6
item_ids:
- mekanism_extras:advance_electric_pump
- mekanism_extras:expand_radioactive_waste_barrel
- mekanism_extras:reinforced_induction_casing
- mekanism_extras:reinforced_induction_port
- mekanism_extras:polonium-208
- mekanism_extras:polonium_containing_solution
---

# 进阶电动泵与其它机器

> 数据来源：`ExtraUsageConfig` / `ExtraStorageConfig` / `ExtraConfig` 默认值（字节码常量）。

## 一、进阶电动泵

| 项 | 值 |
|---|---:|
| 耗能 | **1000 J/t = 400 FE/t** |
| 内部储能 | **400 000 J = 160 000 FE** |
| 重水抽取量 `pumpHeavyWaterAmount` | 1000（可配置，范围 1~1000） |

> 相比本体的电动泵（100 J/t / 40 FE/t），**耗能高 10 倍**，换的是**重水**抽取能力
> —— 重水是本体核燃料链的原料。重水量本身可以在配置里调。

## 二、强化感应矩阵部件

| 方块 | 用途 |
|---|---|
| `reinforced_induction_casing` | **强化**感应矩阵外壳 |
| `reinforced_induction_port` | **强化**感应矩阵端口 |

配合本模组的**绝对 / 至尊 / 寰宇支配 / 悖论悖论无限感应元件与供应器**，把感应矩阵推到新等级
（容量与输出见 [[storage.md|储能与 QIO 驱动]]）。

## 三、扩展放射性废料桶

| 项 | 值 |
|---|---:|
| 最大气体量 `radioactiveWasteBarrelMaxGas` | 2 048 000 |
| 处理节奏 `radioactiveWasteBarrelProcessTicks` | 5 tick |
| 每次衰减量 `radioactiveWasteBarrelDecayAmount` | 4 |

> 本体的放射性废料桶容量有限，这个扩展版把上限拉到 **2 048 000**，适合核废料量大的基地。

## 四、钋与含钋溶液

| 名称 | 形式 | 说明 |
|---|---|---|
| **钋-208** `polonium-208` | 流体（有桶） | 高放射性材料 |
| **含钋溶液** `polonium_containing_solution` | 流体（有桶） | 中间产物 |

二者与**硅岩燃料链**相关（见 [[machines/reactor.md|硅岩反应堆]]）。

## 五、配置项一览（`ExtraConfig`）

| 键 | 默认值 | 说明 |
|---|---:|---|
| `transmitterAlloyUpgrade` | 1 | 管道合金升级开关 |
| `allowRadioactiveChemicalInChemicalTanks` | 1 | 允许放射性化学品进储罐 |
| `enableRegeneration` | 0 | 矿物再生（`ExtraWorldConfig`） |
| `userGenVersion` | 0 | 矿物生成版本 |

> ⚠️ 这几个是**服务端配置**，改动可能影响存档兼容性，改前请备份。

另见 [[materials/naquadah.md|硅岩全链]]、[[machines/reactor.md|硅岩反应堆]]。

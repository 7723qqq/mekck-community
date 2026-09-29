---
navigation:
  parent: index.md
  title: 大型多方块机器（9 台）
  position: 7
item_ids:
- mekmm:large_rotary_condensentrator
- mekmm:large_chemical_infuser
- mekmm:large_electrolytic_separator
- mekmm:large_solar_neutron_activator
- mekmm:large_antiprotonic_nucleosynthesizer
- mekmm:large_pigment_mixer
- mekmm:large_heat_generator
- mekmm:large_gas_burning_generator
- mekmm:large_wind_generator
- mekmm:advanced_electrolysis_core
---

# 大型多方块机器（9 台）

> mekmm 把本体若干机器做成**大型多方块**版（`LargeMachineBlocks` 6 台 + `LargeGeneratorsBlocks` 3 台 = 9）。
> 命名一律取官方 `zh_cn.json`；数值取 `MoreMachineUsageConfig` / `MoreMachineStorageConfig` / `MoreMachineGeneratorsConfig` 字节码，**可被配置覆盖，以 JEI / 方块页为准**。

## 一、6 台大型处理机器（`LargeMachineBlocks`）

| id | 官方名 | 耗能（J/t）| 内部储能（J）|
|---|---|---:|---:|
| `mekmm:large_rotary_condensentrator` | 大型回旋式气液转换机 | 50 | 4,096,000 |
| `mekmm:large_chemical_infuser` | 大型化学灌注机 | 100 | 16,384,000 |
| `mekmm:large_pigment_mixer` | 大型颜料混合器 | 100 | 16,384,000 |
| `mekmm:large_electrolytic_separator` | 大型电解分离机 | —（无 Usage 键）| 32,768,000 |
| `mekmm:large_solar_neutron_activator` | 大型太阳能中子活化器 | —（无 Usage 键）| —（储能不来自该 config）|
| `mekmm:large_antiprotonic_nucleosynthesizer` | 大型反质子核合成器 | 500,000 | 64,000,000,000 |

> 前 5 台是本体化学 / 电解 / 颜料 / 中子链的"大型化"；反质子核合成器是顶级 UU / 物质合成端（超耗能）。

## 二、3 台大型发电机（`LargeGeneratorsBlocks`）

⚠️ **依赖 Mekanism Generators 模块**（配方加载条件 `forge:mod_loaded: mekanismgenerators`），未装 Generators 时无相关配方。

| id | 官方名 | 产能 / 储能要点 |
|---|---|---|
| `mekmm:large_heat_generator` | 大型热力发电机 | 基础 1000 J/t（=400 FE/t）；每面相邻岩浆 +350、下界/超热维度 +750；流体罐 240,000 mB；储能 256,600,000 J |
| `mekmm:large_gas_burning_generator` | 大型燃气发电机 | **无固定产能键**：= 所通气体的 `energyPerTick`，`maxOutput = 该值 × 2`；气罐 180,000 mB；储能 = `FROM_H2 × 20,480,000`（默认 10 J/t ⇒ 204,800,000 J）。**配方需 `mekmm:ultimate_max_chemical_tank`×4 + `advanced_electrolysis_core`×2 + `mekanism:robit` + 钢块×2** |
| `mekmm:large_wind_generator` | 大型风力发电机 | 产能区间 2,250,000 ~ 3,750,000 J/t；储能 256,600,000 J；官方提示 `same_block_nearby` = **"周围有相同的机器"**（相邻布局影响产出）|

## 三、高级电解核心 `mekmm:advanced_electrolysis_core`

- 物品，官方名 **高级电解核心**；
- 是**大型燃气发电机**配方的关键组件（上表 ×2）；具体来源以 **JEI** 为准。

## 四、成型方式：放置即自动成型（**多方块机器**，非搭建型）

这 9 台属于**多方块机器**——放一个**核心方块**就会自动生成**绑定方块**成型，**不需要玩家搭模板、也没有成型扫描**（字节码证据：tile 类 `implements mekanism.common.tile.interfaces.IBoundingBlock`，全 jar 未引用 `FormationProtocol`/`MultiblockData`/`IMultiblock`）。
⇒ 与**多方块结构**（需搭建 + 成型扫描，如传统大热交换、裂变堆、化学品储罐阵列）不同；同类绑定型还有 Mekanism 的**数字采矿机**、本模组的**生物反应堆**。
具体外观（模型比单方块高，约跨 2 格）以游戏内摆放观感为准；玩法上**放一块即用**，无“搭建不成型”问题。

## 五、坑与提醒

- **大型机器是“绑定型”不是“搭建型”**：放置核心方块即自动用绑定方块成型，放一块就能用，**无需按模板搭结构**（区别于需成型扫描的多方块结构）；
- **三台发电机看 Generators**：没装 Mekanism Generators 时它们基本不可用 / 无配方；
- **燃气发电机产能不写死**：完全取决于通入的燃料气体（氢 / 乙烯等），想稳定高产能要先解决气体供给。

> 每台大型机器的成型结构、产出速率、能量与气体缓冲，最终以 **JEI + 游戏内实测**为准。

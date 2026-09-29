---
navigation:
  parent: index.md
  title: 储能与 QIO 驱动
  position: 7
item_ids:
- mekanism_extras:absolute_bin
- mekanism_extras:absolute_energy_cube
- mekanism_extras:absolute_fluid_tank
- mekanism_extras:absolute_chemical_tank
- mekanism_extras:absolute_induction_cell
- mekanism_extras:absolute_induction_provider
- mekanism_extras:supreme_bin
- mekanism_extras:supreme_energy_cube
- mekanism_extras:supreme_fluid_tank
- mekanism_extras:supreme_chemical_tank
- mekanism_extras:supreme_induction_cell
- mekanism_extras:supreme_induction_provider
- mekanism_extras:cosmic_bin
- mekanism_extras:cosmic_energy_cube
- mekanism_extras:cosmic_fluid_tank
- mekanism_extras:cosmic_chemical_tank
- mekanism_extras:cosmic_induction_cell
- mekanism_extras:cosmic_induction_provider
- mekanism_extras:infinite_bin
- mekanism_extras:infinite_energy_cube
- mekanism_extras:infinite_fluid_tank
- mekanism_extras:infinite_chemical_tank
- mekanism_extras:infinite_induction_cell
- mekanism_extras:infinite_induction_provider
- mekanism_extras:qio_drive_collapse
- mekanism_extras:qio_drive_gamma
- mekanism_extras:qio_drive_black_hole
- mekanism_extras:qio_drive_singularity
---

# 储能与 QIO 驱动

> 数据来源：`ECTier` / `BTier` / `FTTier` / `CTTier` / `ICTier` / `IPTier` / `ExtraQIODriverTier` 字节码常量。
> 能量换算：**1 FE = 2.5 J**。

## 一、能量立方

| 等级 | 容量（J） | 容量（FE） | 输出（J/t） | 输出（FE/t） |
|---|---:|---:|---:|---:|
| 绝对 | 1 024 000 000 | 409 600 000 | 1 024 000 | 409 600 |
| 至尊 | 4 096 000 000 | 1 638 400 000 | 4 096 000 | 1 638 400 |
| 寰宇支配 | 16 384 000 000 | 6 553 600 000 | 16 384 000 | 6 553 600 |
| 悖论无限 | 65 536 000 000 | 26 214 400 000 | 65 536 000 | 26 214 400 |

> 本体终极能量立方是 256 000 000 J ⇒ **悖论无限级是它的 256 倍**。

## 二、箱柜（同种物品上限）

| 等级 | 容量 |
|---|---:|
| 绝对 | 1 048 576 |
| 至尊 | 8 388 608 |
| 寰宇支配 | 134 217 728 |
| 悖论无限 | **2 147 483 647**（int 上限） |

## 三、液体储罐（mB）

| 等级 | 容量 | 输出 |
|---|---:|---:|
| 绝对 | 4 096 000 | 2 048 000 |
| 至尊 | 32 768 000 | 16 384 000 |
| 寰宇支配 | 262 144 000 | 131 072 000 |
| 悖论无限 | 2 097 152 000 | 1 048 576 000 |

## 四、化学品储罐（mB）

| 等级 | 容量 | 输出 |
|---|---:|---:|
| 绝对 | 131 072 000 | 65 536 000 |
| 至尊 | 4 194 304 000 | 2 097 150 000 |
| 寰宇支配 | 268 435 456 000 | 134 217 728 000 |
| 悖论无限 | 34 359 738 368 000 | 17 179 869 184 000 |

> 化学品储罐**默认允许存放放射性化学品**（配置 `allowRadioactiveChemicalInChemicalTanks` = 1）。

## 五、感应矩阵（终极储能）

| 等级 | 感应元件容量（J） | 感应供应器输出（J/t） |
|---|---:|---:|
| 绝对 | 32 768 000 000 000 | 1 048 576 000 |
| 至尊 | 262 144 000 000 000 | 8 388 608 000 |
| 寰宇支配 | 2 097 152 000 000 000 | 67 108 864 000 |
| 悖论无限 | **9 223 372 036 854 775 807**（long 上限） | 536 870 912 000 |

另有 **强化感应外壳 / 端口**（`reinforced_induction_casing` / `reinforced_induction_port`），
配合上述电池与供应器搭建新等级感应矩阵，见 [[machines/others.md|进阶电动泵与其它机器]]。

## 六、QIO 驱动

| 等级 | 物品总量 | 物品种类 |
|---|---:|---:|
| **坍缩** COLLAPSE | 512 000 000 000 | 131 072 |
| **伽马** GAMMA | 65 536 000 000 000 | 2 097 152 |
| **黑洞** BLACK_HOLE | 33 554 432 000 000 000 | 33 554 432 |
| **奇点** SINGULARITY | 9 223 372 036 854 775 807（long 上限） | 2 147 483 647 |

> 本体的最高级 QIO 驱动是「超巨质量」（16 000 000 000 件 / 8 192 种），
> **坍缩级就已经是它的 32 倍**。

## 七、怎么选

1. **日常缓冲**用能量立方（悖论无限级 26 214 400 FE/t 输出，够绝大多数产线）；
2. **总仓库**用感应矩阵（悖论无限级电池 = long 上限，事实上无限）；
3. **物品海量存储**用 QIO（奇点级 2 147 483 647 种，也是 int 上限）；
4. 新等级方块**都要用工厂安装器逐级升**（见 [[tiers.md|八级体系与安装器]]），不能跳级。

另见 [[logistics.md|管道、线缆与物流]]。

---
navigation:
  parent: machines/overview.md
  title: 电力研磨机与研磨工厂
  position: 7
  icon: mekck:electric_grinding_machine
categories:
- machines
item_ids:
- mekck:electric_grinding_machine
- mekck:basic_grinding_factory
- mekck:advanced_grinding_factory
- mekck:elite_grinding_factory
- mekck:ultimate_grinding_factory
- mekck:absolute_grinding_factory
- mekck:supreme_grinding_factory
- mekck:cosmic_grinding_factory
- mekck:infinite_grinding_factory
- mekck:blaze_grinding_factory
- mekck:crystal_matrix_grinding_factory
- mekck:nebula_grinding_factory
- mekck:singularity_grinding_factory
---
# 电力研磨机与研磨工厂

## 电力研磨机（mekck:electric_grinding_machine）

* 自动执行森罗物语的石磨研磨配方，按概率随机产出（与原版石磨一致）
* 支持烘焙坊筛粉（flour_sieve）与沉浸农艺绞碎（mincer）配方
* 随机产出按「最坏情况全命中」判定空间，产物自动进入输出槽
* 能耗 20 FE/t、容量 100,000 FE、音效沿用通用机械粉碎机

## 可处理的配方类型

| 来源模组 | 配方类型 | 说明 |
|---|---|---|
| 森罗物语：厨房 | `kaleidoscope_cookery:millstone` | 石磨研磨，**按概率随机产出** |
| 烘焙坊 | `bakeries:flour_sieve` | 筛粉，必出 |
| 沉浸农艺 | `farm_and_charm:mincer` | 绞碎 |

## 概率产出的处理方式

研磨配方多为按概率掉落。机器在**判定输出空间时按「最坏情况全部命中」预留**，实际产出时再对每个条目各掷一次随机。

* 好处：空间足够时**不会因为随机多掉落而卡住**
* 注意：界面显示的产物可能**多于**实际产出

## 研磨工厂（mekck:{等级}_grinding_factory）

* 电力研磨机的**多线程**版本，每个输入槽独立并行处理、自动整理
* 槽位：输入 = 输出 = 线程数；另有速度 / 能量 / 堆叠（绝对 ~ 星云）/ 创造升级与能源槽
* 音效沿用通用机械粉碎机

## 等级链

基础 → 高级 → 精英 → 终极 → 绝对 → 至尊 → 寰宇支配 → 悖论无限 → 烈焰炽焱 → 晶钛矩阵 → 星云塑造 → 奇点创世

## 工厂效果

* 升级方式：手持**对应等级的工厂安装器**右键（高工厂安装器可替代低等级；无尽乐事对应等级的刀同样可用），或用**无尽升级组件**（视为奇点创世级安装器）
* **多线程**：线程数 = 输入槽数量，由等级决定：基础 3 → 高级 5 → 精英 7 → 终极 9 → 绝对 11 → 至尊 13 → 寰宇支配 15 → 悖论无限 17 → **烈焰炽焱 25 → 晶钛矩阵 36 → 星云塑造 49 → 奇点创世 81**
* **并行数**：每个输入槽每次操作处理的物品数，靠**堆叠升级**提升；最大并行 = 基础并行 × 2^堆叠升级数
* 速度/能量升级上限：基础 ~ 悖论无限 8、**烈焰炽焱 12、晶钛矩阵 16、星云塑造 20、奇点创世 32**
* 堆叠升级上限 6（绝对 ~ 星云塑造）；**奇点创世不支持堆叠升级**——它的基础并行即为极限并行
* **只有星云塑造与奇点创世免能耗**；其余等级耗电，实际耗电随同时运行的输入槽数增加
* 升级时数据（物品、能量、进度、侧边配置、红石模式、流体）经 NBT 迁移，不产生掉落

---
navigation:
  parent: machines/overview.md
  title: 通用切菜机与切菜工厂
  position: 2
  icon: mekck:universal_cutting_machine
categories:
- machines
item_ids:
- mekck:universal_cutting_machine
- mekck:basic_cutting_factory
- mekck:advanced_cutting_factory
- mekck:elite_cutting_factory
- mekck:ultimate_cutting_factory
- mekck:absolute_cutting_factory
- mekck:supreme_cutting_factory
- mekck:cosmic_cutting_factory
- mekck:infinite_cutting_factory
- mekck:blaze_cutting_factory
- mekck:crystal_matrix_cutting_factory
- mekck:nebula_cutting_factory
- mekck:singularity_cutting_factory
---
# 通用切菜机与切菜工厂

## 通用切菜机（mekck:universal_cutting_machine）

* 自动执行农夫乐事的切割配方（含刀具、砧板类配方）
* 输入 → 输出，多产物按概率产出，产物自动进入输出槽
* 200 tick / 20 FE/t，能量容量 100,000 FE

## 切菜工厂（mekck:{等级}_cutting_factory）

* 基础机器的多线程版本，每个输入槽独立并行处理
* 能耗 20 FE/t × 当前运行的输入槽数 × 并行倍率（速度/能量升级会进一步改变）；支持速度/能量/创造升级、红石控制、侧面配置

## 等级链

基础 → 高级 → 精英 → 终极 → 绝对 → 至尊 → 寰宇支配 → 悖论无限 → 烈焰炽焱 → 晶钛矩阵 → 星云塑造 → 奇点创世

## 工厂效果

* 升级方式：**不接受工厂安装器**——每一级都只能**逐级合成**（Mekanism 的 4 种、扩展的 4 种、MekCK 自有的 3 种安装器与无尽乐事的刀都不行）；**每级配方查 JEI**
  * **无尽升级组件**仍可用于悖论无限及以下
* **多线程**：线程数 = 输入槽数量，由等级决定：基础 3 → 高级 5 → 精英 7 → 终极 9 → 绝对 11 → 至尊 13 → 寰宇支配 15 → 悖论无限 17 → **烈焰炽焱 25 → 晶钛矩阵 36 → 星云塑造 49 → 奇点创世 81**
* **并行数**：每个输入槽每次操作处理的物品数，靠**堆叠升级**提升；最大并行 = 基础并行 × 2^堆叠升级数
* 速度/能量升级上限：基础 ~ 悖论无限 8、**烈焰炽焱 12、晶钛矩阵 16、星云塑造 20、奇点创世 32**
* 堆叠升级上限 6（绝对 ~ 星云塑造）；**奇点创世不支持堆叠升级**——它的基础并行即为极限并行
* **只有星云塑造与奇点创世免能耗**；其余等级耗电，实际耗电随同时运行的输入槽数增加
* 升级时数据（物品、能量、进度、侧边配置、红石模式、流体）经 NBT 迁移，不产生掉落

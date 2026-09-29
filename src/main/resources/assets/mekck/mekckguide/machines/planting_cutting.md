---
navigation:
  parent: machines/overview.md
  title: 种植切配站与种植切配工厂
  position: 3
  icon: mekck:planting_cutting_station
categories:
- machines
item_ids:
- mekck:planting_cutting_station
- mekck:basic_planting_cutting_factory
- mekck:advanced_planting_cutting_factory
- mekck:elite_planting_cutting_factory
- mekck:ultimate_planting_cutting_factory
- mekck:absolute_planting_cutting_factory
- mekck:supreme_planting_cutting_factory
- mekck:cosmic_planting_cutting_factory
- mekck:infinite_planting_cutting_factory
- mekck:blaze_planting_cutting_factory
- mekck:crystal_matrix_planting_cutting_factory
- mekck:nebula_planting_cutting_factory
- mekck:singularity_planting_cutting_factory
---
# 种植切配站与种植切配工厂

## 种植切配站（mekck:planting_cutting_station）

* 1×2×1 多方块：种子作催化剂（不消耗）+ 营养液培育，自动种植并切割产物，概率产出副产物
* 配方自动生成（优先级：已有种植配方 > BotanyPots 配方转换 > 战利品表生成），JEI 可查
* 能耗 20 FE/t、容量 100,000 FE、单次 200 tick
* 点击主块或上方绑定块均可打开 GUI

## 种植切配工厂（mekck:{等级}_planting_cutting_factory）

* 种植切配站的多线程版本，升级时同步迁移上方绑定块
* **烈焰炽焱之前**的等级可安装气体升级（营养液消耗 −90%）；**烈焰炽焱 / 晶钛矩阵 / 星云塑造 / 奇点创世已内置 90% / 95% / 99% / 100%** 的营养液消耗减免，无需也不能安装气体升级（鼠标悬停可查看具体数值）

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

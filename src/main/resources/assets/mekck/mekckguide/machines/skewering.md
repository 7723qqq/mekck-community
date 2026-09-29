---
navigation:
  parent: machines/overview.md
  title: 智能穿串机与穿串工厂
  position: 5
  icon: mekck:smart_skewering_machine
categories:
- machines
item_ids:
- mekck:smart_skewering_machine
- mekck:basic_skewering_factory
- mekck:advanced_skewering_factory
- mekck:elite_skewering_factory
- mekck:ultimate_skewering_factory
- mekck:absolute_skewering_factory
- mekck:supreme_skewering_factory
- mekck:cosmic_skewering_factory
- mekck:infinite_skewering_factory
- mekck:blaze_skewering_factory
- mekck:crystal_matrix_skewering_factory
- mekck:nebula_skewering_factory
- mekck:singularity_skewering_factory
---
# 智能穿串机与穿串工厂

## 智能穿串机（mekck:smart_skewering_machine）

* 自动执行烧烤乐事（Barbeque's Delight）的穿串配方，材料任意槽位均可合成（位置无关匹配）
* 放入材料并供能即可自动穿串；装 AE2 时可从 ME 网络下单批量制作
* 能耗 20 FE/t、容量 100,000 FE

## 穿串工厂（mekck:{等级}_skewering_factory）

* 智能穿串机的聚合版本，**单线程**——1 个输入槽一次处理多份，而非多线程并行
* **位置无关匹配**：材料放哪个输入槽位都能合成，不要求顺序
* 槽位：输入 / 输出 / 速度 / 能量 / 堆叠（绝对 ~ 星云）/ 创造升级 / 能源
* 可在 GUI 内**自选组合下单**（最多 4 种材料）连续产出；装 AE2 后可直接从 ME 网络下单

## 等级链

基础 → 高级 → 精英 → 终极 → 绝对 → 至尊 → 寰宇支配 → 悖论无限 → 烈焰炽焱 → 晶钛矩阵 → 星云塑造 → 奇点创世

## 工厂效果

* 升级方式：手持**对应等级的工厂安装器**右键（高工厂安装器可替代低等级；无尽乐事对应等级的刀同样可用），或用**无尽升级组件**（视为奇点创世级安装器）
* **单线程（非多线程）**：固定 **1 个输入槽**，每次操作**聚合处理多份**材料，而不是多线程并行
* **并行数**（一次处理的份数）随等级提升：基础 1 → 高级 3 → 精英 5 → 终极 7 → 绝对 9 → 至尊 11 → 寰宇支配 13 → 悖论无限 15 → **烈焰炽焱 30 → 晶钛矩阵 60 → 星云塑造 120 → 奇点创世 21 亿**
* **并行数**：每个输入槽每次操作处理的物品数，靠**堆叠升级**提升；最大并行 = 基础并行 × 2^堆叠升级数
* 速度/能量升级上限：基础 ~ 悖论无限 8、**烈焰炽焱 12、晶钛矩阵 16、星云塑造 20、奇点创世 32**
* 堆叠升级上限 6（绝对 ~ 星云塑造）；**奇点创世不支持堆叠升级**——它的基础并行即为极限并行
* **只有星云塑造与奇点创世免能耗**；其余等级耗电，实际耗电随同时运行的输入槽数增加
* 升级时数据（物品、能量、进度、侧边配置、红石模式、流体）经 NBT 迁移，不产生掉落

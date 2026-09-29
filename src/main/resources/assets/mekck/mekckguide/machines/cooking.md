---
navigation:
  parent: machines/overview.md
  title: 智能厨锅与烹饪工厂
  position: 4
  icon: mekck:smart_cooking_pot
categories:
- machines
item_ids:
- mekck:smart_cooking_pot
- mekck:basic_cooking_factory
- mekck:advanced_cooking_factory
- mekck:elite_cooking_factory
- mekck:ultimate_cooking_factory
- mekck:absolute_cooking_factory
- mekck:supreme_cooking_factory
- mekck:cosmic_cooking_factory
- mekck:infinite_cooking_factory
- mekck:blaze_cooking_factory
- mekck:crystal_matrix_cooking_factory
- mekck:nebula_cooking_factory
- mekck:singularity_cooking_factory
---
# 智能厨锅与烹饪工厂

## 智能厨锅（mekck:smart_cooking_pot）

* 自动执行农夫乐事烹饪/熬汤配方，装森罗物语时还支持炒锅等耗油锅类配方，装沉浸农艺时支持厨锅配方
* 食材放入 3×2 输入网格并供能；支持多流体（水/奶/其它各占一格，水桶奶瓶自动取用并退回空容器）
* 内置下单系统可按 1/16/32/64/自定义批量连续烹饪；装 AE2 时可直接从 ME 网络下单
* 能量容量 100,000 FE、3 个独立大容量流体槽、81 格食材仓库

## 烹饪工厂（mekck:{等级}_cooking_factory）

* 智能厨锅的聚合版本，**单线程**——1 个输入槽一次处理多份，而非多线程并行
* 无尽升级组件对烹饪工厂仅在悖论无限及以下有效

## 等级链

基础 → 高级 → 精英 → 终极 → 绝对 → 至尊 → 寰宇支配 → 悖论无限 → 烈焰炽焱 → 晶钛矩阵 → 星云塑造 → 奇点创世

## 工厂效果

* 升级方式：**不接受工厂安装器**——每一级都只能**逐级合成**（**每级配方查 JEI**）；其中 **烈焰炽焱 / 晶钛矩阵 / 星云塑造三级目前没有配方**
  * **无尽升级组件**仍可用于悖论无限及以下
* **单线程（非多线程）**：固定 **1 个输入槽**，每次操作**聚合处理多份**材料，而不是多线程并行
* **并行数**（一次处理的份数）随等级提升：基础 1 → 高级 3 → 精英 5 → 终极 7 → 绝对 9 → 至尊 11 → 寰宇支配 13 → 悖论无限 15 → **烈焰炽焱 30 → 晶钛矩阵 60 → 星云塑造 120 → 奇点创世 21 亿**
* **并行数**：每个输入槽每次操作处理的物品数，靠**堆叠升级**提升；最大并行 = 基础并行 × 2^堆叠升级数
* 速度/能量升级上限：基础 ~ 悖论无限 8、**烈焰炽焱 12、晶钛矩阵 16、星云塑造 20、奇点创世 32**
* 堆叠升级上限 6（绝对 ~ 星云塑造）；**奇点创世不支持堆叠升级**——它的基础并行即为极限并行
* **只有星云塑造与奇点创世免能耗**；其余等级耗电，实际耗电随同时运行的输入槽数增加
* 升级时数据（物品、能量、进度、侧边配置、红石模式、流体）经 NBT 迁移，不产生掉落

---
navigation:
  parent: machines/overview.md
  title: 电力烧烤架与烧烤工厂
  position: 6
  icon: mekck:electric_grill
categories:
- machines
item_ids:
- mekck:electric_grill
- mekck:basic_grill_factory
- mekck:advanced_grill_factory
- mekck:elite_grill_factory
- mekck:ultimate_grill_factory
- mekck:absolute_grill_factory
- mekck:supreme_grill_factory
- mekck:cosmic_grill_factory
- mekck:infinite_grill_factory
- mekck:blaze_grill_factory
- mekck:crystal_matrix_grill_factory
- mekck:nebula_grill_factory
- mekck:singularity_grill_factory
---
# 电力烧烤架与烧烤工厂

## 电力烧烤架（mekck:electric_grill）

* 自动执行烧烤乐事的烧烤配方（单输入单输出），支持调味系统
* **订单驱动：没下单就不加工**（本机的产品定义）—— 由 ME 终端或本机下单面板下样板，不自动吃料；
  装 AE2 时可从 ME 网络自动取料处理并回网
* 能耗 20 FE/t、容量 100,000 FE

## 烧烤工厂（mekck:{等级}_grill_factory）

* 电力烧烤架的**多线程**版本，每个输入槽独立并行处理、自动整理
* **调味槽 ×3**：可识别烧烤乐事与森罗物语的调味瓶，可开关启用
* **工作模式**：默认 / 下单，可在 GUI 内切换；装 AE2 后支持 ME 自动处理页
* 槽位：输入 = 输出 = 线程数；另有 3 调味槽、速度 / 能量 / 堆叠（绝对 ~ 星云）/ 创造升级与能源槽
* 运行时按消耗电能产热，机身温度显示在界面中，并与相邻的通用机械热力设备双向传导（**温度不影响运行**）

> **熔炉配方扩展**：晶钛矩阵 / 星云塑造（一组配置）与奇点创世（另一组配置）烧烤工厂可额外处理**原版熔炉 / 烟熏炉 / 高炉配方**（默认开启）。

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

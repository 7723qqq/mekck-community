---
navigation:
  parent: machines/overview.md
  title: 急冻制冰机与制冰工厂
  position: 8
  icon: mekck:ice_maker
categories:
- machines
item_ids:
- mekck:ice_maker
- mekck:ice_factory
- mekck:basic_ice_factory
- mekck:advanced_ice_factory
- mekck:elite_ice_factory
- mekck:ultimate_ice_factory
- mekck:absolute_ice_factory
- mekck:supreme_ice_factory
- mekck:cosmic_ice_factory
- mekck:infinite_ice_factory
- mekck:blaze_ice_factory
- mekck:crystal_matrix_ice_factory
- mekck:nebula_ice_factory
- mekck:singularity_ice_factory
---
# 急冻制冰机与制冰工厂

## 急冻制冰机（mekck:ice_maker）

* 用水量产冰块（内置 256,000 mb 水罐，仅接受水），输入槽放冰类物品作催化剂（不消耗）
* 装上冷萃升级链（冷萃→低温→凛冰→龙霜/女王→失温）后变为自动炮台，从目标头顶召唤冰块砸下；GUI 内可调目标类型与索敌半径，攻击受红石控制
* 能耗 60 FE/t、容量 300,000 FE、接收上限 5,000 FE/t

## 温度系统

* 机身温度低于 0 ℃ 才能加工，温度越低越快（0 ℃ 为 1 倍、绝对零度 30 倍，基准 100 tick）
* 耗电分两部分：加工耗电 60 FE/t（与速度无关，仅运行时消耗）；制冷耗电最大 4,000 FE/t，按温差自动调节，效率为电阻型加热器的三分之一
* 界面内可开启控温并设定目标温度（默认 −273.15 ℃），机器自动制冷至设定温度后停止制冷
* 与相邻的通用机械热力设备（电阻型加热器、热导管等）双向传导热量
* 不支持安装速度升级（速度完全由温度决定）

## 制冰工厂（mekck:ice_factory / mekck:{等级}_ice_factory）

* 急冻制冰机的多线程版本（注册当前处于禁用状态）

## 等级链

基础 → 高级 → 精英 → 终极 → 绝对 → 至尊 → 寰宇支配 → 悖论无限 → 烈焰炽焱 → 晶钛矩阵 → 星云塑造 → 奇点创世

## 工厂效果

* 升级方式：手持**对应等级的工厂安装器**右键（高工厂安装器可替代低等级；无尽乐事对应等级的刀同样可用），或用**无尽升级组件**（视为奇点创世级安装器）
* **多线程**：线程数 = 输入槽数量，由等级决定：基础 3 → 高级 5 → 精英 7 → 终极 9 → 绝对 11 → 至尊 13 → 寰宇支配 15 → 悖论无限 17 → **烈焰炽焱 25 → 晶钛矩阵 36 → 星云塑造 49 → 奇点创世 81**
* **并行数**：每个输入槽每次操作处理的物品数，靠**堆叠升级**提升；最大并行 = 基础并行 × 2^堆叠升级数
* 速度/能量升级上限：基础 ~ 悖论无限 8、**烈焰炽焱 12、晶钛矩阵 16、星云塑造 20、奇点创世 32**
* 堆叠升级上限 6（绝对 ~ 星云塑造）；**奇点创世不支持堆叠升级**——它的基础并行即为极限并行
* **能耗机制与本模组其它工厂不同**：容量取等级枚举值，接收上限固定 5,000 FE/t，单进程能耗取等级枚举值（星云塑造与奇点创世为 0 = 免能耗），**不按「20 FE/t × 并行数」计算**
* 升级时数据（物品、能量、进度、侧边配置、红石模式、流体）经 NBT 迁移，不产生掉落

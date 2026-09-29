---
navigation:
  parent: index.md
  title: 八级体系与安装器
  position: 1
item_ids:
- mekanism_extras:absolute_tier_installer
- mekanism_extras:supreme_tier_installer
- mekanism_extras:cosmic_tier_installer
- mekanism_extras:infinite_tier_installer
- mekanism_extras:absolute_control_circuit
- mekanism_extras:supreme_control_circuit
- mekanism_extras:cosmic_control_circuit
- mekanism_extras:infinite_control_circuit
---

# 八级体系与安装器

> 数据来源：`mekanism_extras-1.20.1-1.5.0.jar`（`AdvancedTier` / `ExtraFactoryTier` 字节码常量 + 配方 JSON）。

## 一、四个新等级

| # | 等级 | 来源 |
|---:|---|---|
| 1 | 基础 Basic | 通用机械本体 |
| 2 | 高级 Advanced | 本体 |
| 3 | 精英 Elite | 本体 |
| 4 | 终极 Ultimate | 本体 |
| **5** | **绝对 Absolute** | **本模组** |
| **6** | **至尊 Supreme** | **本模组** |
| **7** | **寰宇支配 Cosmic** | **本模组** |
| **8** | **悖论无限 Infinite** | **本模组** |

**升级方式与本体一致**：把**工厂安装器**拿在主手、**潜行右键**目标方块，等级 +1。

## 二、工厂安装器（4 种）

四个新等级各有一把**工厂安装器**（绝对 / 至尊 / 寰宇支配 / 悖论无限）。
用法与本体一致：**主手拿安装器、潜行右键目标方块**，等级 +1；高等级安装器可替代低等级。

> 🔍 **合成配方请看游戏内的 JEI / 配方书** —— 指南只讲机制，不重复列配方
> （材料要用的**合金**见 [[materials/alloys.md|四种合金]]）。

## 三、控制电路（4 种）

四个新等级还各有对应的**控制电路**，它是做**同等级安装器**的前置材料：

* 是一条**逐级往上**的链：做高一级的电路要用到**上一级的电路**（绝对电路用本体的 `ultimate` 电路）；
* ⇒ **必须先做电路，才能做安装器**，且**不能跳级**；
* 每级具体要哪种合金、哪种电路 —— **查 JEI**。

## 四、各等级能升什么

| 类别 | 本体最高 | 本模组追加 |
|---|---|---|
| 工厂 | 终极（9 并行） | 绝对 11 / 至尊 13 / 寰宇支配 15 / **悖论无限 17** |
| 能量立方 | 终极 256 M J | 见 [[storage.md|储能与 QIO 驱动]] |
| 箱柜 / 储罐 | 终极 | 同上 |
| 感应矩阵 | 终极 | 同上（另有**强化**外壳/端口） |
| 通用线缆 / 机械管道 / 加压管道 / 物流管道 / 热导线缆 | 终极 | 见 [[logistics.md|管道、线缆与物流]] |
| QIO 驱动 | 超巨质量 | 坍缩 / 伽马 / 黑洞 / 奇点 |

另见 [[materials/alloys.md|四种合金]]（升级材料链）与 [[upgrades.md|堆叠 / 离子膜 / 创造升级]]。

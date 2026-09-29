---
navigation:
  parent: index.md
  title: 管道、线缆与物流
  position: 8
item_ids:
- mekanism_extras:absolute_universal_cable
- mekanism_extras:absolute_mechanical_pipe
- mekanism_extras:absolute_pressurized_tube
- mekanism_extras:absolute_logistical_transporter
- mekanism_extras:absolute_thermodynamic_conductor
- mekanism_extras:supreme_universal_cable
- mekanism_extras:supreme_mechanical_pipe
- mekanism_extras:supreme_pressurized_tube
- mekanism_extras:supreme_logistical_transporter
- mekanism_extras:supreme_thermodynamic_conductor
- mekanism_extras:cosmic_universal_cable
- mekanism_extras:cosmic_mechanical_pipe
- mekanism_extras:cosmic_pressurized_tube
- mekanism_extras:cosmic_logistical_transporter
- mekanism_extras:cosmic_thermodynamic_conductor
- mekanism_extras:infinite_universal_cable
- mekanism_extras:infinite_mechanical_pipe
- mekanism_extras:infinite_pressurized_tube
- mekanism_extras:infinite_logistical_transporter
- mekanism_extras:infinite_thermodynamic_conductor
---

# 管道、线缆与物流

> 数据来源：`ExtraConfig` 默认值（字节码常量）。能量换算：**1 FE = 2.5 J**。

五种管道都补齐到 4 个新等级。介质与本体完全一致：
**通用线缆**走能量、**机械管道**走流体、**加压管道**走气体/浆液、**物流管道**走物品、**热导线缆**走热量。

## 一、通用线缆（能量）

| 等级 | 传输上限（J/t） | 换算（FE/t） |
|---|---:|---:|
| 绝对 | 65 536 000 | 26 214 400 |
| 至尊 | 524 288 000 | 209 715 200 |
| 寰宇支配 | 4 194 304 000 | 1 677 721 600 |
| 悖论无限 | 33 554 432 000 | 13 421 772 800 |

> 本体终极线缆 8 192 000 J/t ⇒ **悖论无限级是它的 4096 倍**。

## 二、机械管道（流体，mB）

| 等级 | 容量 | 抽取速率 |
|---|---:|---:|
| 绝对 | 1 024 000 | 256 000 |
| 至尊 | 8 192 000 | 2 048 000 |
| 寰宇支配 | 65 536 000 | 16 384 000 |
| 悖论无限 | 524 288 000 | 131 072 000 |

## 三、加压管道（气体/浆液，mB）

| 等级 | 容量 | 抽取速率 |
|---|---:|---:|
| 绝对 | 8 192 000 | 2 048 000 |
| 至尊 | 65 536 000 | 16 384 000 |
| 寰宇支配 | 524 288 000 | 131 072 000 |
| 悖论无限 | 4 194 304 000 | 1 048 576 000 |

## 四、物流管道（物品）

| 等级 | 速度 | 每次抽取 | 每格耗时 |
|---|---:|---:|---:|
| 绝对 | 55 | 128 | 2 tick |
| 至尊 | 60 | 256 | 2 tick |
| 寰宇支配 | 70 | 512 | 2 tick |
| 悖论无限 | **100** | **1024** | **1 tick** |

> 速度算法与本体一致：每 tick 给物品的 `progress` 加 `speed`，加到 **100** 走一格。
> 悖论无限级速度正好 100 ⇒ **每 tick 走一格**，已经是理论极限。

## 五、热导线缆

| 等级 | 导热 | 热容 | 隔热 |
|---|---:|---:|---:|
| 绝对 | 10 | 1 | 400 000 |
| 至尊 | 15 | 1 | 800 000 |
| 寰宇支配 | 20 | 1 | 1 000 000 |
| 悖论无限 | 25 | 1 | 4 000 000 |

> 隔热越高越保温；悖论无限级隔热是本体的 **40 倍**。

## 六、合金升级

| 配置 | 默认 | 说明 |
|---|---:|---|
| `transmitterAlloyUpgrade` | 1 | 用**合金**升级管道（每级一次） |

> 也就是说：**这四种新等级管道不是直接合成的，而是从终极管道用合金升上去的**。
> 合金链见 [[materials/alloys.md|四种合金]]。

## 七、配线建议

1. **先算总耗能**再选线缆等级 —— 新等级机器耗能极大（悖论无限工厂 17 并行），低等级线缆会严重卡脖子；
2. **悖论无限级线缆 13 421 772 800 FE/t**，实际已不会成为瓶颈；
3. **物流管道建议直接升到悖论无限**（1 tick/格 + 1024 个/次），这是整条自动化里最容易卡的一环；
4. 跨维度/远距离仍用**量子传送装置**，不靠管道硬拉。

另见 [[storage.md|储能与 QIO 驱动]]、[[tiers.md|八级体系与安装器]]。

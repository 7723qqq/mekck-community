---
navigation:
  parent: index.md
  title: 无线充电站与装饰玩偶
  position: 6
item_ids:
- mekmm:wireless_charging_station
- mekmm:author_doll
- mekmm:modeler_doll
---

# 无线充电站与装饰玩偶

> 本页是 mekmm 的"杂项"机器：一台功能机（无线充电站）+ 两台纯装饰（作者 / 建模者玩偶）。

## 一、无线充电站 `mekmm:wireless_charging_station`

> 官方描述：**"一台为物品栏和盔甲进行无线充电的机器（当然还有饰品栏，如果你有的话）"**。

- **内部储能**：10,000,000 J；**充能速率**：100,000（`wirelessChargingStationChargingRate`，可配）；
- **三种充能范围**（官方 `charging.mekmm.*` 键）：

  | 键 | 中文 | 含义 |
  |---|---|---|
  | `charging.mekmm.inventory` | **物品栏充能** | 给玩家背包里的可充电物品回能 |
  | `charging.mekmm.equips` | **装备栏充能** | 给穿戴中的装备回能 |
  | `charging.mekmm.curios` | **饰品栏充能** | 装了 Curios（饰品栏）类 mod 时，连饰品一起充 |

- 站在机器作用范围内即可，无需手持接线；是否覆盖某个物品，取决于该物品是否可被 Mekanism 能量系统充电。

> ⚠️ 语言 / 模型文件里还有一个"**无线传输站 `wireless_transmission_station`**"，但它**没有任何方块注册、中英文都无条目**（死素材），游戏内不会出现 ⇒ 本指南不写。

## 二、作者 / 建模者玩偶（纯装饰）

| id | 官方名（就是玩家名）| 官方描述 |
|---|---|---|
| `mekmm:author_doll` | **LostMyself** | "一个有着作者皮肤的玩偶（**只是装饰品**）" |
| `mekmm:modeler_doll` | **TedXenon** | "一个有着模型师皮肤的玩偶（**感谢所有为 mekmm 提供纹理和模型的人**）" |

- 两者都是**装饰方块**，无机器功能；
- 按数据侧记载，玩偶**由本体的压缩机（`mekanism:compressor`）制得**，具体配方看 **JEI**（本指南不写工作台合成表）。

## 三、坑与提醒

- **无线充电站 vs 无线传输站**：只有"**充电站**（charging）"是真实机器；"**传输站**（transmission）"是死素材、游戏内没有，别在 JEI 之外到处找它；
- **饰品栏充能**要求装了 Curios 一系的 mod，否则该项无意义；
- 玩偶纯粹是致敬装饰，不承载任何玩法。

> 充电站的实际作用半径 / 每 tick 充能量、玩偶的压缩配方，均以 **JEI** 为准。

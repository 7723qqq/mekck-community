---
navigation:
  parent: index.md
  title: 种植站与种植工厂
  position: 4
item_ids:
- mekmm:planting_station
---

# 种植站与种植工厂

> 官方描述（`description.mekmm.planting_station`）：**"一台可用种植作物的高级机器"**。
> 命名以官方 `zh_cn.json` 为准：单机 = **种植站**，批量 = **××种植工厂**（4 档）。

## 一、机器与工厂

| 类型 | id | 官方名 | 耗能（J/t）| 内部储能（J）|
|---|---|---|---:|---:|
| 方块 | `mekmm:planting_station` | **种植站** | 200（=80 FE/t）| 80,000 |
| 工厂 | `mekmm:*_planting_factory`（4 档）| **××种植工厂** | 见方块页 | 逐级提升 |

> 数据取自 `MoreMachineUsageConfig` / `MoreMachineStorageConfig` 字节码；实际数值可被配置覆盖，以 **JEI / 方块 tooltip** 为准。

## 二、配方类型：`mekmm:planting`（**物品 + 气体缓存**）

种植配方（`PlantingRecipe`）是 mekmm 自定义 5 个配方类型之一（见 [[machines/factories.md|工厂]] §一）。
它的结构**同时吃一个物品输入和一个气体输入**：

```jsonc
{
  "type": "mekmm:planting",
  "itemInput":  { "ingredient": { "item": "minecraft:wheat_seeds" } },
  "gasInput":   { "amount": 1, "gas": "mekmm:nutrient_solution" },   // ← 营养液
  "mainOutput": { "count": 5, "item": "minecraft:wheat" },
  "secondaryOutput": { "count": 2, "item": "minecraft:wheat_seeds" }, // 可选
  "secondaryChance": 0.8                                              // 可选
}
```

- **`gasInput` 一律是 `mekmm:nutrient_solution`（营养液）**：jar 内 **47 条** `mekmm:planting` 配方**全部**用这一种气体、量都是 **1 mB/条**（实测逐条比对）；
- **`mainOutput` 必出**、**`secondaryOutput` + `secondaryChance` 可选**（如小麦种子额外返还种子的概率副产）；
- 换作物 = 换输入物品，**营养液缓存要保持**（气体用光机器就停转）。

## 三、47 条种植配方覆盖的作物类别

`data/mekmm/recipes/planting/` 实测 47 条，可归为几类（示例见 JEI）：

| 类别 | 代表输入 → 主输出 |
|---|---|
| 主粮 / 蔬菜 | 小麦种子→小麦、胡萝卜→胡萝卜、马铃薯→马铃薯、甜菜种子→甜菜根 |
| 经济作物 | 甘蔗、竹子、仙人掌、海带、南瓜/西瓜种子、可可豆、地狱疣 |
| 菌类 | 棕色蘑菇、红色蘑菇、绯红菌、诡异菌 |
| 花 / 植物 | 杜鹃花、蓝花矢车菊、铃兰、枯萎玫瑰、堆肥丛等 |
| 树苗 /  propagule | 橡树/白桦/云杉/丛林/金合欢/深色橡树/红树/樱花树苗、红树胎生苗 |
| 浆果 | 甜浆果、发光浆果 |

> 具体某个作物消耗/产出多少、是否有副产概率，以 **JEI** 的 `mekmm:planting` 分类为准（本指南**不写工作台合成表**）。

## 四、种植工厂（4 档批量）

- **`basic` ~ `ultimate` `_planting_factory`** 与种植站**共用同一张 `mekmm:planting` 配方表**，只是**并行槽数 / 能耗 / 速度**逐级提升；
- 每一槽都各自需要 **营养液** 缓存才能持续产出；
- 逐级合成、不能跳档（`mek_data` 网格 `P` = 上一级工厂，见 [[machines/factories.md|工厂]] §二）。

## 五、坑与提醒

- **别断营养液**：种植是唯一"气体 + 物品"双输入的 mekmm 单机，营养液耗尽即停；
- **营养液从哪来**：属 mekmm / 本体气体链产物，本指南不臆造来源，请在 **JEI** 里对 `mekmm:nutrient_solution` 查"用途/来源"；
- **另注意 `mekmm:nutritional_paste`（营养糊剂）是另一种气体**，与"营养液"不是一回事，别混用。

> 耗能 / 每 tick 处理时间 / 槽数以 **JEI** 内方块页为准。批量阶段直接上 `planting` 工厂（见 [[machines/factories.md|工厂]]）。

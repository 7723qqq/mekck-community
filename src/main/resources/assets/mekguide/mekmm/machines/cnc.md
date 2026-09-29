---
navigation:
  parent: index.md
  title: 数控三机（压模 / 车床 / 轧机）与模具机制
  position: 2
item_ids:
- mekmm:cnc_stamper
- mekmm:cnc_lathe
- mekmm:cnc_rolling_mill
---

# 数控三机（压模 / 车床 / 轧机）与模具机制

> 官方描述（jar `description.mekmm.*`）：
> * `cnc_stamper` = 一台依赖**模具**加工物品的机器；
> * `cnc_lathe` = 一台有着很多功能的加工材料的机器；
> * `cnc_rolling_mill` = 一台利用**机械力量弯折金属**的机器。

三台都是**基础单机**（无等级版本），但每一台在 [[machines/factories.md|工厂]] 里都有对应的 4 档工厂（`stamping` / `lathing` / `rolling_mill`）批量并行。

## 一、三种配方类型对比

| 机器 | 方块 id | 配方类型（`MoreMachineRecipeType`） | 输入形态 | 配方实现类 |
|---|---|---|---|---|
| 数控压模机 | `mekmm:cnc_stamper` | `mekmm:stamping`（**StamperRecipe**）| **双物品：工件 + 模具**（模具不消耗）| 自定义 |
| 数控车床 | `mekmm:cnc_lathe` | `mekmm:lathing` | 单物品 | **复用** Mekanism `ItemStackToItemStackRecipe` |
| 数控轧机 | `mekmm:cnc_rolling_mill` | `mekmm:rolling_mill` | 单物品 | **复用** Mekanism `ItemStackToItemStackRecipe` |

> 关键区别：车床 / 轧机的配方结构与本体的"研磨 / 压缩"完全一致，只是**归到 mekmm 自己的类型下**，方便 JEI 分类与工厂并行。

## 二、模具机制（`stamping`）

- **工件 + 模具**双输入，模具**不消耗**、留在槽内；
- 换产品 = **换模具**（不像合成台要重新摆材料）；
- **压模工厂**（`stamping`）并行时**每槽各自持模** ⇒ 想让一台工厂同时压多种产品，需要在每个槽位分别放入对应模具；同型号模具可以复用。

> 具体某个模具能压出什么，看 **JEI** 的 `mekmm:stamping` 分类。

## 三、常见用途

- **压模**：金属板材 → 带纹路的装饰件 / 特定合金片。
- **车削**：金属锭 → 轴、杆、圆柱件。
- **轧制**：金属锭 → 超薄板 / 长条（"弯折金属"官方描述所指）。

## 四、与工厂的衔接

三机的输出常作为**下一级机器**的输入（如压模产出的薄片进本体上色机、车削产出的轴进本体的组装器等）；批量阶段请上对应的 `stamping` / `lathing` / `rolling_mill` 工厂（见 [[machines/factories.md|工厂]]）。

> 具体每台的耗能、每 tick 处理时间与槽数，以 **JEI** 内方块页为准。

---
navigation:
  parent: index.md
  title: 硅岩反应堆
  position: 5
item_ids:
- mekanism_extras:naquadah_reactor_casing
- mekanism_extras:naquadah_reactor_controller
- mekanism_extras:naquadah_reactor_logic_adapter
- mekanism_extras:naquadah_reactor_port
- mekanism_extras:naquadah_hohlraum
- mekanism_extras:lead_coated_glass
- mekanism_extras:lead_coated_laser_focus_matrix
- mekanism_extras:rich_naquadah_fuel
- mekanism_extras:rich_uranium_fuel
- mekanism_extras:fluorinated_naquadah_uranium_fuel
---

# 硅岩反应堆（多方块）

> 数据来源：`com.jerry.generator_extras.common.config.GeneratorConfig` 默认值（字节码常量）。

硅岩反应堆是本模组的**终极发电设备**，结构上**与通用机械发电机模组的聚变反应堆同构**：
燃料 → **热量** →（水/蒸汽）→ 工业蒸汽涡轮 → 电。

## 一、部件

| 方块 | 说明 | 获得方式 |
|---|---|---|
| **硅岩反应堆外壳** `naquadah_reactor_casing` | 搭结构的墙 | **反质子核合成器** |
| **硅岩反应堆控制器** | 主控方块，开界面 | 合成（查 JEI） |
| **硅岩反应堆逻辑适配器** | 红石控制 / 计算机接口 | 合成（查 JEI） |
| **硅岩反应堆端口** | 输入输出（燃料/水/蒸汽） | 合成（查 JEI） |
| **铅涂层玻璃** `lead_coated_glass` | 观察窗（防辐射） | 见下 |
| **铅涂层激光聚焦矩阵** | 激光注入面 | 见下 |
| **黑体腔** `naquadah_hohlraum` | 燃料容器（物品） | — |

> 「铅涂层」系列需要**铅灌注物**（见 [[materials/alloys.md|四种合金]]）。

## 二、运行数值

| 项 | 值 | 换算 |
|---|---:|---:|
| **每 mB 燃料产能** | **10 000 000 J** | 4 000 000 FE |
| 内部储能 | 10 000 000 000 J | 4 000 000 000 FE |
| 燃料容量 | 1 000 mB | — |
| 热电偶效率 | 0.1 | — |
| 外壳导热 | 0.1 | — |
| 水加热比例 | 0.3 | — |
| 每次注入水量 | 1 000 000 mB | `waterPerInjection` |
| 黑体腔容量 | 100 | `naquadah_hohlraum` 的 `maxGas` |

> ⚠️ 配置里还有一项 **`poloniumPerInjection`（默认 100）**，注释写的是「钋」——
> 那是作者从聚变堆的 `steamPerInjection` 改名来的，但**源码里没有任何地方读它**，属于**无效配置项**，不影响实际运行。
> 反应堆与蒸汽相关的逻辑走的是 `steamTank` / `getSteamPerTick()`（见下面的类字段），所以「产蒸汽」这件事是真的。

> **每 mB 燃料 10 000 000 J 与本体聚变堆完全相同** —— 它就是"聚变级"的反应堆。

## 三、三种燃料

| 燃料 | 物品 |
|---|---|
| **富硅岩燃料** | `rich_naquadah_fuel`（桶装流体） |
| **富铀燃料** | `rich_uranium_fuel`（桶装流体） |
| **含氟硅岩铀燃料** | `fluorinated_naquadah_uranium_fuel`（桶装流体） |

## 四、怎么搭（与聚变堆同思路）

1. 用**外壳**搭出反应堆体积，留出**控制器**、**端口**、**铅涂层激光聚焦矩阵**的位置；
2. 控制器里放入**黑体腔**并注入燃料；
3. 用**激光**照射激光聚焦矩阵预热（与聚变堆一致的做法）；
4. 端口接**水**进去、接**蒸汽**出来；
5. 蒸汽送进**工业蒸汽涡轮**发电（本体发电机模组的机器）。

> ⚠️ 本页数值来自配置默认值。**具体搭建尺寸与激光预热流程本指南未逐项核对**，
> 以游戏内控制器界面（`GuiNaquadahReactorController` 提供燃料/热量/信息/状态四个页签）为准。

## 五、安全

* 燃料与产物有**辐射**，注意防护（铅涂层系列就是干这个的）；
* 反应堆内部储能 10 GJ，**大规模储电请配感应矩阵**（见 [[storage.md|储能与 QIO 驱动]]）。

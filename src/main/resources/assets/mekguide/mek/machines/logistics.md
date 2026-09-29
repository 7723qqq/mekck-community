---
navigation:
  parent: index.md
  title: 管道、线缆与物流
  position: 9
item_ids:
- mekanism:basic_universal_cable
- mekanism:advanced_universal_cable
- mekanism:elite_universal_cable
- mekanism:ultimate_universal_cable
- mekanism:basic_mechanical_pipe
- mekanism:advanced_mechanical_pipe
- mekanism:elite_mechanical_pipe
- mekanism:ultimate_mechanical_pipe
- mekanism:basic_pressurized_tube
- mekanism:advanced_pressurized_tube
- mekanism:elite_pressurized_tube
- mekanism:ultimate_pressurized_tube
- mekanism:basic_logistical_transporter
- mekanism:advanced_logistical_transporter
- mekanism:elite_logistical_transporter
- mekanism:ultimate_logistical_transporter
- mekanism:basic_thermodynamic_conductor
- mekanism:advanced_thermodynamic_conductor
- mekanism:elite_thermodynamic_conductor
- mekanism:ultimate_thermodynamic_conductor
- mekanism:logistical_sorter
- mekanism:restrictive_transporter
- mekanism:diversion_transporter
- mekanism:quantum_entangloporter
---

# 管道、线缆与物流

> 数据来源：`Mekanism-1.20.1-10.4.16.80.jar`，逐项取自各 tier 枚举的字节码常量与配置默认值。
> 能量单位换算：**1 FE = 2.5 J**（Mekanism 内部以 J 存储）。

## 一、五种管道，五种资源

| 管道 | 传输的资源 | 网络类型 |
|---|---|---|
| **通用线缆** Universal Cable | 能量 | `ENERGY` |
| **机械管道** Mechanical Pipe | 流体 | `FLUID` |
| **加压管道** Pressurized Tube | 气体、浆液、灌注物、颜料 | `GAS` / `SLURRY` / `INFUSION` / `PIGMENT` |
| **物流管道** Logistical Transporter | 物品 | `ITEM` |
| **热导线缆** Thermodynamic Conductor | 热量 | `HEAT` |

> 气体、浆液、灌注物、颜料在网络层是**四套独立网络**，但**都走加压管道**。
> 管道**不会自动串线**：不同介质各拉一路，交叉处不会互相干扰。

## 二、四档等级

每种管道都有 **基础 / 高级 / 精英 / 终极** 四档（`BaseTier`）。

### 通用线缆（能量）

| 等级 | 传输上限 |
|---|---:|
| 基础 | 8 000 J/t = **3 200 FE/t** |
| 高级 | 128 000 J/t = **51 200 FE/t** |
| 精英 | 1 024 000 J/t = **409 600 FE/t** |
| 终极 | 8 192 000 J/t = **3 276 800 FE/t** |

### 机械管道（流体）

| 等级 | 容量 | 抽取速率 |
|---|---:|---:|
| 基础 | 2 000 mB | 250 mB/t |
| 高级 | 8 000 mB | 1 000 mB/t |
| 精英 | 32 000 mB | 8 000 mB/t |
| 终极 | 128 000 mB | 32 000 mB/t |

### 加压管道（气体/浆液等）

| 等级 | 容量 | 抽取速率 |
|---|---:|---:|
| 基础 | 4 000 mB | 750 mB/t |
| 高级 | 16 000 mB | 2 000 mB/t |
| 精英 | 256 000 mB | 64 000 mB/t |
| 终极 | 1 024 000 mB | 256 000 mB/t |

### 物流管道（物品）

| 等级 | 每次抽取 | 行进速度 | 每格耗时 |
|---|---:|---:|---:|
| 基础 | 1 个 | 5 | 20 tick |
| 高级 | 16 个 | 10 | 10 tick |
| 精英 | 32 个 | 20 | 5 tick |
| 终极 | 64 个 | 50 | 2 tick |

> 速度的算法：每个 tick 给物品的 `progress` 加 `speed`，加到 **100** 就走完一格 ⇒ **每格 tick 数 = 100 ÷ speed**。

### 热导线缆（热量）

| 等级 | 导热 | 热容 | 隔热 |
|---|---:|---:|---:|
| 基础 | 5 | 1 | 10 |
| 高级 | 5 | 1 | 400 |
| 精英 | 5 | 1 | 8 000 |
| 终极 | 5 | 1 | 100 000 |

> **隔热越高越保温**：等级越高，热量在传输途中散失越少。

## 三、物品物流三件套

| 方块 | 作用 |
|---|---|
| **物流分类机** Logistical Sorter | **物流的脑子**：以**过滤**为基础，把指定物品自动弹出到相邻容器或物流管道，可指定输出面 |
| **限流管道** Restrictive Transporter | **降级路径**：物品**优先走别的路**，**只有没有其它可用路径时才走它** —— 用来把某些路线「让开」，不是「只抽自己那格」 |
| **转运管道** Diversion Transporter | **逐面 + 红石控制**：每个面可单独设为 `DISABLED` / `HIGH` / `LOW`（禁用 / 高电平启用 / 低电平启用），用来做**受红石信号控制**的岔路与合流 |

> 做法：**物流分类机 + 一串物流管道**。分类机负责「从哪儿拿、往哪儿送」，管道只负责「跑腿」。

## 四、量子传送装置（跨维度物流）

**量子传送装置** Quantum Entangloporter 把六种资源全部无线化：两端用**同一个频率**即可跨维度互传。

* 能量缓冲（每频率、**每 tick** 上限）：**256 000 000 J = 102 400 000 FE**
  （默认值 = 终极能量立方的容量，见 `GeneralConfig.energyBuffer`）。

## 五、QIO 物品存储

**QIO**（量子物品编排）是一套**超大容量、按种类索引**的物品云端：驱动阵列 + 仪表盘 + 输入/输出总线 + 红石适配器。

| 驱动等级 | 物品总量 | 物品种类 |
|---|---:|---:|
| 基础 Base | 16 000 | 128 |
| 高密度 Hyper Dense | 128 000 | 256 |
| 时间膨胀 Time Dilating | 1 048 000 | 1 024 |
| 超巨质量 Supermassive | 16 000 000 000 | 8 192 |

## 六、仓储方块速查

| 方块 | 基础 | 高级 | 精英 | 终极 |
|---|---:|---:|---:|---:|
| **箱柜** Bin（同种物品） | 4 096 | 8 192 | 32 768 | 262 144 |
| **液体储罐** Fluid Tank（mB） | 32 000 | 64 000 | 128 000 | 256 000 |
| **化学品储罐** Chemical Tank（mB） | 64 000 | 256 000 | 1 024 000 | 8 192 000 |
| **能量立方** Energy Cube（J） | 4 000 000 | 16 000 000 | 64 000 000 | 256 000 000 |
| └ 换算成 FE | 1 600 000 | 6 400 000 | 25 600 000 | 102 400 000 |

各级都有**创造模式**变体（容量为理论最大值）。

## 七、感应矩阵（终极储能）

多方块结构，用**感应外壳**搭出体积后，内部填**感应元件**（存）与**感应供应器**（放）：

| 等级 | 感应元件容量（J） | 感应供应器输出（J/t） |
|---|---:|---:|
| 基础 | 8 000 000 000 | 256 000 |
| 高级 | 64 000 000 000 | 2 048 000 |
| 精英 | 512 000 000 000 | 16 384 000 |
| 终极 | 4 000 000 000 000 | 131 072 000 |

## 八、容易踩的坑

1. **能量立方不是电池组的终点**——大规模储能用**感应矩阵**；
2. **机械管道 ≠ 加压管道**：流体和气体走**两种不同的管道**，别接错；
3. **管道等级决定上限，不是"越大越好"**：低等级管道会**卡住**高等级机器的吞吐；
4. **物流管道不会自己找目标**，必须配**物流分类机**（或机器的自动弹出面）才能把东西送进容器；
5. **热导线缆的"隔热"高才是好事**，它减少传输损失。

另见 [[machines/overview.md|机器与配方总览]]、[[basics/energy.md|能量与发电]]、[[machines/factories.md|工厂与并行处理]]。

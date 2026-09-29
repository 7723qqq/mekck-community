---
navigation:
  parent: index.md
  title: 工厂与并行处理
  position: 8
item_ids:
- mekanism:basic_smelting_factory
- mekanism:advanced_smelting_factory
- mekanism:elite_smelting_factory
- mekanism:ultimate_smelting_factory
- mekanism:basic_enriching_factory
- mekanism:advanced_enriching_factory
- mekanism:elite_enriching_factory
- mekanism:ultimate_enriching_factory
- mekanism:basic_crushing_factory
- mekanism:advanced_crushing_factory
- mekanism:elite_crushing_factory
- mekanism:ultimate_crushing_factory
- mekanism:basic_compressing_factory
- mekanism:advanced_compressing_factory
- mekanism:elite_compressing_factory
- mekanism:ultimate_compressing_factory
- mekanism:basic_combining_factory
- mekanism:advanced_combining_factory
- mekanism:elite_combining_factory
- mekanism:ultimate_combining_factory
- mekanism:basic_purifying_factory
- mekanism:advanced_purifying_factory
- mekanism:elite_purifying_factory
- mekanism:ultimate_purifying_factory
- mekanism:basic_injecting_factory
- mekanism:advanced_injecting_factory
- mekanism:elite_injecting_factory
- mekanism:ultimate_injecting_factory
- mekanism:basic_infusing_factory
- mekanism:advanced_infusing_factory
- mekanism:elite_infusing_factory
- mekanism:ultimate_infusing_factory
- mekanism:basic_sawing_factory
- mekanism:advanced_sawing_factory
- mekanism:elite_sawing_factory
- mekanism:ultimate_sawing_factory
---

# 工厂与并行处理

> 数据来源：`Mekanism-1.20.1-10.4.16.80.jar`，由字节码与语言文件逐项核对。
> 能量单位：配置以 **J（焦耳）** 存储，游戏内默认按 **FE** 显示，**1 FE = 2.5 J**（见 [[basics/energy.md|能量与发电]]）。

## 一、工厂是什么

工厂是**基础机器的多线程版本**：同一台机器同时处理 **N 份**材料（N = 并行数），而不是把 N 台机器摆一排。

* 工厂方块共 **9 类 × 4 级 = 36 个**（语言文件 `block.mekanism.*_factory` 实测 36 条）；
* 单次处理时间 **200 tick**（`TileEntityFactory.BASE_TICKS_REQUIRED = 200`），与对应基础机器相同。

## 二、四个等级与并行数

| 等级 | 并行数（同时处理的份数） | 升级来源 |
|---|---:|---|
| 基础 Basic | **3** | — |
| 高级 Advanced | **5** | 基础工厂 + 高级工厂安装器 |
| 精英 Elite | **7** | 高级工厂 + 精英工厂安装器 |
| 终极 Ultimate | **9** | 精英工厂 + 终极工厂安装器 |

> 依据：`mekanism/common/tier/FactoryTier.class` 静态初始化 —— `BASIC(3) ADVANCED(5) ELITE(7) ULTIMATE(9)`。
> 升级方式：把**工厂安装器**拿在主手、潜行右键工厂方块，等级 +1（安装器分四个等级）。

## 三、9 类工厂对照表

| 工厂类型 | 对应基础机器 | 配方类型 | 额定耗能 |
|---|---|---|---:|
| 熔炼工厂 | 电力熔炼炉 | `smelting` | 50 J/t（20 FE/t） |
| 富集工厂 | 富集仓 | `enriching` | 50 J/t（20 FE/t） |
| 粉碎工厂 | 粉碎机 | `crushing` | 50 J/t（20 FE/t） |
| 压缩工厂 | 锇压缩机 | 锇压缩 | 100 J/t（40 FE/t） |
| 融合工厂 | 融合机 | `combining` | 50 J/t（20 FE/t） |
| 提纯工厂 | 提纯仓 | `purifying` | 200 J/t（80 FE/t） |
| 压射工厂 | 化学压射室 | `injecting` | 400 J/t（160 FE/t） |
| 灌注工厂 | 冶金灌注机 | `metallurgic_infusing` | 50 J/t（20 FE/t） |
| 锯木工厂 | 精密锯木机 | `sawing` | 50 J/t（20 FE/t） |

## 四、耗能怎么算

**额定耗能 = 对应基础机器的耗能**，不随等级变化。

依据：`Factory.setMachineData` 里构造的是
`new AttributeEnergy(energy::getUsage, max(getConfigStorage × 0.5, getUsage) × processes)`
—— 第一个参数（耗能）直接取基础机器的 `getUsage()`，**只有储能乘了并行数**。

也就是说：

* 界面显示的耗能 = 基础机器耗能（例如富集工厂恒为 20 FE/t）；
* **每个正在运行的并行进程各消耗一份**（`RecipeCacheLookupMonitor` 对每个进程单独抽取能量），
  满负荷 N 份同时跑时，实际耗能 = 基础耗能 × N；
* **工厂并不省电**，它省的是**占地和布线**：9 份产能只占 1 格。

### 内部储能

| 工厂 | 内部储能 |
|---|---|
| 冶炼 / 富集 / 粉碎 / 灌注 / 锯木 | 4 000 FE × 并行数 |
| 化合 | 8 000 FE × 并行数 |
| 净化 / 压缩 | 16 000 FE × 并行数 |
| 注入 | 32 000 FE × 并行数 |

（例：基础富集工厂 = 12 000 J = 4 800 FE；终极富集工厂 = 36 000 J = 14 400 FE。）

## 五、怎么用

1. **摆一台对应等级的基础机器当作对照**，确认配方能跑通；
2. 工厂的**输入槽按并行数展开**，界面里能看到 N 组输入/输出格；同类材料会被自动归到同一组；
3. 接上能量（通用线缆）与**气体**（加压管道）—— 提纯、压射两类工厂**必须有气体**（氧气 / 氯化氢）才工作；
4. 想要更多并行就换更高等级，或再摆一台工厂。

## 六、常见疑问

**工厂和 N 台基础机器哪个划算？** 产能与耗能完全相同，工厂的优势是**一格顶 N 格**、布线简单；代价是配方更贵、需要工厂安装器。

**为什么工厂跑不满并行数？** 并行数 = **同时能处理的材料种类/批次份数**，供料不足、输出堵塞、气体不够都会让它跑不满。

**升级会掉东西吗？** 不会，工厂安装器只是把方块替换成上一级，内部物品与能量会保留。

另见 [[machines/ore_processing.md|矿石加工机器速查]]、[[machines/logistics.md|管道、线缆与物流]]、[[guides/operations.md|侧面配置、升级与红石]]。

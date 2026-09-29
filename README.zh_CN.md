# 通用机械：中央厨房（MekCK）

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-green.svg)](https://www.minecraft.net/)
[![Forge](https://img.shields.io/badge/Forge-47.4.16-orange.svg)](https://files.minecraftforge.net/)

把**农夫乐事**的厨房操作改造成**通用机械风格的工厂流水线**的 Minecraft 1.20.1 / Forge 模组，
并且能把吃不完的食物拿去发电。

> **English: [README.md](README.md)**

---

## 内容一览

### 7 大机器系列 × 12 个工厂等级

每个系列都有一台单方块基础机器，外加 **12 个等级**的工厂（合计 84 个工厂方块）：

| 系列 | 基础机器 | 工厂 |
|---|---|---|
| 切菜 | 通用切菜机 | `mekck:{等级}_cutting_factory` |
| 种植切配 | 种植切配站 | `mekck:{等级}_planting_cutting_factory` |
| 烹饪 | 智能厨锅 | `mekck:{等级}_cooking_factory` |
| 穿串 | 智能穿串机 | `mekck:{等级}_skewering_factory` |
| 烧烤 | 电力烧烤架 | `mekck:{等级}_grill_factory` |
| 研磨 | 电力研磨机 | `mekck:{等级}_grinding_factory` |
| 制冰 | 急冻制冰机 | `mekck:{等级}_ice_factory` |

**等级链**

```
基础 → 高级 → 精英 → 终极 → 绝对 → 至尊 → 寰宇支配 → 悖论无限
     → 烈焰炽焱 → 晶钛矩阵 → 星云塑造 → 奇点创世
```

线程数（输入槽数量）从 3 一路提升到 81；**星云塑造**与**奇点创世**不消耗能量，
且**奇点创世**无需安装任何堆叠升级——它的基础并行即为极限并行。

### 5 台独立机器

- **生物反应堆** —— 2×2×3 多方块；食物/有机物 → 有机物流体，或管道输入肉汤 / 营养糊 → FE。
  最高发电 8,000 FE/t、输出 14,000 FE/t。
- **中央厨房** —— 把机器当作模块装入，一台方块处理全部系列配方，支持合成链自动展开与系列过滤。
- **巧克力大炮** —— 先做费列罗，再把费列罗当炮弹打出去。
- **坚果爆炒机** —— 炒榛子；炒好的榛子同时是弹药。
- **三明治组装机** —— 按样品复制，或逐层自定义组装。*（需安装 Some Assembly Required）*

### 14 台联动机器

用于其它模组机器配方的单等级机器：智能料理台、平均切段机、饭团成型机、食品脱水机、发酵机、
智能蒸箱、凝乳成型机、智能陈酿机、鲜果榨汁机、糕点烘焙机、智能烤炉、搅拌机、调酒机、智能茶艺机。

### 联动功能

- **工厂安装器** —— 直接升级工厂；最高四级使用本模组自有的安装器，无尽乐事的刀同样可以当安装器用。
- **AE2** —— 网络拉料与 ME 终端下单（真实加工，产物回写网络）。
- **GuideME** —— 20 页游戏内图文指南；手持任意机器按 **G** 打开。
- **JEI** —— 所有配方、催化剂与燃料换算均可查询。
- **自动生成的配方** —— 为所有已安装模组的宴席方块生成装盘配方，并按战利品表生成种植配方。
- **温度系统** —— 产热机器可与通用机械的热力设备互通；急冻制冰机温度越低越快。

## 环境要求

| | |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.4.16 |
| Java | 17 |
| 必需前置 | [Mekanism](https://github.com/mekanism/Mekanism) ≥ 10.4、[Farmer's Delight](https://github.com/vectorwing/FarmersDelight) ≥ 1.2.7 |

可选联动模组见 [CREDITS.txt](CREDITS.txt)。

## 构建

构建需要两个**不在版本控制内**的第三方 jar（`libs/` 已被 git 忽略——再分发他人模组的 jar 不合规）。
首次构建前请先放入 `libs/`：

| 文件 | 用途 |
|---|---|
| `FarmersDelight-1.20.1-1.2.7.jar` | 编译期 API（raw，未重映射） |
| `appliedenergistics2-forge-15.4.10.jar` | 可选联动 AE2 的编译期 API |

任一文件缺失时，Gradle 会直接报出期望路径与下载地址，不会只在编译阶段抛一句找不到符号。

```bash
./gradlew build
```

产物位于 `build/libs/mekck-<版本>.jar`。

## 配置文件

```
config/mekck/mekck-common.toml
```

主要选项：

- `[multithreaded]` / `[non_multithreaded]` —— 各等级的**基础并行数**。
  最大并行数**不是**配置项，而是由 `基础并行 × 2^堆叠升级数` 计算得出（上限 ×64）。
- `[planting] auto_generate_planting_recipes` —— 启动时自动生成种植配方。
- `[plating] auto_generate_plating_recipes` —— 启动时自动生成装盘配方。

## 仓库结构

```
src/main/java/cn/ism/mekck/     Java 源码（279 个文件）
src/main/resources/             资源、数据、语言文件、游戏内指南
  assets/mekck/mekckguide/      GuideME 指南页（Markdown）
  assets/mekck/textures/block/vendor/   以 MIT 许可再分发的纹理（详见 THIRD-PARTY.md）
```

## 许可证

本项目采用 **MIT 许可证** —— 详见 [LICENSE](LICENSE)。

部分纹理由 Mekanism 与 Mekanism Extras 再分发（两者均为 MIT）；
详情与完整许可文本见 [THIRD-PARTY.md](THIRD-PARTY.md)。

---

*与 Mojang、Mekanism、农夫乐事均无隶属关系。*

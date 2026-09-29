# 交接说明：mekck 配方依赖的可达性判据

给**正在实施阶段 1（升级体系）的同伴**：这份说明划定文件边界，避免我们在
`factory` 包撞车，并把一个我在核查中发现的方法论问题交给你接手。

---

## 1. 我做了什么、没做什么

**没做**：我原计划在 `MekCkFactoryRegistration` 加"按可用性隐藏方块"的逻辑。
写这份说明是因为核查数据时发现**我原先的判据设计是错的**（见 §3），
在你已经开工的 `factory` 包上动手风险大于收益，所以先停下来说清楚。

**做过的分析**（可复用，但结论需修正）：
- 实例 `D:\mc\新建文件夹\versions\1.20.1-Forge_47.4.23` 的 14 个 mod 环境
- 真实运行日志：1080 条 ERROR（RecipeManager 924 / ForgeHooks 120 / Mekanism 32）
- 结论：**mekck 的配方硬编码了 20 个外部 mod，未在 `mods.toml` 声明，也未做条件加载**

---

## 2. 依赖是 (工艺 × 档位) 级，不是档位级

我原打算给 `MekCkFactoryTier` 加"本档需要哪些 mod"字段。**这是错的**——
依赖随工艺变化。实测（从配方 JSON 的 modid 静态提取）：

| 工艺 | 外部依赖分布 |
|---|---|
| `grilling` | 无（全 12 档都只有 mekck/minecraft/mekanism 材料） |
| `ice` | 无（同上） |
| `cutting` / `cooking` / `skewering` | ABSOLUTE~INFINITE 需 `mekanism_extras`；BLAZE~NEBULA 需 `avaritia_delight` |
| `grinding` | ABSOLUTE~INFINITE 需 `mekanism_extras`；BLAZE~SINGULARITY 需 `avaritia` |
| `planting_cutting` | **BASIC~ULTIMATE 需 `mekmm`**（仅经 plant_factory 路径）；其余同 cutting |

所以判据必须放在 `(MekCkFactoryType, MekCkFactoryTier)` 上，例如新增
`MekCkAvailability.isAvailable(type, tier)`，而不是塞进 tier 枚举。

---

## 3. ⚠️ 更重要：「有没有配方引用了缺失 mod」是错误判据

我做过两版静态统计，**都是错的**，你若照搬会得到错误结论：

**第一版错误**：按配方文件名里出现的档位名归因 →
`planting_cutting` 一个工艺的 `mekmm` 依赖被算到了所有 12 个档位上。

**第二版错误**：按 (工艺 × 档位) 归因，得出"planting_cutting BASIC 档不可用"——
但 `combining/` 下同时存在两条升级路径：

```
basic_planting_cutting_factory_from_plant_factory.json   mainInput = mekmm:basic_planting_factory   ← 依赖 mekmm
basic_planting_cutting_factory_from_cutting_factory.json  （不依赖 mekmm）                                  ← 可用
```

**只要存在任意一条不依赖缺失 mod 的合成路径，该方块就能造出来。**

### 正确的判据：配方图可达性

以 `mekck:<tier>_<type>_factory` 为起点节点，沿"某配方所需外部 mod 均已安装"
的边做 BFS/DFS，能抵达的方块即视为可用。

- 输入：`data/mekck/recipes/**/*.json` + 运行环境实际已加载的 mod 集合
- 节点：配方 `output` 里的物品
- 边：某配方的**全部** `mainInput`/`extraInput`（含 tag 展开）都在已安装 mod
  提供的物品集合内时，建立 `output ← inputs` 的可达边
- 外部 mod 缺失时，其提供的物品**不出现在**可用集合中

我估算的 44/84 是**下界**（只看了同名升级配方，没展开 tag 和跨工艺路径）。
真实数字必须由可达性分析得出，我之前的数字不要采信。

> **⚠️ 2026-09-29 降级（经同伴指正）**
>
> 上面这套可达性分析**不是条件配方的前置条件**，我原先把它当成前置是错的。
> 二者的区别：
>
> | | 需要可达性分析 | 需要知道"已装 mod 集合" |
> |---|---|---|
> | 条件化**方块**（隐藏） | 是 | 是 ← 前置卡点在此 |
> | 条件化**配方**（顶层 `conditions`，见 §4） | **否** | **否** |
>
> 条件配方把判断**推迟到运行期**，Forge 对每个配方独立判定其 `conditions`，
> 正确性与"最终哪些方块能造出来"无关。也就是说，逐配方加条件本身
> 就是「对任意 mod 组合都安全」这个判据，因此**不需要任何环境信息**。
>
> 我原先"手上没有完整 mod 清单所以做不了"这个理由，只在可达性分析那条路上
> 成立；换成条件配方后前提消失。**本节降级为独立的后续线**：
> 用途仅是"给玩家提示哪些方块当前造不出来"（JEI/清单灰显），
> 排在阶段 1 与条件配方之后，**不要作为前置阻塞任何工作**。


---

## 4. 建议的实现形态

> **2026-09-29 实测更正：本节原有内容有两处错误，且推荐的形态不是最优解。**
>
> 原先声称 `forge:conditional` 接受顶层 `{"conditions": [...], "recipe": {...}}`。
> **这是错的。** `ConditionalRecipe.Serializer.fromJson` 只读 `recipes` 数组；
> `conditions` / `recipe` 是**数组内每个 holder 对象**的字段
> （`ConditionalRecipe.Finished.save`：`holder.add("conditions", …)` / `holder.add("recipe", …)`）。
> 当初用 `javap` 读常量池时把 `ConditionalRecipe` 与其 `Serializer` 的字段混在了一起。
> 正确形态：
>
> ```json
> { "type": "forge:conditional",
>   "recipes": [ { "conditions": [ … ], "recipe": { … } } ] }
> ```
>
> 仓库里已有的 8 个条件配方（`createcafe_crushing/`、`createcafe_milling/`）用的就是这个形态。
>
> **但实际采用的是更好的方案：不用包装，直接在配方顶层写 `conditions`。**
> 证据是 Forge 的 vanilla 补丁
> `patches/net/minecraft/world/item/crafting/RecipeManager.java.patch`：
>
> ```java
> if (entry.getValue().isJsonObject()
>         && !CraftingHelper.processConditions(entry.getValue().getAsJsonObject(), "conditions", this.context)) {
>     LOGGER.debug("Skipping loading recipe {} as it's conditions were not met", resourcelocation);
>     continue;
> }
> ```
>
> 这个检查在**任何序列化器运行之前**执行，对每个配方都生效。因此：
>
> - 无需 `forge:conditional` 包装，不嵌套、不重排缩进，diff 最小
> - 对「未知配方类型 / 未知物品 / 写错 schema 的 conditions」三类失败**同时有效**
> - 条件数组语义为 AND（`CraftingHelper.processConditions` 遇首个 false 即返回 false）
>
> 采用的写法：
>
> ```json
> { "type": "mekck:sawing", "…原内容不动…",
>   "conditions": [ { "type": "forge:mod_loaded", "modid": "youkaishomecoming" } ] }
> ```
>
> 已实施：454 个配方文件（提交 `5a36aa4`）。完整依据见
> `docs/audit/2026-09-29-conditional-recipe-report.md`。

<details>
<summary>以下为原始（部分错误）记录，保留以备追溯</summary>

Forge 1.20.1 有现成的条件加载体系（`javap` 核实）：

```
net.minecraftforge.common.crafting.ConditionalRecipe        字段: conditions + recipe   ← 错：这是内层 holder 的字段
net.minecraftforge.common.crafting.conditions.ModLoadedCondition   NAME = forge:mod_loaded, 字段 modid
net.minecraftforge.common.crafting.conditions.ItemExistsCondition  字段 item
net.minecraftforge.common.crafting.conditions.AndCondition / OrCondition / NotCondition
```

配方层（零 Java 改动）：

```json
{
  "type": "forge:conditional",
  "conditions": [ { "type": "forge:mod_loaded", "modid": "youkaishomecoming" } ],
  "recipe": { "type": "mekck:sawing", "…原内容不动…" }
}
```

> **2026-09-29 已确认（字节码常量池证据，无需实机验证）**
>
> | 项 | 值 | 证据来源 |
> |---|---|---|
> | 配方 type | `forge:conditional` | `ForgeMod` 常量池 `#588=forge` + `#1048=conditional` |
> | 条件 type | `forge:mod_loaded` | `ModLoadedCondition.NAME` 静态初始化 |
> | 条件 type | `forge:item_exists`（字段 `item`） | `ItemExistsCondition` |
> | 包装字段 | `conditions` / `recipe` | `ConditionalRecipe$Serializer` 常量池 #95 / #103 |
> | 条件字段 | `modid` | `ModLoadedCondition$Serializer` 常量池 #22 |
>
> 组合条件用 `forge:and` / `forge:or` / `forge:not`。
> **原先标注的「未实测项」已关闭**——三个字符串全部来自 `javap` 常量池，
> 不需要「改一个配方启动游戏」来验证。

</details>


方块层：Forge 支持在 `RegisterEvent` 里按 `ModList.isLoaded()` 条件注册，
但那会让**方块 ID 集合随环境变化**（同存档装/卸 mod 会丢方块）。
mekck 的 `mekckfactory` 命名空间方块若未进过正式存档，可接受。

---

## 5. 文件边界（避免我们撞车）

我已提交（`b350537`）：

- `block/BioreactorBlock.java`、`blockentity/CentralKitchenBlockEntity.java`
  ——审查发现的三个静默丢数据缺陷，与本项目无关

### 5.1 条件配方：由**我**做（已确认分工）

条件加载的批量改造（引用外部 mod 的配方，加 `forge:mod_loaded`）由我执行。

理由：纯 JSON 批量改，与你的 Mixin 注入零耦合；你阶段 1 是 7 任务 37 步，
Task 1–3 才刚落地，不再加负担。

**文件边界（已与你对齐）**：

```
我只碰   src/main/resources/data/mekck/recipes/**
          例外：creative_upgrade_from_49_foods.json ← 你的 Task 8 要改，我避开
我不碰   src/main/java/cn/ism/mekck/factory/**      ← MekCkFactoryRegistration / MekCkFactoryTier
          src/main/java/cn/ism/mekck/upgrade/**
          src/main/java/cn/ism/mekck/mixin/**
          src/main/resources/{assets,data}/mekck/ 的非配方部分
```

**执行前先实测**：`ConditionalRecipe` 自身的 `type` 值（推测 `forge:conditional`）
`javap` 未取到。我会先拿**一个**配方改动 → 启动实例 → 看日志确认无
`Parsing error`，再批量跑。**不会拿 924 个文件赌一个未验证字符串。**

> **2026-09-29 已完成（提交 `5a36aa4`）**——实际不用等实机验证，Forge 的
> `patches/net/minecraft/world/item/crafting/RecipeManager.java.patch` 里有直接证据，
> 见 §4。更正：924 是日志行数，**去重后是 462 个不同配方**（资源加载跑两轮），
> 其中 422 个在本仓库、40 个来自用户世界存档的 `mekck_planting` 数据包。
> 已条件化 454 个文件（422 个报错 + 32 个仅含外部 mod 的 tag 引用的静默失效）。
> 详见 `docs/audit/2026-09-29-conditional-recipe-report.md`。

### 5.3 交给你的 Task 8：一个我这边查出的额外问题

`creative_upgrade_from_49_foods.json` 我按约定避开了，但需要提醒：

它的 `"type": "avaritia:shapeless_table"` 指向 **Avaritia** 的配方序列化器，
而 Avaritia 从未在 mekck 的 `mods.toml` 中声明为依赖，实例里也没装。
所以**只把产物从 `mekanism_extras:upgrade_creative` 换成 `mekck:upgrade_randomize`
并不能修好这个配方**——`type` 仍会解析失败，日志照样报
`Invalid or unsupported recipe type 'avaritia:shapeless_table'`。

这是改造后按实例 mod 集合模拟条件判定，**唯一剩下的失败项**。处理方式二选一：
把 `type` 换成 mekck 自有的表格式配方类型（或原生 `minecraft:crafting_shaped`），
或者按 §4 的写法给它加顶层 `conditions` 门控 Avaritia。

### 5.2 可达性分析：独立后续线，不阻塞任何人

用途仅是"给玩家提示哪些方块当前造不出来"（JEI 灰显 / 清单标注），
需要"已装 mod 集合"作为输入。排在阶段 1 与条件配方之后，**不作为前置**。


---

## 6. 顺带记录的、已确认不需要处理的

- `MekckFactoryRegistration` 已具备 `AttributeEnergy` / `AttributeStateFacing` /
  `AttributeUpgradeSupport` / `AttributeGui`（某份 spec 说缺这三个，已过时）
- 三个客户端崩溃（`this.tier is null` 等）是 9-28 03:08~03:14 的**历史记录**，
  最后一次之后（03:27）已正常启动 6.4MB 日志无崩溃，不必追
- `mekanism_extras` 1.5.0 自身有 120 条战利品表解析失败
  （`mekanism_extras:infinite_planting_factory` 等 BlockItem 未注册成功），
  属 Extras 与 Mek 10.4.16.80 的版本耦合，mekck 侧无法修

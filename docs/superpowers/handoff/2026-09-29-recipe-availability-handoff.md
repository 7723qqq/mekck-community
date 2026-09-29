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

---

## 4. 建议的实现形态

Forge 1.20.1 有现成的条件加载体系（`javap` 核实）：

```
net.minecraftforge.common.crafting.ConditionalRecipe        字段: conditions + recipe
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

> 唯一未实测项：`ConditionalRecipe` 自身的 `type` 值（我推测是 `forge:conditional`，
> `javap` 未取到）。改一个配方启动一次看日志即可确认，30 秒。

方块层：Forge 支持在 `RegisterEvent` 里按 `ModList.isLoaded()` 条件注册，
但那会让**方块 ID 集合随环境变化**（同存档装/卸 mod 会丢方块）。
mekck 的 `mekckfactory` 命名空间方块若未进过正式存档，可接受。

---

## 5. 文件边界（避免我们撞车）

我已提交（`b350537`）：

- `block/BioreactorBlock.java`、`blockentity/CentralKitchenBlockEntity.java`
  ——审查发现的三个静默丢数据缺陷，与本项目无关

我**建议由你来做**可达性分析与条件加载，理由：

1. 你的阶段 1 本来就在改 `MekCkUpgradeTypes` / `TileComponentUpgrade`，
   同属升级与配方这条线
2. 我手上没有实例环境的**完整 mod 清单**（实例只有 14 个 mod，
   真实整合包可能有几十个），可达性分析需要准确的已装 mod 集合
3. 上面 §3 的方法论问题需要**先验证再实施**，我不想在你已经推进的分支上
   引入一个未经可达性验证的静态表

如果你更希望我接手，请告诉我，我会**只新建 `MekCkAvailability.java`**，
不改 `MekCkFactoryRegistration` / `MekCkFactoryTier` 的现有契约。

---

## 6. 顺带记录的、已确认不需要处理的

- `MekckFactoryRegistration` 已具备 `AttributeEnergy` / `AttributeStateFacing` /
  `AttributeUpgradeSupport` / `AttributeGui`（某份 spec 说缺这三个，已过时）
- 三个客户端崩溃（`this.tier is null` 等）是 9-28 03:08~03:14 的**历史记录**，
  最后一次之后（03:27）已正常启动 6.4MB 日志无崩溃，不必追
- `mekanism_extras` 1.5.0 自身有 120 条战利品表解析失败
  （`mekanism_extras:infinite_planting_factory` 等 BlockItem 未注册成功），
  属 Extras 与 Mek 10.4.16.80 的版本耦合，mekck 侧无法修

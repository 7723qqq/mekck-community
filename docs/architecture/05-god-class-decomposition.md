# 05 · 巨型类拆分设计

> 描述源码：`blockentity/`、`ae2/`、`machine/`、`command/` 下的 1500+ 行类。
> 范式（沿用既有成功先例 `SimpleMachineFluids` / `SimpleMachineRecipes` / `GrindingRecipes`）：
> **伴生类不持有状态**，状态一律 `be.xxx()` / `tile.xxx()` 取；
> **方法搬迁规则**：一个辅助方法**只有全部调用点都在搬移集合内**时才跟着搬，否则留在 BE 由伴生类回调。
> **行为型断言的判据落点随之下移时，优先保留同名转发**（先例 `TestGrindingRollArithmetic`）。

## 一、批次 A/B：`blockentity/SimpleMachineBlockEntity`（2582，覆盖 17 个方块）

| 伴生类（暂名） | 成员区间 | 职责 | 批次 | 风险 |
|---|---|---|---|---|
| `SimpleMachineOrders` | 1353–1440 | 订单 set/get + 指纹 | A | 低 |
| `SimpleMachineSideConfig` | 1163–1210 | 侧配编解码 | A | 低 |
| `SimpleMachineEnergy` | 290–415 | 能量 / `ContainerData` | A | 低 |
| `SimpleMachineNbt` | 2652–2790 | NBT 三件套 | B | 中 |
| `SimpleMachineAutoIo` | 2239–2460 | 自动 IO 目标槽 | B | 低 |
| `SimpleMachineCapabilities` | 2559–2620 | 能力暴露 | B | 低 |
| **留 BE**：tick 主循环 1214–1352、配方查找入口、酒馆批次 1678–2126、AE2 拉取输入构造 878–1142 | — | 与 tick 状态/AE2 强耦合 | — | — |
| **暂缓**：升级安装/追踪 415–760 | — | 触 `PowerSlotUtil` 共享件 | — | — |
| ✅ `SimpleMachineNetworkPull`（**已实施 2026-10-05**） | AE2 持续补料输入规格构造簇：`simpleSingleInput` / `multiIngredient` / `curd|juicer|rice|sushi|winery|fermenter|teaPullInputs` 等 10 个方法 | 唯一外部调用点是 `getNetworkPullInputs()`（`@Override`，留 BE）的 switch 分派；伴生**无状态**，只持 `be` 引用 | 已做 | 低 |

> ⚠️ **行数口径更正**：本文与旧文档记 `SimpleMachineBlockEntity` 为 2582 行，那是 `Measure-Object -Line` 的**少计**（会跳过部分行，与物理行数差约 242）。
> 按**物理行数**：抽取前 **2824** → 抽取后 **2586**（净 −238）；新类 `SimpleMachineNetworkPull` **295 行**。
> 度量行数请用 `[System.IO.File]::ReadAllLines($p).Length`，不要用 `Measure-Object -Line`。
> 为抽取把 2 个方法由 `private` 放宽到**包级**（`recipeTypeOf`、`juicerHasBottleRequiringRecipe`），未放宽到 `public`。

> ⚠️ **存档契约**：该类根 NBT 键名原样不动（`Items`/`Energy`/`Progress`/`SideConfig`/`FluidSideConfig`/`OrderRecipeId`/`OrderQuantity`/`OrderCompleted`/`MeOrderEnabled`/`CustomName`，**红石键是 `Redstone` 而非 `RedstoneControl`**）。详见 `legacy/SimpleMachine.md`。

## 二、批次：`ae2/MekckAe2`（2515）

| 伴生/顶层类 | 成员区间 | 职责 | 风险 |
|---|---|---|---|
| `ae2/FactoryGridHost`（**暂缓**） | **1185–2185**（约 1001 行） | 内嵌 AE2 网格集成（原内嵌类升顶层） | 高（AE2 运行期） |
| `ae2/grid/Ae2PowerStorage` | 1502 | 电力存储适配 | 中 |
| `ae2/grid/CraftingProvider` | 2125–2634 | 自动加工样板上报 | 高 |
| `ae2/PatternBuilders` | 2191–2600 | 样板构建器 | 中 |
| `ae2/grid/` 内 records | `CachedIndex`/`PatternEntry`/`InputSpec`/`AeJob`/`Hardening` | 数据载体 | 低 |
| `MekckAe2`（保留） | 118–177、177–580、580–928 | 门面：生命周期、网络查询、拉取注入、反射写订单 | — |

> ⚠️ **不可改**：`setOrder` / `getOrderRecipeId` / `getOrderQuantity` 三个成员名（§三 详见 `03-ae2.md`）。
> 铁律：`MekckAe2` 里**按旧 BE 类型派发的 `instanceof` 分支**，在对应机器迁到 Mek 原生体系时必须**删除**（不是留着）。

#### ⚠️ 抽取可行性审计（2026-10-05，**结论：暂缓**）

对 `FactoryGridHost` 做了成员依赖审计，**它不是纯移动**，代价比预估大：

| 项 | 实况 |
|---|---|
| 类范围 | **1185–2185（约 1001 行）**，不是早先记的 1185–2124/1417 行 |
| 需由 `private` 放宽到包级的成员 | **约 20 个**：6 字段 + 12 方法 + 2 私有嵌套类型（`PatternEntry`/`AeJob`）+ 反向耦合的父类侧 2 字段 |
| 遗漏的一整类 | `refreshPatterns()` 里**未限定名**调用的 10 个外层 `private static` 样板构建方法：`buildCooking/SimpleMachine/Cutting/Skewering/Grilling/CookingPot/SimpleSingleOutput/Grinding/Kitchen/SandwichPatterns` |
| 反向耦合 | `MekckAe2` 直接读 `FactoryGridHost` 的 `private` 字段 `mainNode`（12 处）、`job`（2 处）——嵌套私有互访特权随提取消失 |
| 保持「方法体逐字不变」的前提 | 需要 `import static cn.ism.mekck.ae2.MekckAe2.*` 一串静态导入（否则每个调用点都要改成 `MekckAe2.xxx(...)`） |
| 连带护栏重指 | `ae2/TestAe2Hardening`、`network/TestAe2PanelThrottle` 读取 `MekckAe2.java` 里的 `processJob`/`networkAvailSnapshot`/`toggleAutoItem`/`loadFromNBT`/`ownerBusy`/`entriesForPanel`（会随类搬走）——须改为拼接读取新文件，**断言不得削弱** |

**暂缓理由**：① 需放宽约 20 个成员（削弱封装，与「高内聚」目标相抵）；② 静态导入方案是为「逐字不变」付出的设计代价；③ AE2 路径属本仓**最高风险区**（曾出现「编译 + 测试全绿但运行期坏」），而当前**无实机验证环境**。
**前置条件**：先补齐 `MekckAe2` 的运行期验证手段，再按上表逐项做。

> 📌 **方法论**：本次初次审计用正则统计「外层 private 成员被引用」，**漏掉了未限定名的方法调用与私有嵌套类型**（37 个成员里只匹配到 8 个），据此得出的「耦合很小」结论是错的。
> **判「能否纯移动」不能用声明级正则**，必须让编译器参与（先尝试移动，编译错误即真实耦合清单）。

## 三、批次 10：`machine/MekCkMachineTile`（2141）

见 [`02-machine-core.md`](02-machine-core.md) §五。**触共享基类，需实机验证**。

## 四、批次 11：`blockentity/CentralKitchenBlockEntity`（1732）

| 伴生类（暂名） | 成员区间 | 职责 | 风险 |
|---|---|---|---|
| `CentralKitchenStorageView` | 362 | 存储浏览快照同步 | 低 |
| `CentralKitchenOrders` | 381–846 | 订单 place/preview/reserve/buffer + tickOrders | 中 |
| `CentralKitchenNetwork` | 1136–1320 | 网络拉取 + 物品/流体/气体侧配 | 中 |
| `CentralKitchenHeat` | 1371–1400 | 定向传热 | 低 |
| **留 BE**：自动模式/线程 846–1136 | — | **线程所有权必须留在 BE**，伴生类只放纯逻辑 | — |

> 关联护栏：`TestCentralKitchenGuards`、`TestCentralKitchenThreadPersistence`、`menu/TestKitchenStorageBrowserSync`、`kitchen/TestKitchen*`（3）。

## 五、批次：`blockentity/SmartCookingPotBlockEntity`（1567）

| 伴生类（暂名） | 成员区间 | 职责 | 风险 |
|---|---|---|---|
| `CookingPotRecipes` | 564–1074 | 配方匹配/回溯/流体/消耗/产出（**若与烹饪工厂可共用则收益最大**，参照 `GrindingRecipes` 先例） | 中 |

> **刻意少拆**：该机器后续要迁 Mek 原生，大量成员会被迁移整段删除，过度拆分是浪费。
> 热/内容变更 82–177、能量 269–394、tick/流体转换 394–564、升级/红石 1074–1200、网络/ME 订单 1204–1460、NBT 1469–1599 **本轮不动**。

## 六、批次 12：`command/PlantingRecipeGenerator`（1543）

| 伴生类（暂名） | 成员区间 | 职责 | 风险 |
|---|---|---|---|
| `planting/GeneratorFs` | 271–450 | 目录清理/迁移/黑名单 | 低 |
| `planting/RecipeJsonWriter` | 524–1006 | 配方 JSON 生成 | 低 |
| `planting/BotanyPotsCollector` | 1023–1314 | BotanyPots 采集/生成 | 低 |
| `planting/LootRoller` | 1314–1633 | 战利品表求值定产物 | 低 |

> 纯 dev 命令，**无存档风险**；护栏 `TestMinorDefectGuards` 读该文件路径，需改落点。

## 七、其余大机器（不单独精拆）

`IceMakerBlockEntity`(1279)、`ChocolateCannonBlockEntity`(1251)、`SkeweringMachineBlockEntity`(1099)、`GrillBlockEntity`(1074)、`IceFactoryBlockEntity`(1007)、`NutRoasterBlockEntity`(915)：**不单独精拆**；只在「单机与工厂共用逻辑」时按 `GrindingRecipes` 先例去重。`client/MekCkOutlineRenderer`(715) 与 `menu/CentralKitchenMenu`(678) 随各自批次处理。

## 八、排序原则

1. **先纯移动、后触共享基类**：批次 A/B/D/E/F 基本是纯移动；`MekckAe2` 需保反射成员名；批次 10 触共享基类，**必须有实机验证环境**才做。
2. **先 legancy→Mek 迁移、后拆 legacy 巨型类**：迁移会自然删除 god 类成员，先迁后拆可少做无用功。
3. **登记表冲击**：每批都可能改红源码形态护栏，处置只允许三类（改代码 / 改落点 / 收缩登记表+注释），**禁止放宽判据**。

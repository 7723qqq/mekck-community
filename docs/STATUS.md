# mekck 当前状态汇总

- 最后更新：**2026-10-05**（**模块化重构轮**：打断三处循环依赖 + `util` 归位 + 3 个巨型类拆分；提交 `1c345bd`…`f0119e1`，详见下方「本轮」§〇）
- 审查基线：HEAD `f0119e1`（模块化重构 5 个里程碑提交）之后的工作区
- 验收口径：`./gradlew --offline test` + `./gradlew build`
  **本轮实测：100 套件 / 739 用例 / 0 失败 / 0 错误 / 0 跳过**
  （较前一轮的 99/729 多出的是**接手前既有**的 `TestMigrationCompleteness` 改动；本重构自身未新增用例）

> ⚠️ **前一轮（同日）的基数更正**（详见下方「前一轮」§〇）：
> - 记「62 套件 / 457 用例」——**实测 99 / 729**；
> - 记「14 台待迁」——**实际 13 台**（陈化窖早已迁完）；
> - 记「`MekCkMachineTile` 未实现 `INetworkPullable`」——**早已实现**（`MekCkMachineTile:123`）；
> - 记「本机 dev 环境起不来 ⇒ 无法实机验证」——那说的是 `runClient`；
>   另有一套**可启动的独立客户端安装**（见 §五.9），实机验证是可行的。

本文是**单一入口**：当前状态、未修项、环境注意事项都在这里。
全部文档清单见 [`README.md`](README.md)；第三轮及更早的轮次叙事见 §一、§七。

> ⚠️ **本文档曾落后代码 14 个提交**（第三轮收尾写到 `bb00171`，实际 HEAD 已到 `fefa065`），
> 期间发生了大段重构。**上一轮已因此付出代价**：§二 记录的「I1 已关闭 / 已做完」
> 是一条**虚假声明**——全仓检索不到它引用的那份报告，代码里
> `SimpleMachineMenu.getEnergy()` 也仍是裸的 `data.get(DATA_ENERGY)`。
> 那一版甚至把错误结论写进了本文件，让下一个 agent 照着它跳过了一个真缺陷。
> **判据：写「已修」时必须同时给出提交号与代码位置，否则一律按未修对待。**
>
> **本轮又踩了同一个坑的另一种形态**：不是「写了假结论」，而是**照抄过时的基数**
> 就开工。动手前逐项实测（套件数、待迁台数、缺口状态、环境可用性）至少省下了一圈返工。
> **教训：文档里的数字与「缺口清单」，动手前必须回仓库核实。**

---

## 〇、本轮（2026-10-05）—— 模块化重构：破环 + util 归位 + 3 个巨型类拆分

**结论：编译 + 100 套件 / 739 用例 0 失败 + `./gradlew build` 成功（产物 `mekck-1.0.0.jar`）。
本轮不含实机结论**；涉及运行期风险与存档契约的改动**刻意未做**（见 §三）。设计文档见 [`architecture/README.md`](architecture/README.md)。

| 提交 | 内容 | 文件数 |
|---|---|---|
| `1c345bd` | `refactor(structure)`：打断三处循环依赖 + util 越界类归位 | 61 |
| `0bf993a` | `refactor(blockentity)`：`SimpleMachineBlockEntity` 抽取 AE2 补料输入簇 | 2 |
| `a52b511` | `refactor(command)`：`PlantingRecipeGenerator` 拆分为 4 个伴生类 | 6 |
| `29c9ccc` | `refactor(blockentity)`：`CentralKitchenBlockEntity` 抽取 4 个伴生类 | 6 |
| `f0119e1` | `docs(architecture)`：新增 `docs/architecture/` 模块架构文档集 | 20 |

### 一、依赖结构：三处循环依赖全部打断（以 import 计数实测，非感觉）

| 边 | 前 → 后 | 手段 |
|---|---|---|
| `util → block` | 14 → **0** | `TierInstallerHandler` 迁入 `block/`（13 条 block import 变同包消除） |
| `machine → block` | 6 → **0** | 新增 `machine/IFactoryTierProvider`，6 个工厂方块实现、tile 的 `tierFromBlock()` 只认接口 |
| `machine → blockentity` | 1 → **0** | 生长状态码上移为 `machine/plantingcutting/PlantingCuttingStatus` |

`util` 越界依赖 **32 → 2**（仅剩 `→compat`：`ClientPacketBridge`→`GuideMECompat`、`RecipeCache`→`TavernBarrelCompat`）。
另归位 11 个类：`TierInstallerHandler`→`block/`；`RecipeInputMatcher`/`BioreactorFuels`→`recipe/`；
`FreezeEvents`/`FreezeAiReaper`/`ChocolateCannonLifecycle`/`ChocolateTagScanner`→`event/`；
`ChocolateCannonReservations`→`blockentity/`；`ColdBrewHelper`→`item/`；`AE2InputSpec`/`NetworkPullHelper`→`ae2/`。

> ⚠️ **两处与原计划不符的实测更正**（原计划见重构方案，已按实测改）：
> ① 原计划「批次 1：删死代码 `ChocolateCannonLifecycle`/`ChocolateTagScanner`」**前提不成立** ——
> 两者是 `@Mod.EventBusSubscriber` 事件订阅器；同批核验还发现 `FreezeEvents`（注解发现）、
> `MaxLootRandom`（同包简单名调用）亦为假死 ⇒ **`util/` 内零死代码**。已改为**归位 `event/`**，不删。
> ② `TierInstallerHandler` 目的地由 `upgrade/` 改为 `block/` —— 迁 `upgrade/` 会新增
> `upgrade → block` 13 条，与既有 `block → upgrade` 11 条成环；迁 `block/` 则新增边为 0。

### 二、巨型类拆分（**物理行数**；`Measure-Object -Line` 会少计约 240 行，勿用）

| 类 | 前 → 后 | 新伴生类 |
|---|---|---|
| `blockentity/SimpleMachineBlockEntity` | 2824 → **2586** | `SimpleMachineNetworkPull`（295，无状态） |
| `command/PlantingRecipeGenerator` | 1666 → **615** | `command/planting/{GeneratorFs,RecipeJsonWriter,BotanyPotsCollector,LootRoller}` |
| `blockentity/CentralKitchenBlockEntity` | 1868 → **1816** | `CentralKitchen{HeatTransfer,StorageSync,NetworkPull,SideConfig}` |

- 范式沿用既有 `SimpleMachineFluids`/`SimpleMachineRecipes`：**伴生类不持状态**，状态一律 `be.xxx()` 取。
- **反射契约未动**：`setOrder`/`getOrderRecipeId`/`getOrderQuantity` 原样留在 BE 上（`MekckAe2` 按名反射取用）。
- 护栏只改**判据落点**（`TestMinorDefectGuards` 读取路径、`TestFreezeAiRecovery` 随类迁包），**无一条断言被放宽或缩小扫描面**。

### 三、刻意未做（附理由，非遗漏）

| 项 | 理由 |
|---|---|
| `ae2/FactoryGridHost`（约 1001 行）升顶层 | 经审计**非纯移动**：需放宽约 20 个成员（含 10 个 `build*Patterns` 方法 + `mainNode`/`job` 反向耦合）、需静态导入维持「逐字不变」、且连带 2 条 AE2 护栏要重指。AE2 属本仓最高风险区且**当前无实机验证** |
| `CentralKitchen` 订单生命周期簇 | 护栏对 `private` 签名硬断言，搬动只能靠放宽断言 ⇒ 按搬移规则留在 BE |
| `machine/MekCkMachineTile` 拆分（批次 10） | 触 6 家族 ×12 档共享基类，计划本身要求实机验证 |
| legacy→Mek 迁移（批次 8x） | 需用户实机验收 |
| `util/io`、`stack`、`fluid` 子包 | 纯外观分组，约 99 处 import 改动、零破环收益 |

### 四、方法论教训（本轮新增）

- **判「能否纯移动」不能用声明级正则**：本轮先用正则统计「外层 private 成员被引用」，在 37 个成员里
  只匹配到 8 个，据此误判 `FactoryGridHost`「耦合很小、可安全抽取」；实际漏掉了
  **未限定名的方法调用**（10 个 `build*Patterns`）与**私有嵌套类型**。
  **正确做法：让编译器参与** —— 先尝试移动，编译错误即真实耦合清单。
- **行数度量**：`Measure-Object -Line` 会少计（本仓实测差约 240 行）；用
  `[System.IO.File]::ReadAllLines($p).Length`。

---

## 〇、前一轮（2026-10-05）—— 阶段 3 单机迁移：电力研磨机样板

**结论：样板迁完，编译 + 729 用例 0 失败 + `./gradlew build` 成功。
实机验证（放置 / GUI / 投料 / 加工 / 升级卡 / 拆放 / 重启）由用户自行完成 —— 本记录不含实机结论。**

### 一、开工前实测更正的四条基数（见文首引述）

| 项 | 文档记的 | 实测 |
|---|---|---|
| 测试规模 | 62 套件 / 457 用例 | **99 套件 / 729 用例** |
| 待迁单机 | 14 台（含陈化窖） | **13 台**（陈化窖 `WineCellarBlockEntity` 早已是 `TileEntityConfigurableMachine`） |
| `INetworkPullable` 缺口 | 未实现 | **已实现**（`MekCkMachineTile:123`），口径 §2.4 已过时 |
| 实机验证可行性 | 「dev 环境起不来」 | 那指 `runClient`；另有可启动的独立客户端见 §五.9 |

### 二、仓库卫生：813 个 CRLF 假变更

工作区 813 个文件显示为已修改，`git diff --stat` 呈现 **80831 增 / 80831 删** ——
逐字节核对确认**没有任何真实内容改动**，全部是行尾符 CRLF↔LF
（`git diff --ignore-cr-at-eol` 返回空）。已 `git checkout -- .` 还原，工作区恢复干净。

> **判据**：看到「增删行数完全相等」的大规模 diff，先怀疑行尾符，别急着审代码。

### 三、样板迁移：电力研磨机（`mekck:electric_grinding_machine`）

| 文件 | 动作 |
|---|---|
| `machine/grinding/GrindingMachineTile.java` | **新建**：`extends TileEntityConfigurableMachine` |
| `block/ElectricGrindingMachineBlock.java` | **重写**：`extends BlockTile`，144 → 约 100 行（朝向/ticker/掉落/GUI 全交 Mek） |
| `menu/ElectricGrindingMachineMenu.java` | **重写**：333 → 约 100 行，`extends MekanismTileContainer`，**删掉 5 个手写 `IVirtualSlot` 嵌套类** |
| `client/ElectricGrindingMachineScreen.java` | **重写**：`GuiConfigurableTile`，删掉自摆的侧配/升级/红石 tab 与两套自绘窗口 |
| `registry/MekCkFactories.java` | 注册换 Mek 三件套；`UniversalCuttingMachine` 里补 `register(bus)` |
| `menu/slot/MekCkSlots.java` | 新增 `GrindingMachine` 槽位表（坐标的唯一出处） |
| `data/mekck/loot_tables/blocks/electric_grinding_machine.json` | **新增**（见下） |
| `blockentity/ElectricGrindingMachineBlockEntity.java` | **删除**（1048 行） |
| `ae2/MekckAe2.java` | 删掉 4 处本机的 `instanceof` 分支 |
| `network/SideConfigPacket` / `upgrade/UpgradeInstallHandler` | 各删一条分支（侧配与升级已交 Mek） |

**配方语义逐字未改**：石磨 → 筛粉 → 绞碎 → mekck 磨粉的查找顺序、按 id 反查的类型白名单、
输入匹配只看 `ingredients[0]`、订单数量下界与取消清零。

> ⚠️ **上面这句在写下时是错的，后来才修好**（如实留在这里当判据）：
> 迁移第一版的 `grindingOutputs()` 用的是 `recipe.getResultItem()`（单一定值产物），
> 而**石磨配方的产出是 `List<MillstoneOutput>`、每项各带一个 `chance`** ——
> `getResultItem` 对它返回空栈 ⇒ 机器会判成「无产物」**永不开工**，概率性也整个丢失。
> 靠人眼在写完之后才发现，不是护栏抓的。现已改为共用
> `GrindingRecipes.rollOutputs` / `canFitWorstCase`，并补了护栏
> `TestGrindingRollArithmetic#grindingMachinesNeverTakeASingleFixedResult`（变异测试已验证会响）。

### 三之二、研磨逻辑去重：单机与工厂共用一份（`GrindingRecipes`）

上一版的问题不只是「手写」，还有**同一套语义写了两份**：单机研磨机与研磨工厂
各自实现了一遍配方查找、概率掷骰、订单推进。新增 `machine/grinding/GrindingRecipes.java`
把它们收成一份，两边都只留委托：

| | 改前 | 改后 |
|---|---|---|
| `GrindingFactoryExecutor` | 598 行（含全部实现） | **462 行**（全部改为 `GrindingRecipes.xxx` 调用） |
| `GrindingMachineTile` | 各自实现一份 | 调 `GrindingRecipes` |
| `GrindingRecipes` | — | **337 行**（唯一实现） |

>`TestGrindingRollArithmetic` 的 33 条断言直接调 `GrindingFactoryExecutor.clampChance` 等
>包级静态方法，所以那些位置**保留同名转发**而不是直接删 —— 让「实现只有一份」
>与「测试面不变」同时成立。

#### ⚠️ 去重时发现的一处既有分歧（**未修，如实记录**）

| | 支持几类配方 |
|---|---|
| `GrindingMachineTile`（单机） | **4 类**：石磨 / 筛粉 / 绞碎 / mekck 磨粉 |
| `GrindingFactoryExecutor`（工厂） | **1 类**：只有石磨 |

指南 `mekckguide/machines/grinding.md` 把「研磨工厂」描述为「电力研磨机的多线程版本」，
按此应当同样支持四类。**这不是本轮引入的**（工厂执行器迁到 Mek 体系时就只接了石磨），
但本轮去重时暴露了出来。**没有顺手改**：改它等于给 12 级工厂加行为，
是独立决策，需要先确认是「漏接」还是「有意为之」。

### 四、迁移实际要动的东西（原以为只有「换基类」）

动手前低估了范围。**每台机器都要过这 10 项**，已写进口径 §12.4：

1. 新建 tile（**无档位单机不能用 `MekCkMachineTile`** —— 那是工厂家族基类，`createExecutor()` 抽象）；
2. 方块换 `BlockTile` + `BlockTypeTile`：`withGui` / `withEnergyConfig` / `withSupportedUpgrades` /
   `AttributeStateFacing` / `ACTIVE` / `REDSTONE` / `SECURITY` / `INVENTORY`，**一个都不能少**；
3. 注册换 Mek 三件套，**成组 `register(bus)`**（漏一行 = 运行期「注册表里没有这个 id」，且编译通过）；
4. **补战利品表** —— 见下，这是本轮抓到的最重要一条；
5. 菜单换 `MekanismTileContainer`，删手写槽与 `ContainerData`（⇒ `WideDataSlot` 拆位那套作废）；
6. 屏幕换 `GuiConfigurableTile`，**删掉自摆的侧配/升级/红石 tab**（否则两套 tab 叠在一起）；
7. 屏幕补 `drawForegroundText`（不覆写 ⇒ 机器名与 Inventory 标签一个都不画）；
8. 删 `MekckAe2` 里本机的 `instanceof` 分支；
9. 槽下标会变（新槽序是**新的存档契约**）；
10. 同步护栏登记表（见 §五）。

#### ⚠️ 4.1 战利品表：不补就是破坏后什么都不掉

`BlockTile` 的掉落**走战利品表**，而旧机器是 `onRemove` 里 `saveToItem` + `Containers.dropItemStack`。
迁过去不补表 ⇒ **拆掉机器一件东西都不掉**（含方块本体）。
护栏 `TestFactoryLootTableSustainData` 当场抓住，已按陈化窖的表为模板补
`electric_grinding_machine.json`，逐键复制 `componentUpgrade` / `componentConfig` /
`EnergyContainers` / `Items` / `Progress` / 订单三键 / `MeOrderEnabled` / `CustomName`。

#### ⚠️ 4.2 原生版本标记：不写就是每次读档白跑一遍迁移器

`MekCkLegacyMachineNbt.isLegacy` 的判据是「存档里**没有** `MekCkNative` 键」。
tile 的 `load` 调了迁移器、而 `saveAdditional` 漏写这个键 ⇒ 它的存档**永远是旧格式**，
每次区块加载都重跑迁移器。迁移器幂等，所以**不损坏数据、没有任何功能性症状**
—— 只靠人眼极难发现（本轮实测踩到，已修：`GrindingMachineTile:379` 起）。

### 五、护栏处置：改红 17 条，分三类，**没有一条靠放宽判据通过**

| 类别 | 处置 | 实例 |
|---|---|---|
| **真缺陷（我引入的）** | 改代码 | 战利品表缺失（`TestFactoryLootTableSustainData`）；屏幕不画标签（`TestGuiInventoryLabels`）；原生版本标记漏写（新增护栏捕获） |
| **判据对象随迁移消失** | 改判据落点，断言的东西不变 | `getMachineFacing()` 随朝向交 Mek 而消失；`UpgradeSlot` 随升级交组件而消失 |
| **登记表/阈值随迁移收缩** | 同步收缩 + 注释写明「N → N-1」与原因 | `MENUS` 表、`POWER_SLOT_TARGET_*`、`KNOWN_VIOLATIONS`、`KNOWN_MIGRATION_DEBT`、`checked >= 8→7`、`seen >= 10→9` |

### 六、⚠️ 本轮新增护栏的**变异测试记录**（第三次撞上同一个坑）

新增 `everyTileThatMigratesLegacySavesMustAlsoWriteTheNativeMark`（钉 §4.2 那条不变量）。
**它自身连错两版，都是变异测试抓出来的**：

| 轮次 | 判据写法 | 注入变异后 | 问题 |
|---|---|---|---|
| 1 | `Files.readString(...).contains("TAG_NATIVE_VERSION")` | ❌ **绿（漏网）** | 匹配到了 **javadoc 里的解释文字**。**注释被当成了代码** |
| 2 | 改用 `TestSourceText.read`（剥注释）但仍 `contains("TAG_NATIVE_VERSION")` | ❌ **绿（漏网）** | 匹配到了**常量声明那一行** —— 「声明了」不等于「写出了」 |
| 3 | 正则 `put\w*\(\s*(TAG_NATIVE_VERSION\|"MekCkNative")\s*,` | ✅ **红** | 正确：要求「往 tag 里落这个键」的调用形态 |

⇒ **「字段名出现过」不等于「这件事做了」。** 这是本仓第三次记录同类教训
（前两次见 §六的 `clientBranchIsGatedBySideAlone` 与 `TestGuiInventoryLabels` 的
「豁免判据不能用全文子串匹配」）。**新加任何源码形态护栏，一律先跑变异测试；
且必须先在干净树上确认绿、再注入缺陷确认红。**

### 六之一、GUI 手绘的**结构性根因**已定位并切断

> 用户连续指出「你每次都说改，实际上每次还是手绘」。**这句话是对的**，而且我一直没找到根因
> —— 因为我每次只改**那一个屏的表皮**（坐标、尺寸、`dynamicSlots`），从没碰过结构。

#### 全仓 21 个屏的诊断

| 组 | 数量 | 情况 |
|---|---|---|
| A：走 Mek 原生 | 9 | 6 个工厂屏 + 烧烤架 + 切菜机 + 研磨机 |
| B：手绘 | **12** | 全部是待迁单机；手绘成分为 `GuiVirtualSlot` × 11 屏、自研侧配窗 × 9 屏、自研升级窗 × 8 屏 |

#### 根因：复用的入口被类型参数挡住了

本仓早有一套屏幕基类 `MekCkFactoryScreenBase`，里面已实现 `drawForegroundText`（机器名 +
「Inventory」标签）、竖直能源条、进度条 —— **但它的类型参数绑死在 `MekCkMachineTile`（带档位的工厂基类）上**：

```java
public abstract class MekCkFactoryScreenBase<TILE extends MekCkMachineTile, ...>
```

13 台单机是无档位的（`TileEntityConfigurableMachine`），**继承不了** ⇒ 每个单机屏只好把
那几件事各自重写。**实测有 15 个屏各写了一份 `drawForegroundText`。**

> ⇒ **这不是「没人愿意复用」，是复用的入口不可达。** 只看单台机器永远看不出这一点。

#### 本轮的处理：抽出 `MekCkContainerScreenBase`

新增 `client/MekCkContainerScreenBase.java`，类型参数只要求 Mek 的根 tile：

```java
public abstract class MekCkContainerScreenBase<
        TILE extends TileEntityMekanism & ISideConfiguration,
        MENU extends MekanismTileContainer<TILE>> extends GuiConfigurableTile<TILE, MENU>
```

收进基类的：`drawForegroundText`（两行字）、`addEnergyBar(...)`、`addNetworkPullTabs(pos)`、
构造器里的 `dynamicSlots = true`、`jeiLoaded()` 守卫。
`MekCkFactoryScreenBase` 改为继承它（工厂屏一行未改，行为不变）。

**首个受益者**：`ElectricGrindingMachineScreen` 370 → **142 行**，删掉了自己那份
`drawForegroundText` 与能源条实现。

**新增护栏**：`screensExtendingTheSharedBaseDoNotReimplementIt`
——继承共享基类的屏不许再重写基类已有的东西（变异测试已验证会响）。
同时把 `TestGuiInventoryLabels` 的两条判据从「看本文件有没有覆写」改为
**认继承**（否则「正确的复用」会被误判成「漏画」）。

### 六之一之二、⚠️ 我漏建的一个模块：AE2 能力（用户一句「你确定都建立了且绑定了？」查出）

用户问「你确定需要的模块都建立了且绑定了？」—— **答案是不确定，而且实测查出一处真缺口。**

#### 缺口：研磨机迁移时把 AE2 能力整份丢了

| | 实现的能力 |
|---|---|
| 旧 `ElectricGrindingMachineBlockEntity` | `MenuProvider` + **`INetworkPullable`** |
| 烧烤架（已迁，同为单机） | `MenuProvider` + **`INetworkPullable`** |
| **迁移第一版的 `GrindingMachineTile`** | **只有 `MenuProvider`** ⇒ AE2 的「网络拉料 / 自动补料 / 面板下单」**全部静默消失** |

**编译通过、当时 732 个测试全绿** —— 因为**没有任何护栏守「这台机器的能力清单」**。

#### 根因与修法（与 GUI 那次同构）

`INetworkPullable` 的通用实现在 `MekCkMachineTile`（工厂基类）里，所以 6 个工厂家族白得。
**单机没有对应基类** ⇒ 每台都得自己写一份，于是漏了。

⇒ 新增 `machine/MekCkNetworkPullableTile.java`（单机 tile 基类，提供 `INetworkPullable`
的通用实现，子类只答两个问题：处理哪几类配方、哪些槽参与拉料）。
`GrindingMachineTile` 改为继承它，**AE2 能力已恢复**（5 个消费点均能认出）。

#### 新增护栏

`TestAe2Hardening#migratedTilesKeepTheirAe2Capability` —— 扫 `machine/**/*Tile.java`，
凡继承 `MekCkMachineTile` / `MekCkNetworkPullableTile` 的，必须仍然满足
`INetworkPullable` 或 `IMekCkPorted`；外加一条钉子：`MekCkNetworkPullableTile`
必须真的 declare `implements INetworkPullable`（防止上一条因「名字出现过」而恒绿）。

> **变异测试结果（如实记）**：把 `GrindingMachineTile` 退回无能力的基类时，
> **是编译器先拦下的**（子类 `@Override networkPullSlots()` 找不到超类型方法），
> 源码形态护栏根本没轮到出场。这反而更好 —— 说明这套「基类 + 抽象方法」的设计
> 让**漏能力变成编译错误**，而不是靠护栏事后发现。

#### 教训

**「改完了」不等于「链路通了」。** 迁移一台机器 = tile 能力面 + 注册三件套 + 客户端绑定
+ 创造栏 + 槽位表 + 语言键 + **AE2 能力面** + 存档迁移 + 战利品表 …… 每一项都要**逐一实测**，
不能因为「编译过了、测试绿了」就认为做完。本轮两次结构性问题（GUI 手绘、AE2 能力）
都不是测试发现的，是**用户追问**发现的。

### 六之一之三、把「迁移清单」变成可执行的自检

上面两次漏项（GUI 手绘、AE2 能力）都有一个共同点：**编译通过 + 测试全绿**，
靠人（我）记清单 —— 而记不住。所以本轮把它固化成
`machine/TestMigrationCompleteness.java`，以「已迁机器登记表」为锚，逐项断言：

| # | 项 | 漏了的症状 | 判据 |
|---|---|---|---|
| 1 | 三件套成组 `register(bus)` | 运行期「注册表里没有这个 id」，方块变空气 | **逐个**断言 BLOCKS/TILES/CONTAINERS，缺一即红 |
| 2 | 客户端屏幕绑定 | 右键开界面崩 | `XXX_CONTAINER.get()` + `MenuScreens.register` |
| 3 | 创造模式物品栏 | 玩家拿不到 | `XXX_HANDLE.getItemStack()` 或 `XXX_ITEM.get()` |
| 4 | 方块名语言键 | 显示 raw key | en_us + zh_cn 两份都要有 |
| 5 | 旧 `instanceof` 分支已删 | 后来者以为那条路还活着（口径 §2.4） | 三个派发点不得引用已删的旧 BE 类名 |

（能力面 / 旧存档迁移 / 战利品表已有单独护栏，本类不重复。）

**新增一条登记纪律**：`MIGRATED` 表**刻意写死**而不是自动扫描 ——
它是「我认为已迁完了」的声明，**新迁一台必须来登记一次**，而登记这个动作本身
会逼人把清单过一遍。自动扫描反而会让「漏配的那台」因扫不到而逃过所有断言。

#### 变异测试记录（三条，全部如实记）

| 变异 | 结果 |
|---|---|
| 删掉 `GRINDING_MACHINE_CONTAINERS_REG.register(bus)` | 第一版判据 `count >= 3` **照旧全绿**（剩下 3 个盖住了缺的那个）⇒ 改成逐个断言后才抓住 |
| 把研磨机 tile 退回无能力的基类 | **编译器**先拦下（子类的 `@Override` 找不到超类型方法）—— 比护栏更硬 |
| 判据里的创造栏只认 `HANDLE.getItemStack()` | 把 `GRILL_ITEM.get()` / `MACHINE_ITEM.get()` 两种历史写法误报成缺陷 ⇒ 放宽为两种都认 |

> 三条里有两条是**护栏自己错了**，都是变异测试发现的。这已经是本仓第 N 次验证
> 「新加源码形态护栏必须先跑变异测试」。

### 六之二、⚠️ 本轮我自己犯的一条方法论错误（记下来防重蹈）

**「本机没装那个模组」≠「那个功能不存在 / 是死代码」。**

本轮中途我一度判断：研磨机支持的石磨/筛粉/绞碎三类外部配方「在测试环境里 3/4 是死代码」，
并据此提议把它们砍掉。**这个判断是错的，而且错得离谱**：

| 模组 | 本仓 Java 引用文件数 | lang | 指南页 |
|---|---|---|---|
| 森罗物语厨房（kaleidoscope_cookery） | 14 | 2 | 2 |
| 烘焙坊（bakeries） | 7 | 2 | 2 |
| 沉浸农艺（farm_and_charm） | 10 | 2 | 1 |

有三套完整集成、专门的 `KaleidoscopeCompat` 反射桥接（含概率产出的读法）、
指南页明确写着的玩家功能。**只是本机 `mods/` 里没装那三个 jar 而已。**

⇒ 两条判据：
1. **判「死代码」要查全仓引用（java + lang + 指南 + 数据），不能只看 `data/recipes/` 目录**；
2. **「我在本机验证不了」只能得出「未验证」，绝不能得出「可以删」**。
   把前者包装成后者、再作为一个「选项」摆出来请用户拍板 —— 那实质是**未经许可的减配**。

### 七、本轮刻意没做的事

- **12 台剩余单机** —— 用户指定「先做 1 台样板确认形态」，未铺开。
- **实机验证** —— 用户明确表示自行完成。
- **旧存档迁移的创造性升级卡** —— 旧研磨机的创造卡在第 4 槽，而迁移器
  （4 个家族共用的 `migrateSlots`）只认前 2 张升级卡，第 3 张起记 WARN 丢弃。
  这是**迁移器既有行为**，不是我引入的；本机受它影响，如实记在这里，未改。

---

## 〇、本轮（2026-10-01）—— 仓库卫生 + 结构整理

分两件事，判据都是 `./gradlew --offline test`（**62 套件 / 457 用例 / 0 失败**）。

### 一、仓库卫生

| 提交 | 内容 |
|---|---|
| `7885345` | 中央厨房存储浏览器补 S2C 回传（I-N4，详见下文第五轮小节） |
| `e9e9bb9` | 客户端硬编码中文全量迁移（I-N6）+ GUI 机器名 / 背包标签 |
| `2ac632b` | 删 33 个死成员 + 40 条未用 import + 1 处遗留实现（`PlantingRecipeGenerator` 的 139 行废弃黑名单读取），并**订正一处错误注释** |
| `f3c33ef` | 两处资源标签修正（`minecraft:onion` → `farmersdelight:onion`；`planting_factories` 加 `required: false`） |
| `437c8be` | 生物反应炉资产管线收敛到一条，删 3 个与主线写同一个 OBJ / 同一个 atlas 的脚本 |
| `49c36ee` | 可复现构建（去掉 jar 清单里的构建时间戳）+ wrapper 校验和 + `bootstrapDeps` 任务 + `.gitignore` 三项 |

清理掉的本地派生物：`logs/`、`.blender-preview/`、`scratch/`、`run-data/logs/`、`.logs/` 里的旧构建日志。

**两处刻意没清**：

- `.backup-20260929/` —— 其 `README.txt` 自述含 **3 个不在任何 git 提交里的 java 文件（唯一副本）**，
  并要求「删除前先确认不再需要」。0.15 MB，留着。
- `.superpowers/` —— 2026-09-29 多智能体轮次的 39 份会话报告（`docs/superpowers/` 只保留策展版）。
  已在 `.gitignore` 补一条根级规则：此前**仅靠一个未被跟踪的嵌套 `.gitignore`** 挡住它，
  那份文件一丢，39 个文件立刻全变成未跟踪。

### 二、结构整理：注册中枢拆分（`e0f99fa`）

`UniversalCuttingMachine.java` **2265 行 → 341 行**，只留 `@Mod` 入口、`MOD_ID`、构造器、
公共 setup 与创造标签入口；约 1900 行注册项按归属搬进 `cn.ism.mekck.registry`：

| 类 | 搬走什么 |
|---|---|
| `MekCkRegistries` | 十个延迟注册器本体 + 新的 `registerAll(bus)` 统一入口 |
| `MekCkItems` / `MekCkLegacyMachines` / `MekCkStandaloneMachines` / `MekCkFactories` | 纯物品 / 联动机器 / 单机 / 12 级工厂与基座机 |
| `MekCkFluids` / `MekCkEffects` / `MekCkEntities` / `MekCkRecipeTypes` | 流体 / 状态效果 / 弹射物 / 配方类型 |
| `MekCkRegistrySupport` | 注册期辅助（`registryView` / `findFactoryTile` 等闸门） |

三个内嵌事件类升格为顶层类：`ClientWorldEvents` / `ClientEvents` → `client/`，
`NetworkAdvancementEvents` → 新包 `event/`（方法体逐字未改，只去掉 `static` 修饰）。

**三条结构性决定**（都不是「风格偏好」，是被编译器和实机事故逼出来的）

1. **字段声明与赋值必须同居一处**。原先工厂与弹射物的注册都写在同一段静态块里；拆分时编译期
   直接拦下「字段在 `MekCkEntities`、赋值在 `MekCkFactories`」——**blank final 不能跨类赋值**。
   实体注册因此搬回 `MekCkEntities` 自己的静态块。
2. **触碰式初始化**（每个注册类一个空 `init()` + `registerAll` 的显式名单）。条目在静态初始化器里
   进 `DeferredRegister`，而监听器是 `register(bus)` 时挂的、**事件触发时**才读表 ⇒ 晚一步就是
   **静默不注册**（方块变空气、菜单取不到、无任何报错）。入口类不再持有全部字段之后，
   「谁引用到它」纯属运气，所以把顺序写成显式名单。
   新护栏 `TestRegistryInitContract`（3 条）用**目录扫描**得出名单：写死清单会让人误以为
   新注册类「已经接好了」。它的「是否加条目」判据**只看代码不看注释** ——
   `MekCkRegistrySupport` 的 javadoc 里就写着 `.register(blockHandle, …)`，那是解释不是注册项。
3. **创造标签页按类自报**。原先 81 行清单交织着 6 个归属的 `accept` 调用，现在各注册类有
   `addToCreativeTab(event)`，主类只留 tab key 判断 + 五次调用。标签页顺序随之变为「按类分组」
   （纯展示层变化）。

**拆分顺带暴露并修掉的两个真问题**

- `ClientWorldEvents` 里一条**玩家可见的硬编码中文聊天消息**
  （`displayClientMessage(Component.literal("§e[MekCK] 放置预览：…"))`）躲过了只扫 `client/`
  的 i18n 护栏整整五轮 —— 它一直住在**主类里的客户端事件内部类**中，而那个目录不在扫描范围内。
  升格进 `client/` 后当场变红，已改用语言键 `message.mekck.placement_preview_tip`。
  ⇒ **护栏的扫描面决定了它能看见什么；搬文件有时候比加断言更能修好判据。**
- `MekCkTierInstallerItem#materialName` 是死字段（只赋值从不读取），连带 4 个硬编码中文档位名实参。

**护栏改造**：7 条护栏原本硬编码读 `UniversalCuttingMachine.java`，拆分后会集体变红。但它们断言的是
**整体形态**（方块↔物品注册名对应、6 个家族的 tile 闸门、制冰工厂开关的三个使用点、JEI 催化剂按家族表
遍历……），不是文件名。于是给 `TestSourceText` 加了 `readRegistry()`（入口类 + `registry/` 全部类，
顺序固定），断言一行未改 —— 拆分再多次也不用改它们；真的改了注册写法，它们照样会红。
`TestIceFactoryToggle` 是个例外：它的「三个使用点」里有一个随事件类搬进了 `client/`，所以显式拼两面来数 ——
**为了让断言变绿而缩小扫描面，是拆分之后最容易犯的错**（缩小扫描面会把真实回归一起放过）。

### 三、遗留单机 BE 的三次抽取（`36f996a` / `84973f3` / `a6f75c4`）

`SimpleMachineBlockEntity` 原是全仓最大文件（**4841 行**），本轮按关注点抽了三次：

| 提交 | 抽了什么 | 结果 |
|---|---|---|
| `36f996a` | `MatchedRecipe`（配方匹配结果）从私有内嵌类升为 `machine/MatchedRecipe` | 34 处构造、6 个字段外部可读；为后续搬适配器铺路 |
| `84973f3` | 流体子系统 → `blockentity/SimpleMachineFluids` | 两个罐 + 三组 capability + 侧配 + `AutoFluidIO` + 3 个 `IFluidHandler` + 罐 NBT（**键名原样**）；BE 4841 → 4650 |
| `a6f75c4` | 配方适配器族 → `blockentity/SimpleMachineRecipes` | 38 个 `matchXxx`/`absorb*`/`complete` + 28 个仅在它们内部被调用的辅助方法 + `DrainProbe`，共 67 段；BE 4651 → **2783** |

**搬迁规则**：一个辅助方法**只有全部调用点都在搬移集合内**时才跟着搬；否则留在 BE，
伴生类用 `be.xxx()` 调。这条规则杜绝了「同一个方法两个类里各有一份」，
也是这三刀能靠编译器验证的根本原因。三个伴生类都**不持有自己的状态**，
所有机器状态都从 `be` 取 ⇒ 搬运不改变任何读写时序。

**只有真拆才会撞上的四条约束**（都写进了提交信息）

1. `level` 是 `BlockEntity` 的**继承字段且 protected** ⇒ 跨类访问不到，必须走 `be.getLevel()`；
   而某些方法里 `level` 又被**局部 int** 遮蔽，那里不能加前缀（加错会变成 `int be.getLevel() = …`）。
2. `setChanged()` 同理是继承方法。
3. 被搬方法里的 `static` 方法需要 `be.` ⇒ 一律改成实例方法（本来就不是 override）。
4. **字段声明与赋值必须同居一处** —— blank final 不能跨类赋值，编译期就拦下
   「字段在 A 类、赋值在 B 类」这种拆法（弹射物注册因此搬回 `MekCkEntities` 自己的静态块）。

**方法论：区间必须来自编译器，不要用正则猜行号**

搬运脚本改用 `JavacTask.parse()`（只解析不归因，不需要 classpath）拿成员区间。
前几版用正则 + 括号配对猜，连踩四个坑，其中一个卡了两轮：

- `enumerate(masked, 1)` 拿 **1-based 序号**查 **0-based 集合** ⇒ 每个区间「首行被删、末行被留」，
  BE 里凭空多出一串孤立 `}`、类体提前闭合（102 个语法错误）；
- 自己向后找 `    }` 定位方法结束行 ⇒ 跨过嵌套层级，把 `matchSushi` 的结束算到文件末尾；
- 调用点改写命中了**方法声明** ⇒ 现在靠一个不变量解决：被搬方法的声明已从 BE 消失，
  剩下的同名出现必然是调用点；
- 「局部变量遮蔽」必须**逐方法**算：BE 成员那次统计为 0，但 `level` 是继承字段、不在那份名单里。

脚本现在自带三道断言（声明行必须落在删除集、行数守恒、区间不重叠），
并在写盘后**用 javac 重新 parse 两个文件做语法门禁**。一次性脚本仍留在本地 `.logs/`
（`move.py` / `JavaPos.java` / `recipes-header.txt`），未入库。

**护栏的工具教训（本轮新增）**

`JavacTask` 那套脚本里还有一个**假门禁**，值得单独记：解析用的诊断收集器写成了
`d -> { }`，即**丢弃一切 javac 诊断** —— 于是文件里有语法错误时它照样打印「parse OK」。
我据此把「类体提前闭合」误判成区间问题，查了好几轮。修正后（收集诊断 + `exit 2`）
它第一次真正报错，立刻指出第 823 行非法表达式。另外 `-encoding UTF-8` 也必须显式给，
否则 javac 按平台默认（本机 GBK）解码，中文注释全变 ERROR，门禁退化成「永远红」。

**一条没能做成的护栏：反射取用的成员名**

本仓大量用 `Reflect.call(obj, "name")` 按名字取成员（AE2 的订单字段、各外部模组配方字段），
接收者类一旦被重构就会**静默失败**——编译过、测试绿、运行时才炸。所以我尝试把它固化成护栏，
**失败了，如实记录**：

| 尝试 | 结果 |
|---|---|
| 用正则抓 `Reflect.xxx(接收者, "名字")` | 抓到 64 处，但**能解析出本仓类型的只有 0 处** |
| 原因 | 真正要盯的那批（AE2 对 BE 的取用）写法是 `Reflect.method(machine.getClass(), "setOrder", …)`，接收者是**表达式**而非标识符；其余 64 处接收者是第三方对象（`Recipe<?>`、外部 BE），本来就不该校验 |
| 结论 | 半吊子的类型解析只会产出一条**永远绿**的护栏。**已撤掉**，没有留在仓库里 |

可靠的做法是用 javac AST 做真正的类型解析（把 `Reflect.method(x.getClass(), …)` 的 `x` 解析成它的
静态类型），那是另一个量级的工具，不该顺手塞进这一轮。

**一次性人工核对的结果（已被 `TestNbtWriteReadSymmetry` 固化的那部分）**：
以 BE 为接收者的反射取用共 3 个 —— `setOrder` / `getOrderRecipeId` / `getOrderQuantity` ——
**全部仍在 BE 上**，即 AE2 的运行时路径未被这四次重构破坏。

### 四、本轮刻意没做的事

- **实机验证** —— 本仓 dev 环境起不来（Farmer's Delight 自己的 mixin 注入失败，见第二轮报告），
  所以以上拆分只做到了「编译 + 457 测试」。**接手后优先实机验证三条路径**：
  ① 陈酿机批次（Tavern，`matchTavernBarrelPlan` → `absorbJuice*` → `complete`）；
  ② 制冰工厂开关（`ICE_FACTORY_MENU` 的 null 哨兵 + 客户端屏幕绑定）；
  ③ 发酵机流体（配方流体搬进罐 + `SimpleMachineFluids` 的 cap 暴露）。
  这三条恰好是配方与流体交叉最多的地方，也是机械改写最容易改错语义而测试察觉不到的地方。
- **`SimpleMachineRecipes` 仍偏大**（2009 行）—— 内部可再按配方族细分（酒饮 / 食品加工 / 特殊工艺），
  但每族都要一份状态上下文，适合作为单独一轮。
- **`SimpleMachineBlockEntity` 仍有 2783 行** —— 剩下的主要是 NBT 三件套（162 行）、
  侧配置 / 能量 / 红石、tick 主循环、AE2 胶水层；按状态域继续切是同一套工具的活。
- **拆分脚本未入库** —— 一次性搬迁工具留在本地 `.logs/`；入库的是**护栏**
  （`TestRegistryInitContract` + `TestSourceText.readRegistry`），因为它们保护的是「以后」的改动。

---

## 〇、第五轮（2026-10-01）

在 `fefa065` 之上修两条**面向玩家的真实缺陷**。两者的共同特征是
**编译通过、单元测试全绿、单机看不出来**——所以本轮的重点不只是修，还包括
给每条修法配一个**能在源码形态上抓住回归**的护栏。

### I-N4：中央厨房存储浏览器整体是死的 —— 已修

**病因**：`CentralKitchenMenu` 的搜索 / 排序 / 滚动**一直是在服务端算对的**
（`KitchenViewPacket` 落地后调的是服务端 menu 的 `setSearchText` / `setSortMode` / `scroll`）。
缺的不是计算，是**回传**：客户端 menu 里的 `CentralKitchenBlockEntity` 是一个
**items 全空的桩**（300 格存储从不进 `ContainerSynchronizer`），
`StorageSlot.getItem()` 读的又恰好是 `machine.items` ⇒ `machineIndex` 恒 -1 ⇒
**54 个格子永远画成空的**。玩家看到的是一个搜索框能动、但下面什么都没有的浏览器。

**修法**（`network/KitchenStorageSyncPacket.java`，包 id 29）：

| 环节 | 做法 |
|---|---|
| 同步内容 | **只发当前可见的一页（54 格）**，不发 300 格全量。AutoIO 可能每 tick 拉料，全量推送是带宽灾难，而玩家能看到的永远只有一页 |
| 落地 | `CentralKitchenMenu#applyStorageSnapshot` 写客户端镜像；`StorageSlot.getItem()` **分端**：客户端读按**显示位置**索引的镜像，服务端才读 `machine.items` |
| 客户端不再自行过滤 | `refreshDisplay()` 加客户端提前返回。用空桩算出来的必然是空列表，还会顺手把服务端下行的 `displayOrder` 刷成全 -1 |
| 触发时机 | 玩家触发（搜索/排序/滚动/quickMove）**强制**推送；BE 每 tick 的补推**节流 4 tick** 且只在 `storageVersion` 变化时推（`onContentsChanged` 里对存储区计数） |
| 顺带修的显示 bug | `getFilteredCount()` 之前在客户端恒为 0，界面会显示「共 0 条」「1/0 页」——格子有货了页码却是空的，玩家仍会以为没同步。现在随快照一起下行 |

**一个必须记下来的坑**：`pushStorageSync` 在**没有观众时不记账**。
菜单构造器里 `refreshDisplay()` 就会调它，而此刻玩家的 `containerMenu`
**还没被设成本 menu**（`MenuProvider.createMenu` 返回后才赋值）⇒ `sent` 恒为 0。
若照常记账，首推会被记成「已发」，随后 BE 第一次 tick 看到「版本没变」直接返回，
**界面永远收不到第一页**。不记账则下一 tick 自动重试。

护栏 `TestKitchenStorageBrowserSync`（**7** 条断言）钉的是**链路每一环**，
而不只是「有 S2C 包」——补了包却没接进渲染路径，界面照样空白且无任何日志：

- 客户端不自行过滤（`refreshDisplay` 有客户端提前返回）
- 槽位渲染分端，且**客户端分支不得出现 `machine.items`**
- **客户端分支只由 `isClientSide()` 把关**（变异测试逼出来的，见 §六；缺它则 `&& false` 形态漏网）
- 包处理器真的调到了 menu 的落地方法（防「加了包没接上」）
- 包已注册且方向是 S2C（`TestPacketGuardCoverage` 自动覆盖新包，无需改它）
- 页码指示读的值也下行了
- 判据不许空转（正则失配会先红，而不是让文件变成永远绿的摆设）

### I-N6：客户端硬编码中文 —— 已修（85 处 / 45 个新键）

英文客户端此前会在 `client/` 看到成片中文。**剩余 11 处 CJK 字面量全部是日志诊断**
（`MekCkFactoryJei` / `MekCkOutlineRenderer` / `GuideMECompatImpl` / `ObjMeshLoader`），
玩家读不到，**刻意不迁**——这条区分是本项的关键，判据必须能区分日志与 UI，
否则只会得到一份不断变长的豁免清单。

**先加共享键再替换**，没有逐处新造同义键。最典型的是
`gui.mekck.ui.target_type.hostile/all/animal`——**4 个界面**（巧克力大炮 / 制冰机 /
制冰厂 / 坚果烘焙机）共用同一批键。`目标:` / `半径:` 也没有新建键，
复用上一轮已有的 `gui.mekck.ui.target` / `.radius`。

护栏 `TestNoHardcodedUiText` **从「45 条字面量清单 + 10 文件白名单」重写为全域扫描**，
且判据从**文本形状**改为**数据流**：

- 词法器识别 logger 变量绑定 → 字面量所在语句里有 `<logger>.<日志方法>(` 即放行。
  玩家能看到的 `"抽取失败"` 不会被误放过。
- 唯一白名单 `LOG_ONLY_SINKS` 只允许 ≤ **4** 项，超出直接红。
  **目的是逼迫改进判据，而不是让清单长下去。**
- 生命周期自检从「全仓 CJK ≥ 20」提到 **≥ 100**（实测迁移后全仓仍有 499，
  全部残留在 common 代码里）。**20 离现状太远，几乎只在彻底失配时才响。**
- 新增 `theDetectorStillFlagsASyntheticSample`：喂人工合成样本，断言「抓 UI、放日志」。
  这是唯一不依赖仓库现状的防线。

### 护栏总数

337 → 359（第三轮）→ 380（`5a8756f`，未复跑）→ 438（切菜机）→ 441（`fefa065`）→
454（第五轮 +13）→ **457**（整理轮 +3：`TestRegistryInitContract`）。
**62 套件 / 457 用例 / 0 失败 / 0 错误 / 0 跳过**（`./gradlew --offline test`）。
`TestKitchenStorageBrowserSync` 的 7 条断言里，**`clientBranchIsGatedBySideAlone` 是被变异测试逼出来的**——
原先那 6 条挡不住「客户端分支被 `&& false` 短路、缺陷完整复现」这一形态。
变异测试的完整记录见 §六。

### 第六轮补：GUI 两行字（机器名 / 「Inventory」标签）—— 已修

Mek 的 `GuiMekanism.renderLabels` 覆写了原版 `AbstractContainerScreen.renderLabels` 且**不调 super**，
而 `GuiConfigurableTile` / `GuiMekanismTile` 都**没有**覆写 `drawForegroundText`
（实测其方法体就是 `return`）。⇒ **不自己覆写它的屏，机器名与背包标签一个都不画。**

| 屏 | 缺陷 | 修法 |
|---|---|---|
| `UniversalCuttingMachineScreen` | **两行字都不显示**。它直接继承 `GuiConfigurableTile` 且不覆写，而 `inventoryLabelY` 被设成了 84 却**没有任何代码读它** —— 典型的「设了值就以为画了」 | 补 `drawForegroundText`：`renderTitleText` + `drawString(playerInventoryTitle, …)`，并把 label 改用共享常量 |
| `GrillScreen` | `inventoryLabelY = 84`，而本菜单不覆写 `getInventoryYOffset()` ⇒ 背包首行**就是** 84（Mek 的 `BASE_Y_OFFSET`，已用 `MekanismContainer.addInventorySlots` 字节码坐实：槽位 y = `getInventoryYOffset() + row*18`）⇒ 标签画在第一行背包槽的正上方 | label 改 74（= 84−10）；温度读数从 `(8,74)` 移到**与标签同行的右端**（原先也在抢那条 72~83 的空档） |

**原注释把方向写反了，值得单独记一笔**：`GrillScreen` 的注释说
「inventoryLabelY 的默认值是 72，必须显式对齐到 84，否则『Inventory』标签会浮在背包上方 12px」。
实际上「浮在背包上方 12px」**正是对的间距**（原版就是 `imageHeight−94` 的标签配 `imageHeight−84` 的槽），
对齐到 84 才是把它按到槽上。⇒ **注释里描述的「问题」要先验证是不是真问题。**

顺带在 `MekCkFactoryLayout` 里把这条几何显式命名，消除两处裸数字：
`MEK_DEFAULT_INVENTORY_Y = 84` / `INVENTORY_X_OFFSET = 8` / `INVENTORY_LABEL_Y = 84−10`。

护栏 `TestGuiInventoryLabels`（4 条断言）覆盖整类问题，不是单点。
变异测试记录见 §六 —— **其中一条断言在第一版是漏的**：
豁免判据原本是「全文出现过 `MekCkFactoryScreenBase`」，而该屏的 javadoc 正好引用了这个名字
（解释「六个工厂屏由它统一补上」），**注释被当成了继承关系**。
改成解析真正的 `extends` 子句后才抓住。⇒ **豁免判据不能用全文子串匹配。**

### 第六轮补：桩代码 / 死代码清理 —— 已完成

**删了 33 个成员 + 40 条未用 import + 3 个空目录**，产物小 6.3 KB，**454 测试 0 失败**。
编译器（`compileJava` + `clean build`）是本次清理的最终判据：删错任何一个 `::` 引用都会编译失败。

| 类别 | 数量 | 例子 |
|---|---|---|
| 未被引用的 private 方法 | 20 | 7 个遗留 BE 的 `decodeSideConfig`；`SimpleMachineBlockEntity#slotRoom`；`MekckTierInstallerItem#tierName`（**还内含硬编码中文档位名**，删掉顺带消掉一个潜伏的 i18n 违规） |
| 连带孤儿（删上一批后才变成死代码） | 2 | `MekckAe2#countAvailable` / `canExtractFromNetwork` —— 唯一调用方是已删的 `maxCraftable` / `canExtractAll` |
| 未被引用的 private 字段 | 12 | `GrillFactoryTile` 的 3 个 `SEASONING_SLOT_*`；`UniversalCuttingMachineTile` 的 2 个升级槽 X；`IceMakeRecipeCategory` 相关的若干常量 |
| 整块死逻辑 | ~70 行 | `PlantingCuttingFactoryExecutor` 的 `collectBatch` + 嵌套 `record Batch` —— 一整套「批量收集」从未被调用 |
| 未使用的 import | 40 | 18 个文件 |
| 空目录（工作区残留） | 3 | `src/main/java/com/example/examplemod`（Forge MDK 模板）、空的 `datagen/` 与 `fluid/` 包 |

> **删除是级联的**：删掉 `maxCraftable` 之后 `countAvailable` 才变成孤儿。
> 所以清理必须**扫一轮、编译、再扫一轮**，只扫一次会留下新的死代码。

#### ⚠️ 六类「看起来是死的、其实不能删」——本轮全部踩过

这一节是本轮最重要的产出。**自动扫描给出的候选里有一半以上是误报**：

| 目标 | 为什么看着像死代码 | 真相 |
|---|---|---|
| `Reflect.missingMethod()` | 空的 private static 方法体，教科书级桩代码 | **是哨兵**：`getDeclaredMethod("missingMethod")` 拿它当「未找到」缓存标记。名字骗人，作用关键 |
| `matchesTarget`（2 个 BE） | 只在声明处出现 | 通过**方法引用** `this::matchesTarget` 使用。`grep 'matchesTarget('` 看不见 —— **扫描器必须同时认 `::name`** |
| 8 个屏的 `openSideConfigWindow` / `openUpgradeWindow` | 同上 | 全部通过 `this::openSideConfigWindow` 挂在按钮上。**删掉等于从 8 台机器上移除侧配与升级按钮** |
| `TestMekCkHeatIntegration` | 在 `src/main` 里、零引用 | 是 Forge **GameTest**（注解发现），且 `build.gradle` 显式 `exclude` 掉不打进 jar —— 有意为之 |
| `CreativeUpgradeTooltipHandler` 等 4 个类 | 零引用 | `@Mod.EventBusSubscriber` + `@SubscribeEvent`，**事件总线按注解发现** |
| **`TemperatureHelper`（112 行）** | 零引用，纯粹的工具类 | **它的类注释里直接写着「它现在没人用，也不要为了『整洁』把它删掉」**——它保存的是一次设计查证的**否定结论**（本模组不存在「热源温度」概念，正确位置是 `MekCkHeatComponent`），留着是为了让下一个人不必重做一遍 |

另外两个工具性教训：
- **PowerShell 的 `Select-String -Pattern "\bX\b" | Where { $_.Filename -ne "X.java" }` 报出了「0 外部引用」的假结论**
  （`MekCkTabElement` 实有 10+ 处引用）。判据改用 Python 后结论相反。**扫描器本身的 bug 会被误读成「代码是干净的」**。
- 脚本里用 `[^"]*\bNAME\b` 查「字符串里是否出现」时，`[^"]*` 会跨行匹配到整个文件，判据完全失效。

#### 「死资源」核验结果：三项都是误报

| 疑点 | 结论 |
|---|---|
| `loot_tables/blocks/` 疑似 5 张死表 | **73 张全部有效**。其中 4 张早已被删；剩下的 `electric_grill` 对应的 `GrillBlock` **并未覆写 `getDrops`**（全文无该方法），是活表。代码里那段「5 张从未生效、属于待清理项」的注释已订正 |
| 505 个 lang 键疑似 320 个未引用 | **零死键**。`block.mekck.*` / `item.mekck.*` 由引擎按注册名自动解析，进度由 advancement JSON 引用 —— 只扫 Java 会把它们全判成死键 |
| 备份目录 / `scratch/` / `run/` | 全部已 gitignore 且未被跟踪，**不是仓库污染**。`src/generated/resources` 虽空但被 `build.gradle` 引用，保留 |

### 仍未修

| # | 问题 | 备注 |
|---|---|---|
| **ICE 工厂 Mek 原生化** | 7 个工艺里最后一个未迁。**不是「照抄第 7 遍」**，见 §〇 末尾的专项评估 | 默认配置关闭，玩家当前不可达 |
| I-6 | 种植切配站模型硬依赖未声明的 `mekmm`（18 处引用 + 30 条配方） | 需决定：声明依赖 / 换自有模型 / 条件化 |
| I-1（生成器） | 配方生成器在主线程跑 + 强制 `/reload`，大整合包首开服可能超 60 s 看门狗 | 需幂等短路 + 分摊到若干 tick |
| — | 18 个方块把 `getDrops` 覆写成 `List.of()`、物品只在 `onRemove` 掉 ⇒ **TNT/爆炸摧毁时一件不掉** | 需确认是否有意。**连带**：`data/mekck/loot_tables/blocks/` 下 5 张遗留机器的表因这个覆写**从未生效**，是死文件 |
| — | 16 个新物品（8 串烧 + 8 烤制）刻意没设 `food` 属性 | 营养值得与主料逐条对齐才算平衡，**需你定设计** |
| — | `creative_upgrade_from_49_foods.json` 的 `type` 是不存在的 `avaritia:shapeless_table`，而 Avaritia 从未在 `mods.toml` 声明为依赖 | 只改产物修不好它，`type` 仍需改 |
| — | `MixinExtremeSmithingMenu.INFINITY_UPGRADE` 的求值时机存疑 | 需对 Avaritia 做 `javap -v`，该模组不在本地缓存 |

### ICE 工厂专项评估（本轮结论：**不迁**，理由如下）

第 7 个工艺**不是照抄第 6 遍**。`IceFactoryBlockEntity`（964 行）比已迁的 6 个多出四类东西，
其中第一类就需要改**共享基类**：

| 需求 | 已迁的 6 个 | 制冰工厂 | 障碍 |
|---|---|---|---|
| 流体 | 无 | **水罐 256,000 mB** | `MekCkMachineTile#presetVariables` 写死 `TileComponentConfig(this, ITEM, ENERGY)`，**注释明说「本模组工厂没有气体/流体/矿浆」**。加 `TransmissionType.FLUID` 会给**6 台正常机器的侧配 GUI 多出一个空 Tab**——那是回归，不是修复。（可在 `IceFactoryTile` 单点覆写 `presetVariables` 规避，但那是新架构，不是复用） |
| 热 | 烧烤 / 烹饪有单热容 | **双热容**（`heatComponent` + `coldComponent`，正面吸热/背面放热） | 基类只有单热钩子 `hasHeatSupport()` / `getInitialHeatCapacitors()` |
| 升级 | 速度/能量/存储/随机化 | **5 段链式冷萃升级**（冷→低��→霜→龙霜/女王→失温，前段未装则后段不可装） | `appendExtraSlots` 能挂槽，但链式准入判定要新写 |
| 行为 | 纯 item→item | **发射冰块实体**：目标搜索、排队分配、AOE、伤害/减速/去 AI/失温，外加 `targetType` / `radius` 自己的包与客户端同步 | 执行器要拿到 level 并生成实体；`pendingAttackTargets` 队列有跨 tick 状态 |

外加：自有配方类型 `IceMakeRecipe`、从 964 行旧 BE 的存档迁移。

**为什么本轮不做**：架构规格 §13 要求**每个阶段都过实机验收**（放置→GUI→投料→加工→
升级卡→拆放→重启）。而本机代理当前不可用（见 §五.7），**装不出可启动的客户端**。
在这种情况下交付一个改动了**共享基类**、影响 6 台在产机器、且**无法实机验证**的千行改动，
风险高于收益——本仓已经吃过一次「编译 + 441 测试全绿但功能是坏的」的亏
（`fefa065` 修的进度条问题正是 438 个测试全漏、只在联机发作）。
**`MekCkFactoryType.ICE` 的枚举槽与译名键已就位**，补 tile + executor 即可接上；
等有可启动环境时按上面四行逐项做，不要在无验证条件下动 `presetVariables`。

---

## 一、第三轮全量审查（2026-09-30）

4 个 agent 按**包边界**并行深审 + Lead 亲自复核每条 Critical 与产物级证据。
本轮**没有**独立的分域报告文件——结论全部内联在本节。第二轮的分域明细见
[`audit/2026-09-30-details/`](audit/2026-09-30-details/)（基线 `83830e0`，属第二轮）。

### 已修（第三轮 3 个提交）

| 提交 | 内容 |
|---|---|
| `0659f30` | **收编上一轮 C1–C6 的修复成果**。此前 120 个已跟踪文件被改 + **479 个未跟踪文件**（含 `mekck.refmap.json` 本身、审查报告、9 个护栏测试）全部游离在工作区——一次 `git clean -fd` 就全没了，且没有任何东西会失败报警 |
| `b6435bc` | 两个**服务器崩溃级**缺陷 + 订单契约统一 + 仓库卫生 |
| `bb00171` | 存档损坏 / TPS 杀手 / 永久无 AI |

### 第三轮之后（3 个提交，本表未逐条复核）

| 提交 | 内容 |
|---|---|
| `1448ec9` | 中央厨房 off-by-one + 配方流体 tag NPE + 渲染泄漏 + 配置回滚——**修掉下方「未修项」表 7 项** |
| `7403722` | 中央厨房 2 个 Critical（每次存档丢物品 / 订单交付静默销毁）+ 补齐自己修复的漏洞 |
| `5a8756f` | 补回迁移丢失的用途（热容 / 营养液灌注 / 存储区刷新）+ 激活自动加工模式 |

> 护栏数：第三轮收尾 **359** → `5a8756f` 时 **380**（据该提交说明，**未复跑验证**）。

### Critical 处置表

| # | 位置 | 问题 | 状态 |
|---|---|---|---|
| **C-N1** | `network/NetworkRecipeListPacket`、`NetworkMissingPacket` | `handle` 里直接 `Minecraft.getInstance()`。两包在 `FMLCommonSetupEvent`（**双端都触发**）里注册 ⇒ 专用服务端一定链接它们 ⇒ server jar 上没有 `net.minecraft.client.*` ⇒ **启动崩** | **已修**：走 `util/ClientPacketBridge`（common，零客户端符号，反射加载）+ `client/ClientPacketBridgeImpl`（`@OnlyIn(CLIENT)`）。加 `TestNoClientSymbolsInCommonCode`，`network/` 零例外 |
| **C1** | `util/FreezeEvents:59` | 靠「比较伤害数值」防递归，但 `LivingDamageEvent.getAmount()` 是**减免后**的值（`hurt` 顺序：护甲→魔抗→`onLivingDamage`），而 `minecraft:freeze` 只 `bypasses_armor` 不 `bypasses_resistance` ⇒ 抗性 1 即得 1717986917.6 < 2147483647 ⇒ 守卫恒假 ⇒ **无限递归 → StackOverflowError → 服务器崩** | **已修**：`ThreadLocal<LivingEntity>` 重入哨兵（存实体而非 boolean，反射伤害弹到另一只实体时不被误吞） |
| **C3** | `kitchen/KitchenRecipeMatcher:280` | `existing.grow()` 无溢出防护。`ItemStack.setCount` 不夹紧，槽上限默认 `Integer.MAX_VALUE` ⇒ 两个大堆叠归并**必然溢出为负** ⇒ `isEmpty()` 变真 ⇒ 落盘时整堆永久消失、无日志 | **已修**：照抄同仓 `util/StorageMerger.merge`（复核确认正确的参照实现），逐格夹紧 + 剩余量继续找位 |
| **C2** | `effect/EternalFreezeEffect:43`、`entity/IceCubeEntity:215`、`entity/FerreroEntity:248` | `NoAI` **会写进存档**（实测 `Mob.addAdditionalSaveData` 写 `NoAI`），而恢复只靠内存 `MinecraftServer.tellables` ⇒ 重启即丢；且恢复条件挂在 `!isNoAi()` 上 ⇒ 重启后**永远恢复不了**，玩家无任何游戏内手段解除 | **已修**：效果类改成「恢复刻已到」这个与 AI 状态无关的条件；两个实体新增 `util/FreezeAiReaper`（挂 `LevelEvent.Load`，按区块分片） |
| **C-N2** | 4 个 BE 的 `setRadius`/`adjustRadius` | 只钳下限不钳上限，而半径来自 `IceAttackConfigPacket` 的裸 `readInt`（`PacketGuard` 只校验 8 格交互距离）⇒ **一台机器 + 一个包 = 永久的每 tick 全服实体遍历** | **已修**：`IceTargetSearch.clampAttackRadius` 作为唯一闸门；`AABB_SCAN_MAX_RADIUS` 由 512 降到 64（原值只按「AABB 不溢出」定、没算成本：512 ⇒ 65³≈27 万次 section 查找/次） |

### Important（已修）

| # | 位置 | 问题 |
|---|---|---|
| 订单契约漂移 | 6 个执行器 | **3 套 null 约定 + 2 套数量下界**。收进 `machine/MekCkOrderState` 后连带修掉两个真实缺陷：① `orderCompleted++`（int 自增，两处注释都声称「加法走 long」但 `++` 本身就是 int 加法）⇒ 份数配成 `Integer.MAX_VALUE` 时订单**永远完不成**、机器永远只认这一张配方、无任何日志；② 烹饪 `load` 用 `Math.max(0,…)` 读份数，`quantity=0` 让 `batch` 夹成 0 ⇒ **机器永久卡死且订单永不完成** |
| I-4 | 烧烤调味料 / 烹饪·穿串「当前订单 x/y」 | **完全没有同步通道**。原注释断言「随方块更新包同步到客户端」——经 javap 复核**不成立**（`sendBlockUpdated` 只发方块状态；`machine/**` 里 `setChanged\|sendBlockUpdated` 零命中）⇒ 点「开/关」按钮颜色不变，离开并重进区块才刷新 | **已修**：`addContainerTrackers` 加 4 条 `SyncableInt`（订单三件套 + 家族位图），按端分流 |
| C4 | `command/PlantingRecipeGenerator` | 每次开服整棵递归删 `world/datapacks/mekck_planting`（玩家可见的存档内容） | **已修**：只删本方法自己写的 4 个配方目录 |
| I4 | 同上 | 遗留调试插桩：每次开服往世界存档根目录写 `planting_generator_test.txt` | **已修**（删除） |
| I7 | `util/FreezeEvents` | `event.getDrops().clear()` + 查不到战利品表时 `LootTable.EMPTY` ⇒ 「这只生物什么都不掉」且无日志；且抹掉别的模组的掉落 | **已修**：查不到表就原样保留；改为追加而非替换 |
| M7 | 同上 | 按参数签名反射定位战利品 raw 掷骰方法，但 `getRandomItems` 与 `processSelectedStacks` 擦除后签名完全一致，而 `getDeclaredMethods()` 顺序不由 JVM 规范保证 ⇒ 绑错就是「静默少掉整棵池子」 | **已修**：补返回类型判据 + 记录实际方法名 |
| 错 tag 双读 | `CookingFactoryTile`、`PlantingCuttingFactoryTile` | 覆写 `load()` 再调一次 `readExtraSustainedData(tag)`，但那个 `tag` 是**未经迁移的原始 tag**（基类在旧存档路径上会换绑）⇒ 迁移器一旦碰 `FluidTanks`/`GasTank` 就会用旧值覆盖迁移结果 | **已修**：删除覆写，基类已用正确的 tag 调过 |
| 恢复刻键冲突 | `FerreroEntity` | 与 `IceCubeEntity` 共用 `mekck:ai_restore_tick` ⇒「谁后命中谁说了算」，短窗口覆盖长窗口 | **已修**：拆成两个独立键 |
| README | 两份语言 | 都宣称「7 系列 × 12 档 = 84 个工厂方块」并把制冰列为完整系列，而 `ICE_FACTORY_ENABLED = false` ⇒ 实际 6 系列 72 个。该常量的注释本身也错了两处（「禁用 11 级」实为 12 级；「改回 true 即恢复注册」实为还需补 12 张战利品表） | **已修** |

### ⚠️ 本轮引入又修掉的一个回归

手写 refmap 里是 **SRG 名**（打包产物跑 SRG），而 `runClient`/`runServer` 跑的是 **mojmap** jar。
上一轮只验证了产物侧，**没验证 dev 侧** ⇒ `gradlew runClient` 会在 Bootstrap 阶段崩。
`build.gradle` 现按 ForgeGradle 缓存实际布局**动态定位** `srg_to_official_1.20.1.tsrg`
（该路径含 MCP 快照时间戳，写死就是 build.gradle 自己禁止的「本机 + 本次缓存布局的快照」），
并给三个 run 任务传 `-Dmixin.env.remapRefMap=true`；找不到时**显式失败**而不是让 dev 跑出一个看不懂的崩溃。
`TestMixinRefmapIntegrity` 补了对应断言。

### 未修项

**已修（第四轮，2026-09-30）**

| # | 问题 | 修法 |
|---|---|---|
| I-N5 | `ContainerData` 通道是 **16 位有符号** ⇒ 13 台遗留机器能量显示为负；**并顺带关掉**种植切配站的创造升级 UI（`hasCreative` 恒假） | **修法与前两轮的判断都不同，且此前那句「现已做完」是错的**（见下方勘误）。第四轮实测：缩放是**有损**的（容量 4 亿的陈酿机往返误差达 ±6104 FE，tooltip 会显示「399,993,896 / 400,000,000 FE」），而本仓 `SandwichAssemblerMenu` 早就在用**低 16 位 / 高 16 位两个槽**传 32 位值 —— 拆两槽是**无损**的。故新建 `util/WideDataSlot` 统一实现，12 台机器全部改造，护栏 `TestWideDataSlot` 用真实 `ClientboundContainerSetDataPacket` 做字节往返钉死 |

**已修（`1448ec9`，逐项核实）**

| # | 问题 | 修法 |
|---|---|---|
| I-N3 | `CentralKitchenMenu.quickMoveStack` 少算 1 个机器槽 ⇒ 三明治样品被 shift-click 搬进存储区并清空 | 槽数 83 → 84；护栏 `TestMenuQuickMoveSlotRanges` 的表同步更新（原表把该 bug 登记成了「预期行为」） |
| I-2 | `MekCkRenderTypes.getIce()` 每帧新建 `RenderType` ⇒ 无界堆增长 + 缓冲缓存失效 | 改为 `static final RenderType[] ICE_LEVELS`，4 张贴图 4 个实例 |
| I-3 | 冰封贴图 `ResourceLocation` 缺命名空间（`textures/block/textures/block/...`）且 `frosted_ice_0..3` 在本仓与原版都不存在 | 改为返回 0..3 下标，调用方不再持有 `ResourceLocation`，从根上写不出单参构造 |
| I-5 | `ExtractingRecipe.FluidInput.matches()` 在流体 tag 不存在时 NPE，被 `catch (Throwable)` 吞掉 ⇒ 带 `#tag` 的配方**永远不匹配** | 已修 |
| I-8 | `CreativeUpgradeFoodRotator` 用**陈旧快照**覆盖 Forge 刚保存的 `mekck-common.toml` | 已修（配置回滚） |
| I-9 | `MekckConfig.ice_attack_radius` 上界无意义 | `defineInRange(..., 4, IceTargetSearch.MAX_ATTACK_RADIUS)` |
| M-2 | `item.mekck.ferrero_projectile` 两份 lang 都缺 | 两份都已补 |

**仍未修**

> ⚠️ **本表写于第三轮收尾时。** 之后又有 10 个提交（`1448ec9` / `7403722` / `5a8756f` /
> `7885345` / `e9e9bb9` / `2ac632b` / `f3c33ef` / `437c8be` / `49c36ee` / `e0f99fa`）。
> `1448ec9` 已修掉下表 7 项、第五轮的 `7885345` / `e9e9bb9` 又修掉 2 项（I-N4 / I-N6），
> 均已逐项核实并移入上方「已修」表。**其余各项未逐条复核**，以 `git show <提交>` 为准。

**已修（第五轮，2026-10-01；此前本表把它们挂在「仍未修」里，与 §〇 自相矛盾，已对齐）**

| # | 问题 | 修法（提交号 + 代码位置） |
|---|---|---|
| I-N4 | 中央厨房的搜索/排序/滚动只在服务端算，**没有任何 S2C 包回传** ⇒ 存储浏览器整体是死的 | `7885345`。新增 `network/KitchenStorageSyncPacket`（包 id 29）只推可见的一页 54 格；`CentralKitchenMenu#applyStorageSnapshot` 写客户端镜像；`CentralKitchenBlockEntity#storageVersion` 驱动节流补推。护栏 `TestKitchenStorageBrowserSync` **7** 条 |
| **I-N6** | `client/` 下 98 处硬编码 UI 文案 / 21 个文件（第四轮实测）⇒ 英文客户端看到中文 | `e9e9bb9`。文案迁到共享 `gui.mekck.ui.*` 键（先加共享键再替换，**新增 45 个键**，两份语言同批）；剩余 11 处 CJK 全是**日志诊断**，玩家读不到，刻意不迁。护栏 `TestNoHardcodedUiText` 从「45 条字面量清单 + 10 文件白名单」重写为**全域扫描**（判据改为数据流：日志 sink 才放行，白名单 ≤4 项） |

**仍未修**

| # | 问题 | 备注 |
|---|---|---|
| I-1 | 制冰工厂 GUI 面板在高档位超出屏幕，玩家背包被推出可视区 | 制冰工厂未注册，实际不可达 |
| I-6 | 种植切配站的模型硬依赖未声明的 `mekmm`（18 处引用 + 30 条配方） | 需决定：声明依赖 / 换自有模型 / 条件化 |
| I-1(生成器) | 配方生成器在主线程跑 + 强制 `/reload`，大整合包首开服可能超 60 s 看门狗 | 需幂等短路 + 分摊到若干 tick |
| — | 18 个方块把 `getDrops` 覆写成 `List.of()`、物品只在 `onRemove` 掉 ⇒ **TNT/爆炸摧毁时一件不掉** | 需确认是否有意 |
| — | `MixinExtremeSmithingMenu.INFINITY_UPGRADE` 的求值时机存疑 | 需对 Avaritia 做 `javap -v`，该模组不在本地缓存 |

### 新增护栏（337 → 359 测试）

| 测试 | 守什么 |
|---|---|
| `TestTextEncodingIntegrity` | 源码与资源的文本完整性。实测抓到 3 处 U+FFFD（`GuiMekCkSideConfiguration:45`、`PlantingCuttingFactoryExecutor:205`，以及该测试自己第一版用字面量写出的那一处）。**第一轮审查只扫了资源文件因此漏掉 `.java`** |
| `TestNoClientSymbolsInCommonCode` | common 侧不得引用客户端类。`KNOWN_VIOLATIONS` 是**可见的债清单**（6 个 BE 的 `clientTick` → `SoundHandler`、2 个 `Item` 的 `mekanism.client.key`），配一条「每条必须真的还在违规」的反向断言，防清单变成谎言。另有更精确的一条：`network/` 目录零例外 |
| `TestMixinRefmapIntegrity`（新增 1 条） | dev 运行必须传 `mixin.env.remapRefMap`（见上面「本轮引入又修掉的回归」） |
| `TestFactoryGuiSyncSurface` | 工厂 GUI 的同步面：每个 `SyncableInt` 的数据源、getter 与 setter 必须读写**同一批字段**（写错字段的症状与「完全没有同步」完全一样，排查毫无线索） |
| `TestKitchenOutputMergeOverflow` | 产物归并的总量守恒 |
| `TestAttackRadiusClamp` | 攻击半径上下界 + **4 台机器的两个 setter 都必须走共享闸门**（逐文件检查） |
| `TestGrindingOrderEngine`（扩） | 订单推进溢出 + `quantity=0` 的旧档被抬到 1 |

> **两条方法论教训**（都写进了相应测试的注释）：
> ① 一次 `ItemStack.<clinit>` 失败会**毒化整个测试会话**——后续所有碰 `ItemStack` 的用例都变成
> `NoClassDefFoundError: Could not initialize class`。bootstrap 必须逐字照抄
> `TestCuttingBatchPacking#boot` 的配方（`SharedConstants` + 吞 Forge 钩子异常）且放在 `@BeforeClass`。
> ② 第一轮审查的编码检查只扫了 `.json/.toml/.mcmeta/.cfg/.md`，**漏了 `.java`**，因此三轮都以为编码没问题。

---

## 二、需你裁决（第二轮遗留）

> **I1 已关闭**：第三轮把它重新框定为 I-N5，**但第三轮并没真的修** —— 本文件前一版在这里
> 写着「已做完（逐菜单表见报告）」，而全仓检索不到任何那份报告，代码里
> `SimpleMachineMenu.getEnergy()` 也仍是裸的 `data.get(DATA_ENERGY)`。
> **这是一条虚假声明，第四轮勘正。**
>
> 真正的修法见 §一「已修（第四轮）」：不是「同步缩放值或百分比槽」，而是**拆两个槽**（无损）。
> 下表保留原始判断，作为「当时为什么判错」的记录。

| # | 问题 | 为什么不能机械修 |
|---|---|---|
| **I1** | `ContainerData` 走 `ClientboundContainerSetDataPacket`，字节码证实对 value 用 **`writeShort`**，17 个 BE 裸同步能量 → 客户端显示负数 | **缩放修不了**：装下 4 亿需除数 ≥12211，那样 4000 FE 的普通机器会显示 0。**Mek 整个 jar 里引用 `ContainerData` 的类数为 0**，它走另一套通道，照搬属架构改动。且这是**纯显示问题**，无数据丢失 |
| **I3** | `SideMode.NONE` 落到 `default ->` 返回全权限，六面默认全 NONE ⇒ 侧面配置失效 | 改成返回空会**断掉现有玩家**建立在「默认全开」上的全部管道/漏斗。且 `PULL_INPUT_STORAGE`（枚举第 4 值）**没有任何 capability 对应**——BE 里只有 full/input/output 三个 |

---

## 三、待办（按性质分）

### 内容缺口（需要你定设计）
- 16 个新物品（8 串烧 + 8 烤制）**刻意没设 `food` 属性**——营养值得与主料逐条对齐才算平衡。

### 已关闭
- ~~`frost_cold_brew_upgrade` 的 `minecraft:powder_snow`~~ → **已修为 `powder_snow_bucket`**（`c234e71`）。
  该 id 在 1.20.1 不是物品（`Items` 里只有 `POWDER_SNOW_BUCKET`），配方在任何环境都失败，
  且卡死冷萃攻击链后面两环（链式安装）。选桶而非蓝冰是为保住这条链
  「越往后越贵越冷」的递增感：packed ice → blue ice → 细雪桶 → 暮色森林女王奖杯。

---

## 四、配方层现状：残留错误归零

`tools/predict_residual_recipe_errors.py`（按实例已装 mod 集合模拟 Forge 条件判定）：

```
clean (will load)   : 137
skipped by condition: 425
residual failures   : 0
```

**462 个失败配方已全部清零。** 其中：
- 422 个在本仓库（`5a36aa4` 条件化）
- 1 个 `frost_cold_brew_upgrade`（`c234e71` 改材料）
- 1 个 `creative_upgrade_from_49_foods.json`（另一 AI 的 `541f017` 补了
  `forge:mod_loaded: avaritia` 条件门控——注意它的 `type` 仍是不存在的
  Avaritia 序列化器，条件不通过时整条配方被跳过，不报错）
- 40 个来自用户世界存档的数据包 `mekck_planting`，属世界本地内容，**未改**

> 工具预测有已知局限：`forge:item_exists` 只按 modid 判断，无法离线确认具体物品
> 是否注册。真正的确认要启动客户端看 `logs/latest.log`。

### 跨 agent 交接
- `creative_upgrade_from_49_foods.json`（阶段 1 Task 8）的 `type` 是
  `avaritia:shapeless_table`，而 Avaritia **从未在 `mods.toml` 声明为依赖**。
  只把产物换成 `mekck:upgrade_randomize` **修不好它**，`type` 仍需改。
  这是配方条件化后**唯一**的残留失败项。

### 审查报告里仍未修
- I2（`GrillFactoryBlockEntity` 漏写 `Size` 键，玩家升级区块崩溃）属 A 组，随 Mek 迁移走
- I8 / I9 / I10，其中 I9（`bakeries` 配方含空 Ingredient 时加工机永久静默卡死）可达性未坐实

---

## 五、环境注意事项（会浪费时间的坑）

1. **构建输出编码**：Gradle 走 GBK 控制台，中文错误信息在 bash 里是乱码。
   用 `pwsh -NoProfile -Command '[Console]::OutputEncoding=[Text.Encoding]::UTF8; ...'` 一步解决。
2. **日志别放 `build/` 里**：`clean` 会因文件占用失败。放 `.logs/`（已加进 `.gitignore`）。
3. **并发构建**：两个 AI 同用一个 `build/` 目录会互相踩，本轮撞到 3 次文件锁
   （`clean` 失败 / `reobfJar` 删不掉临时文件）。并行推进建议各用各的工作目录。
4. **查 API 别用 `javap` 猜字段名**——`~/.gradle/caches/forge_gradle/maven_downloader/net/minecraftforge/forge/1.20.1-47.4.16/forge-1.20.1-47.4.16-sources.jar`
   里有全部 Forge 源码**和 vanilla 补丁**。本轮有一次错误的字段名记录（`forge:conditional`
   的 `conditions`/`recipe`）就是 javap 常量池把两个类的字段混了造成的。
5. **`clean` 是必须的**：增量编译对跨类引用是盲的。
6. **`clean` 会删掉 `build/fg_cache`，所以 `--offline` 构建不可靠**：验收口径因此是
   **联网**的 `./gradlew build`（见文首）。`--offline` 不允许 ForgeGradle 去补被删掉的缓存。
   详见 [`audit/2026-09-30-full-code-review.md`](audit/2026-09-30-full-code-review.md) §五「最终验证」。
7. **⚠️ 本机代理当前不可用（2026-10-01 实测）**：环境变量
   `HTTP_PROXY` / `HTTPS_PROXY` 指向 `http://127.0.0.1:10793`，但**该端口无进程监听**
   （`Test-NetConnection` 返回 False），于是 ForgeGradle 报
   `Failed to validate certificate for host 'https://maven.minecraftforge.net/'`
   ——**这不是证书问题，是连接被拒**。症状具有欺骗性：报错说证书，真实原因是代理没开。
   - 直接连（清空 proxy 变量）时：`repo1.maven.org` 通、`libraries.minecraft.net` 通，
     但 **`maven.minecraftforge.net` 超时**（本网络直连不通）。
   - **可用解法**：本次验收用 `$env:HTTP_PROXY=''; $env:HTTPS_PROXY=''` 清空代理变量后
     `.\gradlew.bat build` 成功跑完（`downloadMcpConfig` 等 ForgeGradle 任务照常完成，
     依赖全部命中本地缓存）。**长期解法是把代理客户端开起来。**
   - **注意：绕过代理之后网络是抖的。** 同一命令连续两次跑，一次 34 s 成功、
     一次 42 s 就死在同一个证书报错上。**遇到这个报错先原样重试一次**，
     不要立刻判定成代码问题或去加 `--offline`（§五.6）。
   - `~/.gradle/gradle.properties` 里已有 `systemProp.net.minecraftforge.gradle.test_certs=false`，
     那只跳过证书校验、**不能**解决连接被拒。
8. **别信「javac 解析检查 = 编译通过」**：worker 用 `javac` 单文件检查时把 100 个
   「程序包不存在 / 找不到符号」当成可忽略的 classpath 噪音，于是漏掉了
   `TestNoHardcodedUiText.java` 里一处 `codeText` 写成字段、实际是方法的错误
   ——`compileTestJava` 一秒就红。**单文件 javac 的解析检查只保证没有语法错误，
   不保证名字解析正确**；真结论只能来自 Gradle。

9. **✅ 实机验证是可行的（2026-10-05 更正）**：此前多次记「本机 dev 环境起不来 ⇒
   无法实机验证」，那说的是 `runClient`（ForgeGradle 的 dev 环境，受 Farmer's Delight
   自身 mixin 注入失败影响）。但另有一套**独立的生产客户端安装**可用：

   ```
   D:\mc\新建文件夹\versions\1.20.1-Forge_47.4.23\
   ```

   内含 `mods/mekck-1.0.0.jar` + Mekanism / Farmer's Delight / AE2 等依赖，
   且留有历史备份 `mekck-1.0.0.jar.bak-*`。仓库根的 `.launch-verify.py`
   就是启动它并等 `logs/latest.log` 落地的脚本。
   **验证流程**：`./gradlew build` → 把 `build/libs/mekck-1.0.0.jar` 覆盖过去
   （先备份）→ 启动 → 看日志。

   > 本轮未执行实机验证（用户明确表示自行完成），上面只记录**环境存在且可用**
   > ——2026-10-03 的 `latest.log` 有完整的进游戏与正常退出序列。

10. **WSL 下跑 Gradle 的两个坑（本轮实测）**：
    - 本仓的 `gradlew` 用 `-x`（POSIX 可执行位）找 java，而 WSL 下 `java.exe`
      没有该位 ⇒ 必须用包装脚本或直接调发行版：`gradle-8.8/bin/gradle.bat`；
    - Gradle 输出走 GBK，bash 里是乱码 ⇒ `... | iconv -f GBK -t UTF-8`。
    - JDK 在 `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot`
      （**不在** `Program Files\Java` 下）；`GRADLE_USER_HOME` 在
      `C:\Users\Administrator\.gradle`。

---

## 六、方法论教训（避免重蹈）

- **⚠️ 源码形态护栏必须做变异测试，否则不知道它会不会响**（第五轮实测，**三次变异暴露了两个我自己的护栏 bug**）：

  | # | 注入的变异 | 护栏是否抓住 | 说明 |
  |---|---|---|---|
  | 1 | 在 `client/` 塞一处硬编码中文常量 | ✅ **红** | `noHardcodedCjkInPlayerVisibleText` 如期失败并打印语句原文。判据可用 |
  | 2 | `getItem()` 改成 `isClientSide() && clientVisible != null`（语义等价） | ✅ 绿 | **绿是对的**——行为没变，护栏不该响 |
  | 3 | `getItem()` 改成 `isClientSide() && false`（**缺陷完整复现**） | ❌ **绿（漏网）** | 客户端分支整个失效、静默回落到读空桩 `machine.items`，而原有 6 条断言**全部照旧为真** ⇒ 补 `clientBranchIsGatedBySideAlone` |

  补完第 7 条断言后，**它自己又错了两次**，都是同一个变异暴露的：

  | 轮次 | 判据写法 | 干净树上 | 变异树上 | 问题 |
  |---|---|---|---|---|
  | 4 | `substring(open+1, close)` | ❌ 红 | ❌ 红 | 闭区间 off-by-one，切出 `isClientSide`（少右括号）。**在干净代码上也红**——看起来像「抓到变异」，实则是判据自身坏了 |
  | 5 | `substring(open+1, close+1)` | ✅ 绿 | ✅ **绿（漏网）** | 第一个 `)` 是 `isClientSide()` 自己的右括号，条件被截断，`&& false` 根本看不见 |
  | 6 | 按括号**配对**扫描到 if 条件的右括号 | ✅ 绿 | ✅ **红** | 正确 |

  ⇒ 三条教训：
  1. **「变异测试红」不等于「护栏抓到了变异」。** 必须**先在干净树上确认绿**，再注入缺陷确认红。只看红会把自己判据的 bug 误当成验证通过（第 4 轮）。
  2. **字符串判据里凡是用「第一个 X」定位的，几乎都该改成「配对扫描」。** `indexOf(')')` 在有嵌套括号的源码上是错的。
  3. 这个项目的护栏风格（源码形态断言）**天然容易写出这种假绿**，
     因为「结构对」和「结构在跑」是两件事，而静态断言天然只能看前者。
     **新增任何源码形态护栏，一律先跑变异测试。**

- **三次「查证后判定不是缺口」，避免了无效改动**：COOKING 不需要自有配方类型
  （FD 是强制依赖 + 28 条配方，硬造会脱离 FD/森罗/avaritia 生态丢集成）；
  COOKING 输入槽其实已认森罗食材；`alloys/spectrum` 其实能解析到真实物品。
  **动手前先查证**。
- **三次测试自己写错**：假罐没做「最多只能抽罐内存量」的钳制；断言了 400 而实际应为 0；
  读侧/写侧方法名按全等比较。**测试写错时先怀疑测试**，别改生产代码。
- **一处审查报告描述错误**已订正：`getByte` 返回**有符号** byte，5000 往返是 **-120** 不是 136。
- **MixinGradle + 注解处理器那条路走不通，refmap 必须手写**：AP 会对 4 个
  `remap = false` 的 mod 类 mixin 报成员级错误——合成 lambda 名、`$VALUES`/`UPGRADES` 这类
  javac 合成字段没有映射——而 `disableTargetValidator` / `@Pseudo` 只能消掉其中一条。
  `src/main/resources/mekck.refmap.json` 因此是**手写**的，由 `TestMixinRefmapIntegrity`
  用三条断言钉住；最强的一条拿 `build/reobfJar/mappings.tsrg`（ForgeGradle 重混淆**自己用的那份映射**）
  核对 SRG 名，**名字写错也能抓到**（已用变异测试证明：把 `m_41739_` 改成别的值，断言立刻变红）。
  ⇒ **只要有人给 `MixinItemStack` 新加一条打原版方法的 `@Inject`，测试先红，而不是等启动崩。**
  （`.gitignore`、`build.gradle`、`TestMixinRefmapIntegrity` 三处都引用本节。）
- **批量替换带 `if` 包裹的代码块**要连包裹一起删（改写 `findOrderRecipe` 时留了个多余花括号）。

---

## 七、文档索引

**全部文档的清单与状态标记见 [`README.md`](README.md)。** 这里按「该不该读」分组。

### 现行（改动前应读）

| 文档 | 内容 |
|---|---|
| [`superpowers/specs/2026-09-29-mek-machine-architecture.md`](superpowers/specs/2026-09-29-mek-machine-architecture.md) | **架构 v4**，阶段 2 规格（995 行） |
| [`superpowers/specs/2026-09-29-mek-native-machine-framework-design.md`](superpowers/specs/2026-09-29-mek-native-machine-framework-design.md) | 阶段 1 升级体系设计 |
| [`superpowers/handoff/2026-09-29-phase1-runtime-verification.md`](superpowers/handoff/2026-09-29-phase1-runtime-verification.md) | 阶段 1 实机验证清单（需能启动 MC 的环境） |

### 历史

| 文档 | 内容 |
|---|---|
| [`audit/2026-09-30-round2-archive.md`](audit/2026-09-30-round2-archive.md) | **第二轮归档**——本文拆分出的原 §〇 + §一 + §七 |
| [`audit/2026-09-30-full-code-review.md`](audit/2026-09-30-full-code-review.md) | 第二轮全量审查报告（6 个 Critical） |
| [`audit/2026-09-30-details/`](audit/2026-09-30-details/) | 第二轮分域明细 4 份：`machine-core` / `legacy-conservation` / `ae2-network-menu` / `client-recipe-mixin` |
| [`audit/2026-09-29-full-code-review.md`](audit/2026-09-29-full-code-review.md) | 第一轮全量审查报告，I1–I10 处置状态 |
| [`audit/2026-09-29-conditional-recipe-report.md`](audit/2026-09-29-conditional-recipe-report.md) | 配方条件化报告 |
| [`superpowers/plans/2026-09-29-mekck-phase1-upgrade-system.md`](superpowers/plans/2026-09-29-mekck-phase1-upgrade-system.md) | 阶段 1 实施计划 |
| [`superpowers/plans/2026-09-30-mekck-phase2-cutting-factory.md`](superpowers/plans/2026-09-30-mekck-phase2-cutting-factory.md) | 阶段 2 实施计划 |
| [`superpowers/specs/2026-09-29-objmesh-loader-design.md`](superpowers/specs/2026-09-29-objmesh-loader-design.md) | OBJ 网格加载器设计（已实施） |
| [`superpowers/handoff/2026-09-29-recipe-availability-handoff.md`](superpowers/handoff/2026-09-29-recipe-availability-handoff.md) | 旧交接文档，**主题已关闭**，保留作 type id 证据表 |

### 相关

- [`README.md`](README.md) —— 全部文档清单与状态
- [`../tools/README.md`](../tools/README.md) —— 一次性脚本与审计工具
- [`../README.md`](../README.md) / [`../README.zh_CN.md`](../README.zh_CN.md) —— 面向玩家的模组说明

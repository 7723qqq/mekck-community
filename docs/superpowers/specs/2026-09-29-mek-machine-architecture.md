# mekck × Mekanism 机器框架 —— 项目架构（v3）

- 日期：2026-09-29
- 状态：**阶段 2（配方接入机器框架）的架构规格**，实现由另一方进行
- 关联：`2026-09-29-objmesh-loader-design.md`（已完成）、
  `2026-09-29-mek-native-machine-framework-design.md`（阶段 1 升级体系，**另一份 spec**）、
  `../plans/2026-09-29-mekck-phase1-upgrade-system.md`（阶段 1 实施计划，Task 1+2 已提交 `75523b3`）、
  `../audit/2026-09-29-full-code-review.md`

**版本沿革**
- v1：分层与模板方法（配方机制**凭想象写错**）
- v2：补多方块、迁移范围边界、工艺特征矩阵、进度存档、升级公式、失败模式
- **v3：按 `javap` 实测重写 §6 核心抽象与 §10 失败模式**。
  Mek 1.20.1 **没有** `tick()` 可覆写（扩展点是 `onUpdateServer()`），
  也**没有** `RecipeFinder` / `BasicMachine` / `ITickingTile`；
  配方走 `IRecipeLookupHandler` + `RecipeCacheLookupMonitor` + `CachedRecipe` 三件套。
  v1/v2 的自定义泛型 + `findRecipe`/`executeRecipe` 抽象方法**已废弃**。
- **v4：按用户复审补齐缺口**——
  §5 清除 v1/v2 遗留（自定义配方泛型 / `MekCkFactoryRecipe` / 不存在的
  `MekCkUpgradeRegistry`）；§6.5 实测每个工艺的配方来源（发现 3 个工艺无自有配方）；
  §8.3 档位接入 Mek `ITier`（含硬边界）；§10.1 三个特例工艺的失败模式；
  §11 第 6/7/8 条补齐；新增 §14 条件化方块注册、§15 测试策略。

**两份 spec 的分工**
| 阶段 | 权威文档 |
|---|---|
| 阶段 1 升级体系 | `2026-09-29-mek-native-machine-framework-design.md` + `../plans/…-phase1-upgrade-system.md` |
| 阶段 2 配方接入机器框架 | **本文档** |
| 多方块 | 两份重叠，以本文档 §4 为准（`BlockBasicMultiblock` 继承自 `BlockTile`，不推翻现有继承链） |

> 注意：另一份 spec §2.2 描述的「缺 `AttributeGui` / `AttributeStateFacing` / `AttributeUpgradeSupport`」
> 状态**已过时**——当前 `MekCkFactoryRegistration` 三者均已具备。

---

## 1. 迁移范围（v2 新增，v1 含糊导致）

mekck 共 **20 个方块类**。它们分属三套不同的工艺模型，**不能一并迁移**：

| 组 | 数量 | 代表 | 工艺模型 | 是否在本次范围 |
|---|---|---|---|---|
| **A. 工厂** | 7 家族 × 12 档 = **84 方块** | CookingFactory / CuttingMachineFactory / GrillFactory / GrindingFactory / IceFactory / PlantingCuttingFactory / SkeweringFactory | 等级驱动的并行槽位 + 配方 | **是**（阶段 1–2） |
| **B. 联动机器** | 15 | SimpleMachine 系（寿司卷制机 / 榨汁机 / 搅拌机 / 智能烤炉 …） | `SimpleMachineBlockEntity` 的 `matchXxx` 家族，模块化 | 否（阶段 5+） |
| **C. 独立机器** | 5+ | Bioreactor / CentralKitchen / ChocolateCannon / SandwichAssembler / WineCellar | 多方块或特殊交互 | 否（阶段 5+） |

**本架构只覆盖 A 组**。理由：A 组已有 `MekCkFactoryType` / `MekCkFactoryTier` /
`TileEntityMekCkFactory` 的完整骨架，抽象一次可复用到 84 个方块；
B 组的 `matchXxx` 家族与 Mek 槽位模型冲突，需单独立项；
C 组含多方块与联机交互，复杂度另计。

> 越界风险：若实现方把 B/C 组也拉进来，会撞上 §4 的多方块问题，
> 且 `SimpleMachineBlockEntity` 4848 行无法机械改写。

---

## 2. 规模与约束

| 项 | 数量 |
|---|---|
| 工艺家族 | 7 |
| 等级 | 12（BASIC…SINGULARITY，并行 3/5/7/9/11/13/15/17/25/36/49/81） |
| **方块总数** | **84**（`mekck:<tier>_<type>_factory`） |
| 其中**多方块** | 12（仅 PLANTING_CUTTING，见 §4） |
| 每方块需 | 菜单 + 屏幕（Mek 风格 `IConfigurableTile` GUI） |

84 个方块决定了架构取向：**模板方法 + 泛型**，任何逐方块写逻辑的方案都不可行。

---

## 3. 现状盘点

`mekckfactory` 新套骨架**已完成**：

| 已完成 | 文件 |
|---|---|
| Mek 机器基类（侧配 / 能量 / 槽位方阵） | `factory/TileEntityMekCkFactory.java`（239 行） |
| Mek 方块基类 | `factory/MekCkFactoryBlock.java`（46 行） |
| 方块注册（`AttributeEnergy` / `AttributeStateFacing` / `AttributeUpgradeSupport`） | `factory/MekCkFactoryRegistration.java` |
| 工艺枚举（7 个） | `factory/MekCkFactoryType.java`（77 行） |
| 等级枚举（12 档） | `factory/MekCkFactoryTier.java`（114 行） |
| 菜单 / 屏幕 | `factory/MekCkFactoryMenu.java`、`MekCkFactoryScreen.java` |

`MekCkFactoryTile.java` 是 **19 行空壳**，javadoc 明写「尚未接入配方查找」。

**三处必须知道的事实**：

1. `MekCkFactoryType` 的 javadoc 提到 `recipeTypeName`「用于接入配方查找时定位本工艺的 RecipeType」，但**代码中不存在该字段**。实现时需补上，或由 `typeName` 承担。
2. `TileEntityMekCkFactory` 继承 `TileEntityConfigurableMachine`——**平行于** Mek 的 `TileEntityFactory`，不是其子类。故 `addSlots` 模板方法在父类链上**不存在**，`@Override` 不成立；Mek 工厂的写法不可照抄。
3. 父类 `TileEntityMekanism` 构造器内部回调子类的 `presetVariables()` / `getInitialEnergyContainers()` / `getInitialInventory()`，此刻子类字段初始化器**尚未执行**。依赖实例字段的逻辑必须从 `blockProvider` 反查（`tierFromBlock()` / `typeFromBlock()` 是现成范例）。本项目已因此发生三次实机 NPE。

---

## 4. 多方块（v2 新增）

**7 个工艺里 PLANTING_CUTTING 是多方块**（旧 `PlantingCuttingFactoryBlock` 用
`MekCkMultiblock`，形状 `SHAPE_3X3X2` 或 `SHAPE_2_TALL`，以实测为准）。
其余 6 个是单方块。

Mek 1.20.1 侧的多方块支持（**已实测，v3 补全 tile 侧**）：

| 类 | 继承 | 说明 |
|---|---|---|
| `mekanism.common.tile.prefab.TileEntityMultiblock<T extends MultiblockData>` | `TileEntityMekanism` | **不继承** `TileEntityConfigurableMachine`；实现 `IMultiblock<T>` + `IConfigurable` |
| `mekanism.common.tile.machine.TileEntityDigitalMiner`（Mek 自有多方块机器） | `TileEntityMekanism` | 同样直接继承根类；实现 `IBoundingBlock` / `IChunkLoader` / `ISustainedData` |
| `mekanism.common.block.prefab.BlockBasicMultiblock<TILE extends TileEntityMekanism>` | `BlockTile<TILE, BlockTypeTile<TILE>>` | 与现有 `MekCkFactoryBlock` 同源，不推翻方块侧继承链 |
| `mekanism.common.block.attribute.AttributeMultiblock` | — | 方块属性：`EXTERNAL` / `STRUCTURAL` / `INTERNAL`；`getMultiblock(Level, BlockPos, UUID)` |
| `mekanism.common.lib.multiblock.MultiblockData` / `IMultiblock<T>` | — | 多方块数据与接口 |

**结论**：`TileEntityMultiblock` 与 `TileEntityConfigurableMachine` 是**平行**的
（共同祖先只有 `TileEntityMekanism`）。若 PLANTING_CUTTING 走 Mek 多方块机制，
必须换 tile 基类。

**但换基类的成本很低**——`TileEntityConfigurableMachine` 只提供 7 个成员：

```
public TileComponentEjector ejectorComponent;
public TileComponentConfig  configComponent;
getConfig() / getEjector()
getConfigurationData(Player) / setConfigurationData(Player, CompoundTag)
protected onUpdateServer()   // 调 ejector
```

而 `TileEntityMekCkFactory`（第 146–155 行）**已经自己 new 了那两个字段**，
槽位方阵（`getInitialInventory`）与能量容器（`getInitialEnergyContainers`）也都是自实现。
换基类后只需补：实现 `ISideConfiguration` 的 4 个方法 + 侧配 GUI 的数据传输 +
`onUpdateServer()` 里调 ejector，**约 50 行**，不涉及已踩坑 thrice 的构造期时序逻辑。

**决定：采用「接入 Mek 多方块」路线**（原路线乙）。以下 API 全部 `javap` 实测。

### 4.1 模式与 Mek 内部先例

Mek 内部有 5 个类走这条线（`TileEntityMultiblock<T>` + 自建 `MultiblockData` 子类）：

| tile | 数据类 |
|---|---|
| `TileEntityBoilerCasing` | `BoilerMultiblockData` |
| `TileEntityDynamicTank` | `TankMultiblockData` |
| `TileEntityInductionCasing` | `MatrixMultiblockData` |
| `TileEntitySPSCasing` | `SPSMultiblockData` |
| `TileEntityThermalEvaporationBlock` | `EvaporationMultiblockData` |

（Mek 的单方块多方块机器如 Digital Miner 走的是另一条 `IBoundingBlock` 路线，**不参照**。）

### 4.2 需实现的两个类

`TileEntityMultiblock<T>` 的**唯一**关键抽象方法是 `getDefaultData()`；
构造器 `(IBlockProvider, BlockPos, BlockState)`，另有 `setStructure` / `getStructure` /
`canBeMaster` / `resetForFormed` / `onActivate` / `getCacheID` / `resetCache` 等可用。

`MultiblockData` **不是抽象类**，可直接继承；构造器 `(BlockEntity)`。
它已实现 `IMekanismInventory` / `IMekanismFluidHandler` / `IMekanismStrictEnergyHandler` /
`ITileHeatHandler` 及各 chemical tracker——**多方块数据自带库存 / 流体 / 能量接口**，
mekck 的多方块工厂可直接受益。

```java
// factory/MekCkPlantingCuttingMultiblockData.java
public class MekCkPlantingCuttingMultiblockData extends MultiblockData {
    public MekCkPlantingCuttingMultiblockData(BlockEntity tile) {
        super(tile);
    }
    // 结构形成的判定覆写 formedBiPred() / notExternalFormedBiPred()（如需要）
    // 脏标记复用父类 isDirty() / resetDirty() / markDirty()
}

// factory/MekCkPlantingCuttingTile.java
public class MekCkPlantingCuttingTile
        extends TileEntityMultiblock<MekCkPlantingCuttingMultiblockData> {
    @Override
    public MekCkPlantingCuttingMultiblockData getDefaultData() {
        return new MekCkPlantingCuttingMultiblockData(this);
    }
    // 配置组件：自建（见 4.3）
}
```

### 4.3 换基类的成本（已量化）

`TileEntityConfigurableMachine` 只提供 7 个成员：

```
public TileComponentEjector ejectorComponent;
public TileComponentConfig  configComponent;
getConfig() / getEjector()
getConfigurationData(Player) / setConfigurationData(Player, CompoundTag)
protected onUpdateServer()   // 调 ejector
```

而 `TileEntityMekCkFactory` **已自建**那两个字段（第 146–155 行），
`getInitialInventory`（槽位方阵）与 `getInitialEnergyContainers` 也都是自实现。

换基类后需补：`implements ISideConfiguration`（4 个方法）、侧配 GUI 的数据传输
（`getConfigurationData` / `setConfigurationData`，照抄 `TileEntityConfigurableMachine`
实现）、`onUpdateServer()` 里调 `ejectorComponent.tickServer()`。
**约 50 行，不触碰已踩坑三次的构造期时序逻辑。**

### 4.4 方块侧

- `MekCkFactoryBlock extends BlockTile` → PLANTING_CUTTING 改用
  `BlockBasicMultiblock<TILE extends TileEntityMekanism>`（**继承自 `BlockTile`，
  与现有方块同源**，不推翻方块侧继承链）。
- 方块属性加 `AttributeMultiblock`（`EXTERNAL` / `STRUCTURAL` / `INTERNAL`）。
- 6 个单方块工艺**保持** `BlockTile` + `TileEntityConfigurableMachine` 不变。

### 4.5 与 mekck 现有 `MekCkMultiblock` 的关系

旧实现的 `util/MekCkMultiblock`（`SHAPE_2_TALL` / `SHAPE_3X3X2` / `SHAPE_2X2X3` 等形状
常量 + `placeBoundingBlocks` / `removeBoundingBlocks`）服务的是 B/C 组旧机器，
阶段 4 之前**保留不动**。A 组的 PLANTING_CUTTING 改用 Mek 机制后，
两套多方块并存是预期的——不要在阶段 2 顺手删旧的，会连带破坏 B/C 组。

---

## 5. 分层架构

> **v4 更正**：本节原为 v1/v2 版本，遗留了两处已被 v3 推翻的内容——
> `TileEntityMekCkFactory<MekCkXxxRecipe>` 的**自定义配方泛型**，以及要新建的
> `MekCkFactoryRecipe` 统一接口。二者与 §6.2 的决策直接冲突（配方走 Mek 的
> `IRecipeLookupHandler` + `CachedRecipe`，**不引入自定义配方基类与泛型参数**）。
> 照原图实现会重造 v1/v2 被废弃的抽象。下面是订正后的结构。

```
mekck/
├── factory/                     Mek 机器层（方块 / 通用 tile / GUI / 注册）
│   ├── MekCkFactoryRegistration     84 个方块注册（★ 条件化注册，见 §14）
│   ├── MekCkFactoryBlock            extends BlockTile
│   ├── MekCkFactoryMultiblockBlock  ★ 新增：PLANTING_CUTTING 用
│   ├── MekCkFactoryType             7 工艺（需补 recipeTypeName）
│   ├── MekCkFactoryTier             12 等级（★ 实现 ITier，见 §8.3）
│   ├── TileEntityMekCkFactory       ★ 抽象基类：全部通用加工流程（不泛型）
│   └── MekCkFactoryMenu/Screen
│
├── machine/                      工艺实现层（每工艺一个）
│   └── MekCkXxxFactoryTile extends TileEntityMekCkFactory
│
├── recipe/                       配方层
│   ├── MekCkXxxRecipe              继承 Mek 的 ItemStackToItemStackRecipe
│   └── MekCkXxxRecipeSerializer    ItemStackToItemStackRecipeSerializer<>(…)
│
└── upgrade/                      升级层
    └── MekCkUpgradeTypes          阶段 1 产出（★ 名字以阶段 1 plan 为准）
```

**依赖方向严格单向**：`machine → recipe → factory`。`factory` 层不认识任何具体工艺。

**命名权威**：升级层的类名以 `../plans/2026-09-29-mekck-phase1-upgrade-system.md`
为准（`MekCkUpgradeTypes` / `MekCkUpgradeCodec`）。本节早期版本写的
`MekCkUpgradeRegistry` **不存在**，不要创建。

---

## 6. 核心抽象

> **⚠ v3 重大修订**：本章 v1/v2 假设的「自定义泛型 + `findRecipe`/`executeRecipe` 抽象方法 +
> `tick()` 覆写」**经实测全部不成立**。Mek 1.20.1 有既定的三件套机制，
> 应接入而非另建。以下为 `javap` 实测结论（详见 §11）。

### 6.1 tick 扩展点：`onUpdateServer()`，不是 `tick()`

`TileEntityMekanism` **不覆写 `tick()`**。真实链路：

```
Forge BE ticker（Mek 在注册时装好）
  → TileEntityMekanism.tickServer(Level, BlockPos, BlockState, TileEntityMekanism)  [static]
    → frequencyComponent.tickServer()
    → upgradeComponent.tickServer()      （仅 supportsUpgrades() 时）
    → chunkloader（若 IChunkLoader）
    → 翻转 BlockState "active"
    → onUpdateServer()                    ★ protected，唯一扩展点
    → 辐射/热量/comparator/ticker++
```

`onUpdateServer()` 在根类里是**空实现，专为被覆写而存在**。
`TileEntityConfigurableMachine` 已覆写它并调 `ejectorComponent.tickServer()`。

```java
@Override
protected void onUpdateServer() {
    super.onUpdateServer();      // 必须先调：ejector + 父类逻辑
    // 配方监控
    for (RecipeCacheLookupMonitor<RECIPE> monitor : monitors) {
        monitor.updateAndProcess();      // 每 tick 一次
    }
}
```

**注册要求已满足**：`MekCkFactoryRegistration:111` 用的是 2 参
`TILE_ENTITIES.register(block, supplier)` 重载（走 Mek 内建 ticker）。
若改用 4 参重载，必须自行提供 ticker。

### 6.2 配方三件套（Mek 1.20.1 既有机制）

| 角色 | 类 | 关键成员 |
|---|---|---|
| 每 tile 驱动 | `mekanism.common.recipe.lookup.IRecipeLookupHandler<RECIPE extends MekanismRecipe>` | `getRecipeType()` / `getRecipe(int)` / `createNewCachedRecipe(RECIPE,int)` |
| 查找与缓存状态机 | `mekanism.common.recipe.lookup.monitor.RecipeCacheLookupMonitor<RECIPE>` | `updateAndProcess()` — **每 tick 调一次** |
| 每配方执行 | `mekanism.api.recipes.cache.CachedRecipe<RECIPE>` | `process()` — 实际的 tick 步进状态机 |

**没有** `canProcess`/`executeRecipe` 这对共享接口方法。执行是
`CachedRecipe.process()` 状态机，由 `createNewCachedRecipe(...)` 里
一条流式 `set*` 链配置出来（`useEnergy` / `useResources` / `finishProcessing` /
`setActive` / `operatingTicksChanged` 等回调）。

**`CachedRecipe.process()` 的核心算法**（字节码直读）：

```java
if (canHolderFunction.getAsBoolean()) {
    setupVariableValues();
    tracker = new OperationTracker(errors, recheckAllErrors.getAsBoolean(), baselineMaxOperations.get());
    calculateOperationsThisTick(tracker);
    if (tracker.shouldContinueChecking()) postProcessOperations.accept(tracker);
    int currentMax = tracker.currentMax;
    if (tracker.hasErrorsToCopy()) updateErrors(tracker.errors);
} else { currentMax = 0; ... }
if (currentMax > 0) {
    setActive.accept(true);
    useEnergy(currentMax);
    operatingTicks++;
    int required = requiredTicks.getAsInt();
    if (operatingTicks >= required) { operatingTicks = 0; finishProcessing(currentMax); onFinish.run(); resetCache(); }
    else useResources(currentMax);
    if (required > 1) operatingTicksChanged.accept(operatingTicks);
} else { setActive.accept(false); if (currentMax < 0) { operatingTicks = 0; resetCache(); } }
```

注意 `currentMax < 0` 会**重置进度**——这正是 §10「失败模式」里
「产物放不下不得清空配方」在 Mek 机制下的落点：让 `currentMax` 保持 `> 0`
直到产物能放下，而不是返回负数。

**参考实现**：`mekanism.common.tile.prefab.TileEntityElectricMachine`
（最简单的完整机器）。`TileEntityEnrichmentChamber` 是它 1 方法的子类，
只覆写一个返回 `MekanismRecipeType.ENRICHING` 的方法。
**实现前先通读 `TileEntityElectricMachine` 的三个核心方法体。**

### 6.3 基类选型（v3 决策）

`TileEntityMekCkFactory` 当前 `extends TileEntityConfigurableMachine`。两个选项：

| 选项 | 做法 | 评价 |
|---|---|---|
| **A** | 改继承 `TileEntityProgressMachine<RECIPE>`，把能量/槽位装配搬进 2 参的 `getInitialEnergyContainers(listener, recipeListener)` / `getInitialInventory(listener, recipeListener)`（照 `TileEntityCombiner`） | 更贴 Mek，但改动面大且丢失 `TileEntityConfigurableMachine` 的侧配/ejector |
| **B（推荐）** | 保持 `TileEntityConfigurableMachine`，自持 `operatingTicks` / `CachedRecipe` / `RecipeCacheLookupMonitor[]` | 保留现有已验证的侧配/能量/槽位装配，改动面小 |

**取 B**。理由：A 会丢掉 `TileEntityConfigurableMachine` 已跑通的
`configComponent` / `ejectorComponent` / 槽位方阵装配（`TileEntityMekCkFactory`
第 146–205 行，且这些代码记录了三次实机 NPE 的教训，不该推倒）。
无论选哪个，`presetVariables()` 必须**先**创建
`configComponent` + `ejectorComponent`（父类构造期回调，字段初始化器尚未执行）。

### 6.4 工艺特征矩阵

从旧实现实测，决定各工艺 `getRecipe(int)` 的复杂度：


| 工艺 | 输入 | 输出 | 存储槽 | 特殊 |
|---|---|---|---|---|
| COOKING | 6 | 9 | **144** | 144 格材料库需一并迁到 Mek 槽位 |
| PLANTING_CUTTING | 依等级 | 依等级 | — | **多方块** |
| CUTTING | 依等级 | 依等级 | — | 单方块 |
| SKEWERING | 依等级 | 依等级 | — | 单方块 |
| GRILLING | 依等级 | 依等级 | — | 调味槽（seasoning）需单独建模 |
| GRINDING | 依等级 | 依等级 | — | 单方块 |
| ICE | 0 | 1 | — | **无输入槽**，靠外部供料 |

「依等级」= `MekCkFactoryTier.processes`（3–81）。
注意 `ICE` 的输入槽为 0，与 `TileEntityMekCkFactory.addSlotGrid` 的
「输入 = 输出 = processes」假设**冲突**，需特殊处理。

### 6.5 配方来源归属（v4 新增，实测代码得出）

§6.4 让人以为 7 个工艺各有自己的配方类型。**实测旧 BE 后发现不是。**
下表全部来自 `src/main/java` 的实际读取代码，不是推测：

| 工艺 | 配方 `RecipeType` 来源 | 命名空间归属 | mekck 自带配方数 |
|---|---|---|---|
| **GRILLING** | `barbequesdelight:grilling` | **外部，未声明依赖** | **0** |
| **SKEWERING** | `barbequesdelight:skewering` | **外部，未声明依赖** | **0** |
| **COOKING** | farmersdelight 烹饪 + `avaritia_delight:extreme_cooking_{shaped,shapeless}` + kaleidoscope 的 stockpot / pot / flex | **外部** | 0 |
| CUTTING | `mekanism:sawing` | Mek（强制依赖） | 94 |
| PLANTING_CUTTING | `mekanism:combining`（升级路径）/ `mekck:plantcut` | Mek / 自有 | 31 / 0 |
| GRINDING | `mekck:grinding` | 自有 | 1 |
| ICE | `mekck:ice_make` | 自有 | 4 |

（「自带配方数」= `src/main/resources/data/mekck/recipes/` 下引用该命名空间的文件数。）

#### 6.5.1 三个必须写进设计的结论

**结论 1：7 个工艺里有 3 个（GRILLING / SKEWERING / COOKING）没有 mekck 自有配方。**
用户诉求「注册我们的配方」在这三个工艺上**目前是空的**——不是没注册，是没有内容。
迁移到 Mek 机器框架时若照 §6.4 的印象给它们建自有配方类型，等于**放弃**这三条外部配方链，
是功能倒退。

**结论 2：`barbequesdelight` 在 mekck 的配方数据里引用数为 0，且不在 `mods.toml`。**
即：即使玩家装了 Barbeques Delight，GRILLING / SKEWERING 两个工厂
**在 mekck 侧也一个配方都没有**。这两个工厂目前实质上是空壳。
这不是迁移引入的问题，是现状缺陷——但迁移会把它固化成"看起来能跑、实际没有配方"的状态。

**结论 3：旧代码已有优雅降级，迁移必须保留。**
`GrillFactoryBlockEntity` / `SkeweringFactoryBlockEntity` / `CookingFactoryBlockEntity`
的模式都是：

```java
RecipeType<?> t = RecipeCache.type(new ResourceLocation("<外部命名空间>", "<类型>"));
if (t == null) return Optional.empty();     // 源 mod 缺席 → 安静不可用
```

即**配方源缺席时机器不报错，只是没有可用配方**。这是正确设计，
迁移到 `IRecipeLookupHandler` 时**不要改成硬依赖**。
`mods.toml` 也不应把 `barbequesdelight` 声明为强制依赖。

#### 6.5.1.1 烟熏炉上位（2026-09-29 用户决策，已实现）

原设计把原版三族（`SMELTING` / `SMOKING` / `BLASTING`）统一放在
「晶钛矩阵以上 + 配置开关」的门禁后。查 1.20.1 原版数据后确认这不合理：

| 类型 | 条数 | 实际内容 |
|---|---|---|
| `SMOKING` | 9 | 熟肉 ×7、烤马铃薯、干燥海带 |
| `CAMPFIRE_COOKING` | 9 | **与 smoking 完全相同**（同输入同产出） |
| `SMELTING` | 70 | 其中 47 条是**矿石与建材**，与 Mek 富集腔功能重叠 |
| `BLASTING` | 24 | **全是矿石** |

「烧烤工厂」能烧矿石既不合语义，又与 Mek 富集腔重复；而该 mod 未安装时，
低档 9 个档位完全空转（`barbequesdelight` 未声明依赖，mekck 侧引用数 0）。

**决策：拆开两个门禁。**

| 配方族 | 档位 | 配置 |
|---|---|---|
| `SMOKING` + `CAMPFIRE_COOKING` | **全部 12 档** | **不受配置约束** |
| `SMELTING` + `BLASTING` | 晶钛矩阵 / 星云 / 奇点创世 | 受 `crystal_matrix_grill_furnace_recipes` / `singularity_grill_furnace_recipes` 约束（默认开） |

已改动 6 处，**缺一不可**：

| 文件 | 改动 | 漏了会怎样 |
|---|---|---|
| `GrillFactoryBlockEntity.FOOD_COOKING_TYPES` | 拆出食物族常量 | — |
| `GrillFactoryBlockEntity.findRecipeUncached` | 食物族在门禁外遍历 | 配方找不到 |
| `GrillFactoryBlockEntity.isItemValid` | 输入槽校验加 `matchesFoodCooking` | **物品根本放不进输入槽** |
| `RecipeInputMatcher.matchesFoodCooking` | 新增公开判定 | 同上 |
| `JEIPlugin` | 食物族催化剂对 12 档注册 | JEI 显示与实际行为不符 |
| `MekckConfig` | 注释订正 | 配置说明与实际不符 |

> `isItemValid` 那一处最隐蔽：只改配方查找而不改槽位校验，玩家仍然无法投料，
> 表现为「机器启动不了但日志无报错」。

**这是权宜之计，不是修复。** 9 条配方配奇点创世 81 路并行仍然远远不够，
「低档烧烤配方的原始内容」缺口依然存在——见 §6.5.1 结论 2。
后续应建 `mekck:grilling` 自有类型补内容，届时工厂优先读自有类型、
再回落到原版烟熏炉族。

#### 6.5.1.2 串烧自有配方类型（2026-09-29 用户决策，已实现）

SKEWERING 与 GRILLING 同病（读 `barbequesdelight:skewering`，mekck 侧 0 条），
但用户给出了明确设计：**输入1 + 输入2 + 签子**。

**关键发现**：查代码发现原作者早就是这么设计的——
`SkeweringFactoryBlockEntity` 用反射按字段名取配方：
`getIngredientField(recipe, "tool"/"ingredient"/"side")` +
`getCountField(recipe, "ingredientCount"/"sideCount")`，
且 javadoc 明写 `slot 0 -> tool, slot 1 -> ingredient, slot 2 -> side`。
`completeRecipe` 还从输入槽 0 把 tool 取回放进 `returnSlot`——签子不消耗。

所以**新配方类严格对齐这套反射契约**（`tool`/`ingredient`/`side`/`ingredientCount`/`sideCount`），
零改动复用 `matchesSkewering` / `getMaxConsumableCount` / `consumeIngredients` 全套逻辑。

> ⚠ 命名陷阱（已写进类注释）：`consumeIngredients` 把 `ingredientCount` 读作
> **签子的消耗数**，主料则硬编码为 1。本类固定 `ingredientCount = 0`，
> 签子才不会被扣掉。改字段名前先读那段代码。

改动 6 处：

| 文件 | 改动 | 漏了会怎样 |
|---|---|---|
| `recipe/MekCkSkeweringRecipe.java` | 新增（字段名对齐反射契约） | — |
| `UniversalCuttingMachine` | 注册 `mekck:skewering` 类型 + 序列化器 | 类型不存在 |
| `UniversalCuttingMachine.ITEMS` | 注册 8 个串烧物品 | 产物不存在 |
| `SkeweringFactoryBlockEntity` | `getSkeweringRecipeTypes()` 自有优先 + 回落外部 | 找不到自有配方 |
| `RecipeInputMatcher.matchesSkewering` | 先查自有类型 | **输入槽放不进任何东西**，机器完全惰性且无报错 |
| `JEIPlugin` | 自有类型催化剂对穿串机 + 12 档工厂注册 | JEI 看不到配方 |

**串烧物品刻意不设 `food` 属性**：营养值要与主料/辅料逐条对齐才算平衡，
属内容设计，不在机制落地里编。串烧工厂的价值是并行处理 + 签子返还。

已生成 8 条配方（`data/mekck/recipes/skewering/`）+ 4 个食材标签
（`skewering_proteins` / `skewering_vegetables` / `skewering_mushrooms` / `skewering_sweet`），
资产由 `tools/gen_skewer_assets.py` 生成（纹理是程序画的占位图，可读但不是美术）。

> 顺带：§14.2 的条件化方块注册判据要跟着改——SKEWERING 有了自有配方后，
> 依赖不再是 `barbequesdelight`，**这 12 个方块变成无条件可注册**。
> 只剩 GRILLING 一组（24 个方块中的 12 个）仍受该规则约束。

#### 6.5.1.1a `mekck:grilling` 自有类型（2026-09-29，已实现，接续上一节）

烟熏炉上位只是权宜之计，本节补上自有类型，**GRILLING 的外部依赖就此解除**。

**为什么产物是新的"烤"变体**（`grilled_beef` 等 8 个），而不是复用 `cooked_beef`：
原版 `SMOKING` 的 9 条已经产出 `cooked_*`；若本类型产出同一种物品，它与烟熏炉
完全重复，等于什么都没加。烤制变体是刻意与"水煮/烟熏"区分开的第二条产物线。

**⚠ 字段可见性是硬约束**：`GrillFactoryBlockEntity.matchesInput` 用的是
`recipe.getClass().getField("ingredient")`——**`getField` 只找 public 字段**。
私有字段会抛 `NoSuchFieldException`，落到 `getIngredients().get(0)` 兜底分支，
而那条兜底是靠抛异常走的，位于每 tick 每槽的热路径上。
所以 `MekCkGrillingRecipe.ingredient` 必须是 `public final`。

> 这与 §6.5.1.2 的串烧形成对照：串烧走 `Reflect.field`（`getDeclaredField` +
> `setAccessible`，能拿私有字段），所以串烧的五个字段可以私有。
> **两处反射机制不同，可见性要求也不同，改前务必确认走的是哪一条。**

**不自带调味**：调味是 `BarbequesDelightCompat` 的 NBT 体系
（`isSeasonable(result)` → `applySeasoning`），另造一套没有依据。
`currentSeasoningFor` 对本类型产物因 `isSeasonable` 为 false 返回 null，行为安全。

改动 6 处，与 §6.5.1.2 同构：`MekCkGrillingRecipe` + 注册类型/序列化器 +
8 个烤制物品 + `GrillFactoryBlockEntity.collectGrillingRecipes()` 自有优先 +
`RecipeInputMatcher.matchesGrilling` 先查自有 + JEI 催化剂。
资产由 `tools/gen_grilling_assets.py` 生成（8 配方 / 8 纹理 / 双语 lang）。

> 收尾时踩到一次 `if (grillingType != null) {` 的残留：改写 `findOrderRecipe`
> 后留下一个多余右花括号，编译报「非法的类型开始」。批量替换带 `if` 包裹的
> 代码块时，务必连同包裹一起删。

#### 6.5.2 自有 RecipeType 全清单

全部注册在 `UniversalCuttingMachine.java`（`RECIPE_TYPES.register(name, …)`，
未写命名空间故均为 `mekck:` 前缀）：

| id | 配方类 | 归属 |
|---|---|---|
| `mekck:plantcut` | `PlantingCuttingRecipe` | A 组（PLANTING_CUTTING） |
| `mekck:ice_make` | `IceMakeRecipe` | A 组（ICE） |
| `mekck:grinding` | `GrindingRecipe` | A 组（GRINDING） |
| `mekck:grilling` | `MekCkGrillingRecipe` | A 组（GRILLING，§6.5.1.1a） |
| `mekck:skewering` | `MekCkSkeweringRecipe` | A 组（SKEWERING，§6.5.1.2） |
| `mekck:beverage_assembly` | `BeverageAssemblyRecipe` | **B/C 组**（不是 COOKING 工厂） |
| `mekck:packaging` | `PackagingRecipe` | B/C 组 |
| `mekck:grape_pressing` | `GrapePressingRecipe` | B/C 组 |
| `mekck:extracting` | `ExtractingRecipe` | B/C 组 |
| `mekck:ferrero` | `FerreroRecipe` | B/C 组 |
| `mekck:nut_roasting` | `NutRoastingRecipe` | B/C 组 |

> 注意 `mekck:beverage_assembly` 名字最像"烹饪工厂"，实际属于 B/C 组的饮品机器。
> 迁移时**不要**把它当成 COOKING 工厂的配方类型。

---

## 7. 进度与存档（v2 补全）

| 字段 | 存档 | 类型 | 说明 |
|---|---|---|---|
| `activeRecipeId` | 是 | `ResourceLocation` | 当前配方身份 |
| `progress` | 是 | `int` | 当前 tick 进度 |

**重启恢复流程**：`load()` 读出 `activeRecipeId` → 下一 tick 用
`level.recipeAccess()` 按 id 取配方 → **重新校验 `matches()`**
（输入槽可能已被外部改动）→ 匹配则续跑，否则清空回 idle。

只存 id 不存配方对象，是因为配方对象是注册表单例，重启后必须重新获取，
直接存会 NPE 或反序列化失败。

---

## 8. 升级体系

### 8.1 Mek 侧已核实事实

| 事实 | 值 |
|---|---|
| 机器支持的升级种类 | `mekanism.api.Upgrade`：`SPEED` / `ENERGY` / `FILTER` / `GAS` / `MUFFLING` / `ANCHOR` / `STONE_GENERATOR` |
| 升级存档 | `Upgrade.buildMap(CompoundTag)` / `saveMap(Map, CompoundTag)`，以枚举为键 |
| 方块声明支持 | `mekanism.common.block.attribute.AttributeUpgradeSupport`（**已在用**） |
| 升级数据注册 | **1.20.1 用 JSON 配方**（`RecipeUpgradeType`）。**无** `RegisterUpgradeDataEvent`——那是 1.21+，按 1.21 写直接编译失败 |
| 升级卡物品 | `mekanism.common.item.ItemUpgrade` / `IUpgradeItem` |

### 8.2 设计

> **⚠ v3 修正**：v1/v2 假设升级卡就是 Mek 的 `SPEED` / `ENERGY`——**这是错的**。
> 阶段 1 方案（`2026-09-29-mek-native-machine-framework-design.md` §5.1–5.4）是
> **用 Mixin 往 `mekanism.api.Upgrade` 注入 2 个 mekck 自有常量**：
> **存储卡（STACK）** 与 **随机化卡（RANDOMIZE）**。
> 因此 A 组工厂的升级槽支持集合 = {STACK, RANDOMIZE}，**不是** Mek 原生那 7 种。
> 若同时想支持 Mek 原生升级，则 `supported` 集合为 {STACK, RANDOMIZE, SPEED, ENERGY, …}，
> 但**优先只做两种自有升级**，避免继承 §8.1 的 ordinal 持久化缺陷带来的额外面积。

**档位与升级卡正交**：
- 档位 = 这台机器本身多强（方块 ID 决定，不可逆）
- 升级卡 = 往上装什么（可拆、数量可变）

**两种自有升级的效果**：

| 升级 | 语义 | 生效点 | 与既有代码的关系 |
|---|---|---|---|
| **STACK（存储卡）** | 提升槽位堆叠上限 | `getSlotLimit()` | `MekCkFactoryTier.supportsStackUpgrade()` 已存在，语义为 ABSOLUTE~NEBULA（**排除 SINGULARITY**，因其设计前提是并行已封顶） |
| **RANDOMIZE（随机化卡）** | 产物随机化 | `CachedRecipe.finishProcessing` 回调 | 需新实现 |

**并行数恒为 `tier.processes`**，两种自有升级**都不改变并行**——避免与档位语义重叠。

**阶段依赖（重要）**：升级常量由阶段 1.5 注入。**阶段 2（配方接线）完成时，
A 组机器的升级槽应先为空**（`supported` 集合暂时只填 Mek 原生那 7 种或不填），
待阶段 1.5 的 `MekCkUpgradeTypes` 落地后再补 STACK / RANDOMIZE。
不要在阶段 2 里硬编码引用尚不存在的升级常量——那会与另一 AI 的实现抢定义。

旧体系（`MekckUpgradeTracker` / `UpgradeInstallHandler`）在迁移期与新体系**共存**：
B/C 组旧机器用旧的，A 组新机器用 Mek 的。阶段 5 后再决定是否合并。

**已知缺陷（阶段 1 正在修）**：`MekCkUpgradeType:19-20` 与 `UpgradeHelper:23-24`
硬编码 `mekanism_extras:upgrade_stack` / `upgrade_creative`，而 `mods.toml`
**未声明 `mekanism_extras`**。未装 Mek Extras 时两类升级卡静默失效——
`getType()` 恒返回 `NONE`，STACK/CREATIVE 槽永远填不上，线程数锁死基础值。

### 8.3 档位接入 Mek `ITier`（v4 新增，2026-09-29 决策）

**决策**：`MekCkFactoryTier` 实现 `mekanism.api.tier.ITier`，让 12 档成为**合法的 Mek 档位**，
而不是私有平行枚举。

#### 8.3.1 Mek 侧已核实 API

`javap` 读 `mekanism-268560-6018299_mapped_official_1.20.1.jar`：

```java
public interface mekanism.api.tier.ITier {
    BaseTier getBaseTier();          // 只有一个方法
}

public enum BaseTier implements StringRepresentable, SupportsColorMap {
    BASIC, ADVANCED, ELITE, ULTIMATE, CREATIVE;   // 只有 5 个常量
    // getSimpleName / getLowerName / getMapColor / getRgbCode / getColor / getSerializedName
}

public record AttributeTier<TIER extends ITier>(TIER tier) implements Attribute { … }
```

> **更正既有代码注释**：`MekCkFactoryTier:26` 与 `CuttingMachineFactoryTier:6` 写的
> 「与 Mek `BaseTier`(BASIC~ULTIMATE) + `AdvancedTier`(ABSOLUTE~INFINITE) 对齐」中，
> **`AdvancedTier` 这个类在 Mek 1.20.1 中不存在**。Mek 的公开档位 API 只有
> `BaseTier` 的 5 个常量。那句注释描述的是配色参考，不是可对接的类型。
> 本次一并订正。

#### 8.3.2 映射表

用户决策：**有 Mek 拓展就映射到对应 Mek 档位；没有的一律映射到 `BaseTier.ULTIMATE`
（终极工厂升级）**。

| mekck 档位 | 并行槽 | → `getBaseTier()` | 理由 |
|---|---|---|---|
| `BASIC` | 3 | `BaseTier.BASIC` | 一一对应 |
| `ADVANCED` | 5 | `BaseTier.ADVANCED` | 一一对应 |
| `ELITE` | 7 | `BaseTier.ELITE` | 一一对应 |
| `ULTIMATE` | 9 | `BaseTier.ULTIMATE` | 一一对应 |
| `ABSOLUTE` | 11 | `BaseTier.ULTIMATE` | Mek 无对应档 |
| `SUPREME` | 13 | `BaseTier.ULTIMATE` | 同上 |
| `COSMIC` | 15 | `BaseTier.ULTIMATE` | 同上 |
| `INFINITE` | 17 | `BaseTier.ULTIMATE` | 同上 |
| `BLAZE` | 25 | `BaseTier.ULTIMATE` | 同上 |
| `CRYSTAL_MATRIX` | 36 | `BaseTier.ULTIMATE` | 同上 |
| `NEBULA` | 49 | `BaseTier.ULTIMATE` | 同上 |
| `SINGULARITY` | 81 | `BaseTier.ULTIMATE` | 同上 |

`BaseTier.CREATIVE` **不映射任何档位**——它对应 Mek 的创造模式物品，与工厂档位无关。

> 12 档压成 5 档是刻意的：档位身份仍由方块 ID 承载（§9 硬约束），
> `getBaseTier()` 只用于「Mek 侧通用机制需要一个粗粒度档位」的场合。
> 压到 `ULTIMATE` 的 8 档不会被 Mek 误判成基础档。

#### 8.3.3 ⚠ 硬边界：实现 `ITier` **不会**让 `Attribute.getBaseTier(block)` 生效

这一点必须写死，否则后续实现者会误以为接了 `ITier` 就「Mek 认得我们的档位」。

`Attribute` 的静态读取实现（字节码）：

```java
public static BaseTier getBaseTier(Block block) {
    AttributeTier at = Attribute.get(block, AttributeTier.class);
    return at == null ? null : at.tier().getBaseTier();
}
```

即：**方块的 `BlockType` 上必须挂有 `AttributeTier`，否则返回 `null`。**

而 `AttributeTier` 对 addon **不可设置**：

- 引用 `AttributeTier` 的只有 5 种传输管线（`BlockLogisticalTransporter` /
  `BlockMechanicalPipe` / `BlockPressurizedTube` / `BlockThermodynamicConductor` /
  `BlockUniversalCable`）和 Mek 自家的 `Factory` 方块类型。
- `BlockType$BlockTypeBuilder` **没有** `withTier`；唯一带 `ITier` 的入口是
  `withComputerSupport(ITier, String)`，而它的字节码是：

  ```java
  public T withComputerSupport(ITier tier, String name) {
      return withComputerSupport(tier.getBaseTier().getLowerName() + name);
  }
  public T withComputerSupport(String name) {
      return with(new AttributeComputerIntegration(name));   // 记录里只有 String name
  }
  ```

  **ITier 在构建期就被消费成字符串，`ITier` 本身不留在 `BlockType` 上。**
  所以即便调了 `withComputerSupport`，`AttributeTier` 依然不存在，
  `Attribute.getBaseTier(block)` 仍返回 `null`。

**结论**：实现 `ITier` 的收益是——我们的档位成为任何接受 `ITier` 的 Mek API 的合法输入，
并且我们自己可以用 `Attribute.getTier(block, MekCkFactoryTier.class)` 这条路做统一查询
（前提是自己维护 `Block → MekCkFactoryTier` 的反查，即已有的 `tierFromBlock()`）。
收益是**有限且明确的**，不要在文档或对外说明里夸大成「Mek 支持我们的档位」。

#### 8.3.4 与 `TileEntityFactory` 的关系不变

`TileEntityFactory` 的档位字段是**具体类型** `public FactoryTier tier`，
不是 `ITier`（常量池里的 `ITier` 只来自 `Attribute.getTier` 调用）。
且 `Factory$FactoryBuilder.createFactory(Supplier, FactoryType, FactoryTier)` 的
最后一参**写死 `FactoryTier`**，不接受任意 `ITier`。

这印证 §3 第 2 条：`TileEntityMekCkFactory` 与 `TileEntityFactory` 平行、不是子类，
所以我们可以保留 `MekCkFactoryTier` 字段类型不变，只是让它多实现一个 `ITier`。
**不引入对 `FactoryBuilder` 的依赖。**

#### 8.3.5 存档影响：无

`MekCkFactoryTier` 实现的是 `StringRepresentable`，序列化走 `getSerializedName()`
（名字）而非 `ordinal()`。这与 §8.1 提到的 `Upgrade` 枚举按 `ordinal()` 持久化、
注入会静默损坏存档的缺陷**性质不同**——我们走名字，无此风险。
阶段 4 的存档兼容层**不需要**为档位做任何映射。

---


## 9. 存档兼容

**硬约束：方块 ID 保持 `mekck:<tier>_<type>_factory` 不变。**
若改用 `MekCkFactoryRegistration.NAMESPACE`（`mekckfactory`），现有存档的旧方块
会成为未知方块——**不可恢复地消失**。

NBT 兼容层（阶段 3）：
- 等级 / 工艺从方块反查（`tierFromBlock()`），无需存档
- 容器：旧 BE 的键名与 Mek 序列化键若不同，需双向映射
- 升级：旧 `MekckUpgradeTracker` 的 `Installed` 整型 → 新 `Upgrade.buildMap` 结构

---

## 10. 失败模式（v3 对齐 `CachedRecipe` 语义）

v3 起失败模式**不再由我们自己的 `tick()` 处理**，而是通过
`CachedRecipe.process()` 里 `currentMax` 的三态语义表达（见 §6.2 字节码）：

| `currentMax` | 机制行为 | 对应情形 |
|---|---|---|
| `> 0` | 正常加工：`useEnergy` → `operatingTicks++` → 满则 `finishProcessing` | 一切正常 |
| `= 0` | `setActive(false)`，**不推进 `operatingTicks`** | 能量不足 / 产物放不下 / 缺原料（暂停重试） |
| `< 0` | `setActive(false)` + **`resetCache()` 清零进度** | 配方永久失效（输入不再匹配等） |

**因此本设计的硬规则**：

- **「产物放不下」必须让 `currentMax` 停在 `0`，绝不能返回负数。**
  返回负数会触发 `resetCache()` 把进度清零，等于「产物没放进去、料也白扣」。
  旧中央厨房正是这个缺陷（审查报告 F3：先扣料再插产物，输出区满时吃料不交货）。
- **能量不足同样是 `0` 而非负数**——不惩罚玩家，进度保留。
- **只有「输入已不再匹配配方」才允许 `< 0`**，因为那确实意味着该重置缓存。
- 配方在存档期间被删除：`IRecipeLookupHandler.getRecipe(int)` 返回 `null` → `setHasNoRecipe(i)`
  负缓存，下一 tick 自然回落到 `= 0` 状态。

**产物闸门的位置**：应在 `createNewCachedRecipe(...)` 配置的
`finishProcessing` 回调**之前**判定（等价于 v2 的 `canAcceptResult()` 前置），
或直接在 `calculateOperationsThisTick` 里让「产物放不下」的输入被拒绝为 `currentMax = 0`。

### 10.1 三个特例工艺的失败模式（v4 新增）

上面的三态语义是通用规则，但有三个工艺的结构会让通用规则**不够用**。
逐个给条款：

#### 10.1.1 ICE —— 无输入槽

`§6.4` 已记录 ICE 的输入槽为 0，与 `addSlotGrid`「输入 = 输出 = processes」冲突。失败模式上的具体后果：

- ICE 靠外部供料（流体管道 / 其他机器）注入，**输入不是物品槽**。
  「缺原料」这个 `= 0` 状态在 ICE 里的判据不是"输入槽空"，而是**流体容器为空**。
- 产物只有一个，且是流体。**「产物放不下」的判定对象是输出流体容器，不是输出槽。**
  若输出容器已满，必须停在 `currentMax = 0`，不得返回负数（否则流体被抽走却没有去处）。
- `processes` 对 ICE 无意义（单一流体输出），档位差异只体现在能量容量上。
  实现时**不要**给 ICE 建 `processes` 个并行槽。

#### 10.1.2 COOKING —— 144 格材料库

COOKING 有 144 格材料库（`§6.4`），与另外 6 个工艺的 `processes × 2` 槽位模型完全不同。

- 材料库**不参与** `currentMax` 计算，它是"配方可选取用哪些材料"的来源，
  不是"这一 tick 能处理几份"的计数。
- 配方可能声明**多个候选输入**，从材料库里自动选料。此时"缺原料"要细分为：
  - 材料库里有满足配方的料，但**输出放不下** → `currentMax = 0`（暂停，进度保留）
  - 材料库里**根本没有**满足配方的料 → `currentMax = 0`（暂停，等玩家投料）
  - 两者都成立时**优先按"放不下"处理**，因为它可恢复，后者也 recover，但前者更常见
- 144 格材料库迁到 Mek 槽位时（§12 阶段 2），槽位数量超出 `InventorySlotHelper` 常规用法，
  需单独确认上限与性能，**不能默认照搬其他工艺的槽位装配**。

#### 10.1.3 GRILLING —— 调味槽

- 调味槽（seasoning）是**独立于主输入的第二个输入**，不参与并行计数。
- 调味料的语义通常是"可选但影响产物"：配方可能声明"有调味料走 A 产物，无则走 B 产物"。
  这意味着**同一台机器同一时刻可能匹配两条不同配方**，配方查找必须能区分这两种情况，
  且 `getRecipe(int)` 的槽位索引要能把主输入与调味槽分开传。
- 调味料耗尽 ≠ 原料耗尽。若把"调味槽空"当 `currentMax < 0`，会在换配方时清空
  已积累的 `operatingTicks`，等于惩罚玩家。**调味槽空一律按 `= 0` 处理。**

---

## 11. Mek 1.20.1 API 契约（v3 已实测填入）

全部经 `javap` 读取
`mekanism-268560-6018299_mapped_official_1.20.1.jar`（无 sources jar，行为推断处已注明）。

1. **Tick 钩子** ✓ — `protected void onUpdateServer()`。`TileEntityMekanism` 无 `tick()`；
   tick 由 Mek 在注册时装好的 BE ticker 转发到 `static tickServer(...)`，后者调 `onUpdateServer()`。
   **注册要求已满足**（2 参 `TILE_ENTITIES.register(block, supplier)` 重载）。
2. **配方查找/执行** ✓ — `IRecipeLookupHandler<RECIPE>` + `RecipeCacheLookupMonitor<RECIPE>`
   + `CachedRecipe<RECIPE>` 三件套（详见 §6.2）。**不存在** `RecipeFinder` / `BasicMachine` /
   `ITickingTile` / `RecipeTypeRegistry`，也不要找。参考实现 `TileEntityElectricMachine`。
3. **自定义 RecipeType** ✓ — 走 `RecipeSerializerDeferredRegister`（Mek 自有）+ vanilla `RecipeType`；
   配方基类用 `ItemStackToItemStackRecipe`，序列化用
   `ItemStackToItemStackRecipeSerializer<>(MekCkCookingRecipe::new)`。
4. **进度存档** ✓ — `CachedRecipe` 用 `operatingTicks` 计数、`requiredTicks` 为阈值；
   持久化经 `IRecipeLookupHandler.getSavedOperatingTicks(int)`。
5. **升级三件套** — 见 `2026-09-29-mek-native-machine-framework-design.md` §2.3 / §5.4
   （另一份 spec 已实测：`Upgrade` 是 7 常量写死枚举，**无 data map**；持久化按
   `ordinal()` 且 `getByIndexMod` 取模回绕，注入型 mod 会静默损坏存档）。
   **本阶段不动升级**——阶段 1 以该 spec 的 plan 为准。
6. **多方块 tile 基类** — `BlockBasicMultiblock<TILE extends TileEntityMekanism> extends
   BlockTile<TILE, BlockTypeTile<TILE>>`（**与 `MekCkFactoryBlock` 同源，不推翻继承链**）；
   方块属性用 `AttributeMultiblock`（`EXTERNAL` / `STRUCTURAL` / `INTERNAL`）。
   **已定（v2 定，原文写「仍未定」已作废）**：走 §4 的路线乙——
   `TileEntityMultiblock` + 自建 `MultiblockData`，换基类成本约 50 行（§4.3 已量化）。
   阶段 2 开始前**无需**再确认。
7. **档位 API** ✓ — `ITier` 只有 `getBaseTier()` 一个方法；`BaseTier` 只有 5 个常量
   （`BASIC`/`ADVANCED`/`ELITE`/`ULTIMATE`/`CREATIVE`），**`AdvancedTier` 类不存在**。
   但 `AttributeTier` 对 addon 不可设置，实现 `ITier` 并不会让
   `Attribute.getBaseTier(block)` 生效——完整边界见 §8.3.3。
8. **配方注册** — `RecipeSerializerDeferredRegister`（Mek 自有）+ vanilla `RecipeType`；
   每工艺注册独立的 `RecipeType` 与 `RecipeSerializer`，见 §6.5。

> **与另一份 spec 的关系**：阶段 1（升级体系）以
> `2026-09-29-mek-native-machine-framework-design.md` +
> `../plans/2026-09-29-mekck-phase1-upgrade-system.md` 为准（已实测更细、且已有代码落地）。
> **本文档负责阶段 2（配方接入机器框架）的分层与边界**。两份 spec 在此重叠处一致则皆准，
> 冲突时以各自的阶段归属为准。

---

## 12. 实施路线

| 阶段 | 内容 | 验收 |
|---|---|---|
| **0** | Mek API 调研（**已完成**，见 §11） | §11 全部填满且带证据 |
| **1** | **COOKING 一台跑通**：配方注册 + `IRecipeLookupHandler` 实现 + 升级卡 | 放置→GUI→投料→加工→产物→升级卡生效；存档重载正确 |
| **1.5** | 升级体系（**另一 AI 进行中**，见 §11 末） | 名字键编解码 + `Upgrade` 注入 |
| **2** | 7 工艺铺开 | 每工艺独立验收，产物与旧实现一致 |
| **3** | **PLANTING_CUTTING 接入 Mek 多方块**（§4） | 结构形成/破坏无残留；`MultiblockData` 库存/流体/能量接口可用 |
| **4** | 存档兼容层 | 旧存档机器不消失；库存/等级/升级正确迁移 |
| **5** | 旧工厂 BE 下线 | 移除旧方块注册与旧 BE（**注意：先确认 B/C 组不再用 `MekCkMultiblock`**） |
| **6** | B/C 组（15 联动机器 + 独立机器）单独立项 | —— |

**v4 新增的插入点**：

| 阶段 | 内容 | 为什么插在这里 |
|---|---|---|
| **1**（并入） | `MekCkFactoryTier implements ITier` + §8.3 映射表 | 3 行改动 + 12 行映射，越早做越省事；阶段 4 存档迁移时它已经是最终形态 |
| **1**（并入） | 条件化方块注册（§14） | 只影响 GRILLING / SKEWERING 24 个方块，与升级体系零耦合 |
| **2**（前置） | 决定 GRILLING / SKEWERING 是否补自有配方（§6.5.1 结论 2、§14.2） | **内容决策，不是架构决策**，但会决定 §14 的实际效果，必须在铺开 7 工艺前定 |
| **2**（并入） | COOKING 材料库 / ICE 流体 / GRILLING 调味槽的槽位装配（§10.1） | 这三个工艺的槽位模型与另外 4 个不同，不能等铺开后再补 |

**阶段 1 是硬门槛**：7 工艺共享基类，抽象一旦错误代价是 7 倍。
**§4 的多方块换基类必须在阶段 3 单独完成**，不要与配方接线混在同一批改动里——
两处问题（`CachedRecipe` 状态机语义 vs `MultiblockData` 构造期行为）会互相掩盖。

---

## 13. 验收标准

每阶段必须全过：

1. `./gradlew clean build --offline` → BUILD SUCCESSFUL（**必须 clean**——
   增量编译对跨类引用是盲的，本项目已因此产生过一次"编译不过"的误判，见审查报告 §二）。
2. 全量单元测试通过且 **0 跳过**。
3. 实机：放置 → GUI（tab 与 Mek 一致）→ 投料 → 加工 → 产物 → 装升级卡 → 效果生效。
4. 实机：拆掉再放回，状态正确。
5. 实机：重启客户端，状态正确恢复。
6. 多方块工艺（PLANTING_CUTTING）：破坏/放置整体结构无残留，边界方块不误掉落。

**禁止**：为跑通而删存档兼容、省略 `clean`、或吞异常。

---

## 14. 条件化方块注册（v4 新增，用户选定"最严格"方案）

配方层已在 `5a36aa4` 完成条件化（见 `../audit/2026-09-29-conditional-recipe-report.md`）。
本节处理**方块层**：当 84 个工厂方块中某个所需的外部 mod 缺席时，该方块
**不注册**，而不是注册成一个永远无法工作的空壳。

### 14.1 方案选择

用户拍定：**全部依赖到位才注册（最严格）**。

被否掉的两个较宽松方案，理由记录在此以备追溯：

| 方案 | 行为 | 否决理由 |
|---|---|---|
| 注册但隐藏 | 方块注册，JEI/创造栏隐藏 | 存档里若已有该方块，加载后成未知方块，玩家资产消失 |
| 注册并可工作（降级） | 用占位配方运行 | 行为不可预期，且 84 个方块各自的降级语义不同，无法统一 |

### 14.2 判据：每个方块的"依赖"是什么

这是本节**唯一的难点**，必须逐方块确定，不能一刀切。

一个 `<tier>_<craft>_factory` 方块的依赖 = **该工艺的配方来源 mod**（§6.5）：

| 工艺 | 依赖 mod | 缺席时的方块 |
|---|---|---|
| GRILLING | ~~`barbequesdelight`~~ → **已无外部依赖**（§6.5.1.1a 已建自有 `mekck:grilling`） | 恒注册 |
| SKEWERING | ~~`barbequesdelight`~~ → **已无外部依赖**（§6.5.1.2 已建自有 `mekck:skewering`） | 恒注册 |
| COOKING | `farmersdelight`（+可选 `avaritia_delight` / `kaleidoscope_cookery`） | 由 `farmersdelight` 决定；它是 `mods.toml` 强制依赖，**恒注册** |
| CUTTING | `mekanism`（`sawing`） | 恒注册 |
| PLANTING_CUTTING | `mekanism`（`combining`） | 恒注册 |
| GRINDING | 无（`mekck:grinding` 自有） | 恒注册 |
| ICE | 无（`mekck:ice_make` 自有） | 恒注册 |

**结论：7 个工艺现已全部无外部可选依赖，最严格方案对本批方块不再排除任何方块。**

> 原先的顾虑是「按规则跑完，GRILLING / SKEWERING 共 24 个方块在默认实例下不会出现」。
> 补完 §6.5.1.1a 与 §6.5.1.2 的自有配方后，该顾虑已消除——
> 这也说明「条件化方块注册」与「补自有配方」应当配套：先补内容，规则才不会误伤。
>
> §14 的实现因此**可以暂缓**：目前没有需要被条件化排除的方块。
> 若将来引入新的外部配方源，再按本节判据补门控。

### 14.3 实现位置与形态

- **位置**：`MekCkFactoryRegistration`，注册循环内按 `type` 过滤。
- **判据 API**：`ModList.get().isLoaded("barbequesdelight")`。
  必须在注册期（`RegisterEvent`）可求值——Forge 的 mod 列表在注册期已就绪。
- **日志**：缺席时 `LOGGER.info` 列出被跳过的方块 id，便于排查"为什么我的工厂没了"。
  用 `info` 不用 `warn`/`error`——这是**预期行为**，不是错误。
- **不得**在缺席时注册占位方块（否则 §14.1 第 3 行的存档风险照旧）。

### 14.4 与存档的交互（必须在阶段 4 前定案）

玩家若在装了 `barbequesdelight` 的环境里造了 GRILLING 工厂，之后卸掉该 mod，
存档里会留下 24 个未知方块。**本节不提供兼容层**——理由是提供它需要为
「内容将来会不会被补上」做假设，而这是内容决策不是架构决策。

处置方式记录在此，等内容侧定案后二选一：
1. 补齐 GRILLING / SKEWERING 自有配方，使这两个依赖永不缺席（推荐，也顺带解 §6.5 缺口）
2. 接受未知方块，在阶段 4 加 tombstone 清理

---

## 15. 测试策略（v4 新增）

§13 的 6 条验收**全部是手工实测**，而迁移面是 84 方块 × 7 工艺。
纯手测不现实，也无法回归。本节定义自动化覆盖。

### 15.1 分层

| 层 | 范围 | 手段 | 门槛 |
|---|---|---|---|
| **L1 纯逻辑** | 档位↔`BaseTier` 映射、配方选取、失败模式三态判定、进度存取 | 纯 JUnit，无 Minecraft 类加载 | 每次提交 |
| **L2 序列化** | 配方 JSON ↔ `Recipe` 对象、存档 NBT 往返 | `GameTest` 或 JUnit + 反序列化工具 | 每次提交 |
| **L3 结构** | 多方块形成/破坏、槽位装配、能量容器 | `GameTest` | 每阶段 |
| **L4 手工** | §13 的 6 条 | 人 | 每阶段 |

### 15.2 L1 必须覆盖的用例（逐条对应本规格的硬规则）

1. **档位映射完整性**：`MekCkFactoryTier.values()` 每一项 `getBaseTier()` 非 null；
   12 档全部映射到 4 个 `BaseTier` 常量；`CREATIVE` 不被任何档位使用。
2. **产物闸门**：`currentMax` 在「输出满」时**必须为 0**，不为负。
   构造一个输出槽已满的场景，断言 `operatingTicks` 未被清零。
3. **能量不足**：`currentMax == 0` 且 `operatingTicks` 保留。
4. **配方失效**：`currentMax < 0` 时 `resetCache()` 被调用，进度归零。
5. **ICE 特例**（§10.1.1）：输出流体容器满时 `currentMax == 0`，且输入流体**未被抽走**。
6. **COOKING 材料库**（§10.1.2）：144 格中任一格命中配方即算满足；
   全部不命中时 `currentMax == 0`。
7. **GRILLING 调味槽**（§10.1.3）：调味槽空 → `currentMax == 0` 且进度保留。
8. **存档往返**（§7）：`activeRecipeId` + `progress` 序列化后反序列化一致。
9. **条件化方块注册**（§14）：模拟 `ModList` 不含 `barbequesdelight` 时，
   GRILLING/SKEWERING 共 24 个方块不在注册表中；含时在。

### 15.3 硬门槛

- `./gradlew clean build --offline` 必须通过，**0 跳过测试**（沿用 §13 第 1、2 条）。
- **禁止**为了让测试通过而放宽断言。L1 的每条断言都直接对应本规格的一条硬规则，
  改断言等于改规格，必须走评审。

---


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

```
mekck/
├── factory/                     Mek 机器层（方块 / 通用 tile / GUI / 注册）
│   ├── MekCkFactoryRegistration     84 个方块注册
│   ├── MekCkFactoryBlock            extends BlockTile
│   ├── MekCkFactoryMultiblockBlock  ★ 新增：PLANTING_CUTTING 用
│   ├── MekCkFactoryType             7 工艺（需补 recipeTypeName）
│   ├── MekCkFactoryTier             12 等级
│   ├── TileEntityMekCkFactory       ★ 抽象基类：全部通用加工流程
│   └── MekCkFactoryMenu/Screen
│
├── machine/                      工艺实现层（每工艺一个）
│   └── MekCkXxxFactoryTile extends TileEntityMekCkFactory<MekCkXxxRecipe>
│
├── recipe/                       配方层
│   ├── MekCkFactoryRecipe           ★ 统一接口（7 工艺共用）
│   └── MekCkXxxRecipe + Serializer
│
└── upgrade/                      升级层
    └── MekCkUpgradeRegistry
```

**依赖方向严格单向**：`machine → recipe → factory`。`factory` 层不认识任何具体工艺。

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
   **仍未定**：Mek 自有多方块 tile（`TileEntityMultiblock`）不继承
   `TileEntityConfigurableMachine`，需在阶段 2 开始前确认 mekck 是否必须跟随。

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

# MekCK 阶段 2：切菜工厂 Mek 原生化 —— 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把切菜工厂（7 家族 × 12 档 = 12 个方块，2605 行代码）从自研 `BlockEntity` 迁到 Mekanism 原生机器体系，成为阶段 3 推广的样板。

**Architecture:** 复用 `factory/TileEntityMekCkFactory.java` 已有的 Mek 机器骨架（能量/侧配/槽位网格/升级），补上生产必需的执行器挂载点与 AE2 端口声明，然后把切菜工厂的**家族特有逻辑**（Farmer's Delight `CuttingBoardRecipe` 匹配 + 批量执行）搬进独立的执行器类。

**Tech Stack:** MC 1.20.1 / Forge 47.4.16 / Java 17 / Mekanism 10.4.6.20 / JUnit 4 / SpongePowered Mixin 0.8.5

**Spec:** `docs/superpowers/specs/2026-09-29-mek-native-machine-framework-design.md`（§3 架构、§6 阶段 2、§7 命名空间收编）
**前序:** 阶段 1 已完成（4 个 Mixin + 升级体系），见 `docs/superpowers/handoff/2026-09-29-phase1-runtime-verification.md`

## Global Constraints

- **MekCK 的 Mixin 足迹已达上限 4 个，本任务不得新增第 5 个。** 阶段 1 已用满：
  `MixinAPILang` / `MixinUpgrade` / `MixinUpgradeUtilsGetStack` / `MixinTileComponentUpgradePersistence`。
- **`mekck.mixins.json` 的 `injectors.defaultRequire` 保持 `1`。**
- **禁止 `git add -A` / `git commit -a`**（并行同学持续在改本仓库）。
  提交前 `git status --short` 核对暂存范围。
- **不要跑 `./gradlew clean`**（本仓库 clean 会删掉 `build/libs` 的 jar）。
- **首选 PowerShell 7**：`"C:\Program Files\PowerShell\7\pwsh.exe" -NoProfile -c '...'`
  默认 shell 是 MSYS2 但 PATH 污染：`cmp`/`diff` 不存在，`find`/`sort` 被 Windows 同名程序抢占。
  任何「差异数为 0」的结论**必须先确认比较工具真的存在**（用 `Get-FileHash` 或 Python 逐字节比对）。
- 提交带 `/` 的中文 message 时 MSYS 会把 `/` 吃成 `C:/msys64/`——
  用 `MSYS_NO_PATHCONV=1 git commit -F <file>` 或改用 PowerShell 提交。
- **注释用中文解释「为什么」。每写一个 javap 片段、选项名或跨版本行为断言，先跑命令确认再写**——
  阶段 1 八个任务因这个返工了十四次。
- **实机验证本环境做不了**（Farmer's Delight 自身 mixin 崩溃，2026-09-10 起既有）。
  `compileJava` 不触发 Mixin 变换。**不要声称已验证运行期行为。**

---

## 为什么先做切菜工厂

7 个工厂家族里切菜**第二小**（1314 行 BE，仅次于制冰的 1073），
且**没有订单系统**——它按"每个输入槽各自处理"工作，比烹饪那种"按订单批量推进"简单一档。
它的 Menu/Screen/Block 相对薄（388/741/162）。

**家族特有、要保留的**：Farmer's Delight `CuttingBoardRecipe` 匹配、每槽配方缓存
（`slotRecipeValue`）、批量执行 `completeRecipe`、并行线程计算。

**要丢给基类的**：`getCapability` / `invalidateCaps` / `reviveCaps`（`CapabilityTileEntity`）、
`saveAdditional` / `load` / `getDisplayName` / `setCustomName`、
红石三件套、4 个 `MekCkUpgradeTracker` 及其安装/卸载/读条、
约 20 份 `IItemHandler` 样板（`getSlotLimit` 全仓库出现 23 次、`isItemValid` 22 次、
`insertItem` 20 次——全部由 `InventorySlotHelper` + `IInventorySlot` 取代）。

---

## File Structure

**新建 `cn.ism.mekck.machine` 包**——机器体系是独立子系统，不塞进 `blockentity/`。

| 文件 | 职责 |
|---|---|
| `machine/MekCkMachineTile.java` | L2 抽象基类，继承 `TileEntityConfigurableMachine`。承载共性能力 + 执行器挂载点 |
| `machine/MekCkRecipeExecutor.java` | L3 执行器接口（家族特有逻辑的契约） |
| `machine/ports/IMekCkPorted.java` | AE2 端口声明接口（取代 `ae2/INetworkPullable.java`） |
| `machine/cutting/CuttingFactoryExecutor.java` | 切菜执行器：`CuttingBoardRecipe` 匹配 + 批量执行 |
| `machine/cutting/CuttingFactoryTile.java` | 切菜 tile，绑定执行器 + 声明支持的升级 |
| `menu/CuttingMachineFactoryMenu.java` | 改继承 `MekanismTileContainer` |
| `client/CuttingMachineFactoryScreen.java` | 改继承 `GuiConfigurableTile` |

**修改**：`block/CuttingMachineFactoryBlock.java`（改继承 `BlockTile` + `BlockTypeTile` 描述）、
`UniversalCuttingMachine.java`（注册迁到 `BlockDeferredRegister`）

**删除**：`blockentity/CuttingMachineFactoryBlockEntity.java`（1314 行）
及其在 `CuttingMachineFactoryTier.java:38-64` 的 7 个 `getXxxBlockId()` 方法。

**不动**：其余 6 个家族、5 个独立机器、`factory/` 雏形（阶段 3 才清理）。

---

## ⚠️ 与阶段 1 的关键衔接

阶段 1 的 `MixinTileComponentUpgradePersistence` 会重定向 `TileComponentUpgrade` 的 read/write。
切菜工厂一旦真正持有 `TileComponentUpgrade`，该 Mixin **立即从空转变为生效**——这是本任务第一次
让阶段 1 的代码实际跑起来。

同时 `mekck$tier()` 的反射桥接会开始工作：它按类名找
`cn.ism.mekck.machine.MekCkMachineTile` 再反射调 `getTier()`。
**本任务必须保证这个类名和 `getTier()` 签名与阶段 1 的假设一致**，
否则存储卡读档会被静默丢弃（`isSupportedBy(STORAGE, null)` 判 false）。

---

## 已实测确认的关键事实

核实用 jar（只读）：
`C:\Users\Administrator\.gradle\caches\forge_gradle\deobf_dependencies\curse\maven\mekanism-268560\6018299_mapped_official_1.20.1\mekanism-268560-6018299_mapped_official_1.20.1.jar`

1. `factory/TileEntityMekCkFactory.java` 已是可用的 Mek 骨架：
   `presetVariables()` 建 `TileComponentConfig` + `TileComponentEjector`；
   `getInitialEnergyContainers()` 用 `MachineEnergyContainer.input(this, listener)`；
   `getInitialInventory()` 按 `tier.processes` 排 ⌈√N⌉ 方阵 + `EnergyInventorySlot`。
2. **构造期陷阱（已踩过两次）**：`TileEntityMekanism` 构造器内部会回调
   `getInitialInventory()`，那时子类字段初始化器**尚未执行**。
   等级/工艺类型必须从 `blockProvider` 反查（`tierFromBlock()`），
   列表必须在 `getInitialInventory()` 里创建。
   实测崩溃原文：`NPE: Cannot read field "processes" because "this.tier" is null`、
   `NPE: Cannot invoke "java.util.List.add(Object)" because "target" is null`。
3. 方块描述用 `BlockTypeTile.BlockTileBuilder`，缺任一项都有实测症状：
   - 缺 `withGui` → **方块右键不开界面**（`BlockTile.use()` 判定）
   - 缺 `AttributeStateFacing` → `facing=` 变体全不匹配 → **方块隐形**
   - 缺 `withSupportedUpgrades` → `supportsUpgrades()` 为 false，升级槽与升级 tab 都不出现
   - `AttributeEnergy` 签名是 `(usage, storage)`（**先用后容**）
4. `MekCkFactoryRegistration.java` 已验证可行的延迟 Supplier 破环写法
   （`containerRef` / `findTile`），可直接沿用。
5. 阶段 1 交付的升级设施：
   - `MekCkUpgradeRefs.storage()` / `.randomize()`（一律调方法，不读字段、不用 `valueOf`）
   - `MekCkUpgradeTypes.isSupportedBy(Upgrade, CuttingMachineFactoryTier)`
   - `MekCkUpgradeTypes.capOf(Upgrade, CuttingMachineFactoryTier)`
     —— 存储卡资格判据是 `tier.supportsStackUpgrade()`
     （= `ordinal() >= ABSOLUTE.ordinal() && this != SINGULARITY`）
6. `CuttingMachineFactoryTier` 已 `implements ITier`（并行同学提交 `59c69f1`），
   且第 38–64 行的 7 个 `getXxxBlockId()` 方法在本任务删除后成为死代码，需一并清理。

---

## Task 1: `MekCkRecipeExecutor` 接口 + `MekCkMachineTile` 基类扶正

**Files:**
- Create: `src/main/java/cn/ism/mekck/machine/MekCkRecipeExecutor.java`
- Create: `src/main/java/cn/ism/mekck/machine/MekCkMachineTile.java`
- Read-only 参照: `src/main/java/cn/ism/mekck/factory/TileEntityMekCkFactory.java`（239 行，Mek 骨架）

**Interfaces:**
- Consumes: `CuttingMachineFactoryTier`（12 档，`processes` / `energyCapacity` / `energyPerTick`）、
  `MekCkUpgradeTypes.isSupportedBy/capOf`、`MekCkUpgradeRefs`
- Produces:
  - `interface MekCkRecipeExecutor`：`void tick(MekCkMachineTile tile, int slotCount)`、
    `void save(CompoundTag)`、`void load(CompoundTag)`、`boolean isBusy()`
  - `abstract class MekCkMachineTile extends TileEntityConfigurableMachine`：
    `abstract MekCkRecipeExecutor createExecutor()`、
    `MekCkRecipeExecutor executor()`、`CuttingMachineFactoryTier getTier()`、
    `MekCkFactoryType getFactoryType()`

- [ ] **Step 1: 写执行器接口**

`src/main/java/cn/ism/mekck/machine/MekCkRecipeExecutor.java`

```java
package cn.ism.mekck.machine;

import net.minecraft.nbt.CompoundTag;

/**
 * 家族特有的配方执行器 —— L3 内容层的契约。
 *
 * <h3>为什么把它从方块实体里拆出来</h3>
 * 7 个工厂家族的方块实体有 42 个方法完全相同（升级追踪、红石、能力暴露、NBT、
 * AE2 拉料…），这些由 {@link MekCkMachineTile} 与 Mekanism 基类提供。
 * 真正家族特有的只有「怎么匹配配方、怎么把原料变成产物」。
 * 拆出来之后，一个家族的执行器是独立可读、可单测的纯逻辑单元。
 *
 * <h3>为什么不用泛型</h3>
 * 各家族的配方类型不同（切菜是 {@code vectorwing.farmersdelight.CuttingBoardRecipe}，
 * 烹饪是 {@code CookingPotRecipe}，制冰是本模组自有的 {@code mekck:ice_make}），
 * 泛型化会把 {@code IInventorySlot} 与配方类型的耦合传染到基类。
 * 这里用非泛型接口，执行器内部自己持有具体配方类型。
 */
public interface MekCkRecipeExecutor {

    /** 每 tick 调用一次。`slotCount` 是本等级的输入槽并行数。 */
    void tick(MekCkMachineTile tile, int slotCount);

    /** 是否正在工作（决定 GUI 的进度条与 AE2 的忙碌态）。 */
    boolean isBusy();

    /** 持久化订单进度等执行器自有状态。 */
    void save(CompoundTag tag);

    /** 读取执行器自有状态。旧存档无此键时必须保持默认态，不得抛异常。 */
    void load(CompoundTag tag);
}
```

- [ ] **Step 2: 写机器基类**

`src/main/java/cn/ism/mekck/machine/MekCkMachineTile.java`

从 `factory/TileEntityMekCkFactory.java` 迁移并改造。要点：

1. 继承 `mekanism.common.tile.prefab.TileEntityConfigurableMachine`
2. **`presetVariables()`** 建 `TileComponentConfig`（ITEM + ENERGY 两路）+ `TileComponentEjector`
3. **`getInitialEnergyContainers()`** 用 `MachineEnergyContainer.input(this, listener)`
   —— 容量/能耗从方块 `AttributeEnergy` 读，**不在这里设值**
4. **`getInitialInventory()`** 按 `getTier().processes` 排 ⌈√N⌉ 输入/输出方阵 + 一个 `EnergyInventorySlot`
5. **构造期顺序陷阱**：`TileEntityMekanism` 构造器内部会回调 `presetVariables()` 与
   `getInitialInventory()`，那时子类字段初始化器尚未执行。等级/工艺类型一律从
   `blockProvider` 反查（`tierFromBlock()` / `typeFromBlock()`），
   槽位列表在 `getInitialInventory()` 里 `new`，**不能写成字段初始化器**
6. **升级支持**：`getSupportedUpgrades()` 返回
   `{SPEED, ENERGY, STORAGE, RANDOMIZE}` 的过滤子集
   —— 用 `MekCkUpgradeTypes.isSupportedBy(type, getTier())` 逐个过滤，
   **不要**把 `MekCkUpgradeTypes.all()` 整包返回（那会把 40 个外部常量也声明成支持）
7. **执行器挂载**：字段 `executor` **不能在字段初始化器里 `createExecutor()`**
   （此时 `blockProvider` 已可用但 `getTier()` 可能为 null）。
   改成懒加载 getter，或在 `getInitialInventory()` 之后初始化
8. **NBT**：`saveAdditional` / `loadAdditional` 只写执行器状态 + `MekCkNative` 版本标记，
   其余交给父类

**不要**在本类里写任何配方逻辑。

- [ ] **Step 3: 编译验证**

Run: `cd /d/mc/mod/mekck && ./gradlew compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 提交**

```bash
MSYS_NO_PATHCONV=1 git add src/main/java/cn/ism/mekck/machine/
MSYS_NO_PATHCONV=1 git commit -m "新增 MekCkMachineTile 机器基类与 MekCkRecipeExecutor 接口"
```

---

## Task 2: AE2 端口声明 `IMekCkPorted`

**Files:**
- Create: `src/main/java/cn/ism/mekck/machine/ports/IMekCkPorted.java`
- 参照（不删，阶段 3 再清理）: `src/main/java/cn/ism/mekck/ae2/INetworkPullable.java`

**Interfaces:**
- Consumes: `MekCkMachineTile` 的 `IInventorySlot` 列表
- Produces: 6 个方法，签名**刻意对齐外部 SPI** `com.beipuo.mekenergistics.api.upgrade.IMePatternAutomationHost`：
  `mePatternItemInputs()` / `mePatternItemOutputs()` / `mePersistentItemInputs()` /
  `meManualOnlyItemSlots()` / `meGroupParallelItemInputs()` / `meSupportsPatternAutomation()`

- [ ] **Step 1: 写端口接口**

**签名照抄下面这段，方法名一个字符都不要改**：

```java
package cn.ism.mekck.machine.ports;

import cn.ism.mekck.machine.MekCkMachineTile;
import mekanism.api.inventory.IInventorySlot;

import java.util.List;

/**
 * 机器对自动化的 AE2 端口声明。
 *
 * <h3>为什么方法名长得像别人的接口</h3>
 * 这套签名（{@code mePattern*} 前缀、{@code List<IInventorySlot>} 参数、
 * {@code meGroupParallelItemInputs}）与外部 mod Mek Energistics 公开的
 * {@code IMePatternAutomationHost} 逐条对应。对方的目标版本是
 * MC 1.21.1 / NeoForge / Mekanism 10.7.x，与本项目（1.20.1 / Forge / 10.4.x）
 * <b>无交集、无法作为编译期依赖</b>。
 *
 * <p>但形状是可以提前对齐的：将来对方若出 1.20.1 版本，接入 MekCK 只需写一层反射桥接，
 * 不必再改机器代码。<b>起这套名字的成本是零，收益是留门。</b>
 * 本期<b>不实现</b>桥接。
 *
 * <h3>为什么参数是 IInventorySlot 而不是槽位下标</h3>
 * 旧接口 {@code INetworkPullable} 用 {@code int[] getInputSlotRange()}，
 * 只能表达一段连续区间，于是不得不加一个
 * {@code getExtraInputSlots()} 去补「0..4 与 10..13 两段不连续」的情况。
 * 槽对象本身是离散的，没有这个拼接问题——旧接口的债在这里直接消失。
 */
public interface IMekCkPorted {

    /** 本机器是否支持 AE2 样板自动化。 */
    default boolean meSupportsPatternAutomation() {
        return true;
    }

    /** 样板配料槽：会被 AE2 按配方投入。 */
    default List<IInventorySlot> mePatternItemInputs() {
        return List.of();
    }

    /** 产物槽：完成后回写 ME 存储。 */
    default List<IInventorySlot> mePatternItemOutputs() {
        return List.of();
    }

    /**
     * 常驻槽：跨订单不清空（如燃料槽、催化剂槽）。
     * 默认与 {@link #mePatternItemInputs()} 同集，即全部视为常驻。
     */
    default List<IInventorySlot> mePersistentItemInputs() {
        return mePatternItemInputs();
    }

    /** 只手动、AE2 不该碰的槽（升级槽、能量槽、配置槽）。 */
    default List<IInventorySlot> meManualOnlyItemSlots() {
        return List.of();
    }

    /**
     * 是否把 N 个同物输入槽塌缩成 1 个组端口。
     *
     * <p>切菜工厂的并行槽最多到奇点创世的 81 个。若不塌缩，
     * AE2 会把 81 个槽当成 81 个独立配料口，一单编码样板就把它们全占满。
     */
    default boolean meGroupParallelItemInputs() {
        return false;
    }

    /** 本机型的 tile。 */
    default MekCkMachineTile mePortedTile() {
        return null;
    }
}
```

- [ ] **Step 2: 编译验证 + 提交**

```bash
cd /d/mc/mod/mekck && ./gradlew compileJava --console=plain
MSYS_NO_PATHCONV=1 git add src/main/java/cn/ism/mekck/machine/ports/
MSYS_NO_PATHCONV=1 git commit -m "新增 IMekCkPorted：AE2 端口声明，签名对齐外部 SPI"
```

---

## Task 3: 切菜执行器

**Files:**
- Create: `src/main/java/cn/ism/mekck/machine/cutting/CuttingFactoryExecutor.java`
- Read-only 参照: `blockentity/CuttingMachineFactoryBlockEntity.java`（1314 行，只读配方相关段落）

**Interfaces:**
- Consumes: `MekCkRecipeExecutor`、`MekCkMachineTile` 的 `IInventorySlot` 列表
- Produces: `CuttingFactoryExecutor implements MekCkRecipeExecutor`

- [ ] **Step 1: 提取配方逻辑**

从 `CuttingMachineFactoryBlockEntity` 搬这几块（用 Grep 定位，其余部分不搬）：

| 要搬的 | 现有位置 | 备注 |
|---|---|---|
| 配方匹配 | `findRecipe(int inputSlot)`、私有字段 `CuttingBoardRecipe[] slotRecipeValue` | 保留「不每次 findRecipe 都 new 匿名 ItemStackHandler」的优化（L529 注释） |
| 批量执行 | `completeRecipe(int inputSlot, recipe, consumeCount)` | 含输出溢出钳制（L633） |
| 并行数 | `1 << min(stackUpgrades, MekckConfig.getFactoryStackUpgradeMax(tier))`（L967） | 改用 `MekCkUpgradeTypes.capOf(STORAGE, tier)` |
| 配方类型 | `vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe` + `ModRecipeTypes.CUTTING_BOARD` | 保留 |

**不搬**：4 个 `MekCkUpgradeTracker`、能力暴露、红石、槽位布局常量、
`getCapability` 分支、`AE2` 拉料实现 —— 这些全部由 Task 1 的基类提供。

- [ ] **Step 2: 先写特征测试**

在动刀前，对**现有** `CuttingMachineFactoryBlockEntity` 的可测逻辑写单测
（`CutRecipeMatching` 之类），锁住当前行为。
理由：1314 行里配方匹配是唯一有分支陷阱的部分，迁完再补测试就晚了。

⚠️ 注意：切菜配方是 `vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe`，
**它继承了 `Recipe` 并可能触达注册表**。若纯 JVM 加载不了，
改用**源码不变量测试**（沿用 `TestNbtPersistenceInvariants` 的做法：
读源文本检查结构约束，不实际运行）。**不要**为了写测试而启动游戏。

- [ ] **Step 3: 写执行器**

`CuttingFactoryExecutor` 实现 `MekCkRecipeExecutor`，内部持有
`MekCkMachineTile` 引用与 `CuttingBoardRecipe[] slotRecipeValue` 缓存。

- [ ] **Step 4: 测试 + 编译 + 提交**

```bash
cd /d/mc/mod/mekck && ./gradlew test --console=plain
MSYS_NO_PATHCONV=1 git add src/main/java/cn/ism/mekck/machine/cutting/ src/test/
MSYS_NO_PATHCONV=1 git commit -m "切菜工厂：提取 CuttingFactoryExecutor 并补特征测试"
```

---

## Task 4: 切菜 tile + 方块 + 菜单 + 界面

**Files:**
- Create: `src/main/java/cn/ism/mekck/machine/cutting/CuttingFactoryTile.java`
- Modify: `block/CuttingMachineFactoryBlock.java`（162 行）
- Modify: `menu/CuttingMachineFactoryMenu.java`（388 行）
- Modify: `client/CuttingMachineFactoryScreen.java`（741 行）
- Modify: `UniversalCuttingMachine.java`（注册迁到 `BlockDeferredRegister`）

**Interfaces:**
- Consumes: `MekCkMachineTile`、`IMekCkPorted`、`CuttingFactoryExecutor`
- Produces: `CuttingFactoryTile extends MekCkMachineTile implements IMekCkPorted`

- [ ] **Step 1: 写 tile**

`CuttingFactoryTile` 必须：
- `getTier()` 返回 `CuttingMachineFactoryTier`（**与阶段 1 `mekck$tier()` 反射桥接的类名和签名一致**）
- `meGroupParallelItemInputs()` 返回 `true`（并行槽要塌缩）
- `meManualOnlyItemSlots()` 排除升级槽、能量槽、侧配槽

- [ ] **Step 2: 方块改 `BlockTile` + `BlockTypeTile` 描述**

照 `factory/MekCkFactoryRegistration.java:140-176` 的 `blockTypeFor` 写法，
四个属性一个都不能少（缺任一项的实测症状见 Global Constraints 第 3 条）。
延迟 Supplier 破环（`containerRef` / `findTile`）直接沿用。

- [ ] **Step 3: 菜单改 `MekanismTileContainer`**

参照 `factory/MekCkFactoryMenu.java:29`。槽位索引体系换成 `IInventorySlot` 引用后，
`getInputSlotRange()` 那套下标逻辑可以整个删掉。

- [ ] **Step 4: 界面改 `GuiConfigurableTile`**

参照 `factory/MekCkFactoryScreen.java:38`。
`GuiConfigurableTile` 会自动排 侧配(6) / 传输配置(34) / 升级(6,右) / 红石(137,右)，
不再手抄坐标。

⚠️ **预期变化**：旧界面是 741 行手摆 tab，新界面用 Mek 的布局引擎，
tab 坐标必然与旧版不同。**这是预期变化不是回归**，但要记录下来供对照。

- [ ] **Step 5: 编译 + 提交**

```bash
cd /d/mc/mod/mekck && ./gradlew test --console=plain
MSYS_NO_PATHCONV=1 git add src/main/java/cn/ism/mekck/machine/cutting/ src/main/java/cn/ism/mekck/block/CuttingMachineFactoryBlock.java src/main/java/cn/ism/mekck/menu/CuttingMachineFactoryMenu.java src/main/java/cn/ism/mekck/client/CuttingMachineFactoryScreen.java src/main/java/cn/ism/mekck/UniversalCuttingMachine.java
MSYS_NO_PATHCONV=1 git commit -m "切菜工厂：tile / 方块 / 菜单 / 界面全面 Mek 原生化"
```

---

## Task 5: 删除旧实现 + 清理死代码

**Files:**
- Delete: `src/main/java/cn/ism/mekck/blockentity/CuttingMachineFactoryBlockEntity.java`（1314 行）
- Modify: `src/main/java/cn/ism/mekck/CuttingMachineFactoryTier.java`（删第 38–64 行的 7 个 `getXxxBlockId()`）

- [ ] **Step 1: 确认无残留引用**

```powershell
Select-String -Path "src\main\java\cn\ism\mekck\**\*.java" -Pattern "CuttingMachineFactoryBlockEntity"
```
预期：只剩待删文件自身。**若还有别的引用，列出来再决定**，不要盲删。

- [ ] **Step 2: 删旧 BE + 死方法**

- [ ] **Step 3: 编译 + 全量测试 + 提交**

```bash
cd /d/mc/mod/mekck && ./gradlew test --console=plain
MSYS_NO_PATHCONV=1 git add -A src/main/java/cn/ism/mekck/blockentity/ src/main/java/cn/ism/mekck/CuttingMachineFactoryTier.java
MSYS_NO_PATHCONV=1 git commit -m "删除切菜工厂旧 BlockEntity 与 tier 的死方法"
```

---

## Task 6: 回归自检 + 实机验证清单

**Files:** 无代码改动（除非自检发现问题）

- [ ] **Step 1: 静态自检**

```powershell
"C:\Program Files\PowerShell\7\pwsh.exe" -NoProfile -c '
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Set-Location "D:\mc\mod\mekck"
"残留的旧升级系统引用（阶段 3 才清，属预期）:"
Select-String -Path "src\main\java\cn\ism\mekck\**\*.java" -Pattern "MekCkUpgradeTracker" | Measure-Object | ForEach-Object { "  " + $_.Count + " 处" }
"切菜 tile 是否已 Mek 原生:"
Select-String -Path "src\main\java\cn\ism\mekck\machine\cutting\CuttingFactoryTile.java" -Pattern "extends MekCkMachineTile|implements IMekCkPorted"
"阶段 1 反射桥接的类名是否对得上:"
Select-String -Path "src\main\java\cn\ism\mekck\mixin\MixinTileComponentUpgradePersistence.java" -Pattern "MekCkMachineTile|getTier"'
```

- [ ] **Step 2: 把本任务的实机验证项追加进交接文档**

在 `docs/superpowers/handoff/2026-09-29-phase1-runtime-verification.md` 的
第 4 节（阶段 2 验收点）下新增「切菜工厂」小节，列出本任务实际能验的项。

⚠️ **本环境无法启动游戏。** 实机验证必须由你在
`D:\mc\新建文件夹\versions\1.20.1-Forge_47.4.23` 上做。

**这一节尤其重要**：切菜工厂是第一个真正持有 `TileComponentUpgrade` 的 MekCK tile，
意味着阶段 1 的 4 个 Mixin **从这一版开始真正生效**。
特别要验的是：

- [ ] 启动不崩（4 个 Mixin 首次真正被应用）
- [ ] 存储卡装上后并行线程从 `base` 变成 `base × 2^n`；
      BASIC~ULTIMATE 四档不支持倍增（`supportsStackUpgrade()`）
- [ ] **点「移除升级」不崩服**（C1 修复的验收点）
- [ ] 存档往返：装 3 个存储卡 → 存档 → 重进 → 仍是 3 个
- [ ] 切菜功能：放原料 → 出产物；并行数随存储卡提升
- [ ] GUI 各 tab 位置（预期与旧版不同，见 Task 4 Step 4）

- [ ] **Step 3: 提交验证文档更新**

```bash
MSYS_NO_PATHCONV=1 git add docs/superpowers/handoff/
MSYS_NO_PATHCONV=1 git commit -m "补切菜工厂的实机验证项"
```

---

## 阶段 3 的前置（不在本计划范围）

本任务完成后，`factory/` 雏形（8 个类）与 `assets/mekckfactory/`（约 84 个 blockstate）
即可整体删除。切菜工厂将是**第一个证明这条迁移路径可行的实例**，
其余 6 个家族按同一模板推广。

**推广前先复盘本任务的抽象层次是否正确**——若 `MekCkMachineTile` 的边界划错了，
现在改成本低，推广 6 个之后改成本极高。

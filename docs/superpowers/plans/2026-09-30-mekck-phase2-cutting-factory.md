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

## Task 4.5: 补旧存档迁移（必须在 Task 5 删旧 BE 之前完成）

**为什么是阻塞项**：方块注册名不变，但 `BlockEntityType` 的实现类换了。
旧 NBT 无人读取 → **机器内容静默归零**（方块还在，里面空了）。

**已核实的旧键布局**（`blockentity/CuttingMachineFactoryBlockEntity.java:1125-1207`）：

| 旧键 | 类型 | 内容 |
|---|---|---|
| `SpeedUpgradeTracker` / `EnergyUpgradeTracker` / `StackUpgradeTracker` / `CreativeUpgradeTracker` | CompoundTag | 各含 `Installed: int`（`MekCkUpgradeTracker.save()`） |
| `Items` | CompoundTag | `ItemStackHandler.serializeNBT()` = `{Size:int, Items:ListTag<{Slot,Item}>}` |
| `Energy` | int | 存量 |
| `Progress` | int | 加工进度 |
| `SideConfig` | byte[6] | 六个面的 `SideMode.ordinal()` |
| `AutoDistribute` | boolean | |
| `RedstoneControl` | int | `RedstoneControl.ordinal()` |
| `RedstonePowered` | boolean | |
| `AutoSelectedItems` | ListTag\<String\> | |
| `CustomName` | String | `Component.Serializer` 的 JSON |

**迁移目标**：

| 旧 | 新 |
|---|---|
| 4 个 Tracker 的 `Installed` | `componentUpgrade.upgrades`（名字键，经阶段 1 的 Mixin） |
| `Items` 的槽位序列 | `MekCkMachineTile` 的 `IInventorySlot` 列表（**下标需按旧布局映射**：0..N-1 输入、N..2N-1 输出） |
| `Energy` | `MachineEnergyContainer` |
| `Progress` | 执行器自有状态 |
| `SideConfig` | `TileComponentConfig`（⚠️ **两边格式不同**，Mek 用自己的转码，见下） |
| `RedstoneControl` / `RedstonePowered` | 基类的红石状态 |
| `AutoDistribute` / `AutoSelectedItems` | 由 Task 4.6 的 AE2 层接管 |
| `CustomName` | 基类已有 |

- [ ] **Step 1: 确认 `SideConfig` 两边的格式**

用 javap 核实 `TileComponentConfig` 的读写方法与它用的字节编码，
**不要假设与我们的 6 字节枚举数组同构**。若不兼容，走"读旧字节 → 转成 Mek 侧配对象"的显式转换。

- [ ] **Step 2: 在 `MekCkMachineTile.load(CompoundTag)` 加版本判断与迁移**

```java
// 有 MekCkNative 标记 = 已是新格式，直接读
// 无标记 = 旧格式，先迁移再读
```

**必须一次性全量迁移，不要留「兼容读旧键」的分支**——两套格式并存会让后续每个 bug 都要查两遍。

- [ ] **Step 3: 测试 + 提交**

用**旧格式样本 NBT** 做测试（把上表结构写成字面量），
断言迁移后槽位内容、能量、升级数量、红石状态逐项正确，且**迁移幂等**（`load` 两次结果相同）。

```bash
cd /d/mc/mod/mekck && ./gradlew test --console=plain
MSYS_NO_PATHCONV=1 git add src/main/java/cn/ism/mekck/machine/ src/test/
MSYS_NO_PATHCONV=1 git commit -m "切菜工厂：旧存档 NBT 迁移"
```

---

## Task 4.9: 槽位上限改为可配（`MekCkSlot`）

**已独立核实的依据**：

```
常量池 #27 = Integer 64   →  BasicInventorySlot.DEFAULT_LIMIT = 64

public int getLimit(ItemStack stack) {
    if (this.obeyStackLimit && !stack.isEmpty())
        return Math.min(this.limit, stack.getMaxStackSize());
    return this.limit;
}
```

所以单槽实际上限是 `min(64, 物品自身堆叠上限)`。而
`CuttingFactoryExecutor` 用 `Integer.MAX_VALUE` 判容量——**两者不一致**，
高倍合成倍率下余料会掉地上。BASIC 档 3 槽 × 64 = 192 件总容量。

⚠️ 顺带纠正两个流传的错误说法：
- `InputInventorySlot.at(...)` 传给 `BasicInventorySlot` 的尾部两个 `int` 是 **x/y 坐标**
  （`iload 4` / `iload 5`），**不是 maxStack**
- `mekanism.api.DataHandlerUtils` 在 **`mekanism.api`** 包，不在 `mekanism.common.util`

**修法不需要 Mixin**——`BasicInventorySlot` 有一个 `protected` 构造接受任意 limit：

```java
protected BasicInventorySlot(int limit,                       // ← 第一个参数就是 limit
                             BiPredicate canExtract, BiPredicate canInsert,
                             Predicate validator, IContentsListener listener,
                             int x, int y)
```

- [ ] **Step 1: 写 `MekCkSlot`**

`src/main/java/cn/ism/mekck/machine/MekCkSlot.java` —— 继承 `BasicInventorySlot`，
用上述 7 参构造传入可配 limit。

保留 `InputInventorySlot` 的两个行为：
- `ContainerSlotType.INPUT` / `OUTPUT` —— 7 参构造默认设 `NORMAL`，
  **构造后调 `setSlotType(...)` 改回**（`setSlotType` 是 public）
- `notExternal` 插入判定 —— 作为 `canInsert` 参数显式传入

- [ ] **Step 2: `MekCkMachineTile.getInitialInventory()` 换用 `MekCkSlot`**

替换 `InputInventorySlot.at(...)` / `OutputInventorySlot.at(...)`。
**上限值从哪来要有明确决定**：建议按档位取 `tier.processes` 相关的值，
或复用 `MekckConfig` 里已有的容量配置——**不要凭空定一个数**。
若 `MekckConfig` 里没有对应项，说明该加一个，并在报告里说明默认值与理由。

- [ ] **Step 3: 让 `CuttingFactoryExecutor` 的容量判定与槽位一致**

执行器现在用 `Integer.MAX_VALUE`（Task 3 的刻意选择，理由是「用 Mek 的
`getLimit` 会被截到 64 导致静默降速」——那个理由在 `MekCkSlot` 落地后**不再成立**）。
改成读实际槽位的 `getLimit(stack)`，两者口径统一。

⚠️ 这个改动**会改行为**：之前"永远装得下"变成"装到槽位上限为止"。
这是**修正**不是回归——但要在报告里写明，并确认 Task 3 那个
`veryHighParallelTiers...` 之类的测试断言不需要跟着改。

- [ ] **Step 4: 测试 + 提交**

```bash
cd /d/mc/mod/mekck && ./gradlew test --console=plain
MSYS_NO_PATHCONV=1 git add src/main/java/cn/ism/mekck/machine/ src/test/
MSYS_NO_PATHCONV=1 git commit -m "槽位上限改为可配：新增 MekCkSlot，统一执行器与槽位的容量口径"
```

**顺带**：把 §4.8 修好的那个测试与本文档里"槽位单槽 64"的记述一并更新，
别让后人以为上限还是 64。

---

## Task 4.8: 基类自存 int 下标槽位数据（修 SINGULARITY 丢槽）

**前提已独立确认**（`javap -c mekanism.api.DataHandlerUtils`，注意包名是 `mekanism.api` 不是
`mekanism.common.util`）：

```
writeContents:  invokevirtual  CompoundTag.putByte:(Ljava/lang/String;B)V
readContents:   invokevirtual  CompoundTag.getByte:(Ljava/lang/String;)B
                37: iflt 64          ← 负值直接跳过
```

Mek 用 **byte** 存取槽位下标，读到负数**静默跳过**。
`2N ≤ 127` 即 `N ≤ 63` 才安全——CRYSTAL_MATRIX(36)、NEBULA(49) 没问题，
**SINGULARITY(81 → 2N=162) 每次存读档丢 34 个输出槽与能源槽**。
这不是迁移引入的（新建的 81 并行机器照样丢），但必须修。

**为什么不用第 5 个 Mixin**：`DataHandlerUtils.writeContents/readContents` 是
`static` 且**只接槽位列表、没有 tile 上下文**，无法按 tile 收窄——
重定向会改掉整个整合包所有 Mekanism 机器（含 Mek 自带的）的存档格式，
导致「装了 MekCK 存的档，没装 MekCK 打开时 Mek 自己的机器读不出来」。

**做法**：MekCK 的机器自己写一份 **int 下标**的槽位数据，放在 MekCK 专属键下。
Mek 原生那份（byte）照写不误，只是对 MekCK 的机器**不再是权威来源**。

- [ ] **Step 1: 在 `MekCkMachineTile` 加读写**

```java
// 键名与 Mek 的 "componentUpgrade"/"Items" 刻意不同，避免任何交叉污染
private static final String KEY_MEKCKK_ITEMS = "MekCkCkItems";   // 实际命名按仓库风格定
```

- `saveAdditional(CompoundTag)`：先 `super.saveAdditional(tag)`（Mek 照常写它那份），
  **再**用 int 下标把自己的槽位数据写进专属键
- `load(CompoundTag)`：先 `super.load(tag)`（Mek 按 byte 读，128+ 已丢），
  **再**从专属键读 int 版本并**覆盖**槽位内容
- ⚠️ **覆盖必须在 `super.load` 之后**——`TileComponentUpgrade.read` 第一件事是
  `upgrades.clear()`（这条已被 `TestLegacyMachineNbtMigration` 的源码不变量测试钉住，同理适用）

- [ ] **Step 2: 兼容「只有旧 byte 格式」的档**

若专属键不存在（MekCK 装之前存的、或只有 Mek 那份），回落到读 Mek 的 byte 格式——
即**退化到当前行为**，不报错。

- [ ] **Step 3: 测试**

- 81 并行下，第 128 号与第 161 号槽的物品在「存 → 读」后**仍然存在**（这是核心断言）
- 旧格式档（只有 Mek 那份 byte）能正常读入
- 新格式档读两次结果相同（幂等）
- 低端位（< 128）行为不变

- [ ] **Step 4: 提交**

```bash
cd /d/mc/mod/mekck && ./gradlew test --console=plain
MSYS_NO_PATHCONV=1 git add src/main/java/cn/ism/mekck/machine/ src/test/
MSYS_NO_PATHCONV=1 git commit -m "基类自存 int 下标槽位数据，修 SINGULARITY 每次存读档丢 34 槽"
```

**顺带**：把 `TestLegacyMachineNbtMigration` 里那个"钉住已知限制"的测试改成断言**修复后**的行为，
并把它的注释更新为「已修复 + 修复方式 + 为什么不走 Mixin」——
留着一个钉住 bug 的测试会误导后人。

---

## Task 4.6: AE2 自动化层改消费 `IMekCkPorted`

**为什么**：`ae2/MekckAe2.java`（2358 行）与 `network/` 的三个包**仍然指向旧的
`CuttingMachineFactoryBlockEntity`**。Task 5 一删旧 BE，这些 `instanceof` 永远不匹配 →
网络拉料按钮、自动处理、ME 下单**静默失效**（不崩，就是没反应）。

已核实的断链点：

```
network/AutoDistributePacket.java:36          if (be instanceof CuttingMachineFactoryBlockEntity machine)
network/AutoProcessListRequestPacket.java:36  if (be instanceof CuttingMachineFactoryBlockEntity || ...)
network/AutoProcessListPacket.java:48         // 依赖旧 tile 的 autoSelectedItems
```

**为什么**：`ae2/MekckAe2.java`（2358 行）与 `network/` 的三个包**仍然指向旧的
`CuttingMachineFactoryBlockEntity`**。Task 5 一删旧 BE，这些 `instanceof` 永远不匹配 →
网络拉料按钮、自动处理、ME 下单**静默失效**（不崩，就是没反应）。

已核实的断链点：

```
network/AutoDistributePacket.java:36          if (be instanceof CuttingMachineFactoryBlockEntity machine)
network/AutoProcessListRequestPacket.java:36  if (be instanceof CuttingMachineFactoryBlockEntity || ...)
network/AutoProcessListPacket.java:48         // 依赖旧 tile 的 autoSelectedItems
```

- [ ] **Step 1: 让 `MekckAe2` 改用 `IMekCkPorted` 的端口声明**

把"哪些槽是配料、哪些是产物"的判定，从"读旧 BE 的槽位下标"改成
"问 `IMekCkPorted`"。`meGroupParallelItemInputs()` 返回 true 时，
**N 个并行输入槽按 1 个组端口处理**——这正是 81 线程机器在 AE2 里不被当成 81 个独立配料口的关键。

- [ ] **Step 2: 改三个包的 `instanceof`**

从 `instanceof CuttingMachineFactoryBlockEntity` 改成 `instanceof IMekCkPorted`。
`AutoProcessListPacket` 依赖的 `autoSelectedItems`（自动处理清单）需要在新 tile 上有对应存储。

- [ ] **Step 3: 测试 + 提交**

```bash
cd /d/mc/mod/mekck && ./gradlew test --console=plain
MSYS_NO_PATHCONV=1 git add src/main/java/cn/ism/mekck/ae2/ src/main/java/cn/ism/mekck/network/
MSYS_NO_PATHCONV=1 git commit -m "AE2 自动化层改消费 IMekCkPorted，切菜工厂不再断链"
```

---

## Task 4.7: 随机化卡补齐机械收益

**背景**：旧的创造升级（`mekanism_extras:upgrade_creative`）除"随机化 49 食物"外还带
**免耗电 + 1 tick 批次 + 自动补满**三个分支。新的 `mekck:upgrade_randomize`
（`MekCkUpgradeRefs.randomize()`，`maxStack=1`）**已在 `isSupportedBy` 的放行集里**，
但那三个分支在 Task 4 接线时没有对应实现。

- [ ] **Step 1: 查清旧行为的确切语义**

读旧 `CuttingMachineFactoryBlockEntity` 里与 `creativeTracker` / `hasCreativeUpgrade`
相关的分支，逐条记录：免耗电是"完全跳过能量扣减"还是"能量消耗乘 0"？
1 tick 批次是"进度门槛从 N 降到 1"还是别的？自动补满是哪个槽位？

**不要凭描述实现**——按旧代码逐条对照。

- [ ] **Step 2: 在 `CuttingFactoryTile` / `MekCkMachineTile` 实现**

三个分支做成**基类的可覆写钩子**（其余 6 个家族可能不需要），
默认实现走"无随机化卡"的原行为。

- [ ] **Step 3: 测试 + 提交**

```bash
cd /d/mc/mod/mekck && ./gradlew test --console=plain
MSYS_NO_PATHCONV=1 git add src/main/java/cn/ism/mekck/machine/
MSYS_NO_PATHCONV=1 git commit -m "随机化卡补齐免耗电 / 1 tick 批次 / 自动补满"
```

---

## Task 5: 删除旧实现 + 清理死代码

**Files:**
- Delete: `src/main/java/cn/ism/mekck/blockentity/CuttingMachineFactoryBlockEntity.java`（1314 行）
- Modify: `src/main/java/cn/ism/mekck/CuttingMachineFactoryTier.java`（删第 38–64 行的 7 个 `getXxxBlockId()`）
- Modify: `src/main/java/cn/ism/mekck/UniversalCuttingMachine.java`（**删 11 个恒为 null 的 `*_FACTORY_BLOCK_ENTITY` 字段与恒空的 `FACTORY_BLOCK_ENTITIES` 集合**）

⚠️ **Task 4.5/4.6/4.7 全部完成后再做本任务。**
4.5 的迁移需要读旧 NBT 布局，4.6 的断链点仍指向旧类——两者都要求旧 BE 还在。

⚠️ 11 个 `*_FACTORY_BLOCK_ENTITY` 字段现在恒为 `null`、`FACTORY_BLOCK_ENTITIES` 恒空
（一个注册名不能挂两个 `BlockEntityType`）。实测它们在 `UniversalCuttingMachine`
之外无读取点，但**必须一并删掉**，否则留下永久静默 null。

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

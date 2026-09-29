# MekCK 机器体系 Mek 原生化 —— 完整框架设计

- 日期：2026-09-29
- 状态：**设计已批准；§5.1 实测核实已完成并回写本文档，待实施**
- 目标工程：`D:\mc\mod\mekck`（MC 1.20.1 / Forge 47.4.16 / Java 17 / Mekanism 10.4.6.20）
- 规模基线：`blockentity/` 25 文件 / 30,319 行；`client/` GUI ~40 类；`network/` 25 包；`ae2/` 2,358 行
- 核实依据：`javap` 实测 `mekanism-268560-6018299_mapped_official_1.20.1.jar`（方法签名与字节码），核实清单见 §5.1

## 1. 目标与判据

把 MekCK 从"**借用 Mekanism 的库**"改造成"**按 Mekanism 的规矩造机器**"。

**你要的四件事，全部成立**（§5.1 已逐条实测）：

| 目标 | 怎么成立 |
|---|---|
| 用 Mek 的机器 | `TileEntityConfigurableMachine` / `TileEntityMekanism` 提供能量、侧配、弹出、升级组件、GUI 布局引擎、多方块、化学罐、热。`factory/MekCkFactoryRegistration.java` 已证明注册路径可行（84 方块、GUI 可开、tab 对齐） |
| 注册我们的配方 | 完全不涉及 Mek。`DeferredRegister<RecipeType/RecipeSerializer>` 独立注册，MekCK 已有 10 余种在跑；执行逻辑写在 `tickServer()` |
| 注册我们的升级等级 | `MekCkFactoryTier` 已是独立枚举（`StringRepresentable` + `SupportsColorMap`），走 Mekanism Extras `ExtraFactoryTier` 的路子。Mek 不限制附属扩充等级数；容量/能耗经方块 `AttributeEnergy` 声明 |
| 注册我们的升级卡 | 卡片实现 `IUpgradeItem`（单方法）返回自己的常量，常量进 `supported` 集，槽位/读条/安装/GUI 全部由 Mek 原生组件处理。**卡片本身零 Mixin**，只有把常量注入进枚举那一步需要 |

三条可验证判据，缺一不算完成：

1. **机器 tile 继承 `TileEntityMekanism`**。能量走 `MachineEnergyContainer`，侧配走 `TileComponentConfig`，热走 `MekCkHeatComponent`，槽位走 `IInventorySlot`。全仓库不再有自研 `EnergyStorage` 字段。
2. **升级系统吃下当前已加载的全部 `Upgrade` 常量**。Mek 原生 7 种 + Mek Extras 注入的 3 种 + Mek Energistics 注入的 3 种 + MekCK 自己的 2 种。**任一注入者缺席或增删都不炸档、不丢数据、不张冠李戴**——最后一条靠 §5.4 的名字键持久化保证，因为 Mek 自己的 ordinal 索引做不到。
3. **存档往返无损**。迁移前后，任意一台机器的升级数量、订单进度、缓冲产物完全一致。

## 2. 已核实的事实

### 2.1 Mekanism 没有官方附属框架

Mekanism 组织下只有 3 个仓库（`Mekanism` / `mekanism.github.io` / `Mekanism-Feature-Requests`），**无 `Mekanism-Addons`**。`mekanism.wiki` DNS 不存在（NXDOMAIN）。拉取 5 个分支（`release/1.20.x`、`1.20.2`、`1.20.4`、`1.21.x`、`master`）共 14,209 条完整文件树，搜 `addon` **零命中**。1.20.1 的 `src/main/resources` 下**没有 `data/` 目录**（全 datagen），**没有 data map**。

真实存在的是 `mekanism.api.*`（286 文件，`@since 10.4.0`，编在 jar 内的独立 source set，不单独发 maven 坐标）。`mekanism.common.*` 是内部实现，**无稳定性承诺**。

> **推论**：没有"迁移到附属框架"这件事可做。只能选择"离 Mekanism 更近还是更远"。

### 2.2 MekCK 已有一个「附属雏形」，但是空壳

`factory/MekCkFactoryRegistration.java`（224 行）走的就是标准 add-on 写法，注释里有大量反编译实测结论：

- `AttributeEnergy` 签名是 `(usage, storage)`（**先用后容**）
- 缺 `AttributeGui` → 方块右键不开界面（`BlockTile.use()` 判定）
- 缺 `AttributeStateFacing` → `facing=` 变体全部匹配失败 → **方块隐形**
- `AttributeUpgradeSupport` 缺失 → `supportsUpgrades()` 为 false，升级槽与升级 tab 都不出现

但 `factory/MekCkFactoryTile.java:10` 自己写着「当前**尚未接入配方查找**」——全 `factory/` 目录搜 `tick()` 零命中。这 84 个方块能放、能开、能看到能量 tab，**但不生产**，且不在创造标签页里。玩家实际用的是另一套：`mekck:` 命名空间下 19 个自研 `BlockEntity`。

**它是全部迁移的模板。**

### 2.3 `Upgrade` 是写死的枚举，持久化按 ordinal 索引

`mekanism.api.Upgrade` 是 **7 常量 Java 枚举**（SPEED/ENERGY/FILTER/GAS/MUFFLING/ANCHOR/STONE_GENERATOR）。**1.20.1 没有 data map，也没有 namespaced 升级注册表。**

**以下全部经 `javap` 实测**（`mekanism-268560-6018299_mapped_official_1.20.1.jar`）：

```java
public enum Upgrade implements IHasTranslationKey {
    SPEED, ENERGY, FILTER, GAS, MUFFLING, ANCHOR, STONE_GENERATOR;
    private final String name; private final APILang langKey, descLangKey;
    private final int maxStack;  private final EnumColor color;
    private static final Upgrade[] UPGRADES;   // 私有缓存，非 $VALUES
    private static final Upgrade[] $VALUES;
    public int getMax();                        // ← 访问器名是 getMax，不是 getMaxStack
    public static Map<Upgrade,Integer> buildMap(CompoundTag);
    public static void saveMap(Map<Upgrade,Integer>, CompoundTag);
    public static Upgrade byIndexStatic(int);
    public CompoundTag getTag(int amount);
}
```

**🔴 持久化是按 `ordinal()` 索引，不是枚举名，且读取时取模回绕：**

```java
// 写：getTag(int amount)
tag.putInt("type", ordinal());
tag.putInt("amount", amount);

// 读：buildMap(CompoundTag)
Upgrade type = byIndexStatic(c.getInt("type"));
int amount  = Mth.clamp(c.getInt("amount"), 0, type.maxStack);   // ← 读私有字段，不是 getMax()

// byIndexStatic
return (Upgrade) MathUtils.getByIndexMod(UPGRADES, index);

// MathUtils.getByIndexMod
return array[index < 0 ? Math.floorMod(index, array.length) : index % array.length];
```

**后果**：索引越界**不抛异常、不打日志，直接回绕到另一种升级**。玩家增删任何一个注入 `Upgrade` 的 mod，所有高 ordinal 常量整体位移，旧存档数据**静默张冠李戴**。解法见 §5.4。

**`TileComponentUpgrade` 内部**（同为实测）：

```java
private final Map<Upgrade,Integer> upgrades;    // 存储
private final Set<Upgrade> supported;           // 支持集
private final UpgradeInventorySlot upgradeSlot, upgradeOutputSlot;
public void tickServer();                       // 20 tick 读条 + 安装
public int getUpgrades(Upgrade); public int addUpgrades(Upgrade,int);
public void setSupported(Upgrade); public boolean supports(Upgrade);
public void read(CompoundTag); public void write(CompoundTag);   // 经由 Upgrade.buildMap/saveMap
```

`getMax()` 在**两处**被检查——`tickServer()`（`getUpgrades(type) < type.getMax()` 决定读条是否推进）与 `addUpgrades()`（`Math.min(getMax() - current, amount)` 决定实际装几个）。

**升级卡不需要 Mixin 就能工作**（实测）：

```java
public interface mekanism.common.item.interfaces.IUpgradeItem {
    Upgrade getUpgradeType(ItemStack stack);      // 唯一抽象方法
}
// 槽位构造：upgradeSlot = UpgradeInventorySlot.input(listener, supported);
// supported 是 Set<Upgrade>，槽位合法性直接由它决定
```

链路：MekCK 的卡实现 `IUpgradeItem` 返回自己的常量 → 常量进 `supported` 集 → 槽位自动认 → `tickServer()` 每 tick 检查 `supports(type)`，满 20 tick 安装。**Mixin 只有一处省不掉：把常量注入进枚举本身。**

已确认有**三个** mod 在做该注入：

| mod | 注入内容 | 机制 |
|---|---|---|
| Mek Extras (`lostmyself8`) | `STACK`(max 6) / `IONIC_MEMBRANE` / `CREATIVE` | `@Mutable @Shadow Upgrade[] $VALUES` + `@Invoker` 私有构造 + 末尾 `UPGRADES = $VALUES` |
| Mek Energistics (`beipuo`) | `ME_PATTERN_PROVIDER` / `ME_PASSIVE_CRAFTING` / `ME_OUTPUT_INTERFACE` | `mixin/UpgradeMixin.java:19-51`，同上 |
| MekCK | `STORAGE` + 创造卡（本次新增） | 同上 |

**共存不是假设，是既成事实。** 三个 `<clinit>` TAIL 注入的执行顺序由 mixin config 加载序决定，**不保证**。`@Shadow` 读的是活字段，所以三者可以叠加；但 ordinal 分配顺序因此也不保证（这正是 §5.5 问题的根源）。

### 2.4 🔴 MekCK 当前的升级是「借物品 + 未声明依赖」

- `MekCkUpgradeType.java:19-20` 硬指向 `mekanism_extras:upgrade_stack` / `mekanism_extras:upgrade_creative`
- `UpgradeHelper.java:23-24` 硬编码同样两个 ID
- `MekCkUpgradeType.java:57-58` 的上限 6 / 1 **恰好等于** Mek Extras 的 `maxStack`——因为那就是它的升级
- **`mods.toml` 的依赖列表里没有 `mekanism_extras`**（只有 forge / minecraft / farmersdelight / mekanism / ae2）

**这是一个真 bug**：没装 Mek Extras 时 `ForgeRegistries.ITEMS.getKey()` 取不到 ID → `UpgradeHelper.getType()` 全返回 `NONE` → **STACK/CREATIVE 槽永远填不上，线程数锁死基础值，创造模式完全失效**。不崩溃、不报错、不打日志。`CREDITS.txt:16` 把它列在「可选联动」里，与实际行为不符。

### 2.5 AE2 集成已经存在且形状正确

`ae2/MekckAe2.java` **2,358 行**，已经：

- 挂 `Capabilities.IN_WORLD_GRID_NODE_HOST`（`:100-115`，AE2 15.x 的正确 API 名）
- **实现 `ICraftingProvider`**（AE2 标准集成接口）
- 按网络内可用食材动态注册加工样板
- `pushPattern` 时抽料 → **复用机器现有订单管线** → 产物与空容器回写网络
- 每台机器 8 个网格节点，带 `IAEPowerStorage` 与 AE 电力支持

**不需要新 SPI。**

但它建在会被迁移摧毁的地基上。`INetworkPullable` 的签名：

```java
int[] getInputSlotRange();                    // 槽位下标区间
ItemStackHandler getNetworkPullItems();       // Forge 原生存储
int[] getExtraInputSlots();                   // 还得单独补一段
```

`getExtraInputSlots()` 的注释写明：联动机器的有效输入槽是 **0..4 与 10..13 两段不连续**区间，`int[]` 只能表达一段连续区间，所以得用「区间 ∪ 额外槽」硬凑。切到 `IInventorySlot` 后槽位下标这个概念不存在，**`INetworkPullable` 必然要重写**。

### 2.6 Mek Energistics 兼容：长期约束，非本期交付物

Mek Energistics 有官方 SPI `IMePatternAutomationHost`（3.0.6 起公开，带 `API_VERSION`）：

```java
List<IInventorySlot>       mePatternItemInputs();
List<IInventorySlot>       mePatternItemOutputs();
List<IInventorySlot>       mePersistentItemInputs();   // 常驻槽，跨单不清空
List<IInventorySlot>       meManualOnlyItemSlots();
boolean                    meGroupParallelItemInputs(); // N 个同物输入塌缩成 1 组端口
```

SPI **只用 `mekanism.api.*` 类型，零 `appeng.*`**（作者写了单测扫字节码常量池强制这条）。`meGroupParallelItemInputs()` 正好解决 MekCK Singularity 级 81 线程被 AE2 当成 81 个独立配料口的问题。

**但它是 1.21.1 / NeoForge / Mek ≥10.7.19 / AE2 ≥19.2.17**，与 MekCK 的 1.20.1 / Forge / 10.4.x / 15.4.10 无交集，**无法作为编译期依赖**。且 AE2 15.4.10 里**没有 `AECapabilities`**（1.20.1 走 `instanceof IInWorldGridNodeHost`），对方移植时那处要重写。

**已决定：MekCK 继续吃 1.20.1，不跳版本。** 本期不为它设计 SPI、不写桥接，只在方法命名上留门（见 §3.3）。

## 3. 架构总览

四层，自下而上。**上层可以逐层替换，下层一旦定死就不再动。**

```
┌─ L3 内容层：配方 / 订单 / 批量执行 ──────────────────────┐
│   CookingFactoryRecipeExecutor、CuttingFactoryRecipeExecutor ... │
│   —— 纯逻辑：输入槽集合 + 配方 → 输出。不碰 tile、不碰 GUI    │
├─ L2 机器层：MekCkMachineTile（继承 TileEntityConfigurableMachine）┤
│   能力：能量 / 侧配 / 弹出 / 热 / 升级槽 / 配方执行器挂载点       │
├─ L1 抽象层：跨机器横切关注点                                  │
│   MekCkUpgrades（升级聚合 + 上限裁定）                         │
│   MekCkPorts（IInventorySlot 端口声明，供 AE2 消费）             │
├─ L0 注册层：BlockDeferredRegister / BlockTypeTile / DeferredRegister │
│   mekck: 收编全部方块；mekckfactory: 整体删除                   │
└───────────────────────────────────────────────────────┘
```

**L1 是本次的真正产出。** L0/L2 是把已有的雏形（`factory/`）扶正，L3 是逐机器的重复劳动。

### 3.1 L0 注册层

- 方块注册改用 `BlockDeferredRegister` / `TileEntityTypeDeferredRegister` / `ContainerTypeDeferredRegister`
- 方块描述用 `BlockTypeTile.BlockTileBuilder`：`withGui` / `withEnergyConfig` / `withSupportedUpgrades` / `AttributeStateFacing` / `Attributes.{ACTIVE,REDSTONE,SECURITY,INVENTORY}`
- **`mekck:` 收编，`mekckfactory:` 删除**（见 §7）
- 沿用 `MekCkFactoryRegistration` 的延迟 Supplier 破环写法（`containerRef` / `findTile`），已验证可行

### 3.2 L1-a `MekCkUpgrades` —— 升级聚合层

**职责：运行时发现当前所有可用的 `Upgrade` 常量，并裁定每台机器每种类型的安装上限。**

```
MekCkUpgrades
├── Set<Upgrade> discoverAll()
│     枚举 Upgrade.values() 全集 —— Mek 7 种 + 任何已加载 mod 注入的
│     （<clinit> 全部执行完毕后调用，故天然包含所有注入者）
├── Optional<Upgrade> byItem(ItemStack)
│     优先走 IUpgradeItem.getUpgradeType()（O(1)，卡片自身知道自己是哪张）
│     fallback 到遍历比对 UpgradeUtils.getStack(type, 1)，跨所有注入者通用
├── int capOf(MachineContext ctx, Upgrade type)
│     = min(MekckConfig 的按等级上限, type.getMax())     ← 见下
└── boolean isSupportedBy(MachineFamily family, Upgrade type)
```

**上限裁定不需要 Mixin。** `getMax()` 虽然在 `tickServer()` 和 `addUpgrades()` 两处被检查，但只要 `capOf()` **永远不超过 `type.getMax()`**，两个检查就自动满足：

- 对 Mek 原生类型：`capOf() = min(configCap, getMax())`，Mek 的检查永远通过
- 对 MekCK 注入的类型：`maxStack` 是**注入时由 MekCK 自己指定的**，想要多大给多大，`MekckConfig` 的按等级上限直接生效

`MekckConfig` 的 924 行配置因此**完整保留**，不需要退化。§5.1 最初担心的「上限冲突需要 Mixin」经实测不成立。

**容错契约（三条，缺一不可）：**

1. **NBT 读入 — 名字键**：MekCK 自己的持久化按**枚举名**写（§5.4），注入者缺席时该项**原样保留在存档里、不丢弃**。装回 Mek Extras 后数据自动恢复。**不使用 Mek 的 `buildMap`**，因为它的 ordinal + 取模回绕会造成张冠李戴而非丢弃（§2.3）。
2. **物品反查**：`byItem()` 找不到对应 `Upgrade` 时返回 `Optional.empty()`，调用方按「不是升级物品」处理，**不记 ERROR**（避免每 tick 刷屏）。
3. **数量裁剪**：读档时用 `MekCkUpgrades.capOf()` 裁剪，**不用** `Upgrade.buildMap` 里的 `Mth.clamp(..., type.maxStack)`——后者会按枚举自己的 `maxStack` 裁掉 MekCK 配置允许的更大值。

### 3.3 L1-b `MekCkPorts` —— 端口声明

替换 `INetworkPullable` 的槽位下标模型。形状刻意对齐 `IMePatternAutomationHost`，但**只依赖 `mekanism.api.*`**。

**这么起名的成本是零，收益是留门**：方法名与外部 SPI 对齐，将来任何自动化模组要接 MekCK 就是照名写反射，MekCK 不用再动一次。本期**不实现**桥接，只保证形状可桥。

**消掉的债**：`getExtraInputSlots()` 的「区间 ∪ 额外槽」凑合写法——槽对象天然离散，不需要拼区间。

### 3.4 L2 机器层

`MekCkMachineTile` 抽象基类，继承 `TileEntityConfigurableMachine`，承载：

- `presetVariables()`：**必须**初始化 `configComponent` / `ejectorComponent`，否则放置即崩（`TileEntityMekCkFactory.java:146-154` 有实测崩溃记录）
- `getInitialEnergyContainers()`：`MachineEnergyContainer.input(this, listener)`，容量/能耗从方块 `AttributeEnergy` 读
- `getInitialInventory()`：按 `tier.processes` 排输入/输出方阵，列数 `⌈√N⌉`
- 升级槽：走 Mek 的 `TileComponentUpgrade`，支持集由 `MekCkUpgrades` 计算

**⚠️ 构造期陷阱**（`TileEntityMekCkFactory.java:58-104` 已踩过两次）：`TileEntityMekanism` 构造器内部会回调 `getInitialInventory()`，那时子类字段初始化器**尚未执行**。

- 实例字段 `tier` / `factoryType` 读到的会是 `null` → 必须从 `blockProvider` 反查（`tierFromBlock()`）
- `inputSlots` / `outputSlots` **不能**写成 `= new ArrayList<>()` 字段初始化器 → 必须在 `getInitialInventory()` 里创建

实测崩溃记录：
- `NPE: Cannot read field "processes" because "this.tier" is null`
- `NPE: Cannot invoke "java.util.List.add(Object)" because "target" is null`

**独立机器的例外**：`BioreactorBlockEntity` 和 `SimpleMachineBlockEntity` 直接继承 `BlockEntity`，不在 `TileEntityMekCkFactory` 链上，阶段 3 单独处理（生物反应堆还要保留 `IBoundingBlock` 多方块结构）。

### 3.5 L3 内容层

每个家族的配方执行器是一个独立类，接口统一：

```java
IRecipeExecutor {
    Optional<RecipeMatch> findRecipe(MachineContext ctx);
    boolean tick(MachineContext ctx, RecipeMatch match);   // 返回是否本 tick 产出
    void save/load(...);                                    // 订单进度
}
```

`CookingFactoryBlockEntity` 的 2,099 行拆成「执行器 + 状态持久化 + 槽位声明」三块。

## 4. 阶段划分

| 阶段 | 范围 | 验收信号 | 需要实机 |
|---|---|---|---|
| **1** | 升级体系：聚合层 + 名字键编解码 + 2 个 Mixin + 2 张自有卡 + 升级数据迁移 | 单测绿；机器能装能卸；升级数据往返无损；旧存档升级数量不变 | **是**（两个 Mixin 只能在实机验证） |
| **2** | 烹饪工厂样板：L0/L1/L2/L3 全链路 | 该机器能开能产，GUI 是 `GuiConfigurableTile`，存档无损 | 是 |
| **3** | 推广到 6 家族 + 5 独立机器 | 逐个回归，对照迁移前后行为清单 | 是 |

**阶段 1 先行的理由**：升级体系是 19 个机器的共同依赖，先定死再铺开；且它的大部分能在脱离 Minecraft 启动的情况下单测（只有两个 Mixin 不行）。

## 5. 阶段 1 详细设计：升级体系

### 5.1 核实结果（原「阻塞核实项」，已完成）

用 `javap` 对 `mekanism-268560-6018299_mapped_official_1.20.1.jar` 实测，结论如下。**核实用的 jar 路径**（本机 Gradle 缓存，供后续复查）：

```
C:\Users\Administrator\.gradle\caches\forge_gradle\deobf_dependencies\
  curse\maven\mekanism-268560\6018299_mapped_official_1.20.1\
  mekanism-268560-6018299_mapped_official_1.20.1.jar
```

| 原问题 | 实测答案 | 对设计的影响 |
|---|---|---|
| 上限在哪检查？ | **`tickServer()` 与 `addUpgrades()` 两处，都在安装路径** | 原以为可能要 Mixin 重定向；**实际不需要**，见 §5.3 |
| 检查能覆写吗？ | `getMax()` 是枚举的非 final 实例方法，`invokevirtual` 调用，技术上可 Mixin | 但有更省的办法（§5.3） |
| `buildMap` 遇未知名？ | **不存在「未知名」这回事——它按 ordinal 索引，越界时 `getByIndexMod` 取模回绕** | **比预想严重得多**：不是丢弃，是静默张冠李戴。解法见 §5.4 |
| 卡片要不要 Mixin？ | **不要**。`IUpgradeItem` 单方法接口 + `UpgradeInventorySlot.input(listener, supported)` | §5.5 大幅简化 |
| 访问器叫什么？ | **`getMax()`**，不是 `getMaxStack()`（后者是从 Mek Extras 的 Mixin 构造参数名误推的） | 全文已改 |

**核实带来的最大变化**：阶段 1 从「上限裁定可能需要额外 Mixin」变成「上限零成本」（§5.3），升级卡从「可能需要 Mixin」变成「一张接口」（§5.5），同时**新增一个原本没意识到的存档安全问题**（§5.4）。

### 5.2 `MekCkUpgrades` 实现

- `discoverAll()` 在 `FMLCommonSetupEvent`（mod bus，末尾）调用一次并缓存 —— 此时所有 `<clinit>` 已执行
- 结果存**不可变 `LinkedHashSet`**，不用 `EnumSet`（注入的常量不在编译期集合里）
- `byItem()`：**先走 `IUpgradeItem.getUpgradeType()`**（卡片自身知道自己是哪张，O(1)）；对不实现该接口的物品 fallback 到遍历比对 `UpgradeUtils.getStack(type, 1)`
- 单测覆盖：`discoverAll()` 至少含 Mek 7 种；`byItem()` 对空栈/普通物品返回 empty；`capOf()` 在无配置覆盖时等于 `getMax()`

### 5.3 上限裁定：不需要 Mixin

`getMax()` 虽在两处被检查，但只要 `capOf()` **永远不超过 `type.getMax()`**，两个检查就自动满足：

```java
public static int capOf(MachineContext ctx, Upgrade type) {
    int configured = MekckConfig.<按等级查表>(ctx.tier(), type);
    return Math.min(configured, type.getMax());
}
```

- **Mek 原生类型**：`min(configCap, getMax())` —— Mek 的检查永远通过，`MekckConfig` 的按等级配置在 Mek 上限内完整生效
- **MekCK 注入类型**：`maxStack` 是**注入时由 MekCK 指定**的，想要多大给多大，`MekckConfig` 的上限直接生效

`MekckConfig` 的 924 行配置**完整保留，不需要退化**。原设计的「路 A（Mixin 重定向上限）」作废。

### 5.4 持久化：绕开 ordinal 索引（唯一必需的 Mixin）

**问题**：`TileComponentUpgrade.write()` 调 `Upgrade.saveMap()`（写 `type = ordinal()`），`read()` 调 `Upgrade.buildMap()`（读 `byIndexStatic` = 取模回绕）。

**故障场景**（假设装了 Mek Extras + Mek Energistics + MekCK）：

| 场景 | ordinal 分配 | 存档里的 `type=10` | 读回后变成 |
|---|---|---|---|
| 三者都在 | ME_OUTPUT=12, STORAGE=13, CREATIVE=14 | ME_OUTPUT=1 个 | ✅ 正确 |
| 玩家卸载 Mek Energistics | ME_OUTPUT 前移到 9，STORAGE→10 | 原本的 ME_OUTPUT=12 | **10 % 13 = 10 → STORAGE** ❌ |
| 玩家再装回 Mek Extras | 顺序又变 | 同一份存档 | 再次错位 |

`getByIndexMod` 取模 → **不抛异常、不打日志、静默把玩家的 ME 卡变成存储卡**。这是 Mekanism 数据模型自身的缺陷，MekCK 无法从外部修正，只能规避。

**解法**：**保留 Mek 的组件、tick、槽位、GUI、20 tick 读条——全部照用**（这就是「用 Mek 的机器」）。只在**持久化这一层**换成名字键：

```java
// MekCK 自己的编解码，替代 Upgrade.saveMap / Upgrade.buildMap
CompoundTag MekCkUpgradeCodec.encode(Map<Upgrade,Integer> map);   // key = type.getRawName()
Map<Upgrade,Integer> MekCkUpgradeCodec.decode(CompoundTag tag, UpgradeCapResolver caps);
```

注入点（`@Redirect`，两个，指向 `TileComponentUpgrade` 的实例方法）：

| 目标 | 方法 | 重定向的调用 |
|---|---|---|
| `mekanism.common.tile.component.TileComponentUpgrade` | `write(CompoundTag)` | `Upgrade.saveMap(Map,CompoundTag)` |
| 同上 | `read(CompoundTag)` | `Upgrade.buildMap(CompoundTag)` |

**用 `@Redirect` 而非 `@Inject` 的理由**：`saveMap` / `buildMap` 是 `Upgrade` 上的 `public static` 方法，**全局影响所有 Mekanism 机器**（含 Mek 自己的、以及任何装了 MekCK 的整合包里 Mek 的机器）。重定向 `TileComponentUpgrade` 的实例调用则**只作用于 MekCK 的 tile**，不碰 Mek 原生机器的存档格式。

**收益**：注入者增删时，旧存档里那一项**原样保留在 NBT 里**（名字键不认识就跳过，但不删），装回来即自动恢复。不再有张冠李戴。

**代价**：一个 Mixin 类（与 §5.6 的注入 Mixin 一起构成 MekCK 的全部 Mixin 足迹，共 2 个类）。

> **备选方案（若不想写 Mixin）**：完全不用 `TileComponentUpgrade`，把 `MekCkUpgradeTracker` 泛化成 `Map<Upgrade,Integer>` 自管存储。代价是失去 Mek 的升级 GUI、槽位、读条动画，`GuiConfigurableTile` 也不再自动排升级 tab。**不推荐**——用户要的正是「Mek 的机器」。

### 5.5 MekCK 自有升级卡（两张，不是一张）

⚠️ **MekCK 现在依赖 `mekanism_extras` 的是两个物品，不是一个。** 只补存储卡会把创造升级悬空。

| 卡片 | 现用物品 | 特性规模 |
|---|---|---|
| **存储卡** | `mekanism_extras:upgrade_stack` | 线程倍增，公式在各方块实体里 |
| **创造卡** | `mekanism_extras:upgrade_creative` | **MekCK 自己的完整特性**：启动时把 49 个随机食物 tag 写进 `datapacks/mekck_creative_upgrade`（`MekckConfig:379-388` 有 3 个配置项 + 聊天提示），不是"借个物品当开关" |

**两张都改由 MekCK 自注册**，否则 §2.4 的 bug 只修一半。

#### 存储卡（`mekck:upgrade_storage`）

- **语义**：提升并行线程数与缓冲容量。与 Mek Extras 的 `STACK` 定位相近，但由 MekCK 自有，语义明确。
- **物品实现**：`implements IUpgradeItem`，`getUpgradeType()` 返回注入的 `STORAGE` 常量。**槽位、读条、安装、GUI 全部由 Mek 原生组件处理，本卡不需要任何额外 Mixin**（§2.3 已实测该链路）。
- **枚举注入**：唯一的 Mixin，注入 `STORAGE` 常量；`maxStack` 起点对齐现有 STACK 的 6。注入点为 `Upgrade.<clinit>` 的 `@Inject(at = @At("TAIL"))`，末尾 `UPGRADES = $VALUES` 重同步。
- **线程模型**：`activeSlots = min(base << min(count, capOf), maxParallel)`，沿用 `CookingFactoryBlockEntity.java:1658-1667` 的现有公式，把 `getStackUpgradeCount()` 换成 `getUpgrades(STORAGE)`。
- **兼容期**：旧存档里的 `STACK` 数量迁移到 `STORAGE`（见 §7.2）

#### 创造卡（`mekck:upgrade_creative`）

- **语义不变**：随机化本局 49 种可用食物
- `maxStack = 1`（与现状一致）
- ⚠️ **常量命名冲突**：Mek Extras 已注入了一个名为 `CREATIVE` 的常量。若 MekCK 也注入 `CREATIVE`，两个 mod 会产生**两个同名但不同实例**的枚举常量——`valueOf("CREATIVE")` 的结果取决于加载顺序，而 §5.4 的名字键持久化恰好按 `getRawName()` 存，**两者会被当成同一张卡**。
  **解法：MekCK 的常量改名**（如 `RANDOMIZE` / `FOOD_RANDOMIZE`），与 Mek Extras 的 `CREATIVE` 区分开。物品 id 仍可保留 `mekck:upgrade_creative` 以维持玩家熟悉度——**物品 id 与枚举常量名是两回事**。

#### 两张卡的共同交付

- **物品注册**：`ItemDeferredRegister`
- **配方 / 模型 / 贴图 / lang**：新建。贴图参照 Mek Extras 的规格**自绘，不复制其文件**
- **同时移除** `UpgradeHelper` / `MekCkUpgradeType` 对 `mekanism_extras:*` 的两处硬编码
- **两条兼容期迁移**：旧存档的 `STACK` → `STORAGE`、`CREATIVE` → `RANDOMIZE`（见 §7.2）

**这一步修掉 §2.4 的 bug**：不再依赖未声明的第三方 mod。

### 5.6 `MekCkUpgradeTracker` 的去留

已决定用 Mek 的 `TileComponentUpgrade` 作运行时容器（内部即 `Map<Upgrade,Integer>`，20 tick 读条、槽位、GUI tab 全部原生），因此：

- **`MekCkUpgradeTracker` 无条件退役（删除）**——不再有任何调用场景
- 它的 20 tick 读条语义由 Mek 的组件原生提供，不需要保留副本
- `util/UpgradeInstallHandler.java` 的全局 `RightClickBlock` 监听同样退役——Mek 的 `tickServer()` 走的是自己的安装路径

> 注意：这里**不是**「Mek 的序列化对我们有利」。Mek 的组件在运行时（tick / 槽位 / GUI）完全可用且优于自研；**只有持久化那一层**因为 ordinal 索引不可用，由 §5.4 的 Mixin 换成名字键。组件照用，编解码换掉。

### 5.7 `mods.toml` 修正

两张卡落地后，MekCK **不再引用任何 `mekanism_extras` 物品**：

- 从代码里彻底移除相关引用
- `CREDITS.txt:16` 的「Mekanism Extras 工厂安装器（绝对 ~ 悖论无限）」条目**保留但改写**—— MekCK 确实用它的工厂安装器物品做顶层升级安装（见 README「Integration」），那是另一条关系，与升级卡无关
- `mods.toml` **不**加 `mekanism_extras` 声明：升级卡解耦后，Mek Extras 缺席不再导致任何功能静默失效，它回归为真正的纯可选联动

## 6. 阶段 2 详细设计：烹饪工厂样板

选烹饪工厂因为它是**逻辑最复杂、依赖最全**的一个（FD `CookingPotRecipe` 匹配 + 流体输入 + 订单持久化 + AE2 接入 + 热集成），跑通它其余都是重复劳动。

| 步骤 | 内容 |
|---|---|
| 1 | `CookingFactoryBlock` 改继承 `BlockTile`，注册迁到 `BlockDeferredRegister` |
| 2 | `CookingFactoryBlockEntity` 改继承 `MekCkMachineTile`；2,099 行拆成执行器 + 状态 + 端口 |
| 3 | 能量换 `MachineEnergyContainer`；热保留 `MekCkHeatComponent`（已是 Mek 原生，不动） |
| 4 | 升级换 Mek 存储 + `MekCkUpgrades` 裁定 |
| 5 | `CookingFactoryMenu` 改 `MekanismTileContainer`；Screen 改 `GuiConfigurableTile` |
| 6 | `INetworkPullable` → `MekCkPorts` |
| 7 | NBT 迁移（见 §7） |
| 8 | 删除自研 `EnergyStorage` 字段与全部 `getCapability` 分支 |

**回归清单**（迁移前后逐项对照）：并行数、每 tick 产出、能耗曲线、订单持久化、流体输入、AE2 下单与回写、GUI 全部 tab 位置、方块朝向、掉落物、红石模式、安全模式。

## 7. 命名空间收编与存档迁移

### 7.1 收编方案

`mekck:` 命名空间**不变**，Mek 原生 tile 直接接管现有注册 ID。**世界里的方块位置纹丝不动**——变的只是方块实体类。

`mekckfactory:` **整体删除**。它存在的唯一理由是让雏形和正式实现共存（`MekCkFactoryRegistration.java:29-33` 写得很清楚），正式化后没有存在理由；留着玩家能拿到两套同名不同行为的方块。

删除范围：`src/main/java/cn/ism/mekck/factory/`（8 类，内容并入新分层）、`src/main/resources/assets/mekckfactory/`（约 84 个 blockstate）、对应 lang 条目。

### 7.2 NBT 迁移

Forge 重新加载 BE 时会把旧 NBT 喂给新 `load()`。在 `load()` 里做**显式版本判断**：

```java
if (!tag.contains("MekCkNative", Tag.TAG_BYTE)) {
    // 旧格式 → 逐字段重映射，写入新键
    // 写入 MekCkNative=1 标记
}
```

**必须一次性全量迁移，不要留「兼容读取旧键」的分支**——两套格式并存会让后续每个 bug 都要查两遍。

迁移正确性由测试保证：扩展现有 `TestNbtPersistenceInvariants`，它已建立「save/load 键必须对称」「同一持久化方法内不得有重复键」的检查模式。

**升级数据的兼容期迁移（阶段 1 就要做，早于机器迁移）**：

旧存档里升级数量存在各方块实体自己的 NBT 键下（`DATA_STACK_UPGRADE` 等 `ContainerData` 路径 + `MekCkUpgradeTracker.save()` 写的 `Installed`）。迁到 Mek 组件 + 名字键后：

| 旧来源 | 新目标 | 说明 |
|---|---|---|
| `MekCkUpgradeTracker` 的 `STACK` 计数 | `STORAGE` | MekCK 自有常量，无歧义 |
| `MekCkUpgradeTracker` 的 `CREATIVE` 计数 | `RANDOMIZE` | **必须改名**，理由见 §5.5 |
| `SPEED` / `ENERGY` / `GAS` 计数 | 同名常量 | 名字键下天然对齐 |
| Mek Extras 注入的类型（若装） | 同名 | 装则恢复；不装则该项**原样留在 NBT 里**（§5.4） |

**关键**：这一步在阶段 1 就该做完并测试，不要拖到阶段 2。阶段 1 之后 `MekCkUpgradeTracker` 退役（§5.6），数据必须在退役前转移完毕。

## 8. 风险

| # | 风险 | 等级 | 缓解 |
|---|---|---|---|
| 1 | **Mek 的升级持久化按 ordinal + 取模回绕**，注入者增删导致存档静默张冠李戴 | **高** | §5.4 名字键编解码 + Mixin 重定向 `TileComponentUpgrade.read/write`；**已定位到具体方法** |
| 2 | 三方 Mixin 在 `<clinit>` TAIL 执行顺序不可控 → ordinal 分配顺序不可控 | **高** | 名字键持久化使其**对顺序不敏感**；聚合层不假设顺序；每条路径单测 |
| 3 | 同名枚举常量冲突（Mek Extras 的 `CREATIVE` vs MekCK 的） | **高** | §5.5 强制改名 `RANDOMIZE`；物品 id 可保持 `upgrade_creative` 不变 |
| 4 | 烹饪工厂 2,099 行拆分丢失边界条件 | **高** | 回归清单逐项对照；**先补现有行为的特征测试再动刀** |
| 5 | 存档迁移丢数据 | **高** | 一次性全量迁移 + 迁移测试；升级数据迁移在阶段 1 完成（§7.2） |
| 6 | Mixin 注入点失配（Mekanism 升版改了 `Upgrade` 结构） | 中 | `defaultRequire: 1` 让失配**启动即崩**而非静默；Mek 升版时本就需要回归 |
| 7 | `BioreactorBlockEntity` 多方块结构在改基类时破裂 | 中 | 它不在迁移链上，阶段 3 单独处理，保留 `IBoundingBlock` |
| 8 | GUI 改 `GuiConfigurableTile` 后 tab 位置与旧版不一致 | 中 | Mek 排的 tab 坐标与旧的手摆坐标**必然不同**，这是预期变化不是回归 |
| 9 | 30,319 行分批迁移周期长 | 中 | 每阶段独立可发布；阶段 2 完成后游戏是可玩的 |
| 10 | 实机验证成本 | 中 | 本环境无法启动 dev 客户端（见 §9） |

**已排除的风险**（§5.1 实测后降级或消除）：

| 原风险 | 结论 |
|---|---|
| 上限检查位置与预期不符 | **已消除**。`capOf() = min(config, getMax())` 即可，MekckConfig 完整保留，不需要 Mixin |
| 升级卡需要 Mixin 才能工作 | **已消除**。`IUpgradeItem` + `UpgradeInventorySlot.input(listener, supported)` 链路完整，卡片只需实现一个方法 |
| 未知 `Upgrade` 名导致读档抛异常 | **不存在**。Mek 用 ordinal，没有"名"这回事；真实风险是 §8-#1 的回绕，更隐蔽也更严重 |

## 9. 测试策略

**纯 JVM 单测** —— 沿用现有 JUnit 4 基建（`build.gradle:212,217`），当前 73 个测试全绿：

- `MekCkUpgrades`：发现集、`IUpgradeItem` 优先路径、普通物品 fallback、`capOf` 的 `min` 语义
- **名字键编解码**（`MekCkUpgradeCodec`，**本阶段最重要的测试**）：
  - 同一 `Map<Upgrade,Integer>` encode → decode 后完全相等
  - 注入者缺席时：含未知名字的标签 decode **不抛异常、不丢原始 NBT**（该项数据仍在，供装回后恢复）
  - 数量裁剪走 `MekCkUpgrades.capOf()`，**不**走 `type.maxStack`
- **ordinal 回绕的对照测试**：构造一份用 `Upgrade.saveMap` 写的旧标签，断言 `byIndexStatic` 在数组变短后会映射到**错误的类型**——把「为什么不能用 Mek 的序列化」钉成可执行的证据
- **升级数据迁移**：旧 tracker 计数 → 新常量的映射表逐项验证
- 扩展 `TestNbtPersistenceInvariants` 覆盖迁移后的键对称性

**GameTest**（`runGameTestServer`）：

- 机器放置 → 生产 → 停止 → 存档 → 重载 → 进度一致
- 升级安装 20 tick 读条 → 数量正确 → 上限正确（超上限时 `tickServer()` 不推进）
- 侧配 / 红石 / 安全 tab 行为与迁移前一致

**实机手动验证**：

⚠️ **本环境无法启动 dev 客户端**（无显示器 / GPU 上下文）。沿用 OBJ 迁移那次 spec 的做法：编译 + 全量单测 + GameTest 之外的部分**明确标注为未验证**，不冒充已验证。**两个 Mixin（枚举注入 + 持久化重定向）只能在实机验证**——纯 JVM 单测无法覆盖 mixin 应用的正确性。GUI tab 布局、tab 重叠、渲染差异同样只能由你在实机确认。

## 10. 明确不做

- **不升级 MC 版本**（继续 1.20.1 / Forge 47.4.16）
- **不为 Mek Energistics 设计 SPI 或写桥接**。只在 `MekCkPorts` 的方法命名上留门（成本零）
- **不偿还 `mekanism.common.*` 的存量耦合**。589 行 import 里大量内部 API（`MekanismSounds`、`MekanismConfig`、`MekanismUtils`）是存量技术债，本期不动。但**新代码的公共接口只许用 `mekanism.api.*`**——硬约束
- **不改 `MekckConfig` 的配置格式**（仍是 `ForgeConfigSpec`）
- **不重做美术资源**。`mekckfactory` 的贴图本来就是复用 `mekck` 的
- **不实现两张升级卡的完整平衡数值**。先落地机制与物品，数值照搬现状（存储 6 / 创造 1）作为起点

## 11. 交付物清单

### 阶段 1
1. `util/MekCkUpgrades.java` — 聚合层（新建）
2. `util/MekCkUpgradeCodec.java` — **名字键编解码，替代 `Upgrade.saveMap/buildMap`**（新建，§5.4）
3. `mixin/MixinTileComponentUpgradePersistence.java` — 重定向 `read`/`write` 里的编解码调用（新建）
4. `mixin/MixinUpgradeInjection.java` — 注入 `STORAGE` + `RANDOMIZE` 两个常量（新建）
5. `item/MekCkStorageUpgradeItem.java`、`item/MekCkCreativeUpgradeItem.java` — 实现 `IUpgradeItem`（新建）
6. `data/mekck/recipes/upgrade/*.json` + 两张卡的模型/贴图/lang（新建）
7. `util/UpgradeHelper.java`、`client/MekCkUpgradeType.java` — 移除 `mekanism_extras` 硬编码（改现有）
8. `CREDITS.txt` — Mek Extras 条目改写（改现有）
9. `util/MekCkUpgradeTracker.java`、`util/UpgradeInstallHandler.java` — **删除**（见 §5.6）
10. `src/main/resources/mekck.mixins.json` — 挂两个新 Mixin（改现有）
11. `TestMekCkUpgrades`、`TestMekCkUpgradeCodec`、`TestUpgradeOrdinalWraparound`（新建）

> `META-INF/mods.toml` **不需要改**——解耦后不再有未声明的依赖（见 §5.7）。
>
> **MekCK 的 Mixin 足迹到此为止，共 2 个类**（`MixinUpgradeInjection` + `MixinTileComponentUpgradePersistence`），加上已有的 2 个共 4 个。

### 阶段 2
12. `machine/MekCkMachineTile.java` — L2 抽象基类（取代 `factory/TileEntityMekCkFactory.java`）
13. `machine/ports/MekCkPorts.java` + `IMekCkPorted`（取代 `ae2/INetworkPullable.java`）
14. `machine/cooking/CookingFactoryExecutor.java` — L3 执行器（从 2,099 行拆出）
15. `blockentity/CookingFactoryBlockEntity.java` → 重写
16. `menu/CookingFactoryMenu.java`、`client/CookingFactoryScreen.java` → 重写
17. `factory/` 8 类、`assets/mekckfactory/` — 删除

### 阶段 3
18. 其余 6 家族的同构迁移
19. 5 个独立机器，含 `BioreactorBlockEntity` 的多方块保全

## 12. 走过的弯路（留给后人）

- **不要去找 Mekanism 的「附属框架」——它不存在**。Mekanism 组织只有 3 个仓库，
  5 个分支共 14,209 条文件树里搜 `addon` 零命中，`mekanism.wiki` 连 DNS 都不存在。
  时间花在核实这件事上是一次性的，别再花第二次。
- **`mekanism.api.providers.IBlockProvider` 不是注册框架**。它是三方法访问器
  （`getBlock()` / 默认 `getRegistryName()` / 默认 `getTranslationKey()`），
  给 Mek 自己的 datagen 和配方归属用的。别照着它设计注册层。
- **`Mek Energistics` 有官方 SPI，但用不上**。它是 1.21.1/NeoForge，
  和 MekCK 的 1.20.1/Forge 无交集，编译期依赖都挂不上。
  但它的**方法命名值得抄**——`mePatternItemInputs()` 这套名字对齐了，将来桥接是零成本。
- **AE2 15.4.10 里没有 `AECapabilities`**。1.20.1 走 `instanceof IInWorldGridNodeHost`，
  1.21 之后才改成 capability。任何照抄 1.21 AE2 模组代码的尝试都会踩空。
- **MekCK 的 AE2 集成不是残桩**。`MekckAe2.java` 有 2,358 行且已实现 `ICraftingProvider`，
  一开始误以为需要新 SPI 是判断失误。它需要重写纯粹是因为地基（槽位下标）会消失。
- **🔴 别照着别人 Mixin 里的参数名猜 API 名**。Mek Extras 的 `MixinUpgrade` 构造调用里
  参数写作 `maxStack`，我据此在 spec 里写了 `Upgrade.getMaxStack()`，**实际方法是 `getMax()`**。
  `javap` 一条命令的事，不要靠阅读第三方 Mixin 的调用现场去反推被调用方的 API。
- **🔴 `Upgrade` 没有「按名字容错」这回事，因为它根本不存名字**。
  `buildMap` 读的是 `ordinal`，`byIndexStatic` 走 `getByIndexMod` **取模回绕**——
  索引越界不抛异常、不打日志，直接映射到**另一种升级**。
  初稿写的容错契约「未知名字则丢弃」是凭空想象的，真实的失败模式（静默张冠李戴）
  比丢弃严重得多。**涉及枚举的持久化，一定要去看 `getTag` / `buildMap` 的字节码。**
- **`TileEntityMekanism.upgradeComponent` 是 `protected`**，理论上子类可替换，
  但父类构造时已 `new TileComponentUpgrade(this)` 并 `addComponent`，
  事后换字段会让组件列表与字段不一致。走 Mixin 重定向 `read`/`write` 更干净。

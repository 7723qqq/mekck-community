# MekCK 机器体系 Mek 原生化 —— 完整框架设计

- 日期：2026-09-29
- 状态：**设计已批准，待实施**
- 目标工程：`D:\mc\mod\mekck`（MC 1.20.1 / Forge 47.4.16 / Java 17 / Mekanism 10.4.x）
- 规模基线：`blockentity/` 25 文件 / 30,319 行；`client/` GUI ~40 类；`network/` 25 包；`ae2/` 2,358 行

## 1. 目标与判据

把 MekCK 从"**借用 Mekanism 的库**"改造成"**按 Mekanism 的规矩造机器**"。

三条可验证判据，缺一不算完成：

1. **机器 tile 继承 `TileEntityMekanism`**。能量走 `MachineEnergyContainer`，侧配走 `TileComponentConfig`，热走 `MekCkHeatComponent`，槽位走 `IInventorySlot`。全仓库不再有自研 `EnergyStorage` 字段。
2. **升级系统吃下当前已加载的全部 `Upgrade` 常量**。Mek 原生 7 种 + Mek Extras 注入的 3 种 + Mek Energistics 注入的 3 种 + MekCK 自己的，任一注入者缺席都不炸档、不丢数据。
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

### 2.3 `Upgrade` 是写死的枚举，共存是真问题

`mekanism.api.Upgrade` 是 **7 常量 Java 枚举**（SPEED/ENERGY/FILTER/GAS/MUFFLING/ANCHOR/STONE_GENERATOR），`maxStack` 是编译期常量。**1.20.1 没有 data map，也没有 namespaced 升级注册表。**

已确认有**三个** mod 在用 Mixin 往 `<clinit>` TAIL 注入常量：

| mod | 注入内容 | 机制 |
|---|---|---|
| Mek Extras (`lostmyself8`) | `STACK`(max 6) / `IONIC_MEMBRANE` / `CREATIVE` | `@Mutable @Shadow Upgrade[] $VALUES` + `@Invoker` 私有构造 + 重同步 `UPGRADES` |
| Mek Energistics (`beipuo`) | `ME_PATTERN_PROVIDER` / `ME_PASSIVE_CRAFTING` / `ME_OUTPUT_INTERFACE` | `mixin/UpgradeMixin.java:19-51`，同上 |
| MekCK | `STORAGE`（本次新增） | 同上 |

**共存不是假设，是既成事实。** 三个 `<clinit>` TAIL 注入的执行顺序由 mixin config 加载序决定，**不保证**。

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
│     解析升级物品 → Upgrade 常量，跨所有注入者通用
├── int capOf(MachineContext ctx, Upgrade type)
│     MekCK 声明式上限，fallback 到 type.getMaxStack()
└── boolean isSupportedBy(MachineFamily family, Upgrade type)
```

**容错契约（两条，缺一不可）：**

1. **NBT 读入**：存档里的升级名在当前 `Upgrade` 枚举中不存在时（注入者被卸载），**丢弃该项并记 WARN，不抛异常**。玩家把 Mek Extras 装回来，数据自然恢复。
2. **物品反查**：`byItem()` 找不到对应 `Upgrade` 时返回 `Optional.empty()`，调用方按「不是升级物品」处理，**不记 ERROR**（避免每 tick 刷屏）。

**上限裁定**（本次最大的不确定点，见 §5.1）：`MekckConfig` 有 924 行按等级/按机器配置的升级上限，而 Mek 的 `TileComponentUpgrade` 在安装时会卡 `Upgrade.getMaxStack()` 这个编译期常量。两者冲突，**解法取决于上限检查的确切位置——必须先实测，不能猜**。

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
| **1** | 升级体系：`MekCkUpgrades` + 切 Mek 存储 + 上限裁定 + 两张自有升级卡 | 单测绿；机器能装能卸；存档往返无损 | 是（Mixin 需游戏内验证） |
| **2** | 烹饪工厂样板：L0/L1/L2/L3 全链路 | 该机器能开能产，GUI 是 `GuiConfigurableTile`，存档无损 | 是 |
| **3** | 推广到 6 家族 + 5 独立机器 | 逐个回归，对照迁移前后行为清单 | 是 |

**阶段 1 先行的理由**：升级体系是 19 个机器的共同依赖，先定死再铺开；且它是唯一能脱离 Minecraft 启动做单测的部分。

## 5. 阶段 1 详细设计：升级体系

### 5.1 第 0 步：核实 `TileComponentUpgrade`（阻塞项）

**本机没有 Mekanism jar**（`~/.gradle` 不存在，jar 未落在 `libs/`）。必须先跑一次 `./gradlew build` 拉下依赖，然后：

```
javap -p -c <mekanism>/mekanism/common/tile/component/TileComponentUpgrade.class
javap -p -c <mekanism>/mekanism/api/Upgrade.class
```

要确定三件事，**每一条都会改变后续设计**：

| 问题 | 若答案是 A | 若答案是 B |
|---|---|---|
| `getMaxStack()` 在哪被检查？ | 安装路径（`addUpgrades` / 槽位 `canInsert`） | 仅 GUI 展示层 |
| 检查是否可被覆写？ | 需 Mixin 改判定，或退化为自研存储 | 直接用 Mek 组件，无需 Mixin |
| `buildMap(CompoundTag)` 遇到未知名？ | 抛异常 → **必须**加容错层 | 返回 null/跳过 → 容错仍要加，但风险降级 |

**如果答案是「检查只在 GUI 层」**，上限冲突不存在，`MekckConfig` 的按等级配置可以保留，阶段 1 会显著变简单。**不要跳过这一步。**

### 5.2 `MekCkUpgrades` 实现

- `discoverAll()` 在 `FMLCommonSetupEvent`（mod bus，末尾）调用一次并缓存 —— 此时所有 `<clinit>` 已执行
- 结果存**不可变 `LinkedHashSet`**，不用 `EnumSet`（注入的常量不在编译期集合里）
- `byItem()`：遍历 `discoverAll()`，调 `UpgradeUtils.getStack(type, 1)` 比对 `ItemStack.isSameItemSameTags`。**必须缓存**——升级物品判定在 `isItemValid` 里逐 tick 调用
- 单测覆盖：`discoverAll()` 至少含 Mek 7 种；`byItem()` 对空栈/普通物品返回 empty；`capOf()` 在无覆盖时等于 `getMaxStack()`

### 5.3 上限裁定

取决于 §5.1 的结论，两条路：

- **路 A（检查在安装路径）**：写 MekCK 的 Mixin，把上限读取从 `type.getMaxStack()` 重定向到 `MekCkUpgrades.capOf(ctx, type)`。`MekckConfig` 保持权威。
- **路 B（检查不在安装路径）**：不加 Mixin，直接用 Mek 组件；`MekckConfig` 的按等级上限降级为「Mek 上限之下的软约束」，GUI 提示而非硬拦。

**路 A 与 §5.4 的注入 Mixin 共用同一套基础设施**，所以即使走 A，边际成本也低。

### 5.4 MekCK 自有升级卡（两张，不是一张）

⚠️ **MekCK 现在依赖 `mekanism_extras` 的是两个物品，不是一个。** 只补存储卡会把创造升级悬空。

| 卡片 | 现用物品 | 特性规模 |
|---|---|---|
| **存储卡** | `mekanism_extras:upgrade_stack` | 线程倍增，公式在各方块实体里 |
| **创造卡** | `mekanism_extras:upgrade_creative` | **MekCK 自己的完整特性**：启动时把 49 个随机食物 tag 写进 `datapacks/mekck_creative_upgrade`（`MekckConfig:379-388` 有 3 个配置项 + 聊天提示），不是"借个物品当开关" |

**两张都改由 MekCK 自注册**，否则 §2.4 的 bug 只修一半。

#### 存储卡（`mekck:upgrade_storage`）

- **语义**：提升并行线程数与缓冲容量。与 Mek Extras 的 `STACK` 定位相近，但由 MekCK 自有，语义明确。
- **枚举注入**：走 §5.3 建立的 Mixin 基础设施，注入 `STORAGE`，`maxStack` 起点对齐现有 STACK 的 6
- **线程模型**：`activeSlots = base << min(storageUpgrades, configMax)`，沿用现有公式
- **兼容期**：旧存档里的 `STACK` 数量迁移到 `STORAGE`（见 §7.2，一并处理）

#### 创造卡（`mekck:upgrade_creative`）

- **语义不变**：随机化本局 49 种可用食物
- `maxStack = 1`（与现状一致）
- 注入常量名用 `CREATIVE` 还是另起一个（如 `RANDOMIZE`）取决于是否要与 Mek Extras 的 `CREATIVE` 撞名——**§5.1 之后确定**，撞名会让两个 mod 注入出同名常量

#### 两张卡的共同交付

- **物品注册**：`ItemDeferredRegister`
- **配方 / 模型 / 贴图 / lang**：新建。贴图参照 Mek Extras 的规格**自绘，不复制其文件**
- **同时移除** `UpgradeHelper` / `MekCkUpgradeType` 对 `mekanism_extras:*` 的两处硬编码

**这一步修掉 §2.4 的 bug**：不再依赖未声明的第三方 mod。

### 5.5 `MekCkUpgradeTracker` 的去留

已决定用 Mek 的 `TileComponentUpgrade` 作存储（内部即 `Map<Upgrade,Integer>`，按枚举名序列化，注入常量天然生效），因此：

- **§5.3 的路 A 与路 B 都用 Mek 的组件**，`MekCkUpgradeTracker` 在两条路下都失去存在理由 → **退役（删除）**
- 它的 20 tick 读条语义由 Mek 的组件原生提供，不需要保留副本
- `util/UpgradeInstallHandler.java` 的全局 `RightClickBlock` 监听同样退役——`IUpgradeableTile` 走的是 Mek 自己的安装路径

> 这一条在初稿里被写成"按 §5.1 结论决定保留或退役"，是含糊的。已明确为**无条件退役**。

### 5.6 `mods.toml` 修正

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

## 8. 风险

| # | 风险 | 等级 | 缓解 |
|---|---|---|---|
| 1 | `TileComponentUpgrade` 上限检查位置与预期不符 | **高** | §5.1 先核实再动手。阶段 1 阻塞项 |
| 2 | 三方 Mixin 在 `<clinit>` TAIL 执行顺序不可控 | **高** | 聚合层不假设顺序；每条路径单测；存档容错兜底 |
| 3 | 烹饪工厂 2,099 行拆分丢失边界条件 | **高** | 回归清单逐项对照；**先补现有行为的特征测试再动刀** |
| 4 | 存档迁移丢数据 | **高** | 一次性全量迁移 + 迁移测试；上线前手动造存档验证 |
| 5 | `BioreactorBlockEntity` 多方块结构在改基类时破裂 | 中 | 它不在迁移链上，阶段 3 单独处理，保留 `IBoundingBlock` |
| 6 | GUI 改 `GuiConfigurableTile` 后 tab 位置与旧版不一致 | 中 | Mek 排的 tab 坐标与旧的手摆坐标**必然不同**，这是预期变化不是回归 |
| 7 | 30,319 行分批迁移周期长 | 中 | 每阶段独立可发布；阶段 2 完成后游戏是可玩的 |
| 8 | 实机验证成本 | 中 | 本环境无法启动 dev 客户端（见 §9） |

## 9. 测试策略

**纯 JVM 单测** —— 沿用现有 JUnit 4 基建（`build.gradle:212,217`），当前 73 个测试全绿：

- `MekCkUpgrades`：发现集、物品反查缓存、上限 fallback
- **NBT 容错**：构造含未知 `Upgrade` 名的存档标签，断言不抛异常且该项被丢弃
- **存档迁移**：旧格式样本 → 新 tile `load()` → 字段完整且**幂等**（`load` 两次结果相同）
- 扩展 `TestNbtPersistenceInvariants` 覆盖迁移后的键对称性

**GameTest**（`runGameTestServer`）：

- 机器放置 → 生产 → 停止 → 存档 → 重载 → 进度一致
- 升级安装 20 tick 读条 → 数量正确 → 上限正确
- 侧配 / 红石 / 安全 tab 行为与迁移前一致

**实机手动验证**：

⚠️ **本环境无法启动 dev 客户端**（无显示器 / GPU 上下文）。沿用 OBJ 迁移那次 spec 的做法：编译 + 全量单测 + GameTest 之外的部分**明确标注为未验证**，不冒充已验证。GUI tab 布局、tab 重叠、渲染差异只能由你在实机确认。

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
2. `util/MekCkUpgradeCaps.java` — 上限裁定（新建，内容取决于 §5.1）
3. `mixin/MekCkUpgradeInjection.java` — 升级常量注入（新建，注入 `STORAGE` + 创造卡）
4. `item/MekCkStorageUpgradeItem.java`、`item/MekCkCreativeUpgradeItem.java` — 两张自有卡（新建）
5. `data/mekck/recipes/upgrade/*.json` + 两张卡的模型/贴图/lang（新建）
6. `util/UpgradeHelper.java`、`client/MekCkUpgradeType.java` — 移除 `mekanism_extras` 硬编码（改现有）
7. `CREDITS.txt` — Mek Extras 条目改写（改现有）
8. `util/MekCkUpgradeTracker.java`、`util/UpgradeInstallHandler.java` — **删除**（见 §5.5）
9. `TestMekCkUpgrades`、`TestUpgradeNbtTolerance`、`TestNbtMigration`（新建）

> `META-INF/mods.toml` **不需要改**——解耦后不再有未声明的依赖（见 §5.6）。

### 阶段 2
10. `machine/MekCkMachineTile.java` — L2 抽象基类（取代 `factory/TileEntityMekCkFactory.java`）
11. `machine/ports/MekCkPorts.java` + `IMekCkPorted`（取代 `ae2/INetworkPullable.java`）
12. `machine/cooking/CookingFactoryExecutor.java` — L3 执行器（从 2,099 行拆出）
13. `blockentity/CookingFactoryBlockEntity.java` → 重写
14. `menu/CookingFactoryMenu.java`、`client/CookingFactoryScreen.java` → 重写
15. `factory/` 8 类、`assets/mekckfactory/` — 删除

### 阶段 3
16. 其余 6 家族的同构迁移
17. 5 个独立机器，含 `BioreactorBlockEntity` 的多方块保全

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

# 02 · Mek 原生机器内核

> 描述源码：`src/main/java/cn/ism/mekck/machine/`（10 个顶层类 + `cooking/cutting/grill/grinding/plantingcutting/skewering/ports` 子包）。
> 约束文档：[`../2026-09-30-功能实现口径.md`](../2026-09-30-功能实现口径.md) §一/§七/§八（**必须优先遵守，本文不复制其规则**）。

## 一、内核类职责

| 类 | 行 | 职责 |
|---|---|---|
| `MekCkMachineTile` | 2141 | **7 个工厂家族共有的能力**：侧配、能量、槽位方阵、升级、持久化、执行器挂载。抽象 `createExecutor()`。家族特有逻辑一律不写在这里 |
| `MekCkRecipeExecutor` | 67 | **家族特有的配方执行契约**（L3 内容层）：`processCount` / `canProcess` / `process` / `isBusy` / 订单三态 / `save` / `load` |
| `MekCkOrderState` | 252 | 订单状态（配方 id + 数量 + 调味）：统一 3 套 null 约定与 2 套数量下界，收口溢出防护 |
| `MekCkSlot` | 264 | `extends BasicInventorySlot`，`obeyStackLimit=false`（大堆叠）；`SLOT_WINDOW` 共享身份 |
| `MekCkSlotHandler` | 100 | 槽位容器 |
| `MekCkSlotNbt` | 288 | 槽位持久化（新槽序即存档契约） |
| `MekCkBatchPacking` | 124 | 大堆叠下的批量打包 |
| `MekCkFactoryType` | 69 | 本模组自建工艺类型（**不是** Mek 的封闭 `FactoryType`） |
| `MekCkLegacyMachineNbt` | 546 | 旧存档迁移：把旧格式 NBT 搬进 `mekckExecutor` 子标签 |
| `MekCkNetworkPullableTile` | 138 | 无档位单机的网络拉料基类（`GrindingMachineTile` 用它） |
| `MatchedRecipe` | 72 | 配方匹配结果（从 BE 私有内嵌类升格） |
| `grinding/GrindingRecipes` | 314 | **单机与工厂共用一份研磨语义**（去重范式的样板） |

## 二、扩展点（新增一个工厂家族时要落的位置）

1. **继承 `MekCkMachineTile`** 并实现抽象 `createExecutor()`（见 `cooking/CookingFactoryTile` 等 6 个先例）。
2. **家族特有的一切**（配方匹配、批量执行、订单推进）写进 `<family>FactoryExecutor implements MekCkRecipeExecutor`，**不写进 tile**。
3. **档位/工艺类型从方块反查**（`tierFromBlock()` / `typeFromBlock()`），**不设 tile 实例字段**——见 §四。
4. 注册走 `registry/MekCkFactories` 的 Mek 三件套，**构造函数里成组 `register(bus)`**（漏一行 = 运行期「注册表里没有这个 id」，且编译通过）。

## 三、⚠️ 两条不可违反的反射/序列化契约

### 3.1 类名与 `getTier()` 返回类型是阶段 1 的反射桥接契约

- `MixinTileComponentUpgradePersistence.mekck$tier()` 按**类名** `"cn.ism.mekck.machine.MekCkMachineTile"` 沿类链查找，再反射调 `getTier()` 并**强转成 `CuttingMachineFactoryTier`**。
- 两者同时对上才成立：
  - 类名对不上 → 返回 `null` → 存储卡读档被 `isSupportedBy(STORAGE, null)` **静默判 false，无任何日志**；
  - 返回类型对不上 → `ClassCastException`，而该 catch 只接 `ReflectiveOperationException`，**会冒到读档路径炸服**。
- ⇒ **类名与 `getTier()` 签名一个字符都不许改。**（源码出处：`MekCkMachineTile.java:93-120`）

### 3.2 订单成员名是 AE2 运行时反射契约

`MekckAe2` 用 `Reflect.method(machine.getClass(), "setOrder"|"getOrderRecipeId"|"getOrderQuantity", …)` 取用 —— 接收者是**表达式**，编译期与测试都抓不到改名。**永不重命名**，详见 `03-ae2.md` §三。

## 四、⚠️ 构造期顺序陷阱（拆分类时最容易踩）

`TileEntityMekanism` 构造器**内部**依次回调
`presetVariables()` → `getInitialEnergyContainers(listener)` → `getInitialInventory(listener)` → `new TileComponentUpgrade(this)`
（实测偏移 118 / 297 / 331 / 457）。这一切发生在 `super(...)` 返回**之前**，此刻子类的**实例字段初始化器一个都还没跑**。已实测踩过的两个 NPE：

```
NPE: Cannot read field "processes" because "this.tier" is null
NPE: Cannot invoke "java.util.List.add(Object)" because "target" is null
```

两条铁律：

1. `getInitial*` 要读的字段**不能写成字段初始化器**，必须在对应 `getInitial*` 里赋值（或从 `blockProvider` 反查）。
2. `getInitialInventory` 里的槽位列表必须**在方法内 `new`**，不能写成 `= new ArrayList<>()` 字段初始化器。

⇒ **拆 `MekCkMachineTile` 时，凡与 `getInitial*` 相关的字段/赋值必须原地不动**，不得移入伴生类（重构批次 10 的铁律）。

## 五、拆分设计（批次 10，**需实机验证**）

`MekCkMachineTile` 被 6 家族 × 12 档（72 方块）共用，改动风险最高。只抽**不触碰构造时序**的部分：

| 伴生类（暂名） | 成员区间 | 职责 | 风险 |
|---|---|---|---|
| `MekCkContainerSync` | 903–1047 | 容器同步 + 订单同步面 | 中 |
| `MekCkSorting` | 1715–1858 | 输入排序 | 低 |
| `MekCkProgress` | 1858–1917 | 进度比计算 | 低 |
| `MekCkMachineNbt` | 1917–2270 | NBT 存档/迁移/持久槽/旧升级安装 | 中 |
| **不抽**：`workCycle` / 热 / 随机化升级（1047–1417） | — | 与构造时序和 tick 状态强耦合 | — |

**伴生类不持状态**，一律 `tile.xxx()` 取；`getInitial*` 相关字段原地保留。

## 六、验收与护栏

- `./gradlew --offline test` 全绿 + 编译通过；批次 10 额外需**实机验证**（放置→GUI→投料→加工→升级卡→拆放→重启）。
- 预计变红的护栏：`machine/TestFactoryMigrationParity`、`TestFactoryGuiSyncSurface`、`TestMekCkPerLaneProgress`、`TestMekCkPersistedSlotCoverage`、`TestMekCkSlot`、`TestMekCkSlotNbt`、`TestRandomizeUpgradeBranches`、`TestFactoryStorageOnlyStart`、`block/TestFactoryLootTableSustainData`。
- 登记表同步收缩（`MENUS` / `POWER_SLOT_TARGET_*`）时**必须注释 `N → N-1 + 原因`**。

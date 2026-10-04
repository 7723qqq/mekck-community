# SimpleMachine（简单机器族，17 个方块）迁移契约

**迁移阶段：未迁**（仍是普通 `BlockEntity`；**一 BE 覆盖 17 个注册方块**，迁移工作量最大）

## 一、定位

| 项 | 值 | 出处 |
|---|---|---|
| BE | `blockentity/SimpleMachineBlockEntity.java`（2582 行，本目录最大） | — |
| 基类/接口 | `extends BlockEntity implements MenuProvider, IRedstoneControllable, IMekanismHeatHandler, cn.ism.mekck.ae2.INetworkPullable, IHasDumpButton` | `:60` |
| 注册 | `registry/MekCkLegacyMachines` `SIMPLE_MACHINE_BLOCK_ENTITY`（一个 BE 类型挂 17 个方块） | `:177-183` |
| Menu | `menu/SimpleMachineMenu.java`（`ISideConfigurableMenu` + `IUpgradeMenu`） | `:29` |
| Screen | `client/SimpleMachineScreen.java`（自研侧配 + 升级窗 + `MekCkTabElement`） | — |

### 覆盖的 17 个方块（`MachineKind`，`MachineKind.java:9-42`）

`sushi_maker`、`average_slicer`、`rice_ball_maker`、`curd_maker`、`dehydrator`、`fermenter`、`steamer`、`winery`、`juicer`、`bakery_oven`、`stove`、`cocktail_shaker`、`blender`、`tea_brewer`、`smart_extractor`、`beverage_blender`、`packaging_station`。

## 二、槽序（存档契约）

`INPUT_COUNT=5`、`OUTPUT_SLOT=5`、`SLOT_SPEED_UPGRADE=6`、`SLOT_ENERGY_UPGRADE=7`、`SLOT_CREATIVE_UPGRADE=8`、`SLOT_POWER=9`、`EXT_INPUT_START=10`、`EXT_INPUT_COUNT=4`、`TOTAL_SLOTS=14`。（`:83-114`）

### winery（智能陈酿机）专用槽（复用扩展槽，旧存档天然兼容）

| 常量 | 值 | 语义 |
|---|---|---|
| `JUICE_SLOT` | 10（`EXT_INPUT_START`） | 果汁格（复用空闲扩展槽） |
| `RETURN_SLOT` | 11 | 空桶返还（OutputSlot 语义） |
| `FLUID_ITEM_SLOT` | 12 | **已废弃**（2026-09-24 GUI 验收裁定，背景只有 6 格放不下第 7 格；索引保留以兼容存档，不再渲染） |
| `WINERY_CARRIER_SLOT` | 3 | 载重瓶/酒瓶槽 |
| `WINERY_DEPRECATED_SLOT` | 4 | winery 弃用的第 5 通用输入槽 |

> `blender`（搅拌机）启用扩展输入：9 输入 = `0..4` + `10..13`；`INetworkPullable.getExtraInputSlots()` 正是为这种「两段不连续」而设。

## 三、掉落

- **无战利品表**。`getDrops → List.of()`；`SimpleMachineBlock.onRemove` → `saveToItem` + `dropItemStack`（`block/SimpleMachineBlock.java:134-148`）；`SimpleMachineBlockEntity.saveToItem`（`:2779-2831`）。
- ⇒ **迁移时 17 个方块都要补战利品表**（口径 §4.1）。

## 四、NBT 根键

`saveAdditional` `:2652-2700`、`saveToItem` `:2779-2831`：

`Items`、`Energy`、`Progress`、`SpeedUpgrade`、`EnergyUpgrade`、`CreativeUpgrade`、`HeatCapacitor`（条件）、`CurrentRecipeId`（条件）、`MekckJuiceLevel`/`MekckJuiceType`（winery）、`MekckTavernBatch`/`MekckTavernFault`（winery）、`Redstone`（**注意：红石键是 `Redstone`，非 `RedstoneControl`**）、`SideConfig`、`FluidSideConfig`、`OrderRecipeId`/`OrderQuantity`/`OrderCompleted`、`MeOrderEnabled`、`CustomName`、流体罐键（`fluids.writeTanks`）。

**不用 `MekCkLegacyMachineNbt`，无原生标记。**

> ⚠️ **NBT 键名是存档契约，逐字不动**；`Redstone` 与其它机器的 `RedstoneControl` 不一致是既有事实，迁移时**不要**顺手统一（会让旧存档读不到）。

## 五、已抽取的伴生类（既有拆分成果）

| 伴生类 | 抽出内容 |
|---|---|
| `blockentity/SimpleMachineFluids` | 两个罐 + 三组 capability + 侧配 + `AutoFluidIO` + 3 个 `IFluidHandler` + 罐 NBT（键名原样） |
| `blockentity/SimpleMachineRecipes` | 38 个 `matchXxx`/`absorb*`/`complete` + 28 个仅内部调用的辅助 + `DrainProbe`（1951 行） |
| `machine/MatchedRecipe` | 配方匹配结果（原私有内嵌类升格） |

BE 行数演进：**4841 → 4650 → 2783 → 2582**。后续拆分设计见 [`../05-god-class-decomposition.md`](../05-god-class-decomposition.md) §一。

## 六、迁移要点

1. **17 个方块 = 17 张战利品表**，漏一张就一处破坏后不掉落。
2. `MachineKind` 分派逻辑（配方按 kind 走不同 `RecipeType`）需整体保留。
3. winery 的果汁/酒馆批次（`MekckTavernBatch`）语义复杂，属最难迁移的部分。
4. 热容改原生（`getInitialHeatCapacitors`，构造期陷阱）。
5. 该屏有自研侧配 + 升级窗 + tab，随迁移删除。
6. 关联护栏（部分会随迁移改红）：`blockentity/TestNbtWriteReadSymmetry`、`TestNbtPersistenceInvariants`、`TestSimpleMachineEnergyLoad`、`TestTavernBrewBatch`、`TestTavernBarrelPlan`、`TestTankNbtSymmetry`、`machine/TestOrderQuantityLowerBound`、`TestClientValueSync`。

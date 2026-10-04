# SmartCookingPot（智能厨锅）迁移契约

**迁移阶段：未迁**（仍是普通 `BlockEntity`）

## 一、定位

| 项 | 值 | 出处 |
|---|---|---|
| BE | `blockentity/SmartCookingPotBlockEntity.java`（1567 行） | — |
| 基类/接口 | `extends BlockEntity implements MenuProvider, IRedstoneControllable, IMekanismHeatHandler, cn.ism.mekck.ae2.INetworkPullable` | `:75` |
| 多方块 | 否 | — |
| 注册 | 方块 `mekck:smart_cooking_pot`、BE `smart_cooking_pot` | `registry/MekCkStandaloneMachines` `:267` / `:272` |
| Menu | `menu/SmartCookingPotMenu.java`（`ISideConfigurableMenu` + `IUpgradeMenu`） | `:33` |
| Screen | `client/SmartCookingPotScreen.java`（自研侧配 + 升级窗 + `MekCkTabElement`） | — |

## 二、槽序（存档契约）

`INPUT_SLOT_START=0`、`INPUT_SLOT_COUNT=6`、`INPUT_SLOT_END=5`、`OUTPUT_SLOT=6`、`RETURN_SLOT=7`、`SLOT_SPEED_UPGRADE=8`、`SLOT_ENERGY_UPGRADE=9`、`STORAGE_SLOT_START=10`、`STORAGE_SLOT_COUNT=81`、`SLOT_CREATIVE_UPGRADE=91`、`SLOT_POWER=92`、`TOTAL_SLOTS=93`、`FLUID_TANK_COUNT=3`。（`:92-104,111`）

## 三、掉落

- **无战利品表**。`SmartCookingPotBlock.java:126-139`（`onRemove` → `dropContents`；`getDrops → List.of()`）。
- ⇒ 迁移时补战利品表。

## 四、NBT 根键

`HeatCapacitor`、`Items`、`Energy`、`Progress`、`FluidTanks`、`SideConfig`、`RedstoneControl`、`RedstonePowered`、`OrderRecipeId`、`MeOrderEnabled`、`OrderQuantity`、`OrderCompleted`、`CustomName`（`:1469-1494`）。
**load 兼容旧键** `FluidAmount`/`Fluid`（`:1518-1522`）——迁移时须保留旧键读取。
**不用 `MekCkLegacyMachineNbt`，无原生标记。**

## 五、迁移要点

1. **配方匹配/回溯/流体/消耗/产出（`564-1074`）可抽 `CookingPotRecipes`**，若与烹饪工厂（已迁）可共用则收益最大（参照 `GrindingRecipes` 先例）。
2. 流体容器转换 + `AutoIO` + 侧配（`394-564`）：迁移时走 Mek `TileComponentConfig`/`TileComponentEjector`。
3. 热容改原生（`getInitialHeatCapacitors`，构造期陷阱）。
4. `load` 的旧键兼容（`FluidAmount`/`Fluid`）不得删。
5. 补战利品表 + 迁移器 + 原生标记。
6. 关联护栏：`blockentity/TestKitchenPotGuards`。

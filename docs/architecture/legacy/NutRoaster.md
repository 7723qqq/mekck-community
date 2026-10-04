# NutRoaster（坚果烘焙机）迁移契约

**迁移阶段：未迁**（仍是普通 `BlockEntity`）

## 一、定位

| 项 | 值 | 出处 |
|---|---|---|
| BE | `blockentity/NutRoasterBlockEntity.java`（915 行） | — |
| 基类/接口 | `extends BlockEntity implements MenuProvider, IRedstoneControllable, IMekanismHeatHandler, cn.ism.mekck.ae2.INetworkPullable` | `:60` |
| 多方块 | 否 | — |
| 注册 | 方块 `mekck:nut_roaster`、BE `nut_roaster` | `registry/MekCkStandaloneMachines` `:236` / `:242` |
| Menu | `menu/NutRoasterMenu.java`（`ISideConfigurableMenu` + `IUpgradeMenu`） | `:27` |
| Screen | `client/NutRoasterScreen.java`（自研侧配 + 升级窗 + `MekCkTabElement`） | — |

## 二、槽序（存档契约）

`INPUT_SLOT=0`、`OUTPUT_SLOT=1`、`SLOT_SPEED_UPGRADE=2`、`SLOT_ENERGY_UPGRADE=3`、`SLOT_CREATIVE_UPGRADE=4`、`SLOT_POWER=5`、`TOTAL_SLOTS=6`。（`:77-83`）

## 三、掉落

- **无战利品表**。`NutRoasterBlock.java:108`（`onRemove` → `dropContents`）。
- ⇒ 迁移时补战利品表。

## 四、NBT 根键

`HeatCapacitor`、`Items`、`Energy`、`Progress`、`OrderRecipeId`、`OrderQuantity`、`OrderCompleted`、`MeOrderEnabled`、`AttackTimer`、`TargetType`、`Radius`、`SideConfig`、`RedstoneControl`、`RedstonePowered`、`CustomName`、`BuffOwnerPos`（`:864-887`）。**不用 `MekCkLegacyMachineNbt`，无原生标记。**

## 五、迁移要点（**推荐作为首批**）

- 无多方块、无流体、无自有配方类型的复杂联动，是 11 台中**最接近烧烤架（已迁模板）**的一台：6 槽、单输入单输出、热容单段、带 `TargetType`/`Radius`。
- 热容改原生（`getInitialHeatCapacitors`，构造期陷阱）。
- 补战利品表 + 迁移器 + 原生标记。
- 该屏有自研侧配 + 升级窗 + tab，随迁移删除。
- 相关：`recipe/NutRoastingRecipe`、`util/RecipeInputMatcher`。

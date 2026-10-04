# Bioreactor（生物反应器）迁移契约

**迁移阶段：未迁**（仍是普通 `BlockEntity`）

## 一、定位

| 项 | 值 | 出处 |
|---|---|---|
| BE | `blockentity/BioreactorBlockEntity.java`（626 行） | — |
| 基类/接口 | `extends BlockEntity implements MenuProvider, IBoundingBlock, cn.ism.mekck.ae2.INetworkPullable`；另实现 `IComparatorSupport`/`IUpgradeTile` 同名方法 | `:70`、`:649-673` |
| 多方块 | **是**（`IBoundingBlock`） | `:627-647` |
| 注册 | 方块 `mekck:bioreactor`、绑定块 `mekck:bioreactor_bounding`、BE `bioreactor` | `registry/MekCkStandaloneMachines` `:250` / `:260` / `:255` |
| Menu | `menu/BioreactorMenu.java`（`extends AbstractContainerMenu`；**不实现自研接口**） | `:34` |
| Screen | `client/BioreactorScreen.java`（`extends GuiMekanism`，自绘 `drawForegroundText` + 能源条，**无自研侧配/升级/tab**） | — |

## 二、槽序（存档契约）

`INPUT_SLOT_COUNT=16`、`POWER_SLOT=16`、`TANK_SLOT=17`、`TOTAL_SLOTS=18`；**无升级槽**。（`:72-75`）

## 三、掉落

- **无战利品表**。`getDrops → List.of()`，改由 `BioreactorBlock.onRemove` → `saveToItem` + `dropItemStack`（`block/BioreactorBlock.java:126-150`）。
- ⇒ 迁移时必须补战利品表（口径 §4.1），否则破坏后什么都不掉。

## 四、NBT 根键

`Items`、`Energy`、`FluidTank`（`:579-609`）。**不用 `MekCkLegacyMachineNbt`，无原生标记。**

## 五、迁移要点

1. 多方块（`IBoundingBlock`）——Mek 无直接对应，需保留 `BioreactorBoundingBlock` 与绑定逻辑；迁移难度**高于**普通单机。
2. 补战利品表（含绑定块）。
3. 补迁移器调用 + 原生标记（口径 §4.2）。
4. 该屏**无自研侧配/升级/tab**，迁移时界面改动最小。

# CentralKitchen（中央厨房）迁移契约

**迁移阶段：未迁**（仍是普通 `BlockEntity`）

## 一、定位

| 项 | 值 | 出处 |
|---|---|---|
| BE | `blockentity/CentralKitchenBlockEntity.java`（1732 行） | — |
| 基类/接口 | `extends BlockEntity implements MenuProvider, cn.ism.mekck.ae2.INetworkPullable` | `:34-35` |
| 多方块 | 否 | — |
| 注册 | 方块 `mekck:central_kitchen`、BE `central_kitchen` | `registry/MekCkStandaloneMachines` `:150` / `:160` |
| Menu | `menu/CentralKitchenMenu.java`（`extends AbstractContainerMenu`；**仅** `ISideConfigurableMenu`） | `:29-30` |
| Screen | `client/CentralKitchenScreen.java`（自研侧配 + `MekCkTabElement`） | — |

## 二、槽序（存档契约）

`MODULE_SLOTS=20`、`STORAGE_SLOTS=300`、`OUTPUT_SLOTS=30`、`MODULE_START=0`、`STORAGE_START=20`、`OUTPUT_START=320`、`TOTAL_SLOTS=321`、`SANDWICH_SAMPLE_SLOT=320`；**无升级/能源槽**。（`:41-53,248`）

> ⚠️ `quickMoveStack` 的槽数在历史提交 `1448ec9` 从 83 修到 84（三明治样品被 shift-click 搬空），护栏 `menu/TestMenuQuickMoveSlotRanges` 与 `menu/TestKitchenStorageBrowserSync` 钉住此面。

## 三、掉落

- **无战利品表**。`CentralKitchenBlock.onRemove` → `dropContents()`（`block/CentralKitchenBlock.java:96-112`）。
- ⇒ 迁移时补战利品表。

## 四、NBT 根键

`Items`、`Energy`、`MeOrderEnabled`、`Filters`、`FluidTanks`、`GasTank`、`ItemSideConfig`、`FluidSideConfig`、`GasSideConfig`、`AutoMode`、`Orders`、`NextOrderId`、`Threads`、`HeatSide`、`ColdSide`（`:1497-1579`）。**不用 `MekCkLegacyMachineNbt`，无原生标记。**

> ⚠️ 存储浏览器（搜索/排序/滚动）的 S2C 回传是独立链路（`network/KitchenStorageSyncPacket`，包 id 29），迁移时**不得丢弃**：`refreshDisplay` 有客户端提前返回、`StorageSlot.getItem()` 分端读取、快照只推可见一页 54 格。

## 五、迁移要点

1. 存储区 300 格 + 模块 20 + 输出 30 = 321 槽，**槽序即存档契约**，迁移器必须逐键对齐。
2. 自动模式/线程（`:846-1136`）**线程所有权留在 BE**。
3. 补战利品表 + 迁移器 + 原生标记。
4. 该屏有自研侧配与 `MekCkTabElement`，随迁移删除。
5. 关联护栏：`blockentity/TestCentralKitchenGuards`、`TestCentralKitchenThreadPersistence`、`menu/TestKitchenStorageBrowserSync`、`kitchen/TestKitchen*`（3）。

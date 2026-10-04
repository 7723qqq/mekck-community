# SkeweringMachine（智能穿串机）迁移契约

**迁移阶段：未迁**（仍是 `MekCkLegacyMachine` 子类 —— **11 台中唯一已有共享基类的**，迁移改动最小）

## 一、定位

| 项 | 值 | 出处 |
|---|---|---|
| BE | `blockentity/SkeweringMachineBlockEntity.java`（1099 行） | — |
| 基类/接口 | **`extends MekCkLegacyMachine`** implements `MenuProvider, cn.ism.mekck.ae2.INetworkPullable`（`IRedstoneControllable` 由基类继承） | `:54` |
| 多方块 | 否 | — |
| 注册 | 方块 `mekck:smart_skewering_machine`、BE `smart_skewering_machine` | `registry/MekCkStandaloneMachines` `:281` / `:286` |
| Menu | `menu/SkeweringMachineMenu.java`（`ISideConfigurableMenu` + `IUpgradeMenu`） | `:29` |
| Screen | `client/SkeweringMachineScreen.java`（**侧配为自研直发 `SideConfigPacket`**（非 `GuiMekCkSideConfiguration`）+ 升级窗 + `MekCkTabElement`） | `:404` |

## 二、槽序（存档契约）

`INPUT_SLOT_START=0`、`INPUT_SLOT_COUNT=3`、`OUTPUT_SLOT=3`、`RETURN_SLOT=4`、`SLOT_SPEED_UPGRADE=5`、`SLOT_ENERGY_UPGRADE=6`、`STORAGE_SLOT_START=7`、`STORAGE_SLOT_COUNT=81`、`SLOT_CREATIVE_UPGRADE=88`、`TOTAL_SLOTS=90`。（`:55-66`）

## 三、掉落

- **无战利品表**。`SkeweringMachineBlock.java:107-120`（`onRemove` → `dropContents`；`getDrops → List.of()`）。
- ⇒ 迁移时补战利品表。

## 四、NBT 根键

自有键（`saveAdditional` `:1030-1054`）：`Items`、`Progress`、`SideConfig`、`OrderRecipeId`、`MeOrderEnabled`、`OrderQuantity`、`OrderCompleted`、`CustomName`。
公共键由基类 `MekCkLegacyMachine.saveAdditional` 统一写：`Energy`、`RedstoneControl`、`RedstonePowered`（`:140-147`）。
**不用 `MekCkLegacyMachineNbt`，无原生标记。**

## 五、迁移要点

1. **已有共享基类** `MekCkLegacyMachine`（含能量/红石/NBT 公共部分），迁移时先迁基类或其公共部分，风险最低。
2. 菜单中「当前订单 x/y」的同步：历史上曾缺失同步通道，已由 `addContainerTrackers` 的 4 条 `SyncableInt` 修复（订单三件套 + 家族位图）；迁移时不得回退。
3. 81 格存储区 + 3 输入 + 返回槽，槽序即存档契约。
4. 补战利品表 + 迁移器 + 原生标记。
5. 该屏侧配**不是** `GuiMekCkSideConfiguration` 而是直接发 `SideConfigPacket`，迁移时一并收敛到 Mek `TileComponentConfig`。
6. 关联护栏：`machine/skewering/TestSkeweringFactoryGuards`、`recipe/TestSkeweringToolBatchArithmetic`、`machine/TestSkeweringCustomOrderRoundTrip`、`TestCuttingMachineUpgradeTypeGate`（若涉及）。

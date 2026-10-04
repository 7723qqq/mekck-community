# SandwichAssembler（三明治组装机）迁移契约

**迁移阶段：未迁**（仍是普通 `BlockEntity`）

## 一、定位

| 项 | 值 | 出处 |
|---|---|---|
| BE | `blockentity/SandwichAssemblerBlockEntity.java`（739 行） | — |
| 基类/接口 | `extends BlockEntity implements MenuProvider, cn.ism.mekck.ae2.INetworkPullable`（**未**实现 `IRedstoneControllable`） | `:44-45` |
| 多方块 | 否 | — |
| 注册 | 方块 `mekck:sandwich_assembler`、BE `sandwich_assembler` | `registry/MekCkStandaloneMachines` `:169` / `:175` |
| Menu | `menu/SandwichAssemblerMenu.java`（**仅** `ISideConfigurableMenu`） | `:23-24` |
| Screen | `client/SandwichAssemblerScreen.java`（自研侧配 + `MekCkTabElement`；**无**升级窗） | — |

## 二、槽序（存档契约）

`MAX_LAYERS=32`、`ORDERED_START=0`、`ORDERED_SLOTS=32`、`SAMPLE_SLOT=32`、`MATERIAL_START=33`、`MATERIAL_SLOTS=27`、`RETURN_START=60`、`RETURN_SLOTS=3`、`OUTPUT_SLOT=63`、`SLOT_SPEED_UPGRADE=64`、`SLOT_ENERGY_UPGRADE=65`、`SLOT_CREATIVE_UPGRADE=66`、`SLOT_POWER=67`、`TOTAL_SLOTS=68`。（`:50-69`）

## 三、掉落

- **无战利品表**。`SandwichAssemblerBlock.java:104-119`（`onRemove` → `dropContents`）。
- ⇒ 迁移时补战利品表。

## 四、NBT 根键

`Items`、`Energy`、`SequencedFluid`、`Mode`、`TargetCount`、`Progress`、`ItemSideConfig`、`MeOrderEnabled`（`:749-760`）。
**无 `RedstoneControl`/`RedstonePowered`**（该类未实现 `IRedstoneControllable`）。**不用 `MekCkLegacyMachineNbt`，无原生标记。**

## 五、迁移要点

1. `WideDataSlot` 在本菜单中用于传 32 位值（历史：低 16 位/高 16 位两槽无损传值）——迁 Mek 后整组作废（改用 `addContainerTrackers`）。
2. 无红石控制，迁移时**不要**顺手加红石（改变行为）。
3. 补战利品表 + 迁移器 + 原生标记。
4. 该屏有自研侧配 + tab，随迁移删除。
5. 关联：`menu/TestFactoryMenuNullGuards`、`client/TestFactoryScreenNullGuards`（若涉及）。

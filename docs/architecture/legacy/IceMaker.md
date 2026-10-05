# IceMaker（制冰机）迁移契约

**迁移阶段：已迁（2026-10-06）** —— 本文保留为**迁移前契约的快照**（槽序 / NBT 键），
迁移后的落点见下表；迁移当时的动作清单与护栏处置见 `docs/STATUS.md` 本轮 §〇。

| 迁移后 | 位置 |
|---|---|
| 方块实体 | `machine/icemaker/IceMakerTile`（`extends MekCkNetworkPullableTile`） |
| 方块 | `block/IceMakerBlock`（`BlockTile` + `BlockTypeTile.blockTypeFor`） |
| 菜单 / 屏幕 | `menu/IceMakerMenu`（`MekanismTileContainer`）/ `client/IceMakerScreen`（`MekCkContainerScreenBase`） |
| 注册 | `registry/MekCkStandaloneMachines` 的 `ICE_MAKER_{CONTAINERS,BLOCKS,TILES}_REG`（注册名不变） |
| 战利品表 | `data/mekck/loot_tables/blocks/ice_maker.json`（已补） |
| **新槽序** | `[输入 0, 输出 1, 创造升级 2, 冷萃①~⑤ 3..7, 能源 8]`（速度/能量卡改由 `TileComponentUpgrade` 持有） |
| 旧存档 | **不迁**（用户口径：模组尚未正式发布，NBT 键可以改） |
| 冷萃读条器 | 与 `installedColdBrew` **成对落盘**；护栏见 `machine/icemaker/TestIceMakerColdBrewPersistence`（含变异测试记录） |
| 保留的活分支 | `MekckAe2` 四条 + `IceAttackConfigPacket` / `UpgradeUninstallPacket` / `UpgradeInstallHandler` 三条（口径 §12.4.1 续记）；只有 `SideConfigPacket` 那条删 |

## 一、定位

| 项 | 值 | 出处 |
|---|---|---|
| BE | `blockentity/IceMakerBlockEntity.java`（1279 行） | — |
| 基类/接口 | `extends BlockEntity implements MenuProvider, IRedstoneControllable, IMekanismHeatHandler, cn.ism.mekck.ae2.INetworkPullable` | `:57` |
| 多方块 | 否 | — |
| 注册 | 方块 `mekck:ice_maker`、BE `ice_maker` | `registry/MekCkStandaloneMachines` `:65` / `:71` |
| Menu | `menu/IceMakerMenu.java`（`ISideConfigurableMenu` + `IUpgradeMenu`） | `:30` |
| Screen | `client/IceMakerScreen.java`（自研侧配 + 升级窗 + `MekCkTabElement`） | — |

## 二、槽序（存档契约）

`INPUT_SLOT=0`、`OUTPUT_SLOT=1`、`SLOT_SPEED_UPGRADE=2`、`SLOT_ENERGY_UPGRADE=3`、`SLOT_CREATIVE_UPGRADE=4`、`TOTAL_SLOTS=11`。（`:74-86`）

## 三、掉落

- **无战利品表**。`IceMakerBlock.java:109`（`onRemove` → `dropContents`）。
- ⇒ 迁移时补战利品表。

## 四、NBT 根键

`Items`、`Fluid`、`Energy`、`Progress`、`OrderRecipeId`、`OrderQuantity`、`OrderCompleted`、`MeOrderEnabled`、`TargetTemperature`、`EnergyUpgradeTracker`、`CreativeUpgradeTracker`、`ColdBrew{i}`、`TemperatureControl`、`HeatCapacitor`、`AttackTimer`、`TargetType`、`Radius`、`SideConfig`、`RedstoneControl`、`RedstonePowered`、`CustomName`、`BuffOwnerPos`（`:1151-1182`）。**不用 `MekCkLegacyMachineNbt`，无原生标记。**

## 五、热系统（口径 §八的自我实现）

当前是**自研 `IMekanismHeatHandler`**（`MekCkHeatComponent` 提供 `getHeatCapacitors`）。迁移时改用 Mek 原生 `BasicHeatCapacitor`，且**只能在 `getInitialHeatCapacitors` 里赋值**（构造期陷阱，口径 §一）。

## 六、迁移要点

1. 冷萃升级链（`ColdBrew{i}` 槽）+ `util/ColdBrewHelper` 联动。
2. `TargetType`/`Radius` 攻击目标语义与同步包保留。
3. 热容改原生（`getInitialHeatCapacitors`）。
4. 补战利品表 + 迁移器 + 原生标记。
5. 该屏有自研侧配 + 升级窗 + tab，随迁移删除。

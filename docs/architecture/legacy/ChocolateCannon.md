# ChocolateCannon（巧克力大炮）迁移契约

**迁移阶段：未迁**（仍是普通 `BlockEntity`）

## 一、定位

| 项 | 值 | 出处 |
|---|---|---|
| BE | `blockentity/ChocolateCannonBlockEntity.java`（1251 行） | — |
| 基类/接口 | `extends BlockEntity implements MenuProvider, IRedstoneControllable, cn.ism.mekck.ae2.INetworkPullable` | `:76` |
| 多方块 | 否 | — |
| 注册 | 方块 `mekck:chocolate_cannon`、BE `chocolate_cannon` | `registry/MekCkStandaloneMachines` `:222` / `:228` |
| Menu | `menu/ChocolateCannonMenu.java`（`ISideConfigurableMenu` + `IUpgradeMenu`） | `:29` |
| Screen | `client/ChocolateCannonScreen.java`（自研侧配 + 升级窗 + `MekCkTabElement`） | — |

## 二、槽序（存档契约）

`INPUT_SLOT=0`、`EXTRA_SLOT=1`、`OUTPUT_SLOT=2`、`SLOT_SPEED_UPGRADE=3`、`SLOT_ENERGY_UPGRADE=4`、`SLOT_CREATIVE_UPGRADE=5`、`TOTAL_SLOTS=14`（其余含费列罗槽/能源槽）。（`:77-88`）

## 三、掉落

- **无战利品表**。`ChocolateCannonBlock.java:109`（`onRemove` + `dropContents`）。
- ⇒ 迁移时补战利品表。

## 四、NBT 根键

`Items`、`Fluids`、`Energy`、`Progress`、`OrderRecipeId`、`OrderQuantity`、`OrderCompleted`、`MeOrderEnabled`、`SpeedUpgrade`、`EnergyUpgrade`、`CreativeUpgrade`、`Ferrero{i}`、`AttackTimer`、`TargetType`、`Radius`、`SideConfig`、`RedstoneControl`、`RedstonePowered`、`CustomName`（`:1195-1224`）。**不用 `MekCkLegacyMachineNbt`，无原生标记。**

## 五、攻击目标系统（迁移时不可丢）

`TargetType` / `Radius`（`:1195-1224`）是**自有的攻击目标语义**，有自己的包与客户端同步链；迁移时须整体保留（参照制冰系）。攻击半径受 `IceTargetSearch.clampAttackRadius` 唯一闸门约束（护栏 `util/TestAttackRadiusClamp`）。

> 关联工具类：`util/ChocolateCannonReservations`（飞行中伤害预留，按世界实例隔离）+ `util/ChocolateCannonLifecycle`（`@EventBusSubscriber`，世界卸载/服务器停止时确定性清理）。

## 六、迁移要点

1. 补战利品表 + 迁移器 + 原生标记。
2. 保留 `TargetType`/`Radius` 及其同步包。
3. 该屏有自研侧配 + 升级窗 + tab，随迁移删除。

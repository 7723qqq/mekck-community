# IceFactory（制冰工厂）迁移契约

**迁移阶段：未迁，且口径结论为「不迁」**（配置禁用，玩家当前不可达）

## 一、定位

| 项 | 值 | 出处 |
|---|---|---|
| BE | `blockentity/IceFactoryBlockEntity.java`（1007 行） | — |
| 基类/接口 | `extends BlockEntity implements MenuProvider, IRedstoneControllable, IMekanismHeatHandler, cn.ism.mekck.ae2.INetworkPullable` | `:60` |
| 多方块 | 否 | — |
| 注册 | 方块依档位 `mekck:<tier>_ice_factory`（12 档 = 12 方块） | `CuttingMachineFactoryTier.java:99-101`、`registry/MekCkFactories.java:863-882` |
| Menu | `menu/IceFactoryMenu.java`（`ISideConfigurableMenu` + `IUpgradeMenu`） | `:31` |
| Screen | `client/IceFactoryScreen.java`（自研侧配 + 升级窗 + `MekCkTabElement`） | — |

## 二、禁用开关

- `MekckConfig.ENABLE_ICE_FACTORY` 默认 **`false`**（`config/MekckConfig.java:311`；`isIceFactoryEnabled()` `:635`）。
- 关闭时 12 个方块/物品/BE 全不注册，`ICE_FACTORY_MENU = null`（`registry/MekCkFactories.java:861-887`）。
- ⇒ 因 `ICE_FACTORY_MENU` 为 null，相关 GUI 代码实际不可达（口径 §12.1 备注「I-1 制冰工厂 GUI 面板在高档位超出屏幕，实际不可达」）。

## 三、槽序（存档契约，依档位动态）

`INPUT_SLOTS = OUTPUT_SLOTS = processes`；`base = 2×processes`；
`SPEED_UPGRADE_SLOT=base`、`ENERGY_UPGRADE_SLOT=base+1`、`STACK_UPGRADE_SLOT=base+2`、`CREATIVE_SLOT=base+3`、`CB_SLOT_1..5=base+4..base+8`、`POWER_SLOT=base+9`、`TOTAL_SLOTS=base+10`。（`:274-291`）

## 四、掉落

- **无战利品表**。`IceFactoryBlock.java:122`（`onRemove` → `dropContents`）。

## 五、NBT 根键

`SpeedUpgradeTracker`、`EnergyUpgradeTracker`、`StackUpgradeTracker`、`CreativeUpgradeTracker`、`HeatCapacitor`、`Items`、`SlotLayoutVersion`(=2)、`Fluid`、`Energy`、`Progress`(int[])、`AttackTimer`、`TargetType`、`Radius`、`SideConfig`、`RedstoneControl`、`RedstonePowered`、`CustomName`（`:937-957`）。**不用 `MekCkLegacyMachineNbt`，无原生标记。**

## 六、为什么「不迁」（口径专项评估结论）

制冰工厂比已迁的 6 个工厂多出四类东西，**其中第一类就需改共享基类**：

| 需求 | 已迁 6 个 | 制冰工厂 | 障碍 |
|---|---|---|---|
| 流体 | 无 | 水罐 256,000 mB | `MekCkMachineTile#presetVariables` 写死 `TileComponentConfig(this, ITEM, ENERGY)`；加 `FLUID` 会给 **6 台在产机器**的侧配 GUI 多出空 Tab（**回归**） |
| 热 | 单热容 | **双热容**（正面吸热/背面放热） | 基类只有单热钩子 `hasHeatSupport()` / `getInitialHeatCapacitors()` |
| 升级 | 速度/能量/存储/随机化 | **5 段链式冷萃升级** | 链式准入判定要新写 |
| 行为 | 纯 item→item | **发射冰块实体**（目标搜索/排队/AOE/伤害/减速/去 AI/失温 + 自有包与同步） | 执行器要拿 level 生成实体，`pendingAttackTargets` 有跨 tick 状态 |

外加：自有配方类型 `IceMakeRecipe` + 从 964 行旧 BE 的存档迁移。
**结论：等有可启动的实机环境时再按上表逐项做，不在无验证条件下动 `presetVariables`。**
枚举槽与译名键已就位（`MekCkFactoryType.ICE`），补 tile + executor 即可接上。

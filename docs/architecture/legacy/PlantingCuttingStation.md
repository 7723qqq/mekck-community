# PlantingCuttingStation（种植切配台）迁移契约

**迁移阶段：未迁**（仍是普通 `BlockEntity`）

## 一、定位

| 项 | 值 | 出处 |
|---|---|---|
| BE | `blockentity/PlantingCuttingStationBlockEntity.java`（920 行） | — |
| 基类/接口 | `extends BlockEntity implements MenuProvider, IRedstoneControllable, IBoundingBlock, cn.ism.mekck.ae2.INetworkPullable`；另实现 `IComparatorSupport`/`IUpgradeTile` 同名方法 | `:54`、`:936/947` |
| 多方块 | **是**（`IBoundingBlock`，1×2×1） | `:917-918` |
| 注册 | 方块 `mekck:planting_cutting_station`、BE `planting_cutting_station` | `registry/MekCkFactories` `:194` / `:200` |
| Menu | `menu/PlantingCuttingStationMenu.java`（`ISideConfigurableMenu` + `IUpgradeMenu`） | `:27` |
| Screen | `client/PlantingCuttingStationScreen.java`（自研侧配 + 升级窗 + `MekCkTabElement`） | — |

## 二、槽序（存档契约）

`INPUT_SLOT=0`、`NUTRIENT_SLOT=1`、`OUTPUT_SLOT=2`、`SLOT_SPEED_UPGRADE=3`、`SLOT_ENERGY_UPGRADE=4`、`SLOT_CREATIVE_UPGRADE=5`、`SLOT_GAS_UPGRADE=6`、`GROWTH_SLOT=8`、`TOTAL_SLOTS=9`。（`:55-70`）

> 注：`GROWTH_SLOT=8` 与 `SLOT_GAS_UPGRADE=6` 之间跳过 7 —— 槽 7 语义**未核实**（迁移前须确认，槽序即存档契约）。

## 三、掉落

- **无战利品表**。`PlantingCuttingStationBlock.java:133-150`（`onRemove` → `dropContents`）。
- ⇒ 迁移时补战利品表（含绑定块）。

## 四、NBT 根键

`Items`、`Energy`、`Progress`、`NutrientAccumulator`、`SideConfig`、`RedstoneControl`、`RedstonePowered`、`CustomName`（`:824-842`）。**不用 `MekCkLegacyMachineNbt`，无原生标记。**

## 五、迁移要点

1. **`machine → blockentity` 循环依赖的唯一一条边就在这里**：`machine/plantingcutting/PlantingCuttingFactoryTile.java:5` 导入本 BE。迁移/重构时用 `machine/ports/IPlantingCuttingHost` 接口倒置（见 `../00-module-map.md` §5.1）。
2. 多方块（1×2×1 `IBoundingBlock`）——需保留绑定逻辑。
3. 有气体槽（`SLOT_GAS_UPGRADE`）与养分槽（`NUTRIENT_SLOT`），迁移时按口径决定走 Mek 的哪套组件。
4. 补战利品表 + 迁移器 + 原生标记。
5. 遗留问题（未修）：**模型硬依赖未声明的 `mekmm`**（18 处引用 + 30 条配方），需决定：声明依赖 / 换自有模型 / 条件化。

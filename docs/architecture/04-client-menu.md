# 04 · client / menu 分层

> 描述源码：`src/main/java/cn/ism/mekck/client/`（21 个 Screen + 窗口/tab/Renderer）与 `menu/`（21 个 Menu + `slot/`）。
> 规则约束：[`../2026-09-30-功能实现口径.md`](../2026-09-30-功能实现口径.md) §五/§六/§十/§十三（GUI 复用上游、面板几何、自研件清单）。

## 一、分层

```
client(Screen/Renderer/Window/Tab)   ← 只由入口类 UniversalCuttingMachine 引用
   ↓ 依赖
menu(Menu/Slot 表)  network(S2C/C2S)  machine  blockentity  util
```

**铁律**：除入口类 `UniversalCuttingMachine` 外，任何非 `client` 包**不得** `import cn.ism.mekck.client.*`。

## 二、共享屏幕基类（复用的唯一入口）

| 基类 | 类型参数 | 提供 |
|---|---|---|
| `client/MekCkContainerScreenBase` | `<TILE extends TileEntityMekanism & ISideConfiguration, MENU extends MekanismTileContainer<TILE>> extends GuiConfigurableTile` | 构造器 `dynamicSlots=true`、`drawForegroundText`（机器名 + Inventory 标签）、`addEnergyBar`、`addNetworkPullTabs`、`jeiLoaded()`
| `client/MekCkFactoryScreenBase`（继承上者） | `<TILE extends MekCkMachineTile, MENU extends MekanismTileContainer<TILE>>` | `addNetworkPullTabs`、带 `isNotEnoughEnergy` 的能源条、`addSortingTab`、`addFactoryProgressBars`/`addSingleProgressBar`、`jeiCategoriesOf` |

继承者：
- `MekCkFactoryScreenBase`（6）：GrindingFactory / CuttingMachineFactory / GrillFactory / PlantingCuttingFactory / CookingFactory / SkeweringFactory Screen。
- `MekCkContainerScreenBase` 直接（1）：`ElectricGrindingMachineScreen`。
- **未继承**：`UniversalCuttingMachineScreen`（`extends GuiConfigurableTile`，仅注释提及基类）——可归入基类以消重复（低风险）。

## 三、Screen 清单（21 个）

| 屏 | extends | 自绘 `drawForegroundText` | 自研侧配 | 自研升级窗 | 自研 tab |
|---|---|---|---|---|---|
| BioreactorScreen | `GuiMekanism` | 有 | — | — | — |
| CentralKitchenScreen | `GuiMekanism` | 有 | ✔ | — | ✔ |
| ChocolateCannonScreen | `GuiMekanism` | 有 | ✔ | ✔ | ✔ |
| CookingFactoryScreen | `MekCkFactoryScreenBase` | 继承 | — | — | — |
| CuttingMachineFactoryScreen | `MekCkFactoryScreenBase` | 继承 | — | — | — |
| ElectricGrindingMachineScreen | `MekCkContainerScreenBase` | 继承 | — | — | — |
| GrillFactoryScreen | `MekCkFactoryScreenBase` | 继承 | — | — | — |
| GrillScreen | `GuiConfigurableTile` | 有 | — | — | — |
| GrindingFactoryScreen | `MekCkFactoryScreenBase` | 继承 | — | — | — |
| IceFactoryScreen | `GuiMekanism` | 有 | ✔ | ✔ | ✔ |
| IceMakerScreen | `GuiMekanism` | 有 | ✔ | ✔ | ✔ |
| NutRoasterScreen | `GuiMekanism` | 有 | ✔ | ✔ | ✔ |
| PlantingCuttingFactoryScreen | `MekCkFactoryScreenBase` | 继承 | — | — | — |
| PlantingCuttingStationScreen | `GuiMekanism` | 有 | ✔ | ✔ | ✔ |
| SandwichAssemblerScreen | `GuiMekanism` | 有 | ✔ | — | ✔ |
| SimpleMachineScreen | `GuiMekanism` | 有 | ✔ | ✔ | ✔ |
| SkeweringFactoryScreen | `MekCkFactoryScreenBase` | 继承 | — | — | — |
| SkeweringMachineScreen | `GuiMekanism` | 有 | 自研直发 `SideConfigPacket` | ✔ | ✔ |
| SmartCookingPotScreen | `GuiMekanism` | 有 | ✔ | ✔ | ✔ |
| UniversalCuttingMachineScreen | `GuiConfigurableTile` | 有 | — | — | — |
| WineCellarScreen | `GuiMekanism` | 有 | — | — | — |

## 四、自研件残留清单（**待删**，口径 §十三）

| 自研件 | 位置 | 用途 | 上游替代 | 现存引用 |
|---|---|---|---|---|
| `ISideConfigurableMenu` | `menu/` | 物品/流体/气体侧面配置访问 | `TileComponentConfig` | **10 个 menu** 实现 |
| `IUpgradeMenu` | `menu/` | 升级信息访问 | `TileComponentUpgrade` | **8 个 menu** 实现 |
| `GuiMekCkSideConfiguration` | `client/` | 自绘侧配窗（`extends GuiWindow`） | Mek `GuiSideConfiguration` | **9 个屏** `new` |
| `GuiUpgradeWindow` | `client/` | 自研升级弹窗 | Mek `GuiUpgradeWindow` | **8 个屏** `new` |
| `MekCkUpgradeType` / `MekCkUpgradeTypesStrip` / `MekCkUpgradeScrollList` | `client/` | 升级类型枚举/条/滚动列表 | Mek `UpgradeSlot` + `GuiUpgradeWindowTab` | 仅被 `GuiUpgradeWindow` 使用 |
| `MekCkTabElement` | `client/` | 侧栏 tab（`extends GuiInsetElement`） | `GuiTabElementType` / `GuiWindowCreatorTab` | **10 个屏** `new` |

**仍依赖自研件的屏并集 = 14 个**；完全不含自研件的 7 屏：GrindingFactory、CuttingMachineFactory、GrillFactory、PlantingCuttingFactory、UniversalCuttingMachine、Bioreactor、WineCellar。
**仍实现自研接口的 menu**：`ISideConfigurableMenu` 10 个、`IUpgradeMenu` 8 个（并集 10）。

### 删除前置条件（**不可提前删**）

1. **逐台随迁移删除**：每台机器迁到 Mek 原生时，**同批**删除其屏的 `openSideConfigWindow`/`openUpgradeWindow` 按钮、`addWindow(new GuiUpgradeWindow(...))`、menu 的 `implements ISideConfigurableMenu/IUpgradeMenu`、`ContainerData`/`WideDataSlot`（口径 §12.4 第 5、6 项）。
2. **最后清扫批（重构批次 9）**：待**最后一台** legacy 屏迁完后，才可删除上表 6 类自研件本体。
3. `menu/MekCkFactoryLayout` **保留**（工厂 GUI 几何共用公式，非自研残留）。

### 现在（不经迁移）可做

- 已迁屏收敛到共享基类：`GrillScreen`、`WineCellarScreen`、`UniversalCuttingMachineScreen` 消除各自重复的 `drawForegroundText`/能源条。护栏 `TestGuiInventoryLabels` 已改为「认继承」，不会误红。

## 五、Menu 清单（21 个）

| Menu | 基类 | 自研接口 | `WideDataSlot` | `ContainerData` |
|---|---|---|---|---|
| BioreactorMenu | `AbstractContainerMenu` | — | ✔ | ✔ |
| CentralKitchenMenu | `AbstractContainerMenu` | ISide | — | ✔ |
| ChocolateCannonMenu | `AbstractContainerMenu` | ISide + IUpgrade | ✔ | ✔ |
| CookingFactoryMenu | `MekanismTileContainer` | — | — | ✔ |
| CuttingMachineFactoryMenu | `MekanismTileContainer` | — | — | ✔ |
| ElectricGrindingMachineMenu | `MekanismTileContainer` | — | — | ✔ |
| GrillFactoryMenu | `MekanismTileContainer` | — | — | ✔ |
| GrillMenu | `MekanismTileContainer<GrillBlockEntity>` | — | — | ✔ |
| GrindingFactoryMenu | `MekanismTileContainer` | — | — | ✔ |
| IceFactoryMenu | `AbstractContainerMenu` | ISide + IUpgrade | ✔ | ✔ |
| IceMakerMenu | `AbstractContainerMenu` | ISide + IUpgrade | ✔ | ✔ |
| NutRoasterMenu | `AbstractContainerMenu` | ISide + IUpgrade | ✔ | ✔ |
| PlantingCuttingFactoryMenu | `MekanismTileContainer` | — | — | ✔ |
| PlantingCuttingStationMenu | `AbstractContainerMenu` | ISide + IUpgrade | ✔ | ✔ |
| SandwichAssemblerMenu | `AbstractContainerMenu` | ISide | — | ✔ |
| SimpleMachineMenu | `AbstractContainerMenu` | ISide + IUpgrade | ✔ | ✔ |
| SkeweringFactoryMenu | `MekanismTileContainer` | — | — | ✔ |
| SkeweringMachineMenu | `AbstractContainerMenu` | ISide + IUpgrade | ✔ | ✔ |
| SmartCookingPotMenu | `AbstractContainerMenu` | ISide + IUpgrade | ✔ | ✔ |
| UniversalCuttingMachineMenu | `MekanismTileContainer` | — | — | ✔ |
| WineCellarMenu | `MekanismTileContainer` | — | — | ✔ |

> `WideDataSlot`（16 位截断的修复件）随各机器迁到 Mek 后**整组作废**（Mek 用 `addContainerTrackers` 同步通道，无截断）。

## 六、客户端隔离核查

- **无任何非 client、非根包 `import cn.ism.mekck.client.*`**（实测）。
- 唯一**真实代码链接点**（未 import，全限定名）：`item/ItemAtomicKnife.java:69` → `cn.ism.mekck.client.atomic_knife.RenderPropertiesAtomicKnife`。
- 注释/反射字符串引用（非编译期链接，安全）：`entity/FerreroEntity`、`item/GuideHandbookItem`、`buff/BuffLinkIndex`、`network/NetworkRecipeListPacket`、`compat/GuideMECompat`（反射串）、`util/ClientPacketBridge`（反射串）。
- **`KNOWN_VIOLATIONS` 债务**：`item/MekCkBlockItem`、`item/BioreactorBlockItem` 直接依赖 `mekanism.client.key`（`MekKeyHandler` tooltip 按键提示）。可提前修（key 处理下沉到 `client/` 或走 common shim），修完按规则**收缩 `KNOWN_VIOLATIONS` 并注释**——**不得**直接放宽该护栏。

## 七、相关护栏

`client/TestGuiInventoryLabels`、`TestFactoryScreenNullGuards`、`TestScreenNullGuards`、`TestDynamicLayoutScreenNullGuards`、`TestUpgradeSlotHitTest`、`TestWindowHitTest`、`TestTabElementGuard`、`TestNoClientSymbolsInCommonCode`、`menu/TestMenuNullGuards`、`TestDynamicLayoutMenuNullGuards`、`TestSlotTableContract`、`TestMenuQuickMoveSlotRanges`。

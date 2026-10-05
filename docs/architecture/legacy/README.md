# legacy 机器迁移契约索引

> 描述源码：`src/main/java/cn/ism/mekck/blockentity/`（仍是普通 `BlockEntity` / `MekCkLegacyMachine` 的机器）。
> **2026-10-06**：坚果爆炒机已迁（`machine/roasting/NutRoasterTile`）后基数 11 → 10；
> 同日急冻制冰机同批迁移（`machine/icemaker/IceMakerTile`），基数 10 → 9。
> 目的：**迁移前逐台核对不能动的东西**（槽序 = 存档契约、NBT 键名、注册名、战利品表），并留证「迁到哪一步」。
> 迁移动作清单见 [`../../2026-09-30-功能实现口径.md`](../../2026-09-30-功能实现口径.md) §12.4（10 项）与 §12.5（护栏登记表）。

## ⚠️ 基数更正（2026-10-05 实测）

此前文档记「13 台待迁」。实测：

- **不存在 `UniversalCuttingMachineBlockEntity`** —— 该机器**早已迁完**，BE 是 `machine/cutting/UniversalCuttingMachineTile`（`extends TileEntityConfigurableMachine implements ISustainedData`），注册名仍为 `mekck:universal_cutting_machine`，**战利品表存在且生效**（`data/mekck/loot_tables/blocks/universal_cutting_machine.json`）。
- 制冰工厂（IceFactory）**配置禁用**（`MekckConfig.enable_ice_factory` 默认 `false`），当前玩家不可达。
- ⇒ **真正仍是普通 BlockEntity 的是 9 台**（2026-10-06 迁完坚果爆炒机与急冻制冰机，11 → 10 → 9；下表）。

## 汇总表

| # | 机器 | BE 行数 | 覆盖方块 | 多方块 | 现存战利品表 | 用 `MekCkLegacyMachineNbt` | 原生标记 | 迁移阶段 |
|---|---|---|---|---|---|---|---|---|
| 1 | [Bioreactor](Bioreactor.md) | 626 | 1 | ✔ | 无（onRemove 掉落） | 否 | 无 | 未迁 |
| 2 | [CentralKitchen](CentralKitchen.md) | 1732 | 1 | — | 无（onRemove 掉落） | 否 | 无 | 未迁 |
| 3 | [ChocolateCannon](ChocolateCannon.md) | 1251 | 1 | — | 无（onRemove 掉落） | 否 | 无 | 未迁 |
| 4 | [IceFactory](IceFactory.md) | 1007 | 12（档位） | — | 无（onRemove 掉落） | 否 | 无 | 未迁（**配置禁用**，口径结论：不迁） |
| 5 | [IceMaker](IceMaker.md) | 1279 | 1 | — | **有**（`ice_maker.json`） | 否 | 无 | **已迁（2026-10-06）** |
| 6 | [NutRoaster](NutRoaster.md) | 915 | 1 | — | **有**（`nut_roaster.json`） | 否 | 无 | **已迁（2026-10-06）** |
| 7 | [PlantingCuttingStation](PlantingCuttingStation.md) | 920 | 1 | ✔ | 无（onRemove 掉落） | 否 | 无 | 未迁 |
| 8 | [SandwichAssembler](SandwichAssembler.md) | 739 | 1 | — | 无（onRemove 掉落） | 否 | 无 | 未迁 |
| 9 | [SimpleMachine](SimpleMachine.md) | 2582 | **17** | — | 无（onRemove 掉落） | 否 | 无 | 未迁 |
| 10 | [SkeweringMachine](SkeweringMachine.md) | 1099 | 1 | — | 无（onRemove 掉落） | 否 | 无 | 未迁 |
| 11 | [SmartCookingPot](SmartCookingPot.md) | 1567 | 1 | — | 无（onRemove 掉落） | 否 | 无 | 未迁 |
| — | UniversalCuttingMachine | 581（Tile） | 1 | — | **有且生效** | 否 | — | **已迁**（模板） |

> ⚠️ **仍未迁的 9 台全部没有战利品表**：它们一律覆写 `getDrops(...) → List.of()`，改由方块 `onRemove` + `saveToItem`/`dropContents` 掉落。
> ⇒ 迁移时**必须补战利品表**（`BlockTile` 走战利品表），否则**拆掉机器一件东西都不掉**，且编译通过。这是样板迁移抓到的最重要一条（口径 §4.1）。
> （坚果爆炒机与急冻制冰机已于 2026-10-06 分别补齐 `data/mekck/loot_tables/blocks/nut_roaster.json`
> 与 `data/mekck/loot_tables/blocks/ice_maker.json`。）

> ⚠️ **仍未迁的 9 台全部不用 `MekCkLegacyMachineNbt`、不写原生版本标记**。全仓目前只有 `GrillBlockEntity` 用迁移器（`:1001/1083`）并写 `"MekCkGrillNative"`（`:239`）。
> ⇒ 每台迁移时需按样板补：迁移器调用 + `saveAdditional` 写原生标记（漏写 ⇒ 每次区块加载白跑一遍迁移器；幂等所以**无功能性症状、极难发现**，口径 §4.2）。

## 已迁模板（供对照）

| 机器 | 迁移提交/阶段 | 说明 |
|---|---|---|
| 电力烧烤架 `GrillBlockEntity` | 阶段 2 | 首台单机样板，`extends TileEntityConfigurableMachine` |
| 酒窖 `WineCellarBlockEntity` | 早已迁完 | 注意：仍保留自定义渲染屏 |
| 电力研磨机 `GrindingMachineTile` | 阶段 3（2026-10-05） | 本轮样板；暴露了 §12.4 全部 10 项 + 战利品表 + 原生标记 |
| 6 工厂家族 ×12 档 | 阶段 2 | `*FactoryTile extends MekCkMachineTile` |
| 通用切菜机 | 阶段 2 | `machine/cutting/UniversalCuttingMachineTile` |
| 坚果爆炒机 `NutRoasterTile` | 阶段 3（2026-10-06） | `machine/roasting/NutRoasterTile`（经 `MekCkNetworkPullableTile`）；**MekckAe2 的样板/下单分支改指新 tile 而非删除**，见口径 §12.4.1 |
| 急冻制冰机 `IceMakerTile` | 阶段 3（2026-10-06） | `machine/icemaker/IceMakerTile`（经 `MekCkNetworkPullableTile`）；除 AE2 四条外，`IceAttackConfigPacket` / `UpgradeUninstallPacket` / `UpgradeInstallHandler` 三条**也不在 `MekckAe2` 的活分支**同样改指不删（口径 §12.4.1 续记） |

## 每批迁移后的登记（两处，缺一不可）

1. **`docs/STATUS.md`**：记录提交号 + 代码位置（本仓判据：写「已迁」必须同时给出提交号与代码位置，否则按未迁对待）。
2. **`machine/TestMigrationCompleteness`**（`src/test/java/cn/ism/mekck/machine/TestMigrationCompleteness.java`）：该护栏以**刻意写死的 `MIGRATED` 登记表**为锚，逐项断言「三件套成组 `register(bus)` / 客户端屏幕绑定 / 创造栏 / 两份 lang 键 / 旧 `instanceof` 分支已删」。
   **自动扫描做不到这件事**——它会让「漏配的那台」因扫不到而逃过所有断言；所以**新迁一台必须来登记一次**，登记动作本身逼人把清单过一遍。

## 迁移顺序建议

按「风险低、依赖少、被别的机器引用少」优先，每批 2–3 台，各自可回滚：

1. ~~**单个体、无多方块、无热系统**：`NutRoaster`~~（**2026-10-06 已迁**）→ ~~`IceMaker`~~（**2026-10-06 已迁**）→ `ChocolateCannon`（带攻击目标系统，需保留 `TargetType`/`Radius` 同步）。
2. **带热/流体**：`SmartCookingPot` → `SkeweringMachine`（已有 `MekCkLegacyMachine` 基类，改动小）。
3. **多方块**：`Bioreactor`、`PlantingCuttingStation`（`IBoundingBlock`，需与绑定块一并处理）。
4. **一 BE 覆盖 17 方块**：`SimpleMachine`（最后，工作量大）。
5. **被其它 tile 引用**：`UniversalCuttingMachine` 已迁；`IceFactory` 不迁。

> 每批迁完须更新本目录对应页的「迁移阶段」，并在主文档 `docs/STATUS.md` 记录提交号 + 代码位置（本仓判据：写「已修/已迁」必须同时给出提交号与代码位置）。

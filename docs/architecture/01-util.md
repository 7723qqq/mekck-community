# 01 · `util/` 分类与去留

> 描述源码：`src/main/java/cn/ism/mekck/util/`（39 个类）。
> 计数口径：`cn.ism.mekck.util.<类名>` 在全 `src/main` 的**全限定名出现次数**。
> ⚠️ **该口径会低估**：① 同包内以简单名调用不计（如 `FreezeEvents` 用 `MaxLootRandom`）；② 注解发现（`@EventBusSubscriber`）不计。**判死代码必须两处都补查**（本文 §三已做）。

## 一、通用工具（保留在 `util/`）

无 Minecraft 领域语义，或已被全局高频复用；**移动收益最低、波及最广，保持不动**。

| 类 | 行 | 引用 | 说明 |
|---|---|---|---|
| `RecipeCache` | 217 | 164 | 配方缓存（**注意**：现依赖 `compat.TavernBarrelCompat`，见 `00-module-map.md` §六） |
| `Reflect` | 152 | 72 | 反射助手（含哨兵方法 `missingMethod()`，**勿删**） |
| `CountMath` | 50 | 48 | 计数算术 |
| `WideDataSlot` | 90 | 27 | 双槽无损传 32 位值（随 Mek 迁移将整组作废） |
| `Directions` | 15 | 10 | 方向助手 |
| `LagMonitor` | 135 | 8 | 延迟监控 |
| `ClientPacketBridge` | 127 | 4 | common→client 反射桥（依赖 `compat.GuideMECompat`） |
| `IntHandlerBulkView` | 111 | 3 | 大宗 handler 视图 |
| `StorageMerger` | 52 | 2 | 堆叠归并（**C3 修复的参照实现**） |
| `TeleportFxUtil` | 48 | 2 | 传送特效 |
| `MatchKey` | 28 | 1 | 匹配键 |
| `FastTransfer` | 90 | 1 | 快速搬运 |

## 二、领域助手（有明确归属，应迁出 `util/`）

| 类 | 行 | 引用 | 现有越界依赖 | 建议去向 | 理由 |
|---|---|---|---|---|---|
| `TierInstallerHandler` | 288 | 12 | ✅ **已迁 `block/TierInstallerHandler`**（2026-10-05） | `block/` | 语义是升级安装处理（`@EventBusSubscriber`）。迁 `block/` 使 13 条 block import 变同包消失、新增边为 0；**迁 `upgrade/` 会形成 `upgrade ↔ block` 环**（故不采纳原计划目的地） |
| `RecipeInputMatcher` | 233 | 21 | recipe 4 / registry 1 / compat 1 | `recipe/` | 配方输入匹配，自包含 |
| `AE2InputSpec` | 14 | 80 | 无 | `ae2/` | 与 `ae2/INetworkPullable` 同域，被高频引用 |
| `MekCkHeatComponent` | 128 | 21 | — | **保留 `util`** | 类注释自述「现在没人用，也不要为了整洁删掉」——保存一次设计查证的**否定结论** |
| `ChocolateCannonReservations` | 150 | 8 | blockentity 1 / entity 1 | `blockentity/` | 巧克力炮伤害预留注册表，与机器同域 |
| `IceTargetSearch` | 177 | 19 | 无 | 暂留 / 后续随制冰系迁移 | 攻击半径闸门（护栏 `TestAttackRadiusClamp` 硬编码其路径） |
| `ColdBrewHelper` | 88 | 2 | item 2 | `item/` 或 `upgrade/` | 冷萃升级助手 |
| `BigStackItemHandler` | 173 | 23 | 无 | `util/stack/` | 大堆叠专属，成组便于整体演迁 |
| `BigStackDrops` | 69 | 17 | 无 | `util/stack/` | 同上 |
| `MultiFluidHandler` | 243 | 11 | 无 | `util/fluid/` | 流体通用助手 |
| `FluidIngredientHelper` | 191 | 12 | 无 | `util/fluid/` | 同上 |
| `FluidContainerInteract` | 117 | 3 | 无 | `util/fluid/` | 同上 |
| `VineryJuice` | 115 | 13 | 无 | `util/fluid/` | 酒类流体类型表 |
| `AutoIO` | 188 | 10 | 无 | `util/io/` | 自研弹出/自动 IO 体系，**随 Mek 迁移整组消亡**，聚一处便于整组删除 |
| `AutoFluidIO` | 100 | 4 | 无 | `util/io/` | 同上 |
| `AutoGasIO` | 103 | 2 | 无 | `util/io/` | 同上 |
| `NetworkPullHelper` | 19 | 4 | 无 | `util/io/` | 网络拉料助手 |
| `MekCkTransfer` | 84 | 10 | 无 | 暂留 | 被 `SimpleMachineMenu` 依赖 |
| `MekCkMultiblock` | 179 | 7 | 无 | 暂留 | 多方块助手 |
| `PowerSlotUtil` | 189 | 15 | 无 | 暂留 | 供电槽助手（触升级体系，随迁移一并处置） |
| `BioreactorFuels` | 132 | 4 | config 1 | `blockentity/` 或 `recipe/` | 生物反应炉燃料表 |
| `FreezeEvents` | 252 | 0* | registry 1 | `event/` | **`@EventBusSubscriber`，非死代码**（*见 §三） |
| `FreezeAiReaper` | 118 | 3 | effect 1 | `event/` | `@EventBusSubscriber` |
| `ChocolateCannonLifecycle` | 35 | 0* | 无 | `event/` | **`@EventBusSubscriber`，非死代码** |
| `ChocolateTagScanner` | 69 | 0* | 无 | `event/` | **`@EventBusSubscriber`，非死代码** |
| `EnergyCubePreviewUtil` | 49 | 1 | 待核实是否引用客户端类 | 待定 | 若引用客户端类须迁 `client/` |

### 二之补：已完成的搬迁（2026-10-05）

下表 8 个类已迁出 `util/`，`util` 的越界依赖由 32 条降到 4 条（recipe 1 / compat 2 / config 1；§四另有 ae2 1）：

| 类 | 去向 | 迁移后 `util` 减少的越界边 |
|---|---|---|
| `TierInstallerHandler` | `block/` | block 13 / machine 2 / registry 1 |
| `RecipeInputMatcher` | `recipe/` | recipe 4 / registry 1 / compat 1 |
| `FreezeEvents`、`FreezeAiReaper` | `event/` | registry 1 / effect 1 |
| `ChocolateCannonLifecycle`、`ChocolateTagScanner` | `event/` | —（语义归位，非死代码，见 §三） |
| `ChocolateCannonReservations` | `blockentity/` | blockentity 1 / entity 1 |
| `ColdBrewHelper` | `item/` | item 2 |
| `AE2InputSpec` | `ae2/` | —（高频引用的领域 DTO，归位） |

> ⚠️ **随迁的护栏**：`TestFreezeAiRecovery` 从 `cn.ism.mekck.util` 迁到 `cn.ism.mekck.event`
> —— `FreezeAiReaper.shouldReap` 是**刻意包级私有**的（其 javadoc 有说明），测试必须同包才能调用；
> 迁测试而非把生产方法改 public，保证生产类零改动。断言逻辑一行未改。

**仍未迁（保留在 `util/`）**：`BioreactorFuels`（→ config 1，待定去向）、`ClientPacketBridge`/`RecipeCache`（→ compat）、`NetworkPullHelper`（→ recipe）。这些是低频、低风险的收尾项。

## 三、死代码核验记录（结论：`util/` 内**零死代码**）

按仓库教训（STATUS §六之二：「看出来是死的、其实不能删」），对 4 个全限定名引用为 0 的类逐一核验**四源**（java + lang + 指南 + data）并补查「注解发现」与「同包简单名调用」：

| 类 | 全限定引用 | 核验结论 | 证据 |
|---|---|---|---|
| `FreezeEvents` | 0 | **存活** | `src/main/java/cn/ism/mekck/util/FreezeEvents.java:41` `@Mod.EventBusSubscriber(... FORGE)`，事件总线按注解发现 |
| `ChocolateCannonLifecycle` | 0 | **存活** | 同文件 `:20` `@Mod.EventBusSubscriber(... FORGE)` + 2 个 `@SubscribeEvent`（世界卸载 / 服务器停止清理预留） |
| `ChocolateTagScanner` | 0 | **存活** | 同文件 `:32` `@Mod.EventBusSubscriber(...)` + `@SubscribeEvent onTagsUpdated`（数据包重载时扫描 chocolate 物品） |
| `MaxLootRandom` | 0 | **存活** | 被同包 `FreezeEvents.java:219` 以简单名使用（同包无需 import，故全限定计数漏算） |

> ⚠️ **对重构计划的更正**：计划中的「批次 1：死代码核验+删（`ChocolateCannonLifecycle`、`ChocolateTagScanner`）」**前提不成立，不应执行删除**。这两个类是两个真实的 Forge 事件订阅器，删掉会静默移除：
> ① 世界卸载/服务器停止时巧克力炮伤害预留的确定性清理；② 数据包重载时 `mekck:mekck_chocolate` 标签的动态扫描（费列罗配方 extra 输入依赖它）。
> **正确处置**：不是删除，而是**归位到 `event/`**（与 `event/NetworkAdvancementEvents` 同域），属于本文 §二 的搬迁项。

## 四、实施建议（合并进重构批次 2，可再拆 2–3 小批）

1. **破环优先**：先迁 `TierInstallerHandler` → `upgrade/`（一次解 16 条越界边），再迁 `RecipeInputMatcher` → `recipe/`。
2. **建子包**：`util/io/`、`util/stack/`、`util/fluid/`（纯移动，最安全）。
3. **事件类归位**：`FreezeEvents`/`FreezeAiReaper`/`ChocolateCannonLifecycle`/`ChocolateTagScanner` → `event/`。
4. **域助手归位**：`ChocolateCannonReservations` → `blockentity/`；`ColdBrewHelper` → `item|upgrade/`；`AE2InputSpec` → `ae2/`。
5. **每步跑护栏**：预计变红的测试见 `00-module-map.md` 与主计划 §六；处置只允许「改落点 / 收缩登记表并注释」，**不得放宽判据**。

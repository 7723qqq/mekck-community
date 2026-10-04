# 00 · 模块地图与依赖方向

> 本文描述**目标**模块边界与**实测**现状差距。所有跨包计数均以「`import cn.ism.mekck.<pkg>.` 出现次数」为口径，统计范围为 `src/main`。
> 描述的源码根：`src/main/java/cn/ism/mekck/`。

## 一、目的

消除三处结构性混合（巨型类、`util/` 大杂烩、legacy 与 Mek 双体系并存），使模块高内聚低耦合、可独立维护与测试。**不重命名既有顶层包**——包名与 god 类、注册名、mixin/反射名深度绑定，重命名风险远高于收益。

## 二、包清单与职责

| 包 | 文件数 | 职责 |
|---|---|---|
| `<root>` | 5 | `UniversalCuttingMachine`(@Mod 入口)、`MachineKind`、`RedstoneControl`、`SideMode`、`CuttingMachineFactoryTier` |
| `api` | 1 | 对外接口 `IBulkItemHandler`（long 级大宗搬运） |
| `util` | 39 | 通用工具 + 领域助手（**混放，见 `01-util.md`**） |
| `machine` | 10(+5 子包) | Mek 原生机器内核：工厂/执行器/tile/槽/订单 |
| `blockentity` | 20 | legacy 机器的 BlockEntity 与伴生类（迁移中） |
| `block` | 24 | 方块定义、朝向、ticker、掉落 |
| `menu` | 22(+slot 4) | 容器菜单与槽位表 |
| `client` | 61(+`mesh`/`atomic_knife`) | 屏幕、渲染器、窗口、tab、JEI 界面 |
| `network` | 25 | C2S/S2C 包与 `PacketGuard` |
| `registry` | 10 | 延迟注册中枢（方块/物品/流体/实体/配方类型） |
| `recipe` | 11 | 自有配方类型 |
| `ae2` | 4 | AE2 可选联动门面与端口窗口 |
| `upgrade` | 11 | 升级类型、编解码、安装处理 |
| `integration` | 1(+`jei` 15) | JEI 插件与配方类别 |
| `compat` | 9 | 外部模组反射/门面（森罗、烘焙坊、暮色、AE2…） |
| `kitchen` | 6 | 中央厨房订单求解域 |
| `item` | 8 | 物品与方块物品 |
| `mixin` | 8 | Mixin 注入 |
| `command` | 1 | `PlantingRecipeGenerator` 配方生成命令 |
| `config` | 1 | `MekckConfig` |
| `entity` | 3 | 弹射物实体 |
| `effect` | 3 | 状态效果 |
| `buff` | 2 | 攻击增益白名单与归属 |
| `advancement` | 4 | 网络厨师进度/触发 |
| `event` | 1 | 服务端事件订阅 |
| `world` | 2 | 存档级创造性升级食物池/轮换 |

## 三、目标依赖方向

依赖只允许**自上而下**（下层不得引用上层）：

| 层 | 包 | 允许依赖 |
|---|---|---|
| L0 | `api` | 无 |
| L1 | `util`(+`util/*`) | 仅 JDK / vanilla / `api`；**禁止** block/machine/blockentity/registry/recipe/item/compat/entity/effect/config |
| L2 | `machine`(+子包) | `api`、`util`、`machine/ports` |
| L3 | `blockentity`(legacy) | `machine`、`util`、`api` |
| L4 | `block`、`item`、`menu`、`network`、`recipe`、`registry`、`integration`、`event`、`world`、`advancement`、`command`、`compat`、`kitchen`、`ae2`、`entity`、`effect`、`buff`、`config`、`upgrade` | L0–L3 |
| L5 | `client`(+`mesh`/`atomic_knife`) | 全部；**除入口类 `UniversalCuttingMachine` 外无人可依赖它** |

## 四、实测跨包 import 矩阵（只列违反目标方向的边）

| 源 → 目标 | 计数 | 违反 | 说明 |
|---|---|---|---|
| ~~`util → block`~~ | ~~14~~ **0** | — | ✅ **已消除**：`TierInstallerHandler` 迁入 `block/`（13 条 block import 变同包，直接消失） |
| ~~`util → registry`~~ | ~~3~~ **0** | — | ✅ 已消除：三个来源类全部迁出 `util/` |
| ~~`util → machine`~~ | ~~2~~ **0** | — | ✅ 已消除（`TierInstallerHandler` → `block/`） |
| ~~`util → item`~~ | ~~2~~ **0** | — | ✅ 已消除（`ColdBrewHelper` → `item/`） |
| ~~`util → blockentity`~~ | ~~1~~ **0** | — | ✅ 已消除（`ChocolateCannonReservations` → `blockentity/`） |
| ~~`util → entity`~~ | ~~1~~ **0** | — | ✅ 已消除（同上） |
| ~~`util → effect`~~ | ~~1~~ **0** | — | ✅ 已消除（`FreezeAiReaper` → `event/`） |
| `util → recipe` | ~~4~~ **1** | L1→L4 | 余 `util/NetworkPullHelper` → `RecipeInputMatcher`（后者已迁 `recipe/`） |
| `util → compat` | ~~3~~ **2** | L1→L4 | `ClientPacketBridge`→`GuideMECompat`、`RecipeCache`→`TavernBarrelCompat` |
| `util → config` | 1 | L1→L4 | `util/BioreactorFuels`（待迁） |
| `util → ae2` | 1 | L1→L2 | 待核实（`AE2InputSpec` 已迁 `ae2/` 后仍余 1 条） |
| `util → api` | 2 | 允许 | `api` 属 L0，非违反 |
| ~~`machine → blockentity`~~ | ~~1~~ **0** | — | ✅ **已消除**：生长状态码上移为 `machine/plantingcutting/PlantingCuttingStatus` |
| ~~`machine → block`~~ | ~~6~~ **0** | — | ✅ **已消除**：工厂方块实现 `machine/IFactoryTierProvider`，tile 只认接口 |
| `block → blockentity` | 13 | L4 内部 | 方块反查自己的 BE 类型（Mek 模式允许，**不算违反**，仅登记） |
| `blockentity → machine` | 7 | L3→L2 | **合法方向**（L3 可依赖 L2），保留 |
| `upgrade → blockentity` | 7 | L4→L3 | 合法（L4 可依赖 L3），保留 |

> 说明：`block ↔ machine` 与 `block ↔ blockentity` 是方块↔方块实体的常规配对，Mek 自身即如此；本方案只要求**消除 `machine → block` 的 6 条**，使方向统一为 `block/blockentity → machine`。

## 五、三处循环依赖与解法

### 5.1 `machine → blockentity`（唯一一条边）

- **位置**：`machine/plantingcutting/PlantingCuttingFactoryTile.java:5` 导入 `blockentity/PlantingCuttingStationBlockEntity`。
- **解法（依赖倒置）**：在 `machine/ports/` 新增宿主接口（暂名 `IPlantingCuttingHost`），声明工厂需要的那几个方法；让 `PlantingCuttingStationBlockEntity` 实现它；工厂 tile 只依赖接口。
- **结果**：该边归零，`blockentity → machine` 成为单向边。
- **状态**：✅ 已实施（2026-10-05）。实现改用共享状态类 `machine/plantingcutting/PlantingCuttingStatus`（原计划名为 `IPlantingCuttingHost`，实测工厂只需 3 个常量、不需要宿主方法，故改用常量类）；legacy 站点保留同义常量并指向该单一定义源。

### 5.2 `util → block`（14 条中的 13 条源自一个类）

- **位置**：`util/TierInstallerHandler`（288 行，`@Mod.EventBusSubscriber`，语义实为**升级安装**）导入 13 个 `block` 类 + `machine`(2) + `registry`(1)。
- **解法**：整个类迁出 `util/`。**实测目的地是 `block/` 而非计划中的 `upgrade/`** ——
  迁 `upgrade/` 会新增 `upgrade → block` 13 条，与既有 `block → upgrade` 11 条成环；
  迁 `block/` 则 13 条 import 变同包直接消失，且新增边为 0（`block → machine`/`registry` 本就存在）。
- **结果**：`util → block` 归零。
- **状态**：✅ 已实施（2026-10-05）。

### 5.3 `block ↔ machine`（8 / 6）

- **方向**：定为 `block → machine`（`BlockTile` 需要 tile 类型，正是 Mek 模式）。
- **解法**：逐文件消除 `machine → block` 的 6 条（多为工厂 tile 反查档位方块），改从 `registry/` 或 `BlockTypeTile` 的类型参数取。
- **结果**：单向 `block → machine`。
- **状态**：✅ 已实施（2026-10-05）。6 条边同源（各 tile 的 `tierFromBlock()` 反查方块），
  抽 `machine/IFactoryTierProvider` 后 6 个工厂方块实现之、tile 只认接口，`machine → block` 归零。

## 六、`util` 通往 L1 的完整待处置清单（**修正计划中的批次 2 范围**）

原计划只迁 `TierInstallerHandler`，实测不足以让 `util` 成为干净 L1。完整清单：

| # | 类 | 现有越界依赖 | 建议去向 |
|---|---|---|---|
| 1 | ~~`TierInstallerHandler`~~ | ~~block 13 / machine 2 / registry 1~~ | ✅ **已迁 `block/`**（2026-10-05）。计划原定 `upgrade/`，实测会成 `upgrade ↔ block` 环，改迁 `block/` |
| 2 | `RecipeInputMatcher` | recipe 4 / registry 1 / compat 1 | `recipe/` |
| 3 | `RecipeCache` | compat 1（`TavernBarrelCompat`） | 保留 `util` 或下沉到 `compat/`（二选一，需评估） |
| 4 | `ChocolateCannonReservations` | blockentity 1 / entity 1 | `blockentity/`（与巧克力炮同域） |
| 5 | `ColdBrewHelper` | item 2 | `item/` 或 `upgrade/` |
| 6 | `FreezeEvents` / `FreezeAiReaper` | registry 1 / effect 1 | `event/`（均为 `@EventBusSubscriber`） |
| 7 | `ChocolateCannonLifecycle` / `ChocolateTagScanner` | 无越界，但语义属事件 | `event/`（均为 `@EventBusSubscriber`，**非死代码**，见 `01-util.md`） |
| 8 | `ClientPacketBridge` | compat 1（`GuideMECompat`） | 保留 `util`，或经 `api` 解耦 |
| 9 | `BioreactorFuels` | config 1 | `blockentity/` 或 `recipe/` |
| 10 | `AE2InputSpec` | 无越界 | `ae2/`（与 `INetworkPullable` 同域，计划已列） |
| 11 | `EnergyCubePreviewUtil` | 引用客户端符号？ | 待核实；若引用客户端类须迁 `client/` |

> ⚠️ 这是对重构计划批次 2 的**范围修正**：批次 2 应覆盖上表全部条目（可再拆 2–3 个小批），否则「`util` 只依赖 JDK/vanilla」的目标不成立。

## 七、验证方法（不要凭感觉）

1. **依赖方向**：以 import 计数实证（本文 §四的命令即口径），重构后重跑，直到违法边为 0（§四「违反=是」的行）。
2. **客户端隔离**：`client/` 之外除入口类外不得 `import cn.ism.mekck.client.*`。实测当前唯一真实代码泄漏是 `item/ItemAtomicKnife.java:69` → `client.atomic_knife.RenderPropertiesAtomicKnife`（`client/` 包外的实际链接点）；护栏 `client/TestNoClientSymbolsInCommonCode` 覆盖此面。
3. **编译 + 测试**：`./gradlew --offline test` 全绿 + 编译通过。

## 八、非目标

1. 不改包名 / 注册名 / lang 键 / NBT 键名；不动 refmap 与 mixin。
2. 不把 `menu/` 按机器重排目录。
3. 不迁制冰工厂（需改共享 `presetVariables`，无实机环境不动）。
4. 不删 `MekCkHeatComponent`（注释自述勿删）、`Reflect.missingMethod()`（哨兵）、`TemperatureHelper`（否定结论）。
5. 无实机环境时不动共享基类 `MekCkMachineTile` / `MekCkRecipeExecutor`。

# mekck 整体代码审查报告

- 日期：2026-09-29
- 范围：`D:\mc\mod\mekck` 全量（283 文件 / 85,514 行）
- 方法：4 个 agent 按**风险维度**并行深审（物品守恒 / 存档持久化 / 网络同步 / 注册与工具类），
  每条发现由我独立复核后才采纳
- 结论：**5 个 Critical 已修**，其余按置信度分级记录
- 备份：改动前的 `src/main/java` 已存于 `.backup-20260929-audit/`

---

## 一、已修复（4 项，均为静默数据丢失 / 复制类）

### F1 [Critical] 12 个方块的 `onRemove` 缺 `isUpgrading()` 守卫 → 升级时机器连同库存复制

`TierInstallerHandler.upgradeMachine` 的顺序是「保存旧 BE → 置方块 → 读入新 BE」，
而 `LevelChunk.setBlockState` 在**服务端、先于 BE 移除**调用 `onRemove`，
所以此时 `getBlockEntity(pos)` 仍能拿到旧机器。缺少 `!isUpgrading()` 时，
升级会在世界里放下新机器（带完整库存）**并**掉出一个同样带完整库存的旧机器物品。

已有守卫的 8 个方块形成对照，说明这是遗漏而非设计。

**修复**：对 12 个缺守卫的方块补上条件，与既有写法一致。
（agent 报告为 7 个，实测是 12 个：`CentralKitchenBlock` / `ChocolateCannonBlock` /
`ElectricGrindingMachineBlock` / `GrillBlock` / `IceMakerBlock` / `NutRoasterBlock` /
`PlantingCuttingStationBlock` / `SandwichAssemblerBlock` / `SkeweringMachineBlock` /
`SmartCookingPotBlock` / `UniversalCuttingMachineBlock` / `WineCellarBlock`）

### F2 [Critical] 中央厨房每 tick 重复扣除步骤流体

`CentralKitchenBlockEntity.tickOrder` 原本在流体校验通过后**立即** `consumeFluid`，
而下方 `tickStep()` 在加工完成前每 tick 都返回 false 并 `continue` 回到该段——
于是每 tick 扣一次，完成时再扣一次。一个 250 mB / 200 tick 的步骤会扣掉 50,000 mB，
而罐子只有 16,000 mB，会在半秒内见底，且扣掉的水一去不回。

**修复**：校验段只做校验不扣流体，扣除统一留在「本步骤完成」分支，与扣料同一时机。

### F3 [Critical] 中央厨房自动加工产物溢出被静默丢弃

`advanceThread` 丢弃了 `insertOutputs` 返回的 leftover，随后无条件重置线程。
料在开工时就扣了、能量每 tick 在烧，产物却凭空消失。
六面默认 `SideMode.NONE`（不会自动推出输出区），30 格输出槽一旦被占满，
这条自动加工线就会永远空转吃料。

**修复**：leftover 非空时保留线程状态，下 tick 重试插入（背压），不再丢产物。

### F4 [Critical] 生物反应堆掉落裸方块物品 → 18 格燃料 + 流体罐 + 能量全部蒸发

`BioreactorBlock.onRemove` 掉的是 `new ItemStack(this)`，不带任何 BE 数据，
而 `getDrops` 返回 `List.of()`——方块物品是状态的唯一载体。

**修复**：改为经 `machine.saveToItem(stack)` 序列化后再掉
（继承自 vanilla `BlockEntity` 的实现，会写完整的 `saveAdditional` 输出）。

---

## 二、误报（复核后否决，记录以免重复排查）

**「18 个方块调用了不存在的 `saveToItem`，干净构建必然失败」** —— **不成立**。

`BlockEntity.saveToItem(ItemStack)` 是 vanilla 1.20.1 自带方法
（`saveWithoutMetadata()` + `BlockItem.setBlockEntityData`），
所有这些 BE 直接继承 `BlockEntity`，编译通过、内容物也不会丢。
实测 `clean compileJava` → BUILD SUCCESSFUL。

这个误报的成因值得记住：**只跑增量编译无法验证跨类的引用**——
方块源码自 09-16 未变，class 是旧的，而 BE 侧被重编译掉了假设中的方法。
agent 用 `javap` 检查编译产物（同样是旧 class）得出结论，形成闭环误证。
**任何「编译不过」的断言都必须用 `clean` 验证。**

---

## 三、未修复项（按置信度分级）

### 已在 2026-09-29 第二轮修复（5 项）

| # | 位置 | 问题 | 提交 |
|---|---|---|---|
| I4 | `MultiFluidHandler:216,232` | `drain(FluidStack,…)` 每罐都按完整请求量抽 → **流体复制**；`drain(int,…)` 先抽后判流体类型，异种流体 break 时**已抽走的流体被丢弃** | `3021e38` |
| I5 | `SimpleMachineBlockEntity:4799` | `saveToItem` 手写 NBT，漏调 `AE2Compat.saveAdditional` / `PlacerPersist.save`（`load` 两个都读）→ 挖机再放下，ME 补料清单与放置器 UUID 必丢 | `290b3ef` |
| I6 | `BigStackDrops:31` | 掉落物走 vanilla `ItemStack.save`，`putByte("Count")` 截断。新增 `MixinItemStack` 让 `McCount` 旁路在**所有** NBT 往返生效 | `10b4f97` |
| I7 | `CentralKitchenBlockEntity:1116` | `saveAdditional` 不写 `threads` → 区块卸载时**已扣料的线程状态消失，材料永久损失** | `8ec8e7c` |
| — | `SimpleMachineBlockEntity` | 新发现：输入槽校验漏 `RecipeInputMatcher.matchesFoodCooking`（同 I3/I5 那一类「查找侧改了、校验侧没改」的静默失效） | `a4dc8cf` |

> I6 原描述「5000 变 136」有误：`getByte` 返回**有符号** byte，5000 往返后是 **-120**。
> 已由 `TestVanillaCountByteTruncation` 钉正。

### 仍未修复（需设计决策，非机械缺陷）

| # | 位置 | 问题 | 为什么不能顺手修 |
|---|---|---|---|
| **I1** | `SimpleMachineBlockEntity:350` 等 **17 个 BE** | `ContainerData` 经 `ClientboundContainerSetDataPacket` 走 **`writeShort`**（字节码已证），超 ±32767 即截断。`WineCellar` 容量 4 亿 → 客户端显示 −31,744 FE | **缩放修不了**：要装下 4 亿需除数 ≥ 12211，那样 4000 FE 的普通 Mek 机器会显示 0——用一个错换另一个错。Mek 之所以没这问题，是因为它**整个 jar 里引用 `ContainerData` 的类数为 0**，走的是另一套同步通道；照搬属架构改动。且这是**纯显示问题**，无数据丢失 |
| **I3** | `SimpleMachineBlockEntity:4478` | `SideMode.NONE` 落到 `default ->` 返回 `fullItemCapability`，六面默认全 NONE ⇒ 所有格六面全开，侧面配置失效 | 改成返回空会**断掉现有玩家**建立在「默认全开」上的全部管道/漏斗。且 `PULL_INPUT_STORAGE`（枚举第 4 值）**没有任何 capability 对应**——BE 里只有 full/input/output 三个，修它要先实现 storage capability |
| I2 | `GrillFactoryBlockEntity:1752` | 槽位迁移重建 NBT 漏写 `Size` 键 → 玩家升级时区块崩溃 | 属 A 组，按既定决策随 Mek 迁移一起处理 |

### 高置信度 Important（仍待处理）

| # | 位置 | 问题 |
|---|---|---|
| I8 | `MekckAe2:743, 541` | `extractAll` 的 `break` 使同一网络堆叠最多满足一个需求项；含重复材料的配方永远匹配失败（fail-safe，但玩家零反馈） |
| I9 | `SimpleMachineBlockEntity:3826` | `matchBakeriesOven` 把「过滤后」与「未过滤」的两个列表配对，`Ingredient.EMPTY.test()` 恒 false → 加工机**永久静默卡死**。需 `bakeries` 配方含空 Ingredient 才触发（该 mod 不在 libs/，未坐实可达性） |
| I10 | `MekCkTransfer:40` | `existing.getCount() + source.getCount()` 是 int，两个接近 `Integer.MAX_VALUE` 的堆叠相加溢出为负 → 两个堆叠同时消失。该 mod 正是为超大堆叠而建，此处恰好无防护 |


### 中置信度 / 需要设计决策

- `SimpleMachineBlockEntity:786` 先 drain 后 fill，fill 失败时桶里的流体已抽走不退还
- `SandwichAssemblerBlockEntity:495` 返还槽满时未放下的 `stack` 被丢弃
- `NutRoasterBlockEntity:630` 忽略 `extractItem` 返回值
- `CentralKitchenBlockEntity:1252` 自己的 item/fluid 侧配是死分支（两个分支返回同一对象）
- `MekCkUpgradeTracker:112` `load` 时按当前配置夹紧 installed → 改配置 + 区块重载 = 升级物品静默消失
- `BioreactorFuels:31` 破碎缓存按 `Level` 实例作键，`/reload` 不换 Level → 配方改动不生效

### Minor（记录，不建议本轮动）

`GrillFactoryBlockEntity:1406` 订单上限 int 溢出（仅显示）·
`ExtractingRecipe:193` 输出侧不支持 `#tag` 且抛裸异常 ·
`PlantingRecipeGenerator:116` 每次开服往存档根目录写调试标记文件 ·
`MeOrderTogglePacket` 注册了但无客户端发送 · `KitchenFilterSyncPacket:70` 客户端缓存
按 familyOrdinal 而非 pos 键控（同机双厨房会串）· 6 个工厂的「全部卸下」按钮忽略 `mode` 参数 ·
`AutoIO:142` 塞回失败余量被丢弃（竞态窗口）

### agent 明确查证后**排除**的（省得重复排查）

- `ItemStack.grow(int)` 在 Forge 47.4.16 中**不封顶**（`javap` 确认），所以各 `insertOutput*` 的合并是正确的
- `Properties.copy(BlockBehaviour)` 不复制 `lootTable`，`CentralKitchenBlock` 等不会因继承 `IRON_BLOCK` 而双重掉落
- 破坏再放回**不会**丢内容：`BlockItem.place` → `updateCustomBlockEntityTag` → `be.load`
- 多方块 `onRemove` 不会二次掉落（`LevelChunk.setBlockState` 先写区段状态再调 `onRemove`）
- `onRemove` 只在服务端调用，各处缺 `!level.isClientSide` 是安全的
- 全部 21 个菜单的 `stillValid` 都是 `<= 64.0D`，与 `PacketGuard` 一致 → 「GUI 开着 ⟺ 包被接受」无空隙
- `blockentity/` 全包 NBT 键级对称性扫描：**无**只写不读的不匹配

---

## 四、审查覆盖率（残余风险所在）

本轮 4 个 agent 各自声明未覆盖的部分：

- `client/` 全部约 35 个 Screen（约 15K 行）与渲染层——**未审**，desync 未覆盖
- `ae2/MekckAe2.java`（2358 行）的 `pushPattern` / AE2 CPU 任务状态机
- `factory/MekCkFactory*` 整套 Mekanism 风格工厂
- `SimpleMachineBlockEntity` 约 30 个 `matchXxx` 配方匹配函数的逐个对账（约 2000 行胶水代码）
- 五个大工厂 BE（`CookingFactory` 2099 / `GrillFactory` 1986 / `PlantingCuttingFactory` 1833 /
  `SmartCookingPot` 1633 / `SkeweringFactory` 1585）的 tick 主干与工艺算术
- `command/PlantingRecipeGenerator`（1702 行）、`UniversalCuttingMachine`（1826 行，基本是注册表）
- `mixin/`、`entity/`、`effect/`、`world/`、`advancement/`

**下一轮若要继续，建议顺序**：`matchXxx` 家族逐个对账扣料/返还 →
`MekckAe2.pushPattern` 与任务状态机 → 各 `menu/` 的 `quickMoveStack` 区间与 BE 槽位一致性 →
`client/` 的 Screen 同步。

---

## 五、验证

`./gradlew clean build --offline` → BUILD SUCCESSFUL，73 测试 / 0 失败 / 0 跳过。

**clean 编译是必须的**：本次审查中一个「编译不过」的 Critical 结论，
正是靠 clean 编译才被证伪。增量编译对跨类引用变化是盲的。

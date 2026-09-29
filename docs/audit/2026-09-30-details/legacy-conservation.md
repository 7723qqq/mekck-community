# 旧体系（blockentity/ + block/ + util/）物品·流体守恒深审

- 任务：task-2（owner = rev-legacy-conservation）
- 范围：`src/main/java/cn/ism/mekck/blockentity/**`（19 文件 / 20,264 行）、`block/**`（22 / 3,293）、`util/**`（50 / 6,902），外加 `block/` 对应的 60 个战利品表 JSON
- 基线：HEAD `83830e0`（阶段 3 Task 7）
- 本轮**未修改 src/ 下任何文件**、**未运行任何 gradle 命令**、**未提交任何东西**（只新增本报告）
- 方法：① 全文精读关键类；② 正则穷举 —— 全部 `put*/get*/contains` 调用点（键级 NBT 对称性）、全部语句级 `extract/insert/drain/fill/shrink/grow` 调用点（返回值丢弃）、`matchXxx` 的「过滤列表构造」与「消费循环」并排抽取；③ `javap` 反编译 Mekanism 10.4 jar 核对 Mek 的掉落/放置语义（只读，非 gradle）

**结论计数：Critical 1 / Important 4 / Minor 5 / 未经证实 2**

---

## 一、Critical

### C1【需设计决策（推荐方案 A 可机械执行）】阶段 2/3 迁到 Mek `BlockTile` 的工厂方块：破坏时**丢失全部内容**，其中烹饪工厂连方块本身都不掉

**位置**
- `src/main/java/cn/ism/mekck/block/CookingFactoryBlock.java:40-42`、`GrindingFactoryBlock.java:41`、`CuttingMachineFactoryBlock.java:33-35`、`SkeweringFactoryBlock.java:32`、`PlantingCuttingFactoryBlock.java:38-41`（注释均声称掉落已交给「`BlockMekanism.onRemove` + loot table」）
- `src/main/resources/data/mekck/loot_tables/blocks/*_factory.json`（55 个，全部是裸物品掉落）
- 烹饪工厂：**11–12 个档位一个战利品表都没有**（`data/mekck/loot_tables/` 全目录 60 个文件，无一含 "cook"）

**机制（全部由字节码/资源原文坐实，不是推测）**
1. Mek `BlockMekanism.onRemove`（SRG `m_6810_`）反编译后只有三件事：`AttributeHasBounding.removeBoundingBlocks` → `TileEntityUpdateable.blockRemoved()` → `super.onRemove`。**没有任何 `saveToItem` / `popResource` / 库存倾泻**；而 `TileEntityUpdateable.blockRemoved()` 的字节码**只有一条 `return`**（空实现）。
2. Mek 自己的机器靠**战利品表**保内容：`mekanism:blocks/enrichment_chamber` 使用 `minecraft:copy_nbt`，`source: "block_entity"`，targets 含 `mekData.Items` / `mekData.EnergyContainers` / `mekData.componentUpgrade` …（从 mekanism jar 内直接读出该 JSON）。
3. 本仓 55 个 `*_factory.json` 全部形如 `{"type":"minecraft:block","pools":[{"rolls":1,"entries":[{"type":"minecraft:item","name":"mekck:basic_grinding_factory"}]}]}`，**没有任何 functions**；全仓 `grep -rn copy_nbt src/` **零命中**。
4. 迁移后的工厂 BE（`machine/*/*FactoryTile extends MekCkMachineTile extends TileEntityConfigurableMachine`）都是 Mek `TileEntityMekanism` 子类，`machine/MekCkMachineTile.java:1039-1040` 的 `setRemoved()` 只 `super.setRemoved()`，`machine/` 全目录无 `blockRemoved`/`popResource`/`Containers.drop` 倾泻实现。

**触发条件**：用任意镐/爆炸破坏任意一个迁到 Mek 体系的工厂方块（背包里有物品、装了升级、罐里有流体都算）。
**后果**
- 55 个家族档位（cutting / grill / grinding / planting_cutting / skewering × 11 档）：掉一个**裸方块物品**，库存 + 能量 + 已装升级 + 侧配 + 工厂自有状态**全部静默蒸发**（掉落物是状态的唯一载体，`getDrops` 是唯一掉落路径）。
- 11–12 个烹饪工厂：**连方块都不掉**（无战利品表 + 无 `onRemove` 覆写），玩家挖一次即凭空损失一台机器。
**证据**：见上 4 条；`javap -p -c mekanism.common.block.BlockMekanism`（`m_6810_` / `m_49635_` / `m_6402_`）、`mekanism.common.tile.base.TileEntityUpdateable.blockRemoved()`、mekanism jar 内 `data/mekanism/loot_tables/blocks/enrichment_chamber.json`、`src/main/resources/data/mekck/loot_tables/blocks/basic_grinding_factory.json`（裸掉落）。
**建议修法**
- 方案 A（推荐，与 Mek 体系一致，纯资源改动）：照抄 Mek 的 `copy_nbt` ops 表写进 55 个战利品表，并**新建** 11–12 个烹饪工厂战利品表。注意 target 必须写在 `mekData.*` 下 —— Mek 的 `BlockMekanism.setPlacedBy`（`m_6402_`）会 `ItemDataUtils.getDataMapIfPresent(stack)` 把 `mekData` 回灌 tile（字节码已证）。若 `MekCkMachineTile` 另有自有键（threads/侧配等），一并补 ops。
- 方案 B：给这 6 个方块类恢复 `onRemove` + `machine.saveToItem(stack)` 并让 `getDrops` 返空（与 14 个旧方块一致）——属 Java 改动，且需先确认不会与 Mek 的 `getDrops` 双掉落。
**归属提示**：工厂方块由阶段 2/3 迁移方写成，可能与 T1（`machine/`）的结论重叠；但 artifact（战利品表 + 方块 onRemove 契约）落在我的 `block/` 范围，故在此立案。选方案 A 我可以直接执行。

---

## 二、Important

### P1【可机械修复】`util/MekCkTransfer.java:40` —— int 相加溢出，两个 21 亿级堆叠同时消失（= 上一轮 I10，本轮坐实可达）

- **机制**：`int total = existing.getCount() + source.getCount();` → `if (total <= capacity) { source.setCount(0); existing.setCount(total); }`。两个接近 2^31 的计数相加为负，`total <= capacity`（capacity 常为 `Integer.MAX_VALUE`）成立 ⇒ 源堆置 0、目标堆被写成**负数**。
- **触发条件**：Shift 点击搬运的材料，其源槽与本区已有同物槽的计数之和 > 2,147,483,647（例：存储槽 1,500,000,000 + 源 1,000,000,000）。
- **可达性证据（坐实）**：① 本仓槽位上限就是为超大堆叠而设 —— `util/CountMath.java:16` `MAX_COUNT = Integer.MAX_VALUE - 1`；② 机器输入/存储槽 `getSlotLimit` 直接返回 `Integer.MAX_VALUE`（如 `blockentity/SkeweringMachineBlockEntity.java:134-143`）；③ 本工具类 javadoc 自述用途是「把材料快速移动（Shift 点击）进 `Integer.MAX_VALUE` 上限的输入/存储槽」；④ `source` 既可能是玩家背包（≤64），也可能是机器槽（可达 21 亿）—— 机内区间之间的 Shift 搬运正好满足两堆都接近上限。
- **后果**：负数计数写回槽位，I6 的 `MixinItemStack`/`McCount` 会把负数原样存进 NBT，`util/BigStackItemHandler.java:173-175` 的 `readStack` 对 `count <= 0` 直接返回 `ItemStack.EMPTY` ⇒ **两堆物品在下次存档/区块重载后彻底消失**（合计 21 亿+ 件）。
- **建议修法**：改用已存在的 long 口径 `cn.ism.mekck.util.CountMath.canStack(existing.getCount(), source.getCount(), capacity)`，或把 `total` 声明为 `long` 后再 `existing.setCount((int) Math.min(total, capacity))`。
- **证据**：`util/MekCkTransfer.java:33-52`（源码原文）、`util/CountMath.java:16,57-59`、`blockentity/SkeweringMachineBlockEntity.java:134-143`。

### P2【可机械修复】`blockentity/SimpleMachineBlockEntity.java:4320-4323` —— 查找侧只验流体「量」不验「类型」，消费侧验类型 ⇒ 机器空烧电、永久静默停滞

- **机制**：`canAcceptOutputs()`（每 tick 的 canWork 判据）对 drainFluid 只比较 `inputTank.getFluidAmount() < recipe.drainFluid.getAmount()`；而提交前的 `validateInputsFor():4294-4297` 还多一条 `!inputTank.getFluid().isFluidEqual(recipe.drainFluid)`。两条判据不同 ⇒ canWork 为真时 `complete()` 会被 `canCommitRecipe`（4377）挡回。
- **触发条件（两条都可达）**：① `matchHeatedMultiInput`（3889-3938）在匹配阶段完全不看输入罐 —— 配方要 100 mB 水时罐里放 5000 mB 岩浆照样匹配；② 匹配结果被 `matchCached` 缓存，而缓存键 `inputFingerprint()`（1431-1446）只含物品槽 + juiceLevel/juiceType，**不含输入罐** ⇒ 「先装水匹配成功 → 再用管道换成岩浆（物品不动）」会复用旧配方。
- **后果**：`canWork` 恒真 ⇒ 每 tick `energy.extractEnergy(energyPerTick)` + `progress++`（1357-1373），到点 `complete()` 直接 return（不扣料、不出货），调用方随即 `progress = 0` ⇒ **无限循环空烧 FE、产物永不出现、ACTIVE 方块状态还亮着**，玩家零反馈（`noteWineryStall` 只在 WINERY 生效）。属能量侧的静默损失 + 卡死，不产生物品复制。
- **建议修法**：把 `canAcceptOutputs` 的 drainFluid 分支与 `validateInputsFor` 对齐，补一行 `if (!inputTank.isEmpty() && !inputTank.getFluid().isFluidEqual(recipe.drainFluid)) return false;`。
- **证据**：`blockentity/SimpleMachineBlockEntity.java:4310-4325` 与 `4279-4299` 两段原文并排、`3941-3987`（`heatedRecipeWater` 产 WATER 需求）、`1424-1446`（缓存键不含罐）。

### P3【可机械修复】`blockentity/SkeweringMachineBlockEntity.java:536-585` —— 签子「返还」无条件执行：`ingredientCount == 0` 时每次加工**凭空多出 1 根签子**；`toolCount > 0` 时返还余量被丢弃

- **机制**：`consumeInput(tool, toolCount)`（551）在 `toolCount == 0` 时不扣任何东西（`consumeInput` 循环条件 `remaining > 0`，587-607），但 570-581 无条件把**槽 0 物品的副本 ×1** 塞进 `RETURN_SLOT`：`ItemStack returnStack = toolStack.copy(); returnStack.setCount(1); insertOutput(items, returnStack, RETURN_SLOT);`（且返回值被丢弃）。
- **触发条件**：`barbequesdelight:skewering` 配方有 `tool` 字段且 `ingredientCount` 为 0（`ae2/MekckAe2.java:2401-2402` 明确写着「外部模组（BBQ Delight）那种『有 tool 字段、ingredientCount 缺省 0』的配方」，`machine/skewering/SkeweringFactoryExecutor.java:486-493` 的读取口也把缺省值定为 0）；再满足 `findRecipe` 的订单要求（449-456：**本机只在有 ME 订单时工作**）、槽 0 放签子、主料/辅料齐备。
- **后果**：每次完工向 `RETURN_SLOT` 加 1 根签子（`getSlotLimit(RETURN_SLOT) = Integer.MAX_VALUE`；`isItemValid` 返 false 只挡外部插入，机器内部走 `setStackToSlot` 直接写）⇒ **签子无限复制**，可由玩家或输出管道取出。反向分支：`toolCount > 0` 时若 `RETURN_SLOT` 被异类占位，`insertOutput` 的 remainder 被丢弃 ⇒ 真扣掉的签子收不回来（净损失）。同函数第三条：`consumeInput` 凑不够声明数量也照样出成品（`findRecipe` 只用 `Ingredient.test` 验存在性，不看数量）。
- **建议修法**：返还只在**实际扣了签子**时执行且返还数 ≤ 实扣数（让 `consumeInput` 返回实扣数量，`if (consumedTool > 0) returnStack.setCount(Math.min(1, consumedTool))`）；返还 remainder 非空时按 `SimpleMachineBlockEntity.insertReturn` 的兜底写法处理而不是丢弃。
- **证据**：`blockentity/SkeweringMachineBlockEntity.java:541-585`、`587-607`、`:56-70,133-143`；`ae2/MekckAe2.java:2388-2402`。
- **可信度限制**：BBQ Delight 的 jar 不在 `libs/`，无法离线核对它的配方字段取值；「`ingredientCount` 缺省 0 且 `tool` 非空」这一前提来自本仓注释（另一 agent 的 javap 结论），我未能独立复现。若不成立，复制路径不可达，但「返回值被丢弃 + 数量不足也出成品」两条仍成立。

### P4【可机械修复】`blockentity/SandwichAssemblerBlockEntity.java:495-508` —— 返还槽被异类物品占位时容器被静默销毁（上一轮已记录，本轮给出可达性证据：仍未修）

- **机制**：`insertReturn(ItemStack)` 只在 `RETURN_START..+3`（60..62）里找空位或同物堆；三个槽都不接受时**直接 return，容器就此消失**（无日志、无掉落）。
- **触发条件**：该 BE 的 `items`（`BigStackItemHandler`，113-124 行）**没有覆写 `isItemValid`** ⇒ 默认全部槽位接受任意物品；返还槽在 GUI/管道（`itemCapability` 暴露整个 handler）可被放入 3 种与容器不同的物品；此后每次 `consumeMaterials`（437-454）对每个带容器的材料调用 `insertReturn(container.copy())`（如奶桶→桶）即销毁一个。
- **后果**：容器（桶/瓶等）静默损失，加工仍在继续，玩家零提示。
- **建议修法**：`insertReturn` 返回未放下的余量，调用方按 `BigStackDrops`/`Containers.dropItemStack` 掉到世界里；或给返还槽加 `isItemValid`（只收容器类）从源头堵住占位。
- **证据**：`SandwichAssemblerBlockEntity.java:113-124`（无 isItemValid）、`56-63`（槽常量）、`437-454`（调用点）、`495-508`（丢弃点）。

---

## 三、Minor

### N1【可机械修复】`blockentity/CentralKitchenBlockEntity.java:576-599` —— `tickThreads` 全仓无调用者（死代码），其副作用「方块 ACTIVE 状态」随之永不执行
- 证据：`grep -rn tickThreads src/` 只有声明处 576 一处；`serverTick` 走的是 245 行的 `kitchen.tickAutoMode(level, pos, state)`；`CentralKitchenBlock.ACTIVE` 的**唯一写入点**在死的 `tickThreads` 内部（595-597）⇒ 中央厨房激活态模型永远不切换。
- 另注：`tickThreads` 里还有线程列表缩容（582 行 `while (list.size() > ability.threads()) list.remove(...)`），`tickAutoMode` 只扩容不缩容，拆模块后只能在区块重载时对齐。
- 建议：把 ACTIVE 同步与缩容搬进 `tickAutoMode`（推荐）或整体删除 `tickThreads`。

### N2【可机械修复】死代码（`blockentity/` 迁移残留），全仓引用统计坐实

| 位置 | 证据 | 说明 |
|---|---|---|
| `SimpleMachineBlockEntity.java:4253 matchIngredientsFrom` | `grep -rn matchIngredientsFrom src/` 仅声明 1 处 | 陈酿机已改「液位池为唯一来源」，该帮助方法无调用者 |
| `GrillBlockEntity.java:452 getBarbecuingTime` | 全仓仅声明 1 处 | 反射读 `barbecuingTime` 的旧路径已被固定时长取代 |
| `SkeweringMachineBlockEntity.java:397 insertIntoStorageOrInput` | 全仓仅声明 1 处 | 旧插入辅助，无人调用 |
| `SmartCookingPotBlockEntity.java:515 insertIntoStorageOrInput` | 全仓仅声明 1 处 | 同上 |

（`TestMekCkHeatIntegration` **不是**死代码：它是 `@GameTestHolder` gametest，由 gametest 框架发现，勿删。）

### N3【可机械修复】`util/MultiFluidHandler.java:142` —— `Count` 键只写不读（死键）
- 证据：`writeToNBT()` 写 `Tanks` + `Count`（141-142），`readFromNBT()` 只读 `Tanks`（149-158）。这是 91 文件「只写不读」穷举里唯一剩下的真死键；其余命中都是 vanilla 内部键（`Size`/`Items`/`Slot`/`Count(byte)`）或刻意的旧档兼容回退。无功能影响。

### N4【可机械修复】`block/WineCellarBlock.java:82-90` —— 唯一「有 BE、无 `getDrops` 覆写、也无战利品表」的旧方块（当前行为正确，结构上是陷阱）
- 事实：`data/mekck/loot_tables/blocks/wine_cellar.json` 不存在，掉落靠 `onRemove` + `cellar.saveToItem(stack)`（82-88 行，含 `isUpgrading()` 守卫）⇒ **今天不双掉落、内容不丢**。
- 风险：一旦有人补 `wine_cellar.json`（或改用 `Properties.copy(...)` 继承战利品表），就会与 `onRemove` 双掉落/覆盖。建议要么补 `getDrops → List.of()` 与其余 14 个方块对齐，要么补带 `copy_nbt` 的战利品表并删除 `onRemove` 掉落（二选一）。

### N5【无需修复，仅记录】`util/BigStackDrops.java:12-20` javadoc 事实错误
- 注释称「`Containers.dropItemStack` 会按 `getMaxStackSize()`（64）把堆叠拆成多个物品实体 —— 一次掉落就会瞬间生成 3355 万个实体」。vanilla 1.20.1 的 `Containers.dropItemStack` 只创建**一个** `ItemEntity`（`Level.addFreshEntity`），并不分堆 ⇒ 该注释的因果不成立（行为结果等价，无守恒影响），但它是「不能用原版」的理由，容易误导后续改动。

---

## 四、`matchXxx` 家族对账表（35 个匹配函数，`SimpleMachineBlockEntity`）

「查找侧」= 决定能不能做的条件；「消费侧」= `complete()` 真扣料用的 `consumeSlots/consumeCounts/inputIngredients`；「一致？」聚焦 `Ingredient.EMPTY` 是否被配对。

| 函数 | 查找侧条件 | 消费侧条件 | 一致？ | 结论 |
|---|---|---|---|---|
| matchSushi:1699 | `matchIngredients(required)`（跳过空成分；槽 0 可作底材） | 2 参构造 → `inputIngredients=null`，每槽扣 1 | 是 | OK |
| matchSlicer:1751 | `ings.get(0).test(in)` + `matchIngredients(List.of(ings.get(0)))` | 2 参构造 → null | 是 | OK |
| matchBreadKnife:1784 | `ings.get(0).test(slot0)`（EMPTY 恒 false ⇒ 跳过） | `singletonList(0)` + `singletonList(ings.get(0))` | 是 | OK |
| matchRice:1821 | `matchIngredients(ings)`（滤空） | 2 参构造 → null | 是 | OK |
| matchCurd:1843 | 物品 id 硬映射 → 回落 matchMeadowCheese/matchCompacting | 各分支自带 | 是 | OK |
| matchCompacting:1874 | `ings.size()==1 && !isEmpty && test(slot0) && slot0.count >= result.count` | `counts=[result.count]`，`inputIngredients=ings` | 是 | OK |
| matchMeadowCheese:1908 | 滤空 required + RecipeMatcher + 非空槽数相等 | consumeSlots 由 match[] 映射，inputIngredients=required | 是 | OK |
| matchDry:1953 | `ings.get(0).test(in)` + matchIngredients | 2 参构造 → null | 是 | OK |
| matchFarmDrying:1978 | `ings.get(0).test(slot0)` | `singletonList(0)` + 同 ings.get(0) | 是 | OK |
| matchFerment:2001 | 物品 matchIngredients + 流体类型/量/输出容量 | 5 参构造；drain/fill 由 validate/canAccept 复核 | 是 | OK（P2 的罐缓存问题作用于本函数） |
| matchExtractor:2055 | `matchExtracting` → `create:mixing` + `isExtractorRecipe` + 输出罐预检 + 输入流体预检 | 5 参构造 | 是 | OK |
| matchExtracting:2100 | matchIngredients + 输入流体（含 #tag）量/型 + 输出罐容量 | 同左 | 是 | OK |
| matchBeverageAssembly:2196 | matchIngredients + 罐内同型足量 | 同左 | 是 | OK |
| matchPackaging:2225 | matchIngredients | 同左 | 是 | OK |
| matchDrinkBeerBrewing:2250 | 滤空 required + 杯 Ingredient + 槽数相等 + RecipeMatcher | `counts=[1…, cup.count]`，inputIngredients=required | 是（计数只消费侧查） | 匹配不看杯数 ⇒ 不足时 complete 拒绝（停滞，不丢料） |
| matchBreweryBrewing:2318 | 滤空 required + 槽数相等 + RecipeMatcher | inputIngredients=required，每槽 1 | 是 | OK |
| matchBakeriesFermentation:2369 | 滤空 required + 槽数相等 + RecipeMatcher | `counts=[水桶?0:1]`，同序 | 是 | OK（水桶例外两侧同判） |
| matchSteam:2422 | `ings.get(0).test(in)` + matchIngredients | 2 参构造 | 是 | OK |
| matchWinery:3475 | 滤空 required（+空酒瓶）+ matchIngredients + 液位池同型足量 | inputIngredients 与 slots 同序；juiceLevelAfter | 是 | OK |
| matchJuicer:3531 | grape_pressing → vinery → pressing_tub | 各分支自带 | 是 | OK |
| matchGrapePressing:3588 | 按 countAt(i) 跨槽凑数（take=min(count,need)） | consumeCounts 同源、同序 | 是 | OK（数量两侧都查） |
| matchVineryJuice:3628 | `ings.get(0).test(in)` + bottleForJuicer（要瓶必须找得到） | fSlots/slotIngs 同序 | 是 | OK |
| matchBakery:3753 | matchIngredients(ings) | 2 参构造 | 是 | OK |
| matchBakeriesCoffee:3779 | 滤空 required，按槽位有序，且 required 之后必须全空 | consumeSlots=[0..n-1]，inputIngredients=required | 是 | OK |
| **matchBakeriesOven:3821** | `matchIngredients(ings)` ← **过滤后** | `inputIngredients = ings` ← **未过滤** | **否** | **= 上一轮 I9，仍未修**：`Ingredient.EMPTY.test()` 恒 false ⇒ validateInputsFor 永假 ⇒ complete 永不提交（静默卡死）。本轮结论：全仓**仅此一处**是该形态 |
| matchStove:3860 | matchIngredients(ings) | 2 参构造 | 是 | OK |
| matchHeatedMultiInput:3890 | 滤空 required + 槽数相等；**完全不看输入罐** | consumeSlots 由 match[] 映射；drainFluid=水，交给 P2 的罐校验 | 是 | OK（罐问题见 P2） |
| matchBakeriesStoneKiln:3993 | `ings.get(0).test(slot0)` | singletonList(0) + 同 ings.get(0) | 是 | OK |
| matchTea:4049 | 滤空 required + 槽数相等 + RecipeMatcher + isSimplyTeaResult | inputIngredients=required | 是 | OK |
| matchBlender:4120 | 滤空 required + 容器 Ingredient + 槽数相等 + RecipeMatcher | inputIngredients=required | 是 | OK |
| matchShaker:4176 | 滤空 required + 品质校验 + 槽数相等 + RecipeMatcher | inputIngredients=required | 是 | OK |
| findOrderedRecipe:1486（下单路径） | recipeInputSpecs + 每槽只用一次 | 5 参构造 → inputIngredients=null，每槽 1 | 是 | OK；但 recipeInputSpecs 只覆盖 10 个 kind ⇒ FERMENTER 等下单必回 null（功能缺口，非守恒） |
| canMatchFromInputs:1613 / getMaxConsumableCountForOrder:1636 | 与 findOrderedRecipe 同口径 | — | 是 | OK |

**结论**：35 个匹配函数里**只有 I9（matchBakeriesOven）**存在「过滤列表 vs 未过滤列表」错配；另发现 P2（流体判据分裂）与两处「查找不看数量、消费按数量」（matchDrinkBeerBrewing 杯数、Skewering 签子数 —— 后者已立案 P3），其失效模式是**停滞或数量不足仍出成品**，不是「扣了料不出货」。

---

## 五、已核实**无问题**（含上一轮遗留项复核，避免重复排查）

1. **F1 修复完整**：14 个自研 BE 方块的 `onRemove` 全部同时具备 `!state.is(newState.getBlock())` + `!TierInstallerHandler.isUpgrading()` + `saveToItem` + `getDrops → 空`；BioreactorBlock 已改 `saveToItem`。**无漏网方块**。
2. **NBT 键级对称性（扫描面扩到 block/ + util/）**：91 个文件全部做「写了不读 / 读了不写」穷举，唯一真死键是 N3（`MultiFluidHandler.Count`）；其余命中均为 vanilla 内部键或刻意的旧档兼容回退（WineCellar `Prog0..8`、各 `decodeSideConfig` 版本迁移）。
3. **I5 修复完整（三路径键集一致）**：`SimpleMachineBlockEntity` 的 `saveAdditional`(4690-4739) / `saveToItem`(4799-4852) / `load`(4742-4796) 键集一一对应，`saveToItem` 已含 `AE2Compat.saveAdditional` + `PlacerPersist.save`（4841-4842）。唯一差异是 `saveToItem` 不调 `super.saveAdditional`（不写 id/x/y/z/ForgeData）—— 该 BE 不使用 `getPersistentData()`，放置时经 `BlockItem.updateCustomBlockEntityTag → load`，无影响。
4. **`extractItem` 返回值被忽略但不可达丢失**：`NutRoasterBlockEntity:631`、`ElectricGrindingMachineBlockEntity:597`、`GrillBlockEntity:512`、`UniversalCuttingMachineBlockEntity:441` 都在同一 tick 先匹配成功（配方匹配 ⇒ 槽内确有该物品）；`ChocolateCannonBlockEntity:737-738` 的 `FerreroRecipe.matches` 同时校验槽 0/1（recipe/FerreroRecipe.java:74-76）；`:969` 前置 `getCount() < ammoCost` 检查（930-931）；`IceFactoryBlockEntity:855`、`IceMakerBlockEntity:1087`、`NutRoasterBlockEntity:814` 前置 `isEmpty()` 检查。
5. **`SimpleMachineBlockEntity:786`「先 drain 后 fill」不是丢失**：`sim=handler.drain(MAX,SIMULATE)` → `filled=inputTank.fill(sim,SIMULATE)` → `real=handler.drain(filled,EXECUTE)`（≤filled）→ `inputTank.fill(real,EXECUTE)`；Forge 物品能力把 `getContainer()` 作为唯一回写途径（`FluidBucketWrapper` 只在自身字段换栈，不改原 ItemStack），同 tick 罐状态不变 ⇒ 最坏 `done==0` 时 return false，桶保持原样。
6. **`AutoFluidIO:65-83` / `FluidContainerInteract:119-129`**：都是「SIMULATE 定量 → EXECUTE 定量」或「先 fill 邻居、再 drain 自己且 drain 量取自 fill 返回值」，同 tick 无并发 ⇒ 无复制/丢失。
7. **`AutoIO.pullWithTargets:129-150`（上一轮记为「塞回失败余量被丢弃（竞态窗口）」）**：`want` 由对本机槽的 `insertItem(...,true)` 预演得出，`got=adj.extractItem(want)` 后立刻 `items.insertItem(got)`；同 tick 本机槽不会被第三方改动 ⇒ `rest` 恒空，`adj.insertItem(i, rest)` 实际不可达。只有相邻 `adj` 违反 `extractItem(amount)` 契约（返回多于 amount）才会走到塞回分支，本仓无此实现 ⇒ 维持「不可达」判定（未来接第三方容器需改成失败即掉落）。
8. **升级收支平衡**：`UpgradeHelper.install`（setCount(1) 返 1 / `toAdd=min(count,space)` 返 toAdd）、`MekCkUpgradeTracker.tick`（`canAdd=min(count,max-installed)` 后 shrink 同值）、11 个 `addUpgradesFromHand` 实现、`TierInstallerHandler:180` 的 `held.shrink(1)` —— 全部「装进去几件、手上扣几件」一致。
9. **`PowerSlotUtil.drain`（红石→FE）**：`consumed = added / 1000`（向下取整），永不多扣物品；物品→能量顺序是「先 target.receiveEnergy 再 src.extractEnergy(got)」，无凭空能量。
10. **`BigStackItemHandler.bulkInsert/bulkExtract` 与 `FastTransfer.bulkTransfer`**：`inserted` 累加量 = 每槽实际写入量（`newCount = min(min(limit,MAX), cur+move)` 且 `move ≤ space = limit-cur`）；`FastTransfer` 的「先真插、后真抽」由同一 dryRun 预演量约束，同 tick 无中间态丢失。
11. **SimpleMachine WINERY 事务守恒**：`commitTavernFinishConsume:2571-2603`（持守校验失败即中止、不扣不退）、`commitTavernDispense:2704-2724`（同一计划、先复核后提交、失败进故障）、`absorbJuiceFluidFromSlot:3205-3248`（两段 SIMULATE 预演 + 副本抽桶 + 空容器去处预检）、`insertReturn:3387-3403`（两道余量预演 + 兜底不吞瓶）—— 未发现新的静默丢失。
12. **11 个 BE 的 `ContainerData.get` 都有 `level.isClientSide → return stored[index]` 分支**（逐个核对：Bioreactor/ChocolateCannon/ElectricGrinding/Grill/IceFactory/IceMaker/NutRoaster/PlantingCuttingStation/SimpleMachine/Skewering/SmartCookingPot/UniversalCutting/WineCellar）⇒ 不存在「set 写入被 get 丢弃」的客户端失效形态。
13. **blockentity 19 个类全部有外部引用**（全仓 grep 统计）；`TestMekCkHeatIntegration` 是 gametest，非死代码。
14. **`TavernBrewBatch` 瓶数账目**：`bottles = min(配料数, 16)`，`canDispense` 需「满级 + consumed + remaining>0」，`consumeOne` 逐瓶递减并归零即 reset ⇒ 出货瓶数不可能超过计划瓶数。

---

## 六、未经证实（不计入结论）

1. **BBQ Delight 的 `barbequesdelight:skewering` 配方字段取值**：P3 的复制路径以「有 `tool` 字段且 `ingredientCount == 0`」为前提，该前提只见于本仓注释（`ae2/MekckAe2.java:2401-2402`、`machine/skewering/SkeweringFactoryExecutor.java:486-493`），`libs/` 里没有该 mod 的 jar，无法用字节码独立复核。若前提不成立，P3 只剩「返回值丢弃 + 凑不够也出成品」两条（仍属缺陷，严重度降为 Minor）。
2. **第三方 `IItemHandler` 违反契约**（`extractItem(slot, amount)` 返回超过 amount）时 `AutoIO.pullWithTargets:145` 的塞回会丢件：本仓无此实现，无法构造可复现路径。

---

## 七、给用户的决策输入（I1 / I3 相关，本轮**未改**）

- **I1（`ContainerData` 走 `writeShort` 截断）**：本轮未发现改变结论的新证据。补充一条有利事实：这些 BE 的 `get` 在客户端一律 `return stored[index]`、`set` 写 `stored[index]`，所以截断只影响**显示**，与存档/逻辑无关（`SimpleMachineBlockEntity.java:341-376`）。
- **I3（`SideMode.NONE` 落到 default 返回全权限）**：`block/` 与 `util/` 未找到新的可行性证据。补充：`AutoIO:96-104` 的 `SideMode.NONE → continue`（不主动抽取/弹出）与 `getCapability` 的默认全开是两套口径；若将来改成「NONE = 对外不可见」，必须同时核对 `AutoIO`/`AutoFluidIO`/`AutoGasIO` 三处 continue 分支，否则会出现「配置成 NONE 反而被自动 IO 抽走」的新矛盾。

---

## 八、范围文件覆盖清单（91 个文件）

标记：**通读** = 全文读完；**重点+扫描** = 守恒相关段落全读 + 全文件正则穷举（`put*/get*/extract*/insert*/drain/fill/shrink/grow` 全部调用点逐个核对上下文）；**仅扫描** = 只做正则穷举与类级引用统计。

### blockentity/（19）

| 文件 | 行数 | 状态 |
|---|---|---|
| SimpleMachineBlockEntity.java | 4853 | 重点+扫描（1-700 / 900-1180 / 1560-1700 / 2500-2540 / 2620-2660 / 2770-3180 / 3300-3470 未逐行读，已用正则穷举覆盖全部守恒调用点） |
| CentralKitchenBlockEntity.java | 1403 | 重点+扫描 |
| SmartCookingPotBlockEntity.java | 1633 | 仅扫描 |
| IceMakerBlockEntity.java | 1395 | 重点+扫描 |
| ChocolateCannonBlockEntity.java | 1363 | 重点+扫描 |
| SkeweringMachineBlockEntity.java | 1187 | 重点+扫描 |
| IceFactoryBlockEntity.java | 1072 | 重点+扫描 |
| PlantingCuttingStationBlockEntity.java | 1030 | 重点+扫描 |
| NutRoasterBlockEntity.java | 1018 | 重点+扫描 |
| ElectricGrindingMachineBlockEntity.java | 1027 | 重点+扫描 |
| GrillBlockEntity.java | 961 | 重点+扫描 |
| UniversalCuttingMachineBlockEntity.java | 898 | 重点+扫描 |
| SandwichAssemblerBlockEntity.java | 760 | 重点+扫描 |
| BioreactorBlockEntity.java | 629 | 仅扫描（能量/流体/燃料扣减调用点 368/419/496/541 已逐条核对） |
| TavernBrewBatch.java | 436 | 重点+扫描（瓶数账目 152-291 已读） |
| WineCellarBlockEntity.java | 375 | 通读 |
| TavernBarrelPlan.java | 121 | 仅扫描 |
| TestMekCkHeatIntegration.java | 73 | 通读（gametest，非死代码） |
| IRedstoneControllable.java | 17 | 通读（纯接口） |

### block/（22）
全部 22 个文件都做了「`onRemove`/`getDrops`/`playerDestroy`/`saveToItem`/`isUpgrading`/`new ItemStack(this)`/`popResource`/`createBlockEntity`」全量正则体检，并与 60 个战利品表交叉比对；其中 `WineCellarBlock`、`SkeweringMachineBlock`、`SimpleMachineBlock`、`SmartCookingPotBlock`、`CookingFactoryBlock`、`ChocolateCannonBlock` 逐个通读。6 个 Mek 体系工厂方块（Cooking/Cutting/Grinding/Grill/Skewering/PlantingCutting）用「注释 + 继承链 + 战利品表 + Mek 字节码」四方交叉核实（C1）。**无「已看/未看」空白**。

### util/（50）
| 状态 | 文件 |
|---|---|
| 通读（14） | MekCkTransfer、CountMath、StorageMerger、FastTransfer、AutoFluidIO、AutoIO、FluidContainerInteract、IntHandlerBulkView、PowerSlotUtil、BigStackDrops、MekCkUpgradeTracker、MultiFluidHandler、BigStackItemHandler、KaleidoscopeGrillingCompat |
| 重点+扫描（6） | UpgradeHelper、UpgradeInstallHandler、TierInstallerHandler、AE2Compat、RecipeCache、MekCkHeatComponent |
| 仅扫描（30） | FluidIngredientHelper、BarbequesDelightCompat、MekCkMultiblock、IceTargetSearch、ChocolateTagScanner、MaxLootRandom、AE2InputSpec、NetworkPullHelper、ChocolateCannonLifecycle、IceAndFireCompat、ColdBrewHelper、FreezeEvents、TeleportFxUtil、MatchKey、ChocolateCannonReservations、KaleidoscopeCompat、TemperatureHelper、Directions、AutoGasIO、LagMonitor、GuideMECompat、FerreroUpgradeProfile、VineryJuice、TavernBarrelCompat、EnergyCubePreviewUtil、MachinePreviewCompat、BioreactorFuels、WineAgeCompat、Reflect、RecipeInputMatcher |

**未覆盖（明确声明）**：`ae2/MekckAe2.java` 的 AE2 部分（归 T3）；`client/`、`menu/`、`recipe/`、`factory/`、`machine/` 全部；爆炸/活塞破坏路径未构造实机验证；P3/C1 的实际可达性依赖外部 mod（BBQ Delight）或资源文件，未在游戏内实测。

---

## 九、需要 Lead 执行的命令（我不跑 gradle）

1. 若批准修复：**每条修复后**跑 `./gradlew clean build --offline`（本仓约定：跨类引用只能靠 clean 验证）。建议一并加两个回归测试：
   - `MekCkTransfer.moveItemStackTo` 溢出用例（existing=1_500_000_000 / source=1_000_000_000 → 期望不把负数写回槽位）；
   - `SkeweringMachineBlockEntity.completeRecipe` 在 `ingredientCount=0` 的配方下 `RETURN_SLOT` 不增长。
2. C1 若选方案 A（战利品表），除 clean build 外请在**游戏内**实测一次：放置工厂 → 塞物品/装升级 → 挖掉 → 再放下，确认库存与升级回来（纯资源改动，编译测试覆盖不到）。

---

## 十、残留风险 / 下一步建议

- C1 的影响面（67 个方块）远超本报告其余条目，且**当前注释把错误结论写成了设计依据**（「交给 Mek 的 onRemove + loot table 接管」）⇒ 建议优先裁决。
- P1（I10）与 P2 都是**一行级**修复，建议本轮直接改。
- P3 的修法需要先确认 BBQ Delight 的字段语义，否则可能把「本就不该消耗的签子」改成误扣。
- 本轮**没有**再发现第二个 `matchBakeriesOven` 形态的错配；35 个 `matchXxx`（约 2000 行胶水代码）的对账已按上表完成，可作为下一轮基线。

---

## 十一、批 1 修复记录（Lead 指令后执行，2026-09-30）

> 执行范围：Lead 批 1（A 战利品表 + P1/P2/P4 + N1–N4 小修）。**未运行 gradle、未提交**，
> 全部改动待 Lead 统一 `clean build` + 测试。

### A. 工厂战利品表补齐 + `copy_nbt`（对应 C1 方案 A）

- **新建 17 张**：5 个家族的 `blaze_*`（cutting/grinding/grill/planting_cutting/skewering）+ 烹饪工厂全 12 档。
- **改写 55 张**：每张在裸物品掉落基础上加 `minecraft:copy_name(source=block_entity)` 与
  `minecraft:copy_nbt(source=block_entity, ops=[...])`，ops 共 **13 条**、target 全部 `mekData.*`：
  Mek 标准 6 条（`componentUpgrade`/`componentConfig`/`componentEjector`/`controlType`/`EnergyContainers`/`Items`）
  + MekCK 自有 **7** 条（`MekCkSlots`/`mekckExecutor`/`MekCkWorkProgress`/`MekCkNative`/`GasTank`/`FluidTanks`/`MekckPlacerUuid`）。
  已按 Lead 要求删去 Mek 模板里本模组没有的 `componentSecurity.*` 与 `sorting`。
  **键名已与 T1 双向闭环**：T1 回信确认 6 个键名「一字不差」，并已把它们钉成断言
  （`TestMekCkPersistedSlotCoverage#mekckPersistentKeysMatchTheLootTableContract`）；
  source 一律用**裸键名**（六个键都写在 BE 根标签，无前缀），与 T1 给的层级完全一致
  （`FluidTanks` 是根键、罐数组嵌在其内的 `Tanks`，没有额外包装层）。
  第 13 条 `MekckPlacerUuid` 是 T1 建议加的（`PlacerPersist.KEY_UUID`，`putUUID` 存成 int-array；
  生成侧 `MekCkMachineTile.java:938` 写在根、读侧 `MekCkMachineTile.java:1077` `PlacerPersist.load`）——
  少它就会丢放置者归属。
  AE2 节点键（`MekckAe2Main`/`MekckAe2Extra1..7`/`MekCkAutoSel`）按 T1 结论**刻意不搬**（拆机时节点已销毁，重新放置应重新入网）。
- **目录内 `*_factory.json` 总数 55 → 72**；`mekck:<tier>_<family>_factory` 6 家族 × 12 档全覆盖。
- **Ice 工厂不建**：`UniversalCuttingMachine.ICE_FACTORY_ENABLED = false`（第 332 行）⇒ 注册整段跳过
  （1205–1231），方块不存在即不需要战利品表。
- 保留原有的 `survives_explosion` 条件（爆炸仍然毁掉机器与内容）——本次不改该语义。

### B. 代码修复

| 项 | 文件:行 | 改法 |
|---|---|---|
| P1（I10） | `util/MekCkTransfer.java:40` | 合并计数改 `long total = (long)a + (long)b`，收窄处 `(int) total`（能进该分支必有 total ≤ capacity） |
| P2 | `blockentity/SimpleMachineBlockEntity.java:4300` 附近 | `canAcceptOutputs` 的 drainFluid 分支补 `isFluidEqual` 判据，与 `validateInputsFor` 逐字同口径（只判量会让 canWork 恒真 → 空烧电 + 永久停滞） |
| P4 | `blockentity/SandwichAssemblerBlockEntity.java:495-514` | 三个返还槽都放不下时把返还物掉到世界上（`BigStackDrops.dropAbove`），不再静默销毁 |
| N1 | `blockentity/CentralKitchenBlockEntity.java` | 删掉死方法 `tickThreads`；把它的 ACTIVE 同步搬进 `tickAutoMode` 末尾（激活态恢复）。**刻意不迁移缩容逻辑**：tick 路径上缩容会删掉正在加工的线程（材料已扣） |
| N2 | SimpleMachine / Grill / Skewering / SmartCookingPot | 删除 4 个无调用者的私有方法（`matchIngredientsFrom`、`getBarbecuingTime`、两个 `insertIntoStorageOrInput`）与 2 组未使用的 `IO_PULL_RANGES`/`IO_PUSH_RANGES` 字段 |
| N3 | `util/MultiFluidHandler.java:142` | `Count` 死键加注释标注（保留写出以稳定旧存档键集合） |
| N4 | `block/WineCellarBlock.java` | 补 `getDrops → List.of()`，与其余 14 个自研方块同一契约，堵住「将来补战利品表就双掉落」的陷阱（今天是空操作） |
| N5 | `util/BigStackDrops.java:12-20` | 订正 javadoc：vanilla `Containers.dropItemStack` 只生成一个实体，旧注释的「拆成 3355 万个」与源码不符 |

### C. 新增回归测试（2 个）

1. `src/test/java/cn/ism/mekck/util/TestMekCkTransferOverflow.java` —— P1：15 亿 + 10 亿的合并必须守恒、
   不得写出负数计数（修复前三条断言全挂）。
2. `src/test/java/cn/ism/mekck/block/TestFactoryLootTableSustainData.java` —— A：
   ① 6 家族 × 12 档表齐全；② 每张表掉的正是自己那个方块；③ 每张表含 `copy_name` + `copy_nbt`，
   13 条 ops 齐全且 target 全带 `mekData.` 前缀；④ ice_factory 仍未注册（断言不存在其表）。
   本地已用脚本复刻这 4 条断言，72/72 全部通过。

### D. 本轮新发现（未修，留给下一批 / 需设计决策）

- **中央厨房移除模块时，正在加工的线程会连同已扣材料一起消失**：`load` 对「存档里有线程但该系列模块已不在机器里」
  直接 `continue`（`CentralKitchenBlockEntity` 线程恢复段），而正在加工的线程在 `saveAdditional` 里是被写出的
  ⇒「拆掉模块 → 区块卸载 → 重载」＝材料凭空损失。修法三选一（拒绝拆模块 / 拆模块时把 `outputs`+剩余材料吐回 / 
  卸载时把线程结算为产出），属设计决策，未动。
- **`ice_factory` 预留**：若将来把 `ICE_FACTORY_ENABLED` 改成 true，需要同时补 12 张战利品表
  （本模块族的方块 / BE / 菜单注册、tile 的 `mekData` 键、本测试的 `FAMILIES` 列表），否则会重演 C1。

### E. 跨 agent 对齐（**已闭环**）

- 权威契约 = T1 代码里的注释 `machine/MekCkMachineTile.java:1036-1045` + T1 的回信
  （message team-message-fc961123）。逐条比对：`MekCkSlots` / `mekckExecutor` / `MekCkWorkProgress` /
  `MekCkNative` / `GasTank` / `FluidTanks` **6/6 逐字相同**，source 用裸键名（BE 根），target 用 `mekData.<同名>`；
  第 7 条 `MekckPlacerUuid` 已补。T1 侧另有断言
  `TestMekCkPersistedSlotCoverage#mekckPersistentKeysMatchTheLootTableContract` 钉同一份契约。
- T1 的 `writeSustainedData` 是**有意空实现**（它同时被配置卡的 `getConfigurationData` 调，
  写内容键会让「配置卡复制 → 反复粘贴」变成整机库存复制漏洞）——这反过来证明「掉落 → 再放置」
  只能靠战利品表 `copy_nbt`，与本批修法互为印证。
- T1 的放置侧次序（`setPlacedBy` 偏移 299~361 读 Mek 的 byte 下标 `Items`，364~391 才调 `readSustainedData`）
  意味着 `MekCkSlots` **最后落地**，≥128 号槽不会被 `Items` 覆盖 —— 与本批同时搬 `Items` 不冲突。
- T1 后续又加了一道**读侧载荷闸** `hasMekckKeys`（`MekCkMachineTile.java:1117-1125`）：`readSustainedData`
  只在 `MekCkSlots`(COMPOUND) / `mekckExecutor`(COMPOUND) / `MekCkWorkProgress`(INT) / `MekCkNative`(INT)
  任一存在时动手（挡配置卡粘贴误清订单 / AE2 勾选）。**已独立核对**：这四个键都在我的 13 条 ops 里，
  生成侧写法是 `tag.put(TAG_SLOTS, …)`/`tag.put(TAG_EXECUTOR, …)`/`tag.putInt(TAG_WORK_PROGRESS, …)`/
  `tag.putInt(TAG_NATIVE_VERSION, …)`，而 `copy_nbt` 的 `replace` 是整标签原样拷贝 ⇒ 类型保持 COMPOUND/INT，
  闸门条件成立，恢复链不会静默失效。
  ⚠️ 残留风险（在 T1 侧）：若这四个键的**标签类型**将来变了（如 WorkProgress 换成 LongTag），
  闸门会静默判否 ⇒ 退化成「挖机再放下什么都不恢复」；`TestFactoryLootTableSustainData` 钉不住这一层（它只看 JSON）。
  T1 另已独立交叉验证我的 72 张表（bad=0），写侧/读侧互为镜像。
- 若任一侧将来改名：我这边是脚本级一次性替换（72 张表 + 测试里 1 个常量列表），约 1 分钟。


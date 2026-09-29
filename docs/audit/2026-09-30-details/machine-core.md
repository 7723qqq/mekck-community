# machine/ 新机器内核 深度审查报告（阶段 2/3）

- 审查者：rev-machine-core（共享任务 task-1）
- 基线：HEAD `83830e0`（阶段 3 Task 7）
- 范围：`src/main/java/cn/ism/mekck/machine/**`（20 文件）+ `config/MekckConfig.java`
- 证据手段：源码逐行读 + `javap -p -c` 直读 Mekanism 10.4.6.20 / 本项目 `build/classes` 字节码 + `git show` 取已删旧 BE 源码
- 字节码落盘证据：`.logs/tem2.txt`（TileEntityMekanism）、`.logs/revmc/*.txt`（DataHandlerUtils / ConfigHolder / InventorySlotHelper / 三个 tile 的编译产物）
- **未运行任何 gradle 命令**（编译与测试请 Lead 统一执行）
- 证据采集产生的临时文件都在 `.logs/`（已 gitignore），未触碰 `src/`（本轮审查阶段零改动）

---

## 0. 覆盖状态（每个文件「已看 / 未看」）

| 文件 | 行数 | 状态 | 说明 |
|---|---|---|---|
| `machine/MekCkMachineTile.java` | 1103 | 已看（全文） | tick 主干 / 槽位装配 / 持久化 / 升级 / 能量闸门 |
| `machine/MekCkLegacyMachineNbt.java` | 564 | 已看（全文） | 迁移器逐分支 |
| `machine/MekCkSlotNbt.java` | 219 | 已看（全文）+ 字节码对照 | int 下标存档 |
| `machine/MekCkBatchPacking.java` | 132 | 已看（全文） | canFitAll / insertOutput 配对性 |
| `machine/MekCkSlot.java` | 123 | 已看（全文） | 谓词与容量（已知有意设计，未报） |
| `machine/MekCkFactoryType.java` | 80 | 已看（全文） | 仅译名/枚举，无逻辑 |
| `machine/MekCkRecipeExecutor.java` | 33 | 已看（全文） | 接口契约 |
| `machine/ports/IMekCkPorted.java` | 72 | 已看（全文）+ 消费方 `ae2/MekPortWindow` | 见 Minor-4 |
| `machine/cutting/CuttingFactoryTile.java` | 334 | 已看（全文） | |
| `machine/cutting/CuttingFactoryExecutor.java` | 375 | 已看（全文） | |
| `machine/grinding/GrindingFactoryTile.java` | 309 | 已看（全文） | |
| `machine/grinding/GrindingFactoryExecutor.java` | 623 | 已看（全文） | |
| `machine/grill/GrillFactoryTile.java` | 253 | 已看（全文） | **Critical-1** |
| `machine/grill/GrillFactoryExecutor.java` | 719 | 已看（全文） | **Important-1** |
| `machine/skewering/SkeweringFactoryTile.java` | 353 | 已看（全文） | **Critical-1** |
| `machine/skewering/SkeweringFactoryExecutor.java` | 560 | 已看（全文） | |
| `machine/plantingcutting/PlantingCuttingFactoryTile.java` | 427 | 已看（全文） | |
| `machine/plantingcutting/PlantingCuttingFactoryExecutor.java` | 502 | 已看（全文） | |
| `machine/cooking/CookingFactoryTile.java` | 481 | 已看（全文） | **Critical-1 + Critical-2** |
| `machine/cooking/CookingFactoryExecutor.java` | 703 | 已看（全文） | |
| `config/MekckConfig.java` | 约 800 | 已看（与工厂相关的全部段与访问器；生物反应堆/冷萃/预览/种植生成等无关段跳读） | 见 §5 |

辅助对照（不在范围但为结论所需，均已读）：
`CuttingMachineFactoryTier.java`（12 档 processes）、`ae2/MekPortWindow.java`（端口消费方）、
`util/CountMath.java`、`upgrade/MekCkUpgradeTypes.java`（签名）、
`util/BarbequesDelightCompat.java`、`util/KaleidoscopeGrillingCompat.java`、
`UniversalCuttingMachine.java:1020-1204`（tile 注册链）、
旧 BE 源码（`git show 83830e0^:…/CookingFactoryBlockEntity.java`、`git show 070f6d1^:…/SkeweringFactoryBlockEntity.java`）。

---

## 1. 六个必答问题

### Q1 存档往返是否守恒 —— **不守恒（Critical-2）**

**结论**：`appendExtraSlots` 追加的家族专属槽**不在** `mekckPersistedSlots()` 里，因此只走 Mek 自己的
byte 下标存档；下标 ≥ 128 的槽在**每次存读档**都被 `getByte` 读到负值后静默丢弃。

- 机制与字节码证据见 **Critical-2**。
- **用了 `appendExtraSlots` 的家族共 4 个**：grill（3 调味料）、plantingcutting（营养液 + 生长土）、
  skewering（81 存储）、cooking（144 存储）。
- **超过 127 的档位与槽号（实算，N=输入槽数，M=输出槽数，能量槽在 N+M）**：

| 家族 | N | M | 能量槽 | extras 段 | 首次越界档位 | 越界槽号 |
|---|---|---|---|---|---|---|
| cooking | 6 | 12 | 18 | 19..162 | **全部 12 档**（总数恒 163） | **128..162 = 35 格** |
| grill | processes | processes | 2N | 2N+1..2N+3 | 仅 SINGULARITY(N=81) | 163、164、165 |
| plantingcutting | processes | processes | 2N | 2N+1..2N+2 | 仅 SINGULARITY | 163、164 |
| skewering | 3 | 2 | 5 | 6..86 | 无（87 ≤ 128） | — |
| cutting / grinding | processes | processes | 2N | 无 | 无（已由 MekCkSlotNbt 覆盖 2N 号能量槽） | — |

- **是不是「静默消失」：是**。`DataHandlerUtils.readContents` 对负下标整条 `iflt` 跳过，
  不抛异常、不打日志；本类也不写 WARN（只有 `MekCkSlotNbt` 那条路径才会记日志，而 extras 不走它）。
- 排序问题（Lead 让我坐实的那条）：**Mek 的 byte 下标就是 addSlot 的插入序**，见 Critical-2 的证据链，
  所以「cooking 丢的就是 128..162 这 35 格存储」这句成立。

### Q2 旧存档迁移的正确性 —— **部分不正确（Important-2 / Minor-1）**

| 子问题 | 结论 |
|---|---|
| 旧机器槽数 > 新档位槽数时多出来的物品去哪了 | **两个不同的坏结果，取决于「谁比谁大」**：① 同家族同档（`2N` 相同）时不会出现；② 若 NBT 来自更高并行档（降级/被 `/give` 拼出来的方块）：`slot ∈ [2N_new, 2N_old)` 的输入/输出物品会落进 `upgradeCards` 分支，**前两件被写进升级卡槽**（`MekCkLegacyMachineNbt:267`），其余 `dropped++` 记 WARN 丢弃；玩家的真升级卡（下标更大）反而全被丢。③ 反过来升级时（`2N_new > 2N_old`）：旧升级卡与能源槽之前的那几格落在 `[0, 2N_new)` 里，被当成普通机器槽灌进新机器的空输出槽。 |
| `Size` 键是否写对 | 新格式**不需要** `Size`（新 `Items` 是 ListTag，`MekCkMachineTile:276` 整体覆盖同名键）。但迁移**读**它：`powerSlot = max(0, old.getInt("Size")) - 1`（`:246`）。上一轮 I2（`GrillFactoryBlockEntity` 漏写 `Size`）留下的旧档在此处 `powerSlot = -1` ⇒ `slot == powerSlot` 与 `slot < powerSlot` 永假 ⇒ **能源槽物品 + 全部升级卡被逐条 WARN 丢弃**（不崩、不静默，但内容没了）。这是 I2 在新迁移器上的可达后果（**仍未修**，属新证据）。 |
| `migratesLegacyNbt()` 返回 false 的家族 | skewering（`:114`）、cooking（`:156`）：`load` 里 `legacyTag == null` ⇒ 不迁移、**不调 `installLegacyUpgrades`**，`super.load(旧标签)` 后 `getList("Items",10)` 拿到空表 ⇒ 机器读档为**空机器**（旧内容全丢，无日志）。这正是注释声明的取舍，属有意设计；但要注意烹饪**流体罐仍在**（`CookingFactoryTile.load` 读的键与旧实现同名）——「槽空、水还在」是文档化的既定行为。 |
| 迁移后 NBT 是否残留旧键 | `dropLegacyKeys`（`:537-549`）把 Energy/Progress/SideConfig/Redstone*/Order* 与 4 个 tracker 全部 remove；`Items` 被整体覆盖。**残留的是刻意保留的两个**：`AutoDistribute`/`AutoSelectedItems`（AE2 层，文档写明保留）。✔ 无遗漏。 |

### Q3 执行器算术 —— **配对性成立；两处口径问题（Important-1、Important-3）**

逐个过完 6 个执行器：

| 家族 | canFitAll 与 insertOutput 是否同源配对 | 备注 |
|---|---|---|
| cutting | ✔ 同一 `MekCkBatchPacking`，上限都问 `slot.getLimit`，遍历序一致 | 溢出用 long 乘 + 夹到 MAX，溢出路径实际不可达（canFitAll 先否掉） |
| grinding | ✔ 最坏情况（全部命中）判容量，实际命中数 ≤ 最坏 ⇒ 落槽必有位 | `rollByExpectation` 的 `total` 也夹到 MAX |
| grill | ✘ **预演栈与实际落槽栈的 NBT 口径不一致** | 见 Important-1 |
| plantingcutting | ✔ `allOutputs`（含次级，最坏情况）先判后落 | 种子不消耗，无 `min(consume, input.count)` 是对的 |
| skewering | ✔ 返还槽用 `preview`（数量 = batch）先判，`returnPayload` 数量同为 batch | 位置无关扫描，正确 |
| cooking | ✔ 产物先判后落；返还物走 `canFitAll`→`insertOutput`，装不下**掉在机器头顶**（显式 fail-safe，不丢） | 见 Important-3 的 `consumeFluid` 顺序说明 |

`slotCount` 参数（= `inputSlots.size()`）语义：`MekCkMachineTile:852` 传的是 `inputSlots.size()`，
执行器再 `Math.min(slotCount, inputs.size())`。对「固定 3 输入」的穿串/烹饪：它们**不按槽循环**，
只用 `slotCount` 做 0 值保护，语义成立。**查证后不成立**（不是缺陷）。

int 溢出：`CountMath.mulClamp` 全部走 long + 夹到 `[0,cap]`，无负数路径。
`ItemStack` 拷贝与 `shrink` 的配对：`completeRecipe`/`consumeOne` 里 `copy()` 只用于「剩余量」，
`shrink` 前后都判 `isEmpty`，未发现计数丢失。

### Q4 tick 主干语义 —— **闸门安全；但两个家族的门禁比旧实现弱（Important-3）**

- 三段闸门（`workCycle:835-867`）与旧 `serverTick` 同序：红石 → 有活干 → 能量够；
  不满足时 `workProgress = 0` + 释放 PULSE 锁存，与旧 `else { progress = 0 }` 逐字一致。
- **「开工但未完成」的订单状态：查证后不成立**。6 个执行器的加工都是**单次调用内原子完成**
  （扣料 → 落槽 → 推进订单），没有任何「半成品」持久化状态；进度条只表示时间。闸门清零 ⇒
  只损失「已经烧掉的能量与已走的进度」，不会破坏订单。
  订单状态（`orderRecipeId/Quantity/Completed`）只在执行器真正完成一批时自增，不随进度条回退。
- PULSE 锁存：`allowsWork:601-613` 与旧语义一致（跑完整批才复位），Mek 的
  `canFunction` 对 PULSE 只放行上升沿一 tick，这里刻意自实现，理由已写进注释 —— 未报。
- 随机化卡三分支：`gatedEnergyCost` / `gatedTicksPerCycle` / `energyToRefill` 都是静态纯函数，
  语义与旧第 387/414/378 行逐条对应；基类默认全 false，只有 cutting/grinding 覆写 —— 与注释一致。
- **例外**：cooking / skewering 的 `hasWorkToDo()` 覆写成「有订单」（`CookingFactoryTile:402`、
  `SkeweringFactoryTile:278`），比旧 `canProcess` 弱 ⇒ 见 Important-3。

### Q5 能量 —— **不会 int 溢出成负数，无白送电（查证后不成立）**

- `energyPerWorkTick()` 的两段算术都不经手写 int 乘法：
  - `baseEnergyPerTick(...)`（cutting/grinding）走 double ⇒ `(int) Math.min(MAX, Math.max(0, ceil(raw)))`
    （`CuttingFactoryTile:189-195`）；grill/plantingcutting 用**内联的同一式子但没有 `Math.max(0, …)`**，
    不过 `(int)` 对 NaN/负数与超范围 double 的行为是饱和/截断，且乘数全部来自配置下界 0.0，
    未发现负数路径（Minor-3）。
  - 与 active/stackMult 相乘走 `CountMath.mulClamp(Integer.MAX_VALUE, a, b, c)` —— **long 乘法 + 夹到 [0,MAX]**
    （`CountMath:22-31`），结构上不可能返回负数。
- 因此 `hasEnergyFor(cost)` 的 `cost <= 0` 分支不会因为溢出被误触；`deductEnergy` 的
  `AutomationType.MANUAL` 修正（阶段 2 已修）在 6 个家族都生效。
- 一条**边界观察（非缺陷）**：`mulClamp` 把 cost 夹到 `Integer.MAX_VALUE`，此时
  `hasEnergyFor` 要求容器存量 ≥ 21 亿 FE（各档容量上限 810 万）⇒ 表现为「永远不够电、机器停住」，
  不是「白送电」。按当前配置（SINGULARITY 的 `energyPerTick == 0` 先短路）该路径不可达，
  但**一旦有人去掉那个短路**，症状会是停机而不是免费，方向是安全的。

### Q6 口径漂移（6 家族对账）

| 项 | cutting | grinding | grill | plantingcutting | skewering | cooking | 判定 |
|---|---|---|---|---|---|---|---|
| `energyPerWorkTick` | 静态纯函数 + active 乘数 | 同左 | 内联 + active | 内联 + active | 内联、**无 active**（注释有理由） | 内联、无 active、**不乘能效**（注释有理由） | 有意差异，未报；grill/plantingcutting 少一道 `Math.max(0,…)` → Minor-3 |
| `ticksPerWorkCycle` | `effectiveProcessTime()` | 同左 | 内联同式 | 内联同式 | 内联同式 | 内联同式 | 等价 |
| `stackMultiplier` 上限来源 | `MekCkUpgradeTypes.capOf` | 同左 | 同左 | 同左 | `MekckConfig.getFactoryStackUpgradeMax`（基数表不同，注释有理由） | 同穿串 | 有意差异 |
| `meManualOnlyItemSlots` | [能量槽, 升级槽] | 同左 | **只有 3 个调味料槽** | **只有营养液+生长土** | 默认空 | 默认空 | **Minor-4**（今天无实际后果） |
| `mePatternItemInputs` | inputSlots | 同左 | 同左 | 同左 | inputs+81 存储 | inputs+144 存储 | 有意 |
| `meGroupParallelItemInputs` | true | true | true | true | false | false | 有意 |
| 产物落槽 | `insertOutput(全部 outputs)` | 同左 | 同左 | 同左 | 只 `outputs[0]`（产物）+ `outputs[1]`（返还） | `productSlots()` + `returnSlots()` | 槽位形态不同，正确 |
| `load(CompoundTag)` 判 null | ✔ | ✔ | ✔ | ✔ | **✘ 不判** | ✔ | Minor-2（当前调用方恒非 null） |
| `busy` 语义 | 真加工过才置位 | 同左 | `findRecipe` 命中即置位（可能没落任何东西） | active 槽一律置位 | `run` 成功才置位 | `run` 成功才置位 | Minor-5（今天无人消费） |

---

## 2. Critical

### Critical-1【可机械修复】appendExtraSlots 在构造期读「尚未初始化的字段」→ 三个家族的机器**根本建不出来**

**位置**
- `machine/grill/GrillFactoryTile.java:51`（字段初始化器 `= new ArrayList<>(SEASONING_SLOTS)`）+ `:86`（`seasoningSlots.clear()`）
- `machine/skewering/SkeweringFactoryTile.java:82` + `:151`（`storageSlots.clear()`）
- `machine/cooking/CookingFactoryTile.java:114` + `:190`（`storageSlots.clear()`）

**机制**：`TileEntityMekanism` 的构造器内部回调 `getInitialInventory` ⇒
`MekCkMachineTile.getInitialInventory` 末尾调 `appendExtraSlots`（`MekCkMachineTile:312`）。
这发生在 `super(...)` **返回之前**，此刻子类字段初始化器一个都还没跑（JLS 初始化顺序），
上述三个字段仍是 `null` ⇒ `clear()` 抛 NPE。

**触发条件**：放置或加载**任意一档**烧烤 / 穿串 / 烹饪工厂方块（注册链已坐实：
`UniversalCuttingMachine.java:1122 / 1087 / 1051` 的 `TILE_ENTITIES.register(handle, (pos,state) -> new XxxFactoryTile(...))`）。

**后果**：方块实体构造失败 —— 放置与区块加载两条路径都会从该 supplier 抛出 NPE，
机器无法建立（会不会进一步崩服取决于 vanilla 是否吞异常，本报告未坐实那一步；
但「这台机器建不出来」本身已是致命级）。

**证据**（三层，全部可复现）
1. 基类自述的时序陷阱与实测教训：`MekCkMachineTile.java:65-83`（明写「槽位列表……**不能**写成
   `= new ArrayList<>()` 字段初始化器」，并引用了同型 NPE 的实测信息）。
2. 父类字节码：`javap -c mekanism.common.tile.base.TileEntityMekanism`（`.logs/tem2.txt`）
   —— 构造器 `:148 presetVariables()`、`:231 getInitialEnergyContainers`、
   `:248 offset 331 invokevirtual getInitialInventory`、`:303 offset 457 new TileComponentUpgrade`。
3. **本项目编译产物**：`javap -p -c build/classes/java/main/…/GrillFactoryTile.class`（`.logs/revmc/GrillFactoryTile.txt:17-30`）：
   `4: invokespecial MekCkMachineTile.<init>` → `16: putfield seasoningSlots`；
   `appendExtraSlots` 里 `4: invokeinterface List.clear()`（`.logs/revmc/GrillFactoryTile.txt:65-69`）。
   即 super 调用期间字段必为 null。Skewering / Cooking 的编译产物同形（`.logs/revmc/*.txt`）。

**建议修法**（最小、机械）：把三个字段改成**非 final、无初始化器**，并在 `appendExtraSlots` 里：
```java
if (storageSlots == null) {
    storageSlots = new ArrayList<>(STORAGE_SLOTS);
} else {
    storageSlots.clear();
}
```
（保留 clear 的防御意图；`final` 必须去掉，否则方法内赋值不合法。）

**可否机械修复**：可。

---

### Critical-2【可机械修复】家族专属槽不在 int 下标存档的覆盖范围内 → 下标 ≥128 的槽每次存读档静默丢失

**位置**
- `machine/MekCkMachineTile.java:1056-1062`（`mekckPersistedSlots()` 只返回 `inputSlots + outputSlots + energySlot`）
- 受害槽来自 `appendExtraSlots`：`CookingFactoryTile:189-205`、`GrillFactoryTile:85-93`、
  `PlantingCuttingFactoryTile:246-254`、`SkeweringFactoryTile:149-166`
- 存档/读档调用点：`:924`（`saveAdditional` → `MekCkSlotNbt.write`）与 `:1013`（`load` → `MekCkSlotNbt.read`）
- 类注释里的旧结论已不完整：`MekCkSlotNbt.java:32-36`（「N ≤ 63 并行才安全」只覆盖并行方阵）

**机制**（三段证据链，Lead 的候选与我的独立复核一致）
1. Mek 写盘：`TileEntityMekanism.saveAdditional` 偏移 74~86 =
   `tag.put("Items", DataHandlerUtils.writeContainers(getInventorySlots(null)))`
   —— `.logs/tem2.txt:1178-1194`；读盘对称：`:1086-1093`（`readContainers(getInventorySlots(null), getList("Items",10))`）。
2. byte 截断：`DataHandlerUtils.writeContents` 偏移 48~53 `iload_3; i2b; putByte("Slot",(byte)i)`，
   `readContents` 偏移 27~49 `getByte` → `iflt 64`（负值跳过）+ `if_icmpge`（越界跳过）
   —— `.logs/revmc/DataHandlerUtils.txt:82-86` 与 `:41-49`；`getTagByType` 对 `IInventorySlot` 返回 `"Slot"`（`:124-128`）。
3. **下标 = addSlot 插入序**（Lead 未定的那条，我坐实了）：
   `ItemHandlerManager` 的 containerGetter 走 holder；`ConfigInventorySlotHolder.getInventorySlots(side)`
   → `ConfigHolder.getSlots(side, fn)`，而 `getSlots` **第一支就是 `if (side == null) return this.slots;`**
   （`.logs/revmc/ConfigHolder.txt:84-90`），`this.slots` 是 `ArrayList`，
   `ConfigInventorySlotHolder.addSlot` 就是 `slots.add(slot)`（`.logs/revmc/ConfigInventorySlotHolder.txt:11-18`），
   `InventorySlotHelper.addSlot` 逐次转发（`.logs/revmc/InventorySlotHelper.txt:59-116`）。
   ⇒ `getInventorySlots(null)` 的次序 == `getInitialInventory` 里 addSlot 的次序
   （输入方阵 → 输出方阵 → 能量槽 → appendExtraSlots），byte 下标即该次序下标。

**触发条件与后果**（N/M/能量槽见 §Q1 表）
- **烹饪工厂（全部 12 档，新建机器即中招）**：144 格存储位于下标 19..162，
  其中 **128..162 共 35 格**在区块卸载 / 世界保存后内容消失。**无异常、无日志**。
- 烧烤（SINGULARITY）：调味料槽 163/164/165 丢失。
- 种植切配（SINGULARITY）：营养液槽 163、生长土槽 164 丢失。
- 穿串（81 存储，6..86）不在越界区，安全；cutting/grinding 的能量槽（2N=162）已被
  `MekCkSlotNbt` 覆盖，安全。

**建议修法**（机械）：
```java
private List<IInventorySlot> mekckPersistedSlots() {
    // 与 Mek 的 byte 存档同一份列表、同一顺序（ConfigHolder 在 side==null 时返回 addSlot 插入序），
    // 因此本列表下标恒等于 Mek 的 Items 下标，appendExtraSlots 的家族专属槽也在其中。
    return getInventorySlots(null);
}
```
配套：① 更新 `MekCkSlotNbt` 类注释（`:32-36`）把 extras 形态写进去；
② 迁移器写出的 `MekCkSlotCount` 目前恒为 `2N+1`（`MekCkLegacyMachineNbt:278`），
改完后 grill/plantingcutting 的旧档会每次读档多一条「槽位数不符」WARN —— 建议给
`migrate` 加一个重载参数（实际槽数），见 Minor-1。
③ 不改 Mek 那份 byte 存档的写出（保留兼容），本类只是权威来源 —— 与既有设计一致。

**可否机械修复**：可（② 为同批的小修）。

---

## 3. Important

### Important-1【可机械修复】烧烤：容量预演用错了兼容模块 → 预演栈与落槽栈 NBT 不同，可静默丢产物

**位置**：`machine/grill/GrillFactoryExecutor.java:384-387`（预演）与 `:406-411`（实际落槽）。
**机制**：产物先算「已调味」预览栈再判容量（注释明写「用未调味的栈判会判少」），但预演无条件用
`BarbequesDelightCompat.applySeasoning`（写 `{Seasoning: <id>}`），实际落槽却按来源分派：
`seasoning.startsWith(KaleidoscopeGrillingCompat.MOD_ID)` 时用
`KaleidoscopeGrillingCompat.applySeasoningToSkewer`（写 `{SeasoningIngredients:[id], SeasoningUses:n}`，见
`util/KaleidoscopeGrillingCompat.java:265-272` / `BarbequesDelightCompat.java:73-76`）。
**触发条件**：装了暮色…（准确说 `kaleidoscope_grilling`）与 `barbequesdelight` 两个 mod，
调味料槽里放森罗的调味瓶，且产物槽里已有一摞**同物品、BD 口味 NBT** 的烤串。
**后果**：`canFitAll` 认为「并入已有槽后装得下」，`insertOutput` 因 NBT 不同无法并入 ⇒
余量留在参数里被调用方丢弃（`GrillFactoryExecutor:415` 之后不再检查）⇒ **产物静默消失**；
反向（产物槽里是森罗 NBT 时）表现为「明明有位却拒绝加工」。
**证据**：两条分支的源码 + 两个 compat 的写键不同（上引行号）。
**建议修法**：预演与实际共用同一个分派（把 `:407-411` 的分支提成一个小方法，两处都调）。
**可否机械修复**：可。

### Important-2【需设计决策】迁移器对「家族专属槽排在升级卡之前」的两个家族会串位

**位置**：`machine/MekCkLegacyMachineNbt.java:245-273`（`machineSlots = 2N`、`powerSlot = Size-1`、
`upgradeCards.size() < UPGRADE_CARD_SLOTS` 三个分支）。
**机制**：迁移器假设旧布局只有两段机器槽 + 升级卡 + 能源槽。烧烤/种植切配的旧布局是
`2N 之后是调味料（各 3 / 2 格），然后才是升级卡`（两家族的类注释自述：
`GrillFactoryTile.java:80-82`、`PlantingCuttingFactoryTile.java:242-244`），
于是：调味料/营养液/生长土槽（`slot ∈ [2N, Size-1)`，且 `upgradeCards.size() < 2`）
被当成**升级卡**写进 `componentUpgrade.Items`；玩家的真升级卡因 `upgradeCards.size() >= 2`
落进 `else` 分支**被丢弃**（只有这一类会记 WARN）。
**触发条件**：旧存档升级到新版后第一次读档（这两个家族 `migratesLegacyNbt()` 为默认 `true`）。
**后果**：内容放错格子 + 升级卡物品丢失（升级**数量**另由 `installLegacyUpgrades` 从 tracker 键恢复，
所以能力不丢，丢的是实物卡）。对照：穿串/烹饪因为形态差得远已选择 `false`（拿「不搬」换「不放错」）。
**建议修法（两选一，需你定）**：① 同一决策沿用到这两个家族（`migratesLegacyNbt()` 覆写为 false，
一行，机械）；② 让迁移器接收家族自述的旧槽段（输入/输出/专属/升级卡边界），把专属槽映射到新
extras 段（改动大，需要每个家族写一份旧布局常量）。
**可否机械修复**：方案 ① 可，但是**行为变更**（旧档内容整体不搬），属设计决策。

### Important-3【需设计决策】cooking / skewering 的门禁比旧实现弱 → 有订单但无料时空烧电

**位置**：`CookingFactoryTile.java:402-404`、`SkeweringFactoryTile.java:278-280`（`hasWorkToDo() = hasOrder()`）；
两者的 `energyPerWorkTick()`（`CookingFactoryTile:383-392`、`SkeweringFactoryTile:259-269`）
**没有** `activeWorkSlots()` 闸门（注释解释说这是刻意的：这两家不按槽计费）。
**机制**：新闸门 = `allowsWork() && hasWorkToDo() && hasEnergyFor(cost)`；`hasWorkToDo` 只看有没有订单，
不看「料齐不齐 / 产物装不装得下」。旧实现的三条件里 `canProcess` 恰好包含这两项：
`git show 83830e0^:…/CookingFactoryBlockEntity.java` 第 543-568 行算出 `canProcess`（有配方 +
`maxConsumable > 0` + `canFitAll`），第 570 行 `energyPerTick = canProcess ? … : 0`，
第 578 行才 `extractEnergy`。穿串侧同形（`git show 070f6d1^:…/SkeweringFactoryBlockEntity.java:478,486`）。
**触发条件**：下了订单但材料不齐（或产物槽满 / 流体不够）。
**后果**：进度条每周期（200/speed tick）走满一次、**每 tick 照扣电**，执行器却什么都不做
（`tick` 里 `batch <= 0` 直接 return）。旧实现此时 `energyPerTick == 0`，不扣电。
即「订单挂着但缺料」的机器会持续吃电 —— 不是物品损失，但确实是行为回归且玩家难以察觉。
**建议修法**：忠于旧语义需要执行器对外暴露「当前可加工」查询（新接口方法 `canWorkNow()`，
由 6 个执行器各自实现；闸门改用它），属架构小改；**保守半机械替代**：
`hasWorkToDo() = hasOrder() && activeWorkSlots() > 0`（能挡掉「完全没料」的最常见情形，
但仍不能挡住「有料但不匹配配方 / 产物满」）。
**可否机械修复**：否（需你选口径）。

### Important-4【需设计决策】迁移器读不到 `Size` 时（上一轮 I2 的旧档）会丢能源槽物品 + 全部升级卡

**位置**：`machine/MekCkLegacyMachineNbt.java:246`（`powerSlot = max(0, old.getInt("Size")) - 1`）
配合 `:264-273` 的分支。
**机制**：I2 记录的是 `GrillFactoryBlockEntity` 写档漏 `Size`。这类旧档进新迁移器时 `Size` 取 0
⇒ `powerSlot = -1` ⇒ `slot == powerSlot` 与 `slot >= machineSlots && slot < powerSlot` 恒假
⇒ 能源槽物品与 4 张升级卡**全部走 else 分支**（`dropped++` + 逐条 WARN）。
**触发条件**：从带 I2 缺陷的版本升级上来、且那台烧烤工厂里放过升级卡/能源物品。
**后果**：内容丢失（有日志，不静默）；不会崩（新格式不需要 `Size`）。
**建议修法**：`Size` 缺失时用「列表里最大 Slot 下标 + 1」兜底，或退化成
`powerSlot = max(slot)+1-1`（即把最大下标那一格当能源槽）。
**可否机械修复**：技术上是，但「哪种兜底」需要你认可（会改变哪一格被当能源槽）。

---

## 4. Minor

1. **Minor-1【可机械修复】迁移写出的 `MekCkSlotCount` 恒为 `2N+1`**（`MekCkLegacyMachineNbt:278`）：
   修完 Critical-2 后，grill/plantingcutting 的旧档每次读档都会多一条
   「存档记录的槽位数为 X，本机实际有 Y 个」WARN（`MekCkSlotNbt:170-174`）。建议给
   `migrate` 加一个 `totalSlotCount` 重载（保留旧 3 参签名以免测试编译失败）。
2. **Minor-2【可机械修复】迁移日志文案硬编码「切菜工厂」**（`MekCkLegacyMachineNbt:271,404`）：
   grinding/grill/plantingcutting 的迁移告警都写成「切菜工厂旧存档」，排障时会误导。
3. **Minor-3【可机械修复】grill/plantingcutting 的 `baseEnergyPerTick` 少一道下界夹紧**：
   `GrillFactoryTile:196-197` / `PlantingCuttingFactoryTile:331-332` 用
   `(int) Math.ceil(...)` 而没有 cutting/grinding 那样的 `Math.max(0.0, …)`
   （`CuttingFactoryTile:189-195`）。当前配置下界 0.0 + 乘数非负 ⇒ 不可达，但三处口径不一致。
4. **Minor-4【需设计决策】`meManualOnlyItemSlots()` 6 家族 4 种写法**（见 §Q6 表）：
   cutting/grinding 列 [能量槽, 升级槽]，grill 只列 3 调味料槽，plantingcutting 只列 2 额外槽，
   skewering/cooking 用默认空。**今天无实际后果**：消费方 `ae2/MekPortWindow.java:103-135`
   用它做「从输入集合里剔除」的白名单，而能量槽/升级槽本来就不在 `mePatternItemInputs()` 里
   （`InputHandlerManager` 的两份升级槽由 `TileComponentUpgrade` 单独持有、不在 `getInventorySlots` 里）。
   建议统一成「额外槽 + 能量槽 + 升级槽」的全集，或在接口注释里写明「只列会同时出现在
   mePatternItemInputs 里的槽」。
5. **Minor-5【可机械修复】`isBusy()` 语义漂移**：`GrillFactoryExecutor:117-122` 只要
   `findRecipe` 命中就 `busy = true`（`completeRecipe` 可能因装不下直接 return），
   `PlantingCuttingFactoryExecutor:136-139` 对 active 槽一律置位；而 cutting/grinding 只在
   真执行后置位。当前唯一消费方是基类的 `isBusy()`（基类刻意改用 `workProgress > 0`，
   并解释了原因），所以今天无后果；但接口注释写的是「是否正在工作」，将来有人消费就会踩到。
6. **Minor-6【可机械修复】`SkeweringFactoryExecutor.load` 不判 `tag == null`**
   （`:129-157`，直接 `tag.getString`），与其余 5 个家族（都判 null）不一致；
   `MekCkRecipeExecutor.load` 的契约是「旧存档无此键时必须保持默认态，不得抛异常」。
   当前调用方 `MekCkMachineTile:1023` 恒传非 null 的 `getCompound`，所以不可达。
7. **Minor-7（记录，不建议动）**：`MekCkMachineTile.workCycle` 在闸门失败时清进度条，
   但**已扣的能量不退**；与旧实现一致，非回归。

---

## 5. 未经证实（不许当结论）

1. **Critical-1 的下游症状深度**：NPE 从 BE 构造器抛出后，1.20.1 的
   `BlockEntityType#create(BlockPos, BlockState)` 是否在调用链某处被 catch（决定「崩服」还是
   「方块在、BE 为空」）。我未能从 Forge 47.4.16 的 sources jar 取到该 vanilla 文件
   （Forge 的 sources jar 只含被 patch 的文件），未坐实这一步。
2. **穿串「主料/辅料 Ingredient 重叠」时批量高估**：`batchSize`（`SkeweringFactoryExecutor:253-259`）
   对三种材料**各自**求可用数再取小，`consume`（`:359-385`）却从同一个池子里按三种需求分别扣。
   若某条配方的 main 与 side 能被同一批物品满足，就可能「产出多于实扣」。本轮**没有找到这样一张
   配方实例**（自有 8 条 skewering 配方的 main/side 由数据决定），故不计入结论。
3. **烧烤`mePatternItemInputs` 不含调味料槽**（`GrillFactoryTile:229-236`）与
   `meManualOnlyItemSlots` 只含调味料槽的组合语义：AE2 侧表现未在游戏内验证。

---

## 6. 查证后不成立（避免重复排查）

1. **能量 int 溢出 ⇒ 负数 ⇒ 白送电**：不成立。`CountMath.mulClamp` 是 long 乘法 + `[0,cap]` 夹紧；
   `baseEnergyPerTick` 走 double + 饱和转换。证据见 §Q5。
2. **`hasEnergyFor(int)` 的 `cost <= 0` 恒真分支被溢出误触**：不成立（同 1）。
3. **`canFitAll` 与 `insertOutput` 口径漂移**（cutting/grinding/plantingcutting/skewering/cooking）：
   不成立 —— 上限同源（`slot.getLimit`）、遍历序一致、`multiplier` 与 `scaled()` 换算一致。
   **唯一例外是 grill 的 NBT 口径**（Important-1）。
4. **`slotCount` 在「固定 3 输入」家族语义不成立**：不成立，见 §Q3。
5. **进度条清零会破坏执行器的「已开工未完成」订单**：不成立 —— 6 个执行器都没有中间态持久化。
6. **`CuttingFactoryExecutor.stackMultiplier` 与 `MekCkUpgradeTypes.stackMultiplier` 两份实现漂移**：
   不成立 —— 前者是薄委托（`CuttingFactoryExecutor:372-374`），两边同一纯函数。
7. **`mekckPersistedSlots()` 每次新建列表是性能问题**：不成立（注释已说明，存档路径可忽略）。
8. **`getInitialInventory` 的除零/越界保护缺失**：不成立，`:292-296` 已显式拦并点名家族。
9. **已知有意设计（按任务要求不报）**：`MekCkSlot` 关 `obeyStackLimit` 与手工 `ContainerSlotType`、
   `getSupportedUpgrade` 返回 `EnumSet`、`executor()`/`tierFromBlock()` 懒加载与反查、
   穿串 `migratesLegacyNbt() == false`、阶段 1 反射桥接契约（类名 + `getTier()` 返回类型）——
   逐条读过注释与 `docs/superpowers/specs` 依据，**均判为有意设计**。
10. **上一轮 I1–I10 未重复上报**：本报告只把 I2 在新迁移器上的**新可达路径**写成 Important-4
    （标注「仍未修 + 新证据」），其余未触及。

---

## 7. 本轮动手清单（等 Lead 指令）

已获 Lead 明确指令的一项：Critical-2（+ Minor-1 配套）。
我另外建议一并修（均为【可机械修复】且同属我唯一写作用域内的单文件小改）：

| 顺序 | 项 | 文件 | 风险 |
|---|---|---|---|
| 1 | **Critical-1**（构造期 NPE，三家族完全不可用） | Grill/Skewering/Cooking Tile | 低（纯时序修正） |
| 2 | **Critical-2** + Minor-1（`mekckPersistedSlots` 改为 `getInventorySlots(null)`；迁移记录真实槽数） | MekCkMachineTile / MekCkLegacyMachineNbt | 低—中（存档格式的**覆盖范围**变化，需 Lead 跑全量测试） |
| 3 | **Important-1**（烧烤预演/落槽同源） | GrillFactoryExecutor | 低 |
| 4 | Minor-2/3/5/6（日志文案、夹紧一致、busy 语义、null 判定） | 各自文件 | 极低 |

回归测试计划（改完后加）：
- `TestMekCkSlotNbt` 增：**163 槽机器**（6 输入 + 12 输出 + 1 能量 + 144 存储）的**最后一格**
  经 `MekCkSlotNbt` 往返仍在（对应 Critical-2 的越界形态）；
- 新增源码不变量测试：`mekckPersistedSlots()` 必须返回 `getInventorySlots(null)`
  （即「覆盖范围 = Mek 写盘用的同一份列表」），照 `TestMekCkSlotNbt#mekckSlotReadIsAppliedAfterSuperLoad`
  的读源文本手法；
- 迁移重载的 `MekCkSlotCount` 断言（Minor-1）；
- 三条 **构造期 NPE 的源码不变量**（`appendExtraSlots` 里不得对字段直接 `clear()`，
  或字段不得有 `new ArrayList` 初始化器）—— 真 tile 造不出来，只能钉源码形态。

---

## 8. 本轮已实施的修复（审查阶段结束后，按 Lead 指令动手）

> 说明：报告 §1–§6 是**动手前**的审查结论，保持原样不改。本节记录实际改了什么、
> 以及为什么有两条【可机械修复】项**故意没改**。

| # | 项 | 文件 | 改动 |
|---|---|---|---|
| 1 | **Critical-1** 构造期 NPE | `grill/GrillFactoryTile.java`、`skewering/SkeweringFactoryTile.java`、`cooking/CookingFactoryTile.java` | 三个 List 字段去掉 final 与字段初始化器；`appendExtraSlots` 改成 `if (x == null) x = new ArrayList<>(N); else x.clear();`，并各加字段注释说明为什么不能写成初始化器 |
| 2 | **Critical-2** 专属槽不在 int 下标存档覆盖内 | `machine/MekCkMachineTile.java` | `mekckPersistedSlots()` 改为 `return getInventorySlots(null);`（与 Mek 写 Items 用同一份列表），重写该方法 javadoc 记录证据链与后果数字 |
| 2b | 同类文档 | `machine/MekCkSlotNbt.java` | 类注释补「覆盖范围是整份槽位列表，不是前 2N+1 格」+ 三个家族的越界槽号 |
| 2c | **Minor-1** 迁移记录的槽位数 | `machine/MekCkLegacyMachineNbt.java` + `MekCkMachineTile.load` | 新增 4 参 `migrate(legacy, facing, inputSlotCount, totalSlotCount)`；3 参重载保留旧口径（既有测试与调用点零改动）；`load` 传 `mekckPersistedSlots().size()` |
| 3 | **Important-1** 烧烤预演/落槽 NBT 分派不一致 | `grill/GrillFactoryExecutor.java` | 抽出 `static void applySeasoningTo(ItemStack, String)`，预演与落槽共用；原来两处各自 if 分支已删 |
| 4 | **Minor-2** 迁移日志写死「切菜工厂」 | `machine/MekCkLegacyMachineNbt.java` | 两处 WARN 文案改「工厂旧存档」（该方法被 4 个家族共用） |
| 5 | **Minor-3** grill/plantingcutting 少一道下界夹紧 | `grill/GrillFactoryTile.java`、`plantingcutting/PlantingCuttingFactoryTile.java` | `(int) Math.ceil(...)` → `(int) Math.min(Integer.MAX_VALUE, Math.max(0.0, Math.ceil(...)))`，与 cutting/grinding 的 `baseEnergyPerTick` 同口径 |
| 6 | **Minor-6** `load` 不判 null | `skewering/SkeweringFactoryExecutor.java` | 开头补 `if (tag == null) return;`（默认态已在前面清好），与其余 5 个家族一致 |

**故意没改的两条**（避免被当成漏修）：
- **Minor-5 busy 语义**：全仓库没有任何地方调用 `executor().isBusy()`（`grep .isBusy()` 的命中
  全是 `tile.isBusy()` / `menu.isBusy()`，而基类 isBusy() 走 `workProgress > 0`）。
  改它只增加回归面、不改变任何可观察行为。
- **Important-2 / Important-3 / Important-4**：都是【需设计决策】，按任务约定本轮不动。

**回归测试**（新增 `src/test/java/cn/ism/mekck/machine/TestMekCkPersistedSlotCoverage.java`，8 个用例）：
1. `persistedSlotsAreExactlyTheListMekWrites` —— 源码不变量，钉住 `getInventorySlots(null)`；
2. `extraSlotListsAreAllocatedInsideAppendExtraSlots` —— 源码不变量，钉住 Critical-1 不再复发；
3. `cookingLayoutHas35StorageSlotsBeyondByteIndices` —— 从源码读常量算布局：163 槽 / 存储 19..162 / 越界 35 格；
4. `skeweringExtrasStayInsideTheByteRange` —— 87 槽不越界；
5. `parallelFamilyExtrasOnlyOverflowAtSingularity` —— 边界（NEBULA 49 安全 / SINGULARITY 81 越界）；
6. `theLastStorageSlotOfA163SlotMachineSurvivesTheRoundTrip` —— 真槽位往返 19/128/162，
   反面对照证明 Mek 的 byte 存档读不回 128 与 162；
7. `migrationRecordsTheCallersRealSlotCount` —— 4 参迁移写 163、3 参重载仍写 2N+1；
8. `grillSeasoningUsesOneDispatchForPreviewAndInsert` —— 源码不变量，钉住预演/落槽共用分派。

### 验证状态（我做了什么、没做什么）
- **没有运行任何 gradle 命令**。
- `javac -proc:none` 解析级探针（无 classpath）：9 个改动 main 文件 + 1 个新测试文件
  **0 处解析级错误**（其余错误全是「找不到符号 / 程序包不存在」，符合无 classpath 的预期）。
- 又做了一次「mapped Forge jar + deobf 依赖 + 本项目 **陈旧** `build/classes`」的真实编译：
  13 条错误**全部落在我未改动的行**（`MekckConfig.getFactorySlotLimit`、`UpgradeHelper.speedMultiplier`
  等 —— 陈旧 class 里没有这些新方法），**我改动的行 0 错误**。
- 这条证据链**不完整**（缓存 jar 版本混杂、`build/classes` 陈旧）。**编译与测试的权威结论以
  Lead 的 clean build 为准。**

### 需要 Lead 跑的命令
```
./gradlew clean build --offline
```
（clean 必须：跨文件引用变化对增量编译是盲的，见 `docs/STATUS.md` §五.5。）

---

## 9. 批 1 追加（Lead 指令 A + B）

### A. `ISustainedData`：掉落 → 再放置 的 MekCK 状态恢复

**改动**
- `machine/MekCkMachineTile.java`
  - 类声明改为 `... implements ISustainedData`（+ `import mekanism.common.tile.interfaces.ISustainedData`、`java.util.Map`）；
  - 新增 `private void readMekckPersistentState(CompoundTag)`，把 `load` 尾部的自管键读取整段抽出；
    `load` 与 `readSustainedData` **共用同一段**；
  - `readSustainedData(CompoundTag)` → `readMekckPersistentState(tag)`；
  - `getTileDataRemap()` → `Map.of()`；
  - 新增家族钩子 `protected void readExtraSustainedData(CompoundTag)`（默认空），由 `readMekckPersistentState` 调用。
- `plantingcutting/PlantingCuttingFactoryTile.java`：`load` 改为调 `readExtraSustainedData`；新增该覆写读 `GasTank`。
- `cooking/CookingFactoryTile.java`：同上，读 `FluidTanks`。

**关键结论（与 Lead 给的假设不同，附字节码证据）**
1. **`writeSustainedData` 不是「没人调」**。全 jar 常量池扫描 + `javap`：
   - `TileEntityMekanism.addGeneralPersistentData` 偏移 21~34 调它；而 `addGeneralPersistentData`
     被 `saveAdditional`（偏移 57）与 **`getConfigurationData(Player)`（偏移 10）** 调用（`.logs/tem2.txt:2243-2257`）；
   - `BlockMekanism.getCloneItemStack` 偏移 184~211 调它。
2. `TileEntityMekanism implements mekanism.api.IConfigCardAccess`（类头实测），
   `ItemConfigurationCard` 复制时调 `getConfigurationData`、粘贴时调 `setConfigurationData`
   → `loadGeneralPersistentData` → **`readSustainedData`**。
   ⇒ **若在 `writeSustainedData` 里写 `MekCkSlots`/`GasTank`/`FluidTanks`，配置卡就成了
   「复制整机库存 → 反复粘贴」的物品复制漏洞**（粘贴侧 `MekCkSlotNbt.read` 还会先清空目标机器自己的槽位）。
   而「挖掉再放下」不需要写侧——它由战利品表 `copy_nbt` 把 BE 键拷进 `mekData.*`。
   ⇒ **`writeSustainedData` 实现为有意空实现**，javadoc 写明三条调用点、漏洞形态、
   以及「将来若只复制订单/工作模式这类纯设置该怎么做」。**与 Lead 指令的字面差异在此，请复核**；
   若要求写侧非空，唯一安全的子集是 `mekckExecutor` 这类不含内容的键。
3. **放置路径的读取次序对我们有利**（实测 `BlockMekanism.setPlacedBy`）：偏移 299~361 先读 Mek 的
   byte 下标 `Items`，偏移 364~391 才调 `readSustainedData(mekData)` ⇒ **我们的 int 下标读取最后落地**，
   ≥128 的槽不会被覆盖。
4. **读档路径必须保留显式调用**：`TileEntityMekanism.load` 里 `loadGeneralPersistentData` 在偏移 59、
   `readContainers` 在偏移 90 ⇒ 钩子那一次跑在 Mek 的 byte 写入**之前**，所以 `load` 尾部那次显式
   `readMekckPersistentState(tag)` 不能省（否则顺序不变量失效）。两条路径都幂等。
5. `getTileDataRemap()` 在 Mek 10.4.6 里**零消费方**（全 jar 常量池扫描：含该名字的只有接口本身与
   14 个实现类）。

### B. cooking / skewering 门禁（保守版）

两处 `hasWorkToDo()` 改为 `hasOrder() && activeWorkSlots() > 0`，javadoc 写明：
旧实现 `energyPerTick = canProcess ? … : 0`（`CookingFactoryBlockEntity` 旧文件 570/578 行、
`SkeweringFactoryBlockEntity` 旧文件 478/486 行），缺料时一滴电都不扣；
**这是保守近似**：只挡住「输入槽全空」，挡不住「有料但不是订单那张配方」与「产物槽满 / 流体不够」。

### C. 未动
Important-2（迁移串位）、Important-4（I2 旧档丢卡）按指令留待用户裁决。

### 新增回归测试（`TestMekCkPersistedSlotCoverage`，累计 12 个用例）
- `sustainedDataIsReadOnPlaceAndDeliberatelyNotWritten`：钉住「读侧必须读、写侧必须空」+
  `getTileDataRemap` 返回空表 + 两个家族钩子已接线；
- `mekckPersistentKeysMatchTheLootTableContract`：把与 T2 的 6 个键名契约钉成断言；
- `orderOnlyGateIsNotEnoughForCookingAndSkewering`：钉住 B 的保守门禁形态。

### 验证状态（批 1）
- 仍未运行任何 gradle 命令。
- `javac -proc:none` 解析探针：本批 4 个 main 文件 **0 处解析级错误**。
- 叠加「mapped Forge jar + deobf 依赖 + 陈旧 build/classes」的编译：报错只有两类——
  临时 classpath 缺 `org.slf4j`、陈旧 class 里没有 `MekckConfig.getFactorySlotLimit` /
  `UpgradeHelper.speedMultiplier`；**没有一条与 `ISustainedData` / `readSustainedData` /
  `writeSustainedData` / `getTileDataRemap` / `readExtraSustainedData` 相关**。
- 权威结论以 Lead 的 `./gradlew clean build --offline` 为准。

### 补记：`readSustainedData` 的第二道闸（配置卡粘贴方向）

写侧为空只挡住了「配置卡把我们的键带出去」；**读侧还有反方向的风险**：
配置卡粘贴 → `setConfigurationData → loadGeneralPersistentData → readSustainedData(卡片载荷)`，
而卡片载荷里没有 MekCK 的键。若不设闸，本方法会把「键不存在」当成「真的没有」：
- `executor().load(空标签)` → **清掉目标机器正在执行的订单**（执行器把「键不存在」定义为订单清空，
  那是给真读档用的语义）；
- `AE2Compat.load` → `FactoryGridHost.loadFromNBT` 在无节点键时 `selectedAutoItems.clear()`
  → **清掉目标机器的自动补料勾选**；
- `workProgress` 被重置为 0。

**修法**：`readSustainedData` 先判 `hasMekckKeys(tag)`（`MekCkSlots` / `mekckExecutor` /
`MekCkWorkProgress` / `MekCkNative` <b>任意一个</b>存在就算本机载荷），不满足则整段跳过。
判据取「任意一个」而不是某个特定键：战利品表少写一项时仍是「能恢复多少恢复多少」，
而不是全有或全无。`load` 路径不受影响（它走的是不经闸的 `readMekckPersistentState`）。
测试同步钉住：`sustainedDataIsReadOnPlaceAndDeliberatelyNotWritten` 现在也断言这道闸
与四个键的覆盖。

# 深审报告：ae2/ + network/ + menu/ + integration/（客户端/服务端一致性与「包与槽位对齐」）

- 审查者：rev-ae2-network（task-3）
- 基线：HEAD `83830e0`（工作树干净）
- 方法：源码全文/定点阅读 + `javap` 反汇编（Mekanism 10.4.6.20 mapped、Forge 1.20.1-47.4.16 mapped、guava 31.1-jre）+ 菜单槽位序与 BE 槽位真值逐项对账
- **未运行任何 gradle 命令**（编译/测试请 Lead 统一执行，命令清单见 §7）
- 审查阶段未改动 `src/` 下任何文件（本报告是唯一写入）
- **行号基准**：`menu/` `network/` `ae2/` `integration/` 的行号等于 HEAD `83830e0`（这四个包我确认未被他人改动）。`machine/**` 与 `blockentity/**` 正被 `rev-machine-core` 并行编辑，本报告里这些包的**行号已按当前工作树重新锚定一次**（见 §4 与 M2）；若再被改动请以方法名为准重新 grep。结论本身与行号无关

---

## 0. 一句话结论

迁移后的 6 个工厂菜单**没有**手写 `quickMoveStack`（全部交给 Mek 的 `MekanismContainer.quickMoveStack` 按槽类型分派），所以「迁移导致的菜单/BE 下标错位」**不存在**——这一点与任务假设相反，是本次最重要的**排除结论**。

真正的 Critical 在**迁移没碰过的 15 个手写 `quickMoveStack` 里**：**5 个菜单把「handler 槽位索引」当成「菜单槽位下标」传给了 `moveItemStackTo`，其中 4 个的目标区间落在玩家背包上、1 个落在机器电源槽自身。当被点击的槽恰好等于该区间时，原版 `moveItemStackTo` 的合并分支会自我合并 → 堆叠数量翻倍 ⇒ 可无限复制可堆叠能量物品。**

---

## 1. 分级发现

### 1.1 Critical

#### C1 【可机械修复】自指 `moveItemStackTo` 区间 → shift-click 复制物品（5 处）

**位置**

| # | 文件:行 | 目标区间（菜单下标） | 该下标的真实槽 | 何时自指 |
|---|---|---|---|---|
| 1 | `menu/GrillMenu.java:95-97` | `[MACHINE_SLOT_COUNT, +1)` = **[4,5)** | **机器电源槽自身**（GrillBlockEntity.SLOT_POWER=5 的 handler 槽，菜单下标 4） | 点击菜单槽 4（机器电源槽）且物品是能量物品 |
| 2 | `menu/UniversalCuttingMachineMenu.java:95-97` | `[SLOT_POWER, +1)` = **[5,6)** | **玩家背包主区第 0 格**（menu 5） | 点击菜单槽 5 且物品是能量物品 |
| 3 | `menu/ElectricGrindingMachineMenu.java:95-97` | `[SLOT_POWER, +1)` = **[5,6)** | 同上 | 同上 |
| 4 | `menu/SkeweringMachineMenu.java:118-120` | `[SLOT_POWER, +1)` = **[89,90)** | **玩家背包主区第 0 格**（menu 89；电源槽其实在菜单 88） | 点击菜单槽 89 且物品是能量物品 |
| 5 | `menu/SmartCookingPotMenu.java:128-130` | `[SLOT_POWER, +1)` = **[92,93)** | **玩家背包主区第 0 格**（menu 92；电源槽其实在菜单 91） | 点击菜单槽 92 且物品是能量物品 |

**触发条件**（任一即可）

1. 把 1 个红石（或任何 `PowerSlotUtil.isValidEnergyItem` 为真且可堆叠的物品）放到上表「该下标的真实槽」里；
2. shift-click（`ClickType.QUICK_MOVE`）该格。

**后果**

- 该堆叠数量**翻倍**：`count <= 32` 时直接 ×2；`33 <= count <= 63` 时被顶到 64。重复 shift-click 可把 1 个红石刷到 64（6 次），换格子继续刷 ⇒ **无限复制**。
- 全程在服务端执行并持久化到存档；客户端只收到「数量变大」的同步，不构成预测回滚。
- 5 处中 1 处（GrillMenu）直接作用于**机器电源槽**，另外 4 处作用于**玩家背包的固定一格**。

**证据（逐条可复核）**

1. **原版 `moveItemStackTo` 会把「源槽在区间内且 `slot.getItem()` 与传入 stack 是同一对象」当成「两堆相同物品」而自我合并**：`javap -c net.minecraft.world.inventory.AbstractContainerMenu#moveItemStackTo`（Forge mapped jar）：
   ```
   82: aload_1 / 83: aload 8 / 85: ItemStack.isSameItemSameTags   // 只比物品+NBT，无「同槽」判定
   91-101: j = itemstack.getCount() + stack.getCount()            // = 2c
   117-121: if (j <= maxSize)
   124-126: stack.setCount(0)
   129-133: itemstack.setCount(j)                                 // 同一对象 ⇒ 最终 = 2c
   147-166: else if (itemstack.getCount() < maxSize) { stack.shrink(maxSize - itemstack.getCount()); itemstack.setCount(maxSize); }
   ```
2. **`slot.getItem()` 返回的是活引用，与菜单里 `stack = slot.getItem()` 是同一个对象**：
   - `javap -c net.minecraftforge.items.SlotItemHandler#getItem`：`getItemHandler() -> getStackInSlot(index)`；
   - `javap -c net.minecraftforge.items.ItemStackHandler#getStackInSlot`：`stacks.get(i)` 直接 `areturn`（无 copy）；
   - 玩家背包侧是原版 `Slot`：`Slot.getItem() -> container.getItem(slot)`，`Inventory.getItem(i)` 同样返回 `items.get(i)` 活引用。
   - 所有自定义槽（`GrillMenu.PowerSlot` 等）都 `extends SlotItemHandler` 且**未覆写 `getItem()`**（`menu/GrillMenu.java:189-209`），所以身份同一性成立。
3. **`getMaxStackSize()` 足够大（=64），所以走的是「翻倍」分支而不是「顶到上限」分支**：`SlotItemHandler.getMaxStackSize()` → `itemHandler.getSlotLimit(index)`；`blockentity/GrillBlockEntity.java:144-156` 对 `SLOT_POWER` 显式 `return 64;`；玩家背包 `Slot.getMaxStackSize()` → `container.getMaxStackSize()` = 64。红石 `getMaxStackSize()` = 64 ⇒ `maxSize = 64`。
4. **物品资格**：`util/PowerSlotUtil.java:40-54` 的 `isValidEnergyItem` 对 `minecraft:redstone` 恒真（第 44-46 行）；各 BE 的 `isUsablePowerItem` 都转调它（12 处定义，见 §6 覆盖表）。红石可堆叠（`ItemStack.isStackable()` 为真）。
5. **区间确实是自指的**（菜单槽位序对账见 §2 表）：
   - `GrillMenu.java:26` `MACHINE_SLOT_COUNT = 4`，而 addSlot 顺序是 输入→输出→速度→能量→**能源(PowerSlot)**（`GrillMenu.java:46-57`），能源槽是第 5 个 addSlot ⇒ 菜单下标 4；第 95 行 `int powerSlot = MACHINE_SLOT_COUNT;` 正好等于它。
   - `UniversalCuttingMachineMenu.java:26` `MACHINE_SLOT_COUNT = 4`，机器槽其实 5 个（`UniversalCuttingMachineBlockEntity.SLOT_POWER = 5`），故 menu 5 起是玩家主背包；第 96 行用 handler 常量 `SLOT_POWER = 5` ⇒ 目标 menu 5 = 玩家背包第 0 格。
   - `ElectricGrindingMachineMenu` 同构（`ElectricGrindingMachineBlockEntity.SLOT_POWER = 5`）。
   - `SkeweringMachineMenu.java:28` `MACHINE_SLOT_COUNT = 8 + 81 = 89`；`SkeweringMachineBlockEntity.SLOT_POWER = 8 + 81 = 89`，但菜单**跳过了创造升级槽**（handler 88 无对应菜单槽），所以电源槽落在菜单 **88**，玩家主背包从 89 开始 ⇒ 第 119-120 行的目标 `[89,90)` 是玩家背包第 0 格。
   - `SmartCookingPotMenu.java:32` `MACHINE_SLOT_COUNT = 10 + 81 = 91`；`SmartCookingPotBlockEntity.SLOT_POWER = 10+81+1 = 92`，菜单电源槽在 91，玩家主背包从 92 开始 ⇒ 第 129-130 行目标 `[92,93)` 是玩家背包第 0 格。

**为什么现在才暴露**：`moveItemStackTo` 的合并阶段**不检查 `mayPlace`**（字节码 74-144 只有 `isEmpty` + `isSameItemSameTags`），所以「目标槽不接受该物品」并不能阻止自我合并；`mayPlace` 只在第二阶段（空槽放置，偏移 276-282）被调用。

**修复形状（机械）**：把 else 分支里所有「handler 下标」改成**菜单下标**。最稳的写法是构造器里记一个 `private final int powerSlotIndex = slots.size();`（照 `IceFactoryMenu.java:89/95/102` 的现成范式），然后 `moveItemStackTo(stack, powerSlotIndex, powerSlotIndex + 1, false)`；同时把 `MACHINE_SLOT_COUNT` 改成真正的机器槽数（含电源槽），让 `index < MACHINE_SLOT_COUNT` 分区正确。**5 个菜单都要改，且要顺带修 §1.2 的 I-A / I-B（同一个根因）。**

---

### 1.2 Important

#### I-A 【可机械修复】`GrillMenu` 的机器/玩家分区漏掉电源槽，且它被并入「玩家区」

- **位置**：`menu/GrillMenu.java:26,90-97`
- **触发**：`MACHINE_SLOT_COUNT = 4`，但机器槽是 5 个（含菜单下标 4 的电源槽）。
  - `index=4`（电源槽）走 else 分支（→ C1）；
  - `index<4` 分支的 `moveItemStackTo(stack, 4, slots.size(), true)` **把电源槽纳入了目标区间**：reverse=true 时先填快捷栏/背包，背包满了才会走到 menu 4；合并阶段不查 `mayPlace` ⇒ 若电源槽里已有同种能量物品，普通物品会被并进去。
- **后果**：分区语义错误 + 上面这条边缘串槽。
- **证据**：`GrillMenu.java:46-57` 的 addSlot 序列 vs `MACHINE_SLOT_COUNT=4`；`GrillBlockEntity.java:71-77`（`SLOT_POWER = SLOT_CREATIVE_UPGRADE + 1 = 5`）；原版 `moveItemStackTo` 字节码 74-144（合并阶段无 `mayPlace`）。

#### I-B 【可机械修复】`SimpleMachineMenu` 在扩展输入槽机器上把升级/能源目标算错 4 格 → shift-click 静默失效

- **位置**：`menu/SimpleMachineMenu.java:166-200`（目标常量在 174/176/178/180），以及构造器 addSlot 顺序 94-120。
- **触发**：`machine.usesExtendedInputSlots()` 为真的机器（搅拌机 / 智能烤炉）。这类机器上菜单槽位序是
  `0..4 输入 → 5..8 扩展输入(handler 10..13) → 9 输出(handler 5) → 10 速度(6) → 11 能量(7) → 12 创造(8) → 13 能源(9)`，
  但 else 分支沿用了 **handler 下标** `SLOT_SPEED_UPGRADE=6 / SLOT_ENERGY_UPGRADE=7 / SLOT_CREATIVE_UPGRADE=8 / SLOT_POWER=9`。
- **后果**：
  - 从背包 shift-click 速度/能量/创造卡 → 目标是 menu 6/7/8（扩展输入槽）⇒ `InputSlot.mayPlace` 拒绝 ⇒ `moveItemStackTo` 返回 false ⇒ `return ItemStack.EMPTY`，**升级卡永远 shift 不进搅拌机/智能烤炉**（无任何提示）；
  - 从背包 shift-click 能源物品 → 目标是 menu 9 = **输出槽**（`OutputSlot.mayPlace` 恒 false）⇒ 同样静默无效；只有当输出槽已放着同种物品时才会被合并进去。
- **证据**：`SimpleMachineMenu.java:94-120` 的 addSlot 序（扩展槽插在输入与输出之间）；`SimpleMachineBlockEntity.java:82-90` 的 handler 常量（OUTPUT=5, SPEED=6, ENERGY=7, CREATIVE=8, POWER=9）；`SimpleMachineMenu.java:167-169` 的 `machineSlotCount` 公式（扩展时 = TOTAL_SLOTS = 14，非扩展时 = 10）；**同文件 188-189 行的注释已经写明「位置与索引两套编号在此不重合，只能按位置算」——但作者只把「输入槽」按位置算了，升级/能源四条分支仍用索引。**
- **对照**：非扩展机器（多数）handler 下标与菜单位置恰好重合，所以症状只在搅拌机/智能烤炉上出现。

#### I-C 【可机械修复】4 个服务端→机器的下单包把客户端 `quantity` 原样下发，13 个 `setOrder` 里 4 个完全不钳制

- **位置（包）**：`network/OrderRecipePacket.java:67-70`（`setOrderReflectively(be, recipeId, packet.quantity)`，**无任何范围检查**）；`network/GrillSeasoningOrderPacket.java:48`；`network/SkewerThreadingOrderPacket.java:70,85`。（`NetworkOrderPacket.java:52` 有 `quantity <= 0` 早退，**不在其列**。）
- **位置（不钳制的 setOrder）**：`blockentity/SmartCookingPotBlockEntity.java:1271`、`blockentity/SkeweringMachineBlockEntity.java:855`、`blockentity/SimpleMachineBlockEntity.java:1402`、`blockentity/GrillBlockEntity.java:569`（都是 `orderQuantity = quantity;` 原样存）。另外 9 个实现自带 `Math.max(1, q)` 或 `Math.max(0, q)`（`UniversalCuttingMachineBlockEntity:481`、`NutRoasterBlockEntity:710`、`ElectricGrindingMachineBlockEntity:456`、`ChocolateCannonBlockEntity:844`、`IceMakerBlockEntity:964`、`GrindingFactoryExecutor:224`、`CookingFactoryExecutor:236`、`PlantingCuttingFactoryExecutor:194`、`SkeweringFactoryExecutor:435`）。
- **已坐实的一条后果链（`SkeweringMachineBlockEntity` + `quantity = -5`）**：
  1. `SkeweringMachineBlockEntity.java:853-858` 存入 `orderQuantity = -5`，`orderRecipeId` 非 null；
  2. 同文件 323 行 `if (machine.orderQuantity > 0 && ...)` ⇒ 为假 ⇒ **订单门禁整个失效**；
  3. 同文件 334 行 `if (machine.orderQuantity > 0)` ⇒ 为假 ⇒ **订单永远不会被清掉**（`orderRecipeId` 恒非 null）；
  4. `ae2/MekckAe2.java:1732-1741` 的 `orderStateOf` 反射到 `getOrderRecipeId`（非 null）与 `getOrderQuantity`（-5）⇒ `OrderState(true, -5)`；`OrderState.done()`（`ae2/OrderState.java:12-14`）= `!hasRecipe && remaining<=0` = **false**；
  5. `ae2/MekckAe2.java:1622` 的 `if (!orderDone()) return;` ⇒ 若这台机器同时持有 AE2 job，**`processJob` 永久早退、`job` 永不释放**（连带 I-D 的锁死）。
- **性质**：正常客户端只发正数，所以这是**健壮性/防篡改**问题（需改动过的客户端），不是玩家能误触的路径。
- **机械修法**：在 4 个包的服务端 handle 里统一 `int qty = Math.max(1, packet.quantity);`（`SkewerThreadingOrderPacket` 的自选分支同样处理）。这**不改变正常客户端的任何行为**。

#### I-D 【需设计决策】AE2 `job` 无超时、无取消回收路径 → 可永久锁死一台机器的 ME 自动化

- **位置**：`ae2/MekckAe2.java:2047-2067`（`pushPattern`）、`1616-1640`（`processJob`）、`2069-2072`（`isBusy`）、`1186`（`private AeJob job;`）。
- **机制**：`job` 只在 `job.remainingOutput <= 0` 时被清（1633-1639）。没有任何超时、没有 AE2 取消回调、没有「机器长时间不出产物」的看门狗。
- **触发条件（三条都可达）**：
  1. `pushPattern` 只把**部分**输入装进机器（余料掉地，见 I-E）⇒ 配方凑不齐 ⇒ 永不出产物；
  2. 机器产不出 `entry.outputs` 里的物品（缺电、红石禁用、产物槽被占满、配方被数据包改掉）；
  3. 玩家在 ME 终端取消该合成任务——AE2 只停止驱动 provider，**不会把已投进机器的材料收回**，`job` 也不受影响。
- **后果**：`isBusy()` 恒真 ⇒ AE2 侧对这台机器的 `pushPattern` 全部被拒（`2048: if (job != null || ownerBusy()) return false;`）；机器输入槽里的料留在机器里。唯一的解除途径是**区块卸载/重载**（`job` 是运行时字段，`saveToNBT`(1325-1338) 不写它，所以不构成永久存档损坏）——但玩家看不出这一点。
- **证据**：上述行号 + `1622: if (!orderDone()) return;` + `1633-1639` 的唯一清除点 + `saveToNBT` 只写网格节点与勾选清单（1325-1338）。
- **为什么列【需设计决策】**：修法有三条互斥路线（超时放弃 / 让 `pushPattern` 在装不下时返 false / 在 `isBusy` 里加订单维度兜底），涉及「谁来承担失败」的语义，不是一处常量改写。

#### I-E 【需设计决策】`pushPattern` 在「输入没全部进入机器」时仍返回 true，且余料直接掉在世界里

- **位置**：`ae2/MekckAe2.java:1955-1971`（`insertIntoPortWindow`，1962-1968 是容量判断与掉落）、`2063-2066`（`insertIntoMachine(inputs); job = ...; startOrder(entry); return true;`）。
- **触发**：目标窗口剩余空间 < 本次样板输入量（例如输入区已被玩家塞满）。
- **后果**：AE2 合成 CPU 在调用 `pushPattern` **之前**已经从网络扣走了材料（`2051-2053` 的注释即为此事实），装不下的部分被 `BigStackDrops.dropAbove` 丢在机器脚下，而方法**返回 true**。于是「网络付了费」+「地上多了实物」⇒ 玩家捡回即等价复制；同时 `job` 记录了一笔永远无法完成的产出（叠加 I-D）。
- **证据**：`1955-1971`、`2051-2067`；对照 `1902-1944` 的传统路径同样掉落（1937-1941）——两处都写着「连扩展槽也装不下才落到世界」，是**刻意**的，所以属设计决策而非笔误。
- **可选机械修**：让 `insertIntoMachine` 返回「未被接受的量」，非 0 时 `return false`（AE2 契约里 false = provider 现在收不下，材料留在 CPU，会重试或转投别处）。这会**改变现有行为**（不再掉地），需 Lead 拍板。

#### I-F 【需设计决策，且需 client/ 侧确认】6 个工厂菜单未覆写 `getInventoryYOffset()` → 大方阵与玩家背包物理重叠

- **位置**：`menu/{Cooking, CuttingMachine, Grill, Grinding, PlantingCutting, Skewering}FactoryMenu.java`（全都只有构造器 + GUI 转发，**不覆写 `getInventoryYOffset/getInventoryXOffset`**）；`machine/MekCkMachineTile.java:119-128,406-418`。
- **证据**：
  - `javap -c mekanism.common.inventory.container.MekanismContainer#getInventoryYOffset`：`bipush 84; ireturn`（`getInventoryXOffset` 同理 = 8）；
  - `MekanismContainer.addInventorySlots` 字节码偏移 8-128：主背包 3×9 于 `y=invTop+row*18`、快捷栏于 `y=invTop+58` ⇒ 玩家槽占 **y 84..160**；
  - `MekCkMachineTile` 的方阵：`INPUT_START_X=38`(:119) / `GRID_START_Y=41`(:125) / `SLOT_STEP=18`(:123)，列数 `ceil(sqrt(N))`，`addSlotGrid` 的 `y = 41 + (i / columns) * 18` ⇒ `SINGULARITY`（N=81，9 列 9 行）时方阵占 **y 41..185**；
  - 全仓库 `grep getInventoryYOffset src/` = **0 命中** ⇒ 没有任何菜单/屏幕覆写它。
- **后果**：重叠区内原版 `AbstractContainerScreen.getSlotAt` 取 `menu.slots` 里**第一个**命中的槽（机器槽在前）⇒ 这 27 格玩家背包在奇点/星云档工厂里**点不到**，且视觉上槽框叠在一起。（基本档 3 槽只有 2 行，不重叠；档位越高越严重。）
- **为什么列【需设计决策】**：修法有两条互斥路线——① 每个家族按 `ceil(sqrt(N))` 动态覆写 `getInventoryYOffset()`（但 `CookingFactoryMenu` 还有 144 格存储、`GrillFactoryMenu` 还有 3 个调味料槽，都要算进去）；② 学 skewering/cooking 的做法把大区槽位移到屏幕外、由客户端 `GuiVirtualSlot` 承载。屏幕侧是否有补偿我**未读**（`client/` 属 rev-client-recipe 范围），请该审查者确认后再定。

#### I-G 【可机械修复，需同步改口径】I8 仍未修 + 新证据（可达性与「零反馈」已坐实）

> 按要求：不重复上报，只补「仍未修 + 新证据」。

- **位置**：`ae2/MekckAe2.java:697`（`extractAll` 内 `break; // 一个网络条目只满足一个需求项`）与 `495`（`pullNetworkInputs` 内同款 `break`）。**今天两处都还在。**
- **新增证据 1（可达性）**：`ae2/MekckAe2.java:394-397`（`pullGenericIngredients`）把 `recipe.getIngredients()` 的**每一项**变成一个 `InputSpec`。含重复材料的配方（同一 `Ingredient` 出现两次，或两个 `Ingredient` 能被同一个 `AEItemKey` 满足，例如 `#forge:crops` 与 `minecraft:wheat`）⇒ 两个 spec 都能被同一个网络条目满足；但第 681-699 行的循环对每个网络条目只匹配**第一个**未满足 spec 然后 `break` ⇒ 第二个 spec 永远等不到那一条目（`getAvailableStacks()` 每个 key 只遍历一次）⇒ `unsatisfied > 0` ⇒ `rollback`（747-751）并返回 null ⇒ 整单失败。
- **新增证据 2（玩家零反馈，这是 I8 真正的痛点）**：面板的「可做份数」与 I8 用的是**两套互不相干的算法**，且预检侧会把同一份库存算两遍：
  - `maxCraftable`(207-215) 与 `maxCraftableOf`(303-311) 都是**逐需求项独立取 min**，`countAvailable`(724-733) / `countAvailableKey`(314-333) 每次都把该 key 的**全部存量**算一遍 ⇒ 重复材料的两项各得一份完整库存 ⇒ **面板显示「可做 N 次」**；
  - `canExtractAll`(660-667) 同样逐项调 `canExtractFromNetwork`(735-745)（也各自独立求和），所以预检**通过**；
  - 真正的 `extractAll` 因 `break` 失败 ⇒ `pullGenericIngredients` 返回 false（`402`）⇒ `NetworkOrderPacket` 的 handle 忽略返回值（`network/NetworkOrderPacket.java:58`）⇒ **玩家点了没反应、没有任何提示**。
- **该不该修 / 修了会怎样**：`break` → `continue` 即可让同一条目继续满足其余未满足项（`storage.extract` 的返回值 `got` 是真实的，不会超抽；`e.getLongValue()` 的陈旧只影响 `take` 上限，不影响正确性）。**行为变化是正向的**：原本必然失败的订单会开始成功。但必须**同时**修 `canExtractAll`/重复计数口径，否则「预检过、实抽败」的空隙依旧（只是窗口变窄）。⇒ 标【可机械修复】，但按 I8 既定口径请 Lead 确认后再动。

---

### 1.3 Minor

| # | 位置 | 问题 | 证据与影响 |
|---|---|---|---|
| M1 | 6 个工厂菜单的类 javadoc（如 `CuttingMachineFactoryMenu.java:13-19`、`GrillFactoryMenu.java:13-18`） | 写的槽位装配顺序是「玩家背包/快捷栏/**副手** → 升级槽 → tile 槽」，与 Mek 实际顺序**相反**，且**根本没有副手/盔甲槽** | `MekanismTileContainer` 构造器字节码：`super` → `tile=...` → `addContainerTrackers()` → **`addSlotsAndOpen()`**；`addSlotsAndOpen` 偏移 0-20 先 `addSlots()`（= 升级 2 格 + `tile.getInventorySlots(null)`）再 `addInventorySlots`；后者偏移 8-128 只建 27 `MainInventorySlot` + 9 `HotBarSlot`。纯文档债 |
| M2 | `machine/grill/GrillFactoryTile.java:264-267` | `meManualOnlyItemSlots()` 只含 3 个调味料槽，**漏了能量槽**（`CuttingFactoryTile.java:312-321` / `GrindingFactoryTile.java:288-297` 都显式列入） | 当前**无可观测后果**：`MekPortWindow.ofPorted`(103-135) 只用它做**减法**，能量槽本来就不在 `mePatternItemInputs()`（=getInputSlots()）里。但契约不自洽：若将来有消费方按「全部槽 − manualOnly」求输入口，能量槽会被当配料口 |
| M3 | `machine/ports/IMekCkPorted.java:45-51` | `mePersistentItemInputs()` 的名字（「跨订单不清空」）与唯一消费方语义（`MekPortWindow.java:110` 把它**并入 inputs**）不符；默认实现返回 `mePatternItemInputs()` ⇒ 恒等变换 | 4 个家族（Grill/PlantingCutting/Skewering/Cooking）未覆写，与 `CuttingFactoryTile.java:283-297` / `GrindingFactoryTile.java:263-276` 那句「**必须覆写成空**……沿用默认语义会让 AE2 每单都以为还欠着料，于是重复投料、产物翻倍」**直接矛盾**。实测无差异（并入后按 identity 去重），但两条 javadoc 互相误导 |
| M4 | `ae2/MekckAe2.java:1732-1741` | `orderStateOf` 反射 `getOrderRecipeId` / `getOrderQuantity`；**6 个新家族 tile 全都没有 `getOrderRecipeId`** | `SkeweringFactoryTile`/`CookingFactoryTile` 只有 `getOrderQuantity` ⇒ `hasRecipe` 恒 false，`done()` 退化成「只看 quantity」；Cutting/Grinding/PlantingCutting/Grill 两者都没有 ⇒ `OrderState(false,0)` ⇒ `orderDone()` **恒 true**、`ownerBusy()` **恒 false**。当前无可观测错误（`pushPattern` 另有 `job != null` 兜底），但「busy 判定」实际只由 `job` 承担，属语义巧合 |
| M5 | `util/RecipeCache.java:42-58` | 返回**活 Recipe 对象**的不可变列表；跨 `/reload` 仍被调用方字段持有的引用会变陈旧 | 我未找到「跨 reload 持有」的调用点（`MekckAe2` 每次重新 `RecipeCache.all`），故仅记录 |
| M6 | `menu/CentralKitchenMenu.java:174-175,448-457` | 三明治样品槽加在 `machineSlots = VISIBLE_MODULES+VISIBLE_STORAGE+VISIBLE_OUTPUT` **之外** | ① 样品槽被当成「玩家槽」：shift-click 它会走「安装模块 / 进存储区」分支，放不下则 `return EMPTY`，**无法 shift 回背包**；② `index<machineSlots` 分支的区间 `[machineSlots, slots.size())` **含样品槽**，而合并阶段不查 `mayPlace` ⇒ 满背包时同名物品可能被并进样品槽。`mayPlace` 只在第二个阶段被查（`CentralKitchenMenu.java:129-131`） |

---

### 1.4 未经证实（不算结论）

| # | 疑点 | 为什么未坐实 |
|---|---|---|
| U1 | `GrillSeasoningOrderPacket` 送 `quantity = 0` 是否让烧烤工厂进入「订单门禁开着、订单量 0」的悬空态 | `GrillFactoryExecutor.setOrder`(213-219) 用 `Math.max(0, quantity)` 且 `getOrderQuantity()`(189-190) 在 `orderRecipeId==null` 时才返 0；「0 量订单」是否被 `advanceOrder`(417) 视为已完成我未读完该执行器 |
| U2 | I-F 的重叠是否被 `client/` 的 Screen 用别的方式规避（自绘背包背景 / 隐藏槽 / 滚动） | `client/` 不在我的审查范围，我未读 `*FactoryScreen`。已交给 rev-client-recipe 复核 |
| U3 | `MekckAe2.pushPattern` 返回 true/false 时 AE2 合成 CPU 的确切重试语义 | 未读 AE2 源码；只按 `ICraftingProvider` 的公开契约表述 |
| U4 | `KitchenFilterSyncPacket` 按 familyOrdinal 键控在「同机双厨房」时的具体表现 | 上一轮已记为 Minor；本轮确认服务端发送端逐机发送（`KitchenFilterPacket:75` → `sendFilterSync(player)`），客户端缓存仍不分机。**未升级**（没有实机证据证明串味可见） |
| U5 | `MeOrderTogglePacket` 无客户端发送 | 已记录；本轮全仓库未发现 `sendToServer(MeOrderTogglePacket` 调用点，**确认仍无发送方** |

---

## 2. quickMoveStack 对账表（23 个菜单全覆盖）

> 「菜单下标」= `AbstractContainerMenu.slots` 的位置；「handler 下标」= BE 的 `ItemStackHandler` 索引。二者只有在菜单按 handler 顺序、且不跳过槽时才重合——本表的「一致?」列就是这两套编号混淆的产物。

### 2.1 6 个工厂菜单（迁移产物，**无手写 quickMoveStack**）

槽位真值一律来自 `MekCkMachineTile.getInitialInventory`(276-313，HEAD 为 275-314) 的 builder 追加顺序：**输入方阵 → 输出方阵 → 能量槽 → `appendExtraSlots`**；菜单侧由 `MekanismTileContainer.addSlots()` 变成 **升级槽(menu 0) → 升级输出槽(menu 1) → 上面的 tile 槽 → 玩家 27+9**。

| 菜单 | 菜单下标 → 槽真值 | 玩家区 | 与 BE 真值一致? |
|---|---|---|---|
| `CookingFactoryMenu` | 0,1 升级 → 2..7 输入(6) → 8..19 输出(9 产物 + 3 返还) → 20 能量 → 21..164 存储(144) | 165..200 | 一致（`CookingFactoryTile.getStorageSlots/productSlots/returnSlots` 与 `MekCkMachineTile` 的 input/output 列表同源） |
| `CuttingMachineFactoryMenu` | 0,1 升级 → 2..2+N-1 输入 → 2+N..2+2N-1 输出 → 2+2N 能量（N=`tier.processes`） | 2+2N+1.. | 一致 |
| `GrillFactoryMenu` | 同切菜 → 末尾 3 个调味料槽（`GrillFactoryTile.appendExtraSlots`）→ 能量槽在调味料**之前** | 随 N 与 +3 | 一致 |
| `GrindingFactoryMenu` | 同切菜 | 同上 | 一致 |
| `PlantingCuttingFactoryMenu` | 同切菜 → 末尾 营养液槽 + 生长槽（`PlantingCuttingFactoryTile.appendExtraSlots`） | 随 N 与 +2 | 一致 |
| `SkeweringFactoryMenu` | 0,1 升级 → 2..4 输入(3) → 5..6 输出(产物+返还) → 7 能量 → 8..88 存储(81) | 89..124 | 一致 |

**结论**：这 6 个菜单**没有任何手写槽位下标**（类体只剩构造器 + GUI 转发），shift-click 完全由 `MekanismContainer.quickMoveStack`（字节码已读）按**槽类型**分派（`InventoryContainerSlot` / `ArmorSlot` / `MainInventorySlot` / `HotBarSlot` + `IInsertableSlot.exists(window)`），**不存在「下标与 BE 错位」这一类缺陷**。任务描述里的「6 个菜单重写后下标可能错位」是**误报**，已排除（避免后人重复排查）。

### 2.2 15 个手写 quickMoveStack 的菜单

| 菜单 | 机器槽区（菜单下标 → handler 下标） | 边界常量 | else 分支目标用哪套编号 | 一致? |
|---|---|---|---|---|
| `UniversalCuttingMachineMenu` | 0→0, 1→1, 2→2, 3→3, **4→5(能源)** | `MACHINE_SLOT_COUNT=4`（**应为 5**） | handler（`SLOT_POWER=5`）⇒ 落在**玩家背包** | ✗ **C1 #2** |
| `ElectricGrindingMachineMenu` | 同上（`SLOT_POWER=5`） | `=4`（应为 5） | handler | ✗ **C1 #3** |
| `GrillMenu` | 0→0, 1→1, 2→2, 3→3, **4→5(能源)** | `=4`（应为 5）；且 95 行 `powerSlot = MACHINE_SLOT_COUNT` | 菜单下标（但值等于被点槽） | ✗ **C1 #1 + I-A** |
| `NutRoasterMenu` | 0→0 … 5→5（输入/输出/速/能/创造/能源） | `=TOTAL_SLOTS=6` | handler（=菜单，重合） | 一致 |
| `IceMakerMenu` | 0→0 … 10→10（含 5 个冷萃） | `=TOTAL_SLOTS=11` | handler（重合）+ 冷萃目标 5..9 | 一致 |
| `ChocolateCannonMenu` | 0→0(输入),1→1(副),2→2(输出),3..5,6..10(费列罗×5),11..12(流体),13(能源) | `=TOTAL_SLOTS=14` | handler（重合） | 一致 |
| `BioreactorMenu` | 0..15 输入, 16 能源, 17 储罐 | `=TOTAL_SLOTS=18` | handler（重合） | 一致 |
| `WineCellarMenu` | 0..8 存储, 9 能源 | `=TOTAL_SLOTS=10` | handler（重合） | 一致 |
| `SimpleMachineMenu`（普通） | 0..4 输入,5 输出,6 速,7 能,8 创造,9 能源 | `=TOTAL_SLOTS-EXT=10` | handler（重合） | 一致 |
| `SimpleMachineMenu`（扩展槽机器） | 0..4 输入, **5..8 扩展输入(→10..13)**, 9 输出(→5), 10 速(→6), 11 能(→7), 12 创造(→8), 13 能源(→9) | `=TOTAL_SLOTS=14` | **handler**（偏移 +4） | ✗ **I-B** |
| `SimpleMachineMenu`（陈酿机） | 0..4 输入,5 输出,6..9 升级/能源, 10 果汁(→10), 11 返还(→11), 12 流体(→12, 屏外) | `=10+3=13` | handler（重合） | 一致 |
| `SkeweringMachineMenu` | 0..2 输入,3 输出,4 返还,5 速,6 能,**7..87 存储(→7..87)**,**88 能源(→89)** | `=8+81=89`（含能源） | **handler**（能源 89 ≠ 菜单 88） | ✗ **C1 #4** |
| `SmartCookingPotMenu` | 0..5 输入,6 输出,7 返还,8 速,9 能,10..90 存储(→10..90),**91 能源(→92)** | `=10+81=91`（**少算能源槽**） | **handler**（92 ≠ 91） | ✗ **C1 #5** |
| `PlantingCuttingStationMenu` | 0→0,1→1(营养液),2→2(输出),3..6(速/能/创造/气体),**7→7(能源)**,8→8(生长) | `=9` | handler（重合） | 一致 |
| `CentralKitchenMenu` | 0..19 模块(→MODULE_START..),20..73 存储(动态索引),74..82 输出,**83 样品槽** | `=20+54+9=83`（**样品槽在 83，落在界外**） | 菜单位置（0..VISIBLE_MODULES / VISIBLE_MODULES..+VISIBLE_STORAGE） | 一致，但见 **M6** |
| `SandwichAssemblerMenu` | 0..31 有序(→0..31),32..58 材料(→**33..59**),59 样品(→32),60 输出(→63),61..63 返还(→60..62),64..67 升级/能源 | `=TOTAL_SLOTS=68` | **菜单位置**（168-172 行注释已明确说明），范围 32..59 与 0..32 | 一致 |
| `IceFactoryMenu` | 输入 0..p-1、输出 p..2p-1、速 2p、能 2p+1、堆叠 2p+2、冷萃/创造/能源按 `slots.size()` 现场取 | `powerSlotIndex+1`（现场取） | **菜单位置**（`cbSlotStartIndex`/`creativeSlotIndex`/`powerSlotIndex` 全由 `slots.size()` 得到） | 一致 |

**表外说明**

- `IUpgradeMenu` / `ISideConfigurableMenu` 是接口，无槽位。
- `IceFactoryMenu` 是唯一一个**从一开始就用菜单位置**计算所有目标的菜单，可以直接作为 C1/I-B 的修法样板（`IceFactoryMenu.java:89,95,102,163-166`）。

---

## 3. 网络包边界校验（28 个包 + PacketGuard + ModMessages）

**结论：28 个包的「服务端是否校验距离」全部合格。**

- 18 个 C2S 包**全部**走 `PacketGuard.target(...)` 或 `PacketGuard.allowed(...)`（`SideConfigPacket:67`、`AutoDistributePacket:31`、`OrderRecipePacket:62`、`RedstoneControlPacket:40`、`GrillSeasoningOrderPacket:47`、`SkewerThreadingOrderPacket:62`、`NetworkOrderPacket:53`、`NetworkRecipeRequestPacket:38`、`AutoProcessListRequestPacket:34`、`AutoProcessTogglePacket:38`、`GrillWorkModePacket:31`、`GrillSeasoningTogglePacket:34`、`IceAttackConfigPacket:50`、`NetworkPullPacket:47`、`UpgradeUninstallPacket:56`、`MeOrderTogglePacket:41`、`KitchenViewPacket:51`、`KitchenOrderPacket:49`、`SandwichConfigPacket:47`、`KitchenFilterPacket:59`、`NetworkMissingRequestPacket:43`、`WineryClearJuicePacket:49`、`WineCellarConfigPacket:40`），与菜单 `stillValid`（21 个菜单一律 `distanceToSqr(...) <= 64.0D`，逐个人工核对）同口径。⇒ **「GUI 开着 ⟺ 包被接受」的空隙没有出现**（迁移未破坏这条）。
- 10 个 S2C 包（`NetworkRecipeListPacket` / `AutoProcessListPacket` / `NetworkMissingPacket` / `KitchenOrderResultPacket` / `KitchenFilterSyncPacket` 等）不需要距离校验；解码侧全部用 `PacketGuard.clampCount(n)` 夹住预分配（`AutoProcessListPacket:34,37`、`NetworkRecipeListPacket:45`、`KitchenFilterSyncPacket:51`、`SkewerThreadingOrderPacket:52`），仍按原始 `n` 逐个读 ⇒ 越界包在读流耗尽时正常抛错，不会被静默截断（符合 PacketGuard 的类注释承诺）。

**越界 slotIndex / ordinal 是否会让服务端抛异常**——逐个查证结果：

| 包 | 字段 | 服务端校验 | 结论 |
|---|---|---|---|
| `SideConfigPacket` | `directionOrdinal` / `sideModeOrdinal` | `:69-70` 双向显式夹紧 | 安全 |
| `KitchenFilterPacket` | `familyOrdinal` / `index` | `:61` 夹紧家族；`index` 交给 `KitchenFilter.remove`，后者 `:96` 有 `index < 0` / `index >= items.size()` 的显式早退 | 安全（**排除误报**） |
| `UpgradeUninstallPacket` | `slot`（任意 int） | 4 个 `uninstallUpgrade` 实现在碰 `items` **之前**全部先过滤：`SimpleMachineBlockEntity:632-637 trackerForSlot` 返 null 即 return；`IceMakerBlockEntity:497` / `ChocolateCannonBlockEntity:368` 区间判断；`IceFactoryBlockEntity:330-344` if-链 | 安全（**排除误报**） |
| `GrillSeasoningTogglePacket` | `index` | `GrillFactoryExecutor.isSeasoningEnabled/toggleSeasoningEnabled`(工作树 :250-257) `if (index >= 0 && index < SEASONING_SLOTS)` | 安全 |
| `IceAttackConfigPacket` | `value` 作为枚举 ordinal | 4 个 BE 都存原始 int，消费点是 `switch (targetType) { case ...; default -> ... }`（`IceMakerBlockEntity:1102-1106`、`IceFactoryBlockEntity:864-868` 等）⇒ 有 default，不会 AIOOBE | 安全（**排除误报**） |
| `SandwichConfigPacket` | `value` 作为模式 | `SandwichAssemblerBlockEntity:289-293` 显式夹紧 | 安全 |
| `KitchenViewPacket` | `value` 作为排序/滚动 | 排序 `:56-57` 夹紧；`CentralKitchenMenu.scroll:246-252` 夹紧 | 安全 |
| `WineCellarConfigPacket` | `speed` | `WineCellarBlockEntity:183-189` `Math.max(1, Math.min(50, s))` | 安全 |
| `RedstoneControlPacket` | `direction`（byte） | `IRedstoneControllable:13-16` 只用符号，任意值都安全 | 安全 |
| `KitchenOrderPacket` | `recipeId`（可解析为 null） | `placeOrder/previewOrder` 对 null 会走 `RecipeManager.byKey(null)`；guava `RegularImmutableMap.get(Object)` 字节码首条即 `ifnull 8`（null ⇒ 返回 null）⇒ `Optional.empty()` ⇒ 返回「找不到配方：null」 | 安全（**排除误报**） |
| `OrderRecipePacket` / `GrillSeasoningOrderPacket` / `SkewerThreadingOrderPacket` | `quantity` | **无校验**，且 4 个 `setOrder` 不钳制 | ✗ **I-C**（不是崩溃，是状态机被楔住） |
| `NetworkOrderPacket` | `quantity` | `:52` `if (packet.quantity <= 0 ...) return;`（上界不设，但抽料量受网络库存限制，全 long 运算） | 可接受 |

> 「越界下标会不会**静默改错槽**」：本轮**没有**发现任何包用「客户端给的槽下标」直接写 `ItemStackHandler` 的路径——唯一带原始 `slot` 的 `UpgradeUninstallPacket` 只会命中升级槽，且各实现都做了槽位白名单。这条与上一轮结论一致。

---

## 4. AE2 端口（IMekCkPorted）自洽性核对

消费方 `ae2/MekPortWindow.java:103-135` 的规则：`inputs = mePatternItemInputs ∪ mePersistentItemInputs − (mePatternItemOutputs ∪ meManualOnlyItemSlots)`，且 `outputs = mePatternItemOutputs − inputs`。

| 家族 | `mePatternItemInputs` | `mePatternItemOutputs` | `mePersistentItemInputs` | `meManualOnlyItemSlots` | `meGroupParallel` | 自洽? |
|---|---|---|---|---|---|---|
| CUTTING | `getInputSlots()`(:274) | `getOutputSlots()`(:279) | `List.of()`(:295) | 能量槽 + 升级槽(:312-321) | true | 是 |
| GRINDING | `getInputSlots()`(:254) | `getOutputSlots()`(:259) | `List.of()`(:274) | 能量槽 + 升级槽(:288-297) | true | 是 |
| GRILLING | `getInputSlots()`(:249) | `getOutputSlots()`(:254) | 未覆写（=inputs） | **仅 3 个调味料槽**(:264-267) | true | 见 M2（无后果） |
| PLANTING_CUTTING | `getInputSlots()`(:366) | `getOutputSlots()`(:371) | 未覆写 | 营养液槽 + 生长槽(:382-391) | true | 是 |
| SKEWERING | 3 输入 **+ 81 存储**(:344-349) | `getOutputSlots()`（产物+返还）(:352) | 未覆写 | `List.of()`（未覆写） | false(:364) | 是（能量槽不在 inputs 内） |
| COOKING | 6 输入 **+ 144 存储**(:468-473) | `getOutputSlots()`（9 产物+3 返还）(:482) | 未覆写 | `List.of()`（未覆写） | false(:493) | 是 |

- **有没有槽同时出现在 input 与 output？没有。** 六个家族的 `mePatternItemInputs` 都取自 `getInputSlots()`（+存储），`mePatternItemOutputs` 都取自 `getOutputSlots()`；这两条列表由 `MekCkMachineTile.addSlotGrid`(406-418) 按 `input` 布尔分开填充，**构造上不相交**。`MekPortWindow` 还额外做了一次 `used` 去重（126-133），属双保险。
- **有没有漏掉导致 AE2 往升级槽/能量槽投料？没有。**
  - 升级槽由 `TileComponentUpgrade` 单独持有，**不在** `getInventorySlots(null)` 里（`MekCkMachineTile.getInitialInventory` 从未 `builder.addSlot` 它）⇒ `MekPortWindow` 结构上够不着；
  - 能量槽在 `getInventorySlots(null)` 里（`getInitialInventory` 的 `energySlot = ...`(:309) 与 `builder.addSlot(energySlot)`(:311)），但不在任何 `mePatternItemInputs` 里 ⇒ 不会被投料。GRILL/PLANTING_CUTTING/SKEWERING/COOKING 未把它列进 manualOnly **当前不产生差异**（manualOnly 只做减法）——见 M2/M3。
- `meGroupParallelItemInputs` 只在 `MekckAe2` 的注释里被引用（`MekPortWindow` 类注释 32-48 明说「当前消费方集合下不产生可观测差异」），即**该开关目前是纯声明**，没有代码分支读它。这与 `MekPortWindow.java:44-48` 的自述一致，不算缺陷。

---

## 5. util/RecipeCache 与 /reload（任务第 6 项）

**结论：`RecipeCache` 没有重蹈 `BioreactorFuels` 的覆辙——它按 RecipeManager 实例缓存，而 `/reload` 确实会换掉 RecipeManager 实例。**

- `util/RecipeCache.java:34-35`：`WeakHashMap<RecipeManager, Map<RecipeType, List<Recipe>>>`；
- `/reload` 入口 `MinecraftServer.reloadResources` 字节码把工作交给 `lambda$reloadResources$25`，后者（`javap` 反汇编偏移 48）调用**静态工厂** `ReloadableServerResources.loadResources(...)`；
- `ReloadableServerResources.loadResources` 偏移 **0: new #2 ReloadableServerResources** ⇒ 每次都造**新实例**；其构造器偏移 72-77 是 `new RecipeManager(...)` + `putfield recipes` ⇒ **新 RecipeManager 实例**；
- ⇒ 新 `RecipeManager` 作为缓存键查不到旧表，缓存自动失效；旧键是弱键，重载后可回收（类注释 26 行的承诺成立）。

**残留（不构成缺陷，仅记录）**：`TYPE_CACHE` / `MISSING_TYPES` / `MISSED_LOGGED`（71-74 行）按 id 字符串缓存**永不失效**。配方类型是注册表项、`/reload` 不会改变它，所以安全；森罗酒馆那类「造了类型却不注册」的 id 已被 `TavernBarrelCompat.handles` 排除在负缓存之外（102-104 行），逻辑自洽。

> 对照：`BioreactorFuels` 按 `Level` 实例作键——`/reload` **不换 Level**，所以那个才是真问题。它在 `util/`（T2 写作用域），本轮只读，未改动；`docs/STATUS.md` 仍列其为未修。

---

## 6. 文件覆盖状态（72 个文件，逐个标注）

### ae2/（4）

| 文件 | 状态 |
|---|---|
| `ae2/MekckAe2.java`（2519 行） | **部分**：读了 156-345、340-540、630-830、900-1045、1177-1440、1590-1790、1789-2000、2000-2110、2480-2519。**未读**：1-155（注册/能力挂载）、540-630、830-900、1045-1177（供电/网格）、1440-1590（refreshPatterns 各家族分支）、2110-2480（buildXxxPatterns 全部配方构建器）。→ pushPattern / CPU 任务状态机**已覆盖**；样板构建器**未覆盖** |
| `ae2/MekPortWindow.java`（304） | 全文已看 |
| `ae2/INetworkPullable.java` | **未看**（仅由调用点反推：`getNetworkPullInputs/getInputSlotRange/getExtraInputSlots/supportsAutoPull/isMeOrderEnabled`） |
| `ae2/OrderState.java`（13） | 全文已看 |

### network/（30）

| 文件 | 状态 |
|---|---|
| `ModMessages.java` / `PacketGuard.java` | 全文已看 |
| 28 个包（SideConfig / AutoDistribute / OrderRecipe / RedstoneControl / GrillSeasoningOrder / SkewerThreadingOrder / NetworkOrder / NetworkRecipeRequest / NetworkRecipeList / AutoProcessListRequest / AutoProcessList / AutoProcessToggle / GrillWorkMode / GrillSeasoningToggle / IceAttackConfig / NetworkPull / UpgradeUninstall / MeOrderToggle / KitchenView / KitchenOrder / KitchenOrderResult / SandwichConfig / KitchenFilter / KitchenFilterSync / NetworkMissingRequest / NetworkMissing / WineryClearJuice / WineCellarConfig） | **28/28 全文已看** |

### menu/（23）

| 文件 | 状态 |
|---|---|
| 6 个工厂菜单（Cooking/CuttingMachine/Grill/Grinding/PlantingCutting/Skewering FactoryMenu） | 全文已看 |
| `SimpleMachineMenu` `IceFactoryMenu` `SkeweringMachineMenu` `SmartCookingPotMenu` `PlantingCuttingStationMenu` `ChocolateCannonMenu` `BioreactorMenu` `WineCellarMenu` `CentralKitchenMenu` `SandwichAssemblerMenu` `UniversalCuttingMachineMenu` `ElectricGrindingMachineMenu` `GrillMenu` `NutRoasterMenu` `IceMakerMenu` | 构造器 + `quickMoveStack` + `stillValid` **已看**；各文件内部的 `*Slot` 内部类**只看了被 C1 牵涉的那几个**（`GrillMenu.PowerSlot/InputSlot/OutputSlot`、`UniversalCuttingMachineMenu` 的四个、`CentralKitchenMenu.StorageSlot`）——其余内部类未逐行读（它们只影响渲染/工具提示） |
| `IUpgradeMenu` / `ISideConfigurableMenu` | **未看**（接口，无槽位，由 6 个工厂菜单的 javadoc 与实现签名反推） |

### integration/（14）

| 文件 | 状态 |
|---|---|
| 全部 14 个（`JEIPlugin`、11 个 RecipeCategory、`FeastPlatingRecipes`、`WineCellarInfoRecipe`、`BioreactorJeiRecipe` …） | **未看**（本轮聚焦「包与槽位对齐」；JEI 属显示层，无服务端状态） |

### 为完成对账而额外读的**范围外**文件（只读，未改）

`machine/MekCkMachineTile.java`（全文 1103 行）、`machine/ports/IMekCkPorted.java`、`machine/{cutting,grinding,grill,skewering,plantingcutting,cooking}/*FactoryTile.java`（端口 + 布局区）、`machine/grill/GrillFactoryExecutor.java`（订单段）、`blockentity/` 中与包/菜单直接相关的常量与 setter（Grill/IceMaker/IceFactory/ChocolateCannon/NutRoaster/WineCellar/SandwichAssembler/CentralKitchen/SkeweringMachine/SimpleMachine/Bioreactor 的槽位常量、`isUsablePowerItem`、`uninstallUpgrade`、`setOrder`、`setMode`、`setTargetType`、`setSpeed`）、`kitchen/KitchenFilter.java`、`util/PowerSlotUtil.java`、`util/Reflect.java`（头部）、`util/RecipeCache.java`、`ae2/OrderState.java`。

### 已查证**排除**（省得后人重复排查）

1. 6 个工厂菜单的「下标与 BE 槽位错位」——不存在（§2.1）。
2. `moveItemStackTo(stack, MACHINE_SLOT_COUNT, slots.size(), true)` 这类「机器→玩家」分支**不可能**自指（源 index 恒 < 区间起点）。
3. `PacketGuard` 距离校验在迁移后仍然覆盖全部 C2S 包（§3）。
4. 越界 `ordinal` 引发 `ArrayIndexOutOfBoundsException` 的四条候选路径（`targetType` 的 switch、`SandwichAssembler.setMode`、`KitchenFilter.remove`、`UpgradeUninstall.slot`）**全部有夹紧/白名单**。
5. `KitchenOrderPacket` 送 null recipeId 不会 NPE（guava `RegularImmutableMap.get(null)` 直返 null）。
6. AE2 端口不存在「同一槽既在 input 又在 output」或「能量槽/升级槽被投料」（§4）。
7. `RecipeCache` 不重蹈 `BioreactorFuels` 的 `/reload` 失效问题（§5）。
8. `ItemStackHandler.getStackInSlot` 返回活引用这一点**本轮实测确认**（字节码），所以「改 `getStack()` 的返回值不会触发 `onContentsChanged`」这条既有注释是对的，而 C1 正是利用同一事实（自我合并改的是容器里的那个对象）。

---

## 7. 需要 Lead 执行/转交的事项

### 7.1 需要 Lead 跑的编译与测试（我不跑 gradle）

1. 编译验证（我改任何文件之后必须）：`gradlew.bat clean compileJava --offline`——**clean 是必须的**（`docs/STATUS.md` 第五节第 5 条：增量编译对跨类引用是盲的）。
2. 全量测试：`gradlew.bat clean build --offline`
3. 若要我补回归测试，落点建议：`src/test/java/cn/ism/mekck/network/`（在我的 write scope 内）加一个**纯 JUnit** 的槽位数学断言（不依赖真 tile）：「对每个手写 qMS 的菜单，else 分支的单槽目标区间必须 ∉ [0, machineSlotCount) ∪ {被点槽的菜单下标}」——可把 5 处 C1 与 I-B 全部钉住。

### 7.2 建议的修复批次（等 Lead 发「开始修复」）

- **批次 1（C1 + I-A + I-B，同一根因，机械修）**：5 个菜单改用「构造器现场记录的菜单下标」，样板 = `IceFactoryMenu.java:89/95/102`。
- **批次 2（I-C，机械修）**：4 个包的 `quantity` 加 `Math.max(1, ...)`。
- **批次 3（I-G / I8，可机械但改变行为）**：`MekckAe2:697` 与 `:495` 的 `break` → `continue`，并同步修 `canExtractAll` 的重复计数口径。**需 Lead 先确认行为变化可接受。**
- **批次 4（I-D / I-E / I-F，需设计决策）**：不在本轮机械修复。
- M1/M2/M3/M4/M6 是文档债或零后果项，建议与批次 1 同 PR 顺手改注释（M2 若修，只需在 `GrillFactoryTile.meManualOnlyItemSlots()` 补能量槽，风险为零）。

### 7.3 跨范围协调

- **I-F 的屏幕侧**（`client/*FactoryScreen`）：请 Lead 转 `rev-client-recipe` 确认是否有自绘补偿。
- **`util/BioreactorFuels` 仍按 Level 作键**：写作用域属 T2（`rev-legacy-conservation`），本轮只读、未改。
- `ae2/INetworkPullable.java`、`menu/IUpgradeMenu.java`、`menu/ISideConfigurableMenu.java`、`integration/` 全部：我**未读全文**，如需覆盖请另派或告知我补读。

---

## 8. 复审清单（每条结论的最小可复核动作）

| 结论 | 一分钟复核 |
|---|---|
| C1 自指复制 | `javap -c` 原版 `AbstractContainerMenu#moveItemStackTo`，看偏移 91-133 的两条 `setCount`；再看 `GrillMenu.java:26,95-97` |
| C1 的菜单序 | 读 `GrillMenu.java:46-57` 的 5 个 addSlot，与 `GrillBlockEntity.java:71-77` 对照；`UniversalCuttingMachineMenu.java:26,44-56` 同理 |
| I-B | `SimpleMachineMenu.java:94-120` 与 `SimpleMachineBlockEntity.java:82-90` 对照，重点看 167-169 的 `machineSlotCount` |
| I-C | `OrderRecipePacket.java:67-70` + `SkeweringMachineBlockEntity.java:853-858,323` + `OrderState.java:12-14` |
| I-D / I-E | `MekckAe2.java:1616-1640,1955-1971,2047-2067` |
| I-G / I8 | `MekckAe2.java:495,697,660-667,394-397` |
| §2.1 排除 | `javap -c mekanism.common.inventory.container.tile.MekanismTileContainer#addSlots`（偏移 68-136 遍历 `getInventorySlots(null)`）；6 个工厂菜单全文（无 `quickMoveStack`） |
| §3 距离校验 | 在 `network/` 下 grep `PacketGuard`，应命中全部 C2S 包 |
| §5 /reload | `javap -c net.minecraft.server.MinecraftServer` 找 `lambda$reloadResources$25` → `ReloadableServerResources.loadResources`；再 `javap -c ReloadableServerResources` 偏移 0 `new` + 偏移 72 `new RecipeManager` |

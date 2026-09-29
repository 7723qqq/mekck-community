# mekck 全量代码审查报告（第二轮）

- 日期：2026-09-30
- 基线：HEAD `83830e0`（阶段 3 Task 7：烹饪工厂迁到 Mek 原生机器体系）
- 范围：`D:\mc\mod\mekck` 全量（332 个 Java 文件 / 75,500 行 + 1715 个资源）
- 方法：4 个 agent 按**包边界**并行深审（`machine/` 新内核 / `blockentity+block+util` 旧体系 /
  `ae2+network+menu+integration` 同步与自动化 / `client+recipe+upgrade+mixin` 与资源），
  Lead 独立复核每条 Critical、独占构建与 `git`，并负责跨域交叉验证
- 上一轮：`docs/audit/2026-09-29-full-code-review.md`（I1–I10）
- 交付形态：按用户选择「全量审查 + **直接修复缺陷**」

### 证据源（分域明细，与本报告同目录）

| 文件 | 覆盖 | 规模 |
|---|---|---|
| [2026-09-30-details/machine-core.md](2026-09-30-details/machine-core.md) | `machine/**` 新内核 + `MekckConfig` | 20 文件，Critical 2 / Important 4 / Minor 7 |
| [2026-09-30-details/legacy-conservation.md](2026-09-30-details/legacy-conservation.md) | `blockentity/` `block/` `util/`（含 35 个 `matchXxx` 对账表） | Critical 1 / Important 4 / Minor 5 |
| [2026-09-30-details/ae2-network-menu.md](2026-09-30-details/ae2-network-menu.md) | `ae2/` `network/` `menu/` `integration/`（含 23 菜单 quickMoveStack 对账表 + 28 包边界表） | Critical 1（5 处同源）/ Important 6 / Minor 6 |
| [2026-09-30-details/client-recipe-mixin.md](2026-09-30-details/client-recipe-mixin.md) | `client/` `recipe/` `upgrade/` `mixin/` + 资源校验（含 mixin 目标核实表） | Critical 2 / Important 2 / Minor 6 |

四份报告都含「逐文件已看/未看」清单，**未覆盖范围以它们为准**（本报告 §四只是汇总）。

---

## 一、本轮最重要的三条结论

### 1. 打包产物**启动即崩** —— 已修复并验证（C1）

真实实例日志（`D:\mc\新建文件夹\versions\1.20.1-Forge_47.4.23\logs\latest.log`，2026-09-30 01:38:17）：

```
[FATAL] [mixin/]: Mixin apply failed mekck.mixins.json:MixinItemStack -> net.minecraft.world.item.ItemStack:
org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException:
  @Inject annotation on mekck$writeBigCount could not find any targets matching 'save'
  in net.minecraft.world.item.ItemStack. No refMap loaded.
```

`mekck.mixins.json` 是 `"required": true`，所以这不是警告——游戏在 `Bootstrap` 阶段终止。
**即：从 I6 修复（`MixinItemStack`）落地起，这个 mod 在装出来的 jar 里根本起不来。**

根因链（三段独立证据）：
| # | 事实 | 证据 |
|---|---|---|
| 1 | 9 个 mixin 里**只有** `MixinItemStack` 打原版方法（`ItemStack.save`/`of`）且未写 `remap = false` | 源码逐条；其余 6 个都是 `remap = false` 打 mod 类 |
| 2 | 产物里**没有** refmap，`mekck.mixins.json` 也**没有** `refmap` 字段 | 解包 `build/libs/mekck-1.0.0.jar`：`*refmap*` 命中 0 |
| 3 | 没有 refmap 时 Mixin 0.8.5 会回退去找硬编码的 `mixin.refmap.json`，与本模组该生成的名字对不上 | `javap -c org.spongepowered.asm.mixin.transformer.MixinConfig`：`ldc "mixin.refmap.json"; putfield refMapperConfig` |

对照组（旁证）：同实例其它 mod 一律满足「有 refmap 文件 + 配置里有 `refmap` 字段」——
`create` / `farmersdelight` / `mekanism_extras` / `ae2.mixins` / `mixins.epp` 全部命中；mekck 是唯一两处皆无的。

**修复**（见 §三 C1）：手写 `src/main/resources/mekck.refmap.json`，SRG 名取自 ForgeGradle 自己的
`build/reobfJar/mappings.tsrg`（`ItemStack.save → m_41739_`、`ItemStack.of → m_41712_`），
结构逐字对齐 `farmersdelight.refmap.json`（`mappings` + `data.searge` 两份相同、**键是 mixin 类名**）。
**已验证**：产物内出现 `mekck.refmap.json`，且 jar 内 `mekck.mixins.json` 带 `refmap` 键。
**未验证**：需用户在真实实例再跑一次（本轮无法自动启动游戏）。

### 2. 三个家族的机器**根本建不出来** —— 已修复（C2）

`TileEntityMekanism` 的构造器内部回调 `getInitialInventory`（`javap` 实测构造器偏移 331），
而 `MekCkMachineTile.getInitialInventory` 末尾会调 `appendExtraSlots`。
烧烤 / 穿串 / 烹饪三家的槽位列表被写成 `= new ArrayList<>(N)` **字段初始化器**，方法里又 `clear()` ——
按 JLS 的初始化顺序，此刻字段还是 `null` ⇒ `NPE`。
即**这三家的任意一档方块放下去都建不出方块实体**（基类自己的类注释正是为警告这个坑而写的）。

### 3. 迁移到 Mek `BlockTile` 后，工厂**破坏时丢内容**，17 个方块**连方块都不掉** —— 已修复（C3）

- Mek 的 `BlockMekanism.onRemove` 只做 `removeBoundingBlocks` + `blockRemoved()` + `super`，
  **不含任何掉落**（`javap` 逐条确认），掉落完全由战利品表决定。
- 而本仓 55 张工厂战利品表**全是裸物品掉落**（全仓 `copy_nbt` 命中 0），Mek 自己则是
  `copy_nbt(source=block_entity, ops=[... → mekData.*])`（`advanced_combining_factory.json` 逐字取出）。
- 更严重：**烹饪工厂 12 档全都没有战利品表**，`blaze_*` 五家也各缺一张 ⇒ 共 **17 个方块破坏后什么都不掉**。
  （`Block.getLootTable()` 按注册名派生 `mekck:blocks/<path>`，文件不存在即空掉落。）

---

## 二、Critical / Important 清单（含状态）

### Critical

| # | 位置 | 问题 | 状态 |
|---|---|---|---|
| **C1** | `mekck.mixins.json` + 产物 | 无 refmap ⇒ `MixinItemStack` 生产环境应用失败 ⇒ **启动崩** | **已修 + 已构建验证** |
| **C2** | `Grill/Skewering/CookingFactoryTile.appendExtraSlots` | 构造期对 `null` 调 `clear()` ⇒ 三家族机器建不出来 | **已修** |
| **C3** | 72 张工厂战利品表 | 破坏丢全部内容；17 个方块完全不掉 | **已修**（55 改写 + 17 新建） |
| **C4** | `MekCkMachineTile.mekckPersistedSlots()` | 漏掉 `appendExtraSlots` 的家族专属槽 ⇒ 下标 ≥128 的槽**每次存读档静默丢失**（烹饪 35 格存储，全 12 档；烧烤/种植切配 SINGULARITY 各丢 3/2 格） | **已修** |
| **C5** | 5 个菜单 `quickMoveStack` | 把 **handler 槽位索引**当**菜单下标**传给 `moveItemStackTo`，区间含被点槽自身 ⇒ 原版合并分支自我合并 ⇒ **堆叠翻倍**（可无限刷） | **已修** |
| **C6** | `MixinTileComponentUpgradePersistence` | `@Redirect` 打在 `TileComponentUpgrade` 类上 = 全整合包**所有 Mek 机器**；`capOf(type,null)=0` ⇒ 玩家在 Mek 自己机器上的**静音/过滤/气体/锚定升级读档即清零、回写后永久消失** | **已修**（非 MekCK 机器走原实现） |

**C4 的证据链**（本项目最典型的一类静默丢失）：
1. `TileEntityMekanism.saveAdditional` 偏移 74~86 写 `Items = writeContainers(getInventorySlots(null))`；
2. `DataHandlerUtils.writeContents` 偏移 52 `i2b; putByte("Slot",(byte)i)`，
   `readContents` 偏移 37 `iflt`（负下标整条跳过）⇒ **下标 ≥128 的槽写出去是负数、读回来被丢**；
3. `ConfigHolder.getSlots(side,fn)` 第一支即 `if (side == null) return this.slots;`，
   `ConfigInventorySlotHolder.addSlot` 就是 `slots.add` ⇒ **byte 下标 == addSlot 插入序**；
4. 而 `mekckPersistedSlots()` 只返回 `[输入][输出][能量槽]`，**没有** `appendExtraSlots` 追加的槽。

**C5 的触发条件**（以 `UniversalCuttingMachineMenu` 为例）：菜单实际加了 **5 个机器槽**（0..4），
而 `MACHINE_SLOT_COUNT = 4` ⇒ 下标 4 落进「玩家侧」分支；玩家点**第 1 格主背包**（菜单下标 5）持能量物品时，
`moveItemStackTo(stack, SLOT_POWER=5, 6, false)` 的区间**正好含被点槽自身** ⇒ 自合并 ⇒ 翻倍。
其余 4 处同型（Grill / ElectricGrinding / Skewering / SmartCookingPot）。

### Important（已修 / 待裁决）

| # | 位置 | 问题 | 处置 |
|---|---|---|---|
| I10（上轮遗留） | `util/MekCkTransfer:40` | `existing.getCount() + source.getCount()` int 溢出为负 ⇒ **两个大堆叠同时消失**（本 mod 正是为超大堆叠而建） | **已修**（改 long） |
| — | `GrillFactoryExecutor` | 容量预演用 BD 门面栈、实际落槽按来源分派 ⇒ NBT 口径不同 ⇒ **产物静默消失 / 明明有位却拒绝加工** | **已修**（两处共用同一分派） |
| — | `SimpleMachineBlockEntity:4320` | 查找侧只验流体**量**不验**类型** ⇒ 空烧 FE + 永久停滞 | **已修**（补 `isFluidEqual`） |
| — | `SandwichAssembler` | 返还槽被异类占位时容器被销毁 | **已修**（掉到世界） |
| — | 3 个下单包 | `quantity` 无校验，4 个 `setOrder` 不钳制 ⇒ 负数量使订单门禁失效、AE2 job 永不释放 | **已修** |
| — | `SimpleMachineMenu` | 扩展输入槽机器上，升级/能源 shift-click 目标偏移 +4 ⇒ 全部静默失效 | **已修** |
| — | `GrillMenu` | `MACHINE_SLOT_COUNT` 少算电源槽，电源槽被当玩家槽 | **已修** |

### 需用户裁决（本轮**未**动）

| # | 问题 | 为什么不能机械修 |
|---|---|---|
| **I1**（上轮） | 17 个 BE 的 `ContainerData` 经 `ClientboundContainerSetDataPacket` 走 `writeShort` ⇒ 客户端显示负数 | 缩放修不了：装下 4 亿需除数 ≥12211，普通机器会显示 0。属架构改动，且是纯显示问题 |
| **I3**（上轮） | `SideMode.NONE` 落到 `default ->` 返回全权限 ⇒ 侧面配置失效 | 改成返回空会**断掉现有玩家**建立在「默认全开」上的全部管道/漏斗 |
| **I8**（上轮） | `MekckAe2.extractAll` 的 `break` ⇒ 含重复材料的配方永远匹配失败 | `break → continue` 会让「原本必败的订单开始成功」，且要同步修 `canExtractAll` 的重复计数口径 |
| **新** | AE2 job 无超时/无取消回收 ⇒ 可永久锁死一台机器的 ME 自动化 | 需要设计回收策略 |
| **新** | `pushPattern` 装不下仍返 `true`、余料掉地，而 CPU 已扣料 | 需要设计事务语义 |
| **新** | 6 个工厂菜单未覆写 `getInventoryYOffset()` ⇒ 高并行档方阵与玩家背包**物理重叠**（那 27 格点不到） | 需要每个家族给出 GUI 布局参数 |
| **新** | 6 个新工厂 GUI 的 `workProgress` **无任何同步通道**（旧 `ContainerData` 已删）⇒ 进度条几乎不动 | 需接入 Mek 的 `addContainerTrackers`，属架构改动 |
| **新** | 迁移器把烧烤/种植切配的调味料/营养液槽当升级卡处理，真升级卡被丢 | 二选一：沿用餐串/烹饪的「不搬」策略，或让迁移器接收家族自述的旧槽段 |
| **新** | 上一轮 I2 的旧档（缺 `Size` 键）在新迁移器上 `powerSlot=-1` ⇒ 能源槽物品 + 全部升级卡被丢 | 兜底策略需认可 |
| **新** | 中央厨房**移除模块**时正在加工的线程在 `load` 时被丢弃 ⇒ 已扣材料凭空损失 | 三选一：拒绝拆 / 吐回余料 / 卸载时结算 |
| **新** | `en_us.json` 缺 21 个键（12 个档位名由字符串拼接动态构造）⇒ 英文客户端显示 raw key | 资源层，需补键 |

---

## 三、验证

### 构建与测试

| 口径 | 结果 |
|---|---|
| `./gradlew clean build`（联网） | 见 §五「最终验证」 |
| 迁移前基线 | 272 测试 / 0 失败 |

**`--offline` 已不可靠**：本项目 `STATUS.md` 把 `clean build --offline` 写成验收口径，
但本轮实测它在 `compileJava` 阶段失败：

```
Could not find net.minecraftforge:forge:1.20.1-47.4.16_mapped_official_1.20.1_at_e188...jar
Error getting artifact ... from MinecraftUserRepo
```

原因是 AT/重映射产物落在**项目内**的 `build/fg_cache/`，`clean` 会删掉它，
而 `--offline` 又不允许 ForgeGradle 去补。**建议把验收口径改为联网构建**，或用一个不受 `clean` 影响的缓存目录。

### 其它验证手段

- 每个 Critical 都由 **Lead 独立复核**（不采信下游报告）：C4/C5/C6 的字节码链、C3 的战利品表清点、
  C2 的字段初始化器时序，全部自己重跑过一遍证据。
- 审查期间产生了**本项目第一次真实的运行期证据**：用户在开发实例里跑出的 `No refMap loaded` 崩溃日志。

---

## 四、覆盖率与残余风险（下一轮的入口）

四位审查者各自声明的**未覆盖**部分（汇总）：
- `ae2/MekckAe2` 只读了约 55%（`buildXxxPatterns` 约 370 行未读）
- `integration/` 全部 14 文件（含 `JEIPlugin` 751 行）
- `client/` 47 个文件只做了「Screen→Menu 同步 API 面」的 grep，未读实现
- **旧机器 Screen（仍走 `ContainerData`）的下标 ↔ 服务端 `addContainerData` 对账未完成**
  （需连读 21 个 Menu —— 两轮都点到这条，**建议下一轮做成专项**）
- `command/PlantingRecipeGenerator`（1573 行）只读了两段
- `kitchen/` `entity/` `effect/` `advancement/` `buff/` `api/` `item/` 全部未读
- `SimpleMachineBlockEntity`（4538 行）有数段只做了正则穷举 + 片段抽读

**运行期验证仍是最大缺口**：迁移后的 AE2 端口、GUI、真实存档往返、72 张战利品表的
「挖掉再放下」链路，**都只有静态证据**。本轮把这条链的最关键一环（`mekckPersistedSlots` 的覆盖范围）
补上了，但需要用户实机复跑。

---

## 五、最终验证（全部修复落地后，由 Lead 执行）

| 项 | 结果 |
|---|---|
| `./gradlew build` | **BUILD SUCCESSFUL** |
| 测试 | **308 tests / 0 failures / 0 errors / 0 skipped**（34 个测试类） |
| 本轮开始时基线（实测） | 272 tests / 0 失败 |
| 本轮新增回归测试 | **+36**（全部由本轮审查发现驱动） |
| 产物内含 `mekck.refmap.json` | **是**（`build/libs/mekck-1.0.0.jar`） |
| jar 内 `mekck.mixins.json` 带 `refmap` 键 | **是** |
| `TestMixinRefmapIntegrity` 三条断言 | **全绿**，且经**变异测试**证明非空转 |

### C1 的护栏（本轮补的最后一层）

C1 的修复物是一份**手写**的 refmap，而手写的东西会腐烂：只要有人给 `MixinItemStack` 再加一条
打原版方法的注入，**同一个启动崩溃会以完全相同的形态回来**，而编译、单测、打包都不会报错。
因此补了 `src/test/java/cn/ism/mekck/mixin/TestMixinRefmapIntegrity.java`，三条断言：

1. 配置里必须声明 `refmap`，名字与出厂那份一致；
2. 每一个**需要重映射**的 mixin（`@Mixin` 里没有 `remap = false`）的每条 `method = "..."` 选择器
   都必须在 refmap 里有条目；
3. **最强的一条**：拿 `build/reobfJar/mappings.tsrg`（ForgeGradle 重混淆**自己用的那份映射**）
   核对 refmap 里写的 SRG 名 —— 这条能抓住手写 refmap 最危险的错误形态：
   **格式对、内容错**（编译、打包、甚至「配置里声明了 refmap」全都看不出来，只有启动才炸）。

**变异测试**（证明护栏不是空转）：把 refmap 里的 `m_41739_` 改成 `m_99999_` 后重跑，
第 3 条**立刻变红**（另外两条按设计保持绿——它们查的是结构而非名字），改回后全绿。

顺带修掉一处不一致：`MixinExtremeSmithingMenu` 打的是 mod 类却缺 `remap = false`
（另外 6 个都写了），已补上并写明理由。

### 修复的验证来源（诚实标注 Lead 亲自核到什么程度）

本轮**每条 Critical 的"发现"都由 Lead 独立复核**（重跑字节码/清点资源）；
但**"修复"只有一部分是我逐行看过的**，列清楚以免高估：

| 修复 | Lead 的验证方式 |
|---|---|
| C1 refmap | **亲自写**，并用 `mappings.tsrg` 核对 + 变异测试 |
| C2 三家族构造期 NPE | 亲自读改动后源码确认判空写法 |
| C3 72 张战利品表 | 亲自读 `basic_cooking_factory.json` 全文（13 条 ops、前缀、物品名、conditions 全对） |
| C4 `getInventorySlots(null)` | 亲自读 `load`/`mekckPersistedSlots` 改动，并用测试钉住顺序 |
| C5 菜单自指区间 | 亲自读 `SkeweringMachineMenu`/`ElectricGrindingMachineMenu` 改动后的 `quickMoveStack` |
| C6 mixin 归属判定 | 亲自读 `mekck$decode/encode` 的回退分支 |
| `ISustainedData`（C3 的恢复侧） | 亲自读 `readMekckPersistentState` / `readSustainedData` / `hasMekckKeys` 与配置卡安全论证 |
| I10 `MekCkTransfer` | 亲自读 diff（long 相加 + 收窄注释） |
| Important 的其余各项 | **未逐行复核**，采信分域报告；已由 308 个测试覆盖编译与可测行为 |

### 修复的**连带影响**核验（收尾复盘，逐条给结论）

修缺陷本身会引入新缺陷。以下是 Lead 在收尾时专门去查的「修复可能带出的新问题」，
**查过但结论为「不成立」的也记下来**，免得下一轮重复排查：

| 疑点 | 结论 | 依据 |
|---|---|---|
| `mekckPersistedSlots()` 改成 `getInventorySlots(null)` 后，会不会与 Mek 写 `Items` 的顺序不一致？ | **不成立**。两处是同一个调用，同一次返回同一个列表 | `javap`：`TileEntityMekanism.saveAdditional` 偏移 74~86 与 `load` 偏移 76~90 都走 `getInventorySlots(null)`；`ConfigHolder.getSlots(side,fn)` 第一支 `if (side == null) return this.slots;` |
| `MekCkSlotNbt.read` 是「先清空再灌」，遇到**没有专属键**的存档会不会把槽位清空？ | **不成立**。缺键时第 180 行就 `return false`，清空发生在之后 | 源码 `MekCkSlotNbt:179-194`；另 `MekCkMachineTile.load` 注释也写明这条退化路径 |
| 旧存档迁移时，迁移器**会写 `MekCkSlots`** ⇒ 父类钩子 `readSustainedData` 会被触发，是否与 `load` 尾部的显式调用冲突？ | **不成立，且正是想要的行为**。链条自洽：钩子（偏移 59）先按 int 下标灌 → Mek 的 byte 下标 `readContainers`（偏移 90）覆盖 0..127 → `load` 尾部显式调用再把 int 下标那份覆盖回来，最终以 `MekCkSlots` 为权威 | `MekCkLegacyMachineNbt:282,285` 写 `mekckItems`；`MekCkMachineTile:1015-1023` |
| `ISustainedData` 上线后，**配置卡**（`ItemConfigurationCard`）复制/粘贴会不会变成「整机库存复制」或反向清空订单？ | **不成立**。写侧刻意空实现 ⇒ 卡里不含 MekCK 键 ⇒ 粘贴时 `hasMekckKeys` 为假 ⇒ 整段跳过；且这正是 T1 主动查出并堵住的一条物品复制漏洞 | `MekCkMachineTile:1153-1155`（空实现 + 三条调用点清单）、`readSustainedData` 前的闸门 |
| `getTileDataRemap()` 返回空表是否会让 Mek 的某些机制失效？ | **不成立**。全 jar 有 4 处调用，但全是 `invokespecial`（QIO 继承链的 `super.getTileDataRemap()`），**外部消费方 0 个** | 全 jar 常量池扫描 + 逐类 `javap -p -c`；注释已按此精确化（原先只写「零消费方」，容易被后来者当成漏扫） |
| 手写 refmap 会不会「格式对、内容错」而无人发现？ | **已封堵**。`TestMixinRefmapIntegrity` 用 `mappings.tsrg` 核对 SRG 名，并经**变异测试**证明会红 | §五「C1 的护栏」 |
| `MixinExtremeSmithingMenu` 打 mod 类却没有 `remap = false`（另外 6 个都有） | **确是不一致，已修**。改为类级 `remap = false`，与包内约定一致 | 由新护栏测试 `everyRemappedSelectorHasARefmapEntry` 暴露 |

**仍未验证的一件事**：C1 的修复效果需要**在真实实例里再跑一次**才能坐实
（本轮无法自动启动游戏；上一轮之所以能拿到运行期证据，是因为有人手工跑了一次实例，
其日志成了本轮最有价值的一条输入）。建议的复验步骤：
1. 把 `build/libs/mekck-1.0.0.jar` 覆盖进实例的 `mods/`；
2. 启动，确认不再出现 `No refMap loaded`；
3. 顺带验收另外两条：放一台**烹饪工厂**再挖掉（应掉出方块且内容还在）、
   任意工厂「放 → 塞物品/装升级 → 挖掉 → 再放下」（库存/能量/升级应回来）。

---

## 六、方法论与环境坑（值得写进 STATUS）

1. **`clean` 会删掉 `build/fg_cache`**，使 `--offline` 构建失败——验收口径要改。
2. **同一个 `build/` 目录不能被两个 agent 并发使用**：本轮出现一次
   「Gradle build daemon has been stopped: stop command received」。
3. **`build.gradle` 必须只有一个写者**：本轮 Lead 与一位审查者并发编辑同一文件，
   出现一次 `file changed since it was read`；审查者基于**错误推断**改动的内容已被 Lead 回退。
4. **Mixin 的 AP 不是无痛的**：装上 MixinGradle 0.7.38 + 注解处理器后，AP 会对 4 个
   `remap = false` 的 mod 类 mixin 报成员级错误（合成 lambda 名、`$VALUES`/`UPGRADES` 等 javac 合成字段无映射），
   而 `disableTargetValidator` / `@Pseudo` 都只能解决其中一条。本轮因此改为**手写 refmap**。
   若将来要实现自动生成，需要先解决这 4 条。
5. **注释里的断言必须实测**：本轮又一次出现「审查者按注释里的结论推理，得出了与字节码相反的结论」。

*（本报告由 Lead 汇总；每条 Critical 都经 Lead 独立复核后才采纳，下游报告中的未经证实项已单独标注。）*

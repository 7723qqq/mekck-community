# 审查报告：client/ + recipe/ + upgrade/ + mixin/ 与资源一致性

> ## ⚠️ 阅读前必读：本文件里 C1 的**修法建议**已被最终方案取代
>
> 本文件是审查**当时**的证据日志（保留原貌，不改写）。其中与「怎么修 C1」有关的部分
> **已作废**，以下是最终事实（由 Lead 在收尾时敲定并验证）：
>
> | 本文件里的说法 | 最终事实 |
> |---|---|
> | 建议「方案 A：加 MixinGradle 插件让 AP 生成 refmap」 | **走不通**。AP 装上后会对 4 个 `remap = false` 的 mod 类 mixin 报成员级错误（合成 lambda 名 `lambda$read$1`；`$VALUES`/`UPGRADES` 这类 javac 合成枚举字段无映射；Avaritia 不在 classpath），直接让 `compileJava` 失败。`mixin { disableTargetValidator = true }` 只关 `TargetValidator`，`@Pseudo` 只消掉 Avaritia 那一条，**都救不了**。 |
> | §10 提到 `mixingradle:0.7.5` 解析不到 | 正确，但**已无关**——build.gradle 的相关改动已整体回退（与 HEAD 一致），本模组**不依赖 MixinGradle**。 |
> | `add` 的第二个参数语义（本文件 §… 曾按 mixin 配置名理解） | 是 **refmap 名**（javap `MixinExtension`：`add(SourceSet,Object)` → `refMaps: Map`，配置名归 `config(String)` → `configNames: Set`）。 |
>
> **最终方案**：手写 `src/main/resources/mekck.refmap.json`，SRG 名取自
> `build/reobfJar/mappings.tsrg`（`ItemStack.save → m_41739_`、`of → m_41712_`），
> 结构对齐 `farmersdelight.refmap.json`（`mappings` + `data.searge`，**键是 mixin 类名**）；
> `mekck.mixins.json` 补 `"refmap": "mekck.refmap.json"`；
> 另加 `@Pseudo` 与 `remap = false` 到 `MixinExtremeSmithingMenu`。
> **已由 `TestMixinRefmapIntegrity` 三条断言钉住**，其中一条拿 `mappings.tsrg` 核对 SRG 名，
> 并经变异测试证明非空转。详见 `../2026-09-30-full-code-review.md` §五。

- 任务：task-4（审查者 + 修复者；本文件为审查阶段产出）
- 仓库：D:\mc\mod\mekck（MC 1.20.1 / Forge 47.4.16 / Mekanism 10.4.6.20 附属 mod）
- 基线：HEAD 83830e0（阶段 3 Task 7）
- 审查者：rev-client-recipe
- **未运行任何 gradle 命令**（含 ./gradlew / gradlew.bat / gradle）；未修改 src/ 下任何文件；未执行任何 git 写操作。
  本次全部为只读分析：read/grep 工具 + pwsh 下的 javap / python（脚本写在 %TEMP%，未落进仓库）。
- 结论：**Critical 2 · Important 2 · Minor 6 · 未经证实 5**
- **我判断最严重的一条**：C2（mixin 作用面过宽导致 Mek 自家机器的升级卡数量被静默删除 = 跨 mod 数据损失）。
  C1 更响（生产环境启动即崩），但 C2 是**已经在发生的静默数据损失**。

---

## 0. 摘要

| # | 级别 | 一句话 | 文件:行号 | 修复分类 |
|---|---|---|---|---|
| C1 | Critical（**审查期间已被并发修复，但未重建验证**——见 §10） | 生产 jar 里没有 refmap，MixinItemStack（唯一 remap=true 的 vanilla 目标）匹配不到 save/of；已由他人于 2026-09-30 在实机启动时撞到并改了工作区（未提交） | mixin/MixinItemStack.java:51,65 + build.gradle（原无 mixin 段） | 【可机械修复】已修；**待验证 + §10 的 classpath 版本风险** |
| C2 | Critical | @Redirect 打在 TileComponentUpgrade 类上 = 全整合包所有 Mek 机器；capOf(type,null)=0 使 MUFFLING/FILTER/GAS/ANCHOR/STONE_GENERATOR 在读档时被**丢弃**（该语义已被测试钉为有意），回写后存档永久丢失 | mixin/MixinTileComponentUpgradePersistence.java:39,79-94,142-151,186-192 + upgrade/MekCkUpgradeTypes.java:245-252,278-286 + upgrade/MekCkUpgradeCodec.java:120-124 | 【需设计决策】 |
| M1 | Important | 6 个工厂的进度条分子 workProgress **没有任何同步通道**（旧体系的 ContainerData 被整体删除后没有替代），客户端进度条恒停在约 1/批次长度 | machine/MekCkMachineTile.java:525,850-851,890-897 + menu/*FactoryMenu 的 getProgressRatio | 【可机械修复】但落在 menu/ + machine/（他人写作用域）→ 需 Lead 协调 |
| M2 | Important | en_us.json 缺 21 个键（其中 12 个档位名由 "tier.mekck." + tier.name 动态构造）→ 英文客户端显示 raw key | resources/assets/mekck/lang/en_us.json（322 键）vs zh_cn.json（343 键）；item/MekCkBlockItem.java:143-181 | 【可机械修复】 |
| m1 | Minor | assets/mekck/blockstates/mekck.json 指向不存在的模型 mekck:block/mekck（也没有 mekck:mekck 方块）→ 死资源 | assets/mekck/blockstates/mekck.json:1-7 | 【可机械修复】 |
| m2 | Minor | 两个 0 字节 Java 文件 | client/MekCkMeOrderButton.java、item/FerreroUpgradeProfile.java | 【可机械修复】 |
| m3 | Minor | SkeweringFactoryScreen 构造器声明了未使用的 tier（:62），:75 又重新取一次 | client/SkeweringFactoryScreen.java:62,75 | 【可机械修复】 |
| m4 | Minor | PlantingRecipeGenerator.generate **先删后建**：删除成功而后续任一步失败 ⇒ 该存档全部自动生成配方消失 | command/PlantingRecipeGenerator.java:108,123-131,176,189 | 【需设计决策】 |
| m5 | Minor | avaritia 已装 + 新存档首次启动时 cuf_01..49 标签要到 ServerStartedEvent 才生成，配方加载阶段必然 49 条解析失败 | UniversalCuttingMachine.java:1479-1508 + world/CreativeUpgradeFoodRotator.java:96-115 + data/mekck/recipes/creative_upgrade_from_49_foods.json:159-160 | 【需设计决策】 |
| m6 | Minor | 三个"背包可制作"判定用"命中槽数 == ingredient 数"，重复材料可误判（机器侧另有 matcher，只影响 JEI 类路径） | recipe/BeverageAssemblyRecipe.java:74-85、ExtractingRecipe.java:113-124、PackagingRecipe.java:62-72 | 【需设计决策】 |

> 已核对**没有**重复上报 I1–I10：I1（ContainerData 截断）在本轮 6 个新工厂里已不适用（它们一个 ContainerData 都不用）；
> I2（GrillFactoryBlockEntity 的 Size 键）已随迁移消失（blockentity/GrillFactoryBlockEntity.java 不存在）；
> I6（大堆叠 putByte 截断）**仍未修**在生产环境生效——见 C1；I9（Ingredient.EMPTY 恒 false）在自有配方层的可达路径逐条查过，见 §6。

---

## 1. Critical

### C1 —— 生产 jar 没有 refmap：vanilla 目标的 mixin 在正式环境匹配不到方法

> **状态更新（我在写报告期间，工作区被并发修改）**：本条已被另一位成员在**真实实例启动**时撞到并修好，
> 改动**尚未提交**（build.gradle 与 mekck.mixins.json 处于 M 状态）。我的独立推导与实机异常**完全一致**，
> 但他的修复**还没有重建验证**（build/ 与 libs/ 里依然没有任何 refmap），并且新引入了一个**离线构建风险**——
> 两者都记在 §10，请以 §10 为准执行验证。下面的原文保留作为独立证据链。

**文件:行号**
- src/main/java/cn/ism/mekck/mixin/MixinItemStack.java:51（@Inject(method = "save", at = @At("RETURN"))）
- src/main/java/cn/ism/mekck/mixin/MixinItemStack.java:65（@Inject(method = "of", at = @At("RETURN"))）
- src/main/java/cn/ism/mekck/mixin/MixinItemStack.java:39-40（作者原话："目标类属 vanilla，故用默认 remap = true（走 refmap 映射到 SRG 名）"）
- src/main/resources/mekck.mixins.json:2（required: true）、:16-18（injectors.defaultRequire: 1）
- build.gradle:163-213（dependencies，无 annotationProcessor）、:244-265（jar 任务，无 refmap 处理）；全文无 mixin { } 段

**触发条件**：在**生产环境**（正式 jar，非 dev runClient）加载本模组。dev 通过 build.gradle:78 的
arg '-mixin.config=mekck.mixins.json' 加载，此时目标类用 mojmap 名，save/of 恰好能匹配——所以本地开发**永远看不到**。

**后果**
- required: true + defaultRequire: 1 下，注入点找不到目标方法 ⇒ Mixin 注入失败。预期表现是**启动即崩**
  （Critical injection failure: @Inject annotation ... could not find any targets matching 'save'/'of'）。
- 即使把 config 调成非必需（不推荐）：I6（大堆叠掉落经 putByte("Count") 截断，5000→−120、256/4096→0）的修复
  **在生产环境完全不生效**——改了但没上。（I6 本身上一轮已记录；本条的"仍未修 + 新证据"就是：修复代码在正式 jar 里根本不会运行。）

**证据（全部离线可复现）**
1. 生产 jar 内无 refmap：libs/mekck-1.0.0.jar（2026-09-30 01:35，reobfJar 的 COPY 产物）与 build/reobfJar/output.jar
   的条目里只有 mekck.mixins.json，**没有** mekck.refmap.json；jar 内 mixins.json 与源码逐字相同、**无 refmap 键**。
   Get-ChildItem -Recurse -File build,libs -Filter '*refmap*' ⇒ 0 命中。
   该 jar 是最新的（含 79 个 cn/ism/mekck/machine/** class），不是陈旧产物。
2. 生产运行期的成员名是 SRG：build/reobfJar/mappings.tsrg:151050 原文
~~~
   of (Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/item/ItemStack; m_41712_
   save (Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag; m_41739_
~~~
   即运行时 ItemStack 上**不存在**名为 save/of 的方法。reobfJar 任务本身就是这条事实的产物。
3. 构建里没有任何 refmap 环节：build.gradle 无 mixin{}、无 annotationProcessor 'org.spongepowered:mixin:0.8.5:processor'。
   另核实 ForgeGradle 6.0.54 自身**不含** mixin 扩展：ForgeGradle-6.0.54.jar 共 231 个条目，
   路径含 mix（不区分大小写）的为 **0** —— 所以"FG 会自动加"这个假设不成立。
4. 修复所需的两个产物**已在本地 gradle 缓存**（可离线）：
   ~/.gradle/caches/modules-2/files-2.1/org.spongepowered/mixin/0.8.5/*/mixin-0.8.5-processor.jar、
   ~/.gradle/caches/modules-2/files-2.1/org.spongepowered/mixingradle/0.7.38/*/mixingradle-0.7.38.jar。

**为什么不是误报**：其余 6 个 mixin 全部显式 remap = false（目标是 Mek/FD 的类，类名在运行期不变），
只有 MixinItemStack 用默认 remap = true 且**注释里明写了依赖 refmap**——即"意图正确、接线缺失"。

**修复（【可机械修复】，但 build.gradle 属 Lead 写作用域，我不动）**
- 方案 A（推荐）：加 MixinGradle 插件（org.spongepowered.mixin 0.7.38）+ mixin { add sourceSets.main, 'mekck.refmap.json' }，
  让 AP 生成 refmap 并写进 jar，同时给 jar 内 mekck.mixins.json 注入 refmap 键。
- 方案 B：只加 annotationProcessor 'org.spongepowered:mixin:0.8.5:processor'，再用一个 processResources/jar 钩子把
  build/classes/java/main/mekck.refmap.json 加进产物，并给 mekck.mixins.json 手写 "refmap": "mekck.refmap.json"。
- **验证（不用启动游戏）**：重跑 ./gradlew build --offline，确认 build/reobfJar/output.jar 与 build/libs 内同时存在
  mekck.refmap.json 与含 refmap 键的 mekck.mixins.json，且 refmap 里有 save→m_41739_、of→m_41712_（命令见 §8）。
- 口径说明：我无法离线坐实"崩"还是"静默失效"的确切形态（没启动过生产客户端）——但两种结局都必须修。

### C2 —— 升级持久化 Mixin 的 @Redirect 作用面过宽 ⇒ **Mek 自家机器**的升级卡数量被静默删除

**文件:行号**
- src/main/java/cn/ism/mekck/mixin/MixinTileComponentUpgradePersistence.java:39（@Mixin(value = TileComponentUpgrade.class, remap = false)）
- 同文件 :31-33（作者写的选型理由："在 Upgrade 上注入会波及整合包里**所有** Mekanism 机器……重定向**本组件**的实例调用则**只作用于挂了本 Mixin 的 tile**"）← **这条论断与实现不符，是本案根因**
- 同文件 :78-94（mekck$tier()：沿类链找 cn.ism.mekck.machine.MekCkMachineTile，找不到返回 null）
- 同文件 :142-151（read 侧 @Redirect → MekCkUpgradeTypes.decode(tag, mekck$tier())）、:186-192（write 侧 → encode）
- src/main/java/cn/ism/mekck/upgrade/MekCkUpgradeTypes.java:245-252（isSupportedBy 只接受 STORAGE / RANDOMIZE / SPEED / ENERGY）、:278-286（capOf：先过闸门，不通过**直接返回 0**）、:313-315（decode(tag,tier) 把 capOf(type,tier) 交给 codec）
- src/main/java/cn/ism/mekck/upgrade/MekCkUpgradeCodec.java:120-124（amount = min(max(0,amount), cap)；amount > 0 才进 known，**否则既不进 known 也不进 unknownRaw**）
- src/test/java/cn/ism/mekck/upgrade/TestUpgradeCodecRoundTrip.java:123-128（capOfZeroDropsTheEntry，用 muffling 当例子，断言 cap=0 ⇒ 丢弃）

**触发条件**（任一条）
1. 整合包里任何 Mekanism 自己的机器（TileComponentUpgrade 的实例）上装过 MUFFLING / FILTER / GAS / ANCHOR /
   STONE_GENERATOR 中任意一种升级卡，其所在区块被加载一次。
2. MekCK 工厂的档位不支持存储卡（SINGULARITY）而存档里存在存储卡条目（旧存档迁移 / 手改 NBT / 其它 mod 自动化写入），同上。
3. 任何第三方用 TileComponentUpgrade 的机器。

**后果**
- 读档瞬间：upgrades.clear(); upgrades.putAll(known)（Mek 原生代码）⇒ 该类型数量直接变 0，机器行为立刻变化
  （静音失效、过滤槽数回退……），**没有日志、没有掉落返还、没有提示**。
- 下一次存档：write 走重定向后的 encode，名字列表里已经没有那条 ⇒ **NBT 里永久消失**。
  玩家把 mekck 卸掉也恢复不了（不是"注入者缺席"的 unknownRaw 路径）。
- 这是**跨 mod 的静默数据损失**，且触发面是"Mek 自己的机器"，不是 MekCK 的机器。

**证据**
1. @Redirect 打在 TileComponentUpgrade.class 上 ⇒ 修改的是**类本身**，所有实例（含 Mek 自家 tile）都走新实现。
   作者在 :31-33 写下的"只作用于挂了本 Mixin 的 tile"把 @Redirect 与 @Inject+instanceof 的语义混了。
2. Mek 自家 tile 确实会用这个组件读写：javap -p -c mekanism.common.tile.base.TileEntityMekanism ⇒
   saveAdditional 偏移 16-52 是 for (ITileComponent c : components) c.write(tag)，而 upgradeComponent 就是其中一员
   （字段声明见 javap -p 第 51 行，构造器偏移 460 处 putfield upgradeComponent）。
3. 被重定向的两个方法确实存在且仍匹配（见 §5 表）：private void lambda$read$1(CompoundTag)V 内含 Upgrade.buildMap，
   write(CompoundTag)V 偏移 13 内含 Upgrade.saveMap。
4. MUFFLING 是 Mek 标准机器支持的升级：对 Mek jar 全量 .class 做字节扫描，引用 MUFFLING 的类含
   mekanism.common.content.blocktype.Machine、mekanism.common.registries.MekanismBlockTypes、
   mekanism.common.tile.base.TileEntityMekanism、mekanism.common.tile.component.TileComponentUpgrade 等 12 个。
5. 对非 MekCK 的 tile，mekck$tier() 返回 null（:79-94），于是 isSupportedBy(MUFFLING, null) == false
   ⇒ capOf == 0 ⇒ codec 丢弃。整条链无一处 null 检查会拦住它。
6. "cap=0 就丢弃"是**有意语义**（TestUpgradeCodecRoundTrip:123-128 明确断言）——所以这不是 codec 的 bug，
   而是**准入闸门被套在了它不该管的机器上**。

**修复（【需设计决策】，三个方向都会改变存档语义，需 Lead/用户裁定）**
- 方向 A（最小面）：MixinTileComponentUpgradePersistence 只在"确实是 MekCK 机器"时启用 MekCK 编解码；
  非 MekCK 的 tile 直接放行给 Upgrade.saveMap/buildMap。⇒ MekCK 工厂的存储卡走名字键，Mek 机器保留原有行为。
- 方向 B（改丢弃语义）：MekCkUpgradeCodec.decode 把"解析成功但被 cap 裁到 0"的条目也放进 unknownRaw（原样保留），
  只把"本机不接受"体现在运行期而非持久化上。⇒ 与现有测试 capOfZeroDropsTheEntry 冲突，需同步改测试并写明意图。
- 方向 C：MekCkUpgradeTypes.capOf 增加"非 MekCK 机器"分支：tier == null 时退回 type.getMax()，
  并把"存储卡在未知档位判 false"改成显式哨兵值，避免与"Mek 原生类型"共用同一条 null 语义。
- 无论选哪条，**都应补一条回归测试**："非 MekCK 类型（如 muffling）+ tier=null"的条目在 decode→encode 后必须原样保留。
  该测试可完全在普通 JUnit 里跑（用 MekCkUpgradeCodec 的泛型入口，与现有测试同款）。

---

## 2. Important

### M1 —— 6 个新工厂 GUI 的进度条分子没有同步通道

**文件:行号**
- src/main/java/cn/ism/mekck/machine/MekCkMachineTile.java:525（private int workProgress;）
- 同文件 :850-851（服务端每 tick ++workProgress，满一批清零）、:866（setActive(workProgress > 0)）
- 同文件 :890-897（getWorkProgress() / getTicksPerWorkCycle()，注释明写"GUI 用"）
- 同文件 :928（只在 saveAdditional 里写 TAG_WORK_PROGRESS）、:1022（load 里读回）
- src/main/java/cn/ism/mekck/menu/GrillFactoryMenu.java:72-78（tile.getWorkProgress() / tile.getTicksPerWorkCycle()）、:80-83（isBusy()）、:38-39（注释断言"客户端直接读 tile 的网络同步状态"）
  同款：GrindingFactoryMenu:71-81 / CuttingMachineFactoryMenu:83-93 / CookingFactoryMenu:55-65 / SkeweringFactoryMenu:56-66 / PlantingCuttingFactoryMenu:58-68
- client/*FactoryScreen.java（6 个）→ menu.getProgressRatio() / menu.isBusy()（例：GrillFactoryScreen.java:103,108）

**触发条件**：打开任意一个阶段 2/3 重写的工厂 GUI（切菜 / 研磨 / 种植切配 / 串烧 / 烧烤 / 烹饪）。

**后果**
- 客户端那份 tile 的 workProgress **只在区块加载时**由 BE 数据包写入一次，之后整个批次内不再更新。
- 因此 menu.getProgressRatio() 在客户端恒等于 陈旧值 / 批次长度；isBusy() 也只在"批次开始那一刻"被一次数据包点亮
  （值=1），到批次结束再被一次数据包清零。
- 玩家看到的是：进度条**几乎全程为空**（约 1/批次长度，200 tick 时 0.5%），从不随进度推进。
  这是相对旧体系（ContainerData 每 tick 同步）的**功能回退**，6 个家族全都如此。无数据损失，纯显示；
  与 I1（writeShort 截断）**不是同一件事**，不构成重复上报。

**证据**
1. 全仓库 grep addContainerTrackers|container.track|ISyncableData|sendUpdatePacket|markForSave|sendBlockUpdated|setChanged()
   ⇒ machine/ 与 menu/ **零命中**（旧 blockentity/ 里同样的 grep 有上百处 setChanged()，说明旧体系确有另一条同步通道，
   迁移时被整体拿掉了）。
2. **vanilla 的 setChanged() 不发包**：javap -c net.minecraft.world.level.Level（探针 jar：
   ~/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.20.1-47.4.16_mapped_official_1.20.1/）
~~~
   public void blockEntityChanged(BlockPos p) {
       if (this.hasChunkAt(p)) this.getChunkAt(p).setUnsaved(true);   // 只标脏
   }
~~~
   且 ServerLevel 未覆写它（javap -p net.minecraft.server.level.ServerLevel 无该方法）。真正发包的只有
   ServerLevel.sendBlockUpdated(...) → getChunkSource().blockChanged(pos) → ChunkHolder.blockChanged(pos)
   → tick 末给追踪玩家发 getUpdatePacket()（= getUpdateTag() = saveAdditional）。
3. **Mek 自己也不用 sendUpdatePacket 逐 tick 推**：javap -c mekanism.common.tile.base.TileEntityMekanism 显示
   sendUpdatePacket() 只有两个调用点——updateRadiationScale() 与 configurationDataSet()。
   Mek 的逐 tick GUI 通道是 MekanismContainer.track(ISyncableData) / TileEntityMekanism.addContainerTrackers(MekanismContainer)
   + PacketUpdateContainer（javap -p mekanism.common.inventory.container.MekanismContainer 里有
   track/trackArray/startTracking/broadcastChanges；整 jar 里 ClientboundContainerSetDataPacket 出现 **0** 次，
   所以这条通道不受 I1 的 short 截断影响）。⇒ **addContainerTrackers 是官方接入点，而本仓库从未实现或调用它。**
4. 唯一会发包的路径是 setActive()（javap：状态翻转时 Level.setBlockAndUpdate → ServerLevel.sendBlockUpdated），
   它按定义只在翻转那一 tick 动作；Attributes.ACTIVE 确实挂在 6 个工厂方块上
   （block/GrillFactoryBlock.java:109、CuttingMachineFactoryBlock.java:113 等）——所以恰好得到"每批一次"的那两个数据点。

**修复（【可机械修复】，但落在 menu/ 与 machine/）**
- 首选：6 个 Menu 构造器里按 Mek 的方式注册 track(SyncableInt.create(tile::getWorkProgress, ...))
  （或让 MekCkMachineTile 覆写 addContainerTrackers），客户端改为读同步值而不是读 tile 字段。
- 备选：MekCkMachineTile 在批次内按固定节奏调用 sendUpdatePacket()（实现简单，但要为 6 个家族各接一次，且整包重发 NBT）。
- 验收：GUI 打开时进度条随 tick 推进；装/拔速度卡时分母随 getTicksPerWorkCycle() 变化。
- **注意**：machine/ 与 menu/ 分别是 rev-machine-core 与 rev-ae2-network 的写作用域，需 Lead 协调后再动手；本轮只写报告。

### M2 —— en_us.json 缺 21 个键，其中 12 个档位名是动态构造的

**文件:行号**
- src/main/resources/assets/mekck/lang/en_us.json（322 键）vs zh_cn.json（343 键）
- src/main/java/cn/ism/mekck/item/MekCkBlockItem.java:143-144（Component.translatable("tooltip.mekck.tier", Component.translatable("tier.mekck." + tier.name))）
- 同文件 :149,151,166,168,174-175,178,180-181（tooltip.mekck.threads / parallel / max_parallel / energy_per_tick / energy_capacity / tier.mekck.basic_machine）
- tier.name 是小写串（CuttingMachineFactoryTier.java:9-22 的构造参数 basic/advanced/.../singularity，字段 :24）

**触发条件**：语言设为 English（en_us），把任意工厂方块或升级卡拿到手里看 tooltip。

**后果**：界面出现 raw key，例如 "Tier: tier.mekck.absolute"、"tooltip.mekck.parallel" 原样显示。
中文客户端完全正常（这些键只在 zh_cn 里），所以中文自测发现不了。

**证据**：en - zh = 空集，zh - en = 21 个键，全部列出：
~~~
gui.mekck.actual_parallel
tier.mekck.absolute / advanced / basic / basic_machine / blaze / cosmic / crystal_matrix
tier.mekck.elite / infinite / nebula / singularity / supreme / ultimate
tooltip.mekck.energy_capacity / energy_per_tick / ice_maker / max_parallel / parallel / threads / tier
~~~
另：扫全部 Java 的 Component.translatable("<字面量>") 里以 mekck. 开头的键，**0 个**在 en_us 缺失
（即这 21 个全部是"动态拼接"或"只在 zh 侧加过"的，字面量扫描抓不到——这也是上一轮漏掉它们的原因）。

**修复（【可机械修复】）**：把 zh_cn 的这 21 个键的英文译文补进 en_us.json（12 个档位名 + 9 条 tooltip/gui）。
tooltip.mekck.ice_maker 已有中文长文案，英文需要等义翻译（属内容而非机械搬运，建议 Lead 确认措辞）。

---

## 3. Minor

| # | 文件:行号 | 触发条件 | 后果 | 证据 | 分类 |
|---|---|---|---|---|---|
| m1 | assets/mekck/blockstates/mekck.json:1-7 | 有人尝试注册 mekck:mekck 方块时 | 现在是死资源：该文件指向 mekck:block/mekck，而 models/block/mekck.json 不存在，也没有名为 mekck 的方块注册 | 脚本核对 117 个 blockstate 的 model 引用，只有这一条是 mekck: 命名空间的悬空引用 | 【可机械修复】删文件或补模型 |
| m2 | client/MekCkMeOrderButton.java、item/FerreroUpgradeProfile.java | — | 0 字节源文件；不是错误（空编译单元合法），但说明有一次未完成的删除 | Get-ChildItem ... Where Length -eq 0 | 【可机械修复】删除 |
| m3 | client/SkeweringFactoryScreen.java:62,75 | — | :62 的 tier 声明后从未使用，:75 又调了一次 tile.getTier() | 通读该类（126 行） | 【可机械修复】 |
| m4 | command/PlantingRecipeGenerator.java:108,123-131,176,189 | 开服生成时磁盘/权限出错 | 旧数据包**先被递归删除**，随后 createDirectories 或 pack.mcmeta 写入失败即 return false ⇒ 该存档的自动生成配方整体消失（下次启动才可能重建） | :123-131 删除；:169-177 与 :185-190 的失败分支只 log 不恢复 | 【需设计决策】先写临时目录再原子替换 |
| m5 | UniversalCuttingMachine.java:1479-1508 + world/CreativeUpgradeFoodRotator.java:96-115 + data/mekck/recipes/creative_upgrade_from_49_foods.json:159-160 | 装了 avaritia + 新存档（或删过 datapacks/mekck_creative_upgrade）首次启动 | 配方在 ServerStartedEvent（数据包已加载完）之前就引用了 49 个还不存在的 mekck:cuf_* 标签 ⇒ 首次启动必然 49 条 Parsing error，直到该监听器写完标签并自行 /reload（:1502-1507）才恢复。tools/predict_residual_recipe_errors.py 的静态预测**看不到**这条（按"条件跳过"计），所以 STATUS.md 的"残留 0"对该路径不覆盖 | 见上 | 【需设计决策】把生成提前到 ServerAboutToStartEvent / 注册 reload listener，或让配方在缺标签时不引用具体标签 |
| m6 | recipe/BeverageAssemblyRecipe.java:74-85、ExtractingRecipe.java:113-124、PackagingRecipe.java:62-72 | 一次性容器里同一材料占多格 | matched 统计的是"命中了任一 ingredient 的槽数"，不是双射 ⇒ 材料重复时可误判为可制作。机器侧走 RecipeInputMatcher/各执行器，故只影响 JEI 类路径。**未坐实调用方**（见 §4） | 三个类的 matches 都是 matched == ingredients.size() 同一写法 | 【需设计决策】 |

---

## 4. 未经证实（不作为结论，列出待查）

1. **ForgeRegistries.ITEMS.getValue(id) 对未知 id 是否返回 null**：command/PlantingRecipeGenerator.java:380-385 写了
   if (item != null) ... else LOGGER.warn("Blacklist contains unknown item")。若 Forge 的 IForgeRegistry 在 vanilla
   DefaultedRegistry 之上返回默认值（Items.AIR），该 else 分支永不触发、未知 id 会静默变成空气。
   需要 javap IForgeRegistry 与 net.minecraft.core.DefaultedRegistry 才能定论。**未验证，勿当结论。**
2. **m6 的实际调用方**：需要找 BeverageAssemblyRecipe#matches(RecipeWrapper,Level) 的调用点；若无人调用，m6 只是死代码。
3. **MixinCuttingBoardBlockEntity 在 FD 1.2.7 上的 implements Clearable**：该 mixin 类声明
   extends SyncedBlockEntity implements Clearable（:47），而 FD 1.2.7 的 CuttingBoardBlockEntity **没有**实现 Clearable
   （1.3.4 才实现，javap 已证）。若目标的 clearContent() 在 1.2.7 也不存在，任何 Clearable.tryClear(...) 调用会抛
   AbstractMethodError。需要 javap FD 1.2.7 的 CuttingBoardBlockEntity 找 clearContent + 在 vanilla 源码里找 tryClear 调用者。
4. **MixinExtremeSmithingMenu 的目标类无法离线核实**：libs/ 下没有 avaritia 的 jar，
   committee.nova.mods.avaritia.common.menu.ExtremeSmithingMenu#shrinkStackInSlot(int) 与 @Shadow selectedRecipe/level
   均**未验证**。该 mixin 由 MekCkMixinConfigPlugin 在未装 avaritia 时跳过，只有装 avaritia 的环境会受影响。
   另：该目标类属第三方 mod，惯例应写 remap = false（当前用默认 remap = true）。**未证实是否致害**。
5. **各 Screen 面板尺寸与 tile 槽位排布的像素级一致性**：我只核对了"面板宽高按 tier.processes 推导"这一层逻辑
   （6 个 Screen 一致），没有逐个比对 getInitialInventory 写进槽位的 x/y。CookingFactoryScreen 用的是**静态常量**
   CookingFactoryTile.INPUT_SLOTS/PRODUCT_SLOTS/RETURN_SLOTS 而不是 tier.processes（CookingFactoryScreen.java:70-73），
   与其它 5 个 Screen 写法不同——若 CookingFactoryTile 的槽位方阵随档位增长，这里会算矮面板。
   **需要 rev-machine-core 核对 getInitialInventory。**

---

## 5. mixin 目标核实表（交付物 2）

目标 jar：~/.gradle/caches/forge_gradle/deobf_dependencies/curse/maven/mekanism-268560/6018299_mapped_official_1.20.1/
mekanism-268560-6018299_mapped_official_1.20.1.jar（= build.gradle:184 实际用的那个）；FD 用 libs/FarmersDelight-1.20.1-1.2.7.jar
与 libs/FarmersDelight-1.20.1-1.3.4-mapped.jar。
mekck.mixins.json 的 mixins 数组（7 项）与目录内 7 个 mixin 类**一一对应**
（IMekCkUnknownUpgradeHolder 是接口、MekCkMixinConfigPlugin 是 plugin，二者正确地没有列进数组）。

| mixin 类 | 目标类（remap） | 关键注入点 / 描述符 | 是否仍匹配 | 证据命令 |
|---|---|---|---|---|
| MixinAPILang | mekanism.api.text.APILang（false） | 私有构造 (Ljava/lang/String;ILjava/lang/String;)V；@Shadow $VALUES；clinit TAIL | 是 | javap -p -s -cp <mek> mekanism.api.text.APILang → private APILang(String,int,String) 描述符 3 槽，与 invoker 的 3 参一致 |
| MixinUpgrade | mekanism.api.Upgrade（false） | 私有构造 (Ljava/lang/String;ILjava/lang/String;Lmekanism/api/text/APILang;Lmekanism/api/text/APILang;ILmekanism/api/text/EnumColor;)V；@Shadow $VALUES + UPGRADES（均 private static final Upgrade[]） | 是 | javap -p -s -cp <mek> mekanism.api.Upgrade |
| MixinUpgradeUtilsGetStack | mekanism.common.util.UpgradeUtils（false） | getStack(Lmekanism/api/Upgrade;I)Lnet/minecraft/world/item/ItemStack; | 是 | 同上；并核实 Upgrade 的静态字段**声明顺序**为 SPEED,ENERGY,FILTER,GAS,MUFFLING,ANCHOR,STONE_GENERATOR ⇒ STONE_GENERATOR.ordinal()==6，判定"原生"的写法成立 |
| MixinTileComponentUpgradePersistence | mekanism.common.tile.component.TileComponentUpgrade（false） | lambda$read$1(Lnet/minecraft/nbt/CompoundTag;)V 内的 Upgrade.buildMap(CompoundTag)Ljava/util/Map;；write(Lnet/minecraft/nbt/CompoundTag;)V 内的 Upgrade.saveMap(Ljava/util/Map;Lnet/minecraft/nbt/CompoundTag;)V；@Shadow private final TileEntityMekanism tile | 是（目标匹配，但语义面过宽 → C2） | javap -p -s -cp <mek> mekanism.common.tile.component.TileComponentUpgrade → lambda$read$1 与 write(CompoundTag)V 都在；javap -p -c 显示 write 偏移 13 = Upgrade.saveMap、lambda 偏移 14 = Upgrade.buildMap |
| MixinItemStack | net.minecraft.world.item.ItemStack（**默认 true**） | save / of | **否 —— 生产环境不匹配**（无 refmap；运行期是 m_41739_ / m_41712_） | build/reobfJar/mappings.tsrg:151050；jar 内无 refmap（见 C1） |
| MixinCuttingBoardBlockEntity | vectorwing.farmersdelight.common.block.entity.CuttingBoardBlockEntity（false） | processStoredItemUsingTool(ItemStack,Player)Z；@Shadow inventory/isItemCarvingBoard/getMatchingRecipe/playProcessingSound/getStoredItem | 是（1.2.7 与 1.3.4 **描述符相同**） | javap -p -s -cp libs/FarmersDelight-1.20.1-1.2.7.jar ...CuttingBoardBlockEntity 与 ...1.3.4-mapped.jar 对照；注意 getMatchingRecipe 两版都是 **private**（@Shadow 不校验访问级，可以），FD 1.2.7 未实现 Clearable（见 §4.3） |
| MixinExtremeSmithingMenu | committee.nova.mods.avaritia.common.menu.ExtremeSmithingMenu（默认 true） | shrinkStackInSlot(int)；@Shadow selectedRecipe/level | 无法离线核实（libs/ 无 avaritia jar） | 由 MekCkMixinConfigPlugin:27-32 在未装 avaritia 时跳过 |

其它两条静态结论：
- injectors.defaultRequire: 1 + required: true（mekck.mixins.json:2,16-18）⇒ 上表任何一条"不匹配"都不是静默降级。
- 除 MixinItemStack 外全部 remap = false，与"目标是第三方/vanilla 成员名"的事实一致。

---

## 6. 资源一致性校验结果摘要（交付物 3）

方法：一次性 python 脚本（写在 %TEMP%，**未留在仓库**），对 src/main/resources/** 做机器校验。

| 项目 | 数量 | 结论 |
|---|---|---|
| .json 总数 / 不合法 | 1200 / **0** | 全部可解析（无需为 // 注释特判） |
| assets/mekck/models/**/*.json | 442 | mekck: 命名空间的 parent 悬空 **0**；textures 悬空 **0**（7 条 #sides/#front 是模型纹理变量，非路径）；另有 2 处 mekmm: 外部命名空间 parent（外部 mod，离线无法判定） |
| assets/mekck/blockstates/*.json | 117 | 悬空 model 引用 **1**：mekck.json → mekck:block/mekck（= m1） |
| data/mekck/recipes/**/*.json | 562 | item/result 里 mekck: 悬空引用 **0**（用"动态注册"排除法消掉 172 条假阳性：这些 id 出现在 models/item/*.json + lang 里，只是由循环注册、字面量扫不到）；tag 引用悬空 **49**，全部是 creative_upgrade_from_49_foods.json 的 cuf_01..49（运行时由 CreativeUpgradeFoodRotator 生成，见 m5） |
| 自有配方类型 vs JSON type | 11 注册 / 10 被本仓库使用 | **0 不匹配**；plantcut 已注册但本仓库 JSON 未用（由世界数据包 mekck_planting 使用，符合设计） |
| conditions 块 schema | 635 个 data json | **0 个** advancement 风格（condition / minecraft:all_of / terms）；568×forge:mod_loaded + 8×forge:item_exists；60 处 {"condition":"minecraft:survives_explosion"} 全在 **loot_table** 里，是战利品表正确写法（不是配方条件） |
| lang 键配对 | en 322 / zh 343 / 交集 322 | only-en **0**、only-zh **21**（= M2）；Java 里字面量 Component.translatable("mekck.*") 在 en_us 缺失 **0** |

recipe/ 网络层对称性（逐个字段列过）：11 个类型的 toNetwork/fromNetwork **字段顺序、类型、长度前缀全部对称**，
包括 List<Ingredient>（都先写 VarInt 长度）、Ingredient.toNetwork/fromNetwork、writeItem/readItem、
writeFluidStack/readFluidStack、Float（PlantingCutting 的 secondaryChance）、NonNullList 双列表。
两个本轮新增类型（mekck:grilling / mekck:skewering）额外核过：序列化器注册名与 JSON type 一致；tool 缺省时
fromJson 回落到 Ingredient.of(Items.STICK)（**不会**产生 Ingredient.EMPTY，不构成 I9 同型问题）；matches 对空槽有显式短路。

---

## 7. 覆盖清单（交付物 1）

### 已完整/逐行读完
- mixin/：全部 9 个文件 + mekck.mixins.json（含 MekCkMixinConfigPlugin、IMekCkUnknownUpgradeHolder）
- upgrade/：MekCkUpgradeTypes(321) MekCkUpgradeCodec(154) MekCkUpgradeRefs(64) MekCkStorageUpgradeItem(39) MekCkRandomizeUpgradeItem(36) MekCkAPILang(66)
- recipe/：全部 11 个文件的 codec / toNetwork / fromNetwork / matches / assemble / getResultItem / getSerializer；MekCkSkeweringRecipe 全文精读
- client/：6 个工厂 Screen 全文（GrillFactory / CookingFactory / SkeweringFactory / CuttingMachineFactory / GrindingFactory / PlantingCuttingFactory）
- world/：CreativeUpgradeFoodRotator 前 130 行 + 全部调用点；CreativeUpgradeFoodPool（仅交叉引用）
- 其它：UniversalCuttingMachine.java 的配方序列化器注册段与 ServerStartedEvent 监听段（1465-1539）、CuttingMachineFactoryTier.java 前 60 行
- 测试：src/test/java/cn/ism/mekck/upgrade/TestUpgradeCodecRoundTrip.java 全文

### 只做了定向核对（不是逐行）
- machine/MekCkMachineTile.java（约 3 个区段 + grep）、menu/*FactoryMenu.java（6 个的进度段）
- command/PlantingRecipeGenerator.java：**只读了 95-224 与 370-409 两段 + 全文件关键行扫描**（Files.write* / getValue( / pack_format / datapath 共 36 行）——1573 行没有逐行读完
- client/ 其余 47 个文件：只用 grep 核对了"Screen → Menu 的同步 API 面"，没有读实现
- resources/：机器校验覆盖全部 1200 个 json + 442 模型 + 117 blockstate + 562 配方 + lang

### 未看（本轮未覆盖，建议下一轮）
- client/ 渲染与控件层：MekCkOutlineRenderer(650) NetworkOrderPanel(592) MekCkTabElement(285) NetworkPullButton(245)
  mesh/ObjMesh*(442) KitchenOrderWindow/KitchenModuleWindow/OrderSearchBox/GuiMekCkSideConfiguration/
  GuiUpgradeWindow/MekCkUpgradeScrollList/MekCkUpgradeTypesStrip/BigStackHud/VineryJuiceBar/MachineTabIcons/
  CountFormat/MekCkButtons/NetworkOrderHost/NetworkPullButtonElement/MekCkMeOrderButton(0B)/MekCkWineryDumpButton/
  GuideMECompatImpl/CreativeUpgradeTooltipHandler/item/RenderPropertiesAtomicKnife/render/item/gear/RenderAtomicKnife/
  model/ModelAtomicKnife/MekCkRenderTypes/MekCkUpgradeType 及 6 个实体渲染器
- client/ 的旧机器 Screen（SkeweringMachineScreen 890 / SmartCookingPotScreen 810 / IceFactoryScreen 524 /
  IceMakerScreen 523 / ChocolateCannonScreen 513 / NutRoasterScreen 478 / GrillScreen 467 /
  PlantingCuttingStationScreen 434 / SimpleMachineScreen 534 / SandwichAssemblerScreen 176 /
  CentralKitchenScreen 263 / WineCellarScreen 232 / BioreactorScreen 128）：这些**仍走 ContainerData**，
  其"下标 ↔ 服务端 addContainerData 下标"的一一对应**本轮没有完成**（我确认了它们不直接读 containerData.get(...)，
  值都经 menu.getXxx() 转发，所以对账要连带读 21 个 Menu——那属 rev-ae2-network 的写作用域；
  建议把"旧 Screen 的 ContainerData 下标对账"作为一轮交叉核对任务）
- item/：MekCkBlockItem(402) ItemAtomicKnife(184) GuideHandbookItem BioreactorBlockItem MekCkTierInstallerItem ColdBrew*/FerreroUpgrade*
- kitchen/（全部 6 个，1028 行）、entity/（3 个，677 行）、effect/（3 个，138 行）、advancement/（4 个，290 行）、buff/（2 个，111 行）、api/（1 个，27 行）
- command/PlantingRecipeGenerator 的其余约 1350 行
- src/test/java/cn/ism/mekck/upgrade/TestUpgradeIndexWraparoundArithmetic.java（74 行，未读）

---

## 8. 需要 Lead 执行的命令（我不跑 gradle）

1. **C1 修复验证**（改完 build.gradle 后）：./gradlew clean build --offline（clean 是必须的，STATUS.md 第五节第 5 条），
   然后**不启动游戏**地检查产物：
~~~powershell
   Add-Type -AssemblyName System.IO.Compression.FileSystem
   $z=[System.IO.Compression.ZipFile]::OpenRead((Resolve-Path build/reobfJar/output.jar))
   $z.Entries | Where-Object { $_.FullName -match 'refmap|mixins.json' } | Select-Object FullName,Length
   $e=$z.Entries | Where-Object { $_.FullName -eq 'mekck.mixins.json' }
   (New-Object System.IO.StreamReader($e.Open())).ReadToEnd()
~~~
   期望：出现 mekck.refmap.json，且 json 里有 "refmap": "mekck.refmap.json"。
2. **C2 回归测试**（无论选哪个修复方向）：普通 JUnit，验证"非 MekCK 类型（如 muffling）+ tier=null"的条目在
   decode→encode 后仍在。可直接扩 TestUpgradeCodecRoundTrip。
3. **M1 验收**：runClient 放置一台切菜工厂、投料，观察进度条是否推进（改前应当几乎不动）。
4. 若要我进入修复阶段：我的【可机械修复】项是 **m1 / m2 / m3**（+ M2 的 21 个 lang 键，英文文案需你确认）。
   注意 **M2 的 lang 文件不在我的 write scope 里**（task-4 的 writeScopes 只含 .review/client-recipe-mixin.md 与 6 个 java 目录），
   需要你扩 scope 或由你改。

## 9. 环境备注

- 本次使用的探针 jar（只读、未改动）：
  - Mek：~/.gradle/caches/forge_gradle/deobf_dependencies/curse/maven/mekanism-268560/6018299_mapped_official_1.20.1/mekanism-268560-6018299_mapped_official_1.20.1.jar
  - MC/Forge（mojmap）：~/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.20.1-47.4.16_mapped_official_1.20.1/forge-1.20.1-47.4.16_mapped_official_1.20.1.jar
  - reobf 映射：build/reobfJar/mappings.tsrg（9.1 MB，只读）
  - FD：libs/FarmersDelight-1.20.1-1.2.7.jar、libs/FarmersDelight-1.20.1-1.3.4-mapped.jar
- 所有临时 python 脚本写在 %TEMP%\mekck_*.py，**仓库内没有留下任何新文件**（除本报告）。
- 全程未执行 gradle / git 写操作；未修改 src/。

---

## 10. 并发状态核对（写报告期间工作区被改动，必须看这一节）

我在完成审查后运行 git status --porcelain（只读）发现工作区**不是** HEAD 83830e0 的干净状态：
build.gradle、src/main/resources/mekck.mixins.json 以及 9 个 machine/** 文件处于 M 状态，
另有新文件 src/test/java/cn/ism/mekck/machine/TestMekCkPersistedSlotCoverage.java。逐条核对结论如下。

### 10.1 C1 已经被并发修复（我独立得到同一结论）——但**修复未被重建验证**

git diff build.gradle src/main/resources/mekck.mixins.json 显示修复正是三件套：
1. annotationProcessor 'org.spongepowered:mixin:0.8.5:processor'；
2. apply plugin: 'org.spongepowered.mixin' + mixin { add sourceSets.main, "${mod_id}.refmap.json"; config "${mod_id}.mixins.json" }；
3. mekck.mixins.json 里补 "refmap": "mekck.refmap.json"。

而且那条 diff 的注释里**照抄了实机异常**，与我 §1 C1 的推导逐字对应：
~~~
   InvalidInjectionException: @Inject annotation on mekck$writeBigCount could
   not find any targets matching 'save' in net.minecraft.world.item.ItemStack.
   No refMap loaded.
~~~
⇒ C1 **不是误报**，是 2026-09-30 真实实例启动时已经炸过的问题；现已修复（未提交）。

**但修复状态是「未验证」**（证据）：
- build/libs/mekck-1.0.0.jar 与 libs/mekck-1.0.0.jar 的 mtime 都是 **2026/9/30 1:36:56**，
  而 build.gradle 是 **1:45:17**、mekck.mixins.json 是 **1:44:26** ⇒ 产物**早于修复**。
- Get-ChildItem -Recurse -File build,libs -Filter '*refmap*' ⇒ **0 命中**（改造后尚未跑过构建，或跑过但没产出）。
⇒ 必须按 §8 第 1 条重建并确认 jar 内出现 mekck.refmap.json，否则「修复完成」只是源码层的事实。

### 10.2 新引入的离线构建风险：【可机械修复】1 行

新 buildscript 里写的是：classpath 'org.spongepowered:mixingradle:0.7.5'，仓库指向
https://repo.spongepowered.org/repository/maven-public/（远端）。而本机 Gradle 缓存里只有：
~~~
   ~/.gradle/caches/modules-2/files-2.1/org.spongepowered/mixingradle/0.7.38/*/mixingradle-0.7.38.{jar,pom,module}
~~~
**没有 0.7.5**（Get-ChildItem -Recurse -Path ~/.gradle/caches -Filter '*0.7.5*' ⇒ 0 命中；
mixingradle* 的全部命中只有 0.7.38 三个文件）。

- 触发条件：按 STATUS.md 第五节记录的口径执行 ./gradlew clean build --offline（或任何离线构建）。
- 后果：buildscript 的 classpath 是**构建脚本自身的依赖**，离线模式无法访问远端仓库 ⇒
  预期在配置阶段就失败（Could not resolve org.spongepowered:mixingradle:0.7.5 / No cached version available for offline mode）。
  若在线构建，则取决于 Sponge 仓库里是否真有 0.7.5 这个版本——**这一点我无法离线确认**（见下）。
- 证据：上面的缓存清单；另 mixin-0.8.5-processor.jar **已在缓存**（org.spongepowered/mixin/0.8.5/...），
  所以 annotationProcessor 那一行离线可用，只有 mixingradle 这一行的版本号是风险点。
- **建议（机械修复）**：把那行改成缓存里已有的 0.7.38（并核对 mixin { } 扩展的参数在 0.7.38 下一致），
  或保留 0.7.5 但明确要求**在线构建一次**再离线——两者都必须由 Lead 决定，因为 build.gradle 不在我的写作用域。
- **未经证实**：mixingradle:0.7.5 是否真实存在于 Sponge 仓库，我无法离线验证（无网络）。
  若线上并不存在该版本，则无论在线离线都编不过——这是重建时要第一时间确认的事。

### 10.3 其余并发改动与我结论的关系

- machine/** 的 9 个文件与新增测试 TestMekCkPersistedSlotCoverage 属 rev-machine-core 的在途工作，我未审、未碰。
  我的 **M1 行号基于我读取时的工作区内容**（已含其改动）；若他随后继续改 MekCkMachineTile，请以最终版本复核 §2 M1 的行号。
- **C2 不受影响**：mixin/MixinTileComponentUpgradePersistence.java、upgrade/MekCkUpgradeTypes.java、
  upgrade/MekCkUpgradeCodec.java **都不在修改列表里**（git status 未列出），所以 C2 在**当前工作区**上依然成立且可复现。
- .review/ 下另有 ae2-network-menu.md / legacy-conservation.md / machine-core.md（并行同学产出），我未读、未改。

---

## 11. 修复记录（Lead 授权后执行，2026-09-30）

授权范围：C2（两处对称）+ Minor 的 m2/m3。M1/M2 按指令未动；build.gradle 与 mekck.mixins.json 未碰（Lead 已改）。
未运行 gradle、未做 git 写操作。

### 11.1 改动清单（3 改 · 1 增 · 2 删）

| 文件 | 改动 | 说明 |
|---|---|---|
| src/main/java/cn/ism/mekck/upgrade/MekCkUpgradeTypes.java | 新增 MEKCK_TILE_CLASS_NAME 常量、isMekCkOwnedTile(Class)（公开）与 isMekCkOwnedTile(Class,String)（包内，供测试） | 归属判定的**唯一**实现，附「为什么不能用 tier 是否为 null 当判据」的完整注释 |
| src/main/java/cn/ism/mekck/mixin/MixinTileComponentUpgradePersistence.java | 新增 mekck$isMekCkTile()；mekck$decode 加回退 return Upgrade.buildMap(tag)；mekck$encode 加回退 Upgrade.saveMap(map, tag) + return；订正类注释里那句错的「只作用于挂了本 Mixin 的 tile」与 mekck$tier() 的 null 语义注释 | 两处**对称**；判据用类链，不用 tier |
| src/main/java/cn/ism/mekck/client/SkeweringFactoryScreen.java | 删掉构造器里未使用的局部变量 tier（原 :62），注释并入下一行 | m3 |
| src/test/java/cn/ism/mekck/upgrade/TestUpgradePersistenceOwnership.java | **新增**（7 个测试） | 见 11.3 |
| src/main/java/cn/ism/mekck/client/MekCkMeOrderButton.java | **删除**（0 字节） | m2；删前全仓库 grep 该标识符命中 **0** |
| src/main/java/cn/ism/mekck/item/FerreroUpgradeProfile.java | **删除**（0 字节） | m2；删前确认全仓库只引用 cn.ism.mekck.util.FerreroUpgradeProfile，item/ 那个是空壳 |

删除前均用 Resolve-Path 核对绝对路径在仓库内且 Length == 0 才执行（未用 git rm）。

### 11.2 回退分支的形状（与授权要求逐条对照）

~~~java
// mekck$decode（read 侧，目标 Upgrade.buildMap）
if (!mekck$isMekCkTile()) {
    return Upgrade.buildMap(tag);   // 非 MekCK：原样交回 Mek 的 ordinal 编解码
}
// mekck$encode（write 侧，目标 Upgrade.saveMap）—— 与 decode 对称
if (!mekck$isMekCkTile()) {
    Upgrade.saveMap(map, tag);
    return;
}
~~~

判据 `mekck$isMekCkTile()` → `MekCkUpgradeTypes.isMekCkOwnedTile(tile.getClass())` → 沿 `getSuperclass()` 逐级比较
类名 `cn.ism.mekck.machine.MekCkMachineTile`。**没有**用 `mekck$tier() != null`：那个 null 有两种成因
（不是 MekCK 机器 / 是 MekCK 机器但反射取档位失败），两者要相反处理；后者的语义已写进 `mekck$tier()` 注释。

### 11.3 新增测试 TestUpgradePersistenceOwnership（7 个）

1. directSubclassOfTheBaseIsOwned —— 直接派生判真
2. intermediateSubclassIsStillOwned —— 隔一层/两层仍判真（只比直接父类的写法会在这里落空）
3. unrelatedTileIsNotOwned —— 无关机器判假（Mek 自家机器就是这个形态）
4. nullInputsAreNotOwned —— 三种 null 入参组合均判假
5. baseClassNameContractIsPinned —— 钉住 MEKCK_TILE_CLASS_NAME 字面量
6. ownershipDoesNotDependOnTier —— 判定只看类，不看档位
7. mufflingEntryIsLostForeverIfTheNameKeyCodecIsUsedOnANonMekCkTile —— **因果链**：非 MekCK 机器（capOf→0）下
   muffling 既不在 known 也不在 unknownRaw，decode→encode 后只剩 speed。这条就是「必须有回退」的理由。

**覆盖边界**（写在类注释里，避免被当成端到端测试）：它验证判定与因果链；`@Redirect` 是否真的调用了判定
需要 `TileComponentUpgrade`/`Upgrade` 的类初始化（裸 JVM 必抛 ExceptionInInitializerError），只能由 GameTest/实机兜底。
本测试能在裸 JVM 跑，是因为只用 CompoundTag/ListTag 与 MekCkUpgradeCodec 的泛型入口，不触达 Upgrade。

### 11.4 我做到的验证（未跑 gradle）

1. **javac 编译干净**：以缓存里的 mc/forge、mekanism、mixin、junit jar 为 classpath，`-proc:none` 编译被改的
   3 个生产文件 → **exit 0、零错误**。
2. **新测试 7/7 通过**：把 4 个 upgrade 源文件与测试一起编译后直接跑 JUnitCore → `OK (7 tests)`。
   为绕开无法离线解析的 Forge 事件总线，用一个只含 getFactoryStackUpgradeMax 的 MekckConfig 脚手架顶替
   （放在 %TEMP%，未进仓库；该类不在本测试的断言路径上）。
3. **未验证**：mixin 注入是否被 Mixin 接受、生产环境端到端行为 —— 需要 Lead 的 clean build + 实机/GameTest。
   建议 clean build 后确认 gradle 测试输出里出现 TestUpgradePersistenceOwnership 的 7 个用例。

备注：验证期间 Lead 的 clean 已清空 build/classes，所以我把测试编译成自足 classpath 再跑，不依赖 build/。
仓库内除本节列出的文件外没有新增任何文件（临时脚本与产物都在 %TEMP%）。

### 11.5 §6 资源数字的时效更新（工作区已被并发改动）

复查（审查后、他人改动后）：resources 下 json **1217** 个（原 1200，新增 17 个 cooking_factory 战利品表）、
**0 个不合法**；blockstate 悬空 mekck: 模型仍是**那 1 条**（blockstates/mekck.json → mekck:block/mekck）；
loot_tables 77 个，含 conditions 的 0 个偏离 survives_explosion 写法。⇒ §6 的结论不变，只是计数需以最终树为准。



# mekck 当前状态汇总

- 最后更新：2026-09-30（**第三轮**全量审查）
- 审查基线：HEAD `bb00171` 之后的工作区
- 验收口径：`./gradlew build`（**联网**；理由见第五节第 6 条）→
  **359 测试 / 0 失败 / 0 错误 / 0 跳过**，且产物内含 `mekck.refmap.json`

本文是**单一入口**。细节看各专项文档（见文末索引）。

---

## 〇-A、第三轮全量审查（2026-09-30）—— 最新

4 个 agent 按**包边界**并行深审 + Lead 亲自复核每条 Critical 与产物级证据。
详细报告见下方各分域文档（第四份 `client-recipe-mixin` 那一节）。

### 已修（3 个提交）

| 提交 | 内容 |
|---|---|
| `0659f30` | **收编上一轮 C1–C6 的修复成果**。此前 120 个已跟踪文件被改 + **479 个未跟踪文件**（含 `mekck.refmap.json` 本身、审查报告、9 个护栏测试）全部游离在工作区——一次 `git clean -fd` 就全没了，且没有任何东西会失败报警 |
| `b6435bc` | 两个**服务器崩溃级**缺陷 + 订单契约统一 + 仓库卫生 |
| `bb00171` | 存档损坏 / TPS 杀手 / 永久无 AI |

### Critical 处置表

| # | 位置 | 问题 | 状态 |
|---|---|---|---|
| **C-N1** | `network/NetworkRecipeListPacket`、`NetworkMissingPacket` | `handle` 里直接 `Minecraft.getInstance()`。两包在 `FMLCommonSetupEvent`（**双端都触发**）里注册 ⇒ 专用服务端一定链接它们 ⇒ server jar 上没有 `net.minecraft.client.*` ⇒ **启动崩** | **已修**：走 `util/ClientPacketBridge`（common，零客户端符号，反射加载）+ `client/ClientPacketBridgeImpl`（`@OnlyIn(CLIENT)`）。加 `TestNoClientSymbolsInCommonCode`，`network/` 零例外 |
| **C1** | `util/FreezeEvents:59` | 靠「比较伤害数值」防递归，但 `LivingDamageEvent.getAmount()` 是**减免后**的值（`hurt` 顺序：护甲→魔抗→`onLivingDamage`），而 `minecraft:freeze` 只 `bypasses_armor` 不 `bypasses_resistance` ⇒ 抗性 1 即得 1717986917.6 < 2147483647 ⇒ 守卫恒假 ⇒ **无限递归 → StackOverflowError → 服务器崩** | **已修**：`ThreadLocal<LivingEntity>` 重入哨兵（存实体而非 boolean，反射伤害弹到另一只实体时不被误吞） |
| **C3** | `kitchen/KitchenRecipeMatcher:280` | `existing.grow()` 无溢出防护。`ItemStack.setCount` 不夹紧，槽上限默认 `Integer.MAX_VALUE` ⇒ 两个大堆叠归并**必然溢出为负** ⇒ `isEmpty()` 变真 ⇒ 落盘时整堆永久消失、无日志 | **已修**：照抄同仓 `util/StorageMerger.merge`（复核确认正确的参照实现），逐格夹紧 + 剩余量继续找位 |
| **C2** | `effect/EternalFreezeEffect:43`、`entity/IceCubeEntity:215`、`entity/FerreroEntity:248` | `NoAI` **会写进存档**（实测 `Mob.addAdditionalSaveData` 写 `NoAI`），而恢复只靠内存 `MinecraftServer.tellables` ⇒ 重启即丢；且恢复条件挂在 `!isNoAi()` 上 ⇒ 重启后**永远恢复不了**，玩家无任何游戏内手段解除 | **已修**：效果类改成「恢复刻已到」这个与 AI 状态无关的条件；两个实体新增 `util/FreezeAiReaper`（挂 `LevelEvent.Load`，按区块分片） |
| **C-N2** | 4 个 BE 的 `setRadius`/`adjustRadius` | 只钳下限不钳上限，而半径来自 `IceAttackConfigPacket` 的裸 `readInt`（`PacketGuard` 只校验 8 格交互距离）⇒ **一台机器 + 一个包 = 永久的每 tick 全服实体遍历** | **已修**：`IceTargetSearch.clampAttackRadius` 作为唯一闸门；`AABB_SCAN_MAX_RADIUS` 由 512 降到 64（原值只按「AABB 不溢出」定、没算成本：512 ⇒ 65³≈27 万次 section 查找/次） |

### Important（已修）

| # | 位置 | 问题 |
|---|---|---|
| 订单契约漂移 | 6 个执行器 | **3 套 null 约定 + 2 套数量下界**。收进 `machine/MekCkOrderState` 后连带修掉两个真实缺陷：① `orderCompleted++`（int 自增，两处注释都声称「加法走 long」但 `++` 本身就是 int 加法）⇒ 份数配成 `Integer.MAX_VALUE` 时订单**永远完不成**、机器永远只认这一张配方、无任何日志；② 烹饪 `load` 用 `Math.max(0,…)` 读份数，`quantity=0` 让 `batch` 夹成 0 ⇒ **机器永久卡死且订单永不完成** |
| I-4 | 烧烤调味料 / 烹饪·穿串「当前订单 x/y」 | **完全没有同步通道**。原注释断言「随方块更新包同步到客户端」——经 javap 复核**不成立**（`sendBlockUpdated` 只发方块状态；`machine/**` 里 `setChanged\|sendBlockUpdated` 零命中）⇒ 点「开/关」按钮颜色不变，离开并重进区块才刷新 | **已修**：`addContainerTrackers` 加 4 条 `SyncableInt`（订单三件套 + 家族位图），按端分流 |
| C4 | `command/PlantingRecipeGenerator` | 每次开服整棵递归删 `world/datapacks/mekck_planting`（玩家可见的存档内容） | **已修**：只删本方法自己写的 4 个配方目录 |
| I4 | 同上 | 遗留调试插桩：每次开服往世界存档根目录写 `planting_generator_test.txt` | **已修**（删除） |
| I7 | `util/FreezeEvents` | `event.getDrops().clear()` + 查不到战利品表时 `LootTable.EMPTY` ⇒ 「这只生物什么都不掉」且无日志；且抹掉别的模组的掉落 | **已修**：查不到表就原样保留；改为追加而非替换 |
| M7 | 同上 | 按参数签名反射定位战利品 raw 掷骰方法，但 `getRandomItems` 与 `processSelectedStacks` 擦除后签名完全一致，而 `getDeclaredMethods()` 顺序不由 JVM 规范保证 ⇒ 绑错就是「静默少掉整棵池子」 | **已修**：补返回类型判据 + 记录实际方法名 |
| 错 tag 双读 | `CookingFactoryTile`、`PlantingCuttingFactoryTile` | 覆写 `load()` 再调一次 `readExtraSustainedData(tag)`，但那个 `tag` 是**未经迁移的原始 tag**（基类在旧存档路径上会换绑）⇒ 迁移器一旦碰 `FluidTanks`/`GasTank` 就会用旧值覆盖迁移结果 | **已修**：删除覆写，基类已用正确的 tag 调过 |
| 恢复刻键冲突 | `FerreroEntity` | 与 `IceCubeEntity` 共用 `mekck:ai_restore_tick` ⇒「谁后命中谁说了算」，短窗口覆盖长窗口 | **已修**：拆成两个独立键 |
| README | 两份语言 | 都宣称「7 系列 × 12 档 = 84 个工厂方块」并把制冰列为完整系列，而 `ICE_FACTORY_ENABLED = false` ⇒ 实际 6 系列 72 个。该常量的注释本身也错了两处（「禁用 11 级」实为 12 级；「改回 true 即恢复注册」实为还需补 12 张战利品表） | **已修** |

### ⚠️ 本轮引入又修掉的一个回归

手写 refmap 里是 **SRG 名**（打包产物跑 SRG），而 `runClient`/`runServer` 跑的是 **mojmap** jar。
上一轮只验证了产物侧，**没验证 dev 侧** ⇒ `gradlew runClient` 会在 Bootstrap 阶段崩。
`build.gradle` 现按 ForgeGradle 缓存实际布局**动态定位** `srg_to_official_1.20.1.tsrg`
（该路径含 MCP 快照时间戳，写死就是 build.gradle 自己禁止的「本机 + 本次缓存布局的快照」），
并给三个 run 任务传 `-Dmixin.env.remapRefMap=true`；找不到时**显式失败**而不是让 dev 跑出一个看不懂的崩溃。
`TestMixinRefmapIntegrity` 补了对应断言。

### 仍未修（需你定或需实机验证）

| # | 问题 | 备注 |
|---|---|---|
| I-N3 | `CentralKitchenMenu.quickMoveStack` 少算 1 个机器槽 ⇒ 三明治样品被 shift-click 搬进存储区并清空 | 改法明确（`+1`），未动 |
| I-N4 | 中央厨房的搜索/排序/滚动只在服务端算，**没有任何 S2C 包回传** ⇒ 存储浏览器整体是死的 | 需设计同步字段 |
| I-N5 | `ContainerData` 通道是 **16 位有符号** ⇒ 13 台遗留机器能量显示为负；**并顺带关掉**种植切配站的创造升级 UI（`hasCreative` 恒假） | 前两轮都记为「缩放修不了、属架构改动」；本轮复核认为**框定过宽**——绝大多数 Screen 只用比值，正确低成本修法是同步缩放值或百分比槽。**这是三轮都挂在「未完成」栏的那条对账，现已做完**（逐菜单表见报告） |
| I-1 | 制冰工厂 GUI 面板在高档位超出屏幕，玩家背包被推出可视区 | 制冰工厂未注册，实际不可达 |
| I-2 | `MekCkRenderTypes.getIce()` 每帧新建 `RenderType` ⇒ 无界堆增长 + 缓冲缓存失效 | 4 张贴图本可做成 4 个 `static final` |
| I-3 | 冰封贴图 `ResourceLocation` 缺命名空间（`textures/block/textures/block/...`）且 `frosted_ice_0..3` 在本仓与原版都不存在 | 应改用 `minecraft:textures/block/frosted_ice.png` |
| I-5 | `ExtractingRecipe.FluidInput.matches()` 在流体 tag 不存在时 NPE，被 `catch (Throwable)` 吞掉 ⇒ 带 `#tag` 的配方**永远不匹配** | 类注释自称「保守不匹配」，实际没做到 |
| I-6 | 种植切配站的模型硬依赖未声明的 `mekmm`（18 处引用 + 30 条配方） | 需决定：声明依赖 / 换自有模型 / 条件化 |
| I-1(生成器) | 配方生成器在主线程跑 + 强制 `/reload`，大整合包首开服可能超 60 s 看门狗 | 需幂等短路 + 分摊到若干 tick |
| I-8 | `CreativeUpgradeFoodRotator` 用**陈旧快照**覆盖 Forge 刚保存的 `mekck-common.toml` | 删掉手写回写即可 |
| I-9 | `MekckConfig.ice_attack_radius` 上界无意义 | 已由 C-N2 的服务端闸门兜住，配置项本身仍应加上界 |
| M-2 | `item.mekck.ferrero_projectile` 两份 lang 都缺 | 一行 |
| — | 18 个方块把 `getDrops` 覆写成 `List.of()`、物品只在 `onRemove` 掉 ⇒ **TNT/爆炸摧毁时一件不掉** | 需确认是否有意 |
| — | `MixinExtremeSmithingMenu.INFINITY_UPGRADE` 的求值时机存疑 | 需对 Avaritia 做 `javap -v`，该模组不在本地缓存 |

### 新增护栏（337 → 359 测试）

| 测试 | 守什么 |
|---|---|
| `TestTextEncodingIntegrity` | 源码与资源的文本完整性。实测抓到 3 处 U+FFFD（`GuiMekCkSideConfiguration:45`、`PlantingCuttingFactoryExecutor:205`，以及该测试自己第一版用字面量写出的那一处）。**第一轮审查只扫了资源文件因此漏掉 `.java`** |
| `TestNoClientSymbolsInCommonCode` | common 侧不得引用客户端类。`KNOWN_VIOLATIONS` 是**可见的债清单**（6 个 BE 的 `clientTick` → `SoundHandler`、2 个 `Item` 的 `mekanism.client.key`），配一条「每条必须真的还在违规」的反向断言，防清单变成谎言。另有更精确的一条：`network/` 目录零例外 |
| `TestMixinRefmapIntegrity`（新增 1 条） | dev 运行必须传 `mixin.env.remapRefMap`（见上面「本轮引入又修掉的回归」） |
| `TestFactoryGuiSyncSurface` | 工厂 GUI 的同步面：每个 `SyncableInt` 的数据源、getter 与 setter 必须读写**同一批字段**（写错字段的症状与「完全没有同步」完全一样，排查毫无线索） |
| `TestKitchenOutputMergeOverflow` | 产物归并的总量守恒 |
| `TestAttackRadiusClamp` | 攻击半径上下界 + **4 台机器的两个 setter 都必须走共享闸门**（逐文件检查） |
| `TestGrindingOrderEngine`（扩） | 订单推进溢出 + `quantity=0` 的旧档被抬到 1 |

> **两条方法论教训**（都写进了相应测试的注释）：
> ① 一次 `ItemStack.<clinit>` 失败会**毒化整个测试会话**——后续所有碰 `ItemStack` 的用例都变成
> `NoClassDefFoundError: Could not initialize class`。bootstrap 必须逐字照抄
> `TestCuttingBatchPacking#boot` 的配方（`SharedConstants` + 吞 Forge 钩子异常）且放在 `@BeforeClass`。
> ② 第一轮审查的编码检查只扫了 `.json/.toml/.mcmeta/.cfg/.md`，**漏了 `.java`**，因此三轮都以为编码没问题。

---

## 〇、第二轮全量审查（2026-09-30）—— 已被上一节取代，保留作历史

报告：`docs/audit/2026-09-30-full-code-review.md`（分域明细在 `.review/*.md`）。**6 个 Critical 全部已修**：

| # | 一句话 | 状态 |
|---|---|---|
| C1 | **打包产物启动即崩**：没有 refmap ⇒ `MixinItemStack` 匹配不到 `ItemStack.save/of`，而配置是 `required:true` ⇒ 直接终止 | 已修 + **有护栏测试** |
| C2 | 烧烤/穿串/烹饪三家 `appendExtraSlots` 在父类构造期对 `null` 调 `clear()` ⇒ **机器建不出来** | 已修 |
| C3 | 迁到 Mek `BlockTile` 后**破坏丢全部内容**；烹饪 12 档 + `blaze_*` 5 个**连方块都不掉** ⇒ 72 张战利品表重写/新建 | 已修 |
| C4 | `mekckPersistedSlots()` 漏掉家族专属槽 ⇒ 下标 ≥128 的槽**每次存读档静默丢失**（烹饪 35 格，全 12 档） | 已修 |
| C5 | 5 个菜单把 handler 索引当菜单下标 ⇒ shift-click **堆叠翻倍**（可无限刷） | 已修 |
| C6 | `@Redirect` 打在 `TileComponentUpgrade` **类**上 ⇒ **Mek 自己机器**上的静音/过滤升级读档即清零、回写后永久消失 | 已修 |

另修上轮遗留的 **I10**（`MekCkTransfer` int 溢出 ⇒ 两个大堆叠同时消失）等约 10 项 Important，
**新增 33 个回归测试**（272 → 305）。

### ⚠️ C1 的护栏：refmap 是**手写**的，别让它腐烂

`src/main/resources/mekck.refmap.json` 手写（MixinGradle + 注解处理器那条路走不通：AP 会对 4 个
`remap = false` 的 mod 类 mixin 报成员级错误——合成 lambda 名、`$VALUES`/`UPGRADES` 这类
javac 合成字段没有映射——而 `disableTargetValidator` / `@Pseudo` 只能消掉其中一条）。
`TestMixinRefmapIntegrity` 用三条断言钉住它，其中最强的一条拿 `build/reobfJar/mappings.tsrg`
（ForgeGradle 重混淆**自己用的那份映射**）核对 SRG 名，**名字写错也能抓到**：
已用变异测试证明——把 `m_41739_` 改成别的值，该断言立刻变红。

⇒ **只要有人给 `MixinItemStack` 新加一条打原版方法的 `@Inject`，测试先红，而不是等启动崩。**

### 仍未验证

C1 的修复效果需要**真实实例复跑**一次才能坐实（本轮无法自动启动游戏）。
上一轮之所以能定位它，靠的正是实例日志里那条 `No refMap loaded`。

### 需用户裁决（本轮未动，共 11 项）

上轮 I1 / I3 / I8，以及本轮新发现：AE2 job 无回收、`pushPattern` 事务语义、
6 个工厂 GUI **进度条无同步通道**、高并行档 GUI 与玩家背包物理重叠、迁移器串位、
I2 旧档丢升级卡、中央厨房拆模块丢料、`en_us` 缺 21 键。理由见报告 §二。

---

## 一、这一轮做了什么

### 1. 配方条件化（`5a36aa4` + `8e08d6e`）

实例日志 924 条 `Parsing error loading recipe` = **462 个不同配方 × 2** 轮加载。
按堆栈归因出**三类**机制，其中一类此前一直被误判：

| 类别 | 数量 | 机制 |
|---|---|---|
| 未知配方类型 | 158 | `type` 的序列化器来自未安装的 mod |
| **`conditions` 写错 schema** | **155** | 用了 advancement 的 `condition`/`minecraft:all_of`/`terms`，而非 Forge 配方的 `type`/`mod_loaded`/`modid` |
| 未知物品 | 149 | `type` 合法但 `item` 未注册 |

第二类**全部是 mekck 自己的 `beverage_assembly` 配方**。Forge 对每个配方都调
`processConditions(json, "conditions", ctx)`，所以那个 `conditions` 块不是被忽略，
而是被当 Forge 条件解析后抛异常。

**462 个归属**：422 个在本仓库（已修），40 个来自用户世界存档的数据包
`saves/新的世界/datapacks/mekck_planting/`（`mekck:plant_ie/*` 20 + `mekmm:planting/*` 20），
属世界本地内容，**未改**。

### 2. 架构 v4 补齐（`0ce2e76`）

修 v1/v2 遗留的**内部矛盾**（照原文实现会重造被 v3 推翻的抽象）：
§5 分层图里的自定义配方泛型 + `MekCkFactoryRecipe` + 不存在的 `MekCkUpgradeRegistry`；
§11「仍未定」与 §4 已决策不同步。

新增：§6.5 配方来源归属、§8.3 档位接入 `ITier`、§10.1 三个特例工艺的失败模式、
§14 条件化方块注册、§15 测试策略。

### 3. 补配方内容（`a4dc8cf` / `aaf214c` / `43baaf5`）

| 提交 | 内容 |
|---|---|
| `a4dc8cf` | 烟熏炉/篝火烹饪上位为全档位能力（原为晶钛矩阵以上） |
| `aaf214c` | 新增 `mekck:skewering`（签子 + 主料 + 辅料，签子不消耗） |
| `43baaf5` | 新增 `mekck:grilling`（单输入 → 独立的「烤」变体产物） |

**A 组 7 个工艺现已全部无外部可选依赖。**

### 4. 缺陷修复（4 项，各带回归测试）

| 提交 | 项 | 后果 |
|---|---|---|
| `3021e38` | I4 | `MultiFluidHandler.drain` 两个重载：**流体复制** + **静默吞流** |
| `10b4f97` | I6 | 大堆叠掉落物经 `putByte("Count")` 损坏；新增 `MixinItemStack` 让 `McCount` 旁路全局生效 |
| `8ec8e7c` | I7 | 中央厨房加工线程不落盘 → 区块卸载**已扣材料永久损失** |
| `290b3ef` | I5 | `saveToItem` 漏调两个持久化助手 → 挖机再放下，**ME 补料清单与放置器 UUID 必丢** |
| `59c69f1` | — | 实现 `MekCkFactoryTier implements ITier`（兑现 8.3 决策）。**已作废**，见 §5 |

### 5. 档位接入 —— 已作废（`59c69f1` → 阶段 3 Task 0）

`59c69f1` 曾让 `MekCkFactoryTier implements ITier`（`ITier` 只有一个方法 `getBaseTier()`，
映射规则：前 4 档一一对应、其余 8 档全归 `BaseTier.ULTIMATE`，`CREATIVE` 不被任何档位占用）。

**阶段 3 Task 0 查证结论：全仓库 `ITier` 零消费方，已随该枚举一并删除。**
`grep -rn "ITier" src/` 的全部命中只有三处：`MekCkFactoryTier` 自己的声明与 javadoc、
`MekCkMachineTile` 的一句 javadoc、一个**完全不加载生产代码**的测试类。
**没有**任何代码把档位传进接受 `ITier` 的 Mek API——`blockTypeFor` 从来没调用过
`withComputerSupport`，而 `AttributeTier` 对 addon 本就不可设置，
所以 `Attribute.getBaseTier(block)` 对这套档位恒返回 null（这条边界 `59c69f1` 当时就写对了）。

> 原文写的「实际收益是『12 档成为任何接受 `ITier` 的 Mek API 的合法输入』」是一句
> **从未兑现的推测**，不是既成事实。**不要**再把它当作「已接入 Mek 档位体系」的依据。

**为什么连 `ITier` 一起删、而不移植到 `CuttingMachineFactoryTier`**：移植的前提是
「有 Mek API 在消费它」，而查证结果是没有；硬移植等于给 12 档枚举加一个
`baseTier` 字段 + 一个没人读的 `getBaseTier()`，并把死代码带进阶段 3，
还要扩大与并行同学的 `CuttingMachineFactoryTier.java` 冲突面。

档位本身的存档安全结论不变且仍然有效：等级走 `StringRepresentable` 的名字而非 `ordinal()`。

---

## 二、明确判定「不该由我单方面改」的（需你定）

| # | 问题 | 为什么不能机械修 |
|---|---|---|
| **I1** | `ContainerData` 走 `ClientboundContainerSetDataPacket`，字节码证实对 value 用 **`writeShort`**，17 个 BE 裸同步能量 → 客户端显示负数 | **缩放修不了**：装下 4 亿需除数 ≥12211，那样 4000 FE 的普通机器会显示 0。**Mek 整个 jar 里引用 `ContainerData` 的类数为 0**，它走另一套通道，照搬属架构改动。且这是**纯显示问题**，无数据丢失 |
| **I3** | `SideMode.NONE` 落到 `default ->` 返回全权限，六面默认全 NONE ⇒ 侧面配置失效 | 改成返回空会**断掉现有玩家**建立在「默认全开」上的全部管道/漏斗。且 `PULL_INPUT_STORAGE`（枚举第 4 值）**没有任何 capability 对应**——BE 里只有 full/input/output 三个 |

---

## 三、待办（按性质分）

### 内容缺口（需要你定设计）
- 16 个新物品（8 串烧 + 8 烤制）**刻意没设 `food` 属性**——营养值得与主料逐条对齐才算平衡。

### 已关闭
- ~~`frost_cold_brew_upgrade` 的 `minecraft:powder_snow`~~ → **已修为 `powder_snow_bucket`**（`c234e71`）。
  该 id 在 1.20.1 不是物品（`Items` 里只有 `POWDER_SNOW_BUCKET`），配方在任何环境都失败，
  且卡死冷萃攻击链后面两环（链式安装）。选桶而非蓝冰是为保住这条链
  「越往后越贵越冷」的递增感：packed ice → blue ice → 细雪桶 → 暮色森林女王奖杯。

---

## 四、配方层现状：残留错误归零

`tools/predict_residual_recipe_errors.py`（按实例已装 mod 集合模拟 Forge 条件判定）：

```
clean (will load)   : 137
skipped by condition: 425
residual failures   : 0
```

**462 个失败配方已全部清零。** 其中：
- 422 个在本仓库（`5a36aa4` 条件化）
- 1 个 `frost_cold_brew_upgrade`（`c234e71` 改材料）
- 1 个 `creative_upgrade_from_49_foods.json`（另一 AI 的 `541f017` 补了
  `forge:mod_loaded: avaritia` 条件门控——注意它的 `type` 仍是不存在的
  Avaritia 序列化器，条件不通过时整条配方被跳过，不报错）
- 40 个来自用户世界存档的数据包 `mekck_planting`，属世界本地内容，**未改**

> 工具预测有已知局限：`forge:item_exists` 只按 modid 判断，无法离线确认具体物品
> 是否注册。真正的确认要启动客户端看 `logs/latest.log`。

### 跨 agent 交接
- `creative_upgrade_from_49_foods.json`（阶段 1 Task 8）的 `type` 是
  `avaritia:shapeless_table`，而 Avaritia **从未在 `mods.toml` 声明为依赖**。
  只把产物换成 `mekck:upgrade_randomize` **修不好它**，`type` 仍需改。
  这是配方条件化后**唯一**的残留失败项。

### 审查报告里仍未修
- I2（`GrillFactoryBlockEntity` 漏写 `Size` 键，玩家升级区块崩溃）属 A 组，随 Mek 迁移走
- I8 / I9 / I10，其中 I9（`bakeries` 配方含空 Ingredient 时加工机永久静默卡死）可达性未坐实

---

## 五、环境注意事项（会浪费时间的坑）

1. **构建输出编码**：Gradle 走 GBK 控制台，中文错误信息在 bash 里是乱码。
   用 `pwsh -NoProfile -Command '[Console]::OutputEncoding=[Text.Encoding]::UTF8; ...'` 一步解决。
2. **日志别放 `build/` 里**：`clean` 会因文件占用失败。放 `.logs/`（已加进 `.gitignore`）。
3. **并发构建**：两个 AI 同用一个 `build/` 目录会互相踩，本轮撞到 3 次文件锁
   （`clean` 失败 / `reobfJar` 删不掉临时文件）。并行推进建议各用各的工作目录。
4. **查 API 别用 `javap` 猜字段名**——`~/.gradle/caches/forge_gradle/maven_downloader/net/minecraftforge/forge/1.20.1-47.4.16/forge-1.20.1-47.4.16-sources.jar`
   里有全部 Forge 源码**和 vanilla 补丁**。本轮有一次错误的字段名记录（`forge:conditional`
   的 `conditions`/`recipe`）就是 javap 常量池把两个类的字段混了造成的。
5. **`clean` 是必须的**：增量编译对跨类引用是盲的。

---

## 六、本轮踩过的坑（方法论，避免重蹈）

- **三次「查证后判定不是缺口」，避免了无效改动**：COOKING 不需要自有配方类型
  （FD 是强制依赖 + 28 条配方，硬造会脱离 FD/森罗/avaritia 生态丢集成）；
  COOKING 输入槽其实已认森罗食材；`alloys/spectrum` 其实能解析到真实物品。
  **动手前先查证**。
- **三次测试自己写错**：假罐没做「最多只能抽罐内存量」的钳制；断言了 400 而实际应为 0；
  读侧/写侧方法名按全等比较。**测试写错时先怀疑测试**，别改生产代码。
- **一处审查报告描述错误**已订正：`getByte` 返回**有符号** byte，5000 往返是 **-120** 不是 136。
- **批量替换带 `if` 包裹的代码块**要连包裹一起删（改写 `findOrderRecipe` 时留了个多余花括号）。

---

## 七、本轮提交归属（日志里两个 AI 的提交是交错的）

**我（架构把关 / 审查 / 缺陷修复）**：
`5a36aa4` 配方条件化 · `8e08d6e` 更正 conditional 字段名 · `0ce2e76` 架构 v4 ·
`a4dc8cf` 烟熏炉上位 · `aaf214c` `mekck:skewering` · `43baaf5` `mekck:grilling` ·
`3021e38` I4 · `10b4f97` I6 · `8ec8e7c` I7 · `290b3ef` I5 · `0f3f67b` 审查报告更新 ·
`59c69f1` ITier 实现

**另一 AI（阶段 1 升级体系）**：
`7d26f0b` 注入 Upgrade 常量 · `d4c2e1d` `MekCkUpgradeTypes` · `5eeb04a` byItem/capOf ·
`eb7c8fb` 升级上限 · `cbc7fad` 升级持久化改名字键 · `20c9d7f` 存储卡/随机化卡物品 ·
以及 `59260ed` / `d4cf9bd` / `0d1ba28` 等注释与事实订正

**文件边界**：我碰 `factory/`、`recipe/`、`util/`、`blockentity/`、`mixin/MixinItemStack`、
`client` 无关、`tools/`、`docs/`；阶段 1 的 `upgrade/`、`mixin/MixinUpgrade*`、
`mixin/MixinAPILang` 属另一方，**未碰**。

---

## 八、文档索引

| 文档 | 内容 |
|---|---|
| `docs/superpowers/specs/2026-09-29-mek-machine-architecture.md` | **架构 v4**，阶段 2 规格（995 行） |
| `docs/superpowers/specs/2026-09-29-mek-native-machine-framework-design.md` | 阶段 1 升级体系（另一 AI） |
| `docs/superpowers/plans/2026-09-29-mekck-phase1-upgrade-system.md` | 阶段 1 实施计划（另一 AI） |
| `docs/audit/2026-09-29-full-code-review.md` | 全量审查报告，I1–I10 处置状态 |
| `docs/audit/2026-09-29-conditional-recipe-report.md` | 配方条件化报告 |
| `docs/superpowers/handoff/2026-09-29-recipe-availability-handoff.md` | 旧交接文档，**主题已关闭**，保留作 type id 证据表 |

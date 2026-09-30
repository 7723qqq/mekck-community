# mekck 当前状态汇总

- 最后更新：2026-09-30（**第三轮**全量审查；之后另有 3 个提交，见 §一）
- 审查基线：HEAD `bb00171` 之后的工作区
- 验收口径：`./gradlew build`（**联网**；理由见第五节第 6 条）→
  第三轮收尾时 **359 测试 / 0 失败 / 0 错误 / 0 跳过**，且产物内含 `mekck.refmap.json`

本文是**单一入口**：当前状态、未修项、环境注意事项都在这里。
全部文档清单见 [`README.md`](README.md)；第二轮及更早的轮次叙事见 §七。

---

## 一、第三轮全量审查（2026-09-30）—— 最新

4 个 agent 按**包边界**并行深审 + Lead 亲自复核每条 Critical 与产物级证据。
本轮**没有**独立的分域报告文件——结论全部内联在本节。第二轮的分域明细见
[`audit/2026-09-30-details/`](audit/2026-09-30-details/)（基线 `83830e0`，属第二轮）。

### 已修（第三轮 3 个提交）

| 提交 | 内容 |
|---|---|
| `0659f30` | **收编上一轮 C1–C6 的修复成果**。此前 120 个已跟踪文件被改 + **479 个未跟踪文件**（含 `mekck.refmap.json` 本身、审查报告、9 个护栏测试）全部游离在工作区——一次 `git clean -fd` 就全没了，且没有任何东西会失败报警 |
| `b6435bc` | 两个**服务器崩溃级**缺陷 + 订单契约统一 + 仓库卫生 |
| `bb00171` | 存档损坏 / TPS 杀手 / 永久无 AI |

### 第三轮之后（3 个提交，本表未逐条复核）

| 提交 | 内容 |
|---|---|
| `1448ec9` | 中央厨房 off-by-one + 配方流体 tag NPE + 渲染泄漏 + 配置回滚——**修掉下方「未修项」表 7 项** |
| `7403722` | 中央厨房 2 个 Critical（每次存档丢物品 / 订单交付静默销毁）+ 补齐自己修复的漏洞 |
| `5a8756f` | 补回迁移丢失的用途（热容 / 营养液灌注 / 存储区刷新）+ 激活自动加工模式 |

> 护栏数：第三轮收尾 **359** → `5a8756f` 时 **380**（据该提交说明，**未复跑验证**）。

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

### 未修项

> ⚠️ **本表写于第三轮收尾时。** 之后又有 3 个提交（`1448ec9` / `7403722` / `5a8756f`）。
> 其中 `1448ec9` 已修掉下表 7 项——已逐项核实并移入下方「已修」表。
> **其余各项未逐条复核**，以 `git show <提交>` 为准。

**已修（第四轮，2026-09-30）**

| # | 问题 | 修法 |
|---|---|---|
| I-N5 | `ContainerData` 通道是 **16 位有符号** ⇒ 13 台遗留机器能量显示为负；**并顺带关掉**种植切配站的创造升级 UI（`hasCreative` 恒假） | **修法与前两轮的判断都不同，且此前那句「现已做完」是错的**（见下方勘误）。第四轮实测：缩放是**有损**的（容量 4 亿的陈酿机往返误差达 ±6104 FE，tooltip 会显示「399,993,896 / 400,000,000 FE」），而本仓 `SandwichAssemblerMenu` 早就在用**低 16 位 / 高 16 位两个槽**传 32 位值 —— 拆两槽是**无损**的。故新建 `util/WideDataSlot` 统一实现，12 台机器全部改造，护栏 `TestWideDataSlot` 用真实 `ClientboundContainerSetDataPacket` 做字节往返钉死 |

**已修（`1448ec9`，逐项核实）**

| # | 问题 | 修法 |
|---|---|---|
| I-N3 | `CentralKitchenMenu.quickMoveStack` 少算 1 个机器槽 ⇒ 三明治样品被 shift-click 搬进存储区并清空 | 槽数 83 → 84；护栏 `TestMenuQuickMoveSlotRanges` 的表同步更新（原表把该 bug 登记成了「预期行为」） |
| I-2 | `MekCkRenderTypes.getIce()` 每帧新建 `RenderType` ⇒ 无界堆增长 + 缓冲缓存失效 | 改为 `static final RenderType[] ICE_LEVELS`，4 张贴图 4 个实例 |
| I-3 | 冰封贴图 `ResourceLocation` 缺命名空间（`textures/block/textures/block/...`）且 `frosted_ice_0..3` 在本仓与原版都不存在 | 改为返回 0..3 下标，调用方不再持有 `ResourceLocation`，从根上写不出单参构造 |
| I-5 | `ExtractingRecipe.FluidInput.matches()` 在流体 tag 不存在时 NPE，被 `catch (Throwable)` 吞掉 ⇒ 带 `#tag` 的配方**永远不匹配** | 已修 |
| I-8 | `CreativeUpgradeFoodRotator` 用**陈旧快照**覆盖 Forge 刚保存的 `mekck-common.toml` | 已修（配置回滚） |
| I-9 | `MekckConfig.ice_attack_radius` 上界无意义 | `defineInRange(..., 4, IceTargetSearch.MAX_ATTACK_RADIUS)` |
| M-2 | `item.mekck.ferrero_projectile` 两份 lang 都缺 | 两份都已补 |

**仍未修**

| # | 问题 | 备注 |
|---|---|---|
| I-N4 | 中央厨房的搜索/排序/滚动只在服务端算，**没有任何 S2C 包回传** ⇒ 存储浏览器整体是死的 | 需设计同步字段。`5a8756f` 修的是「存储浏览器是开界面那刻的快照」（另一条），**本条未复核** |
| I-1 | 制冰工厂 GUI 面板在高档位超出屏幕，玩家背包被推出可视区 | 制冰工厂未注册，实际不可达 |
| I-6 | 种植切配站的模型硬依赖未声明的 `mekmm`（18 处引用 + 30 条配方） | 需决定：声明依赖 / 换自有模型 / 条件化 |
| I-1(生成器) | 配方生成器在主线程跑 + 强制 `/reload`，大整合包首开服可能超 60 s 看门狗 | 需幂等短路 + 分摊到若干 tick |
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

## 二、需你裁决（第二轮遗留）

> **I1 已关闭**：第三轮把它重新框定为 I-N5，**但第三轮并没真的修** —— 本文件前一版在这里
> 写着「已做完（逐菜单表见报告）」，而全仓检索不到任何那份报告，代码里
> `SimpleMachineMenu.getEnergy()` 也仍是裸的 `data.get(DATA_ENERGY)`。
> **这是一条虚假声明，第四轮勘正。**
>
> 真正的修法见 §一「已修（第四轮）」：不是「同步缩放值或百分比槽」，而是**拆两个槽**（无损）。
> 下表保留原始判断，作为「当时为什么判错」的记录。

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
6. **`clean` 会删掉 `build/fg_cache`，所以 `--offline` 构建不可靠**：验收口径因此是
   **联网**的 `./gradlew build`（见文首）。`--offline` 不允许 ForgeGradle 去补被删掉的缓存。
   详见 [`audit/2026-09-30-full-code-review.md`](audit/2026-09-30-full-code-review.md) §五「最终验证」。

---

## 六、方法论教训（避免重蹈）

- **三次「查证后判定不是缺口」，避免了无效改动**：COOKING 不需要自有配方类型
  （FD 是强制依赖 + 28 条配方，硬造会脱离 FD/森罗/avaritia 生态丢集成）；
  COOKING 输入槽其实已认森罗食材；`alloys/spectrum` 其实能解析到真实物品。
  **动手前先查证**。
- **三次测试自己写错**：假罐没做「最多只能抽罐内存量」的钳制；断言了 400 而实际应为 0；
  读侧/写侧方法名按全等比较。**测试写错时先怀疑测试**，别改生产代码。
- **一处审查报告描述错误**已订正：`getByte` 返回**有符号** byte，5000 往返是 **-120** 不是 136。
- **MixinGradle + 注解处理器那条路走不通，refmap 必须手写**：AP 会对 4 个
  `remap = false` 的 mod 类 mixin 报成员级错误——合成 lambda 名、`$VALUES`/`UPGRADES` 这类
  javac 合成字段没有映射——而 `disableTargetValidator` / `@Pseudo` 只能消掉其中一条。
  `src/main/resources/mekck.refmap.json` 因此是**手写**的，由 `TestMixinRefmapIntegrity`
  用三条断言钉住；最强的一条拿 `build/reobfJar/mappings.tsrg`（ForgeGradle 重混淆**自己用的那份映射**）
  核对 SRG 名，**名字写错也能抓到**（已用变异测试证明：把 `m_41739_` 改成别的值，断言立刻变红）。
  ⇒ **只要有人给 `MixinItemStack` 新加一条打原版方法的 `@Inject`，测试先红，而不是等启动崩。**
  （`.gitignore`、`build.gradle`、`TestMixinRefmapIntegrity` 三处都引用本节。）
- **批量替换带 `if` 包裹的代码块**要连包裹一起删（改写 `findOrderRecipe` 时留了个多余花括号）。

---

## 七、文档索引

**全部文档的清单与状态标记见 [`README.md`](README.md)。** 这里按「该不该读」分组。

### 现行（改动前应读）

| 文档 | 内容 |
|---|---|
| [`superpowers/specs/2026-09-29-mek-machine-architecture.md`](superpowers/specs/2026-09-29-mek-machine-architecture.md) | **架构 v4**，阶段 2 规格（995 行） |
| [`superpowers/specs/2026-09-29-mek-native-machine-framework-design.md`](superpowers/specs/2026-09-29-mek-native-machine-framework-design.md) | 阶段 1 升级体系设计 |
| [`superpowers/handoff/2026-09-29-phase1-runtime-verification.md`](superpowers/handoff/2026-09-29-phase1-runtime-verification.md) | 阶段 1 实机验证清单（需能启动 MC 的环境） |

### 历史

| 文档 | 内容 |
|---|---|
| [`audit/2026-09-30-round2-archive.md`](audit/2026-09-30-round2-archive.md) | **第二轮归档**——本文拆分出的原 §〇 + §一 + §七 |
| [`audit/2026-09-30-full-code-review.md`](audit/2026-09-30-full-code-review.md) | 第二轮全量审查报告（6 个 Critical） |
| [`audit/2026-09-30-details/`](audit/2026-09-30-details/) | 第二轮分域明细 4 份：`machine-core` / `legacy-conservation` / `ae2-network-menu` / `client-recipe-mixin` |
| [`audit/2026-09-29-full-code-review.md`](audit/2026-09-29-full-code-review.md) | 第一轮全量审查报告，I1–I10 处置状态 |
| [`audit/2026-09-29-conditional-recipe-report.md`](audit/2026-09-29-conditional-recipe-report.md) | 配方条件化报告 |
| [`superpowers/plans/2026-09-29-mekck-phase1-upgrade-system.md`](superpowers/plans/2026-09-29-mekck-phase1-upgrade-system.md) | 阶段 1 实施计划 |
| [`superpowers/plans/2026-09-30-mekck-phase2-cutting-factory.md`](superpowers/plans/2026-09-30-mekck-phase2-cutting-factory.md) | 阶段 2 实施计划 |
| [`superpowers/specs/2026-09-29-objmesh-loader-design.md`](superpowers/specs/2026-09-29-objmesh-loader-design.md) | OBJ 网格加载器设计（已实施） |
| [`superpowers/handoff/2026-09-29-recipe-availability-handoff.md`](superpowers/handoff/2026-09-29-recipe-availability-handoff.md) | 旧交接文档，**主题已关闭**，保留作 type id 证据表 |

### 相关

- [`README.md`](README.md) —— 全部文档清单与状态
- [`../tools/README.md`](../tools/README.md) —— 一次性脚本与审计工具
- [`../README.md`](../README.md) / [`../README.zh_CN.md`](../README.zh_CN.md) —— 面向玩家的模组说明

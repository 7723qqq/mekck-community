# 阶段 1 升级体系 —— 实机验证清单

- 日期：2026-09-29
- 面向：在**能正常启动 Minecraft 的环境**里做验证的人
- 配套提交：`cbc7fad`（持久化 Mixin）、`0d1ba28`（Upgrade 常量）、`eb7c8fb`（适配层）、`20c9d7f`（两张卡）、`541f017`（资源）、`7bb07c2`（最终审查）
- 测试基线：`./gradlew test` → **115 通过 / 0 失败**

## 0. 前置：两个环境都不行

| 环境 | 状态 |
|---|---|
| 本仓库 dev 环境 `D:\mc\mod\mekck` | **起不来**。Farmer's Delight 自己的 `farmersdelight.mixins.json:KeepRichSoilGiantTreeMixin` 注入失败（`run/logs/debug-5.log.gz`，2026-09-10 起的既有问题，与本项目无关） |
| 独立实例 `D:\mc\新建文件夹\versions\1.20.1-Forge_47.4.23` | 可用 —— **本清单在那个实例上做** |

装 MekCK 构建产物 + Mekanism 10.4.x + Farmer's Delight 1.2.7 启动。

---

## 1. 4 个 Mixin 能否应用（**最高优先级，其余全部依赖它**）

启动时任何 Mixin 失配都会**立刻崩**（`mekck.mixins.json` 的 `injectors.defaultRequire` 是 `1`，这是刻意配置）。所以：

- [ ] **游戏能进主菜单 / 进世界** = 这一关过了
- [ ] 若崩，错误形如：

  | 异常 | 含义 | 处置 |
  |---|---|---|
  | `InvalidMixinException` / `InvalidAccessorException` | `@Invoker` 描述符或 `@Shadow` 对不上 | 核对 `javap -p -s mekanism.api.text.APILang` 与 `mekanism.api.Upgrade` 的真实描述符 |
  | `InvalidInjectionException: @Redirect ... could not find any targets` | 注入目标方法名变了（`lambda$read$1` 是 javac 合成名，跨 javac 版本可能变号） | 重新 `javap -p -c` 找新的 lambda 名 |
  | `MixinApplyError ... in config [mekck.mixins.json]` | 目标类名变了 | 同上 |

日志位置：实例的 `logs/latest.log`。搜 `Mixin` / `InvalidMixin` / `Exception`。

---

## 2. 注入的常量是否真的存在

在游戏内用命令验证（需要 OP，或开作弊）：

- [ ] **两张卡能拿到，且能正常合成**（说明物品注册 + 配方都对）

  ```
  /give @s mekck:upgrade_storage
  /give @s mekck:upgrade_randomize
  /recipe give @s mekck:crafting_shaped/upgrade_storage
  ```

- [ ] **悬停两张卡显示正确名称与说明**（不是原始 key 字符串）。
  若显示成 `tooltip.mekck.upgrade_storage` 这样的字面量，说明 `assets/mekck/lang/{en_us,zh_cn}.json` 的 8 条键没被加载——查 jar 里资源是否打包成功。

- [ ] **JEI 里能看到两张卡**，且在 49 食物合成表存在时能查到随机化卡的配方

---

## 3. 升级能否装上机器（**阶段 1 预计不通过，属预期**）

- [ ] 把 `mekck:upgrade_storage` 潜行右键任意工厂

**预期结果：装不上，或者装了也不生效。**

原因：阶段 1 只落地了机制。实测当前**没有任何 MekCK tile 持有 `TileComponentUpgrade` 实例**——

```
BioreactorBlockEntity:603              getComponent() → return null
PlantingCuttingFactoryBlockEntity:1695 getComponent() → return null
PlantingCuttingStationBlockEntity:937  getComponent() → return null
```

机器仍在用自研的 `MekCkUpgradeTracker`（93 处引用），完全走不到我们的 Mixin。

**这一条不通过是正常的。** 但如果卡能装上、且旧机器的升级 UI 出现异常，那才是问题——说明 Mixin 误伤了存量机器。

---

## 4. 真正要验的是阶段 2（机器改用 `MekCkMachineTile` 之后）

阶段 2 落地后重跑本节。这几项是本阶段所有代码的**真正验收点**：

- [ ] **存储卡装上后线程数提升**：装 n 个，`并行线程` 从 `base` 变成 `base × 2^n`，
      上限为 `min(base × 2^n, MekckConfig 的 <tier>_stack_max)`。
      **不支持倍增的档位（SINGULARITY）应拒绝安装**——判据是
      `CuttingMachineFactoryTier.supportsStackUpgrade()`。

- [ ] **奇点创世（SINGULARITY）拒绝存储卡**。若能装上，是 `MekCkUpgradeTypes.isSupportedBy` 的准入闸门没生效。

- [ ] **20 tick 读条**正常推进，物品被消耗，数量正确

- [ ] **🔴 点「移除升级」不崩服**（这是 C1 修复的验证点，最关键的一条）

  装上存储卡 → 在 Mek 升级界面点移除 → 服务端**不能**出现
  `IncompatibleClassChangeError`。

  若崩了，说明 `MixinUpgradeUtilsGetStack` 没生效或 HEAD 取消条件写错了。
  没有这个 Mixin 的话，**100% 会崩**——`UpgradeUtils.getStack` 用 `ordinal()` 索引一张
  只填了 7 个原生常量的合成 switch 表，注入常量落 `default` 抛异常，
  而 `TileComponentUpgrade.removeUpgrade` 的唯一调用者是服务端收包
  `PacketGuiInteract$GuiInteraction`。

- [ ] **存档往返无损**：装 3 个存储卡 → 存档 → 重进世界 → 仍是 3 个

- [ ] **换 mod 组合不丢数据**（阶段 1 设计的核心目标，本地无法完整验证）：
  装了 Mek Extras 时存盘 → 卸载 Mek Extras → 重进世界，**存储卡数量不变**
  （名字键持久化的价值就在这里；Mek 自己的 ordinal 索引会静默错配成别的升级）

- [ ] **未支持的升级类型读档被丢弃**：手改存档塞一条 `gas: 8`，
      重进后该机器的 gas 升级应为 0（`MekCkUpgradeTypes.capOf` 的准入闸门）

### 4.1 阶段 2 已落地：切菜工厂实机验证清单

> 阶段 2 把切菜工厂（12 个方块）从自研 `BlockEntity` 迁到了 Mekanism 原生机器体系。
> 它是**第一个真正持有 `TileComponentUpgrade` 的 MekCK tile**——意味着阶段 1 的 4 个 Mixin
> **从这一版开始第一次真正生效**。下面 7 组分别对应迁移过程中发现并修掉的 7 个真实缺陷。

⚠️ **写这一节的人一条都没跑过。** 本仓库起不了游戏（Farmer's Delight 自身 mixin 崩溃，
2026-09-10 起的既有问题），下面每条都是**纸面推导 + 静态自检**的产物。
请当作操作指引，不要当作已验证结论。

**准备**（在 `D:\mc\新建文件夹\versions\1.20.1-Forge_47.4.23` 上）

- MekCK 构建产物 + Mekanism 10.4.x + Farmer's Delight 1.2.7
- 测 4.7（AE2）时再加 Applied Energistics 2
- 测 4.1（换 mod 组合）时再加 Mek Extras
- **开一个一次性测试世界**，全程别拿主存档试
- 两张升级卡用 `/give @s mekck:upgrade_storage` 与 `/give @s mekck:upgrade_randomize` 拿

**档位基础数值**（`CuttingMachineFactoryTier`，测 4.5/4.6 时要对着看）：

| 档位 | 并行 | 声明每 tick 能耗 | 能效乘数 | 支持存储卡 |
|---|---|---|---|---|
| BASIC | 3 | 60 | 1.00 | ✗ |
| ADVANCED | 5 | 100 | 0.80 | ✗ |
| ELITE | 7 | 140 | 0.65 | ✗ |
| ULTIMATE | 9 | 180 | 0.50 | ✗ |
| ABSOLUTE | 11 | 220 | 0.40 | ✓ |
| SUPREME | 13 | 260 | 0.32 | ✓ |
| COSMIC | 15 | 300 | 0.25 | ✓ |
| INFINITE | 17 | 340 | 0.20 | ✓ |
| BLAZE | 25 | 380 | 0.15 | ✓ |
| CRYSTAL_MATRIX | 36 | 500 | 0.10 | ✓ |
| NEBULA | 49 | 0（免能耗） | 0.00 | ✓ |
| SINGULARITY | 81 | 0（免能耗） | 0.00 | **✗** |

「支持存储卡」的判据是 `CuttingMachineFactoryTier.supportsStackUpgrade()`，
即 `ordinal() >= ABSOLUTE.ordinal() && this != SINGULARITY`——**只有奇点创世是例外**，
别把它当成「越高阶越不支持」。

⚠️ **「声明每 tick 能耗」那一列不是实际扣电量。** 它只喂给 Mek 的
`AttributeEnergy.getUsage()`（GUI 顶部显示）与 `getUsage() > 0` 的「这是不是免能耗档」布尔判断。
**真实扣电走** `ceil(20 × 速度倍率² × 能量卡倍率 × 能效乘数) × 活跃槽数 × 堆叠倍率`。
所以 4.5 里拿「GUI 显示」和「实际消耗」对不上是**正常的**，别当 bug 报。

---

#### 4.0 冒烟：能启动、能出活、并行数跟着卡涨

- [ ] **启动不崩**——这是本阶段的头号门槛。切菜工厂是**第一个真正持有
      `TileComponentUpgrade` 的 MekCK tile**，阶段 1 的 4 个 Mixin **从这一版开始
      才第一次真正被应用**。之前它们挂在一个从没被调用到的路径上，
      失配了也不会暴露。

  **预期现象**：进主菜单、进世界，全程无异常。`logs/latest.log` 里搜
      `Mixin` / `InvalidMixin` / `Exception` 应当干净。

  **若不满足说明什么**：见第 1 节的异常对照表。`defaultRequire` 是 `1`，
  任何注入失配都会**立刻崩**——这是刻意配置，所以「起不来」几乎一定是 Mixin 问题，
  不用去别处找。

- [ ] 12 个切菜工厂方块（`mekck:basic_cutting_factory` … `mekck:singularity_cutting_factory`）
      **全部**能放置、能潜行右键打开 GUI、关闭后物品不掉落。
      `facing=` 变体（含 6 个朝向）也都要能正确显示模型。

  **预期现象**：12 个方块外观各不相同（各档 RGB 不同），GUI 能开，槽位格子数 = ⌈√并行⌗²。

  **若不满足说明什么**：方块描述 `BlockTypeTile` 缺属性。三个已知症状对号入座——
  右键不开界面 = 缺 `withGui`；`facing=` 变体方块隐形 = 缺 `AttributeStateFacing`；
  看不到升级槽 / 升级 tab = 缺 `withSupportedUpgrades`。

- [ ] **切菜能出活**：往 BASIC 档的某个输入槽里放一份有 `CuttingBoardRecipe` 的原料
      （比如南瓜），补足电，观察 200 tick（无速度卡）后产物槽里出现成品，
      原料按配方被正确消耗。

  **预期现象**：进度条推进、原料减少、产物出现，**数量正确**（不是多也不是少）。
  产物槽塞满时应当**停止产出**而不是把剩余产物丢地上（输出溢出钳制）。

  **若不满足说明什么**：`CuttingFactoryExecutor` 的配方匹配或批量执行有问题。
  注意每槽配方缓存（`slotRecipeValue`）是刻意保留的优化，**不要**因为「好像没重算」
  就去改它。

- [ ] **并行线程数随存储卡翻倍**。造一台 **ABSOLUTE** 档（基础并行 11），
      分别在 0 / 1 / 3 个 `mekck:upgrade_storage` 下，往**每个**输入槽放满同样的量，
      比较一批完成的时间。

  **预期现象**：每装 1 张卡，单批时间减半；装 n 张则并行数变 `base × 2^n`，
  上限 `min(base × 2^n, MekckConfig 的 <档位>_stack_max)`（默认 `<档位>_stack_max = 6`，
  即最高 ×64）。

  **若不满足说明什么**：`CuttingFactoryExecutor.stackMultiplier` 的 clamp 逻辑断了。
  ⚠️ **BASIC / ADVANCED / ELITE / ULTIMATE / SINGULARITY 这 5 档根本装不上存储卡**
  （`supportsStackUpgrade()`），装不上是**正确**的，别去调配置硬开。

- [ ] 打开任意一台的 GUI，**同时**把玩家物品栏和机器槽位都填满再关闭，确认不掉落、不复制。

  **预期现象**：关 GUI 的瞬间，机器槽里的物品一件不少地回到物品栏（放不下的部分掉在脚边），
  总数守恒。

  **若不满足说明什么**：槽位暴露那层（`InventorySlotHelper` / `IInventorySlot`）有问题。

---

#### 4.1 修复 1：升级持久化从 ordinal 索引换成名字键

> 原症状：Mekanism 自己用 `Upgrade` 的 `ordinal()` 当存档下标，而 `byIndexStatic` 是
> **取模回绕**的。装了 Mek Extras（它也往 `Upgrade` 里注入常量）之后，ordinal 分配
> 会整体后移， MekCK 的存档就会**静默张冠李戴**——升级被读成别的种类或直接归零，**无任何日志**。

- [ ] 装上 **Mek Extras**，在任意一台切菜工厂上装 **3 个** `mekck:upgrade_storage`，
      存盘 → 退出 → 重进世界，确认仍是 3 个。

- [ ] **关键一步**：把 Mek Extras 从 `mods/` 移走，重启游戏，重进**同一个存档**。

  **预期现象**：存储卡**仍是 3 个**，升级 tab 正常显示，机器照常工作。
  启动日志里**没有**任何升级反序列化相关的警告或异常。

  **若不满足说明什么**：名字键持久化没生效，或 `MixinTileComponentUpgradePersistence`
  的 `read` 重定向没命中（阶段 1 的反射桥接 `mekck$tier()` 返回了 `null`，
  `isSupportedBy(STORAGE, null)` 判 false → 存储卡被判为「不支持」→ 读档丢弃）。
  **这是静默失败，日志不会告诉你**，只能靠这一条对照出来。
  此时先确认 `logs/latest.log` 里 Mixin 全部应用成功（见第 1 节），再回头查。

- [ ] 顺手验一下**读档准入闸门**：手改存档，往某台机器的升级数据里塞一条 `gas: 8`，
      重进后 gas 升级应为 **0**（`MekCkUpgradeTypes.capOf` 把不支持的类型裁到 0）。

---

#### 4.2 修复 2：点「移除升级」不能崩服

> 原症状：玩家在 Mek 升级界面点「移除」，服务端**必崩**。
> `UpgradeUtils.getStack` 用 `ordinal()` 索引一张只填了 7 个原生常量的合成 switch 表，
> 注入常量（MekCK 的 `storage` / `randomize`，以及别的 mod 注入的）落到 `default` 分支
> 抛 `IncompatibleClassChangeError`；而 `TileComponentUpgrade.removeUpgrade` 的唯一调用者
> 是服务端收包 `PacketGuiInteract$GuiInteraction`。

- [ ] 装上 `mekck:upgrade_storage` → 打开升级界面 → 点**移除** → 确认**服务端不崩**。

- [ ] 装上 `mekck:upgrade_randomize` → 同样移除一次。

  **预期现象**：升级正常消失，机器继续工作，控制台没有异常堆栈。

  **若不满足说明什么**：`MixinUpgradeUtilsGetStack` 没生效，或 HEAD 取消条件写错了。
  **没有这个 Mixin 就是 100% 崩**——所以这条不过，第 1 节（Mixin 能否应用）一定有别的问题。

- [ ] 移除后**关档重进**，确认移除是持久的（没有偷偷变回来）。

---

#### 4.3 修复 3：SINGULARITY 每次存读档丢 35 个槽

> 原症状：Mekanism 的 `DataHandlerUtils.writeContents` 用 **`putByte`** 存槽位下标，
> `readContents` 的 `getByte` **遇负值直接跳过**。`byte` 上限 127，而奇点创世
> 并行 81 ⇒ 布局是 `[0,81) 输入 + [81,162) 输出 + [162] 能量槽`，
> 于是**第 128~161 号那 34 个输出槽 + 第 162 号的能量槽每次存读档静默丢失**。
> 修法是 MekCK 自己再写一份 **int 下标**的槽位数据（存档键 `MekCkSlots`），
> 在 `super.load` **之后**覆盖回来。

⚠️ **这一条的操作门槛最高，见下面「怎么才碰得到丢的那 35 个槽」。**

- [ ] 造一台 **SINGULARITY** 切菜工厂（没解锁的话用命令
      `/give @s mekck:singularity_cutting_factory`）。

- [ ] **只往输出槽的后 34 个塞满物品**（下标 128~161），保存 → 退出 → 重进。

  **预期现象**：那 34 个输出槽里的物品**一件不少**，能量槽（162）的存量也还在。

  **若不满足说明什么**：`MekCkSlotNbt` 的 int 下标覆盖没生效——最可能是
  `load()` 里覆盖的时机写到了 `super.load(tag)` **之前**（那样会被 Mek 的 byte 版本
  整条盖掉，且顺序反了等于没写这一层，同样是静默丢数据、零日志）。
  `TestMekCkSlotNbt#mekckSlotReadIsAppliedAfterSuperLoad` 把这条顺序钉死了，
  但它是源码不变量测试，**验不了运行期**。

- [ ] 回归：再在**前几个**输出槽（下标 < 128）也塞点东西，一起存读一次。

  **预期现象**：前段与后段都在。低 128 位的行为与修复前一致（这一条验的是「别修坏」）。

  **若不满足说明什么**：前段也丢了 = 覆盖写反了顺序（`super.load` 之后才覆盖是硬要求，
  顺序反了等于没写这一层），或者 int 下标那份根本没被 `load` 读到。

- [ ] 幂等：同一份存档**连续读两次**（重进两次），槽位内容应当完全相同。

**怎么才碰得到丢的那 35 个槽**（最容易被跳过的一步）：奇点创世的输出区是
**9×9 = 81 格**，从左上角数起，**第 128 号槽在第 8 行第 4 列**（127 号是最后一行第 1 列）。
GUI 里默认只显示前几行，**必须一路滚到最底部**才能点到最后 34 格。
只在前两三行塞东西的话，这条会**假通过**——那 35 个槽压根没被触及过。

---

#### 4.4 修复 4：槽位单槽上限（旧实现无上限）

> 原症状：Mek 的 `BasicInventorySlot.DEFAULT_LIMIT` 是 **64**，而旧的自研 BE 无上限。
> 迁移后若不管，单槽就被截到 64，**高倍合成倍率下余料会掉地上**。
> 修法是新增 `MekCkSlot`（继承 `BasicInventorySlot`，构造后把
> `obeyStackLimit` 置 `false`），上限读配置 `slot_limits.slot_limit`，
> **默认 `2147483647`（= Integer.MAX_VALUE），与旧实现零漂移**。

- [ ] **默认配置**下，往任意一台切菜工厂的**单个**输入槽里塞 **超过 64 个**的物品
      （例如 100 个同类原料，用中键/回车逐个叠加）。

  **预期现象**：单槽能装到 100 个，**不会卡在 64**，也不会溢出掉到地上。

  **若不满足说明什么**：`MekCkSlot` 的 `obeyStackLimit` 没置成 `false`。
  ⚠️ 7 参构造把 `obeyStackLimit` 硬钉成 `true`，`getLimit` 于是返回
  `min(limit, 物品自身堆叠上限)`——**这会让配置项形同摆设**，是本条最容易漏的一环。

- [ ] 打开 `config/mekck-common.toml`，确认 `slot_limits.slot_limit` 存在且为
      `2147483647`；把它改成 `64`，重启，再塞 100 个。

  **预期现象**：单槽被截到 64，多的留在玩家手上，**不掉地上、不消失**。

  **若不满足说明什么**：上限没接进 `MekCkSlot` 构造（`getInitialInventory` 里还在用
  `InputInventorySlot.at(...)` / `OutputInventorySlot.at(...)`）。

- [ ] 确认**产物槽不能被别人往里塞东西**：让漏斗/管道对着产物槽推物品。

  **预期现象**：只有机器自己能写产物槽，外部自动化推不进去。
  导入方向正常（从机器里吸走产物可以）。

  **若不满足说明什么**：产物槽的 `canInsert` 被错配成了 `notExternal`。
  `notExternal` 是**输入槽**的 `canExtract` 判据，给产物槽用会让玩家和自动化
  都能往产物槽里白塞东西。

---

#### 4.5 修复 5：机器此前从不扣电

> 原症状：抽取能量时带了 `EXTERNAL` 语义，被容器的 `notExternal` 谓词**整条拒掉**，
> 所以机器**照转不误、一次电都没扣**。修法是改成 `MANUAL`（人工取电语义）。
> 顺带的后果：**空电量时机器现在会停下来**——这是修复，不是回归。

- [ ] 给一台 BASIC 切菜工厂充**已知数量**的能量（比如用创造模式能量监视器对接口充
      正好 12000 FE = 足够跑 100 轮），放入一份 `CuttingBoardRecipe` 原料，让它开工。

  **预期现象**：能量条**肉眼可见地往下掉**，且总耗电 ≈
  `ceil(20 × 1² × 1 × 1.00) × 3 槽 × 1 倍率 = 60 FE/tick`。
  BASIC 档工作 200 tick（无速度卡）跑完一批，理论耗电 `60 × 200 = 12000 FE`，
  刚好把电跑干。**误差在 1~2 轮之内都算正常**（取整 + 收尾那一 tick 不满负荷）。

  **若不满足说明什么**：能量条纹丝不动 = 修复没生效，去查抽取时带的
  `notExternal` 谓词是不是又回来了。

- [ ] **空电量时机器必须停下来**（这是本条修复的另一半，容易被误当成新 bug）：

  把上面的电跑光，原料槽**保持有货**，观察机器。

  **预期现象**：进度条**停住**，不再推进，产物**不再产出**；补电后**自动接着跑**
  （进度不清零）。

  **若不满足说明什么**：修复前就是「照转」——空电也在消耗玩家库存里的原料。
  若现在仍然照转，说明能量闸门那段三条件（`canOperate && anyValid && 能量够`）没接上。

- [ ] 对照一条：**免能耗档**（NEBULA / SINGULARITY）应当**永远不扣电**，
      充不充电都一样能一直转。这两条一起看，才说明闸门是「按需」而不是「恒扣」。

---

#### 4.6 修复 6：档位能效乘数（高阶更省电）

> 这是**阶段 2 新增的设计**，不是修 bug。真实扣电公式现在多乘一个
> `CuttingMachineFactoryTier.energyEfficiency`（运行期可由配置 `<档位>_energy_efficiency` 覆盖）：
> `ceil(20 × 速度倍率² × 能量卡倍率 × 能效乘数) × 活跃槽数 × 堆叠倍率`。
> 设计意图是「高阶更贵，所以也更省电」，等比曲线让玩家按「贵一档 ≈ 省两成电」估算。

⚠️ **别用 SINGULARITY 做对照**——它是免能耗档（乘数 0，且公式第一件事就短路返回 0），
拿它比出来的是 100% 差值，**验证不了曲线**。请用 BASIC / ABSOLUTE / SUPREME 这一组。

- [ ] 分别造 **BASIC**（乘数 1.00）、**ABSOLUTE**（0.40）、**SUPREME**（0.32）三台，
      每台装**同样的升级组合**（都装 0 张卡），往**每个槽**里放**同样的数量**的原料，
      让它们各自跑完**同样份数**的产物。

  **预期现象**：单位产出能耗按 `1.00 : 0.40 : 0.32` 递减——
  ABSOLUTE 大约只花 BASIC 的四成，SUPREME 大约三成二。
  允许的偏差：`ceil` 取整带来的每轮几 FE，加上「收尾那一 tick 不满负荷」。
  SUPREME 是 12 档里唯一一个 `20 × 0.32 = 6.4` 非整数的档，
  它是**唯一会被取整方式影响**的档，出现 ±1 轮都属正常。

  **若不满足说明什么**：三台耗电一样 = 能效乘数没接进 `baseEnergyPerTick`。
  注意乘数是**乘在 `ceil` 之前**的（乘在之后会被 `(int)` 向零截断二次伤害，
  SUPREME 会少收约 14.3%），这一点写反了症状就是「SUPREME 莫名比别人便宜一点」。

- [ ] 改配置验证它是**可配**的：把 `supreme_energy_efficiency` 从 `0.32` 改成 `1.00`，
      重启，SUPREME 的单位耗电应回到与 BASIC 持平。

  **若不满足说明什么**：配置没接上，或者改完没重启（Forge 配置只在启动时读一次）。

---

#### 4.7 修复 7：AE2 链路此前 5 个挂载点全缺

> 原症状：新 tile **完全没有调用** `attachCapabilities` / `serverTick` /
> `saveAdditional` / `load` / `onRemoved` 这 5 个 AE2 挂载点。
> 表现是**完全不崩、就是没反应**：接不上 ME 网络、频道不占、样板不下单、
> 勾选的自动补料清单每次重载都丢。

- [ ] 把切菜工厂用 AE2 电缆接进一个 ME 网络（旁边放个 ME 存储柜 + ME 终端）。

  **预期现象**：
  - 机器**占用 8 个频道**（1 个主节点暴露接电缆 + 7 个内部节点直连）。
    用 `/ae2 dump` 或 JEI 的 AE2 页数确认频道数是 8 而不是 0 或 1。
  - 机器不出现在「AE2 未支持的方块」列表里。

  **若不满足说明什么**：`attachCapabilities` 没接上（`NetworkChefProgress.isAe2Machine`
  没认 `IMekCkPorted`），或者 `reviveCaps` / `invalidateCaps` 有问题导致重载后能力失效。

- [ ] 在 ME 终端里**搜索切菜配方**（比如「南瓜」），确认能看到加工样板。

  **预期现象**：样板按**网络内当前可用的食材**动态注册，能编码、能下单。

  **若不满足说明什么**：`CraftingProvider` 的刷新（每 40 tick 一次）没跑，
  或 `autoWindow` 的家族闸门把切菜挡住了——`IMekCkPorted` 本身只说「哪些槽参与自动化」，
  **不说这机器做什么工艺**，配方族要从 tile 的 `MekCkFactoryType` 读，
  只有 `CUTTING` 认。

- [ ] 真的下**一单**，观察机器开工、产物**自动回写**到 ME 存储。

  **预期现象**：材料从网络抽进输入槽，机器开工，产物回到网络，
  中间不需要玩家插手。**一单做完产物不应该翻倍**。

  **若不满足说明什么**：产物翻倍 = 跨单不清空的语义用错了。切菜的每个输入槽都是
  **一次性消耗型**（每跑完一批就按配方整批吃掉），所以
  `mePersistentItemInputs()` 必须返回**空**，否则 AE2 每单都以为还欠着料、重复投料。

- [ ] 在机器的 AE2 界面勾几个「自动处理材料」，存盘 → 重进世界。

  **预期现象**：勾选清单**还在**。清单存在 AE2 网格宿主上（存档键 `MekCkAutoSel`），
  随节点生命周期一起持久化。

  **若不满足说明什么**：`AE2Compat.saveAdditional` / `load` 没接上
  —— 清单每次重载都换一批频道就是它丢了的症状。
  ⚠️ 这批勾选**不会**从旧格式的 `AutoSelectedItems` 键自动迁移过来，
  旧存档迁移（`MekCkLegacyMachineNbt`）**没覆盖这一项**，属已知代价不是 bug。

---

#### 4.8 旧存档迁移

> 方块注册名没变，但 `BlockEntityType` 的实现类换了。旧 NBT 无人读取的话，
> **机器内容会静默归零**（方块还在，里面空了）。

- [ ] 用**迁移前**的版本造一台切菜工厂，塞点东西（物品 + 能量 + 升级 + 改侧配 + 改名），
      存盘 → 换成**当前**版本 → 重进同一存档。

  **预期现象**：物品（含输出槽）、能量存量、已装升级、六个面的侧配、自定义名称**全部还在**。
  控制台没有 NBT 解析异常。

  **若不满足说明什么**：`MekCkLegacyMachineNbt` 的迁移入口没接上。
  ⚠️ 侧配是**两套不同格式**（旧的是 6 字节 `SideMode.ordinal()` 数组，
  Mek 用自己的转码），需要显式转换；若这块没转，机器能用但自动化方向全反，且**不报错**。

- [ ] 同一份存档**连续读两次**，结果应当**完全相同**（迁移幂等）。

---

#### 4.9 GUI 布局（**这是预期变化，不是回归**）

- [ ] 对照旧版截图看切菜工厂 GUI。

  **预期现象**：各 tab 的位置与旧版**不同**。旧界面是 741 行手摆 tab，
  新界面用 Mek 的 `GuiConfigurableTile` 布局引擎自动排：
  侧配(6) / 传输配置(34) / 升级(6，右) / 红石(137，右)。

  **若不满足说明什么**：**没有**「若不满足」——差异本身就是预期。
  但要确认差异是**坐标变了**而不是**元素丢了**：四个 tab 都还在、都能点开、内容完整。

- [ ] 顺手确认升级 tab 现在**能看见两张 MekCK 卡片**（`upgrade_storage` / `upgrade_randomize`）。

  **若不满足说明什么**：`getSupportedUpgrades()` 若返回了
  `MekCkUpgradeTypes.all()` 整包，会把 40 个外部常量也声明成支持，
  表现为升级列表里塞满别的 mod 的卡。当前实现是逐个过
  `MekCkUpgradeTypes.isSupportedBy(type, getTier())` 过滤的。

---

#### 4.10 🔴 阶段 3 的行为变更提醒（现在**不会**发生，迁移那一刻才发生）

> **阶段 2 只迁了切菜工厂一个家族。** 其余 **6 个家族**（烹饪 / 穿串 / 烧烤 /
> 种植切配 / 研磨 / 制冰）**加 5 个独立机器**（生物反应器 / 中央厨房 / 巧克力炮 /
> 坚果烘焙机 / 三明治机）**仍然跑在旧的自研 `BlockEntity` 上**。
> 它们**没有**能效乘数——旧公式里 `ENERGY_PER_PROCESS` 与档位无关，
> 12 个档位的**单位产出能耗完全相同**，高阶只是并行多。
>
> **阶段 3 推广同一套模板到那 11 个机器的那一刻，它们的耗电会从「没有曲线」
> 变成「有曲线」——高阶档每单位产出开始更省电。**
>
> 这是一处**玩家可感知的行为变更**：
> - **升级过的老存档**里，那些机器的耗电会**变低**，等于**存量玩家的机器被动变强**；
> - 依赖耗电做产线预算的**配方/红石/计数器方案**会**失准**；
> - 若有人拿阶段 2 的数字当基准去对比阶段 3，会**以为算错了**。
>
> 建议处理（属于阶段 3 的范围，本任务不碰）：
> 1. 在更新日志 / 配包里显式写一句「高阶机器单位产出能耗下调」；
> 2. 能效乘数是**可配**的（`<档位>_energy_efficiency`，`0.0`~`10.0`），
>    想保持阶段 2 之前的手感，把那 11 个机器的对应档位值全设成 `1.00` 即可，**不用改代码**；
> 3. 阶段 3 迁移前后各跑一次 4.6 那组对照，把两组数字记进交接文档。

**现在的验法**（确认「切菜已经改了、其余还没改」这个状态本身）：

- [ ] 各造一台 **BASIC** 与一个 **ABSOLUTE**（或 SUPREME）版本的**烹饪工厂**，
      同样配方、同样份数、同样的每 tick 耗电。

  **预期现象**：两者**单位产出能耗基本相同**（旧 BE 的 `energyPerTick` 与档位挂钩，
  但 `ENERGY_PER_PROCESS` 不分档，单位产出能耗不会随档位形成曲线）——
  **注意这一条与 4.6 切菜工厂的现象正好相反**，是刻意的。

  **若不满足说明什么**：烹饪工厂也出现能效曲线了 = 有人提前动了它的代码，
  阶段 3 的变更会被**混进阶段 2 的验收**，基线就不可信了。

---

## 4.11 阶段 3：五个已迁家族的实机验证清单

> 阶段 3 至今**零运行期验证**，只靠编译 + 单测（`262 / 0 失败`）。
> 已迁五族：切菜 Cutting（阶段 2）、研磨 Grinding、种植切配 PlantingCutting、
> 烧烤 Grilling、**穿串 Skewering**（Task 5）。剩烹饪 / 制冰两个工厂家族
> 与 5 台独立机器。

### 4.11.0 冒烟（每族各做一遍，不过这关后面都白搭）

- [ ] 五族各放一台 **BASIC**、各放一台 **ABSOLUTE**，右键能开界面，**机器在转**。
      「界面能开、进度条不动」= tile 类型没绑对或 ticker 为 null——
      `TileEntityTypeRegistryObject.getTicker(boolean)` 没有任何兜底，
      两个 ticker 少给一个就是静默不干活（各族注册循环里那 8 行注释记着这条）。
- [ ] 放**最普通**的材料进去，机器能出活。
- [ ] 拆掉方块，物品掉落**恰好一份**（`BlockMekanism.onRemove` + loot table 接管；
      旧实现里手动 `Containers.dropItemStack` 那条路已删，两条都在就是双份掉落）。
- [ ] 断电 / 断红石 → 机器停；恢复 → 继续，**进度条不跳回 0**（保持进度只暂停）。

### 4.11.1 升级（每族各做一遍）

- [ ] 速度卡：批次变短、进度条同步变短。
- [ ] 能量卡：能耗下降、**容量上升**。
- [ ] 存储卡：并行倍率 / 批次量按该族口径上升（见下表）。
- [ ] 存读档后升级**仍在**，且仍生效。
- [ ] 升级窗口里能看到 4 种卡的格子，**且只有这 4 种**（`supportedUpgrades` 白名单）。

| 家族 | 批次/并行倍率基数 | 存储卡效果 |
|---|---|---|
| 切菜 / 研磨 / 烧烤 | `MekckConfig.getMultithreadedBase` | 倍增并行槽数 |
| 种植切配 | 同上 | 同上（另有营养液槽） |
| **穿串** | `MekckConfig.getNonMultithreadedBase` | **一个周期做多个串** |

> 穿串这一栏不同不是笔误：它逐字对齐旧实现第 1196-1206 行。
> 旧界面 tooltip 用的是另一套公式（`1 << min(卡数, 6)`，且 SINGULARITY 特判），
> **那套公式已随旧界面一起删除**，现在 tooltip 读的是 tile 侧同一个数。

### 4.11.2 调味料（仅烧烤 Grilling）

- [ ] 3 个调味料槽能放调味料、能拖出来。
- [ ] 槽下方 3 枚「开/关」按钮能点，状态随方块更新包同步到客户端
      （走 `saveAdditional` → `getUpdateTag`，不是同步整数）。
- [ ] 放调味料 + 正确材料 → 产物**带调味 NBT**。
- [ ] 调味料耐久按**产出个数**扣；耐久归零时该槽**整个清空**
      （不是写空壳——留着会被「剩余次数」算法误判成满耐久）。
- [ ] 容量判定用**已调味**的预览栈：调味料塞满时，预演说装得下、真落槽也必须装得下。

### 4.11.3 穿串工厂特有（形态与其它四族都不同）

- [ ] **无订单时材料摆满也不动**。这是设计不是 bug（`findRecipe` 在
      `orderRecipeId == null` 时直接返回空，旧实现第 640-641 行同款）。
- [ ] 下单后机器开工，**批量**出串（不是一个一个）。
- [ ] 配料**位置无关**：主料放第 2 格、辅料放第 1 格，一样能开工。
- [ ] 签子（`tool`）消耗数为 0 时**不消耗**、只要求存在。
      `TestSkeweringToolBatchArithmetic` 钉的就是这条的除零回归。
- [ ] 81 格存储：材料放存储里也能开工；存储不参与侧配（见下条）。
- [ ] 返还槽：产物完成后，签子回到返还槽。

> **两处已知的、刻意保留的怪行为**（不是回归，是本轮没改的行为变更）：
> 1. 返还槽复制的是**输入槽 0 的整叠**，不是「本次消耗掉的签子」。
>    签子放在别的槽里时，返还的是槽 0 里另一种物品——而匹配是位置无关的，
>    所以这条路径可达。改成正确语义是行为变更，没混进迁移里。
> 2. 配方里的 `processingTime` 被完全忽略（数据里 Beef 写 100，机器只按
>    `200 / 速度倍率`）。逐字对齐旧实现。

### 4.11.4 已知会在迁移那一刻消失的能力（**预期变化，不是回归**）

| 消失的 | 被谁取代 |
|---|---|
| 潜行 + 手持升级直接装槽 | Mek 升级 tab |
| 升级窗口的「卸载升级」按钮 | Mek `TileComponentUpgrade` 自带 |
| 自动补料开关 | Mek `TileComponentEjector` + 弹出配置 |
| 手算槽位下标 | `IInventorySlot` 对象引用，下标本身消失 |
| ME 拉料按钮（`NetworkPullButton`） | `IMekCkPorted` 端口声明 |
| 烧烤的 45 格 / 穿串的 81 格存储的**独立侧配模式** | 无。存储槽不进 `setupItemIOConfig` |

> 最后一条要特别注意：穿串的存储槽**插得进、抽不出**（AE2 那侧靠
> `mePatternItemInputs` 声明它可被投递，但 Mek 侧配只认登记过的三组）。
> 这是「存储缓冲型家族的侧配」这条线在阶段 4 的待办。

### 4.11.5 GUI 布局

- [ ] 五个界面里，侧栏 tab（侧配 / 传输配置 / 升级 / 红石 / 安全）**互不重叠**。
      旧自研 tab 与 Mek 的 tab 分属两套坐标系，运行时靠 `avoidEnergyTabY`
      互相避让——换体系就是为了根除这个。
- [ ] 高档位工厂的槽位**画在面板内**（面板高度随并行数增长）。
- [ ] 穿串界面：81 格存储挂在面板左右外侧。**GUI 缩放 ≥ 4 时可能被挤出可视区**——
      旧版就有这个问题，沿用旧布局没重新设计。

### 4.11.6 烹饪工厂特有（阶段 3 Task 7）

- [ ] **三个流体罐**都能灌水/奶，液位条随液量变化，**存读档后液体还在**。
      （罐的存档是本机自管的 `FluidTanks` 键，Mek 的 `TileEntityMekanism`
      **不写**罐——实测其 `saveAdditional` 字符串常量池只有 CustomName / Items /
      activeState / redstone / controlType / updateDelay。）
- [ ] 管道能从任一面灌入**也能抽出**（旧实现流体能力不判 side）。
- [ ] 一张同时要水与奶的配方能开工（三个罐就是为了这个）。
- [ ] 侧配界面能切到「流体」「气体」页，但**点任何面都无效**——
      这是既有行为不是回归（旧菜单不覆写 `getFluidSideMode` → 恒 NONE）。
- [ ] **无订单时材料摆满也不动**（旧实现第 729-730 行同款）。
- [ ] 返还槽：喝掉的水瓶/奶瓶产出**玻璃瓶**，FD 配方消耗的**碗被扣掉不返还**
      （这是已经发生过的行为变更，注释写明「matching the SmartCookingPot behaviour」）。
- [ ] 返还槽满时**空瓶掉在机器头顶**而不是凭空消失（显式 fail-safe）。
- [ ] 多个 Ingredient 都能被同一栈满足的配方（两个 tag 都匹配任意木板之类）
      **能正常开工**——这条对应旧实现「贪心首匹配 vs 回溯校验不一致 →
      材料被错误消耗、进度条空转、订单永不完成」那个 AE2 时代的实测故障。

> **两处本轮修掉的缺陷**（不是回归，是修复；请确认修复生效）：
> 1. **流体批量的「跨罐求和 vs 单罐足量」**。旧实现用 `totalOf`（跨罐求和）算批量、
>    实际 `drainOf` 要求单罐足量。水拆成两罐（600+600）、每份要 1000 时旧实现会
>    算成 1 份 → 扣料 → 出货 → **水一滴没扣**（凭空造物品）。
>    现在改成「某个罐能单独供几份」，与扣减同口径。
> 2. **`tanks == null` 曾返回「不限制批量」**（本轮新写的代码里出的，被
>    `TestCookingFluidBatchArithmetic` 抓到）。没有罐 = 一种流体都没有，
>    必须判 0 份。这条已由单测钉住。
>
> **刻意保留的旧行为**：罐的校验器接受**任意**流体，「奶」= 任意非水流体，
> 所以**灌岩浆能顶替奶需求**并被静默抽走。旧实现同款，没收紧。

### 4.11.7 旧存档

- [ ] 放置**迁移前版本**造的五族机器，存盘 → 换新 jar → 读档：机器在、物品在、
      订单还在。
- [ ] **穿串工厂与烹饪工厂是例外**：它们的旧存档**不迁移物品**
      （`migratesLegacyNbt()` 返回 false）。原因是旧槽位排布（穿串 3/2/81、烹饪
      6/144/9/3）与迁移器假设的并行方阵排布不同，硬套会把内容放错格子或
      **静默丢弃 142 格存储**。读档后槽位是空的——这比放错格子安全。
- [ ] 但**烹饪的流体罐仍然迁移**：`FluidTanks` 键名与旧实现逐字相同，
      换 jar 后配方槽是空的、罐里的水还在。

---

## 5. 配方条件门控

- [ ] **装了 Avaritia**：`mekck:cuf_01`…`cuf_49` 九十九食物合成表能做出随机化卡
- [ ] **未装 Avaritia**：启动日志**无该配方的报错**，游戏正常。
      配方走顶层 `conditions` + `forge:mod_loaded`，由 Forge 在任何序列化器之前判定

- [ ] 启动日志里**没有**配方相关的 `JsonSyntaxException`。若有，多半是某条配方 JSON
      被改坏（本项目已有 462/561 条配方带条件，检查时留意近期改动）

---

## 6. 本阶段**无法**在此验证的（必须诚实记为未验证）

| 项 | 为什么 | 何时能验 |
|---|---|---|
| **三方共存**：MekCK + Mek Extras + Mek Energistics 同时注入 `Upgrade` | 另两个 mod 不在本机 | 真实整合包 |
| **ordinal 分配顺序**由 mixin config 加载序决定、不保证 | 同上 | 真实整合包 |
| **任何运行期 Mixin 行为** | 本机起不了游戏 | 上面第 1 节通过后才有意义 |
| 贴图为纯色占位 | 计划显式排除美术工作 | 后续美术任务 |

三方共存是这个设计最核心的假设，也是风险最高的一环。`TestUpgradeIndexWraparoundArithmetic`
把「为什么不能用 Mek 的序列化」钉成了可执行断言，但**共存本身只有静态证据**。

---

## 7. 出了问题怎么查

```powershell
"C:\Program Files\PowerShell\7\pwsh.exe" -NoProfile -c '
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$mk = "C:\Users\Administrator\.gradle\caches\forge_gradle\deobf_dependencies\curse\maven\mekanism-268560\6018299_mapped_official_1.20.1\mekanism-268560-6018299_mapped_official_1.20.1.jar"
javap -p -s -cp $mk mekanism.api.text.APILang
javap -p -s -cp $mk mekanism.api.Upgrade
javap -p -c  -cp $mk mekanism.common.tile.component.TileComponentUpgrade
javap -p -c  -cp $mk mekanism.common.util.UpgradeUtils'
```

⚠️ **`javap -parameters` 不是有效选项**，`javap` 从不打印参数名。判断 `@Invoker` 参数个数
**只看 `-s` 打出的 `descriptor:` 行**——枚举的 `name`/`ordinal` 是合成构造参数，在描述符里。

⚠️ 本机 shell 的 `cmp` 与 `diff` **不存在**，`find`/`sort` 被 Windows 同名程序抢占。
用 PowerShell 7 的 `Get-FileHash` 或 Python 逐字节比对，别相信「差异数为 0」。

---

## 8. 已知遗留（不是 bug，别误报）

| 项 | 说明 |
|---|---|
| `client.MekCkUpgradeType` 仍指向 `mekanism_extras:upgrade_stack` / `upgrade_creative` | 旧升级系统的客户端枚举，阶段 2 随旧系统一并退役 |
| `MekCkUpgradeTracker` / `UpgradeHelper` / `IUpgradeMenu` / `GuiUpgradeWindow` 仍在 | 同上。引用面 42/36/24 个文件，属阶段 2/3 的量 |
| `data/mekck/tags/items/planting_factories.json` 里的 4 条 `mekanism_extras:*` | tag 条目，不支持 conditions，非必需 tag 静默丢弃不报错。属并行同学范围 |
| 两张升级卡的贴图是纯色 | 计划显式排除美术工作 |

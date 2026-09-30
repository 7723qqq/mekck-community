# 第二轮全量审查 —— STATUS.md 归档（2026-09-30）

> **这是历史归档。** 本文件是 2026-09-30 从 `docs/STATUS.md` 拆出的第二轮内容，
> 正文**原样保留、未改写**（仅三个章节标题重新编号以适配独立文件）。
> **现行状态见 [`../STATUS.md`](../STATUS.md)。**
>
> **为什么拆**：`STATUS.md` 曾按轮次堆叠三轮历史，§一~§七 通篇写「本轮」但实为第二轮，
> 与第三轮内容混在一起，读者无法判断哪条是现行结论。
>
> **本轮的分域明细**见 [`2026-09-30-full-code-review.md`](2026-09-30-full-code-review.md)
> 与 [`2026-09-30-details/`](2026-09-30-details/)。
>
> **未拆出的部分**：原 `STATUS.md` 的 §二（需你裁决）、§三（待办）、§四（配方层现状）、
> §五（环境注意事项）、§六（方法论教训）仍留在 `STATUS.md`——它们是前瞻性状态而非轮次叙事。
>
> **本文件内的已知失效引用**（保留原貌，不就地改写）：
> - 「分域明细在 `.review/*.md`」——该目录已不存在，现为 `docs/audit/2026-09-30-details/`
> - 「见 §5」指本文件内的第 5 小节（档位接入），编号仍有效

---

## 一、第二轮全量审查（2026-09-30）

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

## 二、这一轮做了什么

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

## 三、本轮提交归属（日志里两个 AI 的提交是交错的）

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

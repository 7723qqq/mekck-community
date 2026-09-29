# mekck 当前状态汇总

- 最后更新：2026-09-29
- 基线提交：`b6fbcc0`（本轮之前）
- HEAD：`59c69f1`
- 验收口径：`./gradlew clean build --offline` → **115 测试 / 0 失败 / 0 错误 / 0 跳过**

本文是**单一入口**。细节看各专项文档（见文末索引）。

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
| `59c69f1` | — | 实现 `MekCkFactoryTier implements ITier`（兑现 8.3 决策） |

### 5. 档位接入（`59c69f1`）

`ITier` 只有一个方法 `getBaseTier()`。映射：**前 4 档一一对应，其余 8 档全归
`BaseTier.ULTIMATE`**；`CREATIVE` 不被任何档位占用。

**⚠ 硬边界（别夸大）**：接了 `ITier` **不会**让 `Attribute.getBaseTier(block)` 生效——
`AttributeTier` 对 addon 不可设置（`withComputerSupport` 在构建期就把 ITier 消费成字符串）。
实际收益是「12 档成为任何接受 `ITier` 的 Mek API 的合法输入」。

存档无需兼容层：本枚举实现 `StringRepresentable`，走名字而非 `ordinal()`。

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

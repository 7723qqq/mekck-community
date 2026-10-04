# docs/ —— 文档索引

**入口是 [`STATUS.md`](STATUS.md)**：当前状态、未修项、环境注意事项都在那里。
本文件只是全部文档的清单，说明每份是什么、什么状态。

命名约定：`YYYY-MM-DD-<主题>.md`，日期是**该文档所描述的那一轮工作**的日期。

状态口径：

| 标记 | 含义 |
|---|---|
| **现行** | 描述当前有效的设计或状态，改动前应读 |
| **历史** | 某一轮的记录，保留作证据与追溯；结论可能已被后续轮次取代 |
| **已关闭** | 主题已结束，仅作证据表保留 |

---

## 口径

| 文档 | 内容 | 状态 |
|---|---|---|
| [`2026-09-30-功能实现口径.md`](2026-09-30-功能实现口径.md) | **每个功能该走哪个上游机制**（Mek / AE2 / 原版）。新增机器、改界面前必读 | 现行 |

---

## 模块架构 `architecture/`

模块化重构（2026-10-05 起）的目标架构与现状契约。入口是 [`architecture/README.md`](architecture/README.md)。

| 文档 | 内容 | 状态 |
|---|---|---|
| [`architecture/00-module-map.md`](architecture/00-module-map.md) | 模块地图、依赖方向表、跨包 import 实测、循环依赖与破环方案 | 现行 |
| [`architecture/01-util.md`](architecture/01-util.md) | `util/` 39 类去留分类 + 死代码核验记录 | 现行 |
| [`architecture/02-machine-core.md`](architecture/02-machine-core.md) | Mek 原生机器内核职责、扩展点、构造期陷阱 | 现行 |
| [`architecture/03-ae2.md`](architecture/03-ae2.md) | AE2 两条集成路径边界、反射成员名清单、迁移时删分支规则 | 现行 |
| [`architecture/04-client-menu.md`](architecture/04-client-menu.md) | client/menu 分层、自研件残留清单与删除前置条件 | 现行 |
| [`architecture/05-god-class-decomposition.md`](architecture/05-god-class-decomposition.md) | 巨型类拆分设计（伴生类表 + 成员区间 + 风险） | 现行 |
| [`architecture/legacy/`](architecture/legacy/README.md) | 11 台 legacy 机器迁移契约（槽序/NBT/掉落/迁移器）逐台留档 | 现行 |

---

## 审查报告 `audit/`

| 文档 | 内容 | 状态 |
|---|---|---|
| [`2026-09-29-full-code-review.md`](audit/2026-09-29-full-code-review.md) | 第一轮全量审查（283 文件 / 85,514 行，按风险维度分 4 域）。5 个 Critical 已修 | 历史 |
| [`2026-09-29-conditional-recipe-report.md`](audit/2026-09-29-conditional-recipe-report.md) | 配方条件化改造报告。462 条 `Parsing error` 的归因与处置 | 历史 |
| [`2026-09-30-full-code-review.md`](audit/2026-09-30-full-code-review.md) | 第二轮全量审查（332 文件，按包边界分 4 域）。6 个 Critical 已修 | 历史 |
| [`2026-09-30-round2-archive.md`](audit/2026-09-30-round2-archive.md) | 第二轮从 `STATUS.md` 拆出的归档（原 §〇 + §一 + §七） | 历史 |
| [`2026-09-30-details/machine-core.md`](audit/2026-09-30-details/machine-core.md) | 第二轮分域深审：`machine/` 新内核 + `config/` | 历史 |
| [`2026-09-30-details/legacy-conservation.md`](audit/2026-09-30-details/legacy-conservation.md) | 第二轮分域深审：`blockentity/` + `block/` + `util/` 物品·流体守恒 | 历史 |
| [`2026-09-30-details/ae2-network-menu.md`](audit/2026-09-30-details/ae2-network-menu.md) | 第二轮分域深审：`ae2/` + `network/` + `menu/` + `integration/` | 历史 |
| [`2026-09-30-details/client-recipe-mixin.md`](audit/2026-09-30-details/client-recipe-mixin.md) | 第二轮分域深审：`client/` + `recipe/` + `upgrade/` + `mixin/` 与资源 | 历史 |

> ⚠️ **`legacy-conservation.md` 被源码引用**：`UniversalCuttingMachine.java` 的
> `@see docs/audit/2026-09-30-details/legacy-conservation.md`。
> **不要移动或重命名这个文件**，否则该 `@see` 断链。
>
> ⚠️ **`client-recipe-mixin.md` 里 C1 的修法建议已作废**（文件开头的抬头块说明了最终事实）。
> 该文件是审查当时的证据日志，保留原貌不改写。

---

## 规格 `superpowers/specs/`

| 文档 | 内容 | 状态 |
|---|---|---|
| [`2026-09-29-mek-native-machine-framework-design.md`](superpowers/specs/2026-09-29-mek-native-machine-framework-design.md) | 阶段 1：升级体系完整框架设计（Mek 原生化）。**已实施**——文档自述「待实施」是过时的 | 现行 |
| [`2026-09-29-mek-machine-architecture.md`](superpowers/specs/2026-09-29-mek-machine-architecture.md) | **架构 v4**，阶段 2 规格（995 行） | 现行 |
| [`2026-09-29-objmesh-loader-design.md`](superpowers/specs/2026-09-29-objmesh-loader-design.md) | OBJ 网格加载器移植设计（自述：已实施） | 历史 |

---

## 实施计划 `superpowers/plans/`

| 文档 | 内容 | 状态 |
|---|---|---|
| [`2026-09-29-mekck-phase1-upgrade-system.md`](superpowers/plans/2026-09-29-mekck-phase1-upgrade-system.md) | 阶段 1：升级体系实施计划（1433 行） | 历史 |
| [`2026-09-30-mekck-phase2-cutting-factory.md`](superpowers/plans/2026-09-30-mekck-phase2-cutting-factory.md) | 阶段 2：切菜工厂 Mek 原生化实施计划（793 行） | 历史 |

---

## 交接 `superpowers/handoff/`

| 文档 | 内容 | 状态 |
|---|---|---|
| [`2026-09-29-phase1-runtime-verification.md`](superpowers/handoff/2026-09-29-phase1-runtime-verification.md) | 阶段 1 实机验证清单，面向**能正常启动 Minecraft 的环境** | 现行 |
| [`2026-09-29-recipe-availability-handoff.md`](superpowers/handoff/2026-09-29-recipe-availability-handoff.md) | 配方依赖可达性判据交接。主题已关闭，保留作 type id 证据表 | 已关闭 |

---

## 相关

- `tools/README.md` —— 一次性脚本与审计工具的说明
- `../README.md` / `../README.zh_CN.md` —— 面向玩家的模组说明

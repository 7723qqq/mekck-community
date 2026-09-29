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

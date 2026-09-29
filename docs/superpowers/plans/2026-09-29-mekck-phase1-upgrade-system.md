# MekCK 阶段 1：升级体系 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 MekCK 注入自有的升级类型（存储卡 / 随机化卡）与译名，并把 Mekanism 升级组件的持久化从 ordinal 索引换成名字键，使注入型 mod 的增删不会损坏玩家存档。

**Architecture:** 保留 Mekanism 的 `TileComponentUpgrade` 全部运行时行为（20 tick 安装读条、槽位合法性、GUI 升级 tab），只替换它的 **read/write 编解码**。编解码核心 `MekCkUpgradeCodec` 对类型键泛型化（因为 `Upgrade` 在裸 JVM 里加载不了），`Upgrade` 的绑定放在 `MekCkUpgradeTypes`，只在游戏内加载。

**Tech Stack:** MC 1.20.1 / Forge 47.4.16 / Java 17 / Mekanism 10.4.6.20 / JUnit 4 / SpongePowered Mixin 0.8

**Spec:** `docs/superpowers/specs/2026-09-29-mek-native-machine-framework-design.md`（§2.3、§5.1–5.7、§8、§9 为本计划的依据）

## Global Constraints

- **Mekanism 10.4.6.20，MC 1.20.1，Forge 47.4.16，Java 17。** 不升级 MC 版本。
- **核实用 jar（只读，改代码时用它复核 API）**：
  `C:\Users\Administrator\.gradle\caches\forge_gradle\deobf_dependencies\curse\maven\mekanism-268560\6018299_mapped_official_1.20.1\mekanism-268560-6018299_mapped_official_1.20.1.jar`
- **`mekanism.api.Upgrade` 无法在无游戏环境的普通 JVM 里加载。** 静态初始化链是
  `Upgrade → EnumColor → DyeColor → ItemTags → Registries`，必然抛 `ExceptionInInitializerError`；
  `net.minecraft.server.Bootstrap.bootStrap()` 同样失败（需要 `Util.fetchChoiceType` 与完整注册表）。
  任何触及 `Upgrade` 的代码都**不得**写进普通 JUnit 测试，只能进 GameTest。
- **Mixin 一律 `remap = false`**（目标类都不是 Minecraft 映射的类）。配置 `injectors.defaultRequire: 1`，
  注入点失配时**启动即崩**而非静默失效——这是有意的，不要改成 `defaultRequire: 0`。
- **⚠️ 枚举注入依赖一条 JDK 17 实现细节**：JDK 17 的 `java.lang.Enum(String,int)` 构造体
  只剩两行字段赋值，**共享常量目录（`values[ordinal] = this`）已搬到 `Class` 里**。
  因此「先经 invoker 构造新常量、再把加长后的数组写回 `$VALUES`」这个顺序是安全的。
  在 JDK 8/11 上同样的顺序会因数组越界抛 `ArrayIndexOutOfBoundsException`。
  **不要「优化」成先写回数组再构造。**（Task 3 实施者已实测：3 常量枚举上以 ordinal=3 构造成功）
- **不要用 `Enum.valueOf(name)` 找注入的常量。** JDK 17 的 `Class.enumConstantDirectory()`
  与 `getEnumConstants()` 都是一次性快照缓存，改写 `$VALUES` 不会让它们重建。
  一律用 `MekCkAPILang` / `MekCkUpgradeRefs` 提供的访问器。
- **🔴 注入的升级类型会踩 `UpgradeUtils.getStack` 的雷**（Task 5 复审的 C1）。
  该方法用 `ordinal()` 索引 `UpgradeUtils$1` 的合成 switch 映射表，而该表**只填了 7 个原生常量**：
  ```
  getStack(Upgrade,int):
    0: getstatic     UpgradeUtils$1.$SwitchMap$mekanism$api$Upgrade:[I
    4: invokevirtual Upgrade.ordinal:()I
    7: iaload
    8: tableswitch { 1 to 7 }      ← 注入常量的槽位恒为 0 → 落 default
  ```
  → 抛 `IncompatibleClassChangeError`。**不止 `byItem` 一处踩**：
  `TileComponentUpgrade.removeUpgrade(Upgrade,boolean)` 偏移 28 也调它，
  而 `removeUpgrade` 的唯一调用者是 **`PacketGuiInteract$GuiInteraction`（服务端收包，玩家可达）**。
  即：**玩家装上自己的升级卡后，在 Mek 升级界面点「移除」会崩服线程。**
  → 由 Task 6 的第 4 个 Mixin `MixinUpgradeUtilsGetStack` 兜住（见 Task 6）。
- **MekCK 的 Mixin 足迹上限 4 个新类**：`MixinAPILang`（`APILang` 也是封闭枚举，必须单独注入一次）、
  `MixinUpgrade`、`MixinUpgradeUtilsGetStack`（见上一条）、`MixinTileComponentUpgradePersistence`。
  spec §5.1 初稿只算了 `Upgrade` 一个，漏了 `APILang`；`getStack` 那条是 Task 5 复审才发现的。
  **不要再加第 5 个。**
- **不要用 `Upgrade.valueOf(name)` 反查**：注入常量的枚举名是大写（`STACK`），
  而 `getRawName()` 是小写（`stack`），两者不一致。用 `getRawName()`。
- **不要用 `Upgrade.byIndexStatic()` / `Upgrade.buildMap()` / `Upgrade.saveMap()` 做持久化**：
  它们按 `ordinal()` 索引、读取时 `MathUtils.getByIndexMod` 取模回绕，越界不抛异常不打日志。
- **本阶段不动** `MekCkUpgradeTracker`、`MekCkUpgradeType`、`GuiUpgradeWindow`、
  `IUpgradeMenu`、`UpgradeHelper` 及 11 个方块实体、24 个 GUI 屏幕。
  旧升级系统与新系统在本阶段**并存**，退役随阶段 2（烹饪工厂样板）进行。
  原因：这四者的引用面是 42 / 36 / 24 / 21 个文件，属于阶段 2/3 的量。
- **贴图自绘，不复制 `mekanism_extras` 的文件**（MIT 也要求署名，且它的美术风格不是我们的）。
- 提交前先 `git status` 确认暂存范围。**不要用 `git add -A`**（本仓库曾因此误提交 12 个无关文件）。
- 中文注释，与仓库现有风格一致；解释「为什么」而非「做了什么」。

---

## File Structure

**新建 `cn.ism.mekck.upgrade` 包**——升级体系是独立子系统，不塞进已有的 `util/`。

| 文件 | 职责 | 依赖 Mek 类？ |
|---|---|---|
| `upgrade/MekCkUpgradeCodec.java` | 名字键编解码核心，对类型键泛型 | ❌ 可单测 |
| `upgrade/MekCkAPILang.java` | 注入的 4 个译名常量的持有者 | 仅 `APILang` |
| `upgrade/MekCkUpgradeRefs.java` | 注入的 2 个升级常量的持有者 | 仅 `Upgrade` |
| `upgrade/MekCkUpgradeTypes.java` | `Upgrade` 适配层 + 聚合入口 + 上限裁定 | `Upgrade` |
| `upgrade/MekCkStorageUpgradeItem.java` | 存储卡物品，实现 `IUpgradeItem` | `Upgrade` |
| `upgrade/MekCkRandomizeUpgradeItem.java` | 随机化卡物品，实现 `IUpgradeItem` | `Upgrade` |
| `mixin/MixinAPILang.java` | 往 `APILang` 注入 4 个常量 | — |
| `mixin/MixinUpgrade.java` | 往 `Upgrade` 注入 2 个常量 | — |
| `mixin/MixinTileComponentUpgradePersistence.java` | 重定向 `read`/`write` 的编解码调用 + `@Unique` 存未知条目 | — |
| `mixin/IMekCkUnknownUpgradeHolder.java` | 给上一个 Mixin 的 `@Unique` 字段提供访问器接口 | — |

**与 spec 的一处命名差异**：spec §3.2 把这一层叫 `MekCkUpgrades`，本计划叫
`MekCkUpgradeTypes`。原因是它要同时承担「泛型 codec 的 `Upgrade` 绑定」与「聚合入口」
两个职责，`Types` 比 `Upgrades` 更贴切。方法职责与 spec §3.2 一一对应：
`all()` ≡ `discoverAll()`，`byItem()` ≡ `byItem()`，
`capOf(type, tier)` ≡ `capOf()`，`isSupportedBy()` ≡ `isSupportedBy()`。
spec 的命名可在后续实施时一并对齐，不影响本计划。

**测试**（`src/test/java/cn/ism/mekck/upgrade/`）：

| 文件 | 覆盖 |
|---|---|
| `TestUpgradeCodecRoundTrip.java` | 编解码契约，10 个用例 ✅ **已交付** |
| `TestUpgradeIndexWraparoundArithmetic.java` | 索引回绕算术，4 个用例 ✅ **已交付** |

**资源**：`assets/mekck/lang/{en_us,zh_cn}.json`、`assets/mekck/models/item/upgrade_{storage,randomize}.json`、
`assets/mekck/textures/item/upgrade_{storage,randomize}.png`、
`data/mekck/recipes/upgrade/storage.json`（新建；随机化卡**复用并修改**已有的
`data/mekck/recipes/creative_upgrade_from_49_foods.json`，不新建第二个配方文件）

---

## 已完成：Task 1 + Task 2（`75523b3`）

这两个任务是写计划时的探针转正，代码已落地、14 个单测全绿。**实施时从 Task 3 开始。**

- **Task 1** `MekCkUpgradeCodec` + `TestUpgradeCodecRoundTrip`（10 用例）
- **Task 2** `TestUpgradeIndexWraparoundArithmetic`（4 用例）

---

### Task 3: 注入 4 个 APILang 译名常量

`Upgrade` 的私有构造签名（`javap` 实测）是
`Upgrade(String name, APILang langKey, APILang descLangKey, int maxStack, EnumColor color)`，
所以**必须先有 `APILang` 实例**——这是 `APILang` 注入排在 `Upgrade` 注入之前的原因。

`APILang` 有两个私有构造。**用单参的那个**：

```java
private APILang(String key) { super(); this.key = key; }              // key 原样使用
private APILang(String type, String path) {                          // 命名空间写死 "mekanism"
    this(type, Util.makeDescriptionId(type, new ResourceLocation("mekanism", path)));
}
```

双参版本把命名空间硬编码成 `mekanism`，附属模组用它会让译名落进别人的命名空间。
（Mek Extras 正是用了双参版本——那是它的缺陷，不要学。）

**Files:**
- Create: `src/main/java/cn/ism/mekck/upgrade/MekCkAPILang.java`
- Create: `src/main/java/cn/ism/mekck/mixin/MixinAPILang.java`

**Interfaces:**
- Consumes: 无
- Produces: 4 个**静态方法**（不是字段），类型均为 `mekanism.api.text.APILang`：
  - `MekCkAPILang.upgradeStorage()`
  - `MekCkAPILang.upgradeStorageDescription()`
  - `MekCkAPILang.upgradeRandomize()`
  - `MekCkAPILang.upgradeRandomizeDescription()`

  任务 4 消费这四个方法。**注意**：底层同名的静态字段（`upgradeStorage` 等）也存在且非 final，
  但**消费方一律调方法**——方法带 null 兜底与明确异常信息，字段没有。
  （审查发现：本节初稿误写成字段名 `UPGRADE_STORAGE` 等，会让 Task 4 编译失败。已修正。）

  **不要用 `APILang.valueOf("UPGRADE_STORAGE")` 找这些常量。** JDK 17 的
  `Class.enumConstantDirectory()` 与 `getEnumConstants()` 都是**一次性快照缓存**，
  改写 `$VALUES` 不会让它们重建——一旦被别处先填充过，注入的常量对 `valueOf` 永久不可见，
  抛 `IllegalArgumentException`。一律用上面那四个方法。

- [ ] **Step 1: 建常量持有者**

`src/main/java/cn/ism/mekck/upgrade/MekCkAPILang.java`

```java
package cn.ism.mekck.upgrade;

import mekanism.api.text.APILang;

/**
 * MekCK 注入 {@link APILang} 的 4 个译名常量，由 {@code MixinAPILang} 在
 * {@code APILang.<clinit>} 的 TAIL 处赋值。
 *
 * <p><b>字段不能是 final 也不能在静态初始化器里赋值</b>：赋值发生在
 * {@code APILang} 的类初始化过程中，若 {@code MekCkAPILang} 的 {@code <clinit>}
 * 先跑并读 {@code APILang}，就会在注入发生前看到 null。
 * 因此全部通过 {@link #upgradeStorage()} 这类方法在<b>调用时</b>读取，
 * 而调用方必然已经触达过 {@code APILang}（要拿它就得先加载它）。
 *
 * <p>译名 key 用 {@code upgrade.mekck.*} 前缀而非 {@code upgrade.mekanism.*}——
 * 后者需要覆盖 Mekanism 自己的 lang 文件，附属模组不该这么做。
 */
public final class MekCkAPILang {

    /** 存储卡标题，存档/界面用。 */
    public static APILang upgradeStorage;
    /** 存储卡说明，升级列表的 tooltip 用。 */
    public static APILang upgradeStorageDescription;
    /** 随机化卡标题。 */
    public static APILang upgradeRandomize;
    /** 随机化卡说明。 */
    public static APILang upgradeRandomizeDescription;

    private MekCkAPILang() {
    }

    /**
     * 强制 {@code APILang} 完成类初始化后再取字段。
     *
     * @throws IllegalStateException Mixin 未生效时（正常情况下应为启动期崩溃，此处是兜底）
     */
    public static APILang upgradeStorage() {
        return require(upgradeStorage, "UPGRADE_STORAGE");
    }

    public static APILang upgradeStorageDescription() {
        return require(upgradeStorageDescription, "UPGRADE_STORAGE_DESCRIPTION");
    }

    public static APILang upgradeRandomize() {
        return require(upgradeRandomize, "UPGRADE_RANDOMIZE");
    }

    public static APILang upgradeRandomizeDescription() {
        return require(upgradeRandomizeDescription, "UPGRADE_RANDOMIZE_DESCRIPTION");
    }

    private static APILang require(APILang value, String name) {
        if (value == null) {
            throw new IllegalStateException(
                    "APILang 常量 " + name + " 未注入：MixinAPILang 未生效。"
                            + "检查 mekck.mixins.json 是否挂载了它。");
        }
        return value;
    }
}
```

- [ ] **Step 2: 建注入 Mixin**

`src/main/java/cn/ism/mekck/mixin/MixinAPILang.java`

```java
package cn.ism.mekck.mixin;

import cn.ism.mekck.upgrade.MekCkAPILang;
import mekanism.api.text.APILang;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * 往 {@link APILang} 注入 MekCK 自己的 4 个译名常量。
 *
 * <h3>为什么必须注入而不是自己写个类</h3>
 * {@code Upgrade} 的私有构造签名是
 * {@code (String, APILang, APILang, int, EnumColor)}——参数类型写死 {@code APILang}，
 * 而 {@code APILang} 是 {@code final enum}，无法继承也无法新建。
 * 要让 {@code Upgrade} 拿到 MekCK 的译名，只能往它的枚举数组里追加常量。
 *
 * <h3>为什么用单参构造</h3>
 * {@code APILang(String type, String path)} 把命名空间硬编码成 {@code "mekanism"}，
 * 附属模组用它会让译名落进 Mekanism 的命名空间（需要覆盖别人的 lang 文件）。
 * 单参构造 {@code APILang(String key)} 原样使用传入的 key，故用
 * {@code upgrade.mekck.*}。
 *
 * <h3>与其它注入者的共存</h3>
 * {@code @Shadow} 读的是目标类的活字段，所以本 Mixin 看到的是
 * 「Mek 原生 + 其它已注入 mod 追加的」完整数组，追加后自然叠加。
 * 唯一要求是每个注入者都在 {@code <clinit>} 末尾把 {@code $VALUES} 重同步回去。
 */
@Mixin(value = APILang.class, remap = false)
public abstract class MixinAPILang {

    @Shadow
    @Final
    @Mutable
    private static APILang[] $VALUES;

    public MixinAPILang() {
    }

    /**
     * 调 {@code APILang} 的单参私有构造。
     *
     * <p>Mixin 的 {@code @Invoker} 会在声明的两个合成参数
     * （{@code internalName} / {@code internalId}）前补上，源码层面对应
     * {@code private APILang(String key)}。
     */
    @Invoker("<init>")
    public static APILang mekck$langInitInvoker(String internalName, int internalId, String key) {
        throw new AssertionError("mixin 未应用");
    }

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void mekck$injectLangEntries(CallbackInfo ci) {
        MekCkAPILang.upgradeStorage = mekck$add("UPGRADE_STORAGE", "upgrade.mekck.storage");
        MekCkAPILang.upgradeStorageDescription =
                mekck$add("UPGRADE_STORAGE_DESCRIPTION", "upgrade.mekck.storage.description");
        MekCkAPILang.upgradeRandomize = mekck$add("UPGRADE_RANDOMIZE", "upgrade.mekck.randomize");
        MekCkAPILang.upgradeRandomizeDescription =
                mekck$add("UPGRADE_RANDOMIZE_DESCRIPTION", "upgrade.mekck.randomize.description");
    }

    @Unique
    private static APILang mekck$add(String internalName, String key) {
        ArrayList<APILang> variants = new ArrayList<>(Arrays.asList($VALUES));
        APILang entry = mekck$langInitInvoker(internalName, variants.size(), key);
        variants.add(entry);
        $VALUES = variants.toArray(new APILang[0]);
        return entry;
    }
}
```

- [ ] **Step 3: 挂进 mixin 配置**

修改 `src/main/resources/mekck.mixins.json` 的 `mixins` 数组：

```json
  "mixins": [
    "MixinAPILang",
    "MixinCuttingBoardBlockEntity",
    "MixinExtremeSmithingMenu"
  ],
```

（`MixinAPILang` 不需要 `MekCkMixinConfigPlugin` 的条件加载——Mekanism 是必需依赖。）

- [ ] **Step 4: 编译验证**

Run: `./gradlew compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`。若报 `Invoker` 签名不匹配，对照
`javap -p mekanism.api.text.APILang` 的 `private APILang(java.lang.String);` 调整参数个数。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/cn/ism/mekck/upgrade/MekCkAPILang.java \
        src/main/java/cn/ism/mekck/mixin/MixinAPILang.java \
        src/main/resources/mekck.mixins.json
git commit -m "注入 APILang 译名常量（存储卡 / 随机化卡）"
```

---

### Task 4: 注入 2 个 Upgrade 常量

`Upgrade` 的私有构造（`javap` 实测）：
`Upgrade(String name, APILang langKey, APILang descLangKey, int maxStack, EnumColor color)`

它额外持有**两个**静态数组：`$VALUES`（枚举标准）与 `UPGRADES`（Mek 自己的缓存，
被 `byIndexStatic` 读取）。两个都要更新，否则 `byIndexStatic` 看不到新常量。

**命名决策：随机化卡的常量叫 `RANDOMIZE`，不叫 `CREATIVE`。**
Mek Extras 已注入了一个名为 `CREATIVE` 的常量；若 MekCK 也叫 `CREATIVE`，
会出现两个同名不同实例的枚举常量，而名字键持久化按 `getRawName()` 存，
**两者会被当成同一张卡**。物品 id 仍用 `upgrade_randomize`，与旧存档解耦（见 Task 7）。

**Files:**
- Create: `src/main/java/cn/ism/mekck/upgrade/MekCkUpgradeRefs.java`
- Create: `src/main/java/cn/ism/mekck/mixin/MixinUpgrade.java`
- Modify: `src/main/resources/mekck.mixins.json`

**Interfaces:**
- Consumes: `MekCkAPILang.upgradeStorage()` / `upgradeStorageDescription()` /
  `upgradeRandomize()` / `upgradeRandomizeDescription()`（Task 3）
- Produces: `MekCkUpgradeRefs.storage()` → `mekanism.api.Upgrade`（`getRawName()` == `"storage"`，
  `getMax()` == 6）；`MekCkUpgradeRefs.randomize()` → `Upgrade`（`getRawName()` == `"randomize"`，
  `getMax()` == 1）。任务 5、7 消费。

- [ ] **Step 1: 建常量持有者**

`src/main/java/cn/ism/mekck/upgrade/MekCkUpgradeRefs.java`

```java
package cn.ism.mekck.upgrade;

import mekanism.api.Upgrade;

/**
 * MekCK 注入 {@link Upgrade} 的 2 个常量，由 {@code MixinUpgrade} 在
 * {@code Upgrade.<clinit>} 的 TAIL 处赋值。字段形态与 {@link MekCkAPILang} 同理，
 * 原因见该类注释。
 *
 * <p><b>为什么常量名与物品 id 不一致</b>：随机化卡的枚举常量叫
 * {@code RANDOMIZE} 而不是 {@code CREATIVE}，因为 Mek Extras 已经注入了一个
 * 同名的 {@code CREATIVE}。两个同名不同实例的常量，在按 {@code getRawName()}
 * 存档的体系里会被当成同一张卡。物品 id 用 {@code upgrade_randomize}，
 * 与旧存档的 {@code mekanism_extras:upgrade_creative} 也自然解耦。
 */
public final class MekCkUpgradeRefs {

    /** 存储卡：提升并行线程数与缓冲容量。 */
    public static Upgrade storage;
    /** 随机化卡：随机化本局 49 种可用食物。 */
    public static Upgrade randomize;

    private MekCkUpgradeRefs() {
    }

    public static Upgrade storage() {
        return require(storage, "STORAGE");
    }

    public static Upgrade randomize() {
        return require(randomize, "RANDOMIZE");
    }

    private static Upgrade require(Upgrade value, String name) {
        if (value == null) {
            throw new IllegalStateException(
                    "Upgrade 常量 " + name + " 未注入：MixinUpgrade 未生效。"
                            + "检查 mekck.mixins.json 是否挂载了它。");
        }
        return value;
    }
}
```

- [ ] **Step 2: 建注入 Mixin**

`src/main/java/cn/ism/mekck/mixin/MixinUpgrade.java`

```java
package cn.ism.mekck.mixin;

import cn.ism.mekck.upgrade.MekCkAPILang;
import cn.ism.mekck.upgrade.MekCkUpgradeRefs;
import mekanism.api.Upgrade;
import mekanism.api.text.APILang;
import mekanism.api.text.EnumColor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * 往 {@link Upgrade} 注入 MekCK 的 2 个升级常量。
 *
 * <h3>两个数组都要更新</h3>
 * {@code Upgrade} 持有 {@code $VALUES}（枚举标准）与 {@code UPGRADES}（Mek 自己的缓存）。
 * {@code byIndexStatic} 读的是 <b>{@code UPGRADES}</b> 而非 {@code $VALUES}，
 * 只追加前者会让新常量在索引查找里「不存在」。所以末尾必须
 * {@code UPGRADES = $VALUES} 重同步。
 *
 * <h3>与 Mek Extras / Mek Energistics 的共存</h3>
 * {@code @Shadow} 读活字段，所以本 Mixin 看到的是「原生 + 其它注入者已追加」的数组，
 * 追加后自然叠加。三个注入者的执行顺序由 mixin config 加载序决定、不保证，
 * 但因为追加是读-改-写且都基于活字段，顺序不影响最终集合。
 */
@Mixin(value = Upgrade.class, remap = false)
public abstract class MixinUpgrade {

    @Shadow
    @Final
    @Mutable
    private static Upgrade[] $VALUES;

    @Shadow
    @Final
    @Mutable
    private static Upgrade[] UPGRADES;

    public MixinUpgrade() {
    }

    /**
     * 调 {@code Upgrade} 的私有构造。
     *
     * <p>{@code @Invoker} 会补上两个合成参数（{@code internalName} / {@code internalId}），
     * 源码层面对应
     * {@code private Upgrade(String, APILang, APILang, int, EnumColor)}。
     */
    @Invoker("<init>")
    public static Upgrade mekck$upgradeInitInvoker(String internalName, int internalId, String name,
                                                   APILang langKey, APILang descLangKey,
                                                   int maxStack, EnumColor color) {
        throw new AssertionError("mixin 未应用");
    }

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void mekck$injectUpgrades(CallbackInfo ci) {
        MekCkUpgradeRefs.storage = mekck$add("STORAGE", "storage",
                MekCkAPILang.upgradeStorage(), MekCkAPILang.upgradeStorageDescription(),
                6, EnumColor.BRIGHT_PINK);
        MekCkUpgradeRefs.randomize = mekck$add("RANDOMIZE", "randomize",
                MekCkAPILang.upgradeRandomize(), MekCkAPILang.upgradeRandomizeDescription(),
                1, EnumColor.PURPLE);
        // byIndexStatic 读的是 UPGRADES，必须重同步
        UPGRADES = $VALUES;
    }

    @Unique
    private static Upgrade mekck$add(String internalName, String rawName,
                                     APILang langKey, APILang descLangKey,
                                     int maxStack, EnumColor color) {
        ArrayList<Upgrade> variants = new ArrayList<>(Arrays.asList($VALUES));
        Upgrade entry = mekck$upgradeInitInvoker(internalName, variants.size(), rawName,
                langKey, descLangKey, maxStack, color);
        variants.add(entry);
        $VALUES = variants.toArray(new Upgrade[0]);
        return entry;
    }
}
```

- [ ] **Step 3: 挂进 mixin 配置**

`src/main/resources/mekck.mixins.json` 的 `mixins` 数组加入 `"MixinUpgrade"`：

```json
  "mixins": [
    "MixinAPILang",
    "MixinCuttingBoardBlockEntity",
    "MixinExtremeSmithingMenu",
    "MixinUpgrade"
  ],
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/cn/ism/mekck/upgrade/MekCkUpgradeRefs.java \
        src/main/java/cn/ism/mekck/mixin/MixinUpgrade.java \
        src/main/resources/mekck.mixins.json
git commit -m "注入 Upgrade 常量 STORAGE / RANDOMIZE"
```

---

### Task 5: `MekCkUpgradeTypes` —— Upgrade 适配层

把 `MekCkUpgradeCodec` 的泛型核心绑到 `Upgrade` 上。**因为 `Upgrade` 在裸 JVM 里加载不了，
这个类不含任何普通 JUnit 测试**，它的正确性由 Task 6 的 Mixin 与阶段 2 的 GameTest 覆盖。

**上限裁定规则**（spec §3.2）：`capOf(type) = min(MekCK 配置值, type.getMax())`。
只要不超过 `getMax()`，Mek 在 `tickServer()` 与 `addUpgrades()` 里的两处检查就自动满足，
不需要额外 Mixin 去重定向上限。

**Files:**
- Create: `src/main/java/cn/ism/mekck/upgrade/MekCkUpgradeTypes.java`

**Interfaces:**
- Consumes: `MekCkUpgradeCodec`（Task 1）、`MekCkUpgradeRefs`（Task 4）、
  `cn.ism.mekck.config.MekckConfig` 的
  `getFactoryStackUpgradeMax(CuttingMachineFactoryTier)` /
  `getFactorySpeedUpgradeMax(CuttingMachineFactoryTier)` /
  `getFactoryEnergyUpgradeMax(CuttingMachineFactoryTier)`
- Produces:
  - `static List<Upgrade> all()` → 当前已加载的**全部**升级常量（Mek 7 种 + 所有注入者的）
  - `static Upgrade resolve(String rawName)` → 按 `getRawName()` 反查，**找不到返回 `null`**
  - `static String nameOf(Upgrade type)` → `type.getRawName()`
  - `static Optional<Upgrade> byItem(ItemStack stack)` → 升级物品 → 常量
  - `static boolean isSupportedBy(Upgrade type, CuttingMachineFactoryTier tier)` → 某家族/等级是否接受该类型
  - `static int capOf(Upgrade type)` / `static int capOf(Upgrade type, CuttingMachineFactoryTier tier)`
  - `static MekCkUpgradeCodec.Decoded<Upgrade> decode(CompoundTag tag)`
  - `static CompoundTag encode(Map<Upgrade,Integer> known, List<CompoundTag> unknownRaw)`

- [ ] **Step 1: 写适配层**

`src/main/java/cn/ism/mekck/upgrade/MekCkUpgradeTypes.java`

```java
package cn.ism.mekck.upgrade;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.config.MekckConfig;
import mekanism.api.Upgrade;
import mekanism.common.item.interfaces.IUpgradeItem;
import mekanism.common.util.UpgradeUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link MekCkUpgradeCodec} 与 {@link Upgrade} 的绑定层，同时是升级体系的聚合入口。
 *
 * <h3>为什么单独一层</h3>
 * {@code Upgrade} 的静态初始化链
 * （{@code Upgrade → EnumColor → DyeColor → ItemTags → Registries}）在无游戏环境的
 * 普通 JVM 里必然抛 {@code ExceptionInInitializerError}，
 * {@code Bootstrap.bootStrap()} 同样失败（实测：需要 {@code Util.fetchChoiceType}
 * 与完整注册表）。因此泛型化的 codec 核心可以在普通 JUnit 里完整覆盖，
 * 而这一层只能在游戏内加载。
 *
 * <h3>all() 覆盖全部注入者</h3>
 * {@link #all()} 直接返回 {@code Upgrade.values()}。注入是在
 * {@code Upgrade.<clinit>} 的 TAIL 做的，而任何触达 {@code Upgrade} 的代码
 * 都会先跑完它的 {@code <clinit>}，所以拿到的必然是「Mek 原生 + 所有已加载注入者
 * 追加的」完整集合——不依赖任何注入顺序假设。
 *
 * <h3>上限裁定</h3>
 * Mek 在两处硬卡 {@code Upgrade.getMax()}：{@code TileComponentUpgrade#tickServer}
 * 决定读条是否推进、{@code #addUpgrades} 决定实际装几个。
 * 只要本类返回的上限<b>永远不超过 {@code getMax()}</b>，两处检查就自动满足，
 * 不需要额外的 Mixin。代价是 MekCK 的配置值不能突破枚举自带的 {@code maxStack}——
 * 对 Mek 原生类型这是对的（那是 Mek 的平衡）；对 MekCK 自注入的类型无影响，
 * 因为它们的 {@code maxStack} 由 Task 4 自己指定。
 */
public final class MekCkUpgradeTypes {

    private MekCkUpgradeTypes() {
    }

    /** 当前已加载的全部升级常量：Mek 原生 7 种 + 所有已加载注入者追加的。 */
    public static List<Upgrade> all() {
        return List.of(Upgrade.values());
    }

    /** 按 {@code getRawName()} 反查。找不到（注入者缺席）返回 {@code null}。 */
    public static Upgrade resolve(String rawName) {
        return MekCkUpgradeCodec.byName(all(), Upgrade::getRawName, rawName);
    }

    /** 存档里存的字符串名。 */
    public static String nameOf(Upgrade type) {
        return type.getRawName();
    }

    /**
     * 升级物品 → 升级常量。
     *
     * <p>优先走 {@link IUpgradeItem}：卡片自身知道自己是哪张，O(1)，且对
     * 任何按 Mek 规矩实现的第三方卡片都成立。不实现该接口的物品再回退到
     * 逐个比对 {@code UpgradeUtils.getStack(type, 1)}。
     *
     * <p><b>不要记日志</b>：本方法会被槽位校验逐 tick 调用，普通物品查不到是常态。
     */
    public static Optional<Upgrade> byItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        if (stack.getItem() instanceof IUpgradeItem upgradeItem) {
            return Optional.ofNullable(upgradeItem.getUpgradeType(stack));
        }
        for (Upgrade type : all()) {
            if (stack.is(UpgradeUtils.getStack(type, 1).getItem())) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /**
     * 某等级是否接受该升级类型。
     *
     * <p>MekCK 自有的两种卡对所有工厂家族开放；Mek 原生类型只接受
     * {@code MekckConfig} 参与配置的三种（速度/能量/存储），其余留给 Mek 自己的
     * 机器语义，避免装上无效果的卡。
     */
    public static boolean isSupportedBy(Upgrade type, CuttingMachineFactoryTier tier) {
        return type == MekCkUpgradeRefs.storage()
                || type == MekCkUpgradeRefs.randomize()
                || type == Upgrade.SPEED
                || type == Upgrade.ENERGY;
    }

    /** 无等级概念的场合用：直接取枚举自带上限。 */
    public static int capOf(Upgrade type) {
        return type.getMax();
    }

    /**
     * 某升级类型在某等级的安装上限。
     *
     * @return 永远在 {@code [0, type.getMax()]} 区间——这是 Mek 那两处检查不被触发的条件
     */
    public static int capOf(Upgrade type, CuttingMachineFactoryTier tier) {
        if (tier == null) {
            return type.getMax();
        }
        int configured;
        if (type == MekCkUpgradeRefs.storage()) {
            configured = MekckConfig.getFactoryStackUpgradeMax(tier);
        } else if (type == Upgrade.SPEED) {
            configured = MekckConfig.getFactorySpeedUpgradeMax(tier);
        } else if (type == Upgrade.ENERGY) {
            configured = MekckConfig.getFactoryEnergyUpgradeMax(tier);
        } else {
            return type.getMax();
        }
        return Math.max(0, Math.min(configured, type.getMax()));
    }

    /** 解码，按 {@link #capOf(Upgrade)} 裁剪。 */
    public static MekCkUpgradeCodec.Decoded<Upgrade> decode(CompoundTag tag) {
        return MekCkUpgradeCodec.decode(tag, MekCkUpgradeTypes::resolve, MekCkUpgradeTypes::capOf);
    }

    /** 编码，带上无法解析的原始条目以免丢失。 */
    public static CompoundTag encode(Map<Upgrade, Integer> known, List<CompoundTag> unknownRaw) {
        return MekCkUpgradeCodec.encode(known, MekCkUpgradeTypes::nameOf, unknownRaw);
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 提交**

```bash
git add src/main/java/cn/ism/mekck/upgrade/MekCkUpgradeTypes.java
git commit -m "新增 MekCkUpgradeTypes：Upgrade 适配层、聚合入口与上限裁定"
```

---

### Task 6: 重定向 TileComponentUpgrade 的持久化

`javap` 实测 `TileComponentUpgrade`：

```java
public void read(CompoundTag);   // 内部：this.upgrades.putAll(Upgrade.buildMap(tag))
public void write(CompoundTag);  // 内部：Upgrade.saveMap(this.upgrades, tag)，写到 "componentUpgrade" 子标签
```

用 `@Redirect` 而不是 `@Inject`：**`Upgrade.saveMap` / `buildMap` 是 `public static`，
在 `Upgrade` 上 Mixin 会影响整合包里所有 Mekanism 机器**（含 Mek 自己的）。
重定向 `TileComponentUpgrade` 的实例调用则只作用于 MekCK 的 tile。

**未知条目的存放**：`decode` 把解析不出来的条目放进 `unknownRaw`，
但 `read` 之后 Mek 组件内部只有一个 `Map<Upgrade,Integer>`，没有地方放它们，
下次 `write` 就会丢掉。解决办法是给组件 Mixin 加一个 `@Unique` 字段。

**Files:**
- Create: `src/main/java/cn/ism/mekck/mixin/IMekCkUnknownUpgradeHolder.java`
- Create: `src/main/java/cn/ism/mekck/mixin/MixinTileComponentUpgradePersistence.java`
- Create: `src/main/java/cn/ism/mekck/mixin/MixinUpgradeUtilsGetStack.java`（兜底，见 Step 3）
- Modify: `src/main/resources/mekck.mixins.json`

**Interfaces:**
- Consumes: `MekCkUpgradeTypes.decode(CompoundTag, CuttingMachineFactoryTier)` /
  `encode(Map, List)`（Task 5）
  **⚠️ 必须用带 `tier` 的 `decode` 重载**——不带 tier 的重载不做 MekCK 配置裁剪。
  照抄它会让 BASIC/ADVANCED/ELITE/ULTIMATE/SINGULARITY 五档把存档里的存储卡读成
  `getMax()`=6，而这五档的 `MekckConfig.getStackUpgradeDefault` 是 0——
  正是 Task 5 的 R3 要修的 bug 原样复活。
- Produces: `IMekCkUnknownUpgradeHolder#mekck$unknownRaw()` / `#mekck$setUnknownRaw(List<CompoundTag>)`

- [ ] **Step 1: 建 holder 接口**

`src/main/java/cn/ism/mekck/mixin/IMekCkUnknownUpgradeHolder.java`

```java
package cn.ism.mekck.mixin;

import net.minecraft.nbt.CompoundTag;

import java.util.List;

/**
 * 给 {@link mekanism.common.tile.component.TileComponentUpgrade} 附加的「无法解析的升级条目」
 * 存放点。
 *
 * <p>为什么需要它：{@code MekCkUpgradeCodec.decode} 遇到存档里当前不存在的升级名时
 * （比如玩家卸载了注入该升级的 mod），会把原始条目原样返回而不是丢弃。
 * 但 {@code TileComponentUpgrade} 内部只有 {@code Map<Upgrade,Integer>} 一个字段，
 * 装不下这些条目——不存下来就会在下次存档时被抹掉，装回那个 mod 也恢复不了。
 */
public interface IMekCkUnknownUpgradeHolder {

    /** 无法解析的原始条目，可能为空列表，不可为 null。 */
    List<CompoundTag> mekck$unknownRaw();

    void mekck$setUnknownRaw(List<CompoundTag> entries);
}
```

- [ ] **Step 2: 建持久化 Mixin**

`src/main/java/cn/ism/mekck/mixin/MixinTileComponentUpgradePersistence.java`

```java
package cn.ism.mekck.mixin;

import cn.ism.mekck.upgrade.MekCkUpgradeCodec;
import cn.ism.mekck.upgrade.MekCkUpgradeTypes;
import mekanism.api.Upgrade;
import mekanism.common.tile.component.TileComponentUpgrade;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;
import java.util.Map;

/**
 * 把 {@link TileComponentUpgrade} 的升级持久化从 ordinal 索引换成名字键。
 *
 * <h3>为什么要换</h3>
 * Mek 的 {@code Upgrade.saveMap} 按 {@code ordinal()} 写，{@code buildMap} 按
 * {@code byIndexStatic} 读，而后者走 {@code MathUtils.getByIndexMod} <b>取模回绕</b>——
 * 越界不抛异常、不打日志。注入 {@code Upgrade} 的 mod 增删会让 ordinal 整体位移，
 * 玩家旧存档里的升级数量会静默变成另一种升级。详见
 * {@code src/test/java/cn/ism/mekck/upgrade/TestUpgradeIndexWraparoundArithmetic.java}。
 *
 * <h3>为什么用 @Redirect 而不是 @Inject</h3>
 * {@code Upgrade.saveMap} / {@code buildMap} 是 {@code public static}，
 * 在 {@code Upgrade} 上注入会波及整合包里<b>所有</b> Mekanism 机器（Mek 自己的也在内）。
 * 重定向本组件的实例调用则只作用于挂了本 Mixin 的 tile。
 *
 * <h3>只换持久化，运行时行为一概不动</h3>
 * 20 tick 安装读条、槽位合法性（{@code UpgradeInventorySlot.input(listener, supported)}）、
 * GUI 升级 tab 全部仍由 Mek 原生代码负责。
 */
@Mixin(TileComponentUpgrade.class)
public abstract class MixinTileComponentUpgradePersistence implements IMekCkUnknownUpgradeHolder {

    @Unique
    private List<CompoundTag> mekck$unknownRaw = List.of();

    /**
     * 本组件所属的机器档位，取自 tile。
     *
     * <p>只用于 {@link MekCkUpgradeTypes#decode(CompoundTag, CuttingMachineFactoryTier)}
     * 的按档位裁剪。tile 不是 MekCK 机器时返回 {@code null}，
     * 此时 decode 退化为「只按枚举自带 maxStack 裁剪」。
     */
    @Unique
    private CuttingMachineFactoryTier mekck$tier() {
        Object owner = this.tile;
        if (owner instanceof MekCkMachineTile machine) {
            return machine.getTier();
        }
        return null;
    }

    @Override
    public List<CompoundTag> mekck$unknownRaw() {
        return mekck$unknownRaw;
    }

    @Override
    public void mekck$setUnknownRaw(List<CompoundTag> entries) {
        this.mekck$unknownRaw = entries == null ? List.of() : entries;
    }

    /**
     * ⚠️ 目标方法是<b>合成 lambda</b>，不是 {@code read} 本身。
     *
     * <p>{@code javap -p -c TileComponentUpgrade} 实测：{@code read} 只用
     * {@code invokedynamic} 造一个 {@code Consumer} 交给
     * {@code NBTUtils.setCompoundIfPresent}，真正调用 {@code Upgrade.buildMap} 的是
     * 合成方法 {@code lambda$read$1(CompoundTag)}。把 {@code method} 写成
     * {@code "read(Lnet/minecraft/nbt/CompoundTag;)V"} 匹配不到任何目标，
     * 配合 {@code injectors.defaultRequire: 1} 会<b>启动即崩</b>。
     *
     * <p>该 lambda 的完整逻辑是：{@code upgrades.clear()} →
     * {@code putAll(Upgrade.buildMap(tag))}（← 本处重定向）→
     * 遍历 {@code getSupportedTypes()} 调 {@code recalculateUpgrades} →
     * 读 {@code "Items"} 槽位。**只有 buildMap 一处被换掉，其余全部走 Mek 原生。**
     */
    @Redirect(
            method = "lambda$read$1(Lnet/minecraft/nbt/CompoundTag;)V",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/api/Upgrade;buildMap(Lnet/minecraft/nbt/CompoundTag;)"
                            + "Ljava/util/Map;"))
    private Map<Upgrade, Integer> mekck$decode(CompoundTag tag) {
        MekCkUpgradeCodec.Decoded<Upgrade> decoded = MekCkUpgradeTypes.decode(tag, mekck$tier());
        this.mekck$unknownRaw = decoded.unknownRaw();
        return decoded.known();
    }

    @Redirect(
            method = "write(Lnet/minecraft/nbt/CompoundTag;)V",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/api/Upgrade;saveMap(Ljava/util/Map;Lnet/minecraft/nbt/CompoundTag;)V"))
    private void mekck$encode(Map<Upgrade, Integer> map, CompoundTag tag) {
        MekCkUpgradeTypes.encode(map, this.mekck$unknownRaw).copyTo(tag);
    }
}
```

- [ ] **Step 3: 建 `getStack` 兜底 Mixin（必须，否则玩家点「移除升级」崩服）**

Task 5 复审的 C1：`UpgradeUtils.getStack(Upgrade,int)` 用 `ordinal()` 索引
`UpgradeUtils$1` 的合成 switch 映射表，而该表只填了 7 个原生常量，
**对任何注入常量抛 `IncompatibleClassChangeError`**。

不止 `byItem` 一处踩。实测 `javap -p -c`：

```
TileComponentUpgrade.removeUpgrade(Upgrade, boolean):
  28: invokestatic  UpgradeUtils.getStack(Upgrade, int)
```

而 `removeUpgrade` 的唯一调用者是 `mekanism.common.network.to_server.PacketGuiInteract$GuiInteraction`
（扫全 jar 常量池确认，全 jar 只有它与 `TileComponentUpgrade` 引用 `removeUpgrade`）——
**服务端收包，玩家点 Mek 升级界面的「移除」即可触达**。后果是崩服线程。

`src/main/java/cn/ism/mekck/mixin/MixinUpgradeUtilsGetStack.java`

```java
package cn.ism.mekck.mixin;

import mekanism.api.Upgrade;
import mekanism.common.item.interfaces.IUpgradeItem;
import mekanism.common.util.UpgradeUtils;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 给 {@link UpgradeUtils#getStack(Upgrade, int)} 补上注入型升级的支持。
 *
 * <h3>为什么必须有这个 Mixin</h3>
 * {@code getStack} 是 javac 生成的 {@code switch(type.ordinal())}：
 * <pre>
 *   0: getstatic     UpgradeUtils$1.$SwitchMap$mekanism$api$Upgrade:[I
 *   4: invokevirtual Upgrade.ordinal:()I
 *   7: iaload
 *   8: tableswitch { 1 to 7 }
 * </pre>
 * 而 {@code UpgradeUtils$1.<clinit>} 只给 7 个原生常量赋了槽位
 * （{@code SPEED→1 … STONE_GENERATOR→7}）。注入常量的槽位恒为 0 →
 * 落 {@code default} → 抛 {@code IncompatibleClassChangeError}。
 *
 * <p>触达路径不止一处：{@code TileComponentUpgrade.removeUpgrade(Upgrade,boolean)}
 * 偏移 28 也调它，而 {@code removeUpgrade} 的唯一调用者是
 * {@code PacketGuiInteract$GuiInteraction}（服务端收包）。
 * <b>即玩家装上升级卡后点「移除」会崩服线程。</b>
 *
 * <h3>为什么用 HEAD + cancellable 而不是 @Redirect</h3>
 * 原实现对 7 个原生常量是正确的，只需在<b>它会抛之前</b>拦下注入常量即可，
 * 不必重写整张表。用 {@code @Redirect} 反而要把 7 个 case 复制一遍。
 *
 * <h3>为什么扫注册表而不是只认 MekCK 自己的两张卡</h3>
 * 同样的雷对<b>所有</b>注入型 mod 都成立（Mekanism Extras 的 STACK 同样中招）。
 * 按 {@code IUpgradeItem.getUpgradeType} 反查注册表，能让任何遵循 Mek 规矩的
 * 第三方升级卡也拿到正确物品。
 */
@Mixin(UpgradeUtils.class)
public abstract class MixinUpgradeUtilsGetStack {

    @Inject(method = "getStack(Lmekanism/api/Upgrade;I)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"), cancellable = true)
    private static void mekck$handleInjectedUpgrades(Upgrade type, int count,
                                                    CallbackInfoReturnable<ItemStack> cir) {
        if (type == null || count <= 0) {
            return;
        }
        // 7 个原生常量交给原实现——它是对的
        if (type.ordinal() <= Upgrade.STONE_GENERATOR.ordinal()) {
            return;
        }
        Item item = findItemFor(type);
        if (item != null) {
            cir.setReturnValue(new ItemStack(item, count));
        }
        // 找不到就放行给原实现（它会抛）。不要用空栈掩盖——
        // 空栈会让 removeUpgrade 静默扣掉数量却不给回物品。
    }

    /** 按 {@code IUpgradeItem.getUpgradeType} 在物品注册表里反查该升级类型的物品。 */
    @Unique
    private static Item findItemFor(Upgrade type) {
        for (Item item : ForgeRegistries.ITEMS) {
            if (item instanceof IUpgradeItem upgradeItem
                    && upgradeItem.getUpgradeType(new ItemStack(item)) == type) {
                return item;
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: 挂进 mixin 配置**

`src/main/resources/mekck.mixins.json` 的 `mixins` 数组加入
`"MixinTileComponentUpgradePersistence"` 与 `"MixinUpgradeUtilsGetStack"`：

```json
  "mixins": [
    "MixinAPILang",
    "MixinCuttingBoardBlockEntity",
    "MixinExtremeSmithingMenu",
    "MixinTileComponentUpgradePersistence",
    "MixinUpgrade",
    "MixinUpgradeUtilsGetStack"
  ],
```

**注意**：`mixins`（非 `client`）里放 `MixinAPILang` / `MixinUpgrade` /
`MixinUpgradeUtilsGetStack` 是刻意的——`getStack` 在**服务端收包路径**上被调用，
放 `client` 段就只在客户端生效，崩的会是客户端而不是修复它。

- [ ] **Step 5: 核对注入点签名**

Run:
```
javap -p -c -cp "<jar>" mekanism.common.tile.component.TileComponentUpgrade
```

必须逐条核对下面 4 点，与 Step 2 的代码对齐（**预检已确认，但改代码时仍要自己核一遍**）：

| 调用 | 所在方法 | 结论 |
|---|---|---|
| `Upgrade.buildMap(CompoundTag)Map` | `lambda$read$1(CompoundTag)` | ⚠️ **不是 `read`**。`read` 只用 `invokedynamic` 造 Consumer |
| `Upgrade.saveMap(Map,CompoundTag)void` | `write(CompoundTag)` | ✅ 直接在 `write` 方法体内 |
| 描述符全名 | — | `Lmekanism/api/Upgrade;buildMap(Lnet/minecraft/nbt/CompoundTag;)Ljava/util/Map;` |
| 描述符全名 | — | `Lmekanism/api/Upgrade;saveMap(Ljava/util/Map;Lnet/minecraft/nbt/CompoundTag;)V` |

若签名不符，**改 `@At(target=...)` 里的描述符或 `method` 名**，
不要改 `MekCkUpgradeTypes` 的逻辑。

**注意**：`buildMap` 那一处重定向若匹配不到，`injectors.defaultRequire: 1`
会让游戏**启动即崩**（`InvalidInjectionException`），不是静默失效——
这是有意的设计，别改成 `defaultRequire: 0`。

- [ ] **Step 6: 编译验证**

Run: `./gradlew compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 提交**

```bash
git add src/main/java/cn/ism/mekck/mixin/IMekCkUnknownUpgradeHolder.java \
        src/main/java/cn/ism/mekck/mixin/MixinTileComponentUpgradePersistence.java \
        src/main/resources/mekck.mixins.json
git commit -m "升级持久化改名字键：重定向 TileComponentUpgrade 的 read/write"
```

---

### Task 7: 两张升级卡物品

卡片实现 `IUpgradeItem`（`javap` 实测，唯一抽象方法
`Upgrade getUpgradeType(ItemStack)`），返回 Task 4 注入的常量。
**卡片本身不需要任何 Mixin**——`UpgradeInventorySlot.input(listener, supported)`
按 `supported` 集合判定槽位合法性，`TileComponentUpgrade.tickServer()` 负责 20 tick 读条与安装。

**物品注册**沿用 `UniversalCuttingMachine.ITEMS`（`UniversalCuttingMachine.java:151`），
不引入 `ItemDeferredRegister`——仓库现有模式，且只多两个物品。

**Files:**
- Create: `src/main/java/cn/ism/mekck/upgrade/MekCkStorageUpgradeItem.java`
- Create: `src/main/java/cn/ism/mekck/upgrade/MekCkRandomizeUpgradeItem.java`
- Modify: `src/main/java/cn/ism/mekck/UniversalCuttingMachine.java`（注册两个 `RegistryObject`）

**Interfaces:**
- Consumes: `MekCkUpgradeRefs.storage()` / `randomize()`（Task 4）
- Produces: `UniversalCuttingMachine.STORAGE_UPGRADE_ITEM` /
  `RANDOMIZE_UPGRADE_ITEM`，类型 `RegistryObject<Item>`

- [ ] **Step 1: 写存储卡**

`src/main/java/cn/ism/mekck/upgrade/MekCkStorageUpgradeItem.java`

```java
package cn.ism.mekck.upgrade;

import mekanism.api.Upgrade;
import mekanism.common.item.interfaces.IUpgradeItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 存储升级卡：提升机器的并行线程数与缓冲容量。
 *
 * <p>实现 {@link IUpgradeItem} 即可被 Mekanism 的升级槽识别——
 * {@code TileComponentUpgrade.tickServer()} 通过
 * {@code IUpgradeItem.getUpgradeType(stack)} 拿到升级类型，
 * 槽位合法性由 {@code UpgradeInventorySlot.input(listener, supported)} 的
 * {@code supported} 集合决定。无需任何 Mixin。
 */
public class MekCkStorageUpgradeItem extends Item implements IUpgradeItem {

    public MekCkStorageUpgradeItem(Properties properties) {
        super(properties);
    }

    @Override
    public Upgrade getUpgradeType(ItemStack stack) {
        return MekCkUpgradeRefs.storage();
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.mekck.upgrade_storage"));
    }
}
```

- [ ] **Step 2: 写随机化卡**

`src/main/java/cn/ism/mekck/upgrade/MekCkRandomizeUpgradeItem.java`

```java
package cn.ism.mekck.upgrade;

import mekanism.api.Upgrade;
import mekanism.common.item.interfaces.IUpgradeItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 随机化升级卡：把本局可用的 49 种食物重新随机。
 *
 * <p>取代原先借用 {@code mekanism_extras:upgrade_creative} 的做法。
 * 枚举常量叫 {@code RANDOMIZE} 而非 {@code CREATIVE}，因为 Mek Extras 已注入同名常量，
 * 同名不同实例会被名字键持久化当成同一张卡（见 {@link MekCkUpgradeRefs} 类注释）。
 */
public class MekCkRandomizeUpgradeItem extends Item implements IUpgradeItem {

    public MekCkRandomizeUpgradeItem(Properties properties) {
        super(properties);
    }

    @Override
    public Upgrade getUpgradeType(ItemStack stack) {
        return MekCkUpgradeRefs.randomize();
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.mekck.upgrade_randomize"));
    }
}
```

- [ ] **Step 3: 注册两个物品**

在 `src/main/java/cn/ism/mekck/UniversalCuttingMachine.java` 中，
紧跟现有的 `GUIDE_HANDBOOK_ITEM`（`:154`）之后加：

```java
    /** 存储升级卡：提升并行线程数与缓冲容量。 */
    public static final RegistryObject<Item> STORAGE_UPGRADE_ITEM = ITEMS.register("upgrade_storage",
            () -> new cn.ism.mekck.upgrade.MekCkStorageUpgradeItem(new Item.Properties().stacksTo(64)));
    /** 随机化升级卡：随机化本局 49 种可用食物。 */
    public static final RegistryObject<Item> RANDOMIZE_UPGRADE_ITEM = ITEMS.register("upgrade_randomize",
            () -> new cn.ism.mekck.upgrade.MekCkRandomizeUpgradeItem(new Item.Properties().stacksTo(64)));
```

`RegistryObject` 与 `Item` 已在该文件 import 过（`:137` / `:104`），无需新增 import。

- [ ] **Step 4: 编译验证**

Run: `./gradlew compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/cn/ism/mekck/upgrade/ \
        src/main/java/cn/ism/mekck/UniversalCuttingMachine.java
git commit -m "新增存储卡 / 随机化卡物品（实现 IUpgradeItem）"
```

---

### Task 8: 配方、模型、贴图、译名

**Files:**
- Create: `src/main/resources/assets/mekck/models/item/upgrade_storage.json`
- Create: `src/main/resources/assets/mekck/models/item/upgrade_randomize.json`
- Create: `src/main/resources/assets/mekck/textures/item/upgrade_storage.png`
- Create: `src/main/resources/assets/mekck/textures/item/upgrade_randomize.png`
- Create: `src/main/resources/data/mekck/recipes/upgrade/storage.json`
- Modify: `src/main/resources/data/mekck/recipes/creative_upgrade_from_49_foods.json`（改产物）
- Modify: `src/main/resources/assets/mekck/lang/en_us.json`
- Modify: `src/main/resources/assets/mekck/lang/zh_cn.json`
- Modify: `CREDITS.txt`

**Interfaces:**
- Consumes: Task 4 的译名 key（`upgrade.mekck.storage` 等）、Task 7 的物品 id

- [ ] **Step 1: 生成贴图**

不复制 `mekanism_extras` 的 PNG。生成 16×16 占位贴图（后续可替换为正式美术）：

```bash
python - <<'PY'
import struct, zlib, os

def png(path, rgb):
    w = h = 16
    raw = b''
    for y in range(h):
        raw += b'\x00' + bytes(rgb) * w
    def chunk(tag, data):
        c = tag + data
        return struct.pack('>I', len(data)) + c + struct.pack('>I', zlib.crc32(c))
    out = b'\x89PNG\r\n\x1a\n'
    out += chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
    out += chunk(b'IDAT', zlib.compress(raw))
    out += chunk(b'IEND', b'')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, 'wb').write(out)
    print('wrote', path)

base = 'src/main/resources/assets/mekck/textures/item'
png(f'{base}/upgrade_storage.png', (232, 90, 160))    # BRIGHT_PINK
png(f'{base}/upgrade_randomize.png', (160, 90, 232))  # PURPLE
PY
```

- [ ] **Step 2: 写模型**

`src/main/resources/assets/mekck/models/item/upgrade_storage.json`
```json
{
  "parent": "minecraft:item/generated",
  "textures": {
    "layer0": "mekck:item/upgrade_storage"
  }
}
```

`src/main/resources/assets/mekck/models/item/upgrade_randomize.json`
```json
{
  "parent": "minecraft:item/generated",
  "textures": {
    "layer0": "mekck:item/upgrade_randomize"
  }
}
```

- [ ] **Step 3: 写存储卡配方**

**只用原版物品**——已核实 `mekanism_alloy` 在本仓库不存在（`grep -rn "mekanism_alloy" src/main/java` 无命中），
引用它会让配方在游戏里变成不可合成的死配方。

`src/main/resources/data/mekck/recipes/upgrade/storage.json`
```json
{
  "type": "minecraft:crafting_shaped",
  "category": "misc",
  "pattern": [
    "ABA",
    "ACA",
    "ABA"
  ],
  "key": {
    "A": { "item": "minecraft:redstone" },
    "B": { "item": "minecraft:iron_ingot" },
    "C": { "item": "minecraft:redstone_block" }
  },
  "result": {
    "item": "mekck:upgrade_storage",
    "count": 1
  }
}
```

- [ ] **Step 4: 改随机化卡的现有配方产物**

仓库里已存在 `src/main/resources/data/mekck/recipes/creative_upgrade_from_49_foods.json`，
产物目前是 **`mekanism_extras:upgrade_creative`**——这是全仓库最后一处对 Mek Extras 的硬引用，
不改的话 MekCK 自己的随机化卡无法合成，旧的还能合成，§2.4 的 bug 就没修掉。

该配方的 `type` 是 `avaritia:shapeless_table`，而 `avaritia` **不在 `mods.toml` 的依赖列表里**
（只有 forge / minecraft / farmersdelight / mekanism / ae2）——没装无尽贪婪时这条配方会直接报错。
并行负责条件加载的同伴已把本文件划为他们的例外（他们只碰
`data/mekck/recipes/**` 的其余部分），所以**本步骤顺带把 avaritia 条件一并加上**，
避免留缺口。

改成 `forge:conditional` 包裹 + 自有产物：

```json
{
  "type": "forge:conditional",
  "conditions": [
    { "type": "forge:mod_loaded", "modid": "avaritia" }
  ],
  "recipe": {
    "type": "avaritia:shapeless_table",
    "category": "misc",
    "tier": 4,
    "show_notification": true,
    "ingredients": [ ...49 个 mekck:cuf_* tag 原样保留... ],
    "result": {
      "item": "mekck:upgrade_randomize",
      "count": 1
    }
  }
}
```

⚠️ `forge:conditional` 的 type 字面量**必须先实测确认**（改一个配方启动一次看日志）。
若实测结果不是 `forge:conditional`，以实测值为准——**不要拿这个文件赌未验证的字符串**。

- [ ] **Step 5: 确认没有残留的 Mek Extras 引用**

```bash
grep -rn "mekanism_extras" src/main/resources/ || echo "资源目录已无 Mek Extras 引用"
```
预期：仅剩 `assets/mekckfactory/**` 里可能存在的引用（那是阶段 2 要删的目录）；
若 `data/` 与 `assets/mekck/` 下还有输出，逐条改掉。

- [ ] **Step 6: 写译名**

`src/main/resources/assets/mekck/lang/en_us.json` 追加（保持文件既有的缩进与逗号风格）：
```json
  "upgrade.mekck.storage": "Storage Upgrade",
  "upgrade.mekck.storage.description": "Increases a machine's parallel thread count and buffer capacity.",
  "upgrade.mekck.randomize": "Randomize Upgrade",
  "upgrade.mekck.randomize.description": "Randomizes which foods this world's machines can produce.",
  "item.mekck.upgrade_storage": "Storage Upgrade",
  "item.mekck.upgrade_randomize": "Randomize Upgrade",
  "tooltip.mekck.upgrade_storage": "Increases parallel thread count and buffer capacity.",
  "tooltip.mekck.upgrade_randomize": "Randomizes this world's available foods."
```

`src/main/resources/assets/mekck/lang/zh_cn.json` 追加：
```json
  "upgrade.mekck.storage": "存储升级",
  "upgrade.mekck.storage.description": "提升机器的并行线程数与缓冲容量。",
  "upgrade.mekck.randomize": "随机化升级",
  "upgrade.mekck.randomize.description": "随机化本局机器可生产的食物种类。",
  "item.mekck.upgrade_storage": "存储升级",
  "item.mekck.upgrade_randomize": "随机化升级",
  "tooltip.mekck.upgrade_storage": "提升并行线程数与缓冲容量。",
  "tooltip.mekck.upgrade_randomize": "随机化本局可用的食物种类。"
```

**验证 JSON 合法**：
```bash
python -c "import json;json.load(open('src/main/resources/assets/mekck/lang/en_us.json',encoding='utf-8'));json.load(open('src/main/resources/assets/mekck/lang/zh_cn.json',encoding='utf-8'));print('lang JSON ok')"
```

- [ ] **Step 7: 改 CREDITS.txt**

`CREDITS.txt:16` 现在写的是：

```
  Mekanism Extras       工厂安装器（绝对 ~ 悖论无限）
```

这行本身**是准确的**——MekCK 确实用 Mek Extras 的工厂安装器物品做顶层升级安装
（README「Integration」也这么写），与升级卡无关。**保留该行，但追加一句说明**，
避免读者以为 MekCK 的升级卡依赖它：

```
  Mekanism Extras       工厂安装器（绝对 ~ 悖论无限）；MekCK 自己的升级卡
                        （存储 / 随机化）已自注册，不依赖本模组
```

- [ ] **Step 8: 完整构建**

Run: `./gradlew build --console=plain`
Expected: `BUILD SUCCESSFUL`，全部单测通过（现有 73 + 新增 14 = 87）

- [ ] **Step 9: 提交**

```bash
git add src/main/resources/assets/mekck/ \
        src/main/resources/data/mekck/recipes/ \
        CREDITS.txt
git commit -m "两张升级卡的配方/模型/贴图/译名；49 食物配方改产出自有随机化卡"
```

---

### Task 9: 实机冒烟验证（本环境无法完成，需你执行）

三个 Mixin 的应用正确性**无法用单测覆盖**——纯 JVM 环境根本加载不了 `Upgrade`。
必须在游戏里验证。

**Files:** 无代码改动。

- [ ] **Step 1: 启动 dev 客户端**

Run: `./gradlew runClient --console=plain`
Expected: 启动无异常。**任何 Mixin 应用失败都会在这里崩**（`defaultRequire: 1`），
报错形如 `InvalidInjectionException` 或 `MixinApplyError`。

- [ ] **Step 2: 确认常量已注入**

进游戏后执行：
```
/give @s mekck:upgrade_storage
/give @s mekck:upgrade_randomize
```
Expected: 物品能拿到，鼠标悬停显示中文/英文说明（译名 key 解析成功）。

- [ ] **Step 3: 确认没有回归**

本阶段**没有**改任何机器的升级逻辑，所以：
- 现有机器的升级安装/卸载行为应与之前完全一致
- `mekanism_extras` 装与不装，MekCK 都应正常启动

- [ ] **Step 4: 记录结果**

把观察到的现象追加到 spec 的 §9「实机手动验证」小节。
**未验证的部分明确写「未验证」，不要写成已通过。**

---

## 阶段 1 之后

本阶段**不退役**旧升级系统。阶段 2（烹饪工厂样板）迁移第一个家族时，
再一并处理：

| 待退役 | 引用面 | 随阶段 |
|---|---|---|
| `MekCkUpgradeTracker` | 11 个方块实体 | 2 → 3 |
| `MekCkUpgradeType` | 4 个（`GuiUpgradeWindow` 等） | 2 |
| `GuiUpgradeWindow` | 24 个屏幕 | 2 → 3 |
| `IUpgradeMenu` | 36 个 | 2 → 3 |
| `UpgradeHelper` | 42 个 | 3 |
| `mekanism_extras:*` 硬编码 | 2 个 | 2 |

**旧存档的升级数据迁移**（`STACK` → `STORAGE`、`CREATIVE` → `RANDOMIZE`）
随阶段 2 的第一个机器一起做，规则见 spec §7.2。

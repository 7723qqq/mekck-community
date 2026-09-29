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
 * <h3>为什么必须注入而不是自己写个类</h3>
 * {@code Upgrade} 是 {@code final enum}，构造写死 {@code APILang}（同样是 {@code final enum}），
 * 无法继承也无法另起炉灶。要让升级卡用上 MekCK 的译名，只能往它的枚举数组里追加常量。
 *
 * <h3>两个数组都要更新</h3>
 * {@code Upgrade} 持有 {@code $VALUES}（枚举标准）与 {@code UPGRADES}（Mek 自己的缓存）。
 * {@code byIndexStatic} 读的是 <b>{@code UPGRADES}</b> 而非 {@code $VALUES}，
 * 只追加前者会让新常量在索引查找里「不存在」。实测：
 * <pre>
 *   $ javap -c -p mekanism.api.Upgrade
 *     public static mekanism.api.Upgrade byIndexStatic(int);
 *       Code:
 *          0: getstatic     #216   // Field UPGRADES:[Lmekanism/api/Upgrade;
 *          3: iload_0
 *          4: invokestatic  #222   // Method mekanism/api/math/MathUtils.getByIndexMod:([Ljava/lang/Object;I)Ljava/lang/Object;
 *          7: checkcast     #2     // class mekanism/api/Upgrade
 *         10: areturn
 * </pre>
 * 原生 {@code <clinit>} 末尾对两个数组是分别赋值的，所以末尾必须 {@code UPGRADES = $VALUES} 重同步：
 * <pre>
 *     191: invokestatic  #329   // Method $values:()[Lmekanism/api/Upgrade;
 *     194: putstatic     #41    // Field $VALUES:[Lmekanism/api/Upgrade;
 *     197: invokestatic  #331   // Method values:()[Lmekanism/api/Upgrade;
 *     200: putstatic     #216   // Field UPGRADES:[Lmekanism/api/Upgrade;
 *     203: return
 * </pre>
 * 顺带注意 {@code values()} 读的是 {@code $VALUES} 并 {@code clone()}，所以改写 {@code $VALUES}
 * 足以让 {@code Upgrade.values()} 看到新常量，不需要额外处理。
 *
 * <h3>为什么是 TAIL 而不是 HEAD</h3>
 * 原生 {@code <clinit>} 里还有一个 javac 8 风格的合成方法
 * {@code private static Upgrade[] $values()}，它<b>硬编码 7 个元素</b>，
 * 一次性地造出原生数组。只有原生 {@code <clinit>} 自己会调它。
 * 在 TAIL 注入意味着此刻两个数组都已定型、{@code $values()} 已成死码；
 * 早于原生的注入点反而要面对它还在被调用的窗口。
 *
 * <h3>为什么 invoker 要显式写出 name / ordinal</h3>
 * Mixin 对指向 {@code <init>} 的 {@code @Invoker}，是拿 <b>invoker 自身的字节码描述符</b>
 * 去匹配目标构造的（{@code Bytecode.changeDescriptorReturnType(this.method.desc, "V")}），
 * 匹配上之后生成 {@code NEW / DUP / 压参 / INVOKESPECIAL / ARETURN}。
 * <b>Mixin 不会替你补参数</b>——压参是照着 invoker 的形参列表逐个压的，
 * 所以参数必须按<b>字节码元数</b>写，不能按源码元数写。
 * {@code javap -p} 打印源码层签名但不打印参数名；{@code javap -p -s} 额外打印
 * {@code descriptor:} 行，<b>那才是判断 {@code @Invoker} 参数个数的依据</b>。
 * （javap 没有任何能打开参数名的选项，别指望从签名里读出 {@code langKey} 这类名字。）实测输出：
 * <pre>
 *   $ javap -p -s mekanism.api.Upgrade
 *     private mekanism.api.Upgrade(java.lang.String, mekanism.api.text.APILang, mekanism.api.text.APILang, int, mekanism.api.text.EnumColor);
 *       descriptor: (Ljava/lang/String;ILjava/lang/String;Lmekanism/api/text/APILang;Lmekanism/api/text/APILang;Lmekanism/api/text/EnumColor;)V
 * </pre>
 * 源码层是 5 个形参，描述符却是 7 个槽位：枚举构造的前两个槽位
 * （{@code name} / {@code ordinal}）是编译器合成的，源码签名里看不见。
 * 因此 {@link #mekck$upgradeInitInvoker} 声明 7 个参数。少写会让 Mixin 去找
 * 不存在的 5 槽描述符，在 required mixin 下直接抛 {@code InvalidAccessorException}。
 *
 * <h3>为什么是「先构造、后写回数组」这个顺序</h3>
 * {@link #mekck$add} 先 {@code NEW} 出新常量，再把加长后的数组写回 {@code $VALUES}。
 * 这个顺序之所以安全，依据是一条已实测的事实：
 * {@code java.lang.Enum(String, int)} <b>只做两次字段赋值，不写共享常量数组</b>，
 * 枚举常量目录由 {@code java.lang.Class} 侧维护。两个 JDK 分别实测（原文照抄，
 * 常量池索引两边并不相同，这点本身也说明不能凭记忆复述）：
 * <pre>
 *   $ javap -c -p java.lang.Enum    # Temurin 17.0.20+8
 *     protected java.lang.Enum(java.lang.String, int);
 *       Code:
 *          0: aload_0
 *          1: invokespecial #11   // Method java/lang/Object."&lt;init&gt;":()V
 *          4: aload_0
 *          5: aload_1
 *          6: putfield      #1    // Field name:Ljava/lang/String;
 *          9: aload_0
 *         10: iload_2
 *         11: putfield      #7    // Field ordinal:I
 *         14: return
 *
 *   $ /d/mc/neon_jdk8/bin/javap.exe -c -p java.lang.Enum    # Alibaba Dragonwell 1.8.0_292
 *     protected java.lang.Enum(java.lang.String, int);
 *       Code:
 *          0: aload_0
 *          1: invokespecial #3    // Method java/lang/Object."&lt;init&gt;":()V
 *          4: aload_0
 *          5: aload_1
 *          6: putfield      #1    // Field name:Ljava/lang/String;
 *          9: aload_0
 *         10: iload_2
 *         11: putfield      #2    // Field ordinal:I
 *         14: return
 * </pre>
 * 两边都没有 {@code values[ordinal] = this}，所以新常量的 ordinal 即使超出当前数组长度也不会越界。
 * 保持现在这个顺序即可；<b>不要为它编一个并不存在的越界理由</b>。
 *
 * <h3>为什么 rawName 与 ordinal 传的是两样东西</h3>
 * {@code Upgrade} 自己声明了 {@code private final String name}，遮蔽了 {@code Enum.name}，
 * 而 {@code getRawName()} 读的是<b>自己那个</b>字段。第 3 个槽位必须传小写 raw name
 * （{@code "storage"} / {@code "randomize"}），第 1 个槽位传大写常量名供 {@code name()} 用。
 *
 * <p><b>rawName 决定的是物品注册 id，不是存档键</b>。全 jar 只有 2 个 class 引用
 * {@code getRawName()}，实测：
 * <pre>
 *   $ grep -rl getRawName &lt;解压后的 mekanism jar&gt; --include=*.class
 *     mekanism/api/Upgrade.class
 *     mekanism/common/registries/MekanismItems.class
 * </pre>
 * 真正负责存档的 {@code getTag} / {@code buildMap} 里它出现 <b>0 次</b>——两者走的是
 * <b>ordinal</b>：{@code getTag} 写 {@code putInt("type", ordinal())}，
 * {@code buildMap} 读 {@code getInt("type")} 再喂给 {@code byIndexStatic}。
 * {@code getRawName()} 只出现在 {@code MekanismItems.registerUpgrade} 里，
 * 与字面量 {@code "upgrade_"} 一起经 {@code makeConcatWithConstants} 拼出
 * {@code ITEMS.register("upgrade_" + rawName, ...)} 的注册 id。
 *
 * <p>所以「不能与别的注入常量重名」的真正理由是<b>物品注册表命名空间会撞</b>
 * （两个同名 rawName 会去抢同一个 {@code mekanism:upgrade_xxx} 注册 id，
 * 物品是按注册 id 进存档的），<b>而不是</b>什么存档名字键。
 * <b>不要按「Mek 按 rawName 存存档」去推理</b>——那会得出「ordinal 无关紧要」的结论，
 * 而 ordinal 恰恰是 Mek 升级持久化真正依赖的东西：插入顺序一变，
 * 所有已存机器的 {@code "type"} 索引就会指向另一种升级。
 * 这也是本 Mixin 只允许追加到数组末尾、绝不重排已有常量的原因。
 *
 * <h3>为什么 ordinal 取数组长度</h3>
 * {@code ordinal} 必须是常量在 {@code $VALUES} 里的下标。{@code variants} 是加入新常量
 * <i>之前</i>的快照，其长度恰好等于新常量的下标。
 *
 * <h3>与 Mek Extras / Mek Energistics 的共存</h3>
 * {@code @Shadow} 读的是目标类的活字段，所以本 Mixin 看到的是
 * 「Mek 原生 + 其它注入者已追加的」完整数组，追加后自然叠加。
 * 三个注入者的执行顺序由 mixin config 加载序决定、不保证，
 * 但因为追加是读-改-写且都基于活字段，顺序不影响最终集合。
 *
 * <p><b>真正的硬依赖是 {@code UPGRADES} 的重同步，而且它对别人是不可见的义务。</b>
 * {@code UPGRADES} 是 Mekanism 的私有缓存，其它注入型 mod 没有理由知道它存在，
 * 所以标准写法只会改写 {@code $VALUES}。若那个 mod 在 MekCK <b>之后</b>执行 TAIL 注入
 * 却只写 {@code $VALUES}，{@code UPGRADES} 会停在 MekCK 写回的那个 9 元素数组上：
 * <pre>
 *   $ javap -c -p mekanism.api.math.MathUtils
 *     public static &lt;TYPE&gt; TYPE getByIndexMod(TYPE[], int);
 *       Code:
 *         13: aload_0
 *         14: iload_1
 *         15: aload_0
 *         16: arraylength
 *         17: irem                      # 非负下标走取模回绕，不抛异常
 *         18: aaload
 *         19: areturn
 * </pre>
 * 于是 {@code byIndexStatic} 会<b>取模回绕</b>：那个 mod 的常量 ordinal 落在
 * {@code UPGRADES} 之外，{@code byIndexStatic(9)} 在长度 9 的数组上回绕到下标 0，
 * <b>静默返回另一种升级</b>。该常量从此永久不可达，且全程无任何报错——
 * 这是阶段 1 押注「注入式扩展」时最危险的一种失败模式：它不崩，只是悄悄地错。
 * MekCK 自己的两个常量在任一注入顺序下都安全，因为末尾那次重同步保证了它们可见；
 * 但这条重同步<b>必须由本 Mixin 在自己那一次 TAIL 里做完</b>，不能指望后来者。
 *
 * <h3>跨类初始化的隐式不变量（本文件自身保证不了）</h3>
 * 下面的 TAIL 注入调用 {@code MekCkAPILang.upgradeStorage()}，它的字段非空
 * <b>只是因为</b> {@code Upgrade.<clinit>} 的原生部分在第 9 个字节码偏移起就反复
 * {@code getstatic APILang.UPGRADE_*}，从而强制 {@code APILang.<clinit>}
 * （以及 MixinAPILang 的 TAIL）在本注入点之前跑完。这条链是通的，但它是
 * <b>跨文件的隐式依赖</b>：MixinUpgrade 自己无法保证它。
 * 若将来有别的 mod 在 {@code APILang.<clinit>} 完成之前重入 {@code Upgrade}，
 * {@code MekCkAPILang.require()} 会在类初始化过程中抛 {@code IllegalStateException}，
 * 由 JVM 包装成 {@code ExceptionInInitializerError}，两个类一起进入 erroneous 状态。
 * 与 MixinAPILang 注释里记的 {@code valueOf} 重入陷阱同源，改动任一侧时需一并复核。
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

    /**
     * 调 {@code Upgrade} 的私有构造，字节码上即
     * {@code private Upgrade(String, int, String, APILang, APILang, int, EnumColor)}——
     * 前两个参数是枚举编译器合成的 {@code name} 与 {@code ordinal}，源码签名里看不到，
     * 必须显式声明 7 个（见类注释的 javap 实测输出）。
     *
     * <p>必须 {@code static} 且返回 {@link Upgrade}，否则是<b>启动即崩</b>而不是降级。
     * {@code InvokerInfo.initType} 在 {@code targetName} 等于 {@code <init>} 时，
     * 返回类型不匹配直接 {@code throw new InvalidAccessorException}，
     * 非 {@code static} 同样 {@code throw}——<b>没有回退到普通方法代理的分支</b>。
     * {@code METHOD_PROXY} 只在 {@code @Invoker} 不带值、且方法名不以
     * {@code new}/{@code create} 开头时才可能走到。排查时不要去找「代理没生效」的方向。
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

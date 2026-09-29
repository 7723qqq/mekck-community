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
 * 枚举常量目录由 {@code java.lang.Class} 侧维护。两个 JDK 实测同形：
 * <pre>
 *   $ javap -c -p java.lang.Enum                    # Temurin 17.0.20+8
 *   $ /d/mc/neon_jdk8/bin/javap.exe -c -p java.lang.Enum   # Alibaba Dragonwell 1.8.0_292
 *     protected java.lang.Enum(java.lang.String, int);
 *       Code:
 *          0: aload_0
 *          1: invokespecial  // Method java/lang/Object."&lt;init&gt;":()V
 *          4: aload_0
 *          5: aload_1
 *          6: putfield      // Field name:Ljava/lang/String;
 *          9: aload_0
 *         10: iload_2
 *         11: putfield      // Field ordinal:I
 *         14: return
 * </pre>
 * 两边都没有 {@code values[ordinal] = this}，所以新常量的 ordinal 即使超出当前数组长度也不会越界。
 * 保持现在这个顺序即可；<b>不要为它编一个并不存在的越界理由</b>。
 *
 * <h3>为什么 rawName 与 ordinal 传的是两样东西</h3>
 * {@code Upgrade} 自己声明了 {@code private final String name}，遮蔽了 {@code Enum.name}，
 * 而 {@code getRawName()} 读的是<b>自己那个</b>字段。名字键持久化按 {@code getRawName()} 存，
 * 所以第 3 个槽位必须传小写的 raw name（{@code "storage"} / {@code "randomize"}）；
 * 而 {@code name()} / 存档调试输出走的是 {@code Enum.name}，所以第 1 个槽位传大写常量名。
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
 * 唯一要求是每个注入者都在 {@code <clinit>} 末尾把 {@code UPGRADES} 一并重同步。
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
     * 调 {@code Upgrade} 的私有构造，字节码上即
     * {@code private Upgrade(String, int, String, APILang, APILang, int, EnumColor)}——
     * 前两个参数是枚举编译器合成的 {@code name} 与 {@code ordinal}，源码签名里看不到，
     * 必须显式声明 7 个（见类注释的 javap 实测输出）。
     *
     * <p>必须 {@code static} 且返回 {@link Upgrade}，否则 Mixin 不会把它当成
     * 构造器工厂（{@code OBJECT_FACTORY}）而按普通方法代理处理。
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

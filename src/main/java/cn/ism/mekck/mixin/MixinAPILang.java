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
 * <h3>为什么 invoker 要显式写出 name / ordinal</h3>
 * Mixin 对指向 {@code <init>} 的 {@code @Invoker}，是拿 <b>invoker 自身的字节码描述符</b>
 * 去匹配目标构造的（{@code Bytecode.changeDescriptorReturnType(this.method.desc, "V")}），
 * 匹配上之后生成 {@code NEW / DUP / 压参 / INVOKESPECIAL / ARETURN}。
 * 所以参数必须按<b>字节码元数</b>写，不能按源码元数写：
 * 枚举构造的前两个槽位是编译器合成的 {@code (String name, int ordinal)}。
 * {@code javap} 不带 {@code -s} 不打印 {@code descriptor:} 行，带了也不打印参数名
 * （除非另加 {@code -parameters}），所以「源码几参」与「字节码几参」不是一回事。实测输出：
 * <pre>
 *   $ javap -p -s mekanism.api.text.APILang
 *     private mekanism.api.text.APILang(java.lang.String, java.lang.String);
 *       descriptor: (Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;)V
 *     private mekanism.api.text.APILang(java.lang.String);
 *       descriptor: (Ljava/lang/String;ILjava/lang/String;)V
 * </pre>
 * 两行对应源码层的 {@code (String type, String path)}（4 槽）与 {@code (String key)}（3 槽）。
 * 因此 {@link #mekck$langInitInvoker(String, int, String)} 声明 3 个参数对应单参构造。
 * 传 1 个参数会让 Mixin 去找不存在的 {@code (Ljava/lang/String;)V}，
 * 在 required mixin 下直接抛 {@code InvalidAccessorException} 导致启动失败。
 *
 * <h3>为什么必须「先构造、后写回数组」——JDK 17 不变式</h3>
 * {@link #mekck$add(String, String)} 的顺序不能调换：先 {@code NEW} 出新常量，
 * 再把加长后的数组写回 {@code $VALUES}。这依赖 JDK 17 的 {@code java.lang.Enum(String, int)}
 * 只做两次字段赋值、不碰共享常量数组。实测（Temurin 17.0.20+8）：
 * <pre>
 *   $ javap -c -p java.lang.Enum
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
 * </pre>
 * <b>不要把它「优化」成先写回加长后的数组再构造</b>：一旦目标构造会去按 ordinal 写共享数组
 * （旧 JDK 的枚举实现有此行为，本机无 JDK 8/11 可实测，此处未验证），
 * ordinal 就得落在长度为 N 的旧数组之外，直接 {@code ArrayIndexOutOfBoundsException}。
 * 保持现在的顺序即可。
 *
 * <h3>为什么 ordinal 取数组长度</h3>
 * {@code ordinal} 必须是常量在 {@code $VALUES} 里的下标。{@code variants} 是加入新常量
 * <i>之前</i>的快照，其长度恰好等于新常量的下标。name 传常量名后 {@code name()} 正常。
 *
 * <h3>为什么不能用 {@code APILang.valueOf(...)} 取常量</h3>
 * 4 个注入常量<b>只能</b>经 {@code MekCkAPILang} 的访问器取，不能靠 {@code valueOf}。
 * JDK 17 的 {@code Enum.valueOf(Class, String)} 走 {@code Class.enumConstantDirectory()}，
 * 那是一个建在 {@code transient volatile Map} 字段里的一次性缓存：首次调用时从
 * {@code getEnumConstantsShared()} 拉一次快照，之后永不刷新；{@code Class.getEnumConstants()}
 * 同样只是把该缓存克隆一份返回。改写 {@code $VALUES} 不会让任何一处重建它。
 * 所以只要有谁在注入之前碰过一次 {@code Enum.valueOf} / {@code getEnumConstants()}
 * （反射扫描、库预热等，顺序不受本 Mixin 控制），这 4 个常量对它就不可见，
 * 会抛 {@code IllegalArgumentException: No enum constant}。
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

    /**
     * 调 {@code APILang} 的单参私有构造，字节码上即
     * {@code private APILang(String, int, String)}——前两个参数是枚举编译器合成的
     * {@code name} 与 {@code ordinal}，源码签名里看不到，必须显式声明。
     *
     * <p>必须 {@code static} 且返回 {@link APILang}，否则 Mixin 不会把它当成
     * 构造器工厂（{@code OBJECT_FACTORY}）而按普通方法代理处理。
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

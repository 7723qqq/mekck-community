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
 * 枚举构造的前两个槽位是编译器合成的 {@code (String name, int ordinal)}，
 * {@code javap} 不加 {@code -s} 时看不到它们。实测：
 * <pre>
 *   private APILang(String key);                       // 源码 1 参
 *       descriptor: (Ljava/lang/String;ILjava/lang/String;)V              // 字节码 3 参
 *   private APILang(String type, String path);         // 源码 2 参
 *       descriptor: (Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;)V  // 字节码 4 参
 * </pre>
 * 因此 {@link #mekck$langInitInvoker(String, int, String)} 声明 3 个参数对应单参构造。
 * 传 1 个参数会让 Mixin 去找不存在的 {@code (Ljava/lang/String;)V}，
 * 在 required mixin 下直接抛 {@code InvalidAccessorException} 导致启动失败。
 *
 * <h3>为什么 ordinal 取数组长度</h3>
 * {@code ordinal} 必须是常量在 {@code $VALUES} 里的下标。{@code variants} 是加入新常量
 * <i>之前</i>的快照，其长度恰好等于新常量的下标。name 传常量名后，
 * {@code name()} 与 {@code valueOf("UPGRADE_STORAGE")} 也能正常工作。
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

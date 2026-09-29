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
 * <h3>为什么 invoker 只能收一个 String</h3>
 * Mixin 对指向 {@code <init>} 的 {@code @Invoker} 会按<b>invoker 自身的描述符</b>
 * 推导目标构造（{@code Bytecode.changeDescriptorReturnType(this.method.desc, "V")}），
 * 再生成 {@code NEW / DUP / 压参 / INVOKESPECIAL / ARETURN}。
 * 参数个数一旦对不上，要么找不到目标构造，要么生成出来的方法通不过字节码校验。
 * 所以必须与 {@code private APILang(String)} 逐个参数对应。
 *
 * <h3>注入出来的常量没有 name / ordinal</h3>
 * 枚举的 {@code name} 与 {@code ordinal} 声明在 {@link Enum} 里，
 * 不在 {@code APILang} 的私有构造参数中，无法借这次注入写进去。
 * 因此这 4 个常量只能当译名 key 用：{@code name()} 返回 null、{@code ordinal()} 返回 0。
 * 已核实 Mekanism 1.20.1 自身没有任何一处调用 {@code APILang.values()} 或
 * {@code APILang.valueOf()}，所以不会踩到枚举遍历。
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
     * 调 {@code APILang} 的单参私有构造 {@code private APILang(String key)}。
     *
     * <p>必须 {@code static} 且返回 {@link APILang}，否则 Mixin 不会把它当成
     * 构造器工厂（{@code OBJECT_FACTORY}）而按普通方法代理处理。
     */
    @Invoker("<init>")
    public static APILang mekck$langInitInvoker(String key) {
        throw new AssertionError("mixin 未应用");
    }

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void mekck$injectLangEntries(CallbackInfo ci) {
        MekCkAPILang.upgradeStorage = mekck$add("upgrade.mekck.storage");
        MekCkAPILang.upgradeStorageDescription = mekck$add("upgrade.mekck.storage.description");
        MekCkAPILang.upgradeRandomize = mekck$add("upgrade.mekck.randomize");
        MekCkAPILang.upgradeRandomizeDescription = mekck$add("upgrade.mekck.randomize.description");
    }

    @Unique
    private static APILang mekck$add(String key) {
        ArrayList<APILang> variants = new ArrayList<>(Arrays.asList($VALUES));
        APILang entry = mekck$langInitInvoker(key);
        variants.add(entry);
        $VALUES = variants.toArray(new APILang[0]);
        return entry;
    }
}

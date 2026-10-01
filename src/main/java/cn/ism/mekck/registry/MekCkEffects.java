package cn.ism.mekck.registry;

import net.minecraftforge.registries.RegistryObject;
import static cn.ism.mekck.registry.MekCkRegistries.MOB_EFFECTS;

/**
 * 本模组的状态效果（冰冻 / 低温症 / 永恒冰冻）。
 *
 * <p>本类由 {@code UniversalCuttingMachine} 拆出（注册中枢拆分）。成员文本与拆分前
 * 逐字一致；本类必须<b>早于任何注册事件</b>被触碰一次 —— 见 {@link #init()}。</p>
 */
public final class MekCkEffects {

    private MekCkEffects() {
    }

    /**
     * 触碰式初始化：由 {@code UniversalCuttingMachine} 的构造器调用。
     *
     * <h3>为什么需要它</h3>
     * 条目是在本类的<b>静态初始化器</b>里加进
     * {@link cn.ism.mekck.registry.MekCkRegistries} 的 {@code DeferredRegister} 的，
     * 而 {@code DeferredRegister} 是在 {@code register(bus)} 时挂上注册事件监听器、
     * 事件触发时才去读那张表。若本类因「谁都没引用」而晚于注册事件才初始化，
     * 它的条目会<b>静默地一个都不注册</b> —— 方块放下去变空气、菜单取不到，
     * 而且没有任何报错。所以构造器必须显式触碰每一个注册类，
     * 而不是依赖「反正会被引用到」。
     */
    public static void init() {
    }

    public static final RegistryObject<cn.ism.mekck.effect.FrozenEffect> FROZEN_EFFECT = MOB_EFFECTS.register(
            "frozen", cn.ism.mekck.effect.FrozenEffect::new);

    public static final RegistryObject<cn.ism.mekck.effect.HypothermiaEffect> HYPOTHERMIA_EFFECT = MOB_EFFECTS.register(
            "hypothermia", cn.ism.mekck.effect.HypothermiaEffect::new);

    public static final RegistryObject<cn.ism.mekck.effect.EternalFreezeEffect> ETERNAL_FREEZE_EFFECT = MOB_EFFECTS.register(
            "eternal_freeze", cn.ism.mekck.effect.EternalFreezeEffect::new);

    // Ice cube entity (急冻制冰机攻击生成的冰块)
}

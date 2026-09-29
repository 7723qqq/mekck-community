package cn.ism.mekck.util;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 冰火传说（iceandfire）联动门面：纯反射访问，不引入编译依赖；未安装冰火时所有方法静默无操作。
 * <p>
 * 「冰冻」状态（官方称 Frozen，物品 tooltip "Freezes targets"、死亡讯息 "was frozen by a dragon"）
 * 不是药水效果，而是冰火的实体能力数据：{@code CapabilityHandler.ENTITY_DATA_CAPABILITY}
 * → {@code EntityData.frozenData}（{@code FrozenData}）→ {@code setFrozen(entity, duration)}。
 * 冰龙冰息、冰龙骨冰剑（dragonbone_sword_ice）、龙钢冰剑（dragonsteel_ice_sword）均复用该状态：
 * 冻结期间实体水平移动大幅衰减（×0.25/tick）、离地时额外下坠，结束时碎冰粒子与玻璃碎裂音效，遇火立即解除。
 * </p>
 * <p>
 * 龙骨冰剑的命中参数：冰冻 200 tick + 缓慢Ⅱ 100 tick + 挖掘疲劳Ⅱ 100 tick + 击退；
 * 龙钢冰剑为冰冻 300 tick + 缓慢Ⅱ 300 tick。联动冰冻时长按用户需求取 100 tick（5 秒）。
 * </p>
 */
public final class IceAndFireCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("MekCK");
    private static final String MOD_ID = "iceandfire";
    /** 冰冻时长（tick）：用户指定 100 tick = 5 秒（原龙骨冰剑为 200 tick，按需求缩短）。 */
    public static final int DRAGONBONE_FREEZE_TICKS = 100;

    private static final boolean LOADED = ModList.get() != null && ModList.get().isLoaded(MOD_ID);
    private static boolean initFailed = false;
    private static Object capability;      // Capability<EntityData>（CapabilityHandler.ENTITY_DATA_CAPABILITY）
    private static Field frozenDataField;  // EntityData.frozenData（public FrozenData）
    private static Method setFrozenMethod; // FrozenData.setFrozen(LivingEntity, int)

    static {
        if (LOADED) {
            try {
                Class<?> capabilityHandler = Class.forName("com.github.alexthe666.iceandfire.entity.props.CapabilityHandler");
                capability = capabilityHandler.getField("ENTITY_DATA_CAPABILITY").get(null);
                Class<?> entityData = Class.forName("com.github.alexthe666.iceandfire.entity.props.EntityData");
                frozenDataField = entityData.getField("frozenData");
                Class<?> frozenData = Class.forName("com.github.alexthe666.iceandfire.entity.props.FrozenData");
                setFrozenMethod = frozenData.getMethod("setFrozen", LivingEntity.class, int.class);
            } catch (Throwable t) {
                initFailed = true;
                LOGGER.error("[mekck-iaf] 冰火传说联动初始化失败，冰冻附加将不生效（请回传日志）", t);
            }
        }
    }

    private IceAndFireCompat() {
    }

    /** 冰火传说是否已加载且反射通道可用。 */
    public static boolean isAvailable() {
        return LOADED && !initFailed;
    }

    /**
     * 冰冻三件套（时长按用户需求 100 tick = 5 秒）：走冰火 frozenData capability 的冰冻状态
     * + 缓慢 100 tick + 挖掘疲劳 100 tick（amplifier 实传 2，即游戏内 III 级，非 II 级）。
     * 未安装冰火 / 反射不可用 / 客户端侧时静默跳过。
     */
    public static void applyDragonboneFreeze(LivingEntity target) {
        if (!isAvailable() || target == null || target.level().isClientSide) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            net.minecraftforge.common.capabilities.Capability<Object> cap =
                    (net.minecraftforge.common.capabilities.Capability<Object>) capability;
            Object data = target.getCapability(cap, null).resolve().orElse(null);
            if (data != null) {
                Object frozenData = frozenDataField.get(data);
                setFrozenMethod.invoke(frozenData, target, DRAGONBONE_FREEZE_TICKS);
            }
        } catch (Throwable t) {
            LOGGER.warn("[mekck-iaf] 附加冰冻状态失败：{}", t.toString());
        }
        // 药水部分为原版效果，直接附加（时长同龙骨冰剑 100 tick；amplifier 取 2 = III 级）
        target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 2));
        target.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 100, 2));
    }
}

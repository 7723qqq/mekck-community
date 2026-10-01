package cn.ism.mekck.registry;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.registries.RegistryObject;
import static cn.ism.mekck.UniversalCuttingMachine.MOD_ID;
import static cn.ism.mekck.registry.MekCkRegistries.ENTITY_TYPES;

/**
 * 本模组的弹射物实体（冰块 / 费列罗巧克力 / 炒榛子）。
 *
 * <p>本类由 {@code UniversalCuttingMachine} 拆出（注册中枢拆分）。成员文本与拆分前
 * 逐字一致；本类必须<b>早于任何注册事件</b>被触碰一次 —— 见 {@link #init()}。</p>
 */
public final class MekCkEntities {

    private MekCkEntities() {
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

    public static final RegistryObject<EntityType<cn.ism.mekck.entity.IceCubeEntity>> ICE_CUBE_ENTITY;

    // Ferrero chocolate entity (巧克力大炮攻击生成的费列罗巧克力)

    public static final RegistryObject<EntityType<cn.ism.mekck.entity.FerreroEntity>> FERRERO_ENTITY;

    // Roasted hazelnut entity (坚果爆炒机发射的炒榛子弹射物)

    public static final RegistryObject<EntityType<cn.ism.mekck.entity.RoastedHazelnutEntity>> ROASTED_HAZELNUT_ENTITY;

    // 切菜机（单机、无等级维度）：第四轮改走 Mek 的注册器，**注册名一字不改**
    // （仍是 mekck:universal_cutting_machine），旧存档里已放置的方块因此不会变空气。

    /**
     * 弹射物的注册项在这里落定。
     *
     * <p>原先这些赋值写在 {@code UniversalCuttingMachine} 那段「注册全部」的静态块里，
     * 与工厂注册交织在一起。拆分后必须<b>在本类里赋值</b>：blank final 字段不能跨类赋值 ——
     * 这正是「字段声明与赋值必须同居一处」的现实理由，编译期就会拦下违反它的那种拆法。</p>
     */
    static {
        // 冰块实体（急冻制冰机攻击用）
        ICE_CUBE_ENTITY = ENTITY_TYPES.register("ice_cube", () -> EntityType.Builder
                .of(cn.ism.mekck.entity.IceCubeEntity::new, net.minecraft.world.entity.MobCategory.MISC)
                .sized(0.6F, 0.6F)
                .setShouldReceiveVelocityUpdates(true)
                .build(ResourceLocation.fromNamespaceAndPath(MOD_ID, "ice_cube").toString()));

        // 费列罗巧克力实体（巧克力大炮攻击用）
        FERRERO_ENTITY = ENTITY_TYPES.register("ferrero_chocolate", () -> EntityType.Builder
                .of(cn.ism.mekck.entity.FerreroEntity::new, net.minecraft.world.entity.MobCategory.MISC)
                .sized(0.35F, 0.35F)
                .setShouldReceiveVelocityUpdates(true)
                .build(ResourceLocation.fromNamespaceAndPath(MOD_ID, "ferrero_chocolate").toString()));

        // 炒榛子实体（坚果爆炒机发射的弹射物）
        ROASTED_HAZELNUT_ENTITY = ENTITY_TYPES.register("roasted_hazelnut", () -> EntityType.Builder
                .of(cn.ism.mekck.entity.RoastedHazelnutEntity::new, net.minecraft.world.entity.MobCategory.MISC)
                .sized(0.35F, 0.35F)
                .setShouldReceiveVelocityUpdates(true)
                .build(ResourceLocation.fromNamespaceAndPath(MOD_ID, "roasted_hazelnut").toString()));
    }
}

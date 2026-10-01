package cn.ism.mekck.registry;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import static cn.ism.mekck.UniversalCuttingMachine.MOD_ID;

/**
 * 十个延迟注册器本体，以及它们的统一挂载入口 {@link #registerAll}。
 *
 * <p>其余注册类（{@code MekCkItems} / {@code MekCkFactories} / ……）只往这里加条目，
 * 所以本类必须<b>最先</b>初始化 —— 它就是那张「条目表」。</p>
 *
 * <p>本类由 {@code UniversalCuttingMachine} 拆出（注册中枢拆分）：字段文本与拆分前
 * 逐字一致，新增的只有类外壳与 {@link #registerAll}。</p>
 */
public final class MekCkRegistries {

    private MekCkRegistries() {
    }

    /**
     * 触碰全部注册类，再把十个通用延迟注册器挂到 mod 事件总线。
     *
     * <h3>为什么必须显式触碰每一个注册类</h3>
     * 条目是在各注册类的<b>静态初始化器</b>里加进本类这些 {@code DeferredRegister} 的，
     * 而 {@code DeferredRegister} 是在 {@code register(bus)} 时挂上注册事件监听器、
     * <b>事件触发时</b>才去读那张表。于是存在一个静默失败的窗口：某个注册类若因
     * 「没人引用它」而晚于注册事件才初始化，它的条目<b>一个都不会注册</b> ——
     * 方块放下去变空气、菜单取不到，且没有任何报错或警告。
     * <p>「反正会被引用到」不能充当这个保证：本仓刚发生过一次同类事故 ——
     * 种植切配工厂三件套里的 {@code register(bus)} 漏了一次，
     * 客户端 {@code MenuScreens.register} 一取就抛
     * {@code Registry Object not present}（那段注释仍留在
     * {@code UniversalCuttingMachine} 构造器里，别删）。
     * <p>同理，<b>触碰顺序也是契约</b>：{@code MekCkFactories} 的静态块要读
     * {@code MekCkBaseMachines}… 之类同包字段，顺序颠倒会拿到尚未赋值的 final 字段。
     * 这里把顺序写成一份显式名单，而不是依赖「谁引用谁」。
     *
     * <p>Mek 原生的工厂注册器（{@code *_FACTORY_BLOCKS_REG} 等）不在本方法内：
     * 它们不是本类的 {@code DeferredRegister}，由 {@code UniversalCuttingMachine}
     * 构造器逐个 {@code register(bus)}（那里有逐家族的实机事故注释，保留原位）。
     */
    public static void registerAll(net.minecraftforge.eventbus.api.IEventBus bus) {
        MekCkItems.init();
        MekCkStandaloneMachines.init();
        MekCkLegacyMachines.init();
        MekCkFactories.init();
        MekCkFluids.init();
        MekCkEffects.init();
        MekCkEntities.init();
        MekCkRecipeTypes.init();

        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        MENUS.register(bus);
        RECIPE_SERIALIZERS.register(bus);
        RECIPE_TYPES.register(bus);
        FLUID_TYPES.register(bus);
        FLUIDS.register(bus);
        ENTITY_TYPES.register(bus);
        MOB_EFFECTS.register(bus);
    }

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID);

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);

    /** 通用机械指南手册（右键打开 GuideME 指南 mekguide:mek）。 */

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MOD_ID);

    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, MOD_ID);

    public static final DeferredRegister<FluidType> FLUID_TYPES = DeferredRegister.create(ForgeRegistries.Keys.FLUID_TYPES, MOD_ID);

    public static final DeferredRegister<Fluid> FLUIDS = DeferredRegister.create(ForgeRegistries.FLUIDS, MOD_ID);

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MOD_ID);

    // Recipe type and serializer registries

    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS = DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, MOD_ID);

    public static final DeferredRegister<RecipeType<?>> RECIPE_TYPES = DeferredRegister.create(ForgeRegistries.RECIPE_TYPES, MOD_ID);


    public static final DeferredRegister<MobEffect> MOB_EFFECTS = DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, MOD_ID);
}

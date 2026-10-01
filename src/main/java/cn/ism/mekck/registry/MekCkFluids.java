package cn.ism.mekck.registry;

import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.ForgeFlowingFluid;
import net.minecraftforge.registries.RegistryObject;
import static cn.ism.mekck.registry.MekCkRegistries.BLOCKS;
import static cn.ism.mekck.registry.MekCkRegistries.FLUIDS;
import static cn.ism.mekck.registry.MekCkRegistries.FLUID_TYPES;
import static cn.ism.mekck.registry.MekCkRegistries.ITEMS;

/**
 * 流体类型与流体（有机质、榛子可可酱）及其液块、桶。
 *
 * <p>本类由 {@code UniversalCuttingMachine} 拆出（注册中枢拆分）。成员文本与拆分前
 * 逐字一致；本类必须<b>早于任何注册事件</b>被触碰一次 —— 见 {@link #init()}。</p>
 */
public final class MekCkFluids {

    private MekCkFluids() {
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

    public static final RegistryObject<FluidType> HAZELNUT_COCOA_PASTE_FLUID_TYPE = FLUID_TYPES.register("hazelnut_cocoa_paste", () -> new FluidType(
            FluidType.Properties.create()
                    .descriptionId("fluid_type.mekck.hazelnut_cocoa_paste")
                    .density(1100)
                    .viscosity(2500)
                    .temperature(320)) {
        @Override
        public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
            consumer.accept(new IClientFluidTypeExtensions() {
                @Override
                public net.minecraft.resources.ResourceLocation getStillTexture() {
                    return new net.minecraft.resources.ResourceLocation("minecraft:block/water_still");
                }

                @Override
                public net.minecraft.resources.ResourceLocation getFlowingTexture() {
                    return new net.minecraft.resources.ResourceLocation("minecraft:block/water_flow");
                }

                @Override
                public int getTintColor() {
                    return 0xFF6B3A1F;
                }
            });
        }
    });

    public static final RegistryObject<ForgeFlowingFluid.Source> HAZELNUT_COCOA_PASTE_SOURCE = FLUIDS.register("hazelnut_cocoa_paste",
            () -> new ForgeFlowingFluid.Source(hazelnutCocoaPasteFluidProperties()));

    public static final RegistryObject<ForgeFlowingFluid.Flowing> HAZELNUT_COCOA_PASTE_FLOWING = FLUIDS.register("hazelnut_cocoa_paste_flowing",
            () -> new ForgeFlowingFluid.Flowing(hazelnutCocoaPasteFluidProperties()));

    public static final RegistryObject<LiquidBlock> HAZELNUT_COCOA_PASTE_BLOCK = BLOCKS.register("hazelnut_cocoa_paste",
            () -> new LiquidBlock(HAZELNUT_COCOA_PASTE_SOURCE, net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()
                    .noCollission().strength(100.0F).pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)
                    .noLootTable()));

    public static final RegistryObject<Item> HAZELNUT_COCOA_PASTE_BUCKET = ITEMS.register("hazelnut_cocoa_paste_bucket",
            () -> new BucketItem(HAZELNUT_COCOA_PASTE_SOURCE, new Item.Properties().stacksTo(1).craftRemainder(net.minecraft.world.item.Items.BUCKET)));


    private static ForgeFlowingFluid.Properties hazelnutCocoaPasteFluidProperties() {
        return new ForgeFlowingFluid.Properties(HAZELNUT_COCOA_PASTE_FLUID_TYPE, HAZELNUT_COCOA_PASTE_SOURCE, HAZELNUT_COCOA_PASTE_FLOWING)
                .slopeFindDistance(2).levelDecreasePerBlock(2).tickRate(10)
                .block(HAZELNUT_COCOA_PASTE_BLOCK).bucket(HAZELNUT_COCOA_PASTE_BUCKET);
    }

    // 有机物流体（生物反应堆专用，无方块/桶，仅供流体罐存储）

    public static final RegistryObject<FluidType> ORGANIC_MATTER_FLUID_TYPE = FLUID_TYPES.register("organic_matter", () -> new FluidType(
            FluidType.Properties.create()
                    .descriptionId("fluid_type.mekck.organic_matter")
                    .density(1050)
                    .viscosity(2500)) {
        @Override
        public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
            consumer.accept(new IClientFluidTypeExtensions() {
                @Override
                public net.minecraft.resources.ResourceLocation getStillTexture() {
                    return new net.minecraft.resources.ResourceLocation("minecraft:block/water_still");
                }

                @Override
                public net.minecraft.resources.ResourceLocation getFlowingTexture() {
                    return new net.minecraft.resources.ResourceLocation("minecraft:block/water_flow");
                }

                @Override
                public int getTintColor() {
                    return 0xFF2E8B57;
                }
            });
        }
    });

    public static final RegistryObject<ForgeFlowingFluid.Source> ORGANIC_MATTER_SOURCE = FLUIDS.register("organic_matter",
            () -> new ForgeFlowingFluid.Source(organicMatterFluidProperties()));

    public static final RegistryObject<ForgeFlowingFluid.Flowing> ORGANIC_MATTER_FLOWING = FLUIDS.register("organic_matter_flowing",
            () -> new ForgeFlowingFluid.Flowing(organicMatterFluidProperties()));


    private static ForgeFlowingFluid.Properties organicMatterFluidProperties() {
        // 液体流动参数：仅作存储使用，不生成方块
        return new ForgeFlowingFluid.Properties(ORGANIC_MATTER_FLUID_TYPE, ORGANIC_MATTER_SOURCE, ORGANIC_MATTER_FLOWING)
                .slopeFindDistance(2).levelDecreasePerBlock(2).tickRate(10);
    }

    // 生物反应堆（2×2×3 多方块，物品→有机物流体→能量）

    /**
     * 创造模式「功能方块」标签页里，<b>本注册类自己的</b>条目。
     *
     * <p>原先这段清单整个写在 {@code UniversalCuttingMachine#addCreativeTabContents} 里
     * —— 81 行里交织着 6 个归属的 accept 调用。新增机器时既要改注册、
     * 还要记得回头改这个清单，而清单里没人会提醒你。</p>
     *
     * <p>调用方已确认 tab key（见 {@code UniversalCuttingMachine}）。</p>
     */
    public static void addToCreativeTab(net.minecraftforge.event.BuildCreativeModeTabContentsEvent event) {
        event.accept(HAZELNUT_COCOA_PASTE_BUCKET.get());
    }
}

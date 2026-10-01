package cn.ism.mekck.registry;

import cn.ism.mekck.item.MekCkBlockItem;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.RegistryObject;
import static cn.ism.mekck.registry.MekCkRegistries.BLOCKS;
import static cn.ism.mekck.registry.MekCkRegistries.BLOCK_ENTITIES;
import static cn.ism.mekck.registry.MekCkRegistries.ITEMS;
import static cn.ism.mekck.registry.MekCkRegistries.MENUS;

/**
 * 联动机器（其它模组配方的单体机）与它们的公共方块实体/菜单。
 *
 * <p>本类由 {@code UniversalCuttingMachine} 拆出（注册中枢拆分）。成员文本与拆分前
 * 逐字一致；本类必须<b>早于任何注册事件</b>被触碰一次 —— 见 {@link #init()}。</p>
 */
public final class MekCkLegacyMachines {

    private MekCkLegacyMachines() {
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

    public static final RegistryObject<Block> SUSHI_MAKER_BLOCK = BLOCKS.register("sushi_maker",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.SUSHI_MAKER));

    public static final RegistryObject<Item> SUSHI_MAKER_ITEM = ITEMS.register("sushi_maker",
            () -> new MekCkBlockItem(SUSHI_MAKER_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.SUSHI_MAKER.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));

    public static final RegistryObject<Block> AVERAGE_SLICER_BLOCK = BLOCKS.register("average_slicer",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.AVERAGE_SLICER));

    public static final RegistryObject<Item> AVERAGE_SLICER_ITEM = ITEMS.register("average_slicer",
            () -> new MekCkBlockItem(AVERAGE_SLICER_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.AVERAGE_SLICER.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));

    public static final RegistryObject<Block> RICE_BALL_MAKER_BLOCK = BLOCKS.register("rice_ball_maker",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.RICE_BALL_MAKER));

    public static final RegistryObject<Item> RICE_BALL_MAKER_ITEM = ITEMS.register("rice_ball_maker",
            () -> new MekCkBlockItem(RICE_BALL_MAKER_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.RICE_BALL_MAKER.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));

    public static final RegistryObject<Block> CURD_MAKER_BLOCK = BLOCKS.register("curd_maker",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.CURD_MAKER));

    public static final RegistryObject<Item> CURD_MAKER_ITEM = ITEMS.register("curd_maker",
            () -> new MekCkBlockItem(CURD_MAKER_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.CURD_MAKER.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));


    public static final RegistryObject<Block> DEHYDRATOR_BLOCK = BLOCKS.register("dehydrator",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.DEHYDRATOR));

    public static final RegistryObject<Item> DEHYDRATOR_ITEM = ITEMS.register("dehydrator",
            () -> new MekCkBlockItem(DEHYDRATOR_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.DEHYDRATOR.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));


    public static final RegistryObject<Block> FERMENTER_BLOCK = BLOCKS.register("fermenter",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.FERMENTER));

    public static final RegistryObject<Item> FERMENTER_ITEM = ITEMS.register("fermenter",
            () -> new MekCkBlockItem(FERMENTER_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.FERMENTER.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));

    public static final RegistryObject<Block> STEAMER_BLOCK = BLOCKS.register("steamer",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.STEAMER));

    public static final RegistryObject<Item> STEAMER_ITEM = ITEMS.register("steamer",
            () -> new MekCkBlockItem(STEAMER_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.STEAMER.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));


    public static final RegistryObject<Block> WINERY_BLOCK = BLOCKS.register("winery",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.WINERY));

    public static final RegistryObject<Item> WINERY_ITEM = ITEMS.register("winery",
            () -> new MekCkBlockItem(WINERY_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.WINERY.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));

    public static final RegistryObject<Block> JUICER_BLOCK = BLOCKS.register("juicer",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.JUICER));

    public static final RegistryObject<Item> JUICER_ITEM = ITEMS.register("juicer",
            () -> new MekCkBlockItem(JUICER_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.JUICER.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));

    public static final RegistryObject<Block> BAKERY_OVEN_BLOCK = BLOCKS.register("bakery_oven",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.BAKERY_OVEN));

    public static final RegistryObject<Item> BAKERY_OVEN_ITEM = ITEMS.register("bakery_oven",
            () -> new MekCkBlockItem(BAKERY_OVEN_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.BAKERY_OVEN.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));


    public static final RegistryObject<Block> STOVE_BLOCK = BLOCKS.register("stove",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.STOVE));

    public static final RegistryObject<Item> STOVE_ITEM = ITEMS.register("stove",
            () -> new MekCkBlockItem(STOVE_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.STOVE.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));

    /** 调酒机（酒馆 shaker 联动：3 个酒类/原料 → 鸡尾酒）。 */

    public static final RegistryObject<Block> COCKTAIL_SHAKER_BLOCK = BLOCKS.register("cocktail_shaker",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.COCKTAIL_SHAKER));

    public static final RegistryObject<Item> COCKTAIL_SHAKER_ITEM = ITEMS.register("cocktail_shaker",
            () -> new MekCkBlockItem(COCKTAIL_SHAKER_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.COCKTAIL_SHAKER.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));

    /** 搅拌机（烘焙坊 blender 联动：1~9 输入 → 1 输出，9 输入槽）。 */

    public static final RegistryObject<Block> BLENDER_BLOCK = BLOCKS.register("blender",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.BLENDER));

    public static final RegistryObject<Item> BLENDER_ITEM = ITEMS.register("blender",
            () -> new MekCkBlockItem(BLENDER_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.BLENDER.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));

    // ── 三个最终等级的工厂安装器（无尽贪婪材料配色，奇点创世为 16 帧变色动画） ──

    public static final RegistryObject<Block> TEA_BREWER_BLOCK = BLOCKS.register("tea_brewer",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.TEA_BREWER));

    public static final RegistryObject<Item> TEA_BREWER_ITEM = ITEMS.register("tea_brewer",
            () -> new MekCkBlockItem(TEA_BREWER_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.TEA_BREWER.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));

    /** 智能萃取机（F8/F11 §四.1）：create:mixing 流体产物（茶/咖啡/溶糖/糖浆系 + oreo 酱破例）。 */

    public static final RegistryObject<Block> SMART_EXTRACTOR_BLOCK = BLOCKS.register("smart_extractor",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.SMART_EXTRACTOR));

    public static final RegistryObject<Item> SMART_EXTRACTOR_ITEM = ITEMS.register("smart_extractor",
            () -> new MekCkBlockItem(SMART_EXTRACTOR_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.SMART_EXTRACTOR.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));

    /** 饮品调配机（F8/F11 §四.2）：读自有类型 {@code mekck:beverage_assembly}。 */

    public static final RegistryObject<Block> BEVERAGE_BLENDER_BLOCK = BLOCKS.register("beverage_blender",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.BEVERAGE_BLENDER));

    public static final RegistryObject<Item> BEVERAGE_BLENDER_ITEM = ITEMS.register("beverage_blender",
            () -> new MekCkBlockItem(BEVERAGE_BLENDER_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.BEVERAGE_BLENDER.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));

    /** 包材组装机（F7/F11 §四.4）：只读自有类型 {@code mekck:packaging}，仅产包材。 */

    public static final RegistryObject<Block> PACKAGING_STATION_BLOCK = BLOCKS.register("packaging_station",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.PACKAGING_STATION));

    public static final RegistryObject<Item> PACKAGING_STATION_ITEM = ITEMS.register("packaging_station",
            () -> new MekCkBlockItem(PACKAGING_STATION_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.PACKAGING_STATION.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));


    public static final RegistryObject<BlockEntityType<cn.ism.mekck.blockentity.SimpleMachineBlockEntity>> SIMPLE_MACHINE_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "simple_machine", () -> BlockEntityType.Builder.of(cn.ism.mekck.blockentity.SimpleMachineBlockEntity::new,
                    SUSHI_MAKER_BLOCK.get(), AVERAGE_SLICER_BLOCK.get(), RICE_BALL_MAKER_BLOCK.get(), CURD_MAKER_BLOCK.get(),
                    DEHYDRATOR_BLOCK.get(), FERMENTER_BLOCK.get(), STEAMER_BLOCK.get(),
                    WINERY_BLOCK.get(), JUICER_BLOCK.get(), BAKERY_OVEN_BLOCK.get(), STOVE_BLOCK.get(),
                    COCKTAIL_SHAKER_BLOCK.get(), BLENDER_BLOCK.get(), TEA_BREWER_BLOCK.get(),
                    SMART_EXTRACTOR_BLOCK.get(), BEVERAGE_BLENDER_BLOCK.get(), PACKAGING_STATION_BLOCK.get()).build(null));

    public static final RegistryObject<MenuType<cn.ism.mekck.menu.SimpleMachineMenu>> SIMPLE_MACHINE_MENU = MENUS.register(
            "simple_machine", () -> IForgeMenuType.create(cn.ism.mekck.menu.SimpleMachineMenu::new));

    // 冷萃升级物品（5 种）

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
        event.accept(SUSHI_MAKER_ITEM.get());
        event.accept(AVERAGE_SLICER_ITEM.get());
        event.accept(RICE_BALL_MAKER_ITEM.get());
        event.accept(CURD_MAKER_ITEM.get());
        event.accept(DEHYDRATOR_ITEM.get());
        event.accept(FERMENTER_ITEM.get());
        event.accept(STEAMER_ITEM.get());
        event.accept(WINERY_ITEM.get());
        event.accept(JUICER_ITEM.get());
        event.accept(BAKERY_OVEN_ITEM.get());
        event.accept(STOVE_ITEM.get());
        event.accept(COCKTAIL_SHAKER_ITEM.get());
        event.accept(BLENDER_ITEM.get());
        event.accept(TEA_BREWER_ITEM.get());
        event.accept(SMART_EXTRACTOR_ITEM.get());
        event.accept(BEVERAGE_BLENDER_ITEM.get());
        event.accept(PACKAGING_STATION_ITEM.get());
    }
}

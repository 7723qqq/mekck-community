package cn.ism.mekck.registry;

import cn.ism.mekck.block.BioreactorBlock;
import cn.ism.mekck.block.BioreactorBoundingBlock;
import cn.ism.mekck.block.ChocolateCannonBlock;
import cn.ism.mekck.block.IceMakerBlock;
import cn.ism.mekck.block.NutRoasterBlock;
import cn.ism.mekck.block.SkeweringMachineBlock;
import cn.ism.mekck.block.SmartCookingPotBlock;
import cn.ism.mekck.block.WineCellarBlock;
import cn.ism.mekck.blockentity.BioreactorBlockEntity;
import cn.ism.mekck.blockentity.ChocolateCannonBlockEntity;
import cn.ism.mekck.blockentity.IceMakerBlockEntity;
import cn.ism.mekck.blockentity.NutRoasterBlockEntity;
import cn.ism.mekck.blockentity.SkeweringMachineBlockEntity;
import cn.ism.mekck.blockentity.SmartCookingPotBlockEntity;
import cn.ism.mekck.blockentity.WineCellarBlockEntity;
import cn.ism.mekck.item.MekCkBlockItem;
import cn.ism.mekck.menu.BioreactorMenu;
import cn.ism.mekck.menu.ChocolateCannonMenu;
import cn.ism.mekck.menu.IceMakerMenu;
import cn.ism.mekck.menu.NutRoasterMenu;
import cn.ism.mekck.menu.SkeweringMachineMenu;
import cn.ism.mekck.menu.SmartCookingPotMenu;
import cn.ism.mekck.menu.WineCellarMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.RegistryObject;
import static cn.ism.mekck.UniversalCuttingMachine.MOD_ID;
import static cn.ism.mekck.registry.MekCkRegistries.BLOCKS;
import static cn.ism.mekck.registry.MekCkRegistries.BLOCK_ENTITIES;
import static cn.ism.mekck.registry.MekCkRegistries.ITEMS;
import static cn.ism.mekck.registry.MekCkRegistries.MENUS;

/**
 * 单机与厨房侧机器：生物反应炉、中央厨房、智能烹饪锅、穿串机、制冰机、酒窖、三明治装配台、巧克力大炮、坚果爆炒机。
 *
 * <p>本类由 {@code UniversalCuttingMachine} 拆出（注册中枢拆分）。成员文本与拆分前
 * 逐字一致；本类必须<b>早于任何注册事件</b>被触碰一次 —— 见 {@link #init()}。</p>
 */
public final class MekCkStandaloneMachines {

    private MekCkStandaloneMachines() {
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

    public static final RegistryObject<Block> ICE_MAKER_BLOCK = BLOCKS.register("ice_maker", IceMakerBlock::new);

    public static final RegistryObject<Item> ICE_MAKER_ITEM = ITEMS.register("ice_maker",
            () -> new MekCkBlockItem(ICE_MAKER_BLOCK.get(), new Item.Properties(),
                    1, IceMakerBlockEntity.ENERGY_PER_TICK, IceMakerBlockEntity.ENERGY_CAPACITY));

    public static final RegistryObject<BlockEntityType<IceMakerBlockEntity>> ICE_MAKER_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "ice_maker", () -> BlockEntityType.Builder.of(IceMakerBlockEntity::new, ICE_MAKER_BLOCK.get()).build(null));

    public static final RegistryObject<MenuType<IceMakerMenu>> ICE_MAKER_MENU = MENUS.register(
            "ice_maker", () -> IForgeMenuType.create(IceMakerMenu::new));

    // Wine Cellar (陈化窖/时间悖论产生器，F20：独立容器方块)
    //
    // 2026-10-03 迁到 Mek 体系：方块/物品/方块实体/容器全部走 Mek 的注册器
    // （与电力烧烤架 `GRILL_CONTAINER` 同款），**注册名一字不改**（仍是 mekck:wine_cellar），
    // 旧存档已放置的方块不会变空气。
    //
    // 迁移的原因见 {WineCellarMenu} 类注释：旧的 SlotItemHandler 槽 Mek 不认，
    // 屏幕只能手画一份 GuiVirtualSlot，于是同一个槽在菜单与屏幕各有一套坐标（靠 ±1 凑合）。

    public static final mekanism.common.registration.impl.ContainerTypeDeferredRegister WINE_CELLAR_CONTAINERS_REG =
            new mekanism.common.registration.impl.ContainerTypeDeferredRegister(MOD_ID);

    public static final mekanism.common.registration.impl.ContainerTypeRegistryObject<WineCellarMenu> WINE_CELLAR_CONTAINER;

    public static final mekanism.common.registration.impl.BlockDeferredRegister WINE_CELLAR_BLOCKS_REG =
            new mekanism.common.registration.impl.BlockDeferredRegister(MOD_ID);

    public static final mekanism.common.registration.impl.ItemDeferredRegister WINE_CELLAR_ITEMS_REG =
            new mekanism.common.registration.impl.ItemDeferredRegister(MOD_ID);

    public static final mekanism.common.registration.impl.TileEntityTypeDeferredRegister WINE_CELLAR_TILES_REG =
            new mekanism.common.registration.impl.TileEntityTypeDeferredRegister(MOD_ID);

    /** 方块与物品的注册句柄（{@code BlockRegistryObject} 自带 block+item，一次注册两样）。 */
    public static final mekanism.common.registration.impl.BlockRegistryObject<WineCellarBlock, MekCkBlockItem> WINE_CELLAR_HANDLE;

    /** 方块实体类型 —— 由 Mek 的注册器建，{@code BlockTile} 与 ticker 都认它。 */
    public static final mekanism.common.registration.impl.TileEntityTypeRegistryObject<WineCellarBlockEntity> WINE_CELLAR_TILE;

    static {
        // ⚠️ 顺序陷阱：BlockType 的 withGui / tileRef 参数是**延迟 Supplier**，
        // 它们只在 Mek 真正求值（放置 / 开 GUI）时才调用，但 Java 的**明确赋值**规则
        // 不允许在静态块里前向引用尚未赋值的 final 字段（实测报「可能尚未初始化变量」）。
        // 解法与 GrillBlock 同款：先用局部变量串起依赖，最后统一赋给 final 字段。
        mekanism.common.registration.impl.ContainerTypeRegistryObject<WineCellarMenu> container =
                WINE_CELLAR_CONTAINERS_REG.register(
                        "wine_cellar", WineCellarBlockEntity.class, WineCellarMenu::new);

        // BlockType 需要容器与 tile，而两者都必须先有方块 —— 用延迟 Supplier 破这个环。
        // 注意 tile 的 Supplier 同样不能前向引用 final 字段，故也走局部变量。
        java.util.concurrent.atomic.AtomicReference<mekanism.common.registration.impl.TileEntityTypeRegistryObject<WineCellarBlockEntity>> tileRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        mekanism.common.content.blocktype.BlockTypeTile<WineCellarBlockEntity> blockType =
                WineCellarBlock.blockTypeFor(() -> container, tileRef::get);

        WINE_CELLAR_HANDLE = WINE_CELLAR_BLOCKS_REG.register("wine_cellar",
                () -> new WineCellarBlock(blockType,
                        p -> p.strength(3.5F).sound(net.minecraft.world.level.block.SoundType.WOOD)
                                .requiresCorrectToolForDrops()),
                block -> new MekCkBlockItem(block, new Item.Properties()));

        // 两个 ticker 都必须显式给：getTicker(boolean) 只是原样返回存进去的那个、没有任何兜底
        // （实测字节码：ifeq 取 serverTicker / else 取 clientTicker，直接 areturn）。
        // 不填就是 null，而 Level 只在 ticker 非 null 时才驱动方块实体 —— 机器会「放着不动」。
        //
        // ⚠️ 必须传 Mek 自己的 {@code TileEntityMekanism.tickServer/tickClient}，
        // **不能**传本模组自己写的静态方法：Mek 的 tickServer 在调 {@code onUpdateServer()}
        // （偏移 97）之前还要跑 frequency / upgrade 组件、chunkloader、
        // 以及 {@code Attribute.setActive + setBlockAndUpdate} 那一段。
        // 自己写一个「只做陈化」的 ticker 会静默跳过它们 ——
        // 最直接的症状是<b>升级卡的 20 tick 安装读条永不推进</b>（卡放进去永远不生效）。
        // 本机的每 tick 逻辑在 {@code WineCellarBlockEntity#onUpdateServer}。
        WINE_CELLAR_TILE = WINE_CELLAR_TILES_REG.register(WINE_CELLAR_HANDLE,
                (pos, state) -> new WineCellarBlockEntity(WINE_CELLAR_HANDLE, pos, state),
                (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickClient(level, pos, state, tile),
                (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickServer(level, pos, state, tile));

        WINE_CELLAR_CONTAINER = container;
        tileRef.set(WINE_CELLAR_TILE);
    }

    // Central Kitchen (中央厨房：终极机器)

    public static final RegistryObject<Block> CENTRAL_KITCHEN_BLOCK = BLOCKS.register("central_kitchen",
            () -> new cn.ism.mekck.block.CentralKitchenBlock(
                    net.minecraft.world.level.block.state.BlockBehaviour.Properties
                            .copy(net.minecraft.world.level.block.Blocks.IRON_BLOCK)
                            .strength(3.5F).requiresCorrectToolForDrops().noOcclusion()));

    public static final RegistryObject<Item> CENTRAL_KITCHEN_ITEM = ITEMS.register("central_kitchen",
            () -> new MekCkBlockItem(CENTRAL_KITCHEN_BLOCK.get(), new Item.Properties(),
                    1, 0, 5_000_000));

    public static final RegistryObject<BlockEntityType<cn.ism.mekck.blockentity.CentralKitchenBlockEntity>> CENTRAL_KITCHEN_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("central_kitchen", () -> BlockEntityType.Builder.of(
                    cn.ism.mekck.blockentity.CentralKitchenBlockEntity::new, CENTRAL_KITCHEN_BLOCK.get()).build(null));

    public static final RegistryObject<MenuType<cn.ism.mekck.menu.CentralKitchenMenu>> CENTRAL_KITCHEN_MENU = MENUS.register(
            "central_kitchen", () -> IForgeMenuType.create(cn.ism.mekck.menu.CentralKitchenMenu::new));

    // 三明治组装机（联动 Some Assembly Required，单等级、无工厂版本）

    public static final RegistryObject<Block> SANDWICH_ASSEMBLER_BLOCK = BLOCKS.register("sandwich_assembler",
            cn.ism.mekck.block.SandwichAssemblerBlock::new);

    public static final RegistryObject<Item> SANDWICH_ASSEMBLER_ITEM = ITEMS.register("sandwich_assembler",
            () -> new MekCkBlockItem(SANDWICH_ASSEMBLER_BLOCK.get(), new Item.Properties(), 1, 20, 100_000));

    public static final RegistryObject<BlockEntityType<cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity>> SANDWICH_ASSEMBLER_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("sandwich_assembler", () -> BlockEntityType.Builder.of(
                    cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity::new, SANDWICH_ASSEMBLER_BLOCK.get()).build(null));

    public static final RegistryObject<MenuType<cn.ism.mekck.menu.SandwichAssemblerMenu>> SANDWICH_ASSEMBLER_MENU = MENUS.register(
            "sandwich_assembler", () -> IForgeMenuType.create(cn.ism.mekck.menu.SandwichAssemblerMenu::new));

    // 制冰工厂开关：原先是 `public static final boolean ICE_FACTORY_ENABLED = false`，
    // 现改为读 MekckConfig 的 `ice_factory.enable_ice_factory`（默认 false，行为与从前一致）。
    // 刻意**不留**一个 static 缓存字段 —— 字段初始化式在类首次加载时求值，可能早于配置文件
    // 被读取，那样加了配置也永远拿到默认值、开关形同虚设。三个使用点见下方各处。
    //
    // ⚠️ 这是**注册表开关**，不是显示开关：客户端与服务端必须配成同一个值，
    // 否则注册表同步校验会在登录时直接拒绝（详见 MekckConfig 里该配置项的注释）。
    //
    // 【勘误】原先这段注释给出的两条「不能开」的理由，第四轮逐条查证后**都不成立**：
    //   ① 「缺 12 张战利品表 ⇒ 破坏后什么都不掉」——错。制冰工厂走
    //      IceFactoryBlock.onRemove 自行掉落（machine.saveToItem(stack) + Containers.dropItemStack），
    //      且 getDrops 被覆写成 List.of()。实测破坏链路为
    //      Block.dropResources → BlockStateBase.getDrops(LootParams.Builder) →
    //      BlockBehaviour.getDrops(BlockState, LootParams.Builder)，
    //      正是被覆写的那个方法 ⇒ **战利品表根本不会被查询**，补 12 张表只会变成死文件。
    //
    //      【第六轮更正】本段原注称「data/mekck/loot_tables/blocks/ 下还有 5 张遗留机器的表
    //      ——planting_cutting_station / electric_grill / electric_grinding_machine /
    //      universal_cutting_machine / smart_skewering_machine ——同样从未生效，
    //      属于另一个待清理项」。逐条核实后：
    //        · 其中 4 张（planting_cutting_station / electric_grinding_machine /
    //          universal_cutting_machine / smart_skewering_machine）**早已被删**，
    //          目录里根本不存在；
    //        · 剩下的 electric_grill 对应的 {@code GrillBlock} **并没有**覆写 getDrops
    //          （实测 GrillBlock.java 全文无该方法），所以它那张表是**活的**，
    //          不能按死文件删掉。
    //      ⇒ 结论：loot_tables/blocks 下现有 73 张表**全部有效**，没有待清理项。
    //      （17 个方块确实覆写了 getDrops，但它们本来就没有战利品表。）
    //   ② 「byte 下标上限会静默丢存档」——错。BigStackItemHandler.serializeNBT 写的是
    //      putInt("Slot", i)，反序列用 getInt 并带 0 ≤ slot < getSlots() 越界检查。
    //      MekCkSlotNbt 那层兜底针对的是**Mek 自己的** mekanism.api.DataHandlerUtils
    //      （javap 实测 putByte/getByte），遗留 BE 路径本来就不经过它，也就没有那个病。
    // 换言之该族早已掉落与存档齐备，缺的只是一个能被翻开的开关。
    //
    // @see cn.ism.mekck.config.MekckConfig#isIceFactoryEnabled
    // @see cn.ism.mekck.block.IceFactoryBlock#onRemove
    // @see cn.ism.mekck.util.BigStackItemHandler#serializeNBT

    // Chocolate Cannon (巧克力大炮：费列罗巧克力加工 + 攻击机器，无工厂版本)

    public static final RegistryObject<Block> CHOCOLATE_CANNON_BLOCK = BLOCKS.register("chocolate_cannon", ChocolateCannonBlock::new);

    public static final RegistryObject<Item> CHOCOLATE_CANNON_ITEM = ITEMS.register("chocolate_cannon",
            () -> new MekCkBlockItem(CHOCOLATE_CANNON_BLOCK.get(), new Item.Properties(),
                    1, ChocolateCannonBlockEntity.ENERGY_PER_TICK, ChocolateCannonBlockEntity.ENERGY_CAPACITY));

    public static final RegistryObject<BlockEntityType<ChocolateCannonBlockEntity>> CHOCOLATE_CANNON_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "chocolate_cannon", () -> BlockEntityType.Builder.of(ChocolateCannonBlockEntity::new, CHOCOLATE_CANNON_BLOCK.get()).build(null));

    public static final RegistryObject<MenuType<ChocolateCannonMenu>> CHOCOLATE_CANNON_MENU = MENUS.register(
            "chocolate_cannon", () -> IForgeMenuType.create(ChocolateCannonMenu::new));

    // Nut Roaster (坚果爆炒机：炒坚果加工 + 发射炒榛子攻击)

    public static final RegistryObject<Block> NUT_ROASTER_BLOCK = BLOCKS.register("nut_roaster", NutRoasterBlock::new);

    public static final RegistryObject<Item> NUT_ROASTER_ITEM = ITEMS.register("nut_roaster",
            () -> new MekCkBlockItem(NUT_ROASTER_BLOCK.get(), new Item.Properties(),
                    1, NutRoasterBlockEntity.ENERGY_PER_TICK, NutRoasterBlockEntity.ENERGY_CAPACITY));

    public static final RegistryObject<BlockEntityType<NutRoasterBlockEntity>> NUT_ROASTER_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "nut_roaster", () -> BlockEntityType.Builder.of(NutRoasterBlockEntity::new, NUT_ROASTER_BLOCK.get()).build(null));

    public static final RegistryObject<MenuType<NutRoasterMenu>> NUT_ROASTER_MENU = MENUS.register(
            "nut_roaster", () -> IForgeMenuType.create(NutRoasterMenu::new));

    // ── 四合一基础机器（本轮新增，均无工厂版本）────────────────────────────

    public static final RegistryObject<Block> BIOREACTOR_BLOCK = BLOCKS.register("bioreactor", BioreactorBlock::new);

    public static final RegistryObject<Item> BIOREACTOR_ITEM = ITEMS.register("bioreactor",
            () -> new cn.ism.mekck.item.BioreactorBlockItem(BIOREACTOR_BLOCK.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<BioreactorBlockEntity>> BIOREACTOR_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "bioreactor",
            () -> BlockEntityType.Builder.of(BioreactorBlockEntity::new, BIOREACTOR_BLOCK.get()).build(null));
    // 生物反应堆的绑定块（2×2×3 其余 11 格）：专用类，碰撞/拾取外形为整块立方体，保证不可穿模

    public static final RegistryObject<Block> BIOREACTOR_BOUNDING_BLOCK = BLOCKS.register("bioreactor_bounding", BioreactorBoundingBlock::new);

    public static final RegistryObject<MenuType<BioreactorMenu>> BIOREACTOR_MENU = MENUS.register(
            "bioreactor", () -> IForgeMenuType.create(BioreactorMenu::new));

    // Smart Cooking Pot

    public static final RegistryObject<Block> COOKING_POT_BLOCK = BLOCKS.register("smart_cooking_pot", SmartCookingPotBlock::new);

    public static final RegistryObject<Item> COOKING_POT_ITEM = ITEMS.register("smart_cooking_pot",
            () -> new MekCkBlockItem(COOKING_POT_BLOCK.get(), new Item.Properties(), 1, 20, 100000, true));

    public static final RegistryObject<BlockEntityType<SmartCookingPotBlockEntity>> COOKING_POT_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "smart_cooking_pot",
            () -> BlockEntityType.Builder.of(SmartCookingPotBlockEntity::new, COOKING_POT_BLOCK.get()).build(null));

    public static final RegistryObject<MenuType<SmartCookingPotMenu>> COOKING_POT_MENU = MENUS.register(
            "smart_cooking_pot", () -> IForgeMenuType.create(SmartCookingPotMenu::new));

    // Smart Skewering Machine

    public static final RegistryObject<Block> SKEWERING_MACHINE_BLOCK = BLOCKS.register("smart_skewering_machine", SkeweringMachineBlock::new);

    public static final RegistryObject<Item> SKEWERING_MACHINE_ITEM = ITEMS.register("smart_skewering_machine",
            () -> new MekCkBlockItem(SKEWERING_MACHINE_BLOCK.get(), new Item.Properties(), 1, 20, 100000, true));

    public static final RegistryObject<BlockEntityType<SkeweringMachineBlockEntity>> SKEWERING_MACHINE_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "smart_skewering_machine",
            () -> BlockEntityType.Builder.of(SkeweringMachineBlockEntity::new, SKEWERING_MACHINE_BLOCK.get()).build(null));

    public static final RegistryObject<MenuType<SkeweringMachineMenu>> SKEWERING_MACHINE_MENU = MENUS.register(
            "smart_skewering_machine", () -> IForgeMenuType.create(SkeweringMachineMenu::new));

    // Electric Grill
    // 阶段 3：改走 Mek 的注册器，**注册名一字不改**（仍是 mekck:electric_grill），
    // 旧存档里已放置的方块因此不会变空气。

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
        event.accept(COOKING_POT_ITEM.get());
        event.accept(SKEWERING_MACHINE_ITEM.get());
        event.accept(BIOREACTOR_ITEM.get());
        event.accept(ICE_MAKER_ITEM.get());
        event.accept(WINE_CELLAR_HANDLE.getItemStack());
        event.accept(CENTRAL_KITCHEN_ITEM.get());
        event.accept(SANDWICH_ASSEMBLER_ITEM.get());
        event.accept(CHOCOLATE_CANNON_ITEM.get());
        event.accept(NUT_ROASTER_ITEM.get());
    }
}

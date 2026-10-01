package cn.ism.mekck.registry;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.block.CookingFactoryBlock;
import cn.ism.mekck.block.CuttingMachineFactoryBlock;
import cn.ism.mekck.block.ElectricGrindingMachineBlock;
import cn.ism.mekck.block.GrillBlock;
import cn.ism.mekck.block.GrillFactoryBlock;
import cn.ism.mekck.block.GrindingFactoryBlock;
import cn.ism.mekck.block.IceFactoryBlock;
import cn.ism.mekck.block.PlantingCuttingFactoryBlock;
import cn.ism.mekck.block.PlantingCuttingStationBlock;
import cn.ism.mekck.block.SkeweringFactoryBlock;
import cn.ism.mekck.block.UniversalCuttingMachineBlock;
import cn.ism.mekck.blockentity.ElectricGrindingMachineBlockEntity;
import cn.ism.mekck.blockentity.GrillBlockEntity;
import cn.ism.mekck.blockentity.IceFactoryBlockEntity;
import cn.ism.mekck.blockentity.PlantingCuttingStationBlockEntity;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.item.MekCkBlockItem;
import cn.ism.mekck.menu.CookingFactoryMenu;
import cn.ism.mekck.menu.CuttingMachineFactoryMenu;
import cn.ism.mekck.menu.ElectricGrindingMachineMenu;
import cn.ism.mekck.menu.GrillFactoryMenu;
import cn.ism.mekck.menu.GrillMenu;
import cn.ism.mekck.menu.GrindingFactoryMenu;
import cn.ism.mekck.menu.IceFactoryMenu;
import cn.ism.mekck.menu.PlantingCuttingFactoryMenu;
import cn.ism.mekck.menu.PlantingCuttingStationMenu;
import cn.ism.mekck.menu.SkeweringFactoryMenu;
import cn.ism.mekck.menu.UniversalCuttingMachineMenu;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import static cn.ism.mekck.UniversalCuttingMachine.MOD_ID;
import static cn.ism.mekck.registry.MekCkRegistries.BLOCKS;
import static cn.ism.mekck.registry.MekCkRegistries.BLOCK_ENTITIES;
import static cn.ism.mekck.registry.MekCkRegistries.ENTITY_TYPES;
import static cn.ism.mekck.registry.MekCkRegistries.ITEMS;
import static cn.ism.mekck.registry.MekCkRegistries.MENUS;

/**
 * 各家族的 12 级工厂：方块 / 物品 / tile / 容器的注册，以及把句柄装进各家族 map 的静态块。
 *
 * <p>本类由 {@code UniversalCuttingMachine} 拆出（注册中枢拆分）。成员文本与拆分前
 * 逐字一致；本类必须<b>早于任何注册事件</b>被触碰一次 —— 见 {@link #init()}。</p>
 */
public final class MekCkFactories {

    private MekCkFactories() {
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

    public static final mekanism.common.registration.impl.BlockDeferredRegister MACHINE_BLOCKS_REG =
            new mekanism.common.registration.impl.BlockDeferredRegister(MOD_ID);

    public static final mekanism.common.registration.impl.TileEntityTypeDeferredRegister MACHINE_TILES_REG =
            new mekanism.common.registration.impl.TileEntityTypeDeferredRegister(MOD_ID);

    public static final mekanism.common.registration.impl.ContainerTypeDeferredRegister MACHINE_CONTAINERS_REG =
            new mekanism.common.registration.impl.ContainerTypeDeferredRegister(MOD_ID);

    /** 已注册的切菜机方块（Mek 体系下的真实句柄）。 */

    public static final mekanism.common.registration.impl.BlockRegistryObject<UniversalCuttingMachineBlock, MekCkBlockItem> MACHINE_HANDLE;
    /** 已注册的切菜机 tile 类型，供 BlockType 的延迟 Supplier 回查。 */

    public static final mekanism.common.registration.impl.TileEntityTypeRegistryObject<cn.ism.mekck.machine.cutting.UniversalCuttingMachineTile> MACHINE_TILE;
    /** 切菜机容器类型（注册名与旧值逐字相同）。 */

    public static final mekanism.common.registration.impl.ContainerTypeRegistryObject<UniversalCuttingMachineMenu> MACHINE_CONTAINER;

    /** 方块属性：Mek 的 BlockTile 要 UnaryOperator<Properties>，这里原样透传。 */

    public static final java.util.function.UnaryOperator<net.minecraft.world.level.block.state.BlockBehaviour.Properties> MACHINE_PROPERTIES =
            p -> p.strength(3.5F).sound(net.minecraft.world.level.block.SoundType.METAL)
                    .requiresCorrectToolForDrops();

    /** 兼容面：仍按旧类型读方块/物品的地方（JEI 等）。 */

    public static final RegistryObject<Block> MACHINE_BLOCK;

    public static final RegistryObject<Item> MACHINE_ITEM;
    /** 客户端屏幕绑定用。容器本身已改走 MACHINE_CONTAINER（Mek 体系），
     *  这里的 MENUS 注册只用于让 {@code UniversalCuttingMachineScreen} 拿到 MenuType。 */

    public static final RegistryObject<MenuType<UniversalCuttingMachineMenu>> MACHINE_MENU =
            MENUS.register("universal_cutting_machine",
                    () -> IForgeMenuType.create(
                            (int id, net.minecraft.world.entity.player.Inventory inv,
                                    net.minecraft.network.FriendlyByteBuf buf) -> new UniversalCuttingMachineMenu(
                                            id, inv,
                                            (cn.ism.mekck.machine.cutting.UniversalCuttingMachineTile) inv.player.level().getBlockEntity(buf.readBlockPos()))));

    // Electric Grinding Machine (basic machine, processes kaleidoscope_cookery millstone recipes)

    public static final RegistryObject<Block> GRINDING_MACHINE_BLOCK = BLOCKS.register("electric_grinding_machine", ElectricGrindingMachineBlock::new);

    public static final RegistryObject<Item> GRINDING_MACHINE_ITEM = ITEMS.register("electric_grinding_machine",
            () -> new MekCkBlockItem(GRINDING_MACHINE_BLOCK.get(), new Item.Properties(),
                    1, ElectricGrindingMachineBlockEntity.ENERGY_PER_TICK, ElectricGrindingMachineBlockEntity.ENERGY_CAPACITY));

    public static final RegistryObject<BlockEntityType<ElectricGrindingMachineBlockEntity>> GRINDING_MACHINE_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "electric_grinding_machine",
            () -> BlockEntityType.Builder.of(ElectricGrindingMachineBlockEntity::new, GRINDING_MACHINE_BLOCK.get()).build(null));

    public static final RegistryObject<MenuType<ElectricGrindingMachineMenu>> GRINDING_MACHINE_MENU = MENUS.register(
            "electric_grinding_machine", () -> IForgeMenuType.create(ElectricGrindingMachineMenu::new));

    // Planting & Cutting Station

    public static final RegistryObject<Block> PLANTING_CUTTING_STATION_BLOCK = BLOCKS.register("planting_cutting_station", PlantingCuttingStationBlock::new);

    public static final RegistryObject<Item> PLANTING_CUTTING_STATION_ITEM = ITEMS.register("planting_cutting_station",
            () -> new MekCkBlockItem(PLANTING_CUTTING_STATION_BLOCK.get(), new Item.Properties(),
                    1, PlantingCuttingStationBlockEntity.ENERGY_PER_TICK, PlantingCuttingStationBlockEntity.ENERGY_CAPACITY));

    public static final RegistryObject<BlockEntityType<PlantingCuttingStationBlockEntity>> PLANTING_CUTTING_STATION_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "planting_cutting_station",
            () -> BlockEntityType.Builder.of(PlantingCuttingStationBlockEntity::new, PLANTING_CUTTING_STATION_BLOCK.get()).build(null));

    public static final RegistryObject<MenuType<PlantingCuttingStationMenu>> PLANTING_CUTTING_STATION_MENU = MENUS.register(
            "planting_cutting_station", () -> IForgeMenuType.create(PlantingCuttingStationMenu::new));

    // Ice Maker (急冻制冰机)

    public static final mekanism.common.registration.impl.BlockDeferredRegister GRILL_BLOCKS_REG =
            new mekanism.common.registration.impl.BlockDeferredRegister(MOD_ID);

    public static final mekanism.common.registration.impl.TileEntityTypeDeferredRegister GRILL_TILES_REG =
            new mekanism.common.registration.impl.TileEntityTypeDeferredRegister(MOD_ID);

    public static final mekanism.common.registration.impl.ContainerTypeDeferredRegister GRILL_CONTAINERS_REG =
            new mekanism.common.registration.impl.ContainerTypeDeferredRegister(MOD_ID);

    /** 已注册的烧烤架方块（Mek 体系下的真实句柄）。 */

    public static final mekanism.common.registration.impl.BlockRegistryObject<GrillBlock, MekCkBlockItem> GRILL_HANDLE;
    /** 已注册的烧烤架 tile 类型，供 BlockType 的延迟 Supplier 回查。 */

    public static final mekanism.common.registration.impl.TileEntityTypeRegistryObject<GrillBlockEntity> GRILL_TILE;
    /** 烧烤架容器类型（注册名与旧的 mekck:electric_grill 逐字相同）。 */

    public static final mekanism.common.registration.impl.ContainerTypeRegistryObject<GrillMenu> GRILL_CONTAINER;

    /**
     * 兼容面：{@code JEIPlugin} 与本类 1618 行按旧类型 {@code RegistryObject} 读这两个字段，
     * {@code registryView} 让它们一行都不用改。
     */

    public static final RegistryObject<Block> GRILL_BLOCK;

    public static final RegistryObject<Item> GRILL_ITEM;


    private static final UnaryOperator<BlockBehaviour.Properties> GRILL_PROPERTIES =
            props -> props.sound(net.minecraft.world.level.block.SoundType.METAL);

    // Cooking Factory blocks, items, and block entities
    // Cooking Factory：阶段 3 Task 7 改走 Mek 的注册器，
    // **注册名一字不改**（仍是 mekck:<tier>_cooking_factory）。

    public static final mekanism.common.registration.impl.BlockDeferredRegister COOKING_FACTORY_BLOCKS_REG =
            new mekanism.common.registration.impl.BlockDeferredRegister(MOD_ID);

    public static final mekanism.common.registration.impl.TileEntityTypeDeferredRegister COOKING_FACTORY_TILES_REG =
            new mekanism.common.registration.impl.TileEntityTypeDeferredRegister(MOD_ID);

    public static final mekanism.common.registration.impl.ContainerTypeDeferredRegister COOKING_FACTORY_CONTAINERS_REG =
            new mekanism.common.registration.impl.ContainerTypeDeferredRegister(MOD_ID);

    /** 已注册的烹饪方块（按等级索引），Mek 体系下的真实句柄。 */

    public static final Map<CuttingMachineFactoryTier,
            mekanism.common.registration.impl.BlockRegistryObject<CookingFactoryBlock, MekCkBlockItem>> COOKING_FACTORY_HANDLES =
            new LinkedHashMap<>();
    /** 已注册的 tile 类型（按等级索引），供 BlockType 的延迟 Supplier 回查。 */

    public static final Map<CuttingMachineFactoryTier,
            mekanism.common.registration.impl.TileEntityTypeRegistryObject<cn.ism.mekck.machine.cooking.CookingFactoryTile>> COOKING_FACTORY_TILES =
            new LinkedHashMap<>();
    /** 兼容面：外部文件按旧类型读这两个 map，{@code registryView} 让它们一行都不用改。 */

    public static final Map<CuttingMachineFactoryTier, RegistryObject<Block>> COOKING_FACTORY_BLOCKS = new LinkedHashMap<>();

    public static final Map<CuttingMachineFactoryTier, RegistryObject<Item>> COOKING_FACTORY_ITEMS = new LinkedHashMap<>();

    /** 12 个等级共用一个容器类型（注册名与旧的 mekck:cooking_factory 逐字相同）。 */

    public static final mekanism.common.registration.impl.ContainerTypeRegistryObject<CookingFactoryMenu> COOKING_FACTORY_CONTAINER;


    private static final UnaryOperator<BlockBehaviour.Properties> COOKING_FACTORY_PROPERTIES =
            props -> props.sound(net.minecraft.world.level.block.SoundType.METAL);

    // Skewering Factory blocks, items, and block entities
    // Skewering Factory：阶段 3 Task 5 改走 Mek 的注册器，
    // **注册名一字不改**（仍是 mekck:<tier>_skewering_factory）。

    public static final mekanism.common.registration.impl.BlockDeferredRegister SKEWERING_FACTORY_BLOCKS_REG =
            new mekanism.common.registration.impl.BlockDeferredRegister(MOD_ID);

    public static final mekanism.common.registration.impl.TileEntityTypeDeferredRegister SKEWERING_FACTORY_TILES_REG =
            new mekanism.common.registration.impl.TileEntityTypeDeferredRegister(MOD_ID);

    public static final mekanism.common.registration.impl.ContainerTypeDeferredRegister SKEWERING_FACTORY_CONTAINERS_REG =
            new mekanism.common.registration.impl.ContainerTypeDeferredRegister(MOD_ID);

    /** 已注册的穿串方块（按等级索引），Mek 体系下的真实句柄。 */

    public static final Map<CuttingMachineFactoryTier,
            mekanism.common.registration.impl.BlockRegistryObject<SkeweringFactoryBlock, MekCkBlockItem>> SKEWERING_FACTORY_HANDLES =
            new LinkedHashMap<>();
    /** 已注册的 tile 类型（按等级索引），供 BlockType 的延迟 Supplier 回查。 */

    public static final Map<CuttingMachineFactoryTier,
            mekanism.common.registration.impl.TileEntityTypeRegistryObject<cn.ism.mekck.machine.skewering.SkeweringFactoryTile>> SKEWERING_FACTORY_TILES =
            new LinkedHashMap<>();
    /** 兼容面：外部文件按旧类型读这两个 map，{@code registryView} 让它们一行都不用改。 */

    public static final Map<CuttingMachineFactoryTier, RegistryObject<Block>> SKEWERING_FACTORY_BLOCKS = new LinkedHashMap<>();

    public static final Map<CuttingMachineFactoryTier, RegistryObject<Item>> SKEWERING_FACTORY_ITEMS = new LinkedHashMap<>();

    /** 12 个等级共用一个容器类型（注册名与旧的 mekck:skewering_factory 逐字相同）。 */

    public static final mekanism.common.registration.impl.ContainerTypeRegistryObject<SkeweringFactoryMenu> SKEWERING_FACTORY_CONTAINER;


    private static final UnaryOperator<BlockBehaviour.Properties> SKEWERING_FACTORY_PROPERTIES =
            props -> props.sound(net.minecraft.world.level.block.SoundType.METAL);

    // Grill Factory blocks, items, and block entities
    // 阶段 3 Task 3：改走 Mek 的注册器，**注册名一字不改**（仍是 mekck:<tier>_grill_factory），
    // 旧存档里已放置的方块因此不会变空气。

    public static final mekanism.common.registration.impl.BlockDeferredRegister GRILL_FACTORY_BLOCKS_REG =
            new mekanism.common.registration.impl.BlockDeferredRegister(MOD_ID);

    public static final mekanism.common.registration.impl.TileEntityTypeDeferredRegister GRILL_FACTORY_TILES_REG =
            new mekanism.common.registration.impl.TileEntityTypeDeferredRegister(MOD_ID);

    public static final mekanism.common.registration.impl.ContainerTypeDeferredRegister GRILL_FACTORY_CONTAINERS_REG =
            new mekanism.common.registration.impl.ContainerTypeDeferredRegister(MOD_ID);

    /** 已注册的烧烤方块（按等级索引），Mek 体系下的真实句柄。 */

    public static final Map<CuttingMachineFactoryTier,
            mekanism.common.registration.impl.BlockRegistryObject<GrillFactoryBlock, MekCkBlockItem>> GRILL_FACTORY_HANDLES =
            new LinkedHashMap<>();
    /** 已注册的 tile 类型（按等级索引），供 BlockType 的延迟 Supplier 回查。 */

    public static final Map<CuttingMachineFactoryTier,
            mekanism.common.registration.impl.TileEntityTypeRegistryObject<cn.ism.mekck.machine.grill.GrillFactoryTile>> GRILL_FACTORY_TILES =
            new LinkedHashMap<>();
    /**
     * 兼容面：本任务之外的文件按旧类型 {@code RegistryObject} 读这两个 map，
     * {@code registryView} 让外部一行都不用改。
     */

    public static final Map<CuttingMachineFactoryTier, RegistryObject<Block>> GRILL_FACTORY_BLOCKS = new LinkedHashMap<>();

    public static final Map<CuttingMachineFactoryTier, RegistryObject<Item>> GRILL_FACTORY_ITEMS = new LinkedHashMap<>();

    /** 12 个等级共用一个容器类型（注册名与旧的 mekck:grill_factory 逐字相同）。 */

    public static final mekanism.common.registration.impl.ContainerTypeRegistryObject<GrillFactoryMenu> GRILL_FACTORY_CONTAINER;


    private static final UnaryOperator<BlockBehaviour.Properties> GRILL_FACTORY_PROPERTIES =
            props -> props.sound(net.minecraft.world.level.block.SoundType.METAL);

    // Planting & Cutting Factory blocks, items, and block entities

    public static final mekanism.common.registration.impl.BlockDeferredRegister PLANTING_CUTTING_FACTORY_BLOCKS_REG =
            new mekanism.common.registration.impl.BlockDeferredRegister(MOD_ID);

    public static final mekanism.common.registration.impl.TileEntityTypeDeferredRegister PLANTING_CUTTING_FACTORY_TILES_REG =
            new mekanism.common.registration.impl.TileEntityTypeDeferredRegister(MOD_ID);

    public static final mekanism.common.registration.impl.ContainerTypeDeferredRegister PLANTING_CUTTING_FACTORY_CONTAINERS_REG =
            new mekanism.common.registration.impl.ContainerTypeDeferredRegister(MOD_ID);

    /** 已注册的种植切配方块（按等级索引），Mek 体系下的真实句柄。 */

    public static final Map<CuttingMachineFactoryTier,
            mekanism.common.registration.impl.BlockRegistryObject<PlantingCuttingFactoryBlock, MekCkBlockItem>> PLANTING_CUTTING_FACTORY_HANDLES =
            new LinkedHashMap<>();
    /** 已注册的 tile 类型（按等级索引），供 BlockType 的延迟 Supplier 回查。 */

    public static final Map<CuttingMachineFactoryTier,
            mekanism.common.registration.impl.TileEntityTypeRegistryObject<cn.ism.mekck.machine.plantingcutting.PlantingCuttingFactoryTile>> PLANTING_CUTTING_FACTORY_TILES =
            new LinkedHashMap<>();
    /**
     * 已注册的方块 / 物品（按等级索引）。
     *
     * <p><b>兼容面</b>：Mek 的注册器产出的是自己的
     * {@code BlockRegistryObject}，而本任务之外的文件（{@code MekckAe2} /
     * {@code JEIPlugin} / {@code MekCkBlockItem}）仍按原版 {@code RegistryObject}
     * 读这两个 map。用 {@code registryView} 造一个指向同一注册项的视图，
     * 这样外部文件一行都不用改。
     */

    public static final Map<CuttingMachineFactoryTier, RegistryObject<Block>> PLANTING_CUTTING_FACTORY_BLOCKS = new LinkedHashMap<>();

    public static final Map<CuttingMachineFactoryTier, RegistryObject<Item>> PLANTING_CUTTING_FACTORY_ITEMS = new LinkedHashMap<>();

    /** 12 个等级共用一个容器类型（注册名与旧的 mekck:planting_cutting_factory 逐字相同）。 */

    public static final mekanism.common.registration.impl.ContainerTypeRegistryObject<PlantingCuttingFactoryMenu> PLANTING_CUTTING_CONTAINER;


    private static final UnaryOperator<BlockBehaviour.Properties> PLANTING_CUTTING_FACTORY_PROPERTIES =
            props -> props.sound(net.minecraft.world.level.block.SoundType.METAL);

    // Planting & Cutting Factory block entity references


    // Grill Factory block entity references
    // 旧的 11 个 *_GRILL_FACTORY_BLOCK_ENTITY 字段与 GRILL_FACTORY_BLOCK_ENTITIES 已删
    // （阶段 3 Task 3）：它们强绑已删的 GrillFactoryBlockEntity，同一个注册名只能挂一个
    // BlockEntityType。活的 tile 句柄在 GRILL_FACTORY_TILES。

    // Skewering Factory block entity references
    // 穿串工厂的旧 BE 字段与 SKEWERING_FACTORY_BLOCK_ENTITIES 已删（阶段 3 Task 5）：
    // 它们强绑已删的 SkeweringFactoryBlockEntity，同一个注册名只能挂一个 BlockEntityType。
    // 活的 tile 句柄在 SKEWERING_FACTORY_TILES。

    // Factory blocks, items, and block entities
    //
    // ⚠️ 切菜工厂的**实际注册**在下面 CUTTING_FACTORY_*_REG 那一组（Mek 的 BlockDeferredRegister /
    //    TileEntityTypeDeferredRegister / ContainerTypeDeferredRegister），注册 ID 不变
    //    （仍是 mekck:<tier>_cutting_factory），因此旧存档里已放置的方块不会变成空气。
    //    下面这两个 map 保留旧类型不变，是**给本任务之外的文件用的兼容面**：
    //    TierInstallerHandler 与 JEIPlugin 一律按 Map<..., RegistryObject<Block>> 遍历读取。
    //    它们现在装的是 MekCkRegistrySupport.registryView(...) 造出来的同名注册项视图，语义与原来完全一致。
    //
    // ⚠️ 这里**没有** 11 个 BASIC_FACTORY_BLOCK / ADVANCED_FACTORY_BLOCK 之类的逐档别名字段。
    //    它们此前存在且恰好只有 11 个（唯独没有 BLAZE），于是 JEPlugin 的切菜催化剂注册
    //    顺着手写成了 11 行 —— 烈焰等级的切菜工厂在 JEI 里查不到。别名字段一旦没人读，
    //    留着就只会诱导下一个人再手写一遍档位清单。要单档引用请写
    //    `FACTORY_BLOCKS.get(CuttingMachineFactoryTier.XXX)`。

    public static final Map<CuttingMachineFactoryTier, RegistryObject<Block>> FACTORY_BLOCKS = new LinkedHashMap<>();

    public static final Map<CuttingMachineFactoryTier, RegistryObject<Item>> FACTORY_ITEMS = new LinkedHashMap<>();

    // 切菜工厂原先的 11 个 *_FACTORY_BLOCK_ENTITY 字段与 FACTORY_BLOCK_ENTITIES 集合，
    // 已随 blockentity/CuttingMachineFactoryBlockEntity 于阶段 2 Task 5 一并删除：
    // 它们恒为 null（同一个注册名 mekck:<tier>_cutting_factory 只能挂一个 BlockEntityType，
    // 旧的早已被 CuttingFactoryTile 顶替），留着等于永久静默的 null。活的 tile 句柄在下面的
    // CUTTING_FACTORY_TILES。
    //
    // 「11 个」不是笔误：档位枚举有 12 个值，但 BLAZE 从来就没有对应的 *_FACTORY_BLOCK_ENTITY
    // 字段（删除前实测如此），Task 5 不新增也不补齐。
    //
    // ⚠️ 不要按名字相似去删其余家族的 *_FACTORY_BLOCK_ENTITY：本类现存 55 个这类字段，
    //    全部仍被其余 6 个家族的旧 BlockEntity 真实使用（各 XxxFactoryBlock.getTileType
    //    与 XxxFactoryBlockEntity 构造都按名读它们），删任何一个都会编译断。

    // 逐档别名字段（BASIC_FACTORY_BLOCK / … / SINGULARITY_FACTORY_ITEM 共 22 个）已删除：
    // 实测全仓零读取方，且恰好缺 BLAZE 一档，是 JEI 切菜催化剂漏档的直接诱因。
    // 需要单档引用时写 FACTORY_BLOCKS.get(CuttingMachineFactoryTier.XXX)。


    public static final RegistryObject<MenuType<CuttingMachineFactoryMenu>> FACTORY_MENU;

    // ── 切菜工厂的 Mek 原生注册（阶段 2 Task 4）──────────────────────────
    //
    // 为什么单独一组注册而不是复用 Mekck 自己的 DeferredRegister：
    //   * BlockDeferredRegister 会自动挂一个 BlockItem，而切菜工厂的方块物品是
    //     MekCkBlockItem（带等级 tooltip 与 saveToItem），走 register(id, supplier, itemFn) 覆盖；
    //   * TileEntityTypeDeferredRegister 建的是 TileEntityMekanism 的注册对象，
    //     CUTTING_FACTORY_TILES 供 BlockType 的延迟 Supplier 回查；
    //   * ContainerTypeDeferredRegister 注册进同一个 MENU_TYPES 注册表，
    //     所以下面 MekCkRegistrySupport.registryView("factory") 造出来的 FACTORY_MENU 视图指向的就是它，
    //     注册名 mekck:factory 与旧实现逐字相同。


    public static final mekanism.common.registration.impl.BlockDeferredRegister CUTTING_FACTORY_BLOCKS_REG =
            new mekanism.common.registration.impl.BlockDeferredRegister(MOD_ID);

    public static final mekanism.common.registration.impl.TileEntityTypeDeferredRegister CUTTING_FACTORY_TILES_REG =
            new mekanism.common.registration.impl.TileEntityTypeDeferredRegister(MOD_ID);

    public static final mekanism.common.registration.impl.ContainerTypeDeferredRegister CUTTING_FACTORY_CONTAINERS_REG =
            new mekanism.common.registration.impl.ContainerTypeDeferredRegister(MOD_ID);

    /** 已注册的切菜方块（按等级索引），Mek 体系下的真实句柄。 */

    public static final Map<CuttingMachineFactoryTier,
            mekanism.common.registration.impl.BlockRegistryObject<CuttingMachineFactoryBlock, MekCkBlockItem>> CUTTING_FACTORY_HANDLES =
            new LinkedHashMap<>();
    /** 已注册的切菜 tile 类型（按等级索引），供 BlockType 的延迟 Supplier 回查。 */

    public static final Map<CuttingMachineFactoryTier,
            mekanism.common.registration.impl.TileEntityTypeRegistryObject<cn.ism.mekck.machine.cutting.CuttingFactoryTile>> CUTTING_FACTORY_TILES =
            new LinkedHashMap<>();

    /** 12 个等级共用一个容器类型（与旧的 {@code mekck:factory} 同名）。 */

    public static final mekanism.common.registration.impl.ContainerTypeRegistryObject<CuttingMachineFactoryMenu> FACTORY_CONTAINER;

    /**
     * 切菜方块的属性微调。
     *
     * <p>{@code BlockTile} 的构造器已经铺好了
     * {@code Properties.of().strength(3.5F, 16.0F).requiresCorrectToolForDrops()}
     * （实测 {@code BlockTile} 构造器字节码偏移 3~16），因此这里只需补回旧实现独有的一项
     * {@code sound(METAL)}——丢失它会让挖/放的声音变成默认的石头声。</p>
     *
     * <p><b>必须声明在下方 static 块之前</b>：静态字段初始化器与 static 块按<b>文本顺序</b>执行，
     * 在 static 块里引用一个声明在它之后的 static final 字段会编译报「非法前向引用」。</p>
     */

    private static final UnaryOperator<BlockBehaviour.Properties> CUTTING_FACTORY_PROPERTIES =
            props -> props.sound(net.minecraft.world.level.block.SoundType.METAL);

    // Grinding Factory blocks, items, and block entities
    //
    // ── 研磨工厂的 Mek 原生注册（阶段 3 Task 1）───────────────────────────────
    // 与切菜工厂同一套做法：注册名不变（仍是 mekck:<tier>_grinding_factory），
    // 变的是方块与 tile 的实现类。旧存档的内容由 MekCkLegacyMachineNbt
    // 在读档时整体翻译，新存档由 MekCkMachineTile 写出。

    public static final mekanism.common.registration.impl.BlockDeferredRegister GRINDING_FACTORY_BLOCKS_REG =
            new mekanism.common.registration.impl.BlockDeferredRegister(MOD_ID);

    public static final mekanism.common.registration.impl.TileEntityTypeDeferredRegister GRINDING_FACTORY_TILES_REG =
            new mekanism.common.registration.impl.TileEntityTypeDeferredRegister(MOD_ID);

    public static final mekanism.common.registration.impl.ContainerTypeDeferredRegister GRINDING_FACTORY_CONTAINERS_REG =
            new mekanism.common.registration.impl.ContainerTypeDeferredRegister(MOD_ID);

    /** 已注册的研磨方块（按等级索引），Mek 体系下的真实句柄。 */

    public static final Map<CuttingMachineFactoryTier,
            mekanism.common.registration.impl.BlockRegistryObject<GrindingFactoryBlock, MekCkBlockItem>> GRINDING_FACTORY_HANDLES =
            new LinkedHashMap<>();
    /** 已注册的研磨 tile 类型（按等级索引），供 BlockType 的延迟 Supplier 回查。 */

    public static final Map<CuttingMachineFactoryTier,
            mekanism.common.registration.impl.TileEntityTypeRegistryObject<cn.ism.mekck.machine.grinding.GrindingFactoryTile>> GRINDING_FACTORY_TILES =
            new LinkedHashMap<>();

    /** 12 个等级共用一个容器类型（注册名仍是 mekck:grinding_factory）。 */

    public static final mekanism.common.registration.impl.ContainerTypeRegistryObject<GrindingFactoryMenu> GRINDING_FACTORY_CONTAINER;

    /** 研磨方块的属性微调：补回旧实现独有的 sound(METAL)。 */

    private static final UnaryOperator<BlockBehaviour.Properties> GRINDING_FACTORY_PROPERTIES =
            props -> props.sound(net.minecraft.world.level.block.SoundType.METAL);

    // 兼容面：本任务之外的文件（TierInstallerHandler / JEIPlugin / ClientEvents）
    // 按旧类型 Map<..., RegistryObject<Block>> 读这两个 map。

    public static final Map<CuttingMachineFactoryTier, RegistryObject<Block>> GRINDING_FACTORY_BLOCKS = new LinkedHashMap<>();

    public static final Map<CuttingMachineFactoryTier, RegistryObject<Item>> GRINDING_FACTORY_ITEMS = new LinkedHashMap<>();

    // 旧的 11 个 *_GRINDING_FACTORY_BLOCK_ENTITY 字段与 GRINDING_FACTORY_BLOCK_ENTITIES 已删（阶段 3 Task 1）：
    // 它们强绑已删的 GrindingFactoryBlockEntity，同一个注册名只能挂一个 BlockEntityType。
    // 活的 tile 句柄在 GRINDING_FACTORY_TILES。

    public static final RegistryObject<MenuType<GrindingFactoryMenu>> GRINDING_FACTORY_MENU;


    public static final java.util.Map<CuttingMachineFactoryTier, RegistryObject<Block>> ICE_FACTORY_BLOCKS = new java.util.EnumMap<>(CuttingMachineFactoryTier.class);

    public static final java.util.Map<CuttingMachineFactoryTier, RegistryObject<Item>> ICE_FACTORY_ITEMS = new java.util.EnumMap<>(CuttingMachineFactoryTier.class);

    public static final java.util.Map<CuttingMachineFactoryTier, RegistryObject<BlockEntityType<IceFactoryBlockEntity>>> ICE_FACTORY_BLOCK_ENTITIES = new java.util.EnumMap<>(CuttingMachineFactoryTier.class);

    /**
     * 制冰工厂菜单类型 —— <b>在 {@link MekckConfig#isIceFactoryEnabled()} 为 false 时为 {@code null}</b>。
     *
     * <p>这个 {@code null} 是<b>刻意保留的空哨兵</b>，不是「忘了赋值」：整族禁用时
     * 12 个方块与方块实体都不注册，若仍注册一个 {@code MenuType}，
     * 就会往注册表里塞一个没有任何方块能打开的条目 —— 而本开关的语义是
     * 「关掉整族」，注册表里就不该留残迹。</p>
     *
     * <p><b>唯一读取方是 {@link IceFactoryMenu} 的 {@code super(...)} 调用</b>，
     * 而它为 null 时不可能被构造（两道门）：</p>
     * <ol>
     *   <li>服务端：只有 {@code IceFactoryBlockEntity} 的 {@code createMenu} 会 new 它，
     *       而那个方块实体挂在 {@code IceFactoryBlock} 上 —— 禁用时方块不存在；</li>
     *   <li>客户端：只能由本字段注册出的 {@code MenuType} 反射构造，
     *       而禁用时 {@code MenuType} 同样没注册。</li>
     * </ol>
     * <p>所以那个 {@code .get()} 不会真的抛 NPE。护栏：
     * {@code TestIceFactoryToggle} 会核对「为 null ⇔ 开关为关」这两个分支同时成立。</p>
     */

    public static final RegistryObject<MenuType<IceFactoryMenu>> ICE_FACTORY_MENU;


    static {
        // Register all factory blocks
        //
        // 切菜工厂（阶段 2 Task 4）：方块/物品/tile/容器全部走 Mek 的注册器，
        // **注册名一字不改**（仍是 mekck:<tier>_cutting_factory），旧存档里已放置的方块因此不会变空气。
        FACTORY_CONTAINER = CUTTING_FACTORY_CONTAINERS_REG.register(
                "factory", cn.ism.mekck.machine.cutting.CuttingFactoryTile.class, CuttingMachineFactoryMenu::new);
        for (CuttingMachineFactoryTier tier : CuttingMachineFactoryTier.values()) {
            String id = tier.getBlockId();
            Component description = switch (tier) {
                case NEBULA -> Component.translatable("tooltip.mekck.nebula_cutting_factory");
                case BLAZE -> Component.translatable("tooltip.mekck.blaze_cutting_factory");
                case SINGULARITY -> Component.translatable("tooltip.mekck.singularity_cutting_factory");
                default -> null;
            };
            // BlockType 需要 tile 与容器，但两者都必须先有方块 —— 用延迟 Supplier 破这个环。
            // 三个 Supplier 都只在 Mek 真正求值的时刻（放置 / 开 GUI）才被调用，那时注册早已完成。
            mekanism.common.content.blocktype.BlockTypeTile<cn.ism.mekck.machine.cutting.CuttingFactoryTile> blockType =
                    CuttingMachineFactoryBlock.blockTypeFor(tier, () -> FACTORY_CONTAINER, () -> MekCkRegistrySupport.findFactoryTile(CUTTING_FACTORY_TILES, tier, "切菜工厂"));

            mekanism.common.registration.impl.BlockRegistryObject<CuttingMachineFactoryBlock, MekCkBlockItem> handle =
                    CUTTING_FACTORY_BLOCKS_REG.register(id,
                            () -> new CuttingMachineFactoryBlock(blockType, tier, CUTTING_FACTORY_PROPERTIES),
                            block -> new MekCkBlockItem(block, new Item.Properties(), description, tier, false));
            CUTTING_FACTORY_HANDLES.put(tier, handle);
            // 两个 ticker 都必须显式给：TileEntityTypeRegistryObject.getTicker(boolean) 只是
            // 原样返回存进去的那个，没有任何兜底（实测字节码：ifeq 取 serverTicker / else 取
            // clientTicker，直接 areturn）。不填就是 null，而 Level 只在 ticker 非 null 时才
            // 驱动方块实体 —— 机器会「放置成功、界面能开、就是不干活」。
            CUTTING_FACTORY_TILES.put(tier, CUTTING_FACTORY_TILES_REG.register(handle,
                    (pos, state) -> new cn.ism.mekck.machine.cutting.CuttingFactoryTile(handle, pos, state),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickClient(level, pos, state, tile),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickServer(level, pos, state, tile)));

            // 兼容面：本任务之外的文件（TierInstallerHandler / JEIPlugin）按旧类型读这两个 map。
            FACTORY_BLOCKS.put(tier, MekCkRegistrySupport.registryView(id, ForgeRegistries.BLOCKS));
            FACTORY_ITEMS.put(tier, MekCkRegistrySupport.registryView(id, ForgeRegistries.ITEMS));
        }

        // 逐档别名赋值（原 24 行 BASIC_FACTORY_BLOCK = FACTORY_BLOCKS.get(...) 等）已删除，
        // 理由同字段声明处：全仓零读取方，且缺 BLAZE 档导致 JEI 漏注册催化剂。
        // 单档引用请直接写 FACTORY_BLOCKS.get(CuttingMachineFactoryTier.XXX)。

        // 切菜各档的 tile 句柄在 CUTTING_FACTORY_TILES（见上面的注册循环）。
        // 原先这里还有 11 行 BASIC_FACTORY_BLOCK_ENTITY = FACTORY_BLOCK_ENTITIES.get(...)，
        // 因目标 map 恒空而恒为 null，已随阶段 2 Task 5 一并删除。

        // 烹饪工厂（阶段 3 Task 7）：方块/物品/tile/容器全部走 Mek 的注册器，
        // **注册名一字不改**（仍是 mekck:<tier>_cooking_factory）。
        COOKING_FACTORY_CONTAINER = COOKING_FACTORY_CONTAINERS_REG.register(
                "cooking_factory", cn.ism.mekck.machine.cooking.CookingFactoryTile.class,
                CookingFactoryMenu::new);
        for (CuttingMachineFactoryTier tier : CuttingMachineFactoryTier.values()) {
            String id = tier.getCookingBlockId();
            Component description = switch (tier) {
                case NEBULA -> Component.translatable("tooltip.mekck.nebula_cooking_factory");
                case BLAZE -> Component.translatable("tooltip.mekck.blaze_cooking_factory");
                case SINGULARITY -> Component.translatable("tooltip.mekck.singularity_cooking_factory");
                default -> null;
            };
            // BlockType 需要 tile 与容器，但两者都必须先有方块 —— 用延迟 Supplier 破这个环。
            mekanism.common.content.blocktype.BlockTypeTile<cn.ism.mekck.machine.cooking.CookingFactoryTile> blockType =
                    CookingFactoryBlock.blockTypeFor(tier, () -> COOKING_FACTORY_CONTAINER,
                            () -> MekCkRegistrySupport.findFactoryTile(COOKING_FACTORY_TILES, tier, "烹饪工厂"));

            // 末位 true = MekCkBlockItem 的 isCooking 标志（tooltip 用），旧实现同款。
            mekanism.common.registration.impl.BlockRegistryObject<CookingFactoryBlock, MekCkBlockItem> handle =
                    COOKING_FACTORY_BLOCKS_REG.register(id,
                            () -> new CookingFactoryBlock(blockType, tier, COOKING_FACTORY_PROPERTIES),
                            block -> new MekCkBlockItem(block, new Item.Properties(), description, tier, true));
            COOKING_FACTORY_HANDLES.put(tier, handle);
            // 两个 ticker 都必须显式给：TileEntityTypeRegistryObject.getTicker(boolean) 只是
            // 原样返回存进去的那个，没有任何兜底。不填就是 null，而 Level 只在 ticker 非 null 时
            // 才驱动方块实体 —— 机器会「放置成功、界面能开、就是不干活」。
            COOKING_FACTORY_TILES.put(tier, COOKING_FACTORY_TILES_REG.register(handle,
                    (pos, state) -> new cn.ism.mekck.machine.cooking.CookingFactoryTile(handle, pos, state),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickClient(level, pos, state, tile),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickServer(level, pos, state, tile)));

            COOKING_FACTORY_BLOCKS.put(tier, MekCkRegistrySupport.registryView(id, ForgeRegistries.BLOCKS));
            COOKING_FACTORY_ITEMS.put(tier, MekCkRegistrySupport.registryView(id, ForgeRegistries.ITEMS));
        }

        // 穿串工厂（阶段 3 Task 5）：方块/物品/tile/容器全部走 Mek 的注册器，
        // **注册名一字不改**（仍是 mekck:<tier>_skewering_factory）。
        SKEWERING_FACTORY_CONTAINER = SKEWERING_FACTORY_CONTAINERS_REG.register(
                "skewering_factory", cn.ism.mekck.machine.skewering.SkeweringFactoryTile.class,
                SkeweringFactoryMenu::new);
        for (CuttingMachineFactoryTier tier : CuttingMachineFactoryTier.values()) {
            String id = tier.getSkeweringBlockId();
            Component desc = switch (tier) {
                case NEBULA -> Component.translatable("tooltip.mekck.nebula_skewering_factory");
                case BLAZE -> Component.translatable("tooltip.mekck.blaze_skewering_factory");
                case SINGULARITY -> Component.translatable("tooltip.mekck.singularity_skewering_factory");
                default -> null;
            };
            // BlockType 需要 tile 与容器，但两者都必须先有方块 —— 用延迟 Supplier 破这个环。
            mekanism.common.content.blocktype.BlockTypeTile<cn.ism.mekck.machine.skewering.SkeweringFactoryTile> blockType =
                    SkeweringFactoryBlock.blockTypeFor(tier, () -> SKEWERING_FACTORY_CONTAINER,
                            () -> MekCkRegistrySupport.findFactoryTile(SKEWERING_FACTORY_TILES, tier, "穿串工厂"));

            // 末位 true = MekCkBlockItem 的 isCooking 标志（tooltip 用），旧实现同款。
            mekanism.common.registration.impl.BlockRegistryObject<SkeweringFactoryBlock, MekCkBlockItem> handle =
                    SKEWERING_FACTORY_BLOCKS_REG.register(id,
                            () -> new SkeweringFactoryBlock(blockType, tier, SKEWERING_FACTORY_PROPERTIES),
                            block -> new MekCkBlockItem(block, new Item.Properties(), desc, tier, true));
            SKEWERING_FACTORY_HANDLES.put(tier, handle);
            // 两个 ticker 都必须显式给：TileEntityTypeRegistryObject.getTicker(boolean) 只是
            // 原样返回存进去的那个，没有任何兜底。不填就是 null，而 Level 只在 ticker 非 null 时
            // 才驱动方块实体 —— 机器会「放置成功、界面能开、就是不干活」。
            SKEWERING_FACTORY_TILES.put(tier, SKEWERING_FACTORY_TILES_REG.register(handle,
                    (pos, state) -> new cn.ism.mekck.machine.skewering.SkeweringFactoryTile(handle, pos, state),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickClient(level, pos, state, tile),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickServer(level, pos, state, tile)));

            SKEWERING_FACTORY_BLOCKS.put(tier, MekCkRegistrySupport.registryView(id, ForgeRegistries.BLOCKS));
            SKEWERING_FACTORY_ITEMS.put(tier, MekCkRegistrySupport.registryView(id, ForgeRegistries.ITEMS));
        }

        // 烧烤工厂（阶段 3 Task 3）：方块/物品/tile/容器全部走 Mek 的注册器，
        // **注册名一字不改**（仍是 mekck:<tier>_grill_factory），
        // 旧存档里已放置的方块因此不会变空气。
        GRILL_FACTORY_CONTAINER = GRILL_FACTORY_CONTAINERS_REG.register(
                "grill_factory", cn.ism.mekck.machine.grill.GrillFactoryTile.class, GrillFactoryMenu::new);
        for (CuttingMachineFactoryTier tier : CuttingMachineFactoryTier.values()) {
            String id = tier.getGrillingBlockId();
            Component description = switch (tier) {
                case NEBULA -> Component.translatable("tooltip.mekck.nebula_grill_factory");
                case BLAZE -> Component.translatable("tooltip.mekck.blaze_grill_factory");
                case SINGULARITY -> Component.translatable("tooltip.mekck.singularity_grill_factory");
                default -> null;
            };
            // BlockType 需要 tile 与容器，但两者都必须先有方块 —— 用延迟 Supplier 破这个环。
            mekanism.common.content.blocktype.BlockTypeTile<cn.ism.mekck.machine.grill.GrillFactoryTile> blockType =
                    GrillFactoryBlock.blockTypeFor(tier, () -> GRILL_FACTORY_CONTAINER,
                            () -> MekCkRegistrySupport.findFactoryTile(GRILL_FACTORY_TILES, tier, "烧烤工厂"));

            mekanism.common.registration.impl.BlockRegistryObject<GrillFactoryBlock, MekCkBlockItem> handle =
                    GRILL_FACTORY_BLOCKS_REG.register(id,
                            () -> new GrillFactoryBlock(blockType, tier, GRILL_FACTORY_PROPERTIES),
                            block -> new MekCkBlockItem(block, new Item.Properties(), description, tier, false));
            GRILL_FACTORY_HANDLES.put(tier, handle);
            // 两个 ticker 都必须显式给：TileEntityTypeRegistryObject.getTicker(boolean) 只是
            // 原样返回存进去的那个，没有任何兜底。不填就是 null，而 Level 只在 ticker 非 null 时
            // 才驱动方块实体 —— 机器会「放置成功、界面能开、就是不干活」。
            GRILL_FACTORY_TILES.put(tier, GRILL_FACTORY_TILES_REG.register(handle,
                    (pos, state) -> new cn.ism.mekck.machine.grill.GrillFactoryTile(handle, pos, state),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickClient(level, pos, state, tile),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickServer(level, pos, state, tile)));

            // 兼容面：本任务之外的文件（TierInstallerHandler / JEIPlugin / MekckAe2）
            // 按旧类型读这两个 map。
            GRILL_FACTORY_BLOCKS.put(tier, MekCkRegistrySupport.registryView(id, ForgeRegistries.BLOCKS));
            GRILL_FACTORY_ITEMS.put(tier, MekCkRegistrySupport.registryView(id, ForgeRegistries.ITEMS));
        }

        // 切菜机：方块/物品/tile/容器全部走 Mek 的注册器，**注册名一字不改**。
        MACHINE_CONTAINER = MACHINE_CONTAINERS_REG.register(
                "universal_cutting_machine", cn.ism.mekck.machine.cutting.UniversalCuttingMachineTile.class,
                UniversalCuttingMachineMenu::new);
        {
            // BlockType 需要 tile 与容器，但两者都必须先有方块 —— 用延迟 Supplier 破这个环。
            mekanism.common.content.blocktype.BlockTypeTile<cn.ism.mekck.machine.cutting.UniversalCuttingMachineTile> blockType =
                    UniversalCuttingMachineBlock.blockTypeFor(() -> MACHINE_CONTAINER, () -> MekCkRegistrySupport.findMachineTile());

            MACHINE_HANDLE = MACHINE_BLOCKS_REG.register("universal_cutting_machine",
                    () -> new UniversalCuttingMachineBlock(blockType, MACHINE_PROPERTIES),
                    block -> new MekCkBlockItem(block, new Item.Properties(), 1,
                            cn.ism.mekck.machine.cutting.UniversalCuttingMachineTile.ENERGY_PER_TICK,
                            cn.ism.mekck.machine.cutting.UniversalCuttingMachineTile.ENERGY_CAPACITY));
            // 两个 ticker 都必须显式给：getTicker(boolean) 只是原样返回存进去的那个、
            // 没有任何兜底（实测字节码：ifeq 取 serverTicker / else 取 clientTicker，直接 areturn）。
            // 不填就是 null，而 Level 只在 ticker 非 null 时才驱动方块实体 ——
            // 机器会「放置成功、界面能开、就是不干活」。
            MACHINE_TILE = MACHINE_TILES_REG.register(MACHINE_HANDLE,
                    (pos, state) -> new cn.ism.mekck.machine.cutting.UniversalCuttingMachineTile(MACHINE_HANDLE, pos, state),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickClient(level, pos, state, tile),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickServer(level, pos, state, tile));

            MACHINE_BLOCK = MekCkRegistrySupport.registryView("universal_cutting_machine", ForgeRegistries.BLOCKS);
            MACHINE_ITEM = MekCkRegistrySupport.registryView("universal_cutting_machine", ForgeRegistries.ITEMS);
        }

        // 电力烧烤架（阶段 3）：方块/物品/tile/容器全部走 Mek 的注册器，
        // **注册名一字不改**（仍是 mekck:electric_grill）。
        GRILL_CONTAINER = GRILL_CONTAINERS_REG.register(
                "electric_grill", GrillBlockEntity.class, GrillMenu::new);
        {
            // BlockType 需要 tile 与容器，但两者都必须先有方块 —— 用延迟 Supplier 破这个环。
            // 两个 Supplier 都只在 Mek 真正求值的时刻（放置 / 开 GUI）才被调用，那时注册早已完成。
            mekanism.common.content.blocktype.BlockTypeTile<GrillBlockEntity> blockType =
                    GrillBlock.blockTypeFor(() -> GRILL_CONTAINER, () -> MekCkRegistrySupport.findGrillTile());

            // 末位 false = MekCkBlockItem 的 isCooking 标志（tooltip 用），旧实现同款。
            GRILL_HANDLE = GRILL_BLOCKS_REG.register("electric_grill",
                    () -> new GrillBlock(blockType, GRILL_PROPERTIES),
                    block -> new MekCkBlockItem(block, new Item.Properties(), 1, 20, 100000, false));
            // 两个 ticker 都必须显式给：TileEntityTypeRegistryObject.getTicker(boolean) 只是
            // 原样返回存进去的那个，没有任何兜底。不填就是 null，而 Level 只在 ticker 非 null 时
            // 才驱动方块实体 —— 机器会「放置成功、界面能开、就是不干活」。
            GRILL_TILE = GRILL_TILES_REG.register(GRILL_HANDLE,
                    (pos, state) -> new GrillBlockEntity(GRILL_HANDLE, pos, state),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickClient(level, pos, state, tile),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickServer(level, pos, state, tile));

            GRILL_BLOCK = MekCkRegistrySupport.registryView("electric_grill", ForgeRegistries.BLOCKS);
            GRILL_ITEM = MekCkRegistrySupport.registryView("electric_grill", ForgeRegistries.ITEMS);
        }

        // 种植切配工厂（阶段 3 Task 2）：方块/物品/tile/容器全部走 Mek 的注册器，
        // **注册名一字不改**（仍是 mekck:<tier>_planting_cutting_factory），
        // 旧存档里已放置的方块因此不会变空气。
        PLANTING_CUTTING_CONTAINER = PLANTING_CUTTING_FACTORY_CONTAINERS_REG.register(
                "planting_cutting_factory", cn.ism.mekck.machine.plantingcutting.PlantingCuttingFactoryTile.class,
                PlantingCuttingFactoryMenu::new);
        for (CuttingMachineFactoryTier tier : CuttingMachineFactoryTier.values()) {
            String id = tier.getPlantingBlockId();
            Component description = switch (tier) {
                case NEBULA -> Component.translatable("tooltip.mekck.nebula_planting_cutting_factory");
                case BLAZE -> Component.translatable("tooltip.mekck.blaze_planting_cutting_factory");
                case SINGULARITY -> Component.translatable("tooltip.mekck.singularity_planting_cutting_factory");
                default -> null;
            };
            // BlockType 需要 tile 与容器，但两者都必须先有方块 —— 用延迟 Supplier 破这个环。
            mekanism.common.content.blocktype.BlockTypeTile<cn.ism.mekck.machine.plantingcutting.PlantingCuttingFactoryTile> blockType =
                    PlantingCuttingFactoryBlock.blockTypeFor(tier, () -> PLANTING_CUTTING_CONTAINER,
                            () -> MekCkRegistrySupport.findFactoryTile(PLANTING_CUTTING_FACTORY_TILES, tier, "种植切配工厂"));

            mekanism.common.registration.impl.BlockRegistryObject<PlantingCuttingFactoryBlock, MekCkBlockItem> handle =
                    PLANTING_CUTTING_FACTORY_BLOCKS_REG.register(id,
                            () -> new PlantingCuttingFactoryBlock(blockType, tier, PLANTING_CUTTING_FACTORY_PROPERTIES),
                            block -> new MekCkBlockItem(block, new Item.Properties(), description, tier, false));
            PLANTING_CUTTING_FACTORY_HANDLES.put(tier, handle);
            // 两个 ticker 都必须显式给：TileEntityTypeRegistryObject.getTicker(boolean) 只是
            // 原样返回存进去的那个，没有任何兜底（实测字节码：ifeq 取 serverTicker / else 取
            // clientTicker，直接 areturn）。不填就是 null，而 Level 只在 ticker 非 null 时才
            // 驱动方块实体 —— 机器会「放置成功、界面能开、就是不干活」。
            PLANTING_CUTTING_FACTORY_TILES.put(tier, PLANTING_CUTTING_FACTORY_TILES_REG.register(handle,
                    (pos, state) -> new cn.ism.mekck.machine.plantingcutting.PlantingCuttingFactoryTile(handle, pos, state),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickClient(level, pos, state, tile),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickServer(level, pos, state, tile)));

            // 兼容面：本任务之外的文件（TierInstallerHandler / JEIPlugin / MekckAe2）
            // 按旧类型读这两个 map。
            PLANTING_CUTTING_FACTORY_BLOCKS.put(tier, MekCkRegistrySupport.registryView(id, ForgeRegistries.BLOCKS));
            PLANTING_CUTTING_FACTORY_ITEMS.put(tier, MekCkRegistrySupport.registryView(id, ForgeRegistries.ITEMS));
        }

        // 研磨工厂（阶段 3 Task 1）：方块/物品/tile/容器全部走 Mek 的注册器，
        // **注册名一字不改**（仍是 mekck:<tier>_grinding_factory），旧存档里已放置的方块因此不会变空气。
        GRINDING_FACTORY_CONTAINER = GRINDING_FACTORY_CONTAINERS_REG.register(
                "grinding_factory", cn.ism.mekck.machine.grinding.GrindingFactoryTile.class, GrindingFactoryMenu::new);
        for (CuttingMachineFactoryTier tier : CuttingMachineFactoryTier.values()) {
            String id = tier.getGrindingBlockId();
            Component description = switch (tier) {
                case NEBULA -> Component.translatable("tooltip.mekck.nebula_grinding_factory");
                case BLAZE -> Component.translatable("tooltip.mekck.blaze_grinding_factory");
                case SINGULARITY -> Component.translatable("tooltip.mekck.singularity_grinding_factory");
                default -> null;
            };
            // BlockType 需要 tile 与容器，但两者都必须先有方块 —— 用延迟 Supplier 破这个环。
            mekanism.common.content.blocktype.BlockTypeTile<cn.ism.mekck.machine.grinding.GrindingFactoryTile> blockType =
                    GrindingFactoryBlock.blockTypeFor(tier, () -> GRINDING_FACTORY_CONTAINER, () -> MekCkRegistrySupport.findFactoryTile(GRINDING_FACTORY_TILES, tier, "研磨工厂"));

            mekanism.common.registration.impl.BlockRegistryObject<GrindingFactoryBlock, MekCkBlockItem> handle =
                    GRINDING_FACTORY_BLOCKS_REG.register(id,
                            () -> new GrindingFactoryBlock(blockType, tier, GRINDING_FACTORY_PROPERTIES),
                            block -> new MekCkBlockItem(block, new Item.Properties(), description, tier, false));
            GRINDING_FACTORY_HANDLES.put(tier, handle);
            // 两个 ticker 都必须显式给：TileEntityTypeRegistryObject.getTicker(boolean) 只是原样返回存进去的那个，
            // 没有任何兜底。不填就是 null，而 Level 只在 ticker 非 null 时才驱动方块实体——
            // 机器会「放置成功、界面能开、就是不干活」。
            GRINDING_FACTORY_TILES.put(tier, GRINDING_FACTORY_TILES_REG.register(handle,
                    (pos, state) -> new cn.ism.mekck.machine.grinding.GrindingFactoryTile(handle, pos, state),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickClient(level, pos, state, tile),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickServer(level, pos, state, tile)));

            // 兼容面：本任务之外的文件按旧类型读这两个 map。
            GRINDING_FACTORY_BLOCKS.put(tier, MekCkRegistrySupport.registryView(id, ForgeRegistries.BLOCKS));
            GRINDING_FACTORY_ITEMS.put(tier, MekCkRegistrySupport.registryView(id, ForgeRegistries.ITEMS));
        }

        if (MekckConfig.isIceFactoryEnabled()) {
            // Register all ice factory blocks (急冻制冰工厂) —— 禁用时整段跳过，代码保留
            for (CuttingMachineFactoryTier tier : CuttingMachineFactoryTier.values()) {
                String id = tier.getIceMakerBlockId();
                RegistryObject<Block> block = BLOCKS.register(id, () -> new IceFactoryBlock(tier));
                RegistryObject<Item> item = ITEMS.register(id, () -> {
                    Component description = switch (tier) {
                        case NEBULA -> Component.translatable("tooltip.mekck.nebula_ice_factory");
                        case BLAZE -> Component.translatable("tooltip.mekck.blaze_ice_factory");
                        case SINGULARITY -> Component.translatable("tooltip.mekck.singularity_ice_factory");
                        default -> null;
                    };
                    return new MekCkBlockItem(block.get(), new Item.Properties(), description, tier, false);
                });
                RegistryObject<BlockEntityType<IceFactoryBlockEntity>> be = BLOCK_ENTITIES.register(
                        id, () -> BlockEntityType.Builder.of(
                                (pos, state) -> new IceFactoryBlockEntity(tier, pos, state),
                                block.get()).build(null));
                ICE_FACTORY_BLOCKS.put(tier, block);
                ICE_FACTORY_ITEMS.put(tier, item);
                ICE_FACTORY_BLOCK_ENTITIES.put(tier, be);
            }
            ICE_FACTORY_MENU = MENUS.register("ice_factory", () -> IForgeMenuType.create(IceFactoryMenu::new));
        } else {
            // 制冰工厂禁用：菜单不注册（方块/物品/方块实体循环整体跳过）
            ICE_FACTORY_MENU = null;
        }




        // Assign specific planting & cutting factory references

        // Assign specific cooking factory references

        // 烧烤各档的 tile 句柄在 GRILL_FACTORY_TILES（见上面的注册循环）。
        // 原先这里还有 11 行 BASIC_GRILL_FACTORY_BLOCK_ENTITY = GRILL_FACTORY_BLOCK_ENTITIES.get(...)，
        // 因目标 map 恒空而恒为 null，已随阶段 3 Task 3 一并删除。

        // 穿串各档的 tile 句柄在 SKEWERING_FACTORY_TILES（见上面的注册循环）。
        // 原先这里还有 11 行 BASIC_SKEWERING_FACTORY_BLOCK_ENTITY = SKEWERING_FACTORY_BLOCK_ENTITIES.get(...)，
        // 因目标 map 恒空而恒为 null，已随阶段 3 Task 5 一并删除。

        // Shared menu type for all factories
        //
        // Task 4 起容器由 Mek 的 ContainerTypeDeferredRegister 注册（同样落在 MENU_TYPES 注册表、
        // 同样叫 mekck:factory）。这里只造一个指向同一注册项的 RegistryObject 视图，
        // 让 ClientEvents 里的 MenuScreens.register(FACTORY_MENU.get(), ...) 照旧能写。
        FACTORY_MENU = MekCkRegistrySupport.registryView("factory", ForgeRegistries.MENU_TYPES);
        // 同理：研磨工厂的容器现在也是 Mek 的 ContainerTypeRegistryObject，
        // 这里造一个指向同一注册项的视图，让 ClientEvents 里的
        // MenuScreens.register(GRINDING_FACTORY_MENU.get(), ...) 照旧能写。
        GRINDING_FACTORY_MENU = MekCkRegistrySupport.registryView("grinding_factory", ForgeRegistries.MENU_TYPES);
    }

    /**
     * 造一个指向<b>已由别人注册</b>的注册项的 {@link RegistryObject} 视图。
     *
     * <h3>为什么需要它</h3>
     * 切菜工厂现在走 Mek 的 {@code BlockDeferredRegister} / {@code ContainerTypeDeferredRegister}，
     * 它们返回的是 {@code BlockRegistryObject} / {@code ContainerTypeRegistryObject}，
     * 而这两个类<b>不是</b> Forge 的 {@code RegistryObject}
     * （{@code WrappedRegistryObject} 只实现 {@code Supplier}；{@code RegistryObject} 本身是 final class，
     * 无法用适配器糊过去）。
     *
     * <p>但 {@code FACTORY_BLOCKS} / {@code FACTORY_ITEMS} / {@code FACTORY_MENU} 是本类的公开 API，
     * 本任务<b>之外</b>还有三个文件按原类型读它们：
     * {@code util/TierInstallerHandler}（两个 private 辅助方法，参数类型写死
     * {@code Map<..., RegistryObject<Block>>}）、{@code integration/jei/JEIPlugin}、
     * {@code ClientEvents}。改这三个字段的类型会波及任务清单之外的文件。
     *
     * <p>{@link RegistryObject#create} 正是为此存在的公开工厂：它按注册名订阅注册表，
     * 在注册事件里填充值，语义与 {@code DeferredRegister} 返回的那个一模一样，
     * 且类型就是 {@code RegistryObject<T>}。</p>
     *
     * @param id       不含命名空间的注册名
     * @param registry 目标注册表
     */

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
        event.accept(MACHINE_ITEM.get());
        for (RegistryObject<Item> factoryItem : FACTORY_ITEMS.values()) {
            event.accept(factoryItem.get());
        }
        event.accept(GRINDING_MACHINE_ITEM.get());
        for (RegistryObject<Item> factoryItem : GRINDING_FACTORY_ITEMS.values()) {
            event.accept(factoryItem.get());
        }
        for (RegistryObject<Item> factoryItem : COOKING_FACTORY_ITEMS.values()) {
            event.accept(factoryItem.get());
        }
        for (RegistryObject<Item> factoryItem : SKEWERING_FACTORY_ITEMS.values()) {
            event.accept(factoryItem.get());
        }
        event.accept(GRILL_ITEM.get());
        for (RegistryObject<Item> factoryItem : GRILL_FACTORY_ITEMS.values()) {
            event.accept(factoryItem.get());
        }
        for (RegistryObject<Item> factoryItem : PLANTING_CUTTING_FACTORY_ITEMS.values()) {
            event.accept(factoryItem.get());
        }
        event.accept(PLANTING_CUTTING_STATION_ITEM.get());
        if (MekckConfig.isIceFactoryEnabled()) {
            for (RegistryObject<Item> factoryItem : ICE_FACTORY_ITEMS.values()) {
                event.accept(factoryItem.get());
            }
        }
    }
}

package cn.ism.mekck.factory;

import mekanism.api.math.FloatingLong;
import mekanism.common.block.attribute.AttributeEnergy;
import mekanism.common.block.attribute.AttributeStateFacing;
import mekanism.common.block.attribute.AttributeUpgradeSupport;
import mekanism.common.block.attribute.Attributes;
import mekanism.common.content.blocktype.BlockTypeTile;
import mekanism.common.registration.impl.BlockDeferredRegister;
import mekanism.common.registration.impl.BlockRegistryObject;
import mekanism.common.registration.impl.ContainerTypeDeferredRegister;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import mekanism.common.registration.impl.TileEntityTypeDeferredRegister;
import mekanism.common.registration.impl.TileEntityTypeRegistryObject;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * MekCK 工厂的 Mek 体系注册（新套，与旧自研套并列共存）。
 *
 * <h3>为什么是「新套」</h3>
 * 旧的 7 家族 × 12 tier 工厂注册在 {@code mekck:<tier>_<family>_factory}
 * （见 {@link cn.ism.mekck.CuttingMachineFactoryTier#getBlockId()}），走自研 {@code BlockEntity}。
 * 新套走 Mek 的机器体系，方块 ID 必须与旧套区分，故使用独立命名空间 {@value #NAMESPACE}。
 *
 * <p>贴图不重做：新方块复用旧套 {@code assets/mekck/textures/block/...} 下已有的美术资源。</p>
 *
 * <h3>为什么不直接用 Mek 的 BlockFactoryMachine$BlockFactory</h3>
 * 那是 Mek 给<b>自己的</b>工厂用的，泛型绑定 {@code Factory<TILE>}（内容类型为 Mek 的
 * {@code FactoryType}）与 {@code TileEntityFactory}。本项目的工艺类型是自建的
 * {@link MekCkFactoryType}，因此用通用的 Mek {@code BlockTile} + 自建
 * {@link BlockTypeTile}（与 Extras 处理自有工艺时的做法一致）。
 *
 * <h3>能量声明点</h3>
 * 容量与能耗只能通过方块属性 {@link AttributeEnergy} 声明 ——
 * {@code MachineEnergyContainer.input(tile, listener)} 在构造时读取该属性
 * （反编译实测：{@code validateBlock(tile).getStorage()/getUsage()}）。
 * 因此 {@link #blockTypeFor} 是唯一的能量声明处。
 */
public final class MekCkFactoryRegistration {

    /** 新套命名空间：与旧套 {@code mekck} 的同名方块区分。 */
    public static final String NAMESPACE = "mekckfactory";

    public static final BlockDeferredRegister BLOCKS = new BlockDeferredRegister(NAMESPACE);
    public static final TileEntityTypeDeferredRegister TILE_ENTITIES = new TileEntityTypeDeferredRegister(NAMESPACE);
    public static final ContainerTypeDeferredRegister CONTAINERS = new ContainerTypeDeferredRegister(NAMESPACE);

    /** 已注册的方块，按「家族:等级」索引。 */
    private static final Map<String, BlockRegistryObject<MekCkFactoryBlock, BlockItem>> REGISTERED =
            new java.util.HashMap<>();
    /** 已注册的 tile 类型，按「家族:等级」索引。 */
    private static final Map<String, TileEntityTypeRegistryObject<MekCkFactoryTile>> TILES =
            new java.util.HashMap<>();
    /** 已注册的容器，按「家族:等级」索引（Menu 构造器按 tile 取回）。 */
    private static final Map<String, ContainerTypeRegistryObject<MekCkFactoryMenu>> CONTAINERS_BY_TIER =
            new java.util.HashMap<>();

    /** 索引键：家族 + 等级。 */
    private static String key(MekCkFactoryType type, MekCkFactoryTier tier) {
        return type.getTypeName() + ":" + tier.getLowerName();
    }

    private MekCkFactoryRegistration() {
    }

    /**
     * 注册全部 12 档 × 7 家族的工厂。
     *
     * <p>调用方在 mod 构造期挂到事件总线：{@code MekCkFactoryRegistration.register(bus)}。</p>
     */
    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        TILE_ENTITIES.register(bus);
        CONTAINERS.register(bus);

        for (MekCkFactoryType type : MekCkFactoryType.values()) {
            for (MekCkFactoryTier tier : MekCkFactoryTier.values()) {
                registerFactory(tier, type);
            }
        }
    }

    private static void registerFactory(MekCkFactoryTier tier, MekCkFactoryType type) {
        String id = type.blockId(tier);
        String k = key(type, tier);

        // ── 依赖环形：BlockType 需要容器；容器需要 tile；tile 需要 block；block 需要 BlockType ──
        // 用延迟 Supplier 打破：容器在 BlockType 构建后才真正注册，而 Supplier 只在
        // Mek 运行时（GUI 打开时）求值，那时注册早已完成。
        Supplier<ContainerTypeRegistryObject<? extends mekanism.common.inventory.container.MekanismContainer>> containerRef = () -> {
            ContainerTypeRegistryObject<MekCkFactoryMenu> c = CONTAINERS_BY_TIER.get(k);
            if (c == null) {
                throw new IllegalStateException("MekCK 工厂容器尚未注册：key=" + k);
            }
            return c;
        };

        BlockTypeTile<MekCkFactoryTile> blockType = blockTypeFor(type, tier, containerRef, k);

        BlockRegistryObject<MekCkFactoryBlock, BlockItem> block = BLOCKS.register(id,
                () -> new MekCkFactoryBlock(blockType, type, tier, DEFAULT_PROPERTIES));

        TileEntityTypeRegistryObject<MekCkFactoryTile> tileType = TILE_ENTITIES.register(block,
                (pos, state) -> new MekCkFactoryTile(block, pos, state, tier, type));

        // 容器：走 Mek 标准注册 —— 它内部用 invokedynamic 自引用，生成的工厂签名
        // 是 (int, Inventory, TILE)，与 MekCkFactoryMenu 的构造器一致。
        ContainerTypeRegistryObject<MekCkFactoryMenu> container = CONTAINERS.register(
                id, MekCkFactoryTile.class, MekCkFactoryMenu::new);

        REGISTERED.put(k, block);
        TILES.put(k, tileType);
        CONTAINERS_BY_TIER.put(k, container);
    }

    /**
     * 方块类型描述：译名、GUI、能量、状态属性、升级支持。
     *
     * <h3>为什么用官方 builder 而不是 new + add</h3>
     * {@code withGui(...)} 会挂上 {@link mekanism.common.block.attribute.AttributeGui}，
     * 而这是<b>右键打开 GUI 的判定依据</b> —— 反编译实测 {@code BlockTile.use()}：
     * <pre>
     *   if (type.has(AttributeGui.class)) { return tile.openGui(player); }
     *   return InteractionResult.PASS;
     * </pre>
     * 且 {@code TileEntityMekanism.setSupportedTypes()} 的 {@code hasGui} 标志也从该属性读取。
     * 缺它 → 方块右键毫无反应（实机现象）。
     *
     * <p>{@code AttributeGui} 的构造器需要容器注册对象，而容器要到 tile/block 建好之后才能注册，
     * 故传 {@code containerRef} 延迟引用（只在 GUI 打开时求值）。</p>
     */
    private static BlockTypeTile<MekCkFactoryTile> blockTypeFor(
            MekCkFactoryType type, MekCkFactoryTier tier,
            Supplier<ContainerTypeRegistryObject<? extends mekanism.common.inventory.container.MekanismContainer>> containerRef,
            String key) {

        mekanism.common.content.blocktype.BlockTypeTile.BlockTileBuilder<
                BlockTypeTile<MekCkFactoryTile>, MekCkFactoryTile, ?> builder =
                mekanism.common.content.blocktype.BlockTypeTile.BlockTileBuilder.createBlock(
                        () -> findTile(key), type);

        // GUI：没有它右键不开界面
        builder.withGui(containerRef);

        // 能量：AttributeEnergy 签名是 (usage, storage)（先用后容，反编译实测），
        // 用 lambda 延迟取值以支持运行时改配置。
        builder.withEnergyConfig(
                () -> FloatingLong.create(tier.energyPerTick),
                () -> FloatingLong.create(tier.energyCapacity));

        // 升级支持：没有它 TileEntityMekanism.supportsUpgrades() 为 false
        // （实测查的是 AttributeUpgradeSupport），升级组件/槽位/升级 tab 都不会出现。
        builder.withSupportedUpgrades(SUPPORTED_UPGRADES);

        // 方块状态与其它属性：
        //  - 朝向用 AttributeStateFacing（public，可直接 new，等价 Mek 工厂的朝向）；
        //    ★ 缺它则 blockstate 的 facing= 变体全部匹配失败 → 方块隐形。
        //  - active/redstone/security/inventory 用 Mek 的 Attributes 现成静态实例：
        //    它们构造器是包级私有，外部只能通过这些常量使用。
        //    ★ redstone/security 还是红石 tab 与安全 tab 存在的前提。
        builder.with(new AttributeStateFacing());
        builder.with(Attributes.ACTIVE);
        builder.with(Attributes.REDSTONE);
        builder.with(Attributes.SECURITY);
        builder.with(Attributes.INVENTORY);

        return builder.build();
    }

    /** 新套工厂允许安装的升级类型。 */
    private static final java.util.Set<mekanism.api.Upgrade> SUPPORTED_UPGRADES =
            java.util.Set.of(
                    mekanism.api.Upgrade.SPEED,
                    mekanism.api.Upgrade.ENERGY,
                    mekanism.api.Upgrade.MUFFLING);

    /**
     * 取已注册的 tile 类型（按「家族:等级」精确查找）。
     *
     * <p>Mek 的 {@code BlockTypeTile} 在构造时就要求 tile 的 Supplier，但注册顺序上
     * tile 必须<b>绑定到方块之后</b>才能创建（{@code TILE_ENTITIES.register(block, ...)}），
     * 形成先后依赖。这里用延迟查找打破循环：Supplier 只在 Mek 真正需要时才求值，
     * 那时注册已完成。</p>
     *
     * <p><b>必须按 key 查，不能只按 tier 查</b>：每个家族各有自己的 tile 类型
     * （{@code basic_cutting_factory} 与 {@code basic_grilling_factory} 是两块不同的 tile），
     * 若按 tier 取"第一个注册的"，方块会指向别的家族的 tile 类型 —— 表现为
     * {@code BlockTile.use()} 里 {@code WorldUtils.getTileEntity()} 取不到 tile、右键毫无反应。</p>
     */
    private static TileEntityTypeRegistryObject<MekCkFactoryTile> findTile(String key) {
        TileEntityTypeRegistryObject<MekCkFactoryTile> found = TILES.get(key);
        if (found == null) {
            throw new IllegalStateException(
                    "MekCK 工厂 tile 尚未注册：key=" + key + "（BlockTypeTile 的 Supplier 被过早求值）");
        }
        return found;
    }

    /** 方块属性：Mek 的 {@code BlockTile} 会先铺默认强度/工具需求，这里不改动。 */
    private static final UnaryOperator<BlockBehaviour.Properties> DEFAULT_PROPERTIES = props -> props;

    /** 已注册方块（供创造标签页/调试用）。 */
    public static BlockRegistryObject<MekCkFactoryBlock, BlockItem> get(MekCkFactoryType type, MekCkFactoryTier tier) {
        return REGISTERED.get(key(type, tier));
    }

    /** 已注册 tile 类型（按家族+等级精确取，见 {@link #findTile}）。 */
    public static TileEntityTypeRegistryObject<MekCkFactoryTile> getTile(MekCkFactoryType type, MekCkFactoryTier tier) {
        return TILES.get(key(type, tier));
    }

    /** 已注册容器（Menu 构造器按家族+等级取回，父类要求非空）。 */
    public static ContainerTypeRegistryObject<MekCkFactoryMenu> getContainer(MekCkFactoryType type, MekCkFactoryTier tier) {
        return CONTAINERS_BY_TIER.get(key(type, tier));
    }
}

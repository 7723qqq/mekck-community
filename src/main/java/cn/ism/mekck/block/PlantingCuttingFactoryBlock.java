package cn.ism.mekck.block;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.machine.plantingcutting.PlantingCuttingFactoryTile;
import cn.ism.mekck.upgrade.MekCkUpgradeRefs;
import cn.ism.mekck.util.MekCkMultiblock;
import mekanism.api.math.FloatingLong;
import mekanism.api.text.ILangEntry;
import mekanism.common.block.attribute.AttributeEnergy;
import mekanism.common.block.attribute.AttributeStateFacing;
import mekanism.common.block.attribute.Attributes;
import mekanism.common.block.prefab.BlockTile;
import mekanism.common.content.blocktype.BlockTypeTile;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.registries.MekanismSounds;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import mekanism.common.registration.impl.TileEntityTypeRegistryObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * 种植切配工厂方块（Mek 体系版）—— 阶段 3 从自研 {@code BaseEntityBlock} 换成 {@link BlockTile}。
 *
 * <h3>与切菜方块的三处差别</h3>
 * <ol>
 *   <li><b>1×2×1 多方块的放置校验</b>保留在本类：{@code getStateForPlacement} 里查
 *       {@link MekCkMultiblock#canPlace}，{@code setPlacedBy} 里放绑定方块，
 *       {@code onRemove} 里拆绑定方块。Mek 的 {@code BlockTile} 不管多方块，
 *       这三步必须自己留着。</li>
 *   <li><b>落地方块</b>不覆写 {@code getDrops} 返空、也不在 {@code onRemove} 里手动掉物品
 *       ——切菜已经验证过 Mek 的 {@code BlockMekanism.onRemove} 会处理，
 *       物品由 {@code data/mekck/loot_tables/blocks/<id>.json} 掉出。
 *       旧实现在这里 {@code saveToItem} + 手动掉，与 Mek 的路径重复，
 *       留着会双份掉落。</li>
 *   <li><b>「潜行 + 手持升级模块直接装槽」快捷键</b>作废，由 Mek 升级 tab 取代。</li>
 * </ol>
 */
public final class PlantingCuttingFactoryBlock
        extends BlockTile<PlantingCuttingFactoryTile, BlockTypeTile<PlantingCuttingFactoryTile>> {

    /**
     * 1×2×1 多方块形状：主方块 + 上方 1 个绑定方块。
     *
     * <p>逐字取自旧实现，与 {@code MekCkMultiblock.SHAPE_2_TALL} 同一个对象。</p>
     */
    private static final mekanism.api.functions.TriConsumer<BlockPos, BlockState, Stream.Builder<BlockPos>>
            BOUNDING_SHAPE = MekCkMultiblock.SHAPE_2_TALL;

    /**
     * 朝向属性。
     *
     * <p>与 {@code blockTypeFor} 里 {@code new AttributeStateFacing()} 用的是
     * <b>同一个无参构造</b>，所以两边的 facingProperty 一致
     * （默认 {@code BlockStateProperties.HORIZONTAL_FACING}），
     * 现有 blockstate JSON 里的 {@code facing=} 变体因此照旧匹配，贴图与模型都不用改。
     */
    private static final AttributeStateFacing FACING_ATTRIBUTE = new AttributeStateFacing();

    private final CuttingMachineFactoryTier tier;

    public PlantingCuttingFactoryBlock(BlockTypeTile<PlantingCuttingFactoryTile> type,
                                      CuttingMachineFactoryTier tier,
                                      UnaryOperator<BlockBehaviour.Properties> propertyModifier) {
        super(type, propertyModifier);
        this.tier = tier;
    }

    /**
     * 本方块的等级。
     *
     * <p>{@code PlantingCuttingFactoryTile} 在构造期就靠它反查档位
     * （基类注释的「构造期顺序陷阱」），所以必须由构造参数带进来。</p>
     */
    public CuttingMachineFactoryTier getTier() {
        return tier;
    }

    // ── 1×2×1 多方块：Mek 的 BlockTile 不管这三步 ──────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>在 {@link AttributeStateFacing} 给出的朝向之外，额外要求上方一格空着——
     * 绑定方块放不下时直接拒绝放置，否则会造出半截多方块。</p>
     */
    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        // 用 AttributeStateFacing 的实例方法而不是直接 setValue：
        // 它持有自己的 facingProperty（默认 HORIZONTAL_FACING），
        // 且保证写进 state 的属性名与 Mek 的方块描述一致。
        BlockState state = FACING_ATTRIBUTE.setDirection(
                this.defaultBlockState(), context.getHorizontalDirection());
        if (!MekCkMultiblock.canPlace(context.getLevel(), context.getClickedPos(), state, BOUNDING_SHAPE)) {
            return null;
        }
        return state;
    }

    /**
     * {@inheritDoc}
     *
     * <p>放绑定方块组成 1×2×1 整体。自定义名仍由 {@code setPlacedBy} 处理
     * （{@code TileEntityMekanism} 实现了 {@code Nameable}）。</p>
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
        if (stack.hasCustomHoverName() && level.getBlockEntity(pos) instanceof PlantingCuttingFactoryTile tile) {
            tile.setCustomName(stack.getHoverName());
        }
        MekCkMultiblock.placeBoundingBlocks(level, pos, state, BOUNDING_SHAPE);
    }

    /** 拆绑定方块：多方块整体破坏。物品掉落交给 Mek 的 onRemove + loot table。 */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !cn.ism.mekck.util.TierInstallerHandler.isUpgrading()) {
            MekCkMultiblock.removeBoundingBlocks(level, pos, state, BOUNDING_SHAPE);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    // ── 方块类型描述 ────────────────────────────────────────────────────

    /**
     * 构造本等级种植切配工厂的方块类型描述。
     *
     * <p>四个属性一个都不能少，<b>各自的缺失症状</b>：
     * <ul>
     *   <li>{@code withGui} → 缺了右键不开界面；</li>
     *   <li>{@code withEnergyConfig} → {@code MachineEnergyContainer.input} 在构造时读它；</li>
     *   <li>{@code withSupportedUpgrades} → 缺了 {@code supportsUpgrades()} 为 false；</li>
     *   <li>{@link AttributeStateFacing} → 缺了 blockstate 的 {@code facing=} 变体全不匹配，方块隐形。</li>
     * </ul>
     */
    public static BlockTypeTile<PlantingCuttingFactoryTile> blockTypeFor(
            CuttingMachineFactoryTier tier,
            Supplier<ContainerTypeRegistryObject<? extends MekanismContainer>> containerRef,
            Supplier<TileEntityTypeRegistryObject<PlantingCuttingFactoryTile>> tileRef) {

        BlockTypeTile.BlockTileBuilder<BlockTypeTile<PlantingCuttingFactoryTile>,
                PlantingCuttingFactoryTile, ?> builder =
                BlockTypeTile.BlockTileBuilder.createBlock(tileRef, new PlantingCuttingLangEntry(tier));

        builder.withGui(containerRef);

        // AttributeEnergy 的参数是 (usage, storage)（先用后容）。用 lambda 延迟取值。
        builder.withEnergyConfig(
                () -> FloatingLong.create(tier.energyPerTick),
                () -> FloatingLong.create(tier.energyCapacity));

        builder.withSupportedUpgrades(supportedUpgrades());
        builder.withSound(MekanismSounds.PRECISION_SAWMILL);

        builder.with(new AttributeStateFacing());
        builder.with(Attributes.ACTIVE);
        builder.with(Attributes.REDSTONE);
        builder.with(Attributes.SECURITY);
        builder.with(Attributes.INVENTORY);

        return builder.build();
    }

    /**
     * 本模组允许装进种植切配工厂的升级类型。
     *
     * <p>与 {@code MekCkMachineTile#getSupportedUpgrade()} 是<b>两道不同的闸门</b>：
     * 这里决定方块属性 {@code AttributeUpgradeSupport}（进而决定升级槽与升级 tab
     * 是否出现），那个方法决定 {@code TileComponentUpgrade} 收哪几种卡。
     *
     * <p>刻意只列这 4 种，<b>不要写 {@code Upgrade.values()}</b>——
     * 那会把其它注入者（Mek Extras 等）的几十种升级一并开放，本机一个都用不上。
     *
     * <p>写成方法而不是 {@code static final}：常量会在本类 {@code <clinit>} 求值，
     * 而 {@link MekCkUpgradeRefs#storage()} 读的是 Mixin 在 {@code Upgrade.<clinit>}
     * 的 TAIL 才赋值的字段。放进方法里，异常至少带着调用栈出现。
     */
    private static Set<mekanism.api.Upgrade> supportedUpgrades() {
        return Set.of(
                mekanism.api.Upgrade.SPEED,
                mekanism.api.Upgrade.ENERGY,
                MekCkUpgradeRefs.storage(),
                MekCkUpgradeRefs.randomize());
    }

    /**
     * 逐等级的方块译名。
     *
     * <p>本模组的 lang key 是 {@code block.mekck.<tier>_planting_cutting_factory}
     * （每等级一条），而 {@code MekCkFactoryType.PLANTING_CUTTING} 的 key 不带等级，
     * 对不上，所以这里自带一个按等级拼的 entry。</p>
     */
    private static final class PlantingCuttingLangEntry implements ILangEntry {
        private final CuttingMachineFactoryTier tier;

        PlantingCuttingLangEntry(CuttingMachineFactoryTier tier) {
            this.tier = tier;
        }

        @Override
        public String getTranslationKey() {
            return "block.mekck." + tier.getPlantingBlockId();
        }
    }
}

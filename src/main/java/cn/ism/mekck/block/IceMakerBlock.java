package cn.ism.mekck.block;

import cn.ism.mekck.machine.icemaker.IceMakerTile;
import mekanism.api.math.FloatingLong;
import mekanism.api.text.ILangEntry;
import mekanism.common.block.attribute.AttributeStateFacing;
import mekanism.common.block.attribute.Attributes;
import mekanism.common.block.prefab.BlockTile;
import mekanism.common.content.blocktype.BlockTypeTile;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import mekanism.common.registration.impl.TileEntityTypeRegistryObject;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * 急冻制冰机方块（Mek 体系版）—— 与 {@code NutRoasterBlock} / {@code GrillBlock} 逐行同构。
 *
 * <h3>换体系省下的代码</h3>
 * 本类<b>不再有</b> {@code createBlockStateDefinition} / {@code getStateForPlacement} /
 * {@code rotate} / {@code mirror}（{@link AttributeStateFacing} 接管）、
 * {@code newBlockEntity} / {@code getTicker}（{@link BlockTile} 接管）、
 * {@code getRenderShape} / {@code getLightEmission}（默认 MODEL / 0，旧实现写的是同值）、
 * {@code use}（{@code AttributeGui} 接管）、
 * {@code onRemove} + 手动掉物品（{@code BlockMekanism.onRemove} + 战利品表接管）、
 * {@code setPlacedBy} 里的自定义名字（{@code BlockMekanism.setPlacedBy} 接管）。
 *
 * <h3>从本类移走的三件事（与 {@code NutRoasterBlock} 逐条对应）</h3>
 * <ul>
 *   <li><b>「潜行 + 手持升级模块直接装进对应槽」</b>（旧 {@code use} 里的
 *       {@code addUpgradesFromHand}）：<b>功能保留、落点改变</b> ——
 *       本模组已有的 {@code UpgradeInstallHandler}（Forge 的 {@code RightClickBlock} 事件）
 *       本来就在处理这条路径，本轮把它的分支改指新 tile即可。冷萃不是 Mek 的
 *       {@code Upgrade}（Mek 的升级 tab 碰不到它），Mek 的能量卡走
 *       {@code TileComponentUpgrade} 的 20 tick 正常路径；</li>
 *   <li><b>方块侧 {@code SideMode} 枚举</b>：由 {@code TileComponentConfig} 取代（真删）；</li>
 *   <li><b>{@code FACING} / {@code ACTIVE} 两个自研 BlockState 属性</b>：
 *       由 {@link AttributeStateFacing} 与 {@code Attributes.ACTIVE} 提供
 *       （属性名与 blockstate JSON 里的 {@code facing=} / {@code active=} 逐字一致，
 *       贴图模型不用改）。</li>
 * </ul>
 */
public final class IceMakerBlock extends BlockTile<IceMakerTile, BlockTypeTile<IceMakerTile>> {

    public IceMakerBlock(BlockTypeTile<IceMakerTile> type,
                         UnaryOperator<BlockBehaviour.Properties> propertyModifier) {
        super(type, propertyModifier);
    }

    /**
     * 构造本方块的方块类型描述。
     *
     * <p>属性一个都不能少，<b>各自的缺失症状</b>：</p>
     * <ul>
     *   <li>{@code withGui} → 缺了右键不开界面；</li>
     *   <li>{@code withEnergyConfig} → {@code MachineEnergyContainer.input} 在构造时读它，
     *       缺了容量/能耗无处声明；</li>
     *   <li>{@code withSupportedUpgrades} → 缺了 {@code supportsUpgrades()} 为 false，
     *       升级槽与升级 tab 都不会出现；</li>
     *   <li>{@link AttributeStateFacing} → 缺了 blockstate 的 {@code facing=} 变体全部匹配失败，
     *       方块直接隐形。</li>
     * </ul>
     *
     * <p>音效不给：本机没有工作音（旧 {@code clientTick} 是空实现）。</p>
     *
     * @param containerRef 延迟引用：容器要等 tile/block 建好之后才能注册，
     *                     而 {@code AttributeGui} 的构造器又要求容器注册对象
     * @param tileRef      同理，{@code BlockTypeTile} 构造时就要 tile 的 Supplier，
     *                     而 tile 类型必须绑定方块之后才能建
     */
    public static BlockTypeTile<IceMakerTile> blockTypeFor(
            Supplier<ContainerTypeRegistryObject<? extends MekanismContainer>> containerRef,
            Supplier<TileEntityTypeRegistryObject<IceMakerTile>> tileRef) {

        BlockTypeTile.BlockTileBuilder<BlockTypeTile<IceMakerTile>, IceMakerTile, ?> builder =
                BlockTypeTile.BlockTileBuilder.createBlock(tileRef, LANG_ENTRY);

        builder.withGui(containerRef);

        // AttributeEnergy 的参数是 (usage, storage)（先用后容）。
        builder.withEnergyConfig(
                () -> FloatingLong.create(IceMakerTile.ENERGY_PER_TICK),
                () -> FloatingLong.create(IceMakerTile.ENERGY_CAPACITY));

        builder.withSupportedUpgrades(supportedUpgrades());

        // 默认构造器用的是 BlockStateProperties.HORIZONTAL_FACING，与旧方块的 4 向
        // blockstate JSON（facing=... + active=...）逐字一致。
        builder.with(new AttributeStateFacing());
        builder.with(Attributes.ACTIVE);
        builder.with(Attributes.REDSTONE);
        builder.with(Attributes.SECURITY);
        builder.with(Attributes.INVENTORY);

        return builder.build();
    }

    /**
     * 本机允许装进升级槽的类型：<b>只有能量</b>。
     *
     * <p>速度升级<b>刻意不支持</b>：本机的加工速度由机身温度决定
     * （见 {@code IceMakerTile.getEffectiveProcessTime}），与迁移前的
     * {@code supportsSpeedUpgrade() == false} / {@code SLOT_SPEED_UPGRADE} 恒拒同义。</p>
     */
    private static Set<mekanism.api.Upgrade> supportedUpgrades() {
        return Set.of(mekanism.api.Upgrade.ENERGY);
    }

    /** 方块译名：键必须与注册名一致，否则 GUI 标题与物品名显示 raw key。 */
    private static final ILangEntry LANG_ENTRY = () -> "block.mekck.ice_maker";
}

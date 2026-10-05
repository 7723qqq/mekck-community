package cn.ism.mekck.block;

import cn.ism.mekck.machine.roasting.NutRoasterTile;
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
 * 坚果爆炒机方块（Mek 体系版）—— 与 {@code GrillBlock} 逐行同构。
 *
 * <h3>换体系省下的代码</h3>
 * 本类<b>不再有</b> {@code createBlockStateDefinition} / {@code getStateForPlacement} /
 * {@code rotate} / {@code mirror}（{@link AttributeStateFacing} 接管）、
 * {@code newBlockEntity} / {@code getTicker}（{@link BlockTile} 接管）、
 * {@code getRenderShape} / {@code getLightEmission}（默认 MODEL / 0，旧实现写的是同值）、
 * {@code use}（{@code AttributeGui} 接管）、{@code onRemove} + 手动掉物品
 * （{@code BlockMekanism.onRemove} + 战利品表接管）、
 * {@code setPlacedBy} 里的自定义名字（{@code BlockMekanism.setPlacedBy} 接管）。
 *
 * <h3>被删掉的两条旧能力（与 {@code GrillBlock} 逐条对应）</h3>
 * <ul>
 *   <li><b>「潜行 + 手持升级模块直接装进对应槽」</b>（旧 {@code use} 里的
 *       {@code addUpgradesFromHand}）：由 Mek 升级 tab 取代，安装 / 卸载都走
 *       {@code TileComponentUpgrade} 的 20 tick 正常路径。</li>
 *   <li><b>方块侧 {@code SideMode} 枚举</b>：由 {@code TileComponentConfig} 取代。</li>
 * </ul>
 */
public final class NutRoasterBlock extends BlockTile<NutRoasterTile, BlockTypeTile<NutRoasterTile>> {

    public NutRoasterBlock(BlockTypeTile<NutRoasterTile> type,
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
    public static BlockTypeTile<NutRoasterTile> blockTypeFor(
            Supplier<ContainerTypeRegistryObject<? extends MekanismContainer>> containerRef,
            Supplier<TileEntityTypeRegistryObject<NutRoasterTile>> tileRef) {

        BlockTypeTile.BlockTileBuilder<BlockTypeTile<NutRoasterTile>, NutRoasterTile, ?> builder =
                BlockTypeTile.BlockTileBuilder.createBlock(tileRef, LANG_ENTRY);

        builder.withGui(containerRef);

        // AttributeEnergy 的参数是 (usage, storage)（先用后容）。
        builder.withEnergyConfig(
                () -> FloatingLong.create(NutRoasterTile.ENERGY_PER_TICK),
                () -> FloatingLong.create(NutRoasterTile.ENERGY_CAPACITY));

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
     * 本机允许装进升级槽的类型：速度 + 能量（与迁移前的自研升级槽同集合）。
     *
     * <p>刻意只列这两种：Mek 原生 7 种里其余 5 种本机用不上，而 {@code Upgrade.values()}
     * 还会把其它注入者（Mek Extras 等）的几十种升级一并开放。</p>
     */
    private static Set<mekanism.api.Upgrade> supportedUpgrades() {
        return Set.of(mekanism.api.Upgrade.SPEED, mekanism.api.Upgrade.ENERGY);
    }

    /** 方块译名：键必须与注册名一致，否则 GUI 标题与物品名显示 raw key。 */
    private static final ILangEntry LANG_ENTRY = () -> "block.mekck.nut_roaster";
}

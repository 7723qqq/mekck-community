package cn.ism.mekck.block;

import cn.ism.mekck.blockentity.GrillBlockEntity;
import mekanism.api.math.FloatingLong;
import mekanism.api.text.ILangEntry;
import mekanism.common.block.attribute.AttributeStateFacing;
import mekanism.common.block.attribute.Attributes;
import mekanism.common.block.prefab.BlockTile;
import mekanism.common.content.blocktype.BlockTypeTile;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.registries.MekanismSounds;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import mekanism.common.registration.impl.TileEntityTypeRegistryObject;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * 电力烧烤架方块（Mek 体系版）—— 与 {@code GrillFactoryBlock} 逐行同构，
 * 只是没有等级维度。
 *
 * <h3>换体系省下的代码</h3>
 * 本类<b>不再有</b> {@code createBlockStateDefinition}、
 * {@code getStateForPlacement} / {@code rotate} / {@code mirror}（{@link AttributeStateFacing} 接管）、
 * {@code newBlockEntity} / {@code getTicker}（{@link BlockTile} 接管）、
 * {@code use}（{@code AttributeGui} 接管）、{@code onRemove} + 手动掉物品
 * （{@code BlockMekanism.onRemove} + 战利品表接管）、
 * {@code setPlacedBy} 里的自定义名字（{@code BlockMekanism.setPlacedBy} 接管）。
 *
 * <h3>被删掉的两条旧能力（与 {@code GrillFactoryBlock} 逐条对应）</h3>
 * <ul>
 *   <li><b>「潜行 + 手持升级模块直接装进对应槽」</b>（旧 {@code addUpgradesFromHand}）。
 *       由 Mek 升级 tab 取代；卸载升级走升级 tab 的卸载按钮
 *       （{@code TileComponentUpgrade} 自带，不依赖方块侧的快捷键）。</li>
 *   <li><b>方块侧 {@code SideMode} 枚举</b>。由 {@code ISideConfiguration} 取代。</li>
 * </ul>
 */
public final class GrillBlock extends BlockTile<GrillBlockEntity, BlockTypeTile<GrillBlockEntity>> {

    public GrillBlock(BlockTypeTile<GrillBlockEntity> type,
                      UnaryOperator<BlockBehaviour.Properties> propertyModifier) {
        super(type, propertyModifier);
    }

    /**
     * 构造本方块的方块类型描述。
     *
     * <p>属性一个都不能少，<b>各自的缺失症状</b>：
     * <ul>
     *   <li>{@code withGui} → 缺了右键不开界面；</li>
     *   <li>{@code withEnergyConfig} → {@code MachineEnergyContainer.input} 在构造时读它，
     *       缺了容量/能耗无处声明（实测它内部走
     *       {@code validateBlock(tile).getStorage()/getUsage()}）；</li>
     *   <li>{@code withSupportedUpgrades} → 缺了 {@code supportsUpgrades()} 为 false，
     *       升级槽与升级 tab 都不会出现；</li>
     *   <li>{@link AttributeStateFacing} → 缺了 blockstate 的 {@code facing=} 变体全部匹配失败，
     *       方块直接隐形；</li>
     *   <li>{@code withSound} → 缺了 {@code hasSound()} 为 false，机器工作时彻底静音。</li>
     * </ul>
     *
     * @param containerRef 延迟引用：容器要等 tile/block 建好之后才能注册，
     *                     而 {@code AttributeGui} 的构造器又要求容器注册对象
     * @param tileRef      同理，{@code BlockTypeTile} 构造时就要 tile 的 Supplier，
     *                     而 tile 类型必须绑定方块之后才能建
     */
    public static BlockTypeTile<GrillBlockEntity> blockTypeFor(
            Supplier<ContainerTypeRegistryObject<? extends MekanismContainer>> containerRef,
            Supplier<TileEntityTypeRegistryObject<GrillBlockEntity>> tileRef) {

        BlockTypeTile.BlockTileBuilder<BlockTypeTile<GrillBlockEntity>, GrillBlockEntity, ?> builder =
                BlockTypeTile.BlockTileBuilder.createBlock(tileRef, new GrillLangEntry());

        builder.withGui(containerRef);

        // AttributeEnergy 的参数是 (usage, storage)（先用后容）。用 lambda 延迟取值，
        // 使 /reload 改配置后立即生效，也避免类初始化期就碰配置。
        builder.withEnergyConfig(
                () -> FloatingLong.create(GrillBlockEntity.ENERGY_PER_TICK),
                () -> FloatingLong.create(GrillBlockEntity.ENERGY_CAPACITY));

        builder.withSupportedUpgrades(supportedUpgrades());

        // 运行音效：旧 clientTick 里是 SoundHandler.startTileSound(PRECISION_SAWMILL, ...)。
        // 迁到 Mek 体系后由 AttributeSound 接管（TileEntityMekanism.updateSound 按
        // hasSound() + getActive() 自己起停），因此 clientTick 整个删掉。
        builder.withSound(MekanismSounds.PRECISION_SAWMILL);

        // 默认构造器用的是 BlockStateProperties.HORIZONTAL_FACING（实测其
        // 无参构造转发到 (boolean) 构造，而后者读的是 f_61374_ = HORIZONTAL_FACING），
        // 与旧方块的 4 向 blockstate JSON 逐字一致，因此这里不需要传自定义属性。
        builder.with(new AttributeStateFacing());
        builder.with(Attributes.ACTIVE);
        builder.with(Attributes.REDSTONE);
        builder.with(Attributes.SECURITY);
        builder.with(Attributes.INVENTORY);

        return builder.build();
    }

    /**
     * 本机允许装进升级槽的类型。
     *
     * <p>与 {@code TileEntityMekanism.getSupportedUpgrade()} 是<b>同一个来源</b>：
     * 后者读的就是本属性（实测 {@code supportsUpgrades()} 为真时返回
     * {@code Attribute.get(block, AttributeUpgradeSupport.class).supportedUpgrades()}），
     * 而 {@code TileComponentUpgrade} 的构造器再 {@code EnumSet.copyOf} 它。
     * 所以这一份清单同时决定「升级槽收哪几种卡」与「升级 tab 列哪几种」。</p>
     *
     * <p>刻意只列速度与能量：Mek 原生 7 种里其余 5 种（气体/矿浆/注入/颜料/过滤）
     * 本机一个都用不上，而 {@code Upgrade.values()} 还会把其它注入者
     * （Mek Extras 等）的几十种一并开放。<b>创造升级不在这里</b>——
     * Mek 的升级体系里没有这个概念，它由 {@code GrillBlockEntity.CREATIVE_SLOT}
     * 这个额外槽承载。</p>
     */
    private static Set<mekanism.api.Upgrade> supportedUpgrades() {
        return Set.of(mekanism.api.Upgrade.SPEED, mekanism.api.Upgrade.ENERGY);
    }

    /**
     * 方块译名。
     *
     * <p>语言键必须与注册名一致：方块注册为 {@code mekck:electric_grill}，
     * 语言文件里的键是 {@code block.mekck.electric_grill}。
     * 写错的表现是 GUI 标题与物品名显示 raw key。</p>
     */
    private static final class GrillLangEntry implements ILangEntry {
        @Override
        public String getTranslationKey() {
            return "block.mekck.electric_grill";
        }
    }
}

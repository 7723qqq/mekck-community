package cn.ism.mekck.block;

import cn.ism.mekck.machine.cutting.UniversalCuttingMachineTile;
import cn.ism.mekck.upgrade.MekCkUpgradeRefs;
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
 * 切菜机方块（Mek 体系版）—— 第四轮从自研 {@code BaseEntityBlock} 换成 Mek 的 {@link BlockTile}。
 *
 * <h3>换体系省下的代码</h3>
 * 本类<b>不再有</b> {@code createBlockStateDefinition}、
 * {@code getStateForPlacement} / {@code rotate} / {@code mirror}（{@link AttributeStateFacing} 接管）、
 * {@code newBlockEntity} / {@code getTicker}（{@link BlockTile} 接管）、
 * {@code use}（{@code AttributeGui} 接管）、{@code onRemove} + 手动掉物品
 * （{@code BlockMekanism.onRemove} + 战利品表接管）、
 * {@code setPlacedBy} 里的自定义名字。
 *
 * <p><b>注册名一个字没改</b>（仍是 {@code mekck:universal_cutting_machine}）。</p>
 *
 * <h3>随之作废的两条旧能力（与 {@code GrillBlock} 逐条对应）</h3>
 * <ul>
 *   <li>「潜行 + 手持升级模块直接装进对应槽」（旧 {@code addUpgradesFromHand}）
 *       —— 由 Mek 升级 tab 取代；</li>
 *   <li>方块侧的 {@code SideMode} 枚举 —— 由 {@code ISideConfiguration} 取代。</li>
 * </ul>
 */
public final class UniversalCuttingMachineBlock
        extends BlockTile<UniversalCuttingMachineTile, BlockTypeTile<UniversalCuttingMachineTile>> {

    public UniversalCuttingMachineBlock(BlockTypeTile<UniversalCuttingMachineTile> type,
                                       UnaryOperator<BlockBehaviour.Properties> propertyModifier) {
        super(type, propertyModifier);
    }

    /**
     * 构造本方块的方块类型描述。
     *
     * <p>属性一个都不能少，<b>各自的缺失症状</b>：
     * <ul>
     *   <li>{@code withGui} → 缺了右键不开界面；</li>
     *   <li>{@code withEnergyConfig} → {@code MachineEnergyContainer.input} 在构造时读它
     *       （实测走 {@code validateBlock(tile).getStorage()/getUsage()}），
     *       缺了容量/能耗无处声明；</li>
     *   <li>{@code withSupportedUpgrades} → 缺了 {@code supportsUpgrades()} 为 false，
     *       升级槽与升级 tab 都不出现；</li>
     *   <li>{@link AttributeStateFacing} → 缺了 blockstate 的 {@code facing=} 变体全部匹配失败，
     *       方块直接<b>隐形</b>；</li>
     *   <li>{@code withSound} → 缺了机器工作时彻底静音。</li>
     * </ul>
     *
     * @param containerRef 延迟引用：容器要等 tile/block 建好之后才能注册
     * @param tileRef      同理 —— {@code BlockTypeTile} 构造时就要 tile 的 Supplier，
     *                     而 tile 的构造又需要方块，形成先后依赖
     */
    public static BlockTypeTile<UniversalCuttingMachineTile> blockTypeFor(
            Supplier<ContainerTypeRegistryObject<? extends MekanismContainer>> containerRef,
            Supplier<TileEntityTypeRegistryObject<UniversalCuttingMachineTile>> tileRef) {

        BlockTypeTile.BlockTileBuilder<BlockTypeTile<UniversalCuttingMachineTile>,
                UniversalCuttingMachineTile, ?> builder =
                BlockTypeTile.BlockTileBuilder.createBlock(tileRef, LANG_ENTRY);

        builder.withGui(containerRef);

        // AttributeEnergy 的参数是 (usage, storage)（先用后容）。用 lambda 延迟取值，
        // 使 /reload 改配置后立即生效。
        builder.withEnergyConfig(
                () -> FloatingLong.create(UniversalCuttingMachineTile.ENERGY_PER_TICK),
                () -> FloatingLong.create(UniversalCuttingMachineTile.ENERGY_CAPACITY));

        builder.withSupportedUpgrades(supportedUpgrades());

        // 运行音效：旧 clientTick 里是 SoundHandler.startTileSound(PRECISION_SAWMILL, …)，
        // 迁到 Mek 体系后由 AttributeSound 接管（updateSound 按 hasSound() + getActive() 自启停）。
        builder.withSound(MekanismSounds.PRECISION_SAWMILL);

        // 默认构造器用 BlockStateProperties.HORIZONTAL_FACING，与旧方块的 4 向
        // blockstate JSON 逐字一致，因此不需要传自定义属性。
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
     * <p>与 {@code UniversalCuttingMachineTile#getSupportedUpgrade()} 是<b>同一来源</b>：
     * 后者读的就是本属性（{@code supportsUpgrades()} 为真时返回
     * {@code Attribute.get(block, AttributeUpgradeSupport.class).supportedUpgrades()}），
     * 而 {@code TileComponentUpgrade} 构造器再 {@code EnumSet.copyOf} 它。
     * 所以这一份清单同时决定「升级槽收哪几种卡」与「升级 tab 列哪几种」。</p>
     *
     * <p>「创造卡」走 {@link MekCkUpgradeRefs#randomize()} 而非 {@code Upgrade.CREATIVE} ——
     * Mek Extras 也注入了一个同名 {@code CREATIVE}，两者会抢同一个注册 id，
     * 详见 {@link MekCkUpgradeRefs} 类注释。</p>
     */
    private static Set<mekanism.api.Upgrade> supportedUpgrades() {
        return Set.of(
                mekanism.api.Upgrade.SPEED,
                mekanism.api.Upgrade.ENERGY,
                MekCkUpgradeRefs.randomize());
    }

    /** 方块译名：键必须与注册名一致，否则 GUI 标题与物品名显示 raw key。 */
    private static final ILangEntry LANG_ENTRY =
            () -> "block.mekck.universal_cutting_machine";
}

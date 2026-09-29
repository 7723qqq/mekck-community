package cn.ism.mekck.block;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.machine.skewering.SkeweringFactoryTile;
import cn.ism.mekck.upgrade.MekCkUpgradeRefs;
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
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * 穿串工厂方块（Mek 体系版）—— 阶段 3 Task 5 把它从自研 {@code BaseEntityBlock}
 * 换成 Mek 的 {@link BlockTile}。
 *
 * <h3>与切菜 / 烧烤方块逐行同构</h4>
 * 四个属性（GUI / 能量 / 升级 / 朝向）、4 种可装升级、按等级拼的译名 entry，
 * 全部照 {@code CuttingMachineFactoryBlock} 原样。换体系省下的是：
 * <b>{@code createBlockStateDefinition} / {@code getStateForPlacement} / {@code rotate} /
 * {@code mirror} / {@code newBlockEntity} / {@code getTicker} / {@code use} /
 * {@code onRemove} + 手动掉物品</b> 这八段，全部交给
 * {@link AttributeStateFacing} / {@code AttributeGui} / {@code BlockMekanism} 与 loot table。
 *
 * <p>运行音效：旧实现在 {@code clientTick} 里手写
 * {@code SoundHandler.startTileSound(MekanismSounds.ENRICHMENT_CHAMBER, …)}——
 * 那是<b>浓饰舱</b>的声音，穿串用它一直是错的。换成 Mek 基类后那段代码没有了，
 * 播放改由 {@code TileEntityMekanism} 按方块的 AttributeSound 驱动，
 * 而 AttributeSound 只能经 {@code BlockTileBuilder.withSound} 挂上去；
 * 不挂就是 {@code hasSound() == false}，机器工作时彻底静音。
 * 这里改借旋转类机器的循环音，语义上比浓饰舱近。</p>
 */
public final class SkeweringFactoryBlock extends BlockTile<SkeweringFactoryTile, BlockTypeTile<SkeweringFactoryTile>> {

    private final CuttingMachineFactoryTier tier;

    public SkeweringFactoryBlock(BlockTypeTile<SkeweringFactoryTile> type,
                                 CuttingMachineFactoryTier tier,
                                 UnaryOperator<BlockBehaviour.Properties> propertyModifier) {
        super(type, propertyModifier);
        this.tier = tier;
    }

    /**
     * 本方块的等级。
     *
     * <p>{@link SkeweringFactoryTile} 在构造期就靠它反查档位（基类注释的
     * 「构造期顺序陷阱」），所以必须由构造参数带进来。</p>
     */
    public CuttingMachineFactoryTier getTier() {
        return tier;
    }

    // ── 方块类型描述 ────────────────────────────────────────────────────

    /**
     * 构造本等级穿串工厂的方块类型描述。
     *
     * <p>四个属性一个都不能少，<b>各自的缺失症状</b>：
     * <ul>
     *   <li>{@code withGui} → 缺了右键不开界面；</li>
     *   <li>{@code withEnergyConfig} → {@code MachineEnergyContainer.input} 在构造时读它，
     *       缺了容量/能耗无处声明；</li>
     *   <li>{@code withSupportedUpgrades} → 缺了 {@code supportsUpgrades()} 为 false，
     *       升级槽与升级 tab 都不会出现；</li>
     *   <li>{@link AttributeStateFacing} → 缺了 blockstate 的 {@code facing=} 变体全部匹配失败，
     *       方块直接隐形。</li>
     * </ul>
     */
    public static BlockTypeTile<SkeweringFactoryTile> blockTypeFor(
            CuttingMachineFactoryTier tier,
            Supplier<ContainerTypeRegistryObject<? extends MekanismContainer>> containerRef,
            Supplier<TileEntityTypeRegistryObject<SkeweringFactoryTile>> tileRef) {

        BlockTypeTile.BlockTileBuilder<BlockTypeTile<SkeweringFactoryTile>, SkeweringFactoryTile, ?> builder =
                BlockTypeTile.BlockTileBuilder.createBlock(tileRef, new SkeweringFactoryLangEntry(tier));

        builder.withGui(containerRef);

        // AttributeEnergy 的参数是 (usage, storage)（先用后容）。用 lambda 延迟取值，
        // 使 /reload 改 MekckConfig 后立即生效，也避免类初始化期就碰配置。
        builder.withEnergyConfig(
                () -> FloatingLong.create(tier.energyPerTick),
                () -> FloatingLong.create(tier.energyCapacity));

        builder.withSupportedUpgrades(supportedUpgrades());
        builder.withSound(MekanismSounds.ROTARY_CONDENSENTRATOR);

        builder.with(new AttributeStateFacing());
        builder.with(Attributes.ACTIVE);
        builder.with(Attributes.REDSTONE);
        builder.with(Attributes.SECURITY);
        builder.with(Attributes.INVENTORY);

        return builder.build();
    }

    /**
     * 本模组允许装进穿串工厂的升级类型。
     *
     * <p>与 {@code MekCkMachineTile#getSupportedUpgrade()} 是<b>两道不同的闸门</b>：
     * 这里决定方块属性 {@code AttributeUpgradeSupport}（进而决定升级槽与升级 tab
     * 是否出现），那个方法决定 {@code TileComponentUpgrade} 收哪几种卡。
     * 缺任何一道都会表现为「升级槽能看见但什么都装不进去」或反之。</p>
     *
     * <p>刻意只列这 4 种，绝不能写 {@code Upgrade.values()}——那会把其它注入者
     * （Mek Extras 等）的几十种升级一并开放，而本机一个都用不上。</p>
     *
     * <p><b>写成方法而不是 {@code static final} 常量</b>：常量会在本类
     * {@code <clinit>} 求值，而 {@link MekCkUpgradeRefs#storage()} 读的是
     * {@code MixinUpgrade} 在 {@code Upgrade.<clinit>} 的 TAIL 才赋值的字段。
     * 放进方法里，异常至少会带着「正在建升级清单」的调用栈出现。</p>
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
     * <p>本模组的 lang key 是 {@code block.mekck.<tier>_skewering_factory}（每等级一条），
     * 而 {@code MekCkFactoryType.SKEWERING} 的译名 key 不带等级，对不上，
     * 所以这里自带一个按等级拼的 lang entry。</p>
     */
    private static final class SkeweringFactoryLangEntry implements ILangEntry {
        private final CuttingMachineFactoryTier tier;

        SkeweringFactoryLangEntry(CuttingMachineFactoryTier tier) {
            this.tier = tier;
        }

        @Override
        public String getTranslationKey() {
            return "block.mekck." + tier.getSkeweringBlockId();
        }
    }
}

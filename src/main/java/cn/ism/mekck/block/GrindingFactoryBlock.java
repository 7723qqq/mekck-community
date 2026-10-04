package cn.ism.mekck.block;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.machine.IFactoryTierProvider;
import cn.ism.mekck.machine.grinding.GrindingFactoryTile;
import cn.ism.mekck.upgrade.MekCkUpgradeRefs;
import mekanism.api.math.FloatingLong;
import mekanism.api.text.ILangEntry;
import mekanism.common.block.attribute.AttributeEnergy;
import mekanism.common.block.attribute.AttributeStateFacing;
import mekanism.common.block.attribute.Attributes;
import mekanism.common.block.prefab.BlockTile;
import mekanism.common.content.blocktype.BlockTypeTile;
import mekanism.common.registries.MekanismSounds;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import mekanism.common.registration.impl.TileEntityTypeRegistryObject;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * 研磨工厂方块（Mek 体系版）—— 阶段 3 Task 1 把它从自研 {@code BaseEntityBlock}
 * 换成 Mek 的 {@link BlockTile}。
 *
 * <h3>换掉之后哪些行为由 Mek 接管</h3>
 * 与切菜工厂同一份清单（那边有逐条的字节码依据，这里不重复）：
 * 朝向/运行状态属性、破坏掉落、右键开界面、比较器、安全、
 * 能量与侧配能力，全部改由 {@code TileEntityMekanism} 按方块属性自动开通。
 *
 * <p><b>注册名一个字没改</b>（仍是 {@code mekck:<tier>_grinding_factory}），
 * 所以旧存档里已放置的方块不会变成空气；变的是它挂的
 * {@code BlockEntityType} 的实现类，而旧内容由
 * {@code MekCkLegacyMachineNbt} 在读档时整体翻译。
 *
 * <h3>随旧实现一起作废的三样东西</h3>
 * <ul>
 *   <li>「潜行 + 手持升级模块直接装进对应槽」快捷键（{@code addUpgradesFromHand}）——
 *       由 Mek 升级 tab 取代；</li>
 *   <li>方块侧的 {@code SideMode} 枚举——由 {@code ISideConfiguration} 取代；</li>
 *   <li>{@code onRemove} 里「saveToItem 后手动掉实体」与配套的空
 *       {@code getDrops}——由 Mek 的 {@code BlockMekanism.onRemove} 与
 *       {@code data/mekck/loot_tables/blocks/<id>.json} 接管。</li>
 * </ul>
 */
public final class GrindingFactoryBlock extends BlockTile<GrindingFactoryTile, BlockTypeTile<GrindingFactoryTile>>
        implements IFactoryTierProvider {

    private final CuttingMachineFactoryTier tier;

    public GrindingFactoryBlock(BlockTypeTile<GrindingFactoryTile> type,
                                CuttingMachineFactoryTier tier,
                                UnaryOperator<BlockBehaviour.Properties> propertyModifier) {
        super(type, propertyModifier);
        this.tier = tier;
    }

    /**
     * 本方块的等级。
     *
     * <p>{@code GrindingFactoryTile} 在构造期就靠它反查档位（基类注释的
     * 「构造期顺序陷阱」），所以它必须由构造参数带进来、不能事后从别处读。</p>
     */
    public CuttingMachineFactoryTier getTier() {
        return tier;
    }

    // ── 方块类型描述 ────────────────────────────────────────────────────

    /**
     * 构造本等级研磨工厂的方块类型描述。
     *
     * <p>四个属性一个都不能少（各自的缺失症状见
     * {@code CuttingMachineFactoryBlock.blockTypeFor} 的注释）：
     * {@code withGui} / {@code withEnergyConfig} / {@code withSupportedUpgrades} /
     * {@link AttributeStateFacing}。
     *
     * @param containerRef 延迟引用：容器要等 tile/block 建好之后才能注册
     * @param tileRef      同理，{@code BlockTypeTile} 构造时就要 tile 的 Supplier
     */
    public static BlockTypeTile<GrindingFactoryTile> blockTypeFor(
            CuttingMachineFactoryTier tier,
            Supplier<ContainerTypeRegistryObject<? extends mekanism.common.inventory.container.MekanismContainer>> containerRef,
            Supplier<TileEntityTypeRegistryObject<GrindingFactoryTile>> tileRef) {

        BlockTypeTile.BlockTileBuilder<BlockTypeTile<GrindingFactoryTile>, GrindingFactoryTile, ?> builder =
                BlockTypeTile.BlockTileBuilder.createBlock(tileRef, new GrindingFactoryLangEntry(tier));

        builder.withGui(containerRef);

        // AttributeEnergy 的参数是 (usage, storage)（先用后容）。
        // 用 lambda 延迟取值，使 /reload 改 MekckConfig 后立即生效。
        builder.withEnergyConfig(
                () -> FloatingLong.create(tier.energyPerTick),
                () -> FloatingLong.create(tier.energyCapacity));

        builder.withSupportedUpgrades(supportedUpgrades());

        // 运行音效：旧实现在 clientTick 里手写 SoundHandler.startTileSound(CRUSHER, ...)，
        // 换成 Mek 基类后那段代码没有了，播放改由 TileEntityMekanism 按方块的
        // AttributeSound 驱动——不挂 withSound 就没有 soundEvent，机器工作时彻底静音。
        builder.withSound(MekanismSounds.CRUSHER);

        builder.with(new AttributeStateFacing());
        builder.with(Attributes.ACTIVE);
        builder.with(Attributes.REDSTONE);
        builder.with(Attributes.SECURITY);
        builder.with(Attributes.INVENTORY);

        return builder.build();
    }

    /**
     * 本模组允许装进研磨工厂的升级类型。
     *
     * <p>与 {@code MekCkMachineTile#getSupportedUpgrade()} 是<b>两道不同的闸门</b>：
     * 这里决定方块属性 {@code AttributeUpgradeSupport}（进而决定
     * {@code supportsUpgrades()} 与升级槽/升级 tab 是否出现），
     * 那个方法决定 {@code TileComponentUpgrade} 收哪几种卡。缺任何一道都会表现为
     * 「升级槽能看见但什么都装不进去」或反之。</p>
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
     * <p>本模组的 lang key 是 {@code block.mekck.<tier>_grinding_factory}（每个等级一条），
     * 而 {@code MekCkFactoryType.GRINDING} 的译名 key 是
     * {@code block.mekck.grinding_factory}——不带等级，对不上。
     * 所以这里自带一个按等级拼的 lang entry。</p>
     */
    private static final class GrindingFactoryLangEntry implements ILangEntry {
        private final CuttingMachineFactoryTier tier;

        GrindingFactoryLangEntry(CuttingMachineFactoryTier tier) {
            this.tier = tier;
        }

        @Override
        public String getTranslationKey() {
            return "block.mekck." + tier.getGrindingBlockId();
        }
    }
}

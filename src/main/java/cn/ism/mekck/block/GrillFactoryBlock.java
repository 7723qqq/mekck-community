package cn.ism.mekck.block;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.machine.IFactoryTierProvider;
import cn.ism.mekck.machine.grill.GrillFactoryTile;
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
 * 烧烤工厂方块（Mek 体系版）—— 阶段 3 Task 3 把它从自研 {@code BaseEntityBlock}
 * 换成 Mek 的 {@link BlockTile}。
 *
 * <h3>与切菜方块逐行同构的部分</h3>
 * 四个属性（GUI / 能量 / 升级 / 朝向）、声音、{@code withSupportedUpgrades} 的 4 种卡、
 * 按等级拼的译名 entry，全部照 {@code CuttingMachineFactoryBlock} 原样——
 * 换体系省下的代码就在这里：<b>本类不再有</b> {@code createBlockStateDefinition}、
 * {@code getStateForPlacement} / {@code rotate} / {@code mirror}（{@link AttributeStateFacing} 接管）、
 * {@code newBlockEntity} / {@code getTicker}（{@link BlockTile} 接管）、
 * {@code use}（{@code AttributeGui} 接管）、{@code onRemove} + 手动掉物品
 * （{@code BlockMekanism.onRemove} + loot table 接管）。
 *
 * <h3>被删掉的两条旧能力</h3>
 * <ul>
 *   <li><b>「潜行 + 手持升级模块直接装进对应槽」</b>（旧 {@code addUpgradesFromHand}）。
 *       由 Mek 升级 tab 取代；卸载升级则走升级 tab 的卸载按钮
 *       （{@code TileComponentUpgrade} 自带，不依赖方块侧的快捷键）。</li>
 *   <li><b>方块侧 {@code SideMode} 枚举</b>。由 {@code ISideConfiguration} 取代。</li>
 * </ul>
 */
public final class GrillFactoryBlock extends BlockTile<GrillFactoryTile, BlockTypeTile<GrillFactoryTile>>
        implements IFactoryTierProvider {

    private final CuttingMachineFactoryTier tier;

    public GrillFactoryBlock(BlockTypeTile<GrillFactoryTile> type,
                             CuttingMachineFactoryTier tier,
                             UnaryOperator<BlockBehaviour.Properties> propertyModifier) {
        super(type, propertyModifier);
        this.tier = tier;
    }

    /**
     * 本方块的等级。
     *
     * <p>{@link GrillFactoryTile} 在构造期就靠它反查档位（基类注释的「构造期顺序陷阱」），
     * 所以必须由构造参数带进来、不能事后从别处读。</p>
     */
    public CuttingMachineFactoryTier getTier() {
        return tier;
    }

    // ── 方块类型描述 ────────────────────────────────────────────────────

    /**
     * 构造本等级烧烤工厂的方块类型描述。
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
     *
     * @param containerRef 延迟引用：容器要等 tile/block 建好之后才能注册，
     *                     而 {@code AttributeGui} 的构造器又要求容器注册对象
     * @param tileRef      同理，{@code BlockTypeTile} 构造时就要 tile 的 Supplier，
     *                     而 tile 类型必须绑定方块之后才能建
     */
    public static BlockTypeTile<GrillFactoryTile> blockTypeFor(
            CuttingMachineFactoryTier tier,
            Supplier<ContainerTypeRegistryObject<? extends MekanismContainer>> containerRef,
            Supplier<TileEntityTypeRegistryObject<GrillFactoryTile>> tileRef) {

        BlockTypeTile.BlockTileBuilder<BlockTypeTile<GrillFactoryTile>, GrillFactoryTile, ?> builder =
                BlockTypeTile.BlockTileBuilder.createBlock(tileRef, new GrillFactoryLangEntry(tier));

        builder.withGui(containerRef);

        // AttributeEnergy 的参数是 (usage, storage)（先用后容）。用 lambda 延迟取值，
        // 使 /reload 改 MekckConfig 后立即生效，也避免类初始化期就碰配置。
        builder.withEnergyConfig(
                () -> FloatingLong.create(tier.energyPerTick),
                () -> FloatingLong.create(tier.energyCapacity));

        builder.withSupportedUpgrades(supportedUpgrades());

        // 运行音效：烤串是在转的机器，借 Mek 旋转类机器的循环音。
        // 不挂 AttributeSound 的话 hasSound() 为 false，机器工作时会彻底静音。
        builder.withSound(MekanismSounds.ROTARY_CONDENSENTRATOR);

        builder.with(new AttributeStateFacing());
        builder.with(Attributes.ACTIVE);
        builder.with(Attributes.REDSTONE);
        builder.with(Attributes.SECURITY);
        builder.with(Attributes.INVENTORY);

        return builder.build();
    }

    /**
     * 本模组允许装进烧烤工厂的升级类型。
     *
     * <p>与 {@code MekCkMachineTile#getSupportedUpgrade()} 是<b>两道不同的闸门</b>：
     * 这里的 {@code withSupportedUpgrades} 决定方块属性 {@code AttributeUpgradeSupport}
     * （进而决定 {@code supportsUpgrades()} 与升级槽/升级 tab 是否出现），
     * 那个方法决定 {@code TileComponentUpgrade} 收哪几种卡。缺任何一道都会表现为
     * 「升级槽能看见但什么都装不进去」或反之。</p>
     *
     * <p>刻意只列这 4 种：速度 / 能量是 Mek 原生，存储卡与随机化卡是本模组注入的。
     * 绝不能写 {@code Upgrade.values()}——那会把其它注入者（Mek Extras 等）的几十种
     * 升级一并开放，而本机一个都用不上。</p>
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
     * <p>本模组的 lang key 是 {@code block.mekck.<tier>_grill_factory}（每个等级一条，
     * 实测 {@code en_us.json} 的 {@code block.mekck.basic_grill_factory} 等 12 条），
     * 而 {@code MekCkFactoryType.GRILLING} 的译名 key 不带等级，对不上，
     * 所以这里自带一个按等级拼的 lang entry。</p>
     */
    private static final class GrillFactoryLangEntry implements ILangEntry {
        private final CuttingMachineFactoryTier tier;

        GrillFactoryLangEntry(CuttingMachineFactoryTier tier) {
            this.tier = tier;
        }

        @Override
        public String getTranslationKey() {
            return "block.mekck." + tier.getGrillingBlockId();
        }
    }
}

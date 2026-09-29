package cn.ism.mekck.block;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.machine.cutting.CuttingFactoryTile;
import mekanism.api.math.FloatingLong;
import mekanism.api.text.ILangEntry;
import mekanism.common.block.attribute.AttributeEnergy;
import mekanism.common.block.attribute.AttributeStateFacing;
import mekanism.common.block.attribute.Attributes;
import mekanism.common.block.prefab.BlockTile;
import mekanism.common.content.blocktype.BlockTypeTile;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import mekanism.common.registries.MekanismSounds;
import mekanism.common.registration.impl.TileEntityTypeRegistryObject;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * 切菜工厂方块（Mek 体系版）—— 阶段 2 Task 4 把它从自研 {@code BaseEntityBlock}
 * 换成 Mek 的 {@link BlockTile}。
 *
 * <h3>换掉之后哪些行为由 Mek 接管</h3>
 * <ul>
 *   <li><b>朝向 / 运行状态</b>：{@link AttributeStateFacing}（默认即
 *       {@code BlockStateProperties.HORIZONTAL_FACING}）与 {@link Attributes.ACTIVE}
 *       自动往 state 里加属性，并实现 {@code getStateForPlacement} / {@code rotate} / {@code mirror}。
 *       现有 blockstate JSON 用的正是 {@code facing=...,active=...}（实测
 *       {@code assets/mekck/blockstates/basic_cutting_factory.json}），因此
 *       <b>贴图与模型一个都不用改</b>。</li>
 *   <li><b>破坏掉落</b>：旧实现覆写 {@code getDrops} 返空、在 {@code onRemove} 里
 *       {@code saveToItem} 后手动丢实体；Mek 的 {@code BlockMekanism.onRemove} 已经处理，
 *       方块本身由 {@code data/mekck/loot_tables/blocks/<id>.json} 掉出。</li>
 *   <li><b>右键开界面</b>：{@link BlockTile#use} 读方块属性里的 {@code AttributeGui}，
 *       缺它就毫无反应（实测 {@code BlockTile.use} 字节码：
 *       {@code if (type.has(AttributeGui.class)) return tile.openGui(player);}）。</li>
 * </ul>
 *
 * <p>旧实现里的「潜行 + 手持升级模块直接装进对应槽」快捷键（{@code addUpgradesFromHand}）
 * 与方块侧配的 {@code SideMode} 枚举一并作废：前者由 Mek 升级 tab 取代，
 * 后者由 {@code ISideConfiguration} 取代。</p>
 */
public final class CuttingMachineFactoryBlock extends BlockTile<CuttingFactoryTile, BlockTypeTile<CuttingFactoryTile>> {

    private final CuttingMachineFactoryTier tier;

    public CuttingMachineFactoryBlock(BlockTypeTile<CuttingFactoryTile> type,
                                      CuttingMachineFactoryTier tier,
                                      UnaryOperator<BlockBehaviour.Properties> propertyModifier) {
        super(type, propertyModifier);
        this.tier = tier;
    }

    /**
     * 本方块的等级。
     *
     * <p>{@code CuttingFactoryTile} 在构造期就靠它反查档位（基类注释的「构造期顺序陷阱」），
     * 所以它必须由构造参数带进来、不能事后从别处读。</p>
     */
    public CuttingMachineFactoryTier getTier() {
        return tier;
    }

    // ── 方块类型描述 ────────────────────────────────────────────────────

    /**
     * 构造本等级切菜工厂的方块类型描述。
     *
     * <p>抄自 {@code factory/MekCkFactoryRegistration#blockTypeFor}，四个属性一个都不能少，
     * 各自的缺失症状都写在那里的注释里：
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
    public static BlockTypeTile<CuttingFactoryTile> blockTypeFor(
            CuttingMachineFactoryTier tier,
            Supplier<ContainerTypeRegistryObject<? extends mekanism.common.inventory.container.MekanismContainer>> containerRef,
            Supplier<TileEntityTypeRegistryObject<CuttingFactoryTile>> tileRef) {

        BlockTypeTile.BlockTileBuilder<BlockTypeTile<CuttingFactoryTile>, CuttingFactoryTile, ?> builder =
                BlockTypeTile.BlockTileBuilder.createBlock(tileRef, new CuttingFactoryLangEntry(tier));

        builder.withGui(containerRef);

        // AttributeEnergy 的参数是 (usage, storage)（先用后容）。
        // 用 lambda 延迟取值，使 /reload 改 MekckConfig 后立即生效，也避免类初始化期就碰配置。
        builder.withEnergyConfig(
                () -> FloatingLong.create(tier.energyPerTick),
                () -> FloatingLong.create(tier.energyCapacity));

        builder.withSupportedUpgrades(supportedUpgrades());

        // 运行音效：旧实现在 clientTick 里手写 SoundHandler.startTileSound(PRECISION_SAWMILL, ...)
        // 来放「机器在转」的声音。换成 Mek 基类后那段代码没有了，播放改由
        // TileEntityMekanism 按方块的 AttributeSound 驱动，而 AttributeSound 只能经
        // BlockTileBuilder.withSound 挂上去——不挂就没有 soundEvent，hasSound() 为 false，
        // 于是机器工作时彻底静音（实测 hasSound 门控见 setSupportedTypes 与播放点两处）。
        builder.withSound(MekanismSounds.PRECISION_SAWMILL);

        builder.with(new AttributeStateFacing());
        builder.with(Attributes.ACTIVE);
        builder.with(Attributes.REDSTONE);
        builder.with(Attributes.SECURITY);
        builder.with(Attributes.INVENTORY);

        return builder.build();
    }

    /**
     * 本模组允许装进切菜工厂的升级类型。
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
     * {@code <clinit>} 求值，而 {@link cn.ism.mekck.upgrade.MekCkUpgradeRefs#storage()}
     * 读的是 {@code MixinUpgrade} 在 {@code Upgrade.<clinit>} 的 TAIL 才赋值的字段。
     * 把注入点提前到类初始化期就等于依赖一个没有契约保证的时序；
     * 放进方法里，异常至少会带着「正在建升级清单」的调用栈出现。
     * 同一个理由见 {@code MekCkMachineTile#candidateUpgrades()} 的注释。</p>
     */
    private static Set<mekanism.api.Upgrade> supportedUpgrades() {
        return Set.of(
                mekanism.api.Upgrade.SPEED,
                mekanism.api.Upgrade.ENERGY,
                cn.ism.mekck.upgrade.MekCkUpgradeRefs.storage(),
                cn.ism.mekck.upgrade.MekCkUpgradeRefs.randomize());
    }

    /**
     * 逐等级的方块译名。
     *
     * <p>本模组的 lang key 是 {@code block.mekck.<tier>_cutting_factory}（每个等级一条，
     * 实测 {@code en_us.json} / {@code zh_cn.json} 的第 17 行起），
     * 而 {@code MekCkFactoryType.CUTTING} 指向的是 {@code block.mekck.cutting_factory}
     * ——那是 {@code mekckfactory} 那套新方块用的 key，对不上。所以这里自带一个。</p>
     */
    private static final class CuttingFactoryLangEntry implements ILangEntry {
        private final CuttingMachineFactoryTier tier;

        CuttingFactoryLangEntry(CuttingMachineFactoryTier tier) {
            this.tier = tier;
        }

        @Override
        public String getTranslationKey() {
            return "block.mekck." + tier.getBlockId();
        }
    }
}

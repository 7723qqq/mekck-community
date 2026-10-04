package cn.ism.mekck.block;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.machine.IFactoryTierProvider;
import cn.ism.mekck.machine.cooking.CookingFactoryTile;
import cn.ism.mekck.upgrade.MekCkUpgradeRefs;
import cn.ism.mekck.util.FluidContainerInteract;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * 烹饪工厂方块（Mek 体系版）—— 阶段 3 Task 7 把它从自研 {@code BaseEntityBlock}
 * 换成 Mek 的 {@link BlockTile}。
 *
 * <h3>与切菜 / 烧烤 / 穿串方块逐行同构的部分</h3>
 * 四个属性（GUI / 能量 / 升级 / 朝向）、4 种可装升级、按等级拼的译名 entry，
 * 全部照 {@code SkeweringFactoryBlock} 原样。换体系省下的是：
 * {@code createBlockStateDefinition} / {@code getStateForPlacement} / {@code rotate} /
 * {@code mirror} / {@code newBlockEntity} / {@code getTicker} / {@code onRemove}
 * + 手动掉物品 —— 这八段全部交给 {@link AttributeStateFacing} /
 * {@code AttributeGui} / {@code BlockMekanism.onRemove} + loot table。
 *
 * <h3>本类<b>保留</b>的 {@code use} 覆写：手持流体容器灌罐</h3>
 * 这不是「旧体系的债」，是本机独有能力：玩家手持水桶/水瓶右键机器把流体灌进三个罐，
 * 空桶回手上。管道走 {@code ForgeCapabilities.FLUID_HANDLER}，而那条路玩家用不到。
 * Mek 的 {@link BlockTile#use} 只管开界面，所以这里覆写并<b>先试灌罐、失败再交回
 * {@code super.use}</b>——后者才会触发 {@code AttributeGui} 开界面。
 *
 * <p>能直接灌是因为 Mek 的 {@code IExtendedFluidTank} 继承 Forge 的
 * {@code IFluidTank}，而 {@code IFluidTank extends IFluidHandler}——
 * 旧的 {@code FluidContainerInteract.tryFillMachine(IFluidHandler, ItemStack)}
 * 因此原样可用，不需要改。</p>
 */
public final class CookingFactoryBlock extends BlockTile<CookingFactoryTile, BlockTypeTile<CookingFactoryTile>>
        implements IFactoryTierProvider {

    private final CuttingMachineFactoryTier tier;

    public CookingFactoryBlock(BlockTypeTile<CookingFactoryTile> type,
                               CuttingMachineFactoryTier tier,
                               UnaryOperator<BlockBehaviour.Properties> propertyModifier) {
        super(type, propertyModifier);
        this.tier = tier;
    }

    /**
     * 本方块的等级。
     *
     * <p>{@link CookingFactoryTile} 在构造期就靠它反查档位（基类注释的
     * 「构造期顺序陷阱」），所以必须由构造参数带进来。</p>
     */
    public CuttingMachineFactoryTier getTier() {
        return tier;
    }

    /**
     * {@inheritDoc}
     *
     * <p>覆写只为「手持流体容器灌罐」。开界面仍由 {@code super.use} 经
     * {@code AttributeGui} 完成——本类<b>不</b>自己 {@code NetworkHooks.openScreen}，
     * 那条路已随旧 BE 一起删掉。</p>
     */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && !player.isShiftKeyDown()
                && level.getBlockEntity(pos) instanceof CookingFactoryTile machine) {
            ItemStack held = player.getItemInHand(hand);
            if (!held.isEmpty()) {
                FluidContainerInteract.ContainerFluidInfo info = FluidContainerInteract.getFluidInfo(held);
                if (info != null && info.isValid() && tryFillMachine(machine, held)) {
                    return fillAndReturnContainer(player, hand, held, info);
                }
            }
        }
        return super.use(state, level, pos, player, hand, hit);
    }

    /**
     * 灌进机器的流体罐。
     *
     * <p>刻意走 {@link net.minecraftforge.common.capabilities.ForgeCapabilities#FLUID_HANDLER}
     * 而不是直接调 tile 的罐：<b>这一行同时验证了「Mek 的流体能力确实挂上了」</b>。
     * {@code TileEntityMekanism} 的 {@code canHandleFluid()} 就是
     * {@code holder != null}，而 {@code getInitialFluidTanks} 的默认实现是
     * {@code aconst_null; areturn}——返回 null 的后果不是报错，而是
     * <b>静默没有任何流体能力</b>，于是这里会安静地什么都不做、机器灌不进水。
     * 走能力 API 时那条路径变成「返回 false」，配合上面的 {@code info.isValid()}
     * 会让玩家拿桶右键却开不出界面——这个症状比静默更难查。
     *
     * <p>顺带说明为什么不能直接传 {@code IExtendedFluidTank}：
     * 它继承 Forge 的 {@code IFluidTank}，但本仓库这版 Forge 的
     * {@code IFluidTank} <b>不</b>继承 {@code IFluidHandler}，
     * 而 {@code FluidContainerInteract.tryFillMachine} 的入参是后者。
     * Mek 侧对上的类型是 {@code ISidedFluidHandler}（{@code IExtendedFluidHandler}
     * 的子接口），不是 Forge 那个。</p>
     */
    private static boolean tryFillMachine(CookingFactoryTile machine, ItemStack held) {
        // 能力要从**方块实体**取（BlockEntity#getCapability(cap, side)），
        // Level 上没有对应重载。side 传 null = 不限面，与本机暴露流体能力的方式一致。
        return machine.getCapability(
                        net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER, null)
                .map(handler -> FluidContainerInteract.tryFillMachine(handler, held))
                .orElse(false);
    }

    /** 灌满后：容器少一个、空容器进背包（满则掉在地上）。客户端不发包。 */
    private InteractionResult fillAndReturnContainer(Player player, InteractionHand hand, ItemStack held,
                                                    FluidContainerInteract.ContainerFluidInfo info) {
        held.shrink(1);
        if (!held.isEmpty()) {
            player.setItemInHand(hand, held);
        }
        ItemStack emptyContainer = info.emptyContainer();
        if (!player.getInventory().add(emptyContainer)) {
            player.drop(emptyContainer, false);
        }
        return InteractionResult.sidedSuccess(false);
    }

    // ── 方块类型描述 ────────────────────────────────────────────────────

    /**
     * 构造本等级烹饪工厂的方块类型描述。
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
    public static BlockTypeTile<CookingFactoryTile> blockTypeFor(
            CuttingMachineFactoryTier tier,
            Supplier<ContainerTypeRegistryObject<? extends MekanismContainer>> containerRef,
            Supplier<TileEntityTypeRegistryObject<CookingFactoryTile>> tileRef) {

        BlockTypeTile.BlockTileBuilder<BlockTypeTile<CookingFactoryTile>, CookingFactoryTile, ?> builder =
                BlockTypeTile.BlockTileBuilder.createBlock(tileRef, new CookingFactoryLangEntry(tier));

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
     * 本模组允许装进烹饪工厂的升级类型。
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
     * <p>本模组的 lang key 是 {@code block.mekck.<tier>_cooking_factory}（每等级一条，
     * 实测 {@code en_us.json} 的 {@code block.mekck.basic_cooking_factory} 等 12 条），
     * 而 {@code MekCkFactoryType.COOKING} 的译名 key 不带等级，对不上，
     * 所以这里自带一个按等级拼的 lang entry。</p>
     */
    private static final class CookingFactoryLangEntry implements ILangEntry {
        private final CuttingMachineFactoryTier tier;

        CookingFactoryLangEntry(CuttingMachineFactoryTier tier) {
            this.tier = tier;
        }

        @Override
        public String getTranslationKey() {
            return "block.mekck." + tier.getCookingBlockId();
        }
    }
}

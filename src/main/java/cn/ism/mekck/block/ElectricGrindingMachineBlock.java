package cn.ism.mekck.block;

import cn.ism.mekck.machine.grinding.GrindingMachineTile;
import mekanism.api.math.FloatingLong;
import mekanism.api.text.ILangEntry;
import mekanism.common.block.attribute.AttributeStateFacing;
import mekanism.common.block.attribute.Attributes;
import mekanism.common.block.prefab.BlockTile;
import mekanism.common.content.blocktype.BlockTypeTile;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import mekanism.common.registration.impl.TileEntityTypeRegistryObject;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * 电力研磨机方块 —— Mek 体系版。
 *
 * <h3>从自研 {@code BaseEntityBlock} 换成 Mek 的 {@link BlockTile} 后本类只剩什么</h3>
 * 原先这里手写了 {@code createBlockStateDefinition} / {@code getStateForPlacement} /
 * {@code rotate} / {@code mirror}（朝向）、{@code newBlockEntity} / {@code getTicker}
 * （方块实体与 tick）、{@code use}（开界面）、{@code onRemove} + 手动掉物品（掉落），
 * 共 144 行。换体系后这些<b>全部由 Mek 接管</b>：
 * <ul>
 *   <li>{@link AttributeStateFacing} 接管四向朝向与 blockstate 变体；</li>
 *   <li>{@link BlockTile} 接管方块实体创建与 ticker 分发；</li>
 *   <li>{@code AttributeGui}（{@code withGui}）接管右键开界面；</li>
 *   <li>{@code BlockMekanism.onRemove} + 战利品表接管掉落；</li>
 *   <li>侧配（{@code SideMode} 枚举）由 {@code ISideConfiguration} 取代。</li>
 * </ul>
 *
 * <p><b>注册名一个字没改</b>（仍是 {@code mekck:electric_grinding_machine}），旧存档的方块不会丢。</p>
 */
public final class ElectricGrindingMachineBlock
        extends BlockTile<GrindingMachineTile, BlockTypeTile<GrindingMachineTile>> {

    public ElectricGrindingMachineBlock(BlockTypeTile<GrindingMachineTile> type,
                                        UnaryOperator<BlockBehaviour.Properties> propertyModifier) {
        super(type, propertyModifier);
    }

    /**
     * 构造本方块的方块类型描述。
     *
     * <p>属性一个都不能少，各自的缺失症状：</p>
     * <ul>
     *   <li>{@code withGui} → 缺了右键不开界面；</li>
     *   <li>{@code withEnergyConfig} → {@code MachineEnergyContainer.input} 在构造时读它，
     *       缺了容量/能耗无处声明，容器会以 0 容量建出来；</li>
     *   <li>{@code withSupportedUpgrades} → 缺了 {@code supportsUpgrades()} 为 false，
     *       升级槽与升级 tab 都不出现；</li>
     *   <li>{@link AttributeStateFacing} → 缺了 blockstate 的 {@code facing=} 变体全部匹配失败，
     *       方块直接<b>隐形</b>。</li>
     * </ul>
     */
    public static BlockTypeTile<GrindingMachineTile> blockTypeFor(
            Supplier<ContainerTypeRegistryObject<? extends mekanism.common.inventory.container.MekanismContainer>> containerRef,
            Supplier<TileEntityTypeRegistryObject<GrindingMachineTile>> tileRef) {

        BlockTypeTile.BlockTileBuilder<BlockTypeTile<GrindingMachineTile>, GrindingMachineTile, ?> builder =
                BlockTypeTile.BlockTileBuilder.createBlock(tileRef, LANG_ENTRY);

        builder.withGui(containerRef);

        // AttributeEnergy 的参数是 (usage, storage)（先用后容）。用 lambda 延迟取值，
        // 使 /reload 改配置后立即生效。
        // usage 给 0：本机的耗电是「有配方才扣 ENERGY_PER_TICK」，由 tile 自己按 tick 扣；
        // 在这里再声明一个非零 usage 会与它重复计费。
        builder.withEnergyConfig(
                () -> FloatingLong.ZERO,
                () -> FloatingLong.create(GrindingMachineTile.ENERGY_CAPACITY));

        builder.withSupportedUpgrades(supportedUpgrades());

        builder.with(new AttributeStateFacing());
        builder.with(Attributes.ACTIVE);
        builder.with(Attributes.REDSTONE);
        builder.with(Attributes.SECURITY);
        builder.with(Attributes.INVENTORY);

        return builder.build();
    }

    /** 本机支持的升级种类（与 {@code GrindingMachineTile#getSupportedUpgrade} 同源）。 */
    public static Set<mekanism.api.Upgrade> supportedUpgrades() {
        return EnumSet.of(mekanism.api.Upgrade.SPEED, mekanism.api.Upgrade.ENERGY);
    }

    /** 方块译名：键必须与注册名一致，否则 GUI 标题与物品名显示 raw key。 */
    private static final ILangEntry LANG_ENTRY = () -> "block.mekck.electric_grinding_machine";
}

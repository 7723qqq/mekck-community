package cn.ism.mekck.block;

import cn.ism.mekck.blockentity.WineCellarBlockEntity;
import mekanism.api.math.FloatingLong;
import mekanism.api.text.ILangEntry;

import mekanism.common.block.attribute.AttributeStateFacing;
import mekanism.common.block.attribute.Attributes;
import mekanism.common.block.prefab.BlockTile;
import mekanism.common.content.blocktype.BlockTypeTile;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import mekanism.common.registration.impl.TileEntityTypeRegistryObject;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * 陈化窖（时间悖论产生器，F20）方块 —— Mek 体系版。
 *
 * <h3>从自研 {@code BaseEntityBlock} 换成 Mek 的 {@link BlockTile} 后本类只剩什么</h3>
 * 原先这里手写了 {@code createBlockStateDefinition} / {@code getStateForPlacement} /
 * {@code rotate} / {@code mirror}（朝向）、{@code newBlockEntity} / {@code getTicker}
 * （方块实体与 tick）、{@code use}（开界面）、{@code onRemove} + 手动掉物品（掉落），
 * 共 124 行。换体系后这些<b>全部由 Mek 接管</b>：
 * <ul>
 *   <li>{@link AttributeStateFacing} 接管四向朝向与 blockstate 变体；</li>
 *   <li>{@link BlockTile} 接管方块实体创建与 ticker 分发；</li>
 *   <li>{@code AttributeGui}（{@code withGui}）接管右键开界面；</li>
 *   <li>{@code BlockMekanism.onRemove} + 战利品表接管掉落；</li>
 *   <li>侧配（{@code SideMode} 枚举）由 {@code ISideConfiguration} 取代。</li>
 * </ul>
 *
 * <p><b>注册名一个字没改</b>（仍是 {@code mekck:wine_cellar}），旧存档的方块不会丢。</p>
 */
public final class WineCellarBlock
        extends BlockTile<WineCellarBlockEntity, BlockTypeTile<WineCellarBlockEntity>> {

    public WineCellarBlock(BlockTypeTile<WineCellarBlockEntity> type,
                           UnaryOperator<BlockBehaviour.Properties> propertyModifier) {
        super(type, propertyModifier);
    }

    /**
     * 构造本方块的方块类型描述。
     *
     * <p>属性一个都不能少，<b>各自的缺失症状</b>（与 {@code UniversalCuttingMachineBlock} 同款）：</p>
     * <ul>
     *   <li>{@code withGui} → 缺了右键不开界面；</li>
     *   <li>{@code withEnergyConfig} → {@code MachineEnergyContainer.input} 在构造时读它
     *       （实测走 {@code validateBlock(tile).getStorage()/getUsage()}），
     *       缺了容量/能耗无处声明，容器会以 0 容量建出来；</li>
     *   <li>{@code withSupportedUpgrades} → 缺了 {@code supportsUpgrades()} 为 false，
     *       升级槽与升级 tab 都不出现；</li>
     *   <li>{@link AttributeStateFacing} → 缺了 blockstate 的 {@code facing=} 变体全部匹配失败，
     *       方块直接<b>隐形</b>。</li>
     * </ul>
     *
     * <p>音效不给：陈化窖是纯时间推进的容器，没有可听的加工动作（旧实现也没有工作音）。</p>
     *
     * @param containerRef 延迟引用：容器要等 tile/block 建好之后才能注册
     * @param tileRef      同理 —— {@code BlockTypeTile} 构造时就要 tile 的 Supplier，
     *                     而 tile 的构造又需要方块，形成先后依赖
     */
    public static BlockTypeTile<WineCellarBlockEntity> blockTypeFor(
            Supplier<ContainerTypeRegistryObject<? extends mekanism.common.inventory.container.MekanismContainer>> containerRef,
            Supplier<TileEntityTypeRegistryObject<WineCellarBlockEntity>> tileRef) {

        BlockTypeTile.BlockTileBuilder<BlockTypeTile<WineCellarBlockEntity>,
                WineCellarBlockEntity, ?> builder =
                BlockTypeTile.BlockTileBuilder.createBlock(tileRef, LANG_ENTRY);

        builder.withGui(containerRef);

        // AttributeEnergy 的参数是 (usage, storage)（先用后容）。用 lambda 延迟取值，
        // 使 /reload 改配置后立即生效。
        //
        // usage 给 0：本机没有「每 tick 固定耗电」——它的耗电是
        // Σ 62.5×瓶数×倍速，随内容变化，由 tickAging 按实际需要扣。
        // 若在这里声明一个非零 usage，Mek 会按它额外抽电，与本机自己的计费重复。
        builder.withEnergyConfig(
                () -> FloatingLong.ZERO,
                () -> FloatingLong.create(WineCellarBlockEntity.ENERGY_CAPACITY));

        builder.withSupportedUpgrades(supportedUpgrades());

        // 默认构造器用 BlockStateProperties.HORIZONTAL_FACING，与旧方块的 4 向
        // blockstate JSON 逐字一致，因此不需要传自定义属性。
        builder.with(new AttributeStateFacing());
        builder.with(Attributes.ACTIVE);
        builder.with(Attributes.REDSTONE);
        builder.with(Attributes.SECURITY);
        builder.with(Attributes.INVENTORY);

        return builder.build();
    }

    /** 本机支持的升级种类（与 {@code WineCellarBlockEntity#getSupportedUpgrade} 同源）。 */
    public static Set<mekanism.api.Upgrade> supportedUpgrades() {
        return java.util.EnumSet.of(mekanism.api.Upgrade.SPEED, mekanism.api.Upgrade.ENERGY);
    }

    /** 方块译名：键必须与注册名一致，否则 GUI 标题与物品名显示 raw key。 */
    private static final ILangEntry LANG_ENTRY = () -> "block.mekck.wine_cellar";
}

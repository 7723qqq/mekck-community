package cn.ism.mekck.factory;

import mekanism.common.block.prefab.BlockTile;
import mekanism.common.content.blocktype.BlockTypeTile;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.function.UnaryOperator;

/**
 * MekCK 工厂方块（Mek 体系版）。
 *
 * <p>继承 Mek 的 {@link BlockTile}：它负责把方块与 tile 实体、方块类型描述绑定，
 * 并提供 Mek 机器的标准交互（扳手、配置卡等）。工艺类型与等级通过构造参数带入，
 * tile 侧据此决定槽位数与能耗。</p>
 *
 * <p>这是「新套」方块；旧的 {@code CuttingMachineFactoryBlock}（自研 {@code BaseEntityBlock}）
 * 保持不动，两套并列共存。</p>
 */
public class MekCkFactoryBlock extends BlockTile<MekCkFactoryTile, BlockTypeTile<MekCkFactoryTile>> {

    private final MekCkFactoryType factoryType;
    private final MekCkFactoryTier tier;

    /**
     * @param type             方块类型描述（含 tile 注册对象引用，Mek 靠它找回 tile）
     * @param factoryType      工艺家族
     * @param tier             等级
     * @param propertyModifier 方块属性微调（Mek 的 {@code BlockTile} 会先铺默认强度/工具需求再应用它）
     */
    public MekCkFactoryBlock(BlockTypeTile<MekCkFactoryTile> type,
                             MekCkFactoryType factoryType,
                             MekCkFactoryTier tier,
                             UnaryOperator<BlockBehaviour.Properties> propertyModifier) {
        super(type, propertyModifier);
        this.factoryType = factoryType;
        this.tier = tier;
    }

    public MekCkFactoryType getFactoryType() {
        return factoryType;
    }

    public MekCkFactoryTier getFactoryTier() {
        return tier;
    }
}

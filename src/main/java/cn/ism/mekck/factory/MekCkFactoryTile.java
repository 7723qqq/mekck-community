package cn.ism.mekck.factory;

import mekanism.api.providers.IBlockProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 切菜工厂的 tile（Mek 体系版）—— 把 {@link TileEntityMekCkFactory} 的抽象接线补完。
 *
 * <p>当前<b>尚未接入配方查找</b>：本类只承载 Mek 机器骨架（侧配 / 能量 / 槽位 / 升级），
 * 用于验证 tab 布局与 Mek 完全对齐。配方待 tile 骨架验收通过后再接。</p>
 */
public class MekCkFactoryTile extends TileEntityMekCkFactory {

    public MekCkFactoryTile(IBlockProvider blockProvider, BlockPos pos, BlockState state,
                            MekCkFactoryTier tier, MekCkFactoryType factoryType) {
        super(blockProvider, pos, state, tier, factoryType);
    }
}

package cn.ism.mekck.machine;

import cn.ism.mekck.CuttingMachineFactoryTier;

/**
 * 「能自报档位的工厂方块」契约 —— {@code machine} 侧对 {@code block} 侧的依赖倒置。
 *
 * <h3>为什么需要这个接口</h3>
 * 6 个工厂族的 tile（{@code CuttingFactoryTile} / {@code CookingFactoryTile} /
 * {@code SkeweringFactoryTile} / {@code GrillFactoryTile} /
 * {@code PlantingCuttingFactoryTile} / {@code GrindingFactoryTile}）都需要在
 * {@link MekCkMachineTile#tierFromBlock()} 里从方块反查自档位。原先各自的实现是
 * {@code blockProvider.getBlock() instanceof XxxFactoryBlock block → block.getTier()}，
 * 于是每个 tile 都得 import 它对应的 {@code block} 类 —— 这就形成了
 * {@code machine → block} 的 6 条反向依赖（{@code block → machine} 本来就有，两者成环）。
 *
 * <p>把「档位」这个<b>两者都需要的概念</b>抽成接口后：方块实现它（{@code block → machine}，
 * 与既有方向一致），tile 只认接口（{@code machine} 内部），反向依赖归零。</p>
 *
 * <h3>契约</h3>
 * 实现者必须是工厂方块，且 {@link #getTier()} 返回其注册档位（非空）。该方法在
 * {@code TileEntityMekanism} 构造器内部被回调（见 {@link MekCkMachineTile} 的构造期陷阱），
 * <b>不得依赖实例字段</b>——方块侧的档位在构造器里已确定，直接返回即可。
 */
public interface IFactoryTierProvider {

    /** 本方块所属的工厂档位。工厂方块恒非空。 */
    CuttingMachineFactoryTier getTier();
}

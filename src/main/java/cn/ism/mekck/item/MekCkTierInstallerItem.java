package cn.ism.mekck.item;

import cn.ism.mekck.CuttingMachineFactoryTier;
import net.minecraft.world.item.Item;


/**
 * MekCK 自有的工厂安装器（第 9/10/11 级）：
 * 晶钛矩阵 / 星云塑造 / 奇点创世。潜行右键工厂即可把工厂升级到对应等级。
 *
 * <p>升级判定按**注册名**进行（见 {@link cn.ism.mekck.util.TierInstallerHandler}），
 * 因此本类只负责携带等级信息与提示文本。</p>
 */
public class MekCkTierInstallerItem extends Item {

    private final CuttingMachineFactoryTier tier;
    private final boolean animated;

    /**
     * @param tier     目标工厂等级
     * @param animated 是否为变色动画纹理（奇点创世）
     */
    public MekCkTierInstallerItem(Properties properties, CuttingMachineFactoryTier tier,
                                  boolean animated) {
        super(properties);
        this.tier = tier;
        this.animated = animated;
    }

    public CuttingMachineFactoryTier getTier() {
        return tier;
    }

}

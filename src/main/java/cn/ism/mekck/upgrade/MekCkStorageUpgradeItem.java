package cn.ism.mekck.upgrade;

import mekanism.api.Upgrade;
import mekanism.common.item.interfaces.IUpgradeItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 存储升级卡：提升机器的并行线程数与缓冲容量。
 *
 * <p>实现 {@link IUpgradeItem} 即可被 Mekanism 的升级槽识别——该接口只有一个抽象方法
 * {@code Upgrade getUpgradeType(ItemStack)}（{@code javap} 实测），而两个入口都是
 * 先 {@code instanceof IUpgradeItem} 再取 type：
 * {@code TileComponentUpgrade.tickServer()} 拿 type 走 20 tick 读条与安装，
 * 槽位合法性由 {@code UpgradeInventorySlot.input(listener, supported)} 的
 * {@code supported} 集合决定。所以卡片本身不需要任何 Mixin，
 * 需要 Mixin 的只有往 {@code Upgrade} 枚举里注入常量那一步（{@link MekCkUpgradeRefs}）。
 */
public class MekCkStorageUpgradeItem extends Item implements IUpgradeItem {

    public MekCkStorageUpgradeItem(Properties properties) {
        super(properties);
    }

    @Override
    public Upgrade getUpgradeType(ItemStack stack) {
        return MekCkUpgradeRefs.storage();
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.mekck.upgrade_storage"));
    }
}

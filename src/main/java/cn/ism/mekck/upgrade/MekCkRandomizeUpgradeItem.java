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
 * 随机化升级卡：把本局可用的 49 种食物重新随机。
 *
 * <p>取代原先借用 {@code mekanism_extras:upgrade_creative} 的做法。
 * 枚举常量取名 {@code RANDOMIZE} 而非 {@code CREATIVE}，理由（与 Mek Extras 的同名常量
 * 撞注册 id 的机制，以及「存档走 ordinal 而非 rawName」这一条反直觉事实）
 * 见 {@link MekCkUpgradeRefs} 的类注释，此处不复述。
 */
public class MekCkRandomizeUpgradeItem extends Item implements IUpgradeItem {

    public MekCkRandomizeUpgradeItem(Properties properties) {
        super(properties);
    }

    @Override
    public Upgrade getUpgradeType(ItemStack stack) {
        return MekCkUpgradeRefs.randomize();
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.mekck.upgrade_randomize"));
    }
}

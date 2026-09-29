package cn.ism.mekck.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 巧克力大炮的费列罗升级物品。共 5 种（{@link FerreroUpgradeTier}），
 * 相互独立、各占专属升级槽，不消耗，安装后叠加生效。
 */
public class FerreroUpgradeItem extends Item {

    public final FerreroUpgradeTier tier;

    /** 各档升级物品的注册引用，供创造标签页等场景使用。 */
    public static final Map<FerreroUpgradeTier, RegistryObject<Item>> REGISTRY = new EnumMap<>(FerreroUpgradeTier.class);

    public FerreroUpgradeItem(FerreroUpgradeTier tier, Properties properties) {
        super(properties);
        this.tier = tier;
    }

    /** 注册 5 种费列罗升级物品。 */
    public static void registerAll(DeferredRegister<Item> items) {
        for (FerreroUpgradeTier t : FerreroUpgradeTier.values()) {
            REGISTRY.put(t, items.register(t.id, () -> new FerreroUpgradeItem(t, new Item.Properties().stacksTo(1))));
        }
    }

    /** 判断物品栈是否为费列罗升级物品并返回其档位；非费列罗升级物品返回 null。 */
    public static FerreroUpgradeTier getTier(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        if (stack.getItem() instanceof FerreroUpgradeItem item) return item.tier;
        return null;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        // 本档的具体升级效果（颜色与精英等级机器物品名一致）
        tooltip.add(Component.translatable("tooltip.mekck.ferrero_upgrade." + tier.langSuffix)
                .withStyle(style -> style.withColor(cn.ism.mekck.CuttingMachineFactoryTier.ELITE.getColor())));
        // 链式安装提示（需按顺序安装前几档，同冷萃升级模式）
        if (tier.ordinal() > 0) {
            tooltip.add(Component.translatable("tooltip.mekck.ferrero_upgrade.chain")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}

package cn.ism.mekck.item;

import cn.ism.mekck.CuttingMachineFactoryTier;
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
 * 冷萃升级物品。共 5 种，分别由 {@link ColdBrewTier} 描述其等级与安装槽位。
 * 该物品不消耗，安装到制冰机/制冰工厂的冷萃升级槽后提供攻击能力。
 */
public class ColdBrewUpgradeItem extends Item {

    public final ColdBrewTier tier;

    /** 各等级冷萃升级物品的注册引用，供创造标签页等场景使用。 */
    public static final Map<ColdBrewTier, RegistryObject<Item>> REGISTRY = new EnumMap<>(ColdBrewTier.class);

    public ColdBrewUpgradeItem(ColdBrewTier tier, Properties properties) {
        super(properties);
        this.tier = tier;
    }

    /** 注册 5 种冷萃升级物品。 */
    public static void registerAll(DeferredRegister<Item> items) {
        for (ColdBrewTier t : ColdBrewTier.values()) {
            REGISTRY.put(t, items.register(t.id, () -> new ColdBrewUpgradeItem(t, new Item.Properties().stacksTo(1))));
        }
    }

    /** 判断物品栈是否为冷萃升级物品并返回其等级；非冷萃升级物品返回 null。 */
    public static ColdBrewTier getTier(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        if (stack.getItem() instanceof ColdBrewUpgradeItem item) return item.tier;
        return null;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        // 主行：效果摘要（颜色与精英等级机器物品名一致）
        tooltip.add(Component.translatable("tooltip.mekck.cold_brew." + tier.name().toLowerCase())
                .withStyle(style -> style.withColor(CuttingMachineFactoryTier.ELITE.getColor())));
        // 补充行：仅 冷萃① / 低温② 保留灰色 detail；失温⑤ 的 detail 与失温/永冻描述为青色说明行
        if (tier == ColdBrewTier.COLD || tier == ColdBrewTier.LOW_TEMP) {
            tooltip.add(Component.translatable("tooltip.mekck.cold_brew." + tier.name().toLowerCase() + ".detail")
                    .withStyle(ChatFormatting.GRAY));
        } else if (tier == ColdBrewTier.HYPOTHERMIA) {
            tooltip.add(Component.translatable("tooltip.mekck.cold_brew." + tier.name().toLowerCase() + ".detail")
                    .withStyle(style -> style.withColor(CuttingMachineFactoryTier.ELITE.getColor())));
            tooltip.add(Component.translatable("tooltip.mekck.hypothermia.desc")
                    .withStyle(style -> style.withColor(CuttingMachineFactoryTier.ELITE.getColor())));
            tooltip.add(Component.translatable("tooltip.mekck.eternal_freeze.desc")
                    .withStyle(style -> style.withColor(CuttingMachineFactoryTier.ELITE.getColor())));
        }
        // 链式安装提示（需先安装前一级冷萃升级）
        if (tier.slot > 1) {
            tooltip.add(Component.translatable("tooltip.mekck.cold_brew.chain")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}

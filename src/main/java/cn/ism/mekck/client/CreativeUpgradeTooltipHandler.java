package cn.ism.mekck.client;

import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.config.MekckConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 「创造升级」物品的 tooltip 提示（2026-09-18 研究线实施单 §8.3）。
 *
 * <p>目标物品：{@code mekanism_extras:upgrade_creative}。追加两行，说明当前模式是
 * <b>本存档固定</b>（{@code per_world}，默认）还是<b>每次重启重随</b>（{@code per_restart}）。</p>
 *
 * <p>约束（照实施单）：只在物品匹配时追加；未安装通用机械：扩展时该物品不存在 ⇒ 直接跳过、不报错；
 * 文案走语言文件；**不做任何世界/存档访问**（tooltip 可能在任意界面渲染，模式值直接读配置即可）。</p>
 */
@Mod.EventBusSubscriber(modid = UniversalCuttingMachine.MOD_ID, value = Dist.CLIENT)
public final class CreativeUpgradeTooltipHandler {

    /** 目标物品 id（通用机械：扩展的创造升级）。 */
    private static final ResourceLocation TARGET =
            new ResourceLocation("mekanism_extras", "upgrade_creative");

    private CreativeUpgradeTooltipHandler() {
    }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack == null || stack.isEmpty()) return;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null || !TARGET.equals(id)) return;
        String mode = MekckConfig.isCreativeUpgradePerRestart() ? "per_restart" : "per_world";
        event.getToolTip().add(Component.translatable("tooltip.mekck.creative_upgrade." + mode + ".1"));
        event.getToolTip().add(Component.translatable("tooltip.mekck.creative_upgrade." + mode + ".2"));
    }
}

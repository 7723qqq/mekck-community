package cn.ism.mekck.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Map;

/**
 * {@link cn.ism.mekck.util.ClientPacketBridge} 的客户端实现 —— 全部客户端符号都关在这里。
 *
 * <p>门面那边用 {@code Class.forName} 加载本类，因此<b>本类在专用服务端永远不会被加载</b>，
 * 里面的 {@code Minecraft} / {@code Screen} 引用也就不会出现在任何双端都要链接的字节码里。
 * 详见门面的类注释（那里记了具体是哪两个包踩过这个坑）。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ClientPacketBridgeImpl {

    private ClientPacketBridgeImpl() {
    }

    /** 往当前 Screen 的订单面板灌「可下单配方列表」。 */
    public static void applyRecipeList(BlockPos pos, List<String> recipeIds, Map<String, Integer> maxCraftable) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof NetworkOrderHost host) {
            // 其余屏幕（智能穿串机 / 智能厨锅 / 中央厨房下单窗…）：共用面板。
            // 烧烤 / 穿串 / 烹饪工厂在阶段 3 Task 3/5/7 换 Mek 体系界面后不再实现
            // NetworkOrderHost（订单面板等 GuiConfigurableTile 的 tab 布局定稿再统一接），
            // 所以本分支打不到它们——ME 侧改走 IMekCkPorted 自动化。
            NetworkOrderPanel panel = host.networkOrderPanel();
            if (panel != null) {
                panel.setData(pos, recipeIds, maxCraftable);
            }
        }
    }

    /** 往当前 Screen 的订单面板灌「缺料清单」。 */
    public static void applyMissing(BlockPos pos, String recipeId, int quantity, String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof NetworkOrderHost host) {
            NetworkOrderPanel panel = host.networkOrderPanel();
            if (panel != null) {
                // 共用面板的屏幕
                panel.setMissing(pos, recipeId, quantity, text);
            } else {
                // 自绘 ME 面板的屏幕（烹饪 / 穿串工厂）
                host.setNetworkMissing(pos, recipeId, quantity, text);
            }
        }
    }
}

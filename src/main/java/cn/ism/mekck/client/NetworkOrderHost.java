package cn.ism.mekck.client;

import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

/**
 * 实现了「ME 来源」下单面板的屏幕：把面板暴露给网络回包
 * （{@code NetworkRecipeListPacket} / {@code NetworkMissingPacket}）做数据投递。
 *
 * <p>两种实现方式：</p>
 * <ul>
 *   <li>共用面板（烧烤工厂 / 智能穿串机 / 智能厨锅 / 中央厨房下单窗）：返回
 *       {@link NetworkOrderPanel} 实例，回包直接投给面板；</li>
 *   <li>自绘 ME 面板（烹饪工厂 / 穿串工厂，代码已验收）：{@link #networkOrderPanel()} 返回
 *       {@code null}，改为覆写 {@link #setNetworkMissing} 接收缺料清单回包。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public interface NetworkOrderHost {

    /** 当前屏幕的 ME 面板；自绘面板的屏幕返回 null。 */
    @Nullable
    NetworkOrderPanel networkOrderPanel();

    /** 自绘 ME 面板的屏幕用它接收「缺料清单」回包（默认忽略）。 */
    default void setNetworkMissing(BlockPos pos, String recipeId, int quantity, String text) {
    }
}

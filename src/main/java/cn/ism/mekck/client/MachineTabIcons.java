package cn.ism.mekck.client;

import net.minecraft.resources.ResourceLocation;

/**
 * 本模组的 GUI tab 图标（自绘，16×16）。
 *
 * <p>原先「订单 / 下单」tab 借用的是 Mekanism 的 {@code sorting.png} —— 它画的是
 * 「三条竖线 + 中间横线」（整理/排序语义），在 16px 下很像音符，而且与"下单"毫无关系。
 * 这里换成自绘图标：{@link #ORDER} = 一张带三行条目的清单 + 向下箭头（"把订单投给机器"），
 * 与侧配（configuration）/升级（upgrade）图标同为深色描边风格，观感一致。</p>
 *
 * <p>贴图：{@code assets/mekck/textures/gui/icon_order.png}（16×16，颜色 #121212）。</p>
 */
public final class MachineTabIcons {
    /** 下单 / 订单 tab：清单 + 向下箭头。 */
    public static final ResourceLocation ORDER =
            new ResourceLocation("mekck", "textures/gui/icon_order.png");

    private MachineTabIcons() {
    }
}

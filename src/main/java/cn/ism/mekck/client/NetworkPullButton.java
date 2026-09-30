package cn.ism.mekck.client;

import cn.ism.mekck.util.AE2Compat;
import mekanism.client.gui.GuiMekanism;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

/**
 * AE2 通用「自动补料 / 网络拉料」两枚标签页的注册入口。
 *
 * <p>两枚按钮本身就是 Mek 的 tab（{@link MekCkPullTab} 直接继承 Mek 的
 * {@code GuiTabElementType}），本类只负责三件事：装没装 AE2、把 {@link BlockPos} 换成
 * Mek 的 {@code TabType} 要求的 {@code BlockEntity}、以及左列固定坐标。</p>
 *
 * <h3>左列坐标预算（26px 一格）</h3>
 * <pre>
 *   6   侧配（Mek GuiSideConfigurationTab）
 *   34  传输配置（Mek，工厂屏）/ 下单（本模组，其余屏）
 *   62  自动补料
 *   90  网络拉料
 *   137 能量（Mek GuiEnergyTab，位置写死在 Mek 里）
 * </pre>
 * 118..135 只剩 18px，塞不下第三格。{@link MekCkSlotWindowTab} 同样占 90，
 * 但它只挂在工厂屏、而工厂屏目前不挂这两枚（新 tile 不实现 {@code INetworkPullable}），
 * 所以两者不会同屏。将来若给工厂屏补上，槽位视图要挪到右列（99 起，分选之下）。
 */
public final class NetworkPullButton {

    private NetworkPullButton() {
    }

    /** 是否显示（仅装 AE2 时）。 */
    public static boolean isVisible() {
        return AE2Compat.isLoaded();
    }

    /**
     * 造出两枚标签页（自动补料在上、网络拉料在下），由调用屏 {@code addRenderableWidget} 注册。
     *
     * <p><b>调用位置要求</b>：必须在各屏元素注册的<b>末尾</b>调用 —— Mek 的
     * {@code GuiMekanism#mouseClicked} 对 {@code children()} 倒序遍历、命中即返回，
     * 越晚注册命中优先。</p>
     *
     * <p>方块实体取不到时返回空表（不画按钮），而不是塞个 null 进去 ——
     * {@code TabType#onClick} 要拿它取坐标。</p>
     */
    public static List<MekCkPullTab> register(GuiMekanism<?> gui, BlockPos pos) {
        BlockEntity be = Minecraft.getInstance().level == null
                ? null : Minecraft.getInstance().level.getBlockEntity(pos);
        if (be == null) {
            return List.of();
        }
        return List.of(
                new MekCkPullTab(gui, be, MekCkPullTab.PullTab.AUTO_PULL),
                new MekCkPullTab(gui, be, MekCkPullTab.PullTab.NETWORK_PULL));
    }
}

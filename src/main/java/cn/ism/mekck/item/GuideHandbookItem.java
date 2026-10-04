package cn.ism.mekck.item;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 通用机械指南手册：右键打开 GuideME 指南 {@code mekguide:mek}。
 *
 * <p>与热键（默认 G）互为补充：手持本模组/通用机械物品按 G 也能打开对应指南。</p>
 * <p>打开屏幕属于客户端行为，因此这里只在客户端执行；实际调用交给
 * {@link cn.ism.mekck.compat.GuideMECompat} 门面（它在客户端直接引用 {@code Minecraft}，
 * 避免「反射原版类名在运行时对不上 SRG 名」的坑），未装 GuideME 或运行在服务端时都不会出错。</p>
 */
public class GuideHandbookItem extends Item {

    /** 目标指南 id（内容位于 assets/mekguide/mek/，将来随新 mod 一起搬走）。 */
    private static final String GUIDE_NAMESPACE = "mekguide";
    private static final String GUIDE_PATH = "mek";

    public GuideHandbookItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            openGuide();
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /**
     * 打开指南 —— <b>走 {@link cn.ism.mekck.compat.GuideMECompat} 门面</b>，不再自己反射。
     *
     * <p><b>为什么不能在本类里直接反射原版类</b>（2026-09-15 实际踩过的坑）：
     * 生产环境的 Minecraft 类是 <b>SRG 名</b>，{@code Minecraft.getInstance()} 实际叫 {@code m_91087_}；
     * 我们自己的调用会被 reobf 转换，但<b>反射字符串不会</b> ⇒ {@code getMethod("getInstance")}
     * 必抛 {@code NoSuchMethodException}，右键永远没反应。</p>
     *
     * <p>正确做法：反射<b>只用于我们自己的客户端类</b>（{@code cn.ism.mekck.client.GuideMECompatImpl}，
     * 类名/方法名不会被 reobf 改名），由它在客户端直接引用 {@code Minecraft}。
     * 这也保证专用服务器上本类被加载时不会触碰任何客户端类。</p>
     */
    private static void openGuide() {
        cn.ism.mekck.compat.GuideMECompat.openGuide(ResourceLocation.fromNamespaceAndPath(GUIDE_NAMESPACE, GUIDE_PATH));
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable("item.mekck.guide_handbook");
    }
}

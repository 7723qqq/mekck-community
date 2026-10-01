package cn.ism.mekck.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import cn.ism.mekck.util.Reflect;

/**
 * 应用能源2（AE2）可选联动的安全门面。
 *
 * <p>本类<b>不</b>引用任何 AE2 类。所有 AE2 交互都集中在
 * {@code cn.ism.mekck.ae2.MekckAe2}（仅在 {@code ModList.isLoaded("ae2")} 为 true
 * 时才会被 Class.forName 加载），因此 AE2 未安装时本模组完全不受影响。</p>
 */
public final class AE2Compat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String AE2_MOD_ID = "ae2";
    private static final String IMPL_CLASS = "cn.ism.mekck.ae2.MekckAe2";

    private static Boolean loaded;
    private static Class<?> impl;
    /** 兼容实现类定位失败后置位，避免每 tick 重复尝试并刷日志。 */
    private static boolean implFailed = false;

    private AE2Compat() {
    }

    public static boolean isLoaded() {
        if (loaded == null) {
            loaded = ModList.get().isLoaded(AE2_MOD_ID);
        }
        return loaded;
    }

    public static void attachCapabilities(AttachCapabilitiesEvent<BlockEntity> event) {
        if (!isLoaded()) return;
        invoke("attachCapabilities", new Class<?>[]{AttachCapabilitiesEvent.class}, event);
    }

    public static void serverTick(BlockEntity be, Level level, BlockPos pos) {
        if (!isLoaded()) return;
        invoke("serverTick", new Class<?>[]{BlockEntity.class, Level.class, BlockPos.class}, be, level, pos);
    }

    public static void saveAdditional(BlockEntity be, CompoundTag tag) {
        if (!isLoaded()) return;
        invoke("saveAdditional", new Class<?>[]{BlockEntity.class, CompoundTag.class}, be, tag);
    }

    public static void load(BlockEntity be, CompoundTag tag) {
        if (!isLoaded()) return;
        invoke("load", new Class<?>[]{BlockEntity.class, CompoundTag.class}, be, tag);
    }

    public static void onRemoved(BlockEntity be) {
        if (!isLoaded()) return;
        invoke("onRemoved", new Class<?>[]{BlockEntity.class}, be);
    }

    // ----- 本模组下单面板直连 ME 网络（烹饪工厂） -----

    public static boolean isNetworkAvailable(BlockEntity be) {
        if (!isLoaded()) return false;
        Object r = invoke("isNetworkAvailable", new Class<?>[]{BlockEntity.class}, be);
        return Boolean.TRUE.equals(r);
    }

    @SuppressWarnings("unchecked")
    public static List<String> getNetworkOrderableRecipeIds(BlockEntity be) {
        if (!isLoaded()) return List.of();
        Object r = invoke("getNetworkOrderableRecipeIds", new Class<?>[]{BlockEntity.class}, be);
        return r instanceof List<?> list ? (List<String>) (List) list : List.of();
    }

    public static int getNetworkMaxCraftable(BlockEntity be, String recipeId) {
        if (!isLoaded()) return 0;
        Object r = invoke("getNetworkMaxCraftable", new Class<?>[]{BlockEntity.class, String.class}, be, recipeId);
        return r instanceof Number n ? n.intValue() : 0;
    }

    /** 面板 ME 数据：配方 id → 网络可做份数（一次算全表；通用机器走终端样板）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Integer> getNetworkCraftableMap(BlockEntity be) {
        if (!isLoaded()) return Map.of();
        Object r = invoke("getNetworkCraftableMap", new Class<?>[]{BlockEntity.class}, be);
        return r instanceof Map<?, ?> map ? (Map<String, Integer>) (Map) map : Map.of();
    }

    /** AE 终端式缺料摘要（{@code 缺少 X×3、Y×1}）；材料足够或查询不到时返回 null。 */
    public static String describeNetworkMissing(BlockEntity be, String recipeId, int quantity) {
        if (!isLoaded()) return null;
        Object r = invoke("describeNetworkMissing", new Class<?>[]{BlockEntity.class, String.class, int.class},
                be, recipeId, quantity);
        return r instanceof String s && !s.isEmpty() ? s : null;
    }

    public static boolean pullNetworkIngredients(BlockEntity be, String recipeId, int quantity) {
        return pullNetworkIngredients(be, recipeId, quantity, null);
    }

    public static boolean pullNetworkIngredients(BlockEntity be, String recipeId, int quantity, String seasoningId) {
        if (!isLoaded()) return false;
        Object r = invoke("pullNetworkIngredients",
                new Class<?>[]{BlockEntity.class, String.class, int.class, String.class},
                be, recipeId, quantity, seasoningId);
        return Boolean.TRUE.equals(r);
    }

    // ----- 通用"网络拉料"（INetworkPullable 机器） -----

    /** 手动网络拉料：拉取机器当前可处理配方的一份输入进输入槽。 */
    public static boolean pullNetworkInputs(BlockEntity be) {
        if (!isLoaded()) return false;
        Object r = invoke("pullNetworkInputs", new Class<?>[]{BlockEntity.class}, be);
        return Boolean.TRUE.equals(r);
    }

    @SuppressWarnings("unchecked")
    public static List<String> getSelectedAutoItemsGeneric(BlockEntity be) {
        if (!isLoaded()) return List.of();
        Object r = invoke("getSelectedAutoItemsGeneric", new Class<?>[]{BlockEntity.class}, be);
        return r instanceof List<?> list ? (List<String>) (List) list : List.of();
    }

    public static void toggleAutoItemGeneric(BlockEntity be, String itemId) {
        if (!isLoaded()) return;
        invoke("toggleAutoItemGeneric", new Class<?>[]{BlockEntity.class, String.class}, be, itemId);
    }

    public static void autoProcessTickGeneric(BlockEntity be) {
        if (!isLoaded()) return;
        invoke("autoProcessTickGeneric", new Class<?>[]{BlockEntity.class}, be);
    }

    /** 自动补料开关：按机器当前可处理配方的首个输入物品切换勾选。 */
    public static void toggleAutoItemAuto(BlockEntity be) {
        if (!isLoaded()) return;
        invoke("toggleAutoItemAuto", new Class<?>[]{BlockEntity.class}, be);
    }

    // ----- ME 自动处理（切菜工厂 / 烧烤工厂） -----

    public static void autoProcessTick(BlockEntity be) {
        if (!isLoaded()) return;
        invoke("autoProcessTick", new Class<?>[]{BlockEntity.class}, be);
    }

    @SuppressWarnings("unchecked")
    public static List<String> getAutoProcessableItems(BlockEntity be) {
        if (!isLoaded()) return List.of();
        Object r = invoke("getAutoProcessableItems", new Class<?>[]{BlockEntity.class}, be);
        return r instanceof List<?> list ? (List<String>) (List) list : List.of();
    }

    @SuppressWarnings("unchecked")
    public static List<String> getSelectedAutoItems(BlockEntity be) {
        if (!isLoaded()) return List.of();
        Object r = invoke("getSelectedAutoItems", new Class<?>[]{BlockEntity.class}, be);
        return r instanceof List<?> list ? (List<String>) (List) list : List.of();
    }

    /**
     * 切换"持续自动补料"并返回给玩家的一句话（反射门面：未装 AE2 时返回提示）。
     * 返回 Object（实际是 String），避免在未装 AE2 时引用其类型。
     */
    public static Object toggleAutoItemAutoMessage(BlockEntity be) {
        if (!isLoaded()) return "未安装 AE2，无法使用 ME 网络拉料";
        return invoke("toggleAutoItemAuto", new Class<?>[]{BlockEntity.class}, be);
    }

    public static void toggleAutoItem(BlockEntity be, String itemId) {
        if (!isLoaded()) return;
        invoke("toggleAutoItem", new Class<?>[]{BlockEntity.class, String.class}, be, itemId);
    }

    /**
     * 反射调用 AE2 兼容实现类的静态方法。
     * <p>
     * 本方法在**每台机器每 tick** 都会被调用（serverTick / saveAdditional / load 与多种事件），
     * 原先每次都要 {@code impl.getMethod(name, paramTypes)} 重新解析签名并可能反复抛异常刷日志；
     * 现在方法句柄交给 {@link Reflect} 按「类 → 名字 + 参数」缓存（失败结果同样缓存）。
     * </p>
     */
    private static Object invoke(String name, Class<?>[] paramTypes, Object... args) {
        if (implFailed) return null;
        try {
            if (impl == null) {
                impl = Class.forName(IMPL_CLASS);
            }
        } catch (Throwable t) {
            implFailed = true; // 兼容实现类不可用：不再重试，避免每 tick 抛异常 + 刷日志
            LOGGER.warn("[mekck] AE2 compat class '{}' unavailable: {}", IMPL_CLASS, t.toString());
            return null;
        }
        return Reflect.callStatic(impl, name, paramTypes, args);
    }
}

package cn.ism.mekck.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 烧烤乐事 (barbequesdelight) 的**运行时可选联动**。
 *
 * 与 {@link KaleidoscopeCompat} 相同的桥接模式：所有对烧烤乐事类的直接引用
 * 都被隔离在 {@link Bridge} 私有静态内部类里，外层先 {@link #isLoaded()} 守卫，
 * 未安装时不会触发 NoClassDefFoundError。
 *
 * 调味机制（与原版手持撒料一致）：
 * - 调味料物品是 barbequesdelight 的 SeasoningItem（孜然粉/胡椒粉/辣椒粉
 *   耐久 64；蜂蜜芥末酱/水牛城酱/烧烤酱 耐久 16）；
 * - 给烤串调味 = 在烤串 NBT 写入 "seasoning" -> 调味料枚举名（大写，
 *   如 CUMIN / HONEY_MUSTARD），并由机器侧消耗 1 点调味料耐久。
 */
public final class BarbequesDelightCompat {

    private BarbequesDelightCompat() {
    }

    public static final String MOD_ID = "barbequesdelight";
    /** 烤串上记录调味料的 NBT key（与 BBQSkewerItem.KEY 一致）。 */
    public static final String SEASONING_NBT_KEY = "seasoning";

    /** GUI 展示用的调味料信息：枚举名（写入 NBT 的值）+ 图标物品。 */
    public record SeasoningInfo(String id, Item item) {
        public ItemStack getIcon() {
            return new ItemStack(item);
        }
    }

    public static boolean isLoaded() {
        return net.minecraftforge.fml.ModList.get().isLoaded(MOD_ID);
    }

    // ================================================================
    //   外部 API（全部先 isLoaded 检查再进入 Bridge）
    // ================================================================

    /** 是否为调味料物品（SeasoningItem 实例）。未安装返回 false。 */
    public static boolean isSeasoning(ItemStack stack) {
        return !stack.isEmpty() && isLoaded() && Bridge.isSeasoning(stack.getItem());
    }

    /**
     * 取调味料对应的枚举名（大写，即写入烤串 NBT 的值）。
     * 非调味料物品返回 null。
     */
    public static String getSeasoningId(ItemStack stack) {
        if (stack.isEmpty() || !isLoaded()) return null;
        return Bridge.seasoningIdOf(stack.getItem());
    }

    /** 该产物是否可调味（BBQSkewerItem 实例，即各类烤串）。 */
    public static boolean isSeasonable(ItemStack result) {
        return !result.isEmpty() && isLoaded() && Bridge.isSkewer(result.getItem());
    }

    /** 全部调味料列表（枚举定义顺序），供 GUI 选择。未安装返回空列表。 */
    public static List<SeasoningInfo> getAllSeasonings() {
        if (!isLoaded()) return List.of();
        return Bridge.allSeasonings();
    }

    /** 给烤串写入调味料 NBT（不做任何校验，调用方保证 result 可调味）。 */
    public static void applySeasoning(ItemStack skewer, String seasoningId) {
        CompoundTag tag = skewer.getOrCreateTag();
        tag.putString(SEASONING_NBT_KEY, seasoningId);
    }

    /** 读取烤串上已有的调味料名，无则返回 null。 */
    public static String getAppliedSeasoning(ItemStack skewer) {
        CompoundTag tag = skewer.getTag();
        if (tag == null || !tag.contains(SEASONING_NBT_KEY)) return null;
        String s = tag.getString(SEASONING_NBT_KEY);
        return s.isEmpty() ? null : s;
    }

    // ================================================================
    //   Bridge：真实引用烧烤乐事类的所有代码都在这里，延迟到首次访问才加载
    // ================================================================
    private static final class Bridge {
        static final Class<?> SEASONING_ITEM_CLASS;
        static final Class<?> SKEWER_ITEM_CLASS;
        static final Class<?> SEASONING_ENUM_CLASS;

        static {
            try {
                SEASONING_ITEM_CLASS = Class.forName("com.mao.barbequesdelight.content.item.SeasoningItem");
                SKEWER_ITEM_CLASS = Class.forName("com.mao.barbequesdelight.content.item.BBQSkewerItem");
                SEASONING_ENUM_CLASS = Class.forName("com.mao.barbequesdelight.init.food.BBQSeasoning");
            } catch (Exception e) {
                throw new RuntimeException("BarbequesDelight is loaded but cannot access its classes", e);
            }
        }

        static boolean isSeasoning(Item item) {
            return SEASONING_ITEM_CLASS.isInstance(item);
        }

        static boolean isSkewer(Item item) {
            return SKEWER_ITEM_CLASS.isInstance(item);
        }

        static String seasoningIdOf(Item item) {
            try {
                Object seasoning = SEASONING_ITEM_CLASS.getMethod("getSeasoning").invoke(item);
                if (seasoning == null) return null;
                return ((Enum<?>) seasoning).name();
            } catch (Exception e) {
                return null;
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        static List<SeasoningInfo> allSeasonings() {
            List<SeasoningInfo> out = new ArrayList<>(6);
            try {
                Object[] constants = SEASONING_ENUM_CLASS.getEnumConstants();
                for (Object c : constants) {
                    Enum<?> ec = (Enum<?>) c;
                    Item item = (Item) SEASONING_ENUM_CLASS.getMethod("asItem").invoke(c);
                    if (item != null) {
                        out.add(new SeasoningInfo(ec.name(), item));
                    }
                }
            } catch (Exception ignored) {
            }
            return out;
        }
    }
}

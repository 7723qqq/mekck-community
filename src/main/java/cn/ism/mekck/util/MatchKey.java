package cn.ism.mekck.util;

import net.minecraft.world.item.ItemStack;

/**
 * 配方匹配用的输入指纹。
 * <p>
 * 机器可以把「上次匹配结果」按输入指纹缓存起来，输入没变就直接复用，
 * 避免每 tick 把该类型的全部配方（高等级烧烤工厂还会扫描熔炉/烟熏炉/高炉的数千条配方）重扫一遍。
 * 指纹 = 物品注册名 + NBT 哈希；是否计入数量由调用方选择（多数配方匹配与数量无关）。
 * </p>
 */
public final class MatchKey {

    private MatchKey() {
    }

    /** 物品指纹（注册名 + NBT，不含数量）。空物品返回 0。 */
    public static long of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0L;
        net.minecraft.resources.ResourceLocation id =
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        long h = (id == null ? 0 : id.hashCode());
        h = h * 31L + (stack.getTag() == null ? 0 : stack.getTag().hashCode());
        return h == 0L ? 1L : h;
    }

    /** 物品指纹（注册名 + NBT + 数量）。空物品返回 0。 */
    public static long ofWithCount(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0L;
        return of(stack) * 31L + stack.getCount();
    }
}

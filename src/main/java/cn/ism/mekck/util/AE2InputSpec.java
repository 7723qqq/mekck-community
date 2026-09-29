package cn.ism.mekck.util;

import net.minecraft.world.item.crafting.Ingredient;

/** 网络拉料输入规格：一种原料 Ingredient + 每次抽取数量。 */
public final class AE2InputSpec {
    public final Ingredient ingredient;
    public final int count;

    public AE2InputSpec(Ingredient ingredient, int count) {
        this.ingredient = ingredient;
        this.count = Math.max(1, count);
    }

    public AE2InputSpec(Ingredient ingredient) {
        this(ingredient, 1);
    }
}

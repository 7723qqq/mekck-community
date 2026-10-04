package cn.ism.mekck.integration.jei;

import net.minecraft.world.item.ItemStack;

/**
 * JEI 展示用包装：单个物品（输入）→ 每单位可转化的有机物流体 mb 数。
 * 实际转换规则由 {@link cn.ism.mekck.recipe.BioreactorFuels} 计算。
 */
public class BioreactorJeiRecipe {

    private final ItemStack input;
    private final int mbPerUnit;

    public BioreactorJeiRecipe(ItemStack input, int mbPerUnit) {
        this.input = input;
        this.mbPerUnit = mbPerUnit;
    }

    public ItemStack getInput() {
        return input;
    }

    public int getMbPerUnit() {
        return mbPerUnit;
    }
}

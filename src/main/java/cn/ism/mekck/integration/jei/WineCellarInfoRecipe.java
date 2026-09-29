package cn.ism.mekck.integration.jei;

import net.minecraft.world.item.ItemStack;

/**
 * JEI 展示用「介绍页」配方载体（§F20⑥）：陈化窖不是配方加工机，这里只为在 JEI 里挂出一页说明，
 * 用一对示例瓶（进窖 → 出窖更陈）+ 文字描述演示其用途。{@code output} 可为空（未装 vinery 时只留描述）。
 */
public class WineCellarInfoRecipe {

    private final ItemStack input;
    private final ItemStack output;

    public WineCellarInfoRecipe(ItemStack input, ItemStack output) {
        this.input = input;
        this.output = output;
    }

    public ItemStack getInput() {
        return input;
    }

    public ItemStack getOutput() {
        return output;
    }

    /** 是否带示例瓶转换（装了 vinery 才有）。 */
    public boolean hasSample() {
        return !input.isEmpty() && !output.isEmpty();
    }
}

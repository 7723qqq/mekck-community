package cn.ism.mekck.recipe;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.List;

/**
 * 配方<b>真正消耗</b>的材料项 —— 供「本机下单」的输入匹配与 Max 计数使用。
 *
 * <h3>为什么不能直接用 {@code recipe.getIngredients()}</h3>
 * 本模组的三类自有配方（{@link FerreroRecipe} / {@link IceMakeRecipe} /
 * {@link NutRoastingRecipe}）都<b>没有覆写</b> {@code getIngredients()}，而那个方法在
 * 原版 {@code Recipe} 接口上带默认实现、返回<b>空表</b>。于是任何按
 * {@code getIngredients()} 遍历的通用逻辑，对它们都会退化成<b>空循环</b>，
 * 而编译与其余测试全绿：
 *
 * <ul>
 *   <li>{@code matchesInput} ⇒ 退化成「输入槽非空就算匹配」；</li>
 *   <li>{@code getMaxConsumableCountForOrder} ⇒ {@code max} 停在
 *       {@link Integer#MAX_VALUE}，函数按约定返回 {@code 0} ⇒ 面板上的 Max 按钮
 *       <b>只会填 1</b>（{@code NetworkOrderPanel} 再 {@code Math.max(1, 0)}）。</li>
 * </ul>
 *
 * <p>本类把「该配方到底吃哪些材料」显式列出来，与各机 {@code completeRecipe} 的扣料口径
 * 一一对应：{@code FerreroRecipe} 同时吃 {@code input} 与 {@code extra}（各 1 个），
 * 制冰与坚果各吃一个主料。</p>
 *
 * <p><b>新增自有配方类型时必须来这里补一支</b>，否则那台机器的 Max 与输入匹配会静默失效
 * —— 没有异常、没有日志、编译通过。非自有类型按原版语义原样返回 {@code getIngredients()}，
 * 行为不变。</p>
 */
public final class RecipeRequiredInputs {

    private RecipeRequiredInputs() {
    }

    /** 该配方需要的全部非空材料项。 */
    public static List<Ingredient> of(Recipe<?> recipe) {
        if (recipe == null) {
            return List.of();
        }
        if (recipe instanceof FerreroRecipe ferrero) {
            List<Ingredient> required = new ArrayList<>(2);
            addIfPresent(required, ferrero.getInput());
            addIfPresent(required, ferrero.getExtra());
            return required;
        }
        if (recipe instanceof IceMakeRecipe ice) {
            List<Ingredient> required = new ArrayList<>(1);
            addIfPresent(required, ice.getIngredient());
            return required;
        }
        if (recipe instanceof NutRoastingRecipe nut) {
            List<Ingredient> required = new ArrayList<>(1);
            addIfPresent(required, nut.getIngredient());
            return required;
        }
        NonNullList<Ingredient> vanilla = recipe.getIngredients();
        return vanilla == null ? List.of() : new ArrayList<>(vanilla);
    }

    private static void addIfPresent(List<Ingredient> target, Ingredient ingredient) {
        if (ingredient != null && !ingredient.isEmpty()) {
            target.add(ingredient);
        }
    }
}

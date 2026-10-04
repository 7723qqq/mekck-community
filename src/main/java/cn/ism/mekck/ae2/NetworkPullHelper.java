package cn.ism.mekck.ae2;

import cn.ism.mekck.recipe.RecipeInputMatcher;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;

import java.util.List;

/** 网络拉料输入规格的公共辅助：当前输入或可处理并集。 */
public final class NetworkPullHelper {
    private NetworkPullHelper() {
    }

    /** 槽 0 有物 → 该物品成分；空槽 → 某配方类型首个成分的并集。 */
    public static List<AE2InputSpec> currentOrUnion(Level level, ItemStack slot0, ResourceLocation recipeTypeId) {
        if (!slot0.isEmpty()) {
            return List.of(new AE2InputSpec(Ingredient.of(slot0.getItem())));
        }
        Ingredient union = RecipeInputMatcher.unionFirstIngredients(level, recipeTypeId);
        return union.isEmpty() ? List.of() : List.of(new AE2InputSpec(union));
    }
}

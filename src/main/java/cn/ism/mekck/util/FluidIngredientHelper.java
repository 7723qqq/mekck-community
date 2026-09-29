package cn.ism.mekck.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper for converting fluid-bearing ingredients (water bottle, water bucket,
 * milk bottle, milk bucket) to their fluid equivalents, and for determining
 * which containers should be RETURNED when such ingredients are consumed
 * (e.g. empty bucket / empty glass bottle / bowl from a processed ingredient).
 *
 * <p>Also handles the general "ingredient declares a remaining item" case: when
 * an ingredient item has {@link Item#getCraftingRemainingItem(ItemStack)} (i.e.
 * vanilla bucket behaviour — {@code water_bucket → bucket}), we still return
 * the container through this helper for consistency.
 */
public final class FluidIngredientHelper {

    public static final int MB_PER_BOTTLE = 250;
    public static final int MB_PER_BUCKET = 1000;

    /** Forge tag key for milk fluids (used via item tag for matching) */
    public static final TagKey<Item> FORGE_MILK_ITEM_TAG =
            TagKey.create(BuiltInRegistries.ITEM.key(), new ResourceLocation("forge", "milk"));

    private FluidIngredientHelper() {}

    // ========================================================================
    //   Fluid information extraction per-ingredient
    // ========================================================================

    /**
     * Information about a single fluid-bearing ingredient.
     *
     * <p>There will often be at most one water and one milk requirement per recipe.
     * The caller should {@link #merge(FluidInfo)} them per-recipe.
     */
    public static final class FluidInfo {
        /** Required water in millibuckets (0 if none). */
        public int waterMb;
        /** Required milk in millibuckets (0 if none). Always stored under milk,
         *  even if ingredient was a #forge:milk item. */
        public int milkMb;

        public FluidInfo() { this(0, 0); }
        public FluidInfo(int waterMb, int milkMb) {
            this.waterMb = waterMb;
            this.milkMb = milkMb;
        }

        public FluidInfo merge(FluidInfo other) {
            this.waterMb += other.waterMb;
            this.milkMb  += other.milkMb;
            return this;
        }

        public boolean isEmpty() {
            return waterMb == 0 && milkMb == 0;
        }
    }

    /**
     * Classifies a single ingredient and returns the associated fluid requirement
     * per single craft unit. Returns an empty FluidInfo (0 / 0) when no fluid is
     * carried by this ingredient.
     *
     * <p>Rules:
     * <ul>
     *   <li>{@code minecraft:potion + {Potion:"minecraft:water"}} → 250 mb WATER</li>
     *   <li>{@code minecraft:water_bucket} → 1000 mb WATER</li>
     *   <li>{@code minecraft:milk_bucket} → 1000 mb MILK (strict item check,
     *       must not also fall into the general #forge:milk branch)</li>
     *   <li>{@code farmersdelight:milk_bottle} or any item in {@code #forge:milk}
     *       (unless it is the milk bucket, handled above) → 250 mb MILK</li>
     * </ul>
     */
    public static FluidInfo classify(Ingredient ingredient) {
        ItemStack[] candidates = ingredient.getItems();
        if (candidates == null || candidates.length == 0) return new FluidInfo();

        boolean waterBottle = false;
        boolean waterBucket = false;
        boolean milkBucket = false;
        boolean milkBottle = false;

        for (ItemStack stack : candidates) {
            Item item = stack.getItem();
            if (item == Items.POTION || item == Items.SPLASH_POTION || item == Items.LINGERING_POTION) {
                if (PotionUtils.getPotion(stack) == Potions.WATER) waterBottle = true;
            } else if (item == Items.WATER_BUCKET) {
                waterBucket = true;
            } else if (item == Items.MILK_BUCKET) {
                // Strict milk_bucket: 1000 mb milk. Test this BEFORE the
                // general #forge:milk check so #forge:milk does NOT gobble up
                // the milk bucket entry (which would otherwise also be a
                // #forge:milk holder if tagged that way by a data-pack).
                milkBucket = true;
            } else if (item == Items.GLASS_BOTTLE || item == Items.BUCKET) {
                // Empty containers never carry fluid.
                continue;
            } else {
                // #forge:milk (or FarmersDelight milk_bottle via tag) → 250 mb milk
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                if (id != null && "farmersdelight".equals(id.getNamespace())
                        && "milk_bottle".equals(id.getPath())) {
                    milkBottle = true;
                } else if (stack.is(FORGE_MILK_ITEM_TAG)) {
                    milkBottle = true;
                }
            }
        }

        // If an ingredient has multiple candidates, count it as the BIGGEST
        // carrier it matches (bucket wins over bottle). This mirrors how
        // generic Ingredients are allowed to match various items of different
        // yield sizes in practice — consumers will drain up to the matched
        // recipe-side amount; actual per-craft counts are handled by the
        // per-recipe "per-bottle / per-bucket" counts outside this helper.
        //
        // We only produce the fluid requirement, not the count. The caller is
        // expected to multiply "per unit" requirements by the number of times
        // this ingredient appears in a recipe (each Ingredient slot = 1 copy).
        int w = 0, m = 0;
        if (waterBucket) w = Math.max(w, MB_PER_BUCKET);
        if (waterBottle) w = Math.max(w, MB_PER_BOTTLE);
        if (milkBucket) m = Math.max(m, MB_PER_BUCKET);
        if (milkBottle) m = Math.max(m, MB_PER_BOTTLE);

        return new FluidInfo(w, m);
    }

    /**
     * Summarises total fluid requirement across every ingredient in a recipe.
     * (One Ingredient = one serving of fluid, not multiplied by stack size
     * inside the Ingredient — Vanilla recipe semantics.)
     */
    public static FluidInfo sumFluids(Iterable<Ingredient> ingredients) {
        FluidInfo total = new FluidInfo();
        for (Ingredient ing : ingredients) total.merge(classify(ing));
        return total;
    }

    // ========================================================================
    //   Container return logic (for when fluid / processed ingredients have
    //   been consumed)
    // ========================================================================

    /**
     * Returns the list of "container stacks" that should be pushed into the
     * return area after {@code matchedStack} (the concrete item consumed from
     * storage) is used to satisfy {@code originalIngredient}.
     *
     * <p>This covers two cases:
     * <ol>
     *   <li>Vanilla {@link Item#getCraftingRemainingItem(ItemStack)} — e.g.
     *       consuming {@code water_bucket} gives back {@code bucket}.</li>
     *   <li>Explicit {@code milk_bottle → glass_bottle} and water-potion →
     *       {@code glass_bottle} returns (these are NOT declared as remaining
     *       items by vanilla/FarmersDelight because the bottle normally stays
     *       with the player when crafting; we simulate it here for automation).
     * </ol>
     *
     * <p>Callers call this per matched item per unit. The returned list may be
     * empty but never null.
     */
    public static List<ItemStack> getReturnStacksForConsumed(ItemStack matchedStack,
                                                             Ingredient originalIngredient) {
        List<ItemStack> returns = new ArrayList<>(1);
        Item item = matchedStack.getItem();

        // 1) Vanilla remaining item first (bucket → empty bucket; also applies
        //    to e.g. a "bowl" carried by intermediate items like ketchup).
        if (item.hasCraftingRemainingItem(matchedStack)) {
            ItemStack remainder = item.getCraftingRemainingItem(matchedStack);
            if (remainder == null) remainder = ItemStack.EMPTY;
            if (!remainder.isEmpty()) returns.add(remainder.copy());
        } else {
            // 2) Explicit mappings for items Vanilla/FarmersDelight does NOT
            //    mark with a remaining item but that a machine ought to return
            //    because the bottle was used only for fluid carry.
            if (item == Items.POTION || item == Items.SPLASH_POTION
                    || item == Items.LINGERING_POTION) {
                if (PotionUtils.getPotion(matchedStack) == Potions.WATER) {
                    returns.add(new ItemStack(Items.GLASS_BOTTLE));
                }
            } else {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                if (id != null && "farmersdelight".equals(id.getNamespace())
                        && "milk_bottle".equals(id.getPath())) {
                    returns.add(new ItemStack(Items.GLASS_BOTTLE));
                }
            }
        }

        return returns;
    }
}

package cn.ism.mekck.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 定向 Mixin（仅无尽贪婪 Re-Avaritia 的极极大锻造台菜单）：
 * 当合成结果为奇点创世切菜工厂 / 奇点创世烹饪工厂（mekck 的 avaritia:extreme_smithing 配方）时，
 * 附加材料槽中的 avaritia:infinity_upgrade（无尽机器升级组件）改为<b>扣 1 点耐久</b>，不消耗。
 * 其它配方 / 其它物品行为不变；未装 avaritia 时由 {@link MekCkMixinConfigPlugin} 跳过本 Mixin。
 */
@Mixin(targets = "committee.nova.mods.avaritia.common.menu.ExtremeSmithingMenu")
public abstract class MixinExtremeSmithingMenu {

    private static final ResourceLocation SINGULARITY_CUTTING = new ResourceLocation("mekck", "avaritia_cutting_factory");
    private static final ResourceLocation SINGULARITY_COOKING = new ResourceLocation("mekck", "avaritia_cooking_factory");
    private static final Item INFINITY_UPGRADE = ForgeRegistries.ITEMS.getValue(new ResourceLocation("avaritia", "infinity_upgrade"));

    @Shadow
    private SmithingRecipe selectedRecipe;

    @Shadow
    private Level level;

    @Inject(method = "shrinkStackInSlot", at = @At("HEAD"), cancellable = true)
    private void mekck$damageInsteadOfConsume(int slot, CallbackInfo ci) {
        SmithingRecipe recipe = this.selectedRecipe;
        if (recipe == null) {
            return;
        }
        ResourceLocation id = recipe.getId();
        if (!SINGULARITY_CUTTING.equals(id) && !SINGULARITY_COOKING.equals(id)) {
            return;
        }
        if (INFINITY_UPGRADE == null || INFINITY_UPGRADE == Items.AIR) {
            return;
        }
        ItemStack stack = ((net.minecraft.world.inventory.AbstractContainerMenu) (Object) this).getSlot(slot).getItem();
        if (stack.isEmpty() || stack.getItem() != INFINITY_UPGRADE) {
            return;
        }
        // 扣 1 点耐久，不消耗（耐久耗尽时该格物品消失）
        int dmg = stack.getDamageValue() + 1;
        if (dmg >= stack.getMaxDamage()) {
            stack.shrink(1);
        } else {
            stack.setDamageValue(dmg);
        }
        ((net.minecraft.world.inventory.AbstractContainerMenu) (Object) this).getSlot(slot).setChanged();
        ci.cancel();
    }
}

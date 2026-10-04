package cn.ism.mekck.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 定向 Mixin（仅无尽贪婪 Re-Avaritia 的极极大锻造台菜单）：
 * 当合成结果为奇点创世切菜工厂 / 奇点创世烹饪工厂（mekck 的 avaritia:extreme_smithing 配方）时，
 * 附加材料槽中的 avaritia:infinity_upgrade（无尽机器升级组件）改为<b>扣 1 点耐久</b>，不消耗。
 * 其它配方 / 其它物品行为不变；未装 avaritia 时由 {@link MekCkMixinConfigPlugin} 跳过本 Mixin。
 *
 * <h3>⚠️ {@code @Pseudo} 不能去掉</h3>
 * 目标类属于**可选** mod：Avaritia 不在编译期 classpath 上（也不该在——它不是本模组的依赖）。
 * 没有 {@code @Pseudo} 时，Mixin 的注解处理器会在 {@code AnnotatedMixin} 的
 * {@code initTargets} 阶段直接报
 * {@code Mixin target committee.nova.mods.avaritia... could not be found} 并让 {@code compileJava} 失败。
 * <b>这个检查不受 {@code mixin { disableTargetValidator = true }} 影响</b>——那个开关只关掉
 * {@code TargetValidator}（另一条校验路径），实测设了它错误照旧。{@code @Pseudo} 才是
 * 「目标可能不存在」的**设计内**声明：处理器据此接受缺失目标，运行期则由
 * {@link MekCkMixinConfigPlugin} 在 Avaritia 缺席时跳过应用。
 */
@Pseudo
@Mixin(targets = "committee.nova.mods.avaritia.common.menu.ExtremeSmithingMenu", remap = false)
public abstract class MixinExtremeSmithingMenu {

    private static final ResourceLocation SINGULARITY_CUTTING = ResourceLocation.fromNamespaceAndPath("mekck", "avaritia_cutting_factory");
    private static final ResourceLocation SINGULARITY_COOKING = ResourceLocation.fromNamespaceAndPath("mekck", "avaritia_cooking_factory");
    private static final Item INFINITY_UPGRADE = ForgeRegistries.ITEMS.getValue(ResourceLocation.fromNamespaceAndPath("avaritia", "infinity_upgrade"));

    // remap = false 同上：`Shadow.remap` 默认 true、不继承类级设置，而本 mixin 用的是
    // `targets = "..."` 字符串形式（目标类可能不在编译期 classpath 上），无从解析。
    // 目标类是 Avaritia 的类，其字段名在 SRG 下不变，本来就不需要映射。
    @Shadow(remap = false)
    private SmithingRecipe selectedRecipe;

    @Shadow(remap = false)
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

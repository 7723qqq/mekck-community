package cn.ism.mekck.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;

/**
 * 手持流体容器（水瓶/水桶/奶瓶/奶桶）与机器流体系统交互的工具。
 *
 * <p>用于：
 * <ul>
 *   <li>玩家右键机器时将手持容器中的流体注入机器</li>
 *   <li>存储槽中流体容器自动转换为流体（见 server tick 调用）</li>
 * </ul>
 */
public final class FluidContainerInteract {

    private FluidContainerInteract() {
    }

    /** 流体容器信息：流体类型 + 量(mb) + 空容器返回物。 */
    public record ContainerFluidInfo(FluidStack fluid, ItemStack emptyContainer) {
        public boolean isValid() {
            return !fluid.isEmpty() && !emptyContainer.isEmpty();
        }
    }

    @Nullable
    private static Fluid cachedMilkFluid = null;
    private static boolean milkSearched = false;

    /**
     * 查找注册表中的牛奶流体。优先查找 path 含 "milk" 的流体，
     * 找不到则返回 null（表示当前环境没有牛奶流体）。
     */
    @Nullable
    public static Fluid findMilkFluid() {
        if (milkSearched) return cachedMilkFluid;
        milkSearched = true;
        for (var entry : ForgeRegistries.FLUIDS.getEntries()) {
            Fluid fluid = entry.getValue();
            if (fluid == Fluids.WATER || fluid == Fluids.LAVA || fluid == Fluids.EMPTY) continue;
            // 只考虑仍然流体（非方块态）
            if (fluid.getBucket() != null && fluid.getBucket() != Items.AIR) {
                ResourceLocation id = entry.getKey().location();
                if (id.getPath().contains("milk")) {
                    cachedMilkFluid = fluid;
                    return fluid;
                }
            }
        }
        return null;
    }

    /**
     * 判断物品是否为流体容器，返回对应流体信息。
     * 支持：水桶(1000mb水)、水瓶(250mb水)、奶桶(1000mb奶)、奶瓶(250mb奶)。
     * 不支持的物品返回 null。
     */
    @Nullable
    public static ContainerFluidInfo getFluidInfo(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        Item item = stack.getItem();

        // 水桶 → 1000mb 水 + 空桶
        if (item == Items.WATER_BUCKET) {
            return new ContainerFluidInfo(
                    new FluidStack(Fluids.WATER, 1000),
                    new ItemStack(Items.BUCKET));
        }
        // 水瓶 → 250mb 水 + 空玻璃瓶
        if (item == Items.POTION && PotionUtils.getPotion(stack) == Potions.WATER) {
            return new ContainerFluidInfo(
                    new FluidStack(Fluids.WATER, 250),
                    new ItemStack(Items.GLASS_BOTTLE));
        }
        // 奶桶 → 1000mb 奶 + 空桶
        if (item == Items.MILK_BUCKET) {
            Fluid milk = findMilkFluid();
            if (milk == null) return null;
            return new ContainerFluidInfo(
                    new FluidStack(milk, 1000),
                    new ItemStack(Items.BUCKET));
        }
        // 奶瓶（FarmersDelight）或 #forge:milk 物品 → 250mb 奶 + 空玻璃瓶
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        boolean isMilkBottle = (id != null && "farmersdelight".equals(id.getNamespace()) && "milk_bottle".equals(id.getPath()))
                || (stack.is(FluidIngredientHelper.FORGE_MILK_ITEM_TAG) && item != Items.MILK_BUCKET);
        if (isMilkBottle) {
            Fluid milk = findMilkFluid();
            if (milk == null) return null;
            return new ContainerFluidInfo(
                    new FluidStack(milk, 250),
                    new ItemStack(Items.GLASS_BOTTLE));
        }

        return null;
    }

    /**
     * 尝试将手持流体容器中的流体注入机器的流体系统。
     * 成功时消耗 1 个手持物品并返回空容器。
     *
     * @param handler 机器的 IFluidHandler（通常是 MultiFluidHandler）
     * @param held    手持物品
     * @return true 表示成功注入（调用方应消耗手持物品并给予空容器）
     */
    public static boolean tryFillMachine(IFluidHandler handler, ItemStack held) {
        ContainerFluidInfo info = getFluidInfo(held);
        if (info == null || !info.isValid()) return false;

        // 尝试注入流体
        int filled = handler.fill(info.fluid(), IFluidHandler.FluidAction.SIMULATE);
        if (filled < info.fluid().getAmount()) return false; // 没有足够空间

        handler.fill(info.fluid(), IFluidHandler.FluidAction.EXECUTE);
        return true;
    }
}

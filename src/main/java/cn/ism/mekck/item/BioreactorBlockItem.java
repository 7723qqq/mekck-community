package cn.ism.mekck.item;

import cn.ism.mekck.blockentity.BioreactorBlockEntity;
import mekanism.api.text.EnumColor;
import mekanism.client.key.MekKeyHandler;
import mekanism.client.key.MekanismKeyHandler;
import mekanism.common.MekanismLang;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 生物反应堆物品：发电机（非用电器），tooltip 只显示发电机应有的信息——
 * 发电速度（{@link BioreactorBlockEntity#MAX_GENERATION_PER_TICK}）与流体储罐容量
 * （{@link BioreactorBlockEntity#FLUID_CAPACITY}），不显示工厂等级 / 线程数 / 并行 / 能耗。
 */
public class BioreactorBlockItem extends BlockItem {

    public BioreactorBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level world, List<Component> tooltip, TooltipFlag flag) {
        if (MekKeyHandler.isKeyPressed(MekanismKeyHandler.descriptionKey)) {
            tooltip.add(Component.translatable("tooltip.mekck.bioreactor")
                    .withStyle(style -> style.withColor(EnumColor.AQUA.getColor())));
        } else if (MekKeyHandler.isKeyPressed(MekanismKeyHandler.detailsKey)) {
            addDetails(tooltip, stack);
        } else {
            // 发电机 tooltip：发电速度 + 流体储罐容量
            tooltip.add(Component.translatable("tooltip.mekck.generate_per_tick",
                    BioreactorBlockEntity.MAX_GENERATION_PER_TICK)
                    .withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
            tooltip.add(Component.translatable("tooltip.mekck.fluid_capacity",
                    BioreactorBlockEntity.FLUID_CAPACITY)
                    .withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
            tooltip.add(MekanismLang.HOLD_FOR_DETAILS.translateColored(EnumColor.GRAY, EnumColor.INDIGO,
                    MekanismKeyHandler.detailsKey.getTranslatedKeyMessage()));
            tooltip.add(MekanismLang.HOLD_FOR_DESCRIPTION.translateColored(EnumColor.GRAY, EnumColor.AQUA,
                    MekanismKeyHandler.descriptionKey.getTranslatedKeyMessage()));
        }
    }

    private void addDetails(List<Component> tooltip, ItemStack stack) {
        long stored = 0;
        CompoundTag beTag = stack.getTagElement("BlockEntityTag");
        if (beTag != null && beTag.contains("Energy", Tag.TAG_INT)) {
            stored = beTag.getInt("Energy");
        }
        tooltip.add(Component.translatable("tooltip.mekck.detail_energy")
                .withStyle(style -> style.withColor(EnumColor.BRIGHT_GREEN.getColor()))
                .append(Component.literal(stored + " FE / " + (BioreactorBlockEntity.ENERGY_CAPACITY / 1000) + " kFE")
                        .withStyle(style -> style.withColor(EnumColor.GRAY.getColor()))));
        tooltip.add(Component.translatable("tooltip.mekck.detail_has_items")
                .withStyle(style -> style.withColor(EnumColor.AQUA.getColor()))
                .append(Component.literal(hasSustainedItems(stack) ? "是" : "否")
                        .withStyle(style -> style.withColor(EnumColor.GRAY.getColor()))));
    }

    private boolean hasSustainedItems(ItemStack stack) {
        CompoundTag beTag = stack.getTagElement("BlockEntityTag");
        if (beTag == null) {
            return false;
        }
        CompoundTag itemsCompound = beTag.getCompound("Items");
        if (!itemsCompound.contains("Items", Tag.TAG_LIST)) {
            return false;
        }
        ListTag items = itemsCompound.getList("Items", Tag.TAG_COMPOUND);
        return !items.isEmpty();
    }
}

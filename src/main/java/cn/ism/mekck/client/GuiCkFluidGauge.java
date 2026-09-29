package cn.ism.mekck.client;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.gauge.GaugeType;
import mekanism.client.gui.element.gauge.GuiGauge;
import mekanism.client.render.MekanismRenderer;
import mekanism.client.render.MekanismRenderer.FluidTextureType;
import mekanism.common.MekanismLang;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.util.text.TextUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.NotNull;

/**
 * 参考 {@code mekanism:chemical_dissolution_chamber} 的 {@code GuiFluidGauge}
 * 渲染逻辑实现的流体条。直接从 {@link FluidStack}+容量供应商读取数据，
 * 不依赖 Mekanism 的 IExtendedFluidTank，因此可用于本模组的 Forge FluidTank。
 *
 * <p>外观与化学溶解室的标准流体条完全一致：STANDARD 背景图、液位填充、
 * 边框 overlay 均为 Mekanism 的 GuiGauge 同款绘制（{@link GuiGauge#drawBackground}）。
 */
public final class GuiCkFluidGauge extends GuiGauge<FluidStack> {

    @NotNull
    private final Supplier<FluidStack> fluidSupplier;
    @NotNull
    private final Supplier<Integer> capacitySupplier;

    public GuiCkFluidGauge(IGuiWrapper gui, int x, int y, Supplier<FluidStack> fluidSupplier, Supplier<Integer> capacitySupplier) {
        super(GaugeType.STANDARD, gui, x, y);
        this.fluidSupplier = fluidSupplier;
        this.capacitySupplier = capacitySupplier;
        setDummyType(FluidStack.EMPTY);
    }

    @Override
    public int getScaledLevel() {
        FluidStack fluid = fluidSupplier.get();
        int capacity = capacitySupplier.get();
        if (fluid == null || fluid.isEmpty() || capacity <= 0) {
            return 0;
        }
        if (fluid.getAmount() == Integer.MAX_VALUE) {
            return height - 2;
        }
        int scaled = Math.round((float) fluid.getAmount() / (float) capacity * (height - 2));
        // 容量极大时正常值会非常小，保留至少 2px 以便在 GUI 里能看到流体存在
        return Math.max(2, Math.min(scaled, height - 2));
    }

    @Override
    public TextureAtlasSprite getIcon() {
        FluidStack fluid = fluidSupplier.get();
        return fluid == null || fluid.isEmpty() ? null : MekanismRenderer.getFluidTexture(fluid, FluidTextureType.STILL);
    }

    @Override
    public Component getLabel() {
        return null;
    }

    @Override
    public List<Component> getTooltipText() {
        FluidStack fluid = fluidSupplier.get();
        if (fluid == null || fluid.isEmpty()) {
            return Collections.singletonList(MekanismLang.EMPTY.translate());
        }
        if (fluid.getAmount() == Integer.MAX_VALUE) {
            return Collections.singletonList(MekanismLang.GENERIC_STORED.translate(fluid, MekanismLang.INFINITE));
        }
        return Collections.singletonList(MekanismLang.GENERIC_STORED_MB.translate(fluid, TextUtils.format(fluid.getAmount())));
    }

    @Override
    protected void applyRenderColor(GuiGraphics guiGraphics) {
        FluidStack fluid = fluidSupplier.get();
        MekanismRenderer.color(guiGraphics, fluid == null ? FluidStack.EMPTY : fluid);
    }

    @Override
    public TransmissionType getTransmission() {
        return TransmissionType.FLUID;
    }
}
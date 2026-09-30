package cn.ism.mekck.menu;

import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.blockentity.BioreactorBlockEntity;
import cn.ism.mekck.util.PowerSlotUtil;
import cn.ism.mekck.util.WideDataSlot;
import mekanism.common.inventory.container.IGUIWindow;
import mekanism.common.inventory.container.slot.IVirtualSlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.function.IntSupplier;

/**
 * 生物反应堆菜单：4×4 输入格（16）+ 能源槽（1）+ 玩家物品栏。
 */
public final class BioreactorMenu extends AbstractContainerMenu {
    private final BioreactorBlockEntity machine;
    private final ContainerData data;

    // Slot 布局
    private static final int INPUT_START_X = 35;
    private static final int INPUT_START_Y = 17;
    private static final int INPUT_COLS = 4;
    private static final int INPUT_SPACING = 18;
    private static final int POWER_SLOT_X = 7;
    private static final int POWER_SLOT_Y = 13;
    private static final int TANK_SLOT_X = 122;
    private static final int TANK_SLOT_Y = 82;
    private static final int INV_TOP = 103;

    public BioreactorMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory,
                (BioreactorBlockEntity) inventory.player.level().getBlockEntity(buffer.readBlockPos()));
    }

    public BioreactorMenu(int containerId, Inventory inventory, BioreactorBlockEntity machine) {
        this(containerId, inventory, machine, machine.getData());
    }

    public BioreactorMenu(int containerId, Inventory inventory, BioreactorBlockEntity machine, ContainerData data) {
        super(UniversalCuttingMachine.BIOREACTOR_MENU.get(), containerId);
        this.machine = machine;
        this.data = data;

        // 4×4 输入格
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < INPUT_COLS; col++) {
                addSlot(new InputSlot(machine.getItems(), row * INPUT_COLS + col,
                        INPUT_START_X + col * INPUT_SPACING, INPUT_START_Y + row * INPUT_SPACING));
            }
        }

        // 能源槽（能量立方/能量板/红石）
        addSlot(new PowerSlot(machine, machine.getPowerSlot(), POWER_SLOT_X, POWER_SLOT_Y, this));

        // 流体储罐槽（流体物品 → 流体格，仅供能发电的燃料流体）
        addSlot(new TankSlot(machine.getItems(), BioreactorBlockEntity.TANK_SLOT, TANK_SLOT_X, TANK_SLOT_Y));

        // 玩家物品栏（GUI 高 184）
        int invLeft = (176 - 162) / 2;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, invLeft + column * 18, INV_TOP + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, invLeft + column * 18, INV_TOP + 58));
        }

        addDataSlots(data);
    }

    @Override
    public boolean stillValid(Player player) {
        Level level = player.level();
        return level.getBlockEntity(machine.getBlockPos()) == machine
                && player.distanceToSqr(machine.getBlockPos().getX() + 0.5D, machine.getBlockPos().getY() + 0.5D,
                machine.getBlockPos().getZ() + 0.5D) <= 64.0D;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        int totalMachineSlots = BioreactorBlockEntity.TOTAL_SLOTS;

        if (index < totalMachineSlots) {
            if (!moveItemStackTo(stack, totalMachineSlots, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (PowerSlotUtil.isValidEnergyItem(stack)) {
                if (!moveItemStackTo(stack, BioreactorBlockEntity.POWER_SLOT,
                        BioreactorBlockEntity.POWER_SLOT + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (stack.getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM, null).isPresent()) {
                if (!moveItemStackTo(stack, BioreactorBlockEntity.TANK_SLOT,
                        BioreactorBlockEntity.TANK_SLOT + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, 0, BioreactorBlockEntity.INPUT_SLOT_COUNT, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    public BioreactorBlockEntity getMachine() {
        return machine;
    }

    public int getEnergy() {
        return WideDataSlot.read(data,
                BioreactorBlockEntity.DATA_ENERGY,
                BioreactorBlockEntity.DATA_ENERGY_HI);
    }

    /**
     * 能量上限 —— 直接取 BE 上的 static final 常量，<b>不走 {@code ContainerData}</b>。
     *
     * <p>此前同步一个 {@code DATA_ENERGY_CAPACITY} 槽，但容量是客户端已知的常量，
     * 那个槽纯冗余，且同样会被 16 位通道截断（10 万 → -31072）。
     * 该槽现已改作 {@link BioreactorBlockEntity#DATA_ENERGY_HI}，见
     * {@link cn.ism.mekck.util.WideDataSlot}。</p>
     */
    public int getEnergyCapacity() {
        return BioreactorBlockEntity.ENERGY_CAPACITY;
    }

    /** 当前实际发电量 FE/t。 */
    public int getGeneratingRate() {
        return data.get(BioreactorBlockEntity.DATA_GENERATING);
    }

    /** 客户端重建有机物流体；空槽返回 EMPTY。 */
    public FluidStack getFluidStack() {
        int amount = data.get(BioreactorBlockEntity.DATA_FLUID_AMOUNT);
        if (amount <= 0) {
            return FluidStack.EMPTY;
        }
        int typeId = data.get(BioreactorBlockEntity.DATA_FLUID_TYPE);
        Fluid fluid = typeId >= 0 ? BuiltInRegistries.FLUID.byId(typeId) : Fluids.EMPTY;
        if (fluid == null || fluid == Fluids.EMPTY) {
            return FluidStack.EMPTY;
        }
        return new FluidStack(fluid, amount);
    }

    public int getFluidCapacity() {
        return BioreactorBlockEntity.FLUID_CAPACITY;
    }

    public BlockPos getBlockPos() {
        return machine.getBlockPos();
    }

    // ── 槽位 ──────────────────────────────────────────────────────────

    private static final class PowerSlot extends SlotItemHandler implements IVirtualSlot {
        private PowerSlot(BioreactorBlockEntity machine, int slot, int x, int y, BioreactorMenu menu) {
            super(machine.getItems(), slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return PowerSlotUtil.isValidEnergyItem(stack);
        }

        @Override public boolean isActive() { return true; }
        @Override public IGUIWindow getLinkedWindow() { return null; }
        @Override public int getActualX() { return x; }
        @Override public int getActualY() { return y; }
        @Override public void updatePosition(IGUIWindow window, IntSupplier xSupplier, IntSupplier ySupplier) {}
        @Override public void updateRenderInfo(ItemStack stack, boolean overlay, String tooltip) {}
        @Override public ItemStack getStackToRender() { return getItem(); }
        @Override public boolean shouldDrawOverlay() { return false; }
        @Override public String getTooltipOverride() { return null; }
        @Override public Slot getSlot() { return this; }
    }

    private static final class TankSlot extends SlotItemHandler implements IVirtualSlot {
        private TankSlot(ItemStackHandler handler, int slot, int x, int y) {
            super(handler, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM, null).isPresent();
        }

        // IVirtualSlot - 阻止原版槽位背景渲染
        @Override public IGUIWindow getLinkedWindow() { return null; }
        @Override public int getActualX() { return x; }
        @Override public int getActualY() { return y; }
        @Override public void updatePosition(IGUIWindow window, IntSupplier xSupplier, IntSupplier ySupplier) {}
        @Override public void updateRenderInfo(ItemStack stack, boolean overlay, String tooltip) {}
        @Override public ItemStack getStackToRender() { return getItem(); }
        @Override public boolean shouldDrawOverlay() { return false; }
        @Override public String getTooltipOverride() { return null; }
        @Override public Slot getSlot() { return this; }
    }

    private static final class InputSlot extends SlotItemHandler implements IVirtualSlot {
        private InputSlot(ItemStackHandler handler, int slot, int x, int y) {
            super(handler, slot, x, y);
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return getItemHandler().getSlotLimit(getContainerSlot());
        }

        // IVirtualSlot - 阻止原版槽位背景渲染
        @Override public IGUIWindow getLinkedWindow() { return null; }
        @Override public int getActualX() { return x; }
        @Override public int getActualY() { return y; }
        @Override public void updatePosition(IGUIWindow window, IntSupplier xSupplier, IntSupplier ySupplier) {}
        @Override public void updateRenderInfo(ItemStack stack, boolean overlay, String tooltip) {}
        @Override public ItemStack getStackToRender() { return getItem(); }
        @Override public boolean shouldDrawOverlay() { return false; }
        @Override public String getTooltipOverride() { return null; }
        @Override public Slot getSlot() { return this; }
    }
}

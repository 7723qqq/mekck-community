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
import net.minecraft.world.inventory.SimpleContainerData;
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
import cn.ism.mekck.registry.MekCkStandaloneMachines;

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

    /**
     * 客户端构造器：方块在 OpenScreen 到达前被破坏/替换、或区块被卸载时，
     * {@code getBlockEntity} 返回 null。旧写法直接强转后交给主构造器，
     * 主构造器第一行 {@code machine.getItems()} 就 NPE 崩客户端。这里显式判空
     * （{@code instanceof} 同时挡掉类型不符），null 时构造「空菜单」：
     * 槽位数量与坐标照旧，所有读取走 {@code machine == null} 的兜底分支。
     */
    public BioreactorMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory,
                inventory.player.level().getBlockEntity(buffer.readBlockPos())
                        instanceof BioreactorBlockEntity machine ? machine : null);
    }

    public BioreactorMenu(int containerId, Inventory inventory, BioreactorBlockEntity machine) {
        // 空菜单（客户端 BE 缺失）用等长的空数据槽兜底：屏幕侧会读这些槽，
        // 不兜底就是「构造器不崩了、第一帧渲染崩」。
        this(containerId, inventory, machine,
                machine == null ? new SimpleContainerData(BioreactorBlockEntity.DATA_SIZE) : machine.getData());
    }

    public BioreactorMenu(int containerId, Inventory inventory, BioreactorBlockEntity machine, ContainerData data) {
        super(MekCkStandaloneMachines.BIOREACTOR_MENU.get(), containerId);
        this.machine = machine;
        this.data = data;

        // 空菜单（客户端 BE 缺失）用等长的空 handler 兜底：槽位数量与坐标必须照旧，
        // 否则客户端与服务端的槽位契约不一致；读取路径全部走 machine == null 分支。
        ItemStackHandler items = machine == null
                ? new ItemStackHandler(BioreactorBlockEntity.TOTAL_SLOTS)
                : machine.getItems();

        // 4×4 输入格
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < INPUT_COLS; col++) {
                addSlot(new InputSlot(items, row * INPUT_COLS + col,
                        INPUT_START_X + col * INPUT_SPACING, INPUT_START_Y + row * INPUT_SPACING));
            }
        }

        // 能源槽（能量立方/能量板/红石）
        addSlot(new PowerSlot(items, BioreactorBlockEntity.POWER_SLOT, POWER_SLOT_X, POWER_SLOT_Y, this));

        // 流体储罐槽（流体物品 → 流体格，仅供能发电的燃料流体）
        addSlot(new TankSlot(items, BioreactorBlockEntity.TANK_SLOT, TANK_SLOT_X, TANK_SLOT_Y));

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
        // 空菜单一律视为失效：服务端据此关闭窗口，客户端也不再接受交互。
        if (machine == null) {
            return false;
        }
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
        int amount = WideDataSlot.read(data,
                BioreactorBlockEntity.DATA_FLUID_AMOUNT,
                BioreactorBlockEntity.DATA_FLUID_AMOUNT_HI);
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
        // 空菜单没有真实坐标，返回 ZERO 而不是 NPE（同 GrillMenu 的 tile == null 写法）。
        return machine == null ? BlockPos.ZERO : machine.getBlockPos();
    }

    // ── 槽位 ──────────────────────────────────────────────────────────

    private static final class PowerSlot extends SlotItemHandler implements IVirtualSlot {
        private PowerSlot(ItemStackHandler handler, int slot, int x, int y, BioreactorMenu menu) {
            super(handler, slot, x, y);
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

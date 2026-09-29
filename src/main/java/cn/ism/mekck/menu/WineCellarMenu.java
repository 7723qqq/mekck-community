package cn.ism.mekck.menu;

import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.blockentity.WineCellarBlockEntity;
import cn.ism.mekck.util.PowerSlotUtil;
import mekanism.common.inventory.container.IGUIWindow;
import mekanism.common.inventory.container.slot.IVirtualSlot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

import java.util.function.IntSupplier;

/**
 * 陈化窖（F20）菜单：9 个普通储存格（既放酒也取酒，箱子式）+ 电源槽（§F45）+ 玩家物品栏；
 * ContainerData 同步每格进度 / 能量 / 当前倍速 / 陈化中格数。无输出槽、无燃料槽、无升级槽。
 */
public final class WineCellarMenu extends AbstractContainerMenu {
    // 3×3 储存格网格（与 WineCellarScreen 一致）
    public static final int GRID_X0 = 62;
    public static final int GRID_Y0 = 18;
    public static final int GRID_SPACING = 18;
    public static final int GRID_COLS = 3;
    public static final int INV_TOP = 103;
    public static final int IMAGE_WIDTH = 176;
    public static final int IMAGE_HEIGHT = 184;
    /** §F45：电源槽坐标（与 Screen 的 GuiVirtualSlot 完全一致，点击命中按 Slot.x/y 判定）。 */
    public static final int POWER_X = 6;
    public static final int POWER_Y = 12;

    private final WineCellarBlockEntity machine;
    private final ContainerData data;

    public WineCellarMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory,
                (WineCellarBlockEntity) inventory.player.level().getBlockEntity(buffer.readBlockPos()));
    }

    public WineCellarMenu(int containerId, Inventory inventory, WineCellarBlockEntity machine) {
        this(containerId, inventory, machine, machine.getData());
    }

    public WineCellarMenu(int containerId, Inventory inventory, WineCellarBlockEntity machine, ContainerData data) {
        super(UniversalCuttingMachine.WINE_CELLAR_MENU.get(), containerId);
        this.machine = machine;
        this.data = data;

        ItemStackHandler items = machine.getItems();
        for (int i = 0; i < WineCellarBlockEntity.SLOT_COUNT; i++) {
            int row = i / GRID_COLS;
            int col = i % GRID_COLS;
            addSlot(new StoreSlot(items, i, GRID_X0 + col * GRID_SPACING, GRID_Y0 + row * GRID_SPACING));
        }

        // §F45：电源槽（能量物品/红石），左上角，同急冻制冰机对位
        addSlot(new PowerSlot(items, WineCellarBlockEntity.SLOT_POWER, POWER_X, POWER_Y));

        int invLeft = (IMAGE_WIDTH - 162) / 2;
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

    public WineCellarBlockEntity getMachine() {
        return machine;
    }

    public BlockPos getBlockPos() {
        return machine.getBlockPos();
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
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        int machineSlots = WineCellarBlockEntity.TOTAL_SLOTS;
        if (index < machineSlots) {
            if (!moveItemStackTo(stack, machineSlots, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            // 玩家 → 机器：能量物品优先入电源槽，其余入 9 储存格
            if (WineCellarBlockEntity.isUsablePowerItem(stack)) {
                if (!moveItemStackTo(stack, WineCellarBlockEntity.SLOT_POWER,
                        WineCellarBlockEntity.SLOT_POWER + 1, false)) return ItemStack.EMPTY;
            } else if (!moveItemStackTo(stack, 0, WineCellarBlockEntity.SLOT_COUNT, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }

    // ================== 数据访问（客户端从 ContainerData 读） ==================
    public int getEnergy() {
        return data.get(WineCellarBlockEntity.DATA_ENERGY);
    }

    public int getEnergyCapacity() {
        return data.get(WineCellarBlockEntity.DATA_CAPACITY);
    }

    public int getSpeed() {
        return data.get(WineCellarBlockEntity.DATA_SPEED);
    }

    public int getActiveCount() {
        return data.get(WineCellarBlockEntity.DATA_ACTIVE);
    }

    /** 第 slot 格进度百分比（-1 = 空格/非酒）。 */
    public int getSlotProgress(int slot) {
        if (slot < 0 || slot >= WineCellarBlockEntity.SLOT_COUNT) return 0;
        return data.get(WineCellarBlockEntity.DATA_PROGRESS0 + slot);
    }

    /** §F45：电源槽（只收能量物品/红石），坐标与 Screen 的 GuiVirtualSlot 对齐。 */
    private static final class PowerSlot extends SlotItemHandler implements IVirtualSlot {
        private PowerSlot(ItemStackHandler handler, int slot, int x, int y) {
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

    /** 普通储存格（IVirtualSlot 以关闭原版槽底、交给 GuiVirtualSlot 绘制）。 */
    private static final class StoreSlot extends SlotItemHandler implements IVirtualSlot {
        private StoreSlot(ItemStackHandler handler, int slot, int x, int y) {
            super(handler, slot, x, y);
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return getItemHandler().getSlotLimit(getContainerSlot());
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
}

package cn.ism.mekck.menu;

import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.blockentity.UniversalCuttingMachineBlockEntity;
import cn.ism.mekck.util.MekCkTransfer;
import cn.ism.mekck.util.WideDataSlot;
import mekanism.common.inventory.container.IGUIWindow;
import mekanism.common.inventory.container.slot.IVirtualSlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

import java.util.function.IntSupplier;

public final class UniversalCuttingMachineMenu extends AbstractContainerMenu implements ISideConfigurableMenu, IUpgradeMenu {
    /**
     * 机器槽在 {@code slots} 里的数量：input / output / speed / energy / power = <b>5</b>。
     *
     * <p><b>这个值必须等于构造器实际 addSlot 的机器槽数</b>。写小 1 会让最后一个机器槽
     * （能源槽）落进 {@code quickMoveStack} 的 else 分支，即被当成「玩家槽」，
     * 于是「从能源槽 shift-click 出去」会走玩家分支，而 else 分支里指向能源槽的区间
     * 又恰好等于被点槽自身 ⇒ 原版 {@code moveItemStackTo} 的合并分支自我合并、堆叠翻倍。
     * 历史上这里写的是 4（漏了能源槽），构成可无限复制红石/能量方块的复制路径。</p>
     */
    private static final int MACHINE_SLOT_COUNT = 5;
    private final UniversalCuttingMachineBlockEntity machine;
    private final ContainerData data;
    private boolean upgradePageActive = false;
    private final UpgradeSlot speedUpgradeSlot;
    private final UpgradeSlot energyUpgradeSlot;

    /**
     * 能源槽的<b>菜单下标</b>（不是 handler 下标）。
     *
     * <p>必须走 {@code slots.size()} 现场捕获：两套编号在本菜单里不同
     * （handler 侧 {@code SLOT_POWER=5} 而菜单侧是 4，因为 handler 的 4 号槽
     * 「创造升级槽」在本菜单没有 addSlot）。拿 handler 常量当菜单下标传进
     * {@code moveItemStackTo} 会指向玩家背包第 0 格，被点槽正好是那一格时区间自指 →
     * 自我合并 → 翻倍。</p>
     */
    private final int powerSlotIndex;

    public UniversalCuttingMachineMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory,
                (UniversalCuttingMachineBlockEntity) inventory.player.level().getBlockEntity(buffer.readBlockPos()),
                new SimpleContainerData(UniversalCuttingMachineBlockEntity.DATA_SIZE));
    }

    public UniversalCuttingMachineMenu(int containerId, Inventory inventory, UniversalCuttingMachineBlockEntity machine, ContainerData data) {
        super(UniversalCuttingMachine.MACHINE_MENU.get(), containerId);
        this.machine = machine;
        this.data = data;

        // Input slot
        addSlot(new InputSlot(machine.getItems(), UniversalCuttingMachineBlockEntity.INPUT_SLOT, 38, 41));
        // Output slot (tightly packed, right next to input slot)
        addSlot(new OutputSlot(machine, UniversalCuttingMachineBlockEntity.OUTPUT_SLOT, 56, 41));

        // Upgrade slots (at normal positions, visible only in upgrade page)
        this.speedUpgradeSlot = new UpgradeSlot(machine.getItems(), UniversalCuttingMachineBlockEntity.SLOT_SPEED_UPGRADE, 40, 46, this);
        addSlot(this.speedUpgradeSlot);
        this.energyUpgradeSlot = new UpgradeSlot(machine.getItems(), UniversalCuttingMachineBlockEntity.SLOT_ENERGY_UPGRADE, 40, 72, this);
        addSlot(this.energyUpgradeSlot);

        // Power slot (energy items: energy cube / tablet / redstone), on the right near the energy bar
        this.powerSlotIndex = slots.size();
        addSlot(new PowerSlot(machine, machine.getPowerSlot(), 7, 13, this));

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, 20 + column * 18, 101 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 20 + column * 18, 159));
        }
        addDataSlots(data);
    }

    /** 机器实例（客户端也持有，槽位内容由菜单同步 ⇒ 可用于「本机下单」列表）。 */
    public cn.ism.mekck.blockentity.UniversalCuttingMachineBlockEntity getMachine() {
        return machine;
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
        if (index < MACHINE_SLOT_COUNT) {
            if (!moveItemStackTo(stack, MACHINE_SLOT_COUNT, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (UniversalCuttingMachineBlockEntity.isUsablePowerItem(stack)) {
                // powerSlotIndex 是菜单下标；用 handler 常量 SLOT_POWER(=5) 会指向玩家背包第 0 格
                if (!moveItemStackTo(stack, powerSlotIndex, powerSlotIndex + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (UniversalCuttingMachineBlockEntity.isSpeedUpgrade(stack)) {
                if (!moveItemStackTo(stack, UniversalCuttingMachineBlockEntity.SLOT_SPEED_UPGRADE,
                        UniversalCuttingMachineBlockEntity.SLOT_SPEED_UPGRADE + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (UniversalCuttingMachineBlockEntity.isEnergyUpgrade(stack)) {
                if (!moveItemStackTo(stack, UniversalCuttingMachineBlockEntity.SLOT_ENERGY_UPGRADE,
                        UniversalCuttingMachineBlockEntity.SLOT_ENERGY_UPGRADE + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!MekCkTransfer.moveItemStackTo(stack, slots, UniversalCuttingMachineBlockEntity.INPUT_SLOT,
                    UniversalCuttingMachineBlockEntity.INPUT_SLOT + 1, false)) {
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

    public int getProgress() {
        int maximum = data.get(1);
        return maximum == 0 ? 0 : data.get(0) * 24 / maximum;
    }

    public int getEnergy() {
        return WideDataSlot.read(data,
                UniversalCuttingMachineBlockEntity.DATA_ENERGY,
                UniversalCuttingMachineBlockEntity.DATA_ENERGY_HI);
    }

    public int getEncodedSideConfig() {
        return data.get(3);
    }

    public SideMode getSideMode(Direction direction) {
        int encoded = getEncodedSideConfig();
        int ordinal = (encoded >> (direction.ordinal() * 2)) & 0x3;
        SideMode[] values = SideMode.values();
        if (ordinal >= 0 && ordinal < values.length) {
            return values[ordinal];
        }
        return SideMode.NONE;
    }

    public int getSpeedUpgradeCount() {
        return machine.getSpeedUpgradeCount();
    }

    public int getEnergyUpgradeCount() {
        return machine.getEnergyUpgradeCount();
    }

    public void setUpgradePageActive(boolean active) {
        this.upgradePageActive = active;
    }

    public boolean isUpgradePageActive() {
        return this.upgradePageActive;
    }

    @Override
    public Slot getSpeedUpgradeSlot() {
        return speedUpgradeSlot;
    }

    @Override
    public Slot getEnergyUpgradeSlot() {
        return energyUpgradeSlot;
    }

    public BlockPos getBlockPos() {
        return machine.getBlockPos();
    }

    public int getRedstoneControl() {
        return data.get(UniversalCuttingMachineBlockEntity.DATA_REDSTONE_CONTROL);
    }

    private static final class PowerSlot extends SlotItemHandler implements IVirtualSlot {
        private PowerSlot(UniversalCuttingMachineBlockEntity machine, int slot, int x, int y, UniversalCuttingMachineMenu menu) {
            super(machine.getItems(), slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return UniversalCuttingMachineBlockEntity.isUsablePowerItem(stack);
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

    private static final class InputSlot extends SlotItemHandler implements IVirtualSlot {
        private InputSlot(ItemStackHandler handler, int slot, int x, int y) {
            super(handler, slot, x, y);
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return getItemHandler().getSlotLimit(getContainerSlot());
        }

        // IVirtualSlot - prevents vanilla slot background rendering
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

    private static final class OutputSlot extends SlotItemHandler implements IVirtualSlot {
        private OutputSlot(UniversalCuttingMachineBlockEntity machine, int slot, int x, int y) {
            super(machine.getItems(), slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        // IVirtualSlot - prevents vanilla slot background rendering
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

    private static final class UpgradeSlot extends SlotItemHandler implements IVirtualSlot {
        /** 升级窗口未打开时把渲染位置移出屏幕：主屏便既不绘制、也命中不到它
         *  （Mek 的 VirtualSlotContainerScreen 渲染与 isMouseOverSlot 都走 getActualX/Y）。
         *  刻意<b>不改 isActive()</b> —— 那是槽的语义标志（服务端 mayPlace/转移逻辑依赖它），
         *  为了纯视觉的布局问题去改写它风险过大。 */
        private static final int HIDDEN_POS = -9999;

        private final UniversalCuttingMachineMenu menu;
        private IGUIWindow linkedWindow;
        private int actualX, actualY;
        private ItemStack stackToRender = ItemStack.EMPTY;
        private boolean overlay;
        private String tooltip;

        private UpgradeSlot(ItemStackHandler handler, int slot, int x, int y, UniversalCuttingMachineMenu menu) {
            super(handler, slot, x, y);
            this.menu = menu;
            this.actualX = x;
            this.actualY = y;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }

        @Override public boolean isActive() { return true; }

        @Override public IGUIWindow getLinkedWindow() { return linkedWindow; }
        @Override public int getActualX() { return linkedWindow == null ? HIDDEN_POS : actualX; }
        @Override public int getActualY() { return linkedWindow == null ? HIDDEN_POS : actualY; }
        @Override public void updatePosition(IGUIWindow window, IntSupplier xSupplier, IntSupplier ySupplier) {
            linkedWindow = window;
            actualX = xSupplier.getAsInt();
            actualY = ySupplier.getAsInt();
        }
        @Override public void updateRenderInfo(ItemStack stack, boolean overlay, String tooltip) {
            this.stackToRender = stack;
            this.overlay = overlay;
            this.tooltip = tooltip;
        }
        @Override public ItemStack getStackToRender() { return stackToRender; }
        @Override public boolean shouldDrawOverlay() { return overlay; }
        @Override public String getTooltipOverride() { return tooltip; }
        @Override public Slot getSlot() { return this; }
    }
}

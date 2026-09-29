package cn.ism.mekck.menu;

import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.blockentity.GrillBlockEntity;
import cn.ism.mekck.util.MekCkTransfer;
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

public final class GrillMenu extends AbstractContainerMenu implements ISideConfigurableMenu, IUpgradeMenu {
    /**
     * 机器槽在 {@code slots} 里的数量：input / output / speed / energy / power = <b>5</b>。
     *
     * <p><b>写小 1 会造成双重错误</b>：① 能源槽（下标 4）落进 else 分支被当成玩家槽；
     * ② else 分支里 {@code powerSlot = MACHINE_SLOT_COUNT} 恰好等于能源槽自己的下标，
     * 被点槽就是能源槽时区间自指 ⇒ 原版 {@code moveItemStackTo} 合并阶段自我合并、
     * 数量翻倍（1 个红石 6 次 shift-click 到 64，可无限复制）。</p>
     */
    private static final int MACHINE_SLOT_COUNT = 5;
    private final GrillBlockEntity machine;
    private final ContainerData data;
    private boolean upgradePageActive = false;

    private final UpgradeSlot speedUpgradeSlot;
    private final UpgradeSlot energyUpgradeSlot;

    /**
     * 能源槽的<b>菜单下标</b>（不是 handler 下标）：handler 侧 {@code SLOT_POWER=5}，
     * 而本菜单只 addSlot 了 handler 的 0/1/2/3/5（跳过创造升级槽 4），能源槽在菜单里是下标 4。
     */
    private final int powerSlotIndex;

    public GrillMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory,
                (GrillBlockEntity) inventory.player.level().getBlockEntity(buffer.readBlockPos()),
                new SimpleContainerData(GrillBlockEntity.DATA_SIZE));
    }

    public GrillMenu(int containerId, Inventory inventory, GrillBlockEntity machine, ContainerData data) {
        super(UniversalCuttingMachine.GRILL_MENU.get(), containerId);
        this.machine = machine;
        this.data = data;

        // Input slot
        addSlot(new InputSlot(machine.getItems(), GrillBlockEntity.INPUT_SLOT, 38, 41));
        // Output slot
        addSlot(new OutputSlot(machine, GrillBlockEntity.OUTPUT_SLOT, 56, 41));

        // Upgrade slots
        this.speedUpgradeSlot = new UpgradeSlot(machine.getItems(), GrillBlockEntity.SLOT_SPEED_UPGRADE, 40, 46, this);
        addSlot(this.speedUpgradeSlot);
        this.energyUpgradeSlot = new UpgradeSlot(machine.getItems(), GrillBlockEntity.SLOT_ENERGY_UPGRADE, 40, 72, this);
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

    @Override
    public boolean stillValid(Player player) {
        Level level = player.level();
        return level.getBlockEntity(machine.getBlockPos()) == machine
                && player.distanceToSqr(machine.getBlockPos().getX() + 0.5D, machine.getBlockPos().getY() + 0.5D,
                machine.getBlockPos().getZ() + 0.5D) <= 64.0D;
    }

    public int getImageWidth() {
        return 220;
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
            if (GrillBlockEntity.isUsablePowerItem(stack)) {
                // 必须用菜单下标 powerSlotIndex；用 MACHINE_SLOT_COUNT(=被点槽自身) 会区间自指 → 复制
                if (!moveItemStackTo(stack, powerSlotIndex, powerSlotIndex + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (GrillBlockEntity.isSpeedUpgrade(stack)) {
                if (!moveItemStackTo(stack, GrillBlockEntity.SLOT_SPEED_UPGRADE,
                        GrillBlockEntity.SLOT_SPEED_UPGRADE + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (GrillBlockEntity.isEnergyUpgrade(stack)) {
                if (!moveItemStackTo(stack, GrillBlockEntity.SLOT_ENERGY_UPGRADE,
                        GrillBlockEntity.SLOT_ENERGY_UPGRADE + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!MekCkTransfer.moveItemStackTo(stack, slots, GrillBlockEntity.INPUT_SLOT,
                    GrillBlockEntity.INPUT_SLOT + 1, false)) {
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
        return data.get(2);
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

    public GrillBlockEntity getMachine() {
        return machine;
    }

    public int getRedstoneControl() {
        return data.get(GrillBlockEntity.DATA_REDSTONE_CONTROL);
    }

    /** 机身温度（单位 0.01 ℃）。 */
    public int getTemperature() {
        return data.get(GrillBlockEntity.DATA_TEMPERATURE);
    }

    private static final class PowerSlot extends SlotItemHandler implements IVirtualSlot {
        private PowerSlot(GrillBlockEntity machine, int slot, int x, int y, GrillMenu menu) {
            super(machine.getItems(), slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return GrillBlockEntity.isUsablePowerItem(stack);
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
        private OutputSlot(GrillBlockEntity machine, int slot, int x, int y) {
            super(machine.getItems(), slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

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
        private final GrillMenu menu;
        private IGUIWindow linkedWindow;
        private int actualX, actualY;
        private ItemStack stackToRender = ItemStack.EMPTY;
        private boolean overlay;
        private String tooltip;

        private UpgradeSlot(ItemStackHandler handler, int slot, int x, int y, GrillMenu menu) {
            super(handler, slot, x, y);
            this.menu = menu;
            this.actualX = x;
            this.actualY = y;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }

        @Override
        public boolean isActive() { return true; }

        /** 升级窗口未打开时把渲染位置移出屏幕：主屏便既不绘制、也命中不到它
         *  （Mek 的 VirtualSlotContainerScreen 渲染与 isMouseOverSlot 都走 getActualX/Y）。
         *  刻意<b>不改 isActive()</b> —— 那是槽的语义标志（服务端 mayPlace/转移逻辑依赖它），
         *  为了纯视觉的布局问题去改写它风险过大。 */
        private static final int HIDDEN_POS = -9999;

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
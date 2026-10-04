package cn.ism.mekck.menu;

import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.blockentity.ElectricGrindingMachineBlockEntity;
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
import cn.ism.mekck.registry.MekCkFactories;

public final class ElectricGrindingMachineMenu extends AbstractContainerMenu implements ISideConfigurableMenu, IUpgradeMenu {
    /**
     * 机器槽在 {@code slots} 里的数量：input / output / speed / energy / power = <b>5</b>。
     * 写小 1 会让能源槽落进 {@code quickMoveStack} 的 else 分支（当成玩家槽），
     * 而 else 里指向能源槽的区间会自指 ⇒ 原版 {@code moveItemStackTo} 自我合并、堆叠翻倍。
     * 与 {@code UniversalCuttingMachineMenu} 同源，见那边的详细注释。
     */
    private static final int MACHINE_SLOT_COUNT = 5;
    private final ElectricGrindingMachineBlockEntity machine;
    private final ContainerData data;
    private boolean upgradePageActive = false;
    private final UpgradeSlot speedUpgradeSlot;
    private final UpgradeSlot energyUpgradeSlot;

    /**
     * 能源槽的<b>菜单下标</b>（不是 handler 下标）。
     *
     * <p>handler 侧 {@code SLOT_POWER=5}，而本菜单只 addSlot 了 handler 的
     * 0/1/2/3/5 五个槽（跳过创造升级槽 4），所以能源槽在菜单里是第 5 个 = 下标 4。
     * 拿 handler 常量当菜单下标会指向玩家背包第 0 格 ⇒ 自指 ⇒ 复制。</p>
     */
    private final int powerSlotIndex;

    public ElectricGrindingMachineMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory,
                (ElectricGrindingMachineBlockEntity) inventory.player.level().getBlockEntity(buffer.readBlockPos()),
                new SimpleContainerData(ElectricGrindingMachineBlockEntity.DATA_SIZE));
    }

    public ElectricGrindingMachineMenu(int containerId, Inventory inventory, ElectricGrindingMachineBlockEntity machine, ContainerData data) {
        super(MekCkFactories.GRINDING_MACHINE_MENU.get(), containerId);
        this.machine = machine;
        this.data = data;

        // Input slot
        addSlot(new InputSlot(machine.getItems(), ElectricGrindingMachineBlockEntity.INPUT_SLOT, 38, 41));
        // Output slot (tightly packed, right next to input slot)
        addSlot(new OutputSlot(machine, ElectricGrindingMachineBlockEntity.OUTPUT_SLOT, 56, 41));

        // Upgrade slots (at normal positions, visible only in upgrade page)
        this.speedUpgradeSlot = new UpgradeSlot(machine.getItems(), ElectricGrindingMachineBlockEntity.SLOT_SPEED_UPGRADE, 40, 46, this);
        addSlot(this.speedUpgradeSlot);
        this.energyUpgradeSlot = new UpgradeSlot(machine.getItems(), ElectricGrindingMachineBlockEntity.SLOT_ENERGY_UPGRADE, 40, 72, this);
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
    public cn.ism.mekck.blockentity.ElectricGrindingMachineBlockEntity getMachine() {
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
            if (ElectricGrindingMachineBlockEntity.isUsablePowerItem(stack)) {
                // powerSlotIndex 是菜单下标；handler 常量 SLOT_POWER(=5) 会指向玩家背包第 0 格
                if (!moveItemStackTo(stack, powerSlotIndex, powerSlotIndex + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (ElectricGrindingMachineBlockEntity.isSpeedUpgrade(stack)) {
                if (!moveItemStackTo(stack, ElectricGrindingMachineBlockEntity.SLOT_SPEED_UPGRADE,
                        ElectricGrindingMachineBlockEntity.SLOT_SPEED_UPGRADE + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (ElectricGrindingMachineBlockEntity.isEnergyUpgrade(stack)) {
                if (!moveItemStackTo(stack, ElectricGrindingMachineBlockEntity.SLOT_ENERGY_UPGRADE,
                        ElectricGrindingMachineBlockEntity.SLOT_ENERGY_UPGRADE + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!MekCkTransfer.moveItemStackTo(stack, slots, ElectricGrindingMachineBlockEntity.INPUT_SLOT,
                    ElectricGrindingMachineBlockEntity.INPUT_SLOT + 1, false)) {
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
                ElectricGrindingMachineBlockEntity.DATA_ENERGY,
                ElectricGrindingMachineBlockEntity.DATA_ENERGY_HI);
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
        return data.get(ElectricGrindingMachineBlockEntity.DATA_REDSTONE_CONTROL);
    }

    private static final class PowerSlot extends SlotItemHandler implements IVirtualSlot {
        private PowerSlot(ElectricGrindingMachineBlockEntity machine, int slot, int x, int y, ElectricGrindingMachineMenu menu) {
            super(machine.getItems(), slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return ElectricGrindingMachineBlockEntity.isUsablePowerItem(stack);
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
        private OutputSlot(ElectricGrindingMachineBlockEntity machine, int slot, int x, int y) {
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
        private final ElectricGrindingMachineMenu menu;
        private IGUIWindow linkedWindow;
        // 存供给器而非快照：窗口拖拽后 getActualX/Y 必须实时跟随。
        private IntSupplier xSupplier, ySupplier;
        private ItemStack stackToRender = ItemStack.EMPTY;
        private boolean overlay;
        private String tooltip;

        private UpgradeSlot(ItemStackHandler handler, int slot, int x, int y, ElectricGrindingMachineMenu menu) {
            super(handler, slot, x, y);
            this.menu = menu;
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
        @Override public int getActualX() { return linkedWindow == null ? HIDDEN_POS : (xSupplier != null ? xSupplier.getAsInt() : x); }
        @Override public int getActualY() { return linkedWindow == null ? HIDDEN_POS : (ySupplier != null ? ySupplier.getAsInt() : y); }
        @Override public void updatePosition(IGUIWindow window, IntSupplier xSupplier, IntSupplier ySupplier) {
            linkedWindow = window;
            this.xSupplier = xSupplier;
            this.ySupplier = ySupplier;
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

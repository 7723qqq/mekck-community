package cn.ism.mekck.menu;

import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.blockentity.NutRoasterBlockEntity;
import cn.ism.mekck.config.MekckConfig;
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
import cn.ism.mekck.registry.MekCkStandaloneMachines;

public final class NutRoasterMenu extends AbstractContainerMenu implements ISideConfigurableMenu, IUpgradeMenu {
    // Mekanism 风格布局常量（与 NutRoasterScreen 保持一致）
    public static final int INPUT_X = 40;
    public static final int INPUT_Y = 40;
    public static final int OUTPUT_X = 108;
    public static final int OUTPUT_Y = 40;
    public static final int ATTACK_ROW_Y = 68;
    public static final int INV_TOP = ATTACK_ROW_Y + 24;
    public static final int IMAGE_WIDTH = 220;
    public static final int IMAGE_HEIGHT = INV_TOP + 83;

    private final NutRoasterBlockEntity machine;
    private final ContainerData data;
    private boolean upgradePageActive = false;

    private final UpgradeSlot speedUpgradeSlot;
    private final UpgradeSlot energyUpgradeSlot;

    public NutRoasterMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory,
                (NutRoasterBlockEntity) inventory.player.level().getBlockEntity(buffer.readBlockPos()),
                new SimpleContainerData(NutRoasterBlockEntity.DATA_SIZE));
    }

    public NutRoasterMenu(int containerId, Inventory inventory, NutRoasterBlockEntity machine, ContainerData data) {
        super(MekCkStandaloneMachines.NUT_ROASTER_MENU.get(), containerId);
        this.machine = machine;
        this.data = data;

        // 容器槽顺序与 handler 索引一致：input, output, speed, energy, creative, power
        addSlot(new InputSlot(machine.getItems(), NutRoasterBlockEntity.INPUT_SLOT, INPUT_X, INPUT_Y));
        addSlot(new OutputSlot(machine.getItems(), NutRoasterBlockEntity.OUTPUT_SLOT, OUTPUT_X, OUTPUT_Y));

        // 升级槽（仅升级弹窗打开时可用）
        this.speedUpgradeSlot = new UpgradeSlot(machine.getItems(), NutRoasterBlockEntity.SLOT_SPEED_UPGRADE, 40, 46, this);
        addSlot(this.speedUpgradeSlot);
        this.energyUpgradeSlot = new UpgradeSlot(machine.getItems(), NutRoasterBlockEntity.SLOT_ENERGY_UPGRADE, 40, 72, this);
        addSlot(this.energyUpgradeSlot);

        // 创造升级槽（主界面常显，产物格右侧）
        addSlot(new MachineSlot(machine.getItems(), NutRoasterBlockEntity.SLOT_CREATIVE_UPGRADE, OUTPUT_X + 2 * 18 + 8, OUTPUT_Y));

        // 能源槽（能量物品）
        addSlot(new PowerSlot(machine.getItems(), NutRoasterBlockEntity.SLOT_POWER, 7, 13));

        // 玩家物品栏（居中）
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

    /** 机器实例（客户端也持有，槽位内容由菜单同步 ⇒ 可用于「本机下单」列表）。 */
    public cn.ism.mekck.blockentity.NutRoasterBlockEntity getMachine() {
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
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        int machineSlotCount = NutRoasterBlockEntity.TOTAL_SLOTS;
        if (index < machineSlotCount) {
            if (!moveItemStackTo(stack, machineSlotCount, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            if (NutRoasterBlockEntity.isUsablePowerItem(stack)) {
                if (!moveItemStackTo(stack, NutRoasterBlockEntity.SLOT_POWER, NutRoasterBlockEntity.SLOT_POWER + 1, false)) return ItemStack.EMPTY;
            } else if (cn.ism.mekck.upgrade.UpgradeHelper.isSpeedUpgrade(stack)) {
                if (!moveItemStackTo(stack, NutRoasterBlockEntity.SLOT_SPEED_UPGRADE, NutRoasterBlockEntity.SLOT_SPEED_UPGRADE + 1, false)) return ItemStack.EMPTY;
            } else if (cn.ism.mekck.upgrade.UpgradeHelper.isEnergyUpgrade(stack)) {
                if (!moveItemStackTo(stack, NutRoasterBlockEntity.SLOT_ENERGY_UPGRADE, NutRoasterBlockEntity.SLOT_ENERGY_UPGRADE + 1, false)) return ItemStack.EMPTY;
            } else if (cn.ism.mekck.upgrade.UpgradeHelper.isCreativeUpgrade(stack)) {
                if (!moveItemStackTo(stack, NutRoasterBlockEntity.SLOT_CREATIVE_UPGRADE, NutRoasterBlockEntity.SLOT_CREATIVE_UPGRADE + 1, false)) return ItemStack.EMPTY;
            } else if (!cn.ism.mekck.util.MekCkTransfer.moveItemStackTo(stack, slots,
                    NutRoasterBlockEntity.INPUT_SLOT, NutRoasterBlockEntity.INPUT_SLOT + 1, false)) {
                // 输入格上限是 Integer.MAX_VALUE：走原版会被物品自身的 64 钳制，
                // 已堆到 64 的那一格 shift 就再也并不进去（与陈酿机同一病灶）。
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }

    // ================== 数据访问 ==================
    public int getProgress() {
        int maximum = data.get(NutRoasterBlockEntity.DATA_PROCESS_TIME);
        return maximum == 0 ? 0 : data.get(NutRoasterBlockEntity.DATA_PROGRESS) * 24 / maximum;
    }

    public int getEnergy() {
        return WideDataSlot.read(data,
                NutRoasterBlockEntity.DATA_ENERGY,
                NutRoasterBlockEntity.DATA_ENERGY_HI);
    }

    public int getEnergyCapacity() {
        return NutRoasterBlockEntity.ENERGY_CAPACITY;
    }

    public int getEncodedSideConfig() {
        // 24-bit 侧配拆两槽，裸读低槽会丢 WEST/EAST 两面，见 WideDataSlot。
        return WideDataSlot.read(data,
                NutRoasterBlockEntity.DATA_SIDE_CONFIG,
                NutRoasterBlockEntity.DATA_SIDE_CONFIG_HI);
    }

    @Override
    public SideMode getSideMode(Direction direction) {
        int encoded = getEncodedSideConfig();
        int ordinal = (encoded >> (direction.ordinal() * 4)) & 0xF;
        SideMode[] values = SideMode.values();
        return (ordinal >= 0 && ordinal < values.length) ? values[ordinal] : SideMode.NONE;
    }

    public int getRedstoneControl() {
        return data.get(NutRoasterBlockEntity.DATA_REDSTONE_CONTROL);
    }

    /** 机身温度（单位 0.01 ℃）。 */
    public int getTemperature() {
        return data.get(NutRoasterBlockEntity.DATA_TEMPERATURE);
    }

    public int getTargetType() {
        return data.get(NutRoasterBlockEntity.DATA_TARGET_TYPE);
    }

    public int getRadius() {
        return data.get(NutRoasterBlockEntity.DATA_RADIUS);
    }

    @Override
    public BlockPos getBlockPos() {
        return machine.getBlockPos();
    }

    // ================== IUpgradeMenu ==================
    public int getSpeedUpgradeCount() {
        return data.get(NutRoasterBlockEntity.DATA_SPEED_UPGRADE);
    }

    public int getEnergyUpgradeCount() {
        return data.get(NutRoasterBlockEntity.DATA_ENERGY_UPGRADE);
    }

    @Override
    public int getSpeedUpgradeMax() {
        return MekckConfig.getBasicSpeedUpgradeMax();
    }

    @Override
    public int getEnergyUpgradeMax() {
        return MekckConfig.getBasicEnergyUpgradeMax();
    }

    @Override
    public Slot getSpeedUpgradeSlot() {
        return speedUpgradeSlot;
    }

    @Override
    public Slot getEnergyUpgradeSlot() {
        return energyUpgradeSlot;
    }

    @Override
    public void setUpgradePageActive(boolean active) {
        this.upgradePageActive = active;
    }

    @Override
    public boolean isUpgradePageActive() {
        return this.upgradePageActive;
    }

    // ================== 槽位类型 ==================
    private static final class PowerSlot extends SlotItemHandler implements IVirtualSlot {
        private PowerSlot(ItemStackHandler handler, int slot, int x, int y) {
            super(handler, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return NutRoasterBlockEntity.isUsablePowerItem(stack);
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
        private final int slotIndex;

        private InputSlot(ItemStackHandler handler, int slot, int x, int y) {
            super(handler, slot, x, y);
            this.slotIndex = slot;
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return getItemHandler().getSlotLimit(slotIndex);
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
        private OutputSlot(ItemStackHandler handler, int slot, int x, int y) {
            super(handler, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return getItemHandler().getSlotLimit(NutRoasterBlockEntity.OUTPUT_SLOT);
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

    /** 创造升级槽（主界面常显，是否可放由 handler 的 isItemValid 决定）。 */
    private static final class MachineSlot extends SlotItemHandler implements IVirtualSlot {
        private MachineSlot(ItemStackHandler handler, int slot, int x, int y) {
            super(handler, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return getItemHandler().isItemValid(getSlotIndex(), stack);
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return 1;
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
        /** 升级窗口未打开时把渲染位置移出屏幕：主屏便既不绘制、也命中不到它
         *  （Mek 的 VirtualSlotContainerScreen 渲染与 isMouseOverSlot 都走 getActualX/Y）。
         *  刻意<b>不改 isActive()</b> —— 那是槽的语义标志（服务端 mayPlace/转移逻辑依赖它），
         *  为了纯视觉的布局问题去改写它风险过大。 */
        private static final int HIDDEN_POS = -9999;

        private final NutRoasterMenu menu;
        private IGUIWindow linkedWindow;
        // 存供给器而非快照：窗口拖拽后 getActualX/Y 必须实时跟随。
        private IntSupplier xSupplier, ySupplier;
        private ItemStack stackToRender = ItemStack.EMPTY;
        private boolean overlay;
        private String tooltip;

        private UpgradeSlot(ItemStackHandler handler, int slot, int x, int y, NutRoasterMenu menu) {
            super(handler, slot, x, y);
            this.menu = menu;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }

        @Override public boolean isActive() { return true; }

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

package cn.ism.mekck.menu;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.blockentity.IceFactoryBlockEntity;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.item.ColdBrewTier;
import cn.ism.mekck.item.ColdBrewUpgradeItem;
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
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

import java.util.function.IntSupplier;
import cn.ism.mekck.registry.MekCkFactories;

public final class IceFactoryMenu extends AbstractContainerMenu implements ISideConfigurableMenu, IUpgradeMenu {
    // Mekanism 风格布局常量（与 IceFactoryScreen 保持一致）
    public static final int INPUT_START_X = 38;
    public static final int INPUT_START_Y = 41;
    public static final int GAP_BETWEEN = 30;
    public static final int MIN_IMAGE_WIDTH = 200;

    private final IceFactoryBlockEntity machine;
    private final ContainerData data;
    private final int processes;
    private final boolean hasCreative;
    private final int cbSlotStartIndex;
    private final int creativeSlotIndex;
    private final int powerSlotIndex;
    private boolean upgradePageActive = false;

    private final UpgradeSlot speedUpgradeSlot;
    private final UpgradeSlot energyUpgradeSlot;
    private final UpgradeSlot stackUpgradeSlot;

    public IceFactoryMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory,
                (IceFactoryBlockEntity) inventory.player.level().getBlockEntity(buffer.readBlockPos()),
                new SimpleContainerData(IceFactoryBlockEntity.DATA_SIZE));
    }

    public IceFactoryMenu(int containerId, Inventory inventory, IceFactoryBlockEntity machine, ContainerData data) {
        super(MekCkFactories.ICE_FACTORY_MENU.get(), containerId);
        this.machine = machine;
        this.data = data;
        this.processes = machine.getProcesses();
        this.hasCreative = machine.CREATIVE_SLOT >= 0;

        int cols = getGridCols();
        int rows = getGridRows();

        // 输入格（与其他多线程工厂一致的方形网格）
        for (int i = 0; i < processes; i++) {
            int col = i % cols;
            int row = i / cols;
            addSlot(new InputSlot(machine.getItems(), i, INPUT_START_X + col * 18, INPUT_START_Y + row * 18));
        }

        // 输出格（与输入相同的方形网格，中间留出进度条间距）
        int outputBaseX = INPUT_START_X + cols * 18 + GAP_BETWEEN;
        for (int i = 0; i < processes; i++) {
            int col = i % cols;
            int row = i / cols;
            addSlot(new OutputSlot(machine.getItems(), processes + i, outputBaseX + col * 18, INPUT_START_Y + row * 18));
        }

        // 升级槽（仅升级弹窗打开时可用）
        this.speedUpgradeSlot = new UpgradeSlot(machine.getItems(), machine.SPEED_UPGRADE_SLOT, 40, 46, this);
        addSlot(this.speedUpgradeSlot);
        this.energyUpgradeSlot = new UpgradeSlot(machine.getItems(), machine.ENERGY_UPGRADE_SLOT, 40, 72, this);
        addSlot(this.energyUpgradeSlot);
        this.stackUpgradeSlot = new UpgradeSlot(machine.getItems(), machine.STACK_UPGRADE_SLOT, 40, 98, this);
        addSlot(this.stackUpgradeSlot);

        // 冷萃升级槽 + 创造升级槽（主界面，输入网格下方；冷萃 ①~⑤ 一排 5 格）
        this.cbSlotStartIndex = slots.size();
        int cbY = getCbSlotY();
        for (int i = 0; i < 5; i++) {
            addSlot(new ColdBrewSlot(machine.getItems(), machine.CB_SLOT_1 + i, INPUT_START_X + i * 18, cbY));
        }
        if (hasCreative) {
            this.creativeSlotIndex = slots.size();
            addSlot(new ColdBrewSlot(machine.getItems(), machine.CREATIVE_SLOT, INPUT_START_X + 5 * 18 + 8, cbY));
        } else {
            this.creativeSlotIndex = -1;
        }

        // 能源槽（能量物品：能量立方/红石等），Mekanism 风格位置
        this.powerSlotIndex = slots.size();
        addSlot(new PowerSlot(machine.getItems(), machine.POWER_SLOT, 7, 13));

        // 玩家物品栏（居中，随网格行数下移）
        int invTop = getInventoryTop();
        int imageWidth = getImageWidth();
        int invLeft = (imageWidth - 162) / 2;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, invLeft + column * 18, invTop + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, invLeft + column * 18, invTop + 58));
        }

        addDataSlots(data);
    }

    // ================== 布局计算（与屏幕共享） ==================
    public int getGridCols() {
        return (int) Math.ceil(Math.sqrt(processes));
    }

    public int getGridRows() {
        return (int) Math.ceil((double) processes / getGridCols());
    }

    public int getCbSlotY() {
        return INPUT_START_Y + getGridRows() * 18 + 6;
    }

    public int getAttackRowY() {
        return getCbSlotY() + 24;
    }

    public int getInventoryTop() {
        return getAttackRowY() + 24;
    }

    public int getImageWidth() {
        int cols = getGridCols();
        return Math.max(INPUT_START_X + cols * 18 + GAP_BETWEEN + cols * 18 + 20, MIN_IMAGE_WIDTH);
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
        int machineSlotCount = powerSlotIndex + 1;
        int speedIdx = processes * 2;
        int energyIdx = speedIdx + 1;
        int stackIdx = speedIdx + 2;

        if (index < machineSlotCount) {
            if (!moveItemStackTo(stack, machineSlotCount, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (IceFactoryBlockEntity.isUsablePowerItem(stack)) {
                if (!moveItemStackTo(stack, powerSlotIndex, powerSlotIndex + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (cn.ism.mekck.util.UpgradeHelper.isSpeedUpgrade(stack)) {
                if (!moveItemStackTo(stack, speedIdx, speedIdx + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (cn.ism.mekck.util.UpgradeHelper.isEnergyUpgrade(stack)) {
                if (!moveItemStackTo(stack, energyIdx, energyIdx + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (cn.ism.mekck.util.UpgradeHelper.isStackUpgrade(stack)) {
                if (!moveItemStackTo(stack, stackIdx, stackIdx + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (hasCreative && cn.ism.mekck.util.UpgradeHelper.isCreativeUpgrade(stack)) {
                if (!moveItemStackTo(stack, creativeSlotIndex, creativeSlotIndex + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else {
                ColdBrewTier cb = ColdBrewUpgradeItem.getTier(stack);
                if (cb != null) {
                    int target = switch (cb) {
                        case COLD -> cbSlotStartIndex;
                        case LOW_TEMP -> cbSlotStartIndex + 1;
                        case FROST -> cbSlotStartIndex + 2;
                        case DRAGON_FROST, QUEEN -> cbSlotStartIndex + 3;
                        case HYPOTHERMIA -> cbSlotStartIndex + 4;
                    };
                    if (!moveItemStackTo(stack, target, target + 1, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (!moveItemStackTo(stack, 0, processes, false)) {
                    return ItemStack.EMPTY;
                }
            }
        }
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    // ================== 数据访问 ==================
    public int getProgress() {
        int maximum = data.get(IceFactoryBlockEntity.DATA_PROCESS_TIME);
        return maximum == 0 ? 0 : data.get(IceFactoryBlockEntity.DATA_PROGRESS) * 24 / maximum;
    }

    public int getEnergy() {
        return WideDataSlot.read(data,
                IceFactoryBlockEntity.DATA_ENERGY,
                IceFactoryBlockEntity.DATA_ENERGY_HI);
    }

    public int getEnergyCapacity() {
        return machine.tier.energyCapacity > 0 ? machine.tier.energyCapacity : 100_000;
    }

    /** 从 ContainerData 同步值重建流体（客户端 FluidTank 不进网络同步，直接读会是空罐）。 */
    public FluidStack getWaterStack() {
        int amount = data.get(IceFactoryBlockEntity.DATA_WATER_AMOUNT);
        if (amount <= 0) return FluidStack.EMPTY;
        int id = data.get(IceFactoryBlockEntity.DATA_WATER_FLUID_ID);
        net.minecraft.world.level.material.Fluid fluid = id >= 0
                ? net.minecraft.core.registries.BuiltInRegistries.FLUID.byId(id) : null;
        if (fluid == null || fluid == net.minecraft.world.level.material.Fluids.EMPTY) return FluidStack.EMPTY;
        return new FluidStack(fluid, amount);
    }

    public int getWaterCapacity() {
        return machine.getWaterTank().getCapacity();
    }

    public int getEncodedSideConfig() {
        return data.get(IceFactoryBlockEntity.DATA_SIDE_CONFIG);
    }

    @Override
    public SideMode getSideMode(Direction direction) {
        int encoded = getEncodedSideConfig();
        int ordinal = (encoded >> (direction.ordinal() * 4)) & 0xF;
        SideMode[] values = SideMode.values();
        return (ordinal >= 0 && ordinal < values.length) ? values[ordinal] : SideMode.NONE;
    }

    public int getRedstoneControl() {
        return data.get(IceFactoryBlockEntity.DATA_REDSTONE_CONTROL);
    }

    public int getTargetType() {
        return data.get(IceFactoryBlockEntity.DATA_TARGET_TYPE);
    }

    public int getRadius() {
        return data.get(IceFactoryBlockEntity.DATA_RADIUS);
    }

    public int getProcesses() {
        return processes;
    }

    public CuttingMachineFactoryTier getTier() {
        return machine.tier;
    }

    public boolean hasCreative() {
        return hasCreative;
    }

    public int getCbSlotIndex(int i) {
        return cbSlotStartIndex + i;
    }

    public int getCreativeSlotIndex() {
        return creativeSlotIndex;
    }

    public int getPowerSlotIndex() {
        return powerSlotIndex;
    }

    @Override
    public BlockPos getBlockPos() {
        return machine.getBlockPos();
    }

    // ================== IUpgradeMenu ==================
    /** 卸载升级（升级界面卸载按钮）。 */
    @Override
    public void uninstallUpgrade(byte mode, int slot) {
        cn.ism.mekck.network.ModMessages.sendToServer(
                new cn.ism.mekck.network.UpgradeUninstallPacket(machine.getBlockPos(), mode, slot));
    }

    @Override
    public boolean supportsUpgradeUninstall() {
        return true;
    }

    public int getSpeedUpgradeCount() {
        return data.get(IceFactoryBlockEntity.DATA_SPEED_UPGRADE);
    }

    public int getEnergyUpgradeCount() {
        return data.get(IceFactoryBlockEntity.DATA_ENERGY_UPGRADE);
    }

    @Override
    public int getStackUpgradeCount() {
        return data.get(IceFactoryBlockEntity.DATA_STACK_UPGRADE);
    }

    @Override
    public boolean hasStackUpgrade() {
        return true;
    }

    @Override
    public int getSpeedUpgradeMax() {
        return MekckConfig.getFactorySpeedUpgradeMax(machine.tier);
    }

    @Override
    public int getEnergyUpgradeMax() {
        return MekckConfig.getFactoryEnergyUpgradeMax(machine.tier);
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
    public Slot getStackUpgradeSlot() {
        return stackUpgradeSlot;
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
            return IceFactoryBlockEntity.isUsablePowerItem(stack);
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

    /** 冷萃/创造升级槽（主界面常显）。 */
    private static final class ColdBrewSlot extends SlotItemHandler implements IVirtualSlot {
        private ColdBrewSlot(ItemStackHandler handler, int slot, int x, int y) {
            super(handler, slot, x, y);
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
        private final IceFactoryMenu menu;
        private IGUIWindow linkedWindow;
        private int actualX, actualY;
        private ItemStack stackToRender = ItemStack.EMPTY;
        private boolean overlay;
        private String tooltip;

        private UpgradeSlot(ItemStackHandler handler, int slot, int x, int y, IceFactoryMenu menu) {
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

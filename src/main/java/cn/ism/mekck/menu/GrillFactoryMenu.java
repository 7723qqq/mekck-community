package cn.ism.mekck.menu;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.util.BarbequesDelightCompat;
import cn.ism.mekck.util.KaleidoscopeGrillingCompat;
import cn.ism.mekck.util.MekCkTransfer;
import cn.ism.mekck.blockentity.GrillFactoryBlockEntity;
import mekanism.common.inventory.container.IGUIWindow;
import mekanism.common.inventory.container.slot.IVirtualSlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import org.jetbrains.annotations.Nullable;

import java.util.function.IntSupplier;

public final class GrillFactoryMenu extends AbstractContainerMenu implements ISideConfigurableMenu, IUpgradeMenu {
    private final GrillFactoryBlockEntity machine;
    private final ContainerData data;
    private final CuttingMachineFactoryTier tier;
    private final boolean hasStackUpgrade;
    private boolean upgradePageActive = false;

    private final UpgradeSlot speedUpgradeSlot;
    private final UpgradeSlot energyUpgradeSlot;
    private final UpgradeSlot stackUpgradeSlot;

    public GrillFactoryMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory,
                (GrillFactoryBlockEntity) inventory.player.level().getBlockEntity(buffer.readBlockPos()));
    }

    public GrillFactoryMenu(int containerId, Inventory inventory, GrillFactoryBlockEntity machine) {
        this(containerId, inventory, machine, machine.getData());
    }

    public GrillFactoryMenu(int containerId, Inventory inventory, GrillFactoryBlockEntity machine, ContainerData data) {
        super(UniversalCuttingMachine.GRILL_FACTORY_MENU.get(), containerId);
        this.machine = machine;
        this.data = data;
        this.tier = machine.getTier();
        this.hasStackUpgrade = machine.hasStackUpgradeSlot();

        int inputSlots = tier.processes;
        int cols = (int) Math.ceil(Math.sqrt(inputSlots));
        int rows = (int) Math.ceil((double) inputSlots / cols);

        // Input slots (tightly packed grid, 18px spacing)
        int inputStartX = 38;
        int inputStartY = 41;
        for (int i = 0; i < inputSlots; i++) {
            int col = i % cols;
            int row = i / cols;
            addSlot(new InputSlot(machine.getItems(), i, inputStartX + col * 18, inputStartY + row * 18));
        }

        // Output slots (same square grid layout as input, with 30px gap matching GUI)
        int gapBetween = 30;
        int outputBaseX = inputStartX + cols * 18 + gapBetween;
        int outputBaseY = inputStartY;
        for (int i = 0; i < inputSlots; i++) {
            int col = i % cols;
            int row = i / cols;
            int slot = inputSlots + i;
            addSlot(new OutputSlot(machine, slot, outputBaseX + col * 18, outputBaseY + row * 18));
        }

        // Upgrade slots (at normal positions, visible in upgrade window)
        int speedUpgradeSlot = 2 * inputSlots;
        int energyUpgradeSlot = 2 * inputSlots + 1;
        int stackUpgradeSlot = 2 * inputSlots + 2;
        this.speedUpgradeSlot = new UpgradeSlot(machine.getItems(), speedUpgradeSlot, 40, 46, this);
        addSlot(this.speedUpgradeSlot);
        this.energyUpgradeSlot = new UpgradeSlot(machine.getItems(), energyUpgradeSlot, 40, 72, this);
        addSlot(this.energyUpgradeSlot);
        if (hasStackUpgrade) {
            this.stackUpgradeSlot = new UpgradeSlot(machine.getItems(), stackUpgradeSlot, 40, 98, this);
            addSlot(this.stackUpgradeSlot);
        } else {
            this.stackUpgradeSlot = null;
        }

        // Power slot (energy items: energy cube / tablet / redstone), on the right near the energy bar
        int imageWidth = 38 + cols * 18 + gapBetween + cols * 18 + 20;
        addSlot(new PowerSlot(machine, machine.getPowerSlot(), 7, 13, this));

        // Seasoning slots (3): 位于输出槽网格下方，与输出中心对齐
        int seasoningStart = machine.getSeasoningSlotStart();
        int seasoningGridX = outputBaseX + (cols * 18 - GrillFactoryBlockEntity.SEASONING_SLOTS * 18) / 2;
        int seasoningGridY = outputBaseY + rows * 18 + 6;
        for (int i = 0; i < GrillFactoryBlockEntity.SEASONING_SLOTS; i++) {
            addSlot(new SeasoningSlot(machine.getItems(), seasoningStart + i,
                    seasoningGridX + i * 18, seasoningGridY));
        }

        // Storage slots (45): 存储区改「单列纵向滚动」（E4/拍板 F1·方案 5）。
        // 真实槽坐标全部移到屏幕外（-1000）——渲染与命中改由客户端 GuiVirtualSlot 承载，
        // 见 GrillFactoryScreen。索引与数量保持 STORAGE_SLOTS 个不变 ⇒ 存档兼容。
        int storageStart = machine.getStorageSlotStart();
        for (int i = 0; i < GrillFactoryBlockEntity.STORAGE_SLOTS; i++) {
            addSlot(new StorageSlot(machine.getItems(), storageStart + i, -1000, -1000));
        }

        // Player inventory slots (centered)
        int extraHeight = Math.max(0, (rows - 2) * 18);
        int invTop = Math.max(101, 85 + rows * 18);
        int invLeft = (imageWidth - 162) / 2; // center 9 columns (162px) within GUI
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
        int inputSlots = tier.processes;
        // 菜单内机器槽位（创造升级槽不出现在菜单）
        int speedUpgradeSlot = 2 * inputSlots;
        int energyUpgradeSlot = 2 * inputSlots + 1;
        int stackUpgradeSlot = 2 * inputSlots + 2;
        int powerSlot = 2 * inputSlots + (hasStackUpgrade ? 3 : 2);
        int seasoningStart = powerSlot + 1;
        int storageStart = seasoningStart + GrillFactoryBlockEntity.SEASONING_SLOTS;
        int machineSlotCount = storageStart + GrillFactoryBlockEntity.STORAGE_SLOTS;

        if (index < machineSlotCount) {
            if (!moveItemStackTo(stack, machineSlotCount, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            boolean orderMode = machine.getWorkMode() == GrillFactoryBlockEntity.WorkMode.ORDER;
            if (GrillFactoryBlockEntity.isUsablePowerItem(stack)) {
                if (!moveItemStackTo(stack, powerSlot, powerSlot + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (GrillFactoryBlockEntity.isSpeedUpgrade(stack)) {
                if (!moveItemStackTo(stack, speedUpgradeSlot, speedUpgradeSlot + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (GrillFactoryBlockEntity.isEnergyUpgrade(stack)) {
                if (!moveItemStackTo(stack, energyUpgradeSlot, energyUpgradeSlot + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (hasStackUpgrade && GrillFactoryBlockEntity.isStackUpgrade(stack)) {
                if (!moveItemStackTo(stack, stackUpgradeSlot, stackUpgradeSlot + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (BarbequesDelightCompat.isSeasoning(stack) || KaleidoscopeGrillingCompat.isSeasoningBottle(stack)) {
                if (!MekCkTransfer.moveItemStackTo(stack, slots, seasoningStart,
                        seasoningStart + GrillFactoryBlockEntity.SEASONING_SLOTS, false)
                        && !MekCkTransfer.moveItemStackTo(stack, slots, storageStart, machineSlotCount, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (orderMode) {
                // 下单模式：玩家 Shift 放入的材料进存储区，等待下单
                if (!MekCkTransfer.moveItemStackTo(stack, slots, storageStart, machineSlotCount, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!MekCkTransfer.moveItemStackTo(stack, slots, 0, inputSlots, false)) {
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
        int progress = data.get(GrillFactoryBlockEntity.DATA_PROGRESS);
        int max = data.get(GrillFactoryBlockEntity.DATA_PROCESS_TIME);
        return max == 0 ? 0 : progress * 24 / max;
    }

    public int getEnergy() {
        return data.get(GrillFactoryBlockEntity.DATA_ENERGY);
    }

    public int getEnergyCapacity() {
        return data.get(GrillFactoryBlockEntity.DATA_ENERGY_CAPACITY);
    }

    public int getEncodedSideConfig() {
        return data.get(GrillFactoryBlockEntity.DATA_SIDE_CONFIG);
    }

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
        return data.get(GrillFactoryBlockEntity.DATA_SPEED_UPGRADE);
    }

    public int getEnergyUpgradeCount() {
        return data.get(GrillFactoryBlockEntity.DATA_ENERGY_UPGRADE);
    }

    public int getStackUpgradeCount() {
        return data.get(GrillFactoryBlockEntity.DATA_STACK_UPGRADE);
    }

    public int getRedstoneControl() {
        return data.get(GrillFactoryBlockEntity.DATA_REDSTONE_CONTROL);
    }

    /** 机身温度（单位 0.01 ℃）。 */
    public int getTemperature() {
        return data.get(GrillFactoryBlockEntity.DATA_TEMPERATURE);
    }

    public boolean getAutoDistribute() {
        return data.get(GrillFactoryBlockEntity.DATA_AUTO_DISTRIBUTE) != 0;
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

    @Override
    public boolean supportsStoragePull() {
        return true;
    }

    public CuttingMachineFactoryTier getTier() {
        return tier;
    }

    @Override
    public int getSpeedUpgradeMax() {
        return MekckConfig.getFactorySpeedUpgradeMax(tier);
    }

    @Override
    public int getEnergyUpgradeMax() {
        return MekckConfig.getFactoryEnergyUpgradeMax(tier);
    }

    public BlockPos getBlockPos() {
        return machine.getBlockPos();
    }

    public boolean hasStackUpgrade() {
        return hasStackUpgrade;
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

    public void setUpgradePageActive(boolean active) {
        this.upgradePageActive = active;
    }

    public boolean isUpgradePageActive() {
        return this.upgradePageActive;
    }

    public GrillFactoryBlockEntity getMachine() {
        return machine;
    }

    // ================== 订单系统（含调味） ==================

    public int getOrderQuantity() {
        return data.get(GrillFactoryBlockEntity.DATA_ORDER_QUANTITY);
    }

    public int getOrderCompleted() {
        return data.get(GrillFactoryBlockEntity.DATA_ORDER_COMPLETED);
    }

    @Nullable
    public String getOrderSeasoning() {
        return machine.getOrderSeasoning();
    }

    /** 客户端可用的当前订单调味料下标（经 ContainerData 同步），-1 表示无。 */
    public int getOrderSeasoningIndex() {
        return data.get(GrillFactoryBlockEntity.DATA_ORDER_SEASONING);
    }

    /** 工作模式（经 ContainerData 同步）。 */
    public GrillFactoryBlockEntity.WorkMode getWorkMode() {
        return GrillFactoryBlockEntity.WorkMode.byOrdinal(data.get(GrillFactoryBlockEntity.DATA_WORK_MODE));
    }

    /** 第 index 个调味料槽是否启用自动调味（经 ContainerData 位图同步）。 */
    public boolean getSeasoningEnabled(int index) {
        return (data.get(GrillFactoryBlockEntity.DATA_SEASONING_ENABLED) & (1 << index)) != 0;
    }

    private static final class SeasoningSlot extends SlotItemHandler {
        private SeasoningSlot(ItemStackHandler handler, int slot, int x, int y) {
            super(handler, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return BarbequesDelightCompat.isSeasoning(stack) || KaleidoscopeGrillingCompat.isSeasoningBottle(stack);
        }
    }

    private static final class StorageSlot extends SlotItemHandler implements IVirtualSlot {
        // 与 UpgradeSlot 同款：真正实现 updatePosition（存 supplier），
        // 使 getActualX/Y 实时反映 GuiVirtualSlot 的滚动位置（renderSlot/findSlot 均以此定位）。
        private IGUIWindow linkedWindow;
        private IntSupplier xSupplier;
        private IntSupplier ySupplier;
        private ItemStack stackToRender = ItemStack.EMPTY;
        private boolean overlay;
        private String tooltip;

        private StorageSlot(ItemStackHandler handler, int slot, int x, int y) {
            super(handler, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !GrillFactoryBlockEntity.isAnyUpgradeItem(stack);
        }

        @Override public IGUIWindow getLinkedWindow() { return linkedWindow; }
        @Override public int getActualX() { return xSupplier != null ? xSupplier.getAsInt() : x; }
        @Override public int getActualY() { return ySupplier != null ? ySupplier.getAsInt() : y; }
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

    private static final class PowerSlot extends SlotItemHandler implements IVirtualSlot {
        private PowerSlot(GrillFactoryBlockEntity machine, int slot, int x, int y, GrillFactoryMenu menu) {
            super(machine.getItems(), slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return GrillFactoryBlockEntity.isUsablePowerItem(stack);
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

    private static final class OutputSlot extends SlotItemHandler implements IVirtualSlot {
        private OutputSlot(GrillFactoryBlockEntity machine, int slot, int x, int y) {
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

    private static final class UpgradeSlot extends SlotItemHandler implements IVirtualSlot {
        private final GrillFactoryMenu menu;
        private IGUIWindow linkedWindow;
        private int actualX, actualY;
        private ItemStack stackToRender = ItemStack.EMPTY;
        private boolean overlay;
        private String tooltip;

        private UpgradeSlot(ItemStackHandler handler, int slot, int x, int y, GrillFactoryMenu menu) {
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
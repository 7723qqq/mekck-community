package cn.ism.mekck.menu;

import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.blockentity.SkeweringMachineBlockEntity;
import cn.ism.mekck.util.MekCkTransfer;
import mekanism.common.inventory.container.IGUIWindow;
import mekanism.common.inventory.container.slot.IVirtualSlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
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
import org.jetbrains.annotations.Nullable;

import java.util.function.IntSupplier;

public final class SkeweringMachineMenu extends AbstractContainerMenu implements ISideConfigurableMenu, IUpgradeMenu {
    /**
     * 机器槽在 {@code slots} 里的数量：3 输入 + 产物 + 返还 + 速度 + 能量 + 81 存储 + 能源 = 89。
     *
     * <p>恒等于 {@code 8 + STORAGE_SLOT_COUNT}，所以它本身是对的；出问题的是 else 分支
     * 指向能源槽的那个区间用了 <b>handler 下标</b> {@code SLOT_POWER = 8 + 81 = 89}，
     * 而能源槽的<b>菜单</b>下标是 88（handler 的 88 号「创造升级槽」在本菜单没有 addSlot）。
     * 89 恰好是本菜单玩家背包第 0 格的下标 ⇒ 点那一格且持能量物品时区间自指 → 复制。</p>
     */
    private static final int MACHINE_SLOT_COUNT = 8 + SkeweringMachineBlockEntity.STORAGE_SLOT_COUNT;
    private final SkeweringMachineBlockEntity machine;
    private final ContainerData data;
    private boolean upgradePageActive = false;

    private final UpgradeSlot speedUpgradeSlot;
    private final UpgradeSlot energyUpgradeSlot;

    /**
     * 能源槽的<b>菜单下标</b>（= 88，不是 handler 的 89）。
     *
     * <p>本菜单跳过了 handler 的 88 号创造升级槽，所以 88 之后所有 handler 下标都比
     * 菜单下标大 1；任何「用 handler 常量当菜单下标」的写法都会错位到玩家背包。</p>
     */
    private final int powerSlotIndex;

    public SkeweringMachineMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory,
                (SkeweringMachineBlockEntity) inventory.player.level().getBlockEntity(buffer.readBlockPos()),
                new SimpleContainerData(SkeweringMachineBlockEntity.DATA_SIZE));
    }

    public SkeweringMachineMenu(int containerId, Inventory inventory, SkeweringMachineBlockEntity machine, ContainerData data) {
        super(UniversalCuttingMachine.SKEWERING_MACHINE_MENU.get(), containerId);
        this.machine = machine;
        this.data = data;

        // 3 input slots: 1 row x 3 columns, starting from (38, 41), 18px spacing
        addSlot(new InputSlot(machine, SkeweringMachineBlockEntity.INPUT_SLOT_START + 0, 38, 41));
        addSlot(new InputSlot(machine, SkeweringMachineBlockEntity.INPUT_SLOT_START + 1, 56, 41));
        addSlot(new InputSlot(machine, SkeweringMachineBlockEntity.INPUT_SLOT_START + 2, 74, 41));

        // Output slot (130, 41)
        addSlot(new OutputSlot(machine, SkeweringMachineBlockEntity.OUTPUT_SLOT, 130, 41));
        // Return slot (130, 59)
        addSlot(new OutputSlot(machine, SkeweringMachineBlockEntity.RETURN_SLOT, 130, 59));

        // Upgrade slots (visible only in upgrade page, not removable)
        this.speedUpgradeSlot = new UpgradeSlot(machine.getItems(), SkeweringMachineBlockEntity.SLOT_SPEED_UPGRADE, 40, 46, this);
        addSlot(this.speedUpgradeSlot);
        this.energyUpgradeSlot = new UpgradeSlot(machine.getItems(), SkeweringMachineBlockEntity.SLOT_ENERGY_UPGRADE, 40, 72, this);
        addSlot(this.energyUpgradeSlot);

        // 存储槽（81）：改「单列纵向滚动」（拍板 F1·方案 5，与烧烤工厂同款）。
        // 真实槽坐标全部移到屏幕外（-1000）——渲染与命中改由客户端 GuiVirtualSlot 承载，
        // 见 SkeweringMachineScreen。索引与数量保持 STORAGE_SLOT_COUNT 个不变 ⇒ 存档兼容。
        int storageSlots = SkeweringMachineBlockEntity.STORAGE_SLOT_COUNT;
        for (int i = 0; i < storageSlots; i++) {
            int slotIndex = SkeweringMachineBlockEntity.STORAGE_SLOT_START + i;
            addSlot(new StorageSlot(machine.getItems(), slotIndex, -1000, -1000));
        }

        // Power slot (energy items: energy cube / tablet / redstone) next to the energy bar
        this.powerSlotIndex = slots.size();
        addSlot(new PowerSlot(machine, SkeweringMachineBlockEntity.SLOT_POWER, 7, 13, this));

        // Player inventory: starting from (20, 101)
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

    public int getImageWidth() {
        return Math.max(176, 130 + 18 + 20);
    }

    // 存储区改「单列纵向滚动」后，GUI 高度不再由存储行数推导（此前 9 行 ⇒ 333 高，缩放 4 下出屏）。
    // 固定为 184，与菜单玩家槽 (20,101)/(20,159) 对齐（快捷栏底 177 < 184）。
    public int getImageHeight() {
        return 184;
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
            if (SkeweringMachineBlockEntity.isUsablePowerItem(stack)) {
                // powerSlotIndex(=88) 是菜单下标；handler 常量 SLOT_POWER(=89) 会指向玩家背包第 0 格
                if (!moveItemStackTo(stack, powerSlotIndex, powerSlotIndex + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (SkeweringMachineBlockEntity.isSpeedUpgrade(stack)) {
                if (!moveItemStackTo(stack, SkeweringMachineBlockEntity.SLOT_SPEED_UPGRADE,
                        SkeweringMachineBlockEntity.SLOT_SPEED_UPGRADE + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (SkeweringMachineBlockEntity.isEnergyUpgrade(stack)) {
                if (!moveItemStackTo(stack, SkeweringMachineBlockEntity.SLOT_ENERGY_UPGRADE,
                        SkeweringMachineBlockEntity.SLOT_ENERGY_UPGRADE + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // Try to move to storage slots first
                if (!MekCkTransfer.moveItemStackTo(stack, slots, SkeweringMachineBlockEntity.STORAGE_SLOT_START,
                        SkeweringMachineBlockEntity.STORAGE_SLOT_START + SkeweringMachineBlockEntity.STORAGE_SLOT_COUNT, false)) {
                    // Then try input slots
                    if (!MekCkTransfer.moveItemStackTo(stack, slots, SkeweringMachineBlockEntity.INPUT_SLOT_START,
                            SkeweringMachineBlockEntity.INPUT_SLOT_END + 1, false)) {
                        return ItemStack.EMPTY;
                    }
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

    @Override
    public boolean supportsStoragePull() {
        return true;
    }

    public int getSpeedUpgradeCount() {
        return machine.getSpeedUpgradeCount();
    }

    public int getEnergyUpgradeCount() {
        return machine.getEnergyUpgradeCount();
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

    public int getOrderQuantity() {
        return data.get(SkeweringMachineBlockEntity.DATA_ORDER_QUANTITY);
    }

    public int getOrderCompleted() {
        return data.get(SkeweringMachineBlockEntity.DATA_ORDER_COMPLETED);
    }

    public int getRedstoneControl() {
        return data.get(SkeweringMachineBlockEntity.DATA_REDSTONE_CONTROL);
    }

    @Nullable
    public ResourceLocation getOrderRecipeId() {
        return machine.getOrderRecipeId();
    }

    public int getMaxOrderQuantity() {
        ResourceLocation recipeId = machine.getOrderRecipeId();
        if (recipeId == null) return 0;
        // For SkeweringMachine, we don't have getMaxConsumableCount, so return 0
        return 0;
    }

    public BlockPos getBlockPos() {
        return machine.getBlockPos();
    }

    public SkeweringMachineBlockEntity getMachine() {
        return machine;
    }

    private static final class PowerSlot extends SlotItemHandler implements IVirtualSlot {
        private PowerSlot(SkeweringMachineBlockEntity machine, int slot, int x, int y, SkeweringMachineMenu menu) {
            super(machine.getItems(), slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return SkeweringMachineBlockEntity.isUsablePowerItem(stack);
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
        private InputSlot(SkeweringMachineBlockEntity machine, int slot, int x, int y) {
            super(machine.getItems(), slot, x, y);
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
        private OutputSlot(SkeweringMachineBlockEntity machine, int slot, int x, int y) {
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
        private final SkeweringMachineMenu menu;
        private IGUIWindow linkedWindow;
        private int actualX, actualY;
        private ItemStack stackToRender = ItemStack.EMPTY;
        private boolean overlay;
        private String tooltip;

        private UpgradeSlot(ItemStackHandler handler, int slot, int x, int y, SkeweringMachineMenu menu) {
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
        public boolean isActive() {
            // 升级槽只应在升级窗口（GuiUpgradeWindow）打开时由该窗口的 GuiVirtualSlot 渲染。
            // 主屏不该按构造坐标(40,46/72/98)画它们 —— 否则网格下方会浮出一个压住「物品栏」的空槽。
            // IVirtualSlot 槽必须仍留在 menu.slots 里，quickMoveStack 只检查 mayPlace()，不受 isActive 影响。
            return linkedWindow != null;
        }

        @Override public IGUIWindow getLinkedWindow() { return linkedWindow; }
        @Override public int getActualX() { return actualX; }
        @Override public int getActualY() { return actualY; }
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

    // 存储槽：改「单列纵向滚动」——与 grill 同款，实现 IVirtualSlot 承载渲染/命中；
    // getActualX/Y 走 supplier，实时跟随客户端 GuiVirtualSlot 的位置（真实槽已移到 -1000）。
    private static final class StorageSlot extends SlotItemHandler implements IVirtualSlot {
        private IGUIWindow linkedWindow;
        private IntSupplier xSupplier;
        private IntSupplier ySupplier;
        private ItemStack stackToRender = ItemStack.EMPTY;
        private boolean overlay;
        private String tooltip;

        private StorageSlot(ItemStackHandler handler, int slot, int x, int y) {
            super(handler, slot, x, y);
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
}
package cn.ism.mekck.menu;

import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.blockentity.ChocolateCannonBlockEntity;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.item.FerreroUpgradeTier;
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
import cn.ism.mekck.registry.MekCkStandaloneMachines;

public final class ChocolateCannonMenu extends AbstractContainerMenu implements ISideConfigurableMenu, IUpgradeMenu {
    // Mekanism 风格布局常量（与 ChocolateCannonScreen 保持一致）
    public static final int INPUT_X = 40;
    public static final int INPUT_Y = 40;
    public static final int EXTRA_X = 40;
    public static final int EXTRA_Y = 62;
    public static final int OUTPUT_X = 108;
    public static final int OUTPUT_Y = 40;
    public static final int FERRERO_ROW_Y = 92;
    public static final int ATTACK_ROW_Y = 116;
    public static final int INV_TOP = ATTACK_ROW_Y + 24;
    public static final int IMAGE_WIDTH = 220;
    public static final int IMAGE_HEIGHT = INV_TOP + 83;

    private final ChocolateCannonBlockEntity machine;
    private final ContainerData data;
    private boolean upgradePageActive = false;

    private final UpgradeSlot speedUpgradeSlot;
    private final UpgradeSlot energyUpgradeSlot;

    public ChocolateCannonMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory,
                (ChocolateCannonBlockEntity) inventory.player.level().getBlockEntity(buffer.readBlockPos()),
                new SimpleContainerData(ChocolateCannonBlockEntity.DATA_SIZE));
    }

    public ChocolateCannonMenu(int containerId, Inventory inventory, ChocolateCannonBlockEntity machine, ContainerData data) {
        super(MekCkStandaloneMachines.CHOCOLATE_CANNON_MENU.get(), containerId);
        this.machine = machine;
        this.data = data;

        // 容器槽顺序与 handler 索引一致：input, extra, output, speed, energy, creative, ferrero×5, fluid×2, power
        addSlot(new InputSlot(machine.getItems(), ChocolateCannonBlockEntity.INPUT_SLOT, INPUT_X, INPUT_Y));
        addSlot(new InputSlot(machine.getItems(), ChocolateCannonBlockEntity.EXTRA_SLOT, EXTRA_X, EXTRA_Y));
        addSlot(new OutputSlot(machine.getItems(), ChocolateCannonBlockEntity.OUTPUT_SLOT, OUTPUT_X, OUTPUT_Y));

        // 升级槽（仅升级弹窗打开时可用）
        this.speedUpgradeSlot = new UpgradeSlot(machine.getItems(), ChocolateCannonBlockEntity.SLOT_SPEED_UPGRADE, 40, 46, this);
        addSlot(this.speedUpgradeSlot);
        this.energyUpgradeSlot = new UpgradeSlot(machine.getItems(), ChocolateCannonBlockEntity.SLOT_ENERGY_UPGRADE, 40, 72, this);
        addSlot(this.energyUpgradeSlot);

        // 费列罗升级槽 ×5（主界面常显）+ 创造升级槽（右侧）
        addSlot(new FerreroSlot(machine.getItems(), ChocolateCannonBlockEntity.SLOT_CREATIVE_UPGRADE, INPUT_X + 5 * 18 + 8, FERRERO_ROW_Y));
        for (int i = 0; i < FerreroUpgradeTier.values().length; i++) {
            addSlot(new FerreroSlot(machine.getItems(), ChocolateCannonBlockEntity.FERRERO_SLOT_BASE + i, INPUT_X + i * 18, FERRERO_ROW_Y));
        }

        // 流体容器槽 ×2（对应两个流体罐）
        addSlot(new FluidSlot(machine.getItems(), ChocolateCannonBlockEntity.FLUID_SLOT_1, 6, FERRERO_ROW_Y));
        addSlot(new FluidSlot(machine.getItems(), ChocolateCannonBlockEntity.FLUID_SLOT_2, 24, FERRERO_ROW_Y));

        // 能源槽（能量物品），Mekanism 风格位置
        addSlot(new PowerSlot(machine.getItems(), ChocolateCannonBlockEntity.SLOT_POWER, 7, 13));

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
    public cn.ism.mekck.blockentity.ChocolateCannonBlockEntity getMachine() {
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
        int machineSlotCount = ChocolateCannonBlockEntity.TOTAL_SLOTS;
        if (index < machineSlotCount) {
            if (!moveItemStackTo(stack, machineSlotCount, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            if (ChocolateCannonBlockEntity.isUsablePowerItem(stack)) {
                if (!moveItemStackTo(stack, ChocolateCannonBlockEntity.SLOT_POWER, ChocolateCannonBlockEntity.SLOT_POWER + 1, false)) return ItemStack.EMPTY;
            } else if (cn.ism.mekck.upgrade.UpgradeHelper.isSpeedUpgrade(stack)) {
                if (!moveItemStackTo(stack, ChocolateCannonBlockEntity.SLOT_SPEED_UPGRADE, ChocolateCannonBlockEntity.SLOT_SPEED_UPGRADE + 1, false)) return ItemStack.EMPTY;
            } else if (cn.ism.mekck.upgrade.UpgradeHelper.isEnergyUpgrade(stack)) {
                if (!moveItemStackTo(stack, ChocolateCannonBlockEntity.SLOT_ENERGY_UPGRADE, ChocolateCannonBlockEntity.SLOT_ENERGY_UPGRADE + 1, false)) return ItemStack.EMPTY;
            } else if (cn.ism.mekck.upgrade.UpgradeHelper.isCreativeUpgrade(stack)) {
                if (!moveItemStackTo(stack, ChocolateCannonBlockEntity.SLOT_CREATIVE_UPGRADE, ChocolateCannonBlockEntity.SLOT_CREATIVE_UPGRADE + 1, false)) return ItemStack.EMPTY;
            } else {
                FerreroUpgradeTier tier = cn.ism.mekck.item.FerreroUpgradeItem.getTier(stack);
                if (tier != null) {
                    int target = ChocolateCannonBlockEntity.FERRERO_SLOT_BASE + tier.ordinal();
                    if (!moveItemStackTo(stack, target, target + 1, false)) return ItemStack.EMPTY;
                } else if (ChocolateCannonBlockEntity.isFluidContainer(stack)) {
                    if (!moveItemStackTo(stack, ChocolateCannonBlockEntity.FLUID_SLOT_1, ChocolateCannonBlockEntity.FLUID_SLOT_2 + 1, false)) return ItemStack.EMPTY;
                } else if (!cn.ism.mekck.util.MekCkTransfer.moveItemStackTo(stack, slots,
                        ChocolateCannonBlockEntity.INPUT_SLOT, ChocolateCannonBlockEntity.EXTRA_SLOT + 1, false)) {
                    // 输入格与副输入格上限是 Integer.MAX_VALUE：走原版会被物品自身的 64 钳制，
                    // 已堆到 64 的那一格 shift 就再也并不进去（与陈酿机同一病灶）。
                    return ItemStack.EMPTY;
                }
            }
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }

    // ================== 数据访问 ==================
    public int getProgress() {
        int maximum = data.get(ChocolateCannonBlockEntity.DATA_PROCESS_TIME);
        return maximum == 0 ? 0 : data.get(ChocolateCannonBlockEntity.DATA_PROGRESS) * 24 / maximum;
    }

    public int getEnergy() {
        return WideDataSlot.read(data,
                ChocolateCannonBlockEntity.DATA_ENERGY,
                ChocolateCannonBlockEntity.DATA_ENERGY_HI);
    }

    public int getEnergyCapacity() {
        return ChocolateCannonBlockEntity.ENERGY_CAPACITY;
    }

    /** 从 ContainerData 同步值重建流体（客户端 FluidTank 不进网络同步，直接读会是空罐）。 */
    public FluidStack getFluid1Stack() {
        return fluidStackFromData(ChocolateCannonBlockEntity.DATA_FLUID1_AMOUNT, ChocolateCannonBlockEntity.DATA_FLUID1_ID);
    }

    public int getFluid1Capacity() {
        return ChocolateCannonBlockEntity.TANK_CAPACITY;
    }

    public FluidStack getFluid2Stack() {
        return fluidStackFromData(ChocolateCannonBlockEntity.DATA_FLUID2_AMOUNT, ChocolateCannonBlockEntity.DATA_FLUID2_ID);
    }

    public int getFluid2Capacity() {
        return ChocolateCannonBlockEntity.TANK_CAPACITY;
    }

    private FluidStack fluidStackFromData(int amountIndex, int idIndex) {
        int amount = data.get(amountIndex);
        if (amount <= 0) return FluidStack.EMPTY;
        int id = data.get(idIndex);
        net.minecraft.world.level.material.Fluid fluid = id >= 0
                ? net.minecraft.core.registries.BuiltInRegistries.FLUID.byId(id) : null;
        if (fluid == null || fluid == net.minecraft.world.level.material.Fluids.EMPTY) return FluidStack.EMPTY;
        return new FluidStack(fluid, amount);
    }

    public int getEncodedSideConfig() {
        return data.get(ChocolateCannonBlockEntity.DATA_SIDE_CONFIG);
    }

    @Override
    public SideMode getSideMode(Direction direction) {
        int encoded = getEncodedSideConfig();
        int ordinal = (encoded >> (direction.ordinal() * 4)) & 0xF;
        SideMode[] values = SideMode.values();
        return (ordinal >= 0 && ordinal < values.length) ? values[ordinal] : SideMode.NONE;
    }

    public int getRedstoneControl() {
        return data.get(ChocolateCannonBlockEntity.DATA_REDSTONE_CONTROL);
    }

    public int getTargetType() {
        return data.get(ChocolateCannonBlockEntity.DATA_TARGET_TYPE);
    }

    public int getRadius() {
        return data.get(ChocolateCannonBlockEntity.DATA_RADIUS);
    }

    public BlockPos getBlockPos() {
        return machine.getBlockPos();
    }

    // ================== IUpgradeMenu ==================
    public int getSpeedUpgradeCount() {
        return data.get(ChocolateCannonBlockEntity.DATA_SPEED_UPGRADE);
    }

    public int getEnergyUpgradeCount() {
        return data.get(ChocolateCannonBlockEntity.DATA_ENERGY_UPGRADE);
    }

    /** 升级安装读条进度（0~1）。 */
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

    public double getUpgradeInstallProgress() {
        return data.get(ChocolateCannonBlockEntity.DATA_UPGRADE_PROGRESS) / 100.0;
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
            return ChocolateCannonBlockEntity.isUsablePowerItem(stack);
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
            return getItemHandler().getSlotLimit(ChocolateCannonBlockEntity.OUTPUT_SLOT);
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

    /** 费列罗升级槽（各档独立，主界面常显）。 */
    private static final class FerreroSlot extends SlotItemHandler implements IVirtualSlot {
        private FerreroSlot(ItemStackHandler handler, int slot, int x, int y) {
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

    /** 流体容器槽（桶/储罐中的流体抽入对应流体罐）。 */
    private static final class FluidSlot extends SlotItemHandler implements IVirtualSlot {
        private FluidSlot(ItemStackHandler handler, int slot, int x, int y) {
            super(handler, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return ChocolateCannonBlockEntity.isFluidContainer(stack);
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
        private final ChocolateCannonMenu menu;
        private IGUIWindow linkedWindow;
        private int actualX, actualY;
        private ItemStack stackToRender = ItemStack.EMPTY;
        private boolean overlay;
        private String tooltip;

        private UpgradeSlot(ItemStackHandler handler, int slot, int x, int y, ChocolateCannonMenu menu) {
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

package cn.ism.mekck.menu;

import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.blockentity.IceMakerBlockEntity;
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
import cn.ism.mekck.registry.MekCkStandaloneMachines;

public final class IceMakerMenu extends AbstractContainerMenu implements ISideConfigurableMenu, IUpgradeMenu {
    // Mekanism 风格布局常量（与 IceMakerScreen 保持一致）
    public static final int INPUT_X = 40;
    public static final int INPUT_Y = 40;
    public static final int OUTPUT_X = 108;
    public static final int OUTPUT_Y = 40;
    public static final int CB_ROW_Y = 68;
    public static final int ATTACK_ROW_Y = 92;
    /** 温度控制行（攻击控制行下方）。 */
    public static final int TEMP_ROW_Y = ATTACK_ROW_Y + 20;
    public static final int INV_TOP = TEMP_ROW_Y + 24;
    public static final int IMAGE_WIDTH = 220;
    public static final int IMAGE_HEIGHT = INV_TOP + 83;

    private final IceMakerBlockEntity machine;
    private final ContainerData data;
    private boolean upgradePageActive = false;

    private final UpgradeSlot speedUpgradeSlot;
    private final UpgradeSlot energyUpgradeSlot;

    public IceMakerMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory,
                (IceMakerBlockEntity) inventory.player.level().getBlockEntity(buffer.readBlockPos()),
                new SimpleContainerData(IceMakerBlockEntity.DATA_SIZE));
    }

    public IceMakerMenu(int containerId, Inventory inventory, IceMakerBlockEntity machine, ContainerData data) {
        super(MekCkStandaloneMachines.ICE_MAKER_MENU.get(), containerId);
        this.machine = machine;
        this.data = data;

        // 容器槽顺序与 handler 索引一致：input, output, speed, energy, creative, cb1-4, power
        addSlot(new InputSlot(machine.getItems(), IceMakerBlockEntity.INPUT_SLOT, INPUT_X, INPUT_Y));
        addSlot(new OutputSlot(machine.getItems(), IceMakerBlockEntity.OUTPUT_SLOT, OUTPUT_X, OUTPUT_Y));

        // 升级槽（仅升级弹窗打开时可用）
        this.speedUpgradeSlot = new UpgradeSlot(machine.getItems(), IceMakerBlockEntity.SLOT_SPEED_UPGRADE, 40, 46, this);
        addSlot(this.speedUpgradeSlot);
        this.energyUpgradeSlot = new UpgradeSlot(machine.getItems(), IceMakerBlockEntity.SLOT_ENERGY_UPGRADE, 40, 72, this);
        addSlot(this.energyUpgradeSlot);

        // 创造升级槽 + 冷萃升级槽（主界面常显；冷萃 ①~⑤ 一排 5 格）
        addSlot(new ColdBrewSlot(machine.getItems(), IceMakerBlockEntity.SLOT_CREATIVE_UPGRADE, INPUT_X + 5 * 18 + 8, CB_ROW_Y));
        for (int i = 0; i < 5; i++) {
            addSlot(new ColdBrewSlot(machine.getItems(), IceMakerBlockEntity.CB_SLOT_1 + i, INPUT_X + i * 18, CB_ROW_Y));
        }

        // 能源槽（能量物品），Mekanism 风格位置
        addSlot(new PowerSlot(machine.getItems(), IceMakerBlockEntity.SLOT_POWER, 7, 13));

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
    public cn.ism.mekck.blockentity.IceMakerBlockEntity getMachine() {
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
        int machineSlotCount = IceMakerBlockEntity.TOTAL_SLOTS;
        if (index < machineSlotCount) {
            if (!moveItemStackTo(stack, machineSlotCount, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            if (IceMakerBlockEntity.isUsablePowerItem(stack)) {
                if (!moveItemStackTo(stack, IceMakerBlockEntity.SLOT_POWER, IceMakerBlockEntity.SLOT_POWER + 1, false)) return ItemStack.EMPTY;
            } else if (cn.ism.mekck.upgrade.UpgradeHelper.isSpeedUpgrade(stack)) {
                if (!moveItemStackTo(stack, IceMakerBlockEntity.SLOT_SPEED_UPGRADE, IceMakerBlockEntity.SLOT_SPEED_UPGRADE + 1, false)) return ItemStack.EMPTY;
            } else if (cn.ism.mekck.upgrade.UpgradeHelper.isEnergyUpgrade(stack)) {
                if (!moveItemStackTo(stack, IceMakerBlockEntity.SLOT_ENERGY_UPGRADE, IceMakerBlockEntity.SLOT_ENERGY_UPGRADE + 1, false)) return ItemStack.EMPTY;
            } else if (cn.ism.mekck.upgrade.UpgradeHelper.isCreativeUpgrade(stack)) {
                if (!moveItemStackTo(stack, IceMakerBlockEntity.SLOT_CREATIVE_UPGRADE, IceMakerBlockEntity.SLOT_CREATIVE_UPGRADE + 1, false)) return ItemStack.EMPTY;
            } else {
                ColdBrewTier cb = ColdBrewUpgradeItem.getTier(stack);
                if (cb != null) {
                    int target = switch (cb) {
                        case COLD -> IceMakerBlockEntity.CB_SLOT_1;
                        case LOW_TEMP -> IceMakerBlockEntity.CB_SLOT_2;
                        case FROST -> IceMakerBlockEntity.CB_SLOT_3;
                        case DRAGON_FROST, QUEEN -> IceMakerBlockEntity.CB_SLOT_4;
                        case HYPOTHERMIA -> IceMakerBlockEntity.CB_SLOT_5;
                    };
                    if (!moveItemStackTo(stack, target, target + 1, false)) return ItemStack.EMPTY;
                } else if (!cn.ism.mekck.util.MekCkTransfer.moveItemStackTo(stack, slots,
                        IceMakerBlockEntity.INPUT_SLOT, IceMakerBlockEntity.INPUT_SLOT + 1, false)) {
                    // 输入格上限是 Integer.MAX_VALUE：走原版会被物品自身的 64 钳制，
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
        int maximum = data.get(IceMakerBlockEntity.DATA_PROCESS_TIME);
        return maximum == 0 ? 0 : data.get(IceMakerBlockEntity.DATA_PROGRESS) * 24 / maximum;
    }

    public int getEnergy() {
        return WideDataSlot.read(data,
                IceMakerBlockEntity.DATA_ENERGY,
                IceMakerBlockEntity.DATA_ENERGY_HI);
    }

    public int getEnergyCapacity() {
        return IceMakerBlockEntity.ENERGY_CAPACITY;
    }

    /** 从 ContainerData 同步值重建流体（客户端 FluidTank 不进网络同步，直接读会是空罐）。 */
    public FluidStack getWaterStack() {
        int amount = WideDataSlot.read(data,
                IceMakerBlockEntity.DATA_WATER_AMOUNT,
                IceMakerBlockEntity.DATA_WATER_AMOUNT_HI);
        if (amount <= 0) return FluidStack.EMPTY;
        int id = data.get(IceMakerBlockEntity.DATA_WATER_FLUID_ID);
        net.minecraft.world.level.material.Fluid fluid = id >= 0
                ? net.minecraft.core.registries.BuiltInRegistries.FLUID.byId(id) : null;
        if (fluid == null || fluid == net.minecraft.world.level.material.Fluids.EMPTY) return FluidStack.EMPTY;
        return new FluidStack(fluid, amount);
    }

    public int getWaterCapacity() {
        return machine.getWaterTank().getCapacity();
    }

    public int getEncodedSideConfig() {
        // 24-bit 侧配拆两槽，裸读低槽会丢 WEST/EAST 两面，见 WideDataSlot。
        return WideDataSlot.read(data,
                IceMakerBlockEntity.DATA_SIDE_CONFIG,
                IceMakerBlockEntity.DATA_SIDE_CONFIG_HI);
    }

    @Override
    public SideMode getSideMode(Direction direction) {
        int encoded = getEncodedSideConfig();
        int ordinal = (encoded >> (direction.ordinal() * 4)) & 0xF;
        SideMode[] values = SideMode.values();
        return (ordinal >= 0 && ordinal < values.length) ? values[ordinal] : SideMode.NONE;
    }

    public int getRedstoneControl() {
        return data.get(IceMakerBlockEntity.DATA_REDSTONE_CONTROL);
    }

    public int getTargetType() {
        return data.get(IceMakerBlockEntity.DATA_TARGET_TYPE);
    }

    public int getRadius() {
        return data.get(IceMakerBlockEntity.DATA_RADIUS);
    }

    /** 当前机身温度（单位 0.01 ℃）。 */
    public int getCurrentTemperature() {
        return data.get(IceMakerBlockEntity.DATA_CURRENT_TEMP);
    }

    /** 设定温度（单位 0.01 ℃）。 */
    public int getTargetTemperature() {
        return data.get(IceMakerBlockEntity.DATA_TARGET_TEMP);
    }

    /** 设定温度功能是否开启。 */
    public boolean isTemperatureControlEnabled() {
        return data.get(IceMakerBlockEntity.DATA_TEMP_CONTROL) != 0;
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

    public boolean supportsSpeedUpgrade() {
        return false;
    }

    public int getSpeedUpgradeCount() {
        return data.get(IceMakerBlockEntity.DATA_SPEED_UPGRADE);
    }

    public int getEnergyUpgradeCount() {
        return data.get(IceMakerBlockEntity.DATA_ENERGY_UPGRADE);
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
            return IceMakerBlockEntity.isUsablePowerItem(stack);
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
        /** 升级窗口未打开时把渲染位置移出屏幕：主屏便既不绘制、也命中不到它
         *  （Mek 的 VirtualSlotContainerScreen 渲染与 isMouseOverSlot 都走 getActualX/Y）。
         *  刻意<b>不改 isActive()</b> —— 那是槽的语义标志（服务端 mayPlace/转移逻辑依赖它），
         *  为了纯视觉的布局问题去改写它风险过大。 */
        private static final int HIDDEN_POS = -9999;

        private final IceMakerMenu menu;
        private IGUIWindow linkedWindow;
        private int actualX, actualY;
        private ItemStack stackToRender = ItemStack.EMPTY;
        private boolean overlay;
        private String tooltip;

        private UpgradeSlot(ItemStackHandler handler, int slot, int x, int y, IceMakerMenu menu) {
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

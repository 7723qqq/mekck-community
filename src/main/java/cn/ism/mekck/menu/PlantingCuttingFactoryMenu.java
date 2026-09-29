package cn.ism.mekck.menu;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.blockentity.PlantingCuttingFactoryBlockEntity;
import cn.ism.mekck.blockentity.PlantingCuttingStationBlockEntity;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.util.MekCkTransfer;
import mekanism.api.chemical.gas.IGasTank;
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

import java.util.function.IntSupplier;

public final class PlantingCuttingFactoryMenu extends AbstractContainerMenu implements ISideConfigurableMenu, IUpgradeMenu {
    private final PlantingCuttingFactoryBlockEntity machine;
    private final ContainerData data;
    private final CuttingMachineFactoryTier tier;
    private final boolean hasStackUpgrade;
    private boolean upgradePageActive = false;
    /**
     * 生长方块格在**菜单 slots 列表**里的下标（注意：工厂的菜单顺序与 BE 槽位下标不完全一致 ——
     * 菜单没有创造升级槽，所以这里单独记一份给界面用）。
     */
    private final int growthSlotMenuIndex;

    private final UpgradeSlot speedUpgradeSlot;
    private final UpgradeSlot energyUpgradeSlot;
    private final UpgradeSlot stackUpgradeSlot;
    private final UpgradeSlot gasUpgradeSlot;

    public PlantingCuttingFactoryMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory,
                (PlantingCuttingFactoryBlockEntity) inventory.player.level().getBlockEntity(buffer.readBlockPos()));
    }

    public PlantingCuttingFactoryMenu(int containerId, Inventory inventory, PlantingCuttingFactoryBlockEntity machine) {
        this(containerId, inventory, machine, machine.getData());
    }

    public PlantingCuttingFactoryMenu(int containerId, Inventory inventory, PlantingCuttingFactoryBlockEntity machine, ContainerData data) {
        super(UniversalCuttingMachine.PLANTING_CUTTING_FACTORY_MENU.get(), containerId);
        this.machine = machine;
        this.data = data;
        this.tier = machine.getTier();
        this.hasStackUpgrade = machine.hasStackUpgradeSlot();

        int inputSlots = tier.processes;
        int cols = (int) Math.ceil(Math.sqrt(inputSlots));
        int rows = (int) Math.ceil((double) inputSlots / cols);

        // Input slots (tightly packed grid, 18px spacing like output slots)
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

        // Gas container (nutrient) slot — 参考 mekmm ultimate_planting_factory 位置(左下角)。
        // 奇点创世等级无需营养液，不放置该槽。
        boolean hasNutrient = tier != CuttingMachineFactoryTier.SINGULARITY;
        int nutrientSlotIndex = 2 * inputSlots;
        if (hasNutrient) {
            addSlot(new NutrientSlot(machine.getItems(), nutrientSlotIndex, 7, 77));
        }

        // Upgrade slots (at normal positions, visible only in upgrade page)
        int base = 2 * inputSlots + (hasNutrient ? 1 : 0);
        int speedUpgradeSlot = base;
        int energyUpgradeSlot = base + 1;
        int stackUpgradeSlot = base + 2;
        this.speedUpgradeSlot = new UpgradeSlot(machine.getItems(), speedUpgradeSlot, 40, 46, this);
        addSlot(this.speedUpgradeSlot);
        this.energyUpgradeSlot = new UpgradeSlot(machine.getItems(), energyUpgradeSlot, 40, 72, this);
        addSlot(this.energyUpgradeSlot);
        if (hasStackUpgrade) {
            this.stackUpgradeSlot = new UpgradeSlot(machine.getItems(), stackUpgradeSlot, 130, 72, this);
            addSlot(this.stackUpgradeSlot);
        } else {
            this.stackUpgradeSlot = null;
        }

        // Power slot (energy items: energy cube / tablet / redstone), next to the energy bar
        int imageWidth = 38 + cols * 18 + gapBetween + cols * 18 + 20;
        addSlot(new PowerSlot(machine, machine.getPowerSlot(), 7, 13, this));

        // Gas upgrade slot (only for tiers before CRYSTAL_MATRIX; reduces nutrient consumption by 90%)
        int gasSlotIndex = machine.getGasUpgradeSlot();
        if (gasSlotIndex >= 0) {
            this.gasUpgradeSlot = new UpgradeSlot(machine.getItems(), gasSlotIndex, 40, 98, this) {
                @Override
                public boolean mayPickup(Player player) {
                    return true;
                }
            };
            addSlot(this.gasUpgradeSlot);
        } else {
            this.gasUpgradeSlot = null;
        }

        // 生长方块格：所有并行格共用 1 格；只有神秘农业种子受它约束。
        // 追加在机器槽位末尾（不动任何已有下标）；槽位句柄直接绑 BE 的 growthSlot。
        this.growthSlotMenuIndex = slots.size();
        addSlot(new GrowthSlot(machine.getItems(), machine.getGrowthSlot(), 7, 41));

        // Player inventory slots (centered)
        int extraHeight = Math.max(0, (rows - 2) * 18);
        int invTop = 101 + extraHeight + 22; // +22 for nutrient slot row
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

    /** 机器实例（客户端也持有，槽位内容由菜单同步 ⇒ 可用于「本机下单」列表）。 */
    public cn.ism.mekck.blockentity.PlantingCuttingFactoryBlockEntity getMachine() {
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
        int powerSlot = machine.getPowerSlot();
        int totalMachineSlots = machine.getItems().getSlots();
        int speedUpgradeSlot = machine.getSpeedUpgradeSlot();
        int energyUpgradeSlot = machine.getEnergyUpgradeSlot();
        int stackUpgradeSlot = machine.getStackUpgradeSlot();
        int gasUpgradeSlot = machine.getGasUpgradeSlot();

        if (index < totalMachineSlots) {
            if (!moveItemStackTo(stack, totalMachineSlots, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (PlantingCuttingFactoryBlockEntity.isUsablePowerItem(stack)) {
                if (!moveItemStackTo(stack, powerSlot, powerSlot + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (PlantingCuttingFactoryBlockEntity.isSpeedUpgrade(stack)) {
                if (!moveItemStackTo(stack, speedUpgradeSlot, speedUpgradeSlot + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (PlantingCuttingFactoryBlockEntity.isEnergyUpgrade(stack)) {
                if (!moveItemStackTo(stack, energyUpgradeSlot, energyUpgradeSlot + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (hasStackUpgrade && PlantingCuttingFactoryBlockEntity.isStackUpgrade(stack)) {
                if (!moveItemStackTo(stack, stackUpgradeSlot, stackUpgradeSlot + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (gasUpgradeSlot >= 0 && PlantingCuttingFactoryBlockEntity.isGasUpgrade(stack)) {
                if (!moveItemStackTo(stack, gasUpgradeSlot, gasUpgradeSlot + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!MekCkTransfer.moveItemStackTo(stack, slots, 0, tier.processes, false)) {
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

    /** 生长方块格在菜单 slots 列表里的下标（界面用来挂 GuiVirtualSlot）。 */
    public int getGrowthSlotMenuIndex() {
        return growthSlotMenuIndex;
    }

    /** 生长方块格状态（0 = 无需/已满足，1 = 缺方块，2 = 等级不足）。 */
    public int getGrowthStatus() {
        return data.get(PlantingCuttingFactoryBlockEntity.DATA_GROWTH_STATUS);
    }

    /** 并行格里最严的档位要求（-1 = 无要求）。 */
    public int getGrowthTierIndex() {
        return data.get(PlantingCuttingFactoryBlockEntity.DATA_GROWTH_TIER);
    }

    /** 要求的生长方块档位名（如 {@code inferium}）；无要求或认不出时为空串。 */
    public String getGrowthTierName() {
        int index = getGrowthTierIndex();
        String[] names = PlantingCuttingStationBlockEntity.GROWTH_TIER_NAMES;
        return index >= 0 && index < names.length ? names[index] : "";
    }

    public int getProgress() {
        int progress = data.get(PlantingCuttingFactoryBlockEntity.DATA_PROGRESS);
        int max = data.get(PlantingCuttingFactoryBlockEntity.DATA_PROCESS_TIME);
        return max == 0 ? 0 : progress * 24 / max;
    }

    public int getEnergy() {
        return data.get(PlantingCuttingFactoryBlockEntity.DATA_ENERGY);
    }

    public int getEnergyCapacity() {
        return data.get(PlantingCuttingFactoryBlockEntity.DATA_ENERGY_CAPACITY);
    }

    public int getEncodedSideConfig() {
        return data.get(PlantingCuttingFactoryBlockEntity.DATA_SIDE_CONFIG);
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
        return data.get(PlantingCuttingFactoryBlockEntity.DATA_SPEED_UPGRADE);
    }

    public int getEnergyUpgradeCount() {
        return data.get(PlantingCuttingFactoryBlockEntity.DATA_ENERGY_UPGRADE);
    }

    public int getStackUpgradeCount() {
        return data.get(PlantingCuttingFactoryBlockEntity.DATA_STACK_UPGRADE);
    }

    @Override
    public Slot getGasUpgradeSlot() {
        return gasUpgradeSlot;
    }

    @Override
    public int getGasUpgradeCount() {
        return gasUpgradeSlot != null ? gasUpgradeSlot.getItem().getCount() : 0;
    }

    public int getNutrientCount() {
        return data.get(PlantingCuttingFactoryBlockEntity.DATA_NUTRIENT);
    }

    public IGasTank getGasTank() {
        return machine.getGasTank();
    }

    public boolean getAutoDistribute() {
        return data.get(PlantingCuttingFactoryBlockEntity.DATA_AUTO_DISTRIBUTE) != 0;
    }

    public int getRedstoneControl() {
        return data.get(PlantingCuttingFactoryBlockEntity.DATA_REDSTONE_CONTROL);
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

    /** 气体侧面配置（营养液注入方向）。 */
    @Override
    public SideMode getGasSideMode(Direction direction) {
        return machine.getGasSideMode(direction);
    }

    public CuttingMachineFactoryTier getTier() {
        return tier;
    }

    public BlockPos getBlockPos() {
        return machine.getBlockPos();
    }

    public boolean hasStackUpgrade() {
        return hasStackUpgrade;
    }

    @Override
    public int getSpeedUpgradeMax() {
        return MekckConfig.getFactorySpeedUpgradeMax(tier);
    }

    @Override
    public int getEnergyUpgradeMax() {
        return MekckConfig.getFactoryEnergyUpgradeMax(tier);
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

    private static final class PowerSlot extends SlotItemHandler implements IVirtualSlot {
        private PowerSlot(PlantingCuttingFactoryBlockEntity machine, int slot, int x, int y, PlantingCuttingFactoryMenu menu) {
            super(machine.getItems(), slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return PlantingCuttingFactoryBlockEntity.isUsablePowerItem(stack);
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
        private OutputSlot(PlantingCuttingFactoryBlockEntity machine, int slot, int x, int y) {
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

    private static final class NutrientSlot extends SlotItemHandler implements IVirtualSlot {
        private NutrientSlot(ItemStackHandler handler, int slot, int x, int y) {
            super(handler, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return getItemHandler().isItemValid(getSlotIndex(), stack);
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

    /** 生长方块格：什么都放得下（合不合格由配方白名单判，见 BE 的 updateGrowthStatus）。 */
    private static final class GrowthSlot extends SlotItemHandler implements IVirtualSlot {
        private GrowthSlot(ItemStackHandler handler, int slot, int x, int y) {
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

    private static class UpgradeSlot extends SlotItemHandler implements IVirtualSlot {
        private final PlantingCuttingFactoryMenu menu;
        private IGUIWindow linkedWindow;
        private int actualX, actualY;
        private ItemStack stackToRender = ItemStack.EMPTY;
        private boolean overlay;
        private String tooltip;

        private UpgradeSlot(ItemStackHandler handler, int slot, int x, int y, PlantingCuttingFactoryMenu menu) {
            super(handler, slot, x, y);
            this.menu = menu;
            this.actualX = x;
            this.actualY = y;
        }

        @Override
        public boolean mayPickup(Player player) {
            // 升级槽不允许取出（旧的营养液气体升级已移除）
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
}
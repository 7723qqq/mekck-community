package cn.ism.mekck.menu;

import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.blockentity.PlantingCuttingStationBlockEntity;
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

public final class PlantingCuttingStationMenu extends AbstractContainerMenu implements ISideConfigurableMenu, IUpgradeMenu {
    private static final int MACHINE_SLOT_COUNT = 9;
    private final PlantingCuttingStationBlockEntity machine;
    private final ContainerData data;
    private boolean upgradePageActive = false;
    private final UpgradeSlot speedUpgradeSlot;
    private final UpgradeSlot energyUpgradeSlot;
    private final UpgradeSlot gasUpgradeSlot;

    public PlantingCuttingStationMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory,
                (PlantingCuttingStationBlockEntity) inventory.player.level().getBlockEntity(buffer.readBlockPos()),
                new SimpleContainerData(PlantingCuttingStationBlockEntity.DATA_SIZE));
    }

    public PlantingCuttingStationMenu(int containerId, Inventory inventory, PlantingCuttingStationBlockEntity machine, ContainerData data) {
        super(UniversalCuttingMachine.PLANTING_CUTTING_STATION_MENU.get(), containerId);
        this.machine = machine;
        this.data = data;

        // Input slot at (56, 17)
        addSlot(new InputSlot(machine.getItems(), PlantingCuttingStationBlockEntity.INPUT_SLOT, 56, 17));
        // Nutrient slot at (56, 53)
        addSlot(new NutrientSlot(machine.getItems(), PlantingCuttingStationBlockEntity.NUTRIENT_SLOT, 56, 53));
        // Output slot at (116, 35)
        addSlot(new OutputSlot(machine, PlantingCuttingStationBlockEntity.OUTPUT_SLOT, 116, 35));

        // Upgrade slots: speed (8, 17), energy (8, 53), creative (152, 35), gas (152, 17)
        this.speedUpgradeSlot = new UpgradeSlot(machine.getItems(), PlantingCuttingStationBlockEntity.SLOT_SPEED_UPGRADE, 8, 17, this);
        addSlot(this.speedUpgradeSlot);
        this.energyUpgradeSlot = new UpgradeSlot(machine.getItems(), PlantingCuttingStationBlockEntity.SLOT_ENERGY_UPGRADE, 8, 53, this);
        addSlot(this.energyUpgradeSlot);
        addSlot(new UpgradeSlot(machine.getItems(), PlantingCuttingStationBlockEntity.SLOT_CREATIVE_UPGRADE, 152, 35, this));
        this.gasUpgradeSlot = new UpgradeSlot(machine.getItems(), PlantingCuttingStationBlockEntity.SLOT_GAS_UPGRADE, 152, 17, this);
        addSlot(this.gasUpgradeSlot);

        // Power slot (energy items: energy cube / tablet / redstone), next to the energy bar
        addSlot(new PowerSlot(machine, machine.getPowerSlot(), 7, 13, this));

        // 生长方块格（种子与营养液之间）：所有配方共用 1 格；只有神秘农业种子受它约束
        addSlot(new GrowthSlot(machine.getItems(), PlantingCuttingStationBlockEntity.GROWTH_SLOT, 56, 35));

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, 8 + column * 18, 84 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, 142));
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
        if (index < MACHINE_SLOT_COUNT) {
            if (!moveItemStackTo(stack, MACHINE_SLOT_COUNT, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (PlantingCuttingStationBlockEntity.isUsablePowerItem(stack)) {
                if (!moveItemStackTo(stack, machine.getPowerSlot(), machine.getPowerSlot() + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (PlantingCuttingStationBlockEntity.isSpeedUpgrade(stack)) {
                if (!moveItemStackTo(stack, PlantingCuttingStationBlockEntity.SLOT_SPEED_UPGRADE,
                        PlantingCuttingStationBlockEntity.SLOT_SPEED_UPGRADE + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (PlantingCuttingStationBlockEntity.isEnergyUpgrade(stack)) {
                if (!moveItemStackTo(stack, PlantingCuttingStationBlockEntity.SLOT_ENERGY_UPGRADE,
                        PlantingCuttingStationBlockEntity.SLOT_ENERGY_UPGRADE + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (PlantingCuttingStationBlockEntity.isCreativeUpgrade(stack)) {
                if (!moveItemStackTo(stack, PlantingCuttingStationBlockEntity.SLOT_CREATIVE_UPGRADE,
                        PlantingCuttingStationBlockEntity.SLOT_CREATIVE_UPGRADE + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (PlantingCuttingStationBlockEntity.isGasUpgrade(stack)) {
                if (!moveItemStackTo(stack, PlantingCuttingStationBlockEntity.SLOT_GAS_UPGRADE,
                        PlantingCuttingStationBlockEntity.SLOT_GAS_UPGRADE + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (machine.getLevel() != null
                    && cn.ism.mekck.util.RecipeInputMatcher.matchesPlantingSeed(machine.getLevel(), stack)) {
                // 种子 → 输入格
                if (!MekCkTransfer.moveItemStackTo(stack, slots, PlantingCuttingStationBlockEntity.INPUT_SLOT,
                        PlantingCuttingStationBlockEntity.INPUT_SLOT + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (stack.getItem() instanceof net.minecraft.world.item.BlockItem) {
                // 方块（耕地等）→ 生长方块格；放不进再退回营养液格
                if (!moveItemStackTo(stack, PlantingCuttingStationBlockEntity.GROWTH_SLOT,
                        PlantingCuttingStationBlockEntity.GROWTH_SLOT + 1, false)
                        && !moveItemStackTo(stack, PlantingCuttingStationBlockEntity.NUTRIENT_SLOT,
                        PlantingCuttingStationBlockEntity.NUTRIENT_SLOT + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!MekCkTransfer.moveItemStackTo(stack, slots, PlantingCuttingStationBlockEntity.INPUT_SLOT,
                    PlantingCuttingStationBlockEntity.INPUT_SLOT + 1, false)) {
                // Try nutrient slot if input can't accept
                if (!moveItemStackTo(stack, PlantingCuttingStationBlockEntity.NUTRIENT_SLOT,
                        PlantingCuttingStationBlockEntity.NUTRIENT_SLOT + 1, false)) {
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

    public int getProgress() {
        int maximum = data.get(1);
        return maximum == 0 ? 0 : data.get(0) * 24 / maximum;
    }

    public int getEnergy() {
        return data.get(2);
    }

    public int getEnergyCapacity() {
        return data.get(3);
    }

    /**
     * 已装的创造升级数量 —— 对应 {@code PlantingCuttingStationBlockEntity.DATA_CREATIVE_UPGRADE = 7}。
     *
     * <p><b>本菜单此前没有这个 getter</b>，而服务端那一格一直在同步（值是
     * {@code hasCreativeUpgrade() ? 1 : 0}，即恒为 0 或 1）。于是屏幕只能去猜：
     * {@code getEnergyCapacity() > ENERGY_CAPACITY}。</p>
     *
     * <p>那条猜法<b>必然失败</b>：{@code ContainerData} 经
     * {@code ClientboundContainerSetDataPacket} 传输时对每个值用 {@code writeShort} ——
     * <b>16 位有符号</b>，上限 32767。{@code getMaxEnergyStored()} 的常规值就是
     * {@code 100_000}，到客户端会变成 {@code 100000 - 65536 = 34464}，
     * 于是 {@code 34464 > 100000} 恒假。</p>
     *
     * <p>本 getter 读的那一格值域是 {@code {0, 1}}，<b>不可能溢出</b> ——
     * 这才是「有没有装创造升级」的正确判据。与 {@code client/MekCkUpgradeType} 里
     * {@code case CREATIVE -> menu.getCreativeUpgradeCount()} 的既有约定一致。</p>
     */
    public int getCreativeUpgradeCount() {
        return data.get(PlantingCuttingStationBlockEntity.DATA_CREATIVE_UPGRADE);
    }

    public int getEncodedSideConfig() {
        return data.get(4);
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

    public boolean hasNutrient() {
        return machine.hasNutrient();
    }

    /** 生长方块格状态（0 = 无需/已满足，1 = 缺方块，2 = 等级不足）。 */
    public int getGrowthStatus() {
        return data.get(PlantingCuttingStationBlockEntity.DATA_GROWTH_STATUS);
    }

    /** 要求的生长方块档位下标（-1 = 无要求）。 */
    public int getGrowthTierIndex() {
        return data.get(PlantingCuttingStationBlockEntity.DATA_GROWTH_TIER);
    }

    /** 要求的生长方块档位名（如 {@code inferium}）；无要求或认不出时为空串。 */
    public String getGrowthTierName() {
        int index = getGrowthTierIndex();
        String[] names = PlantingCuttingStationBlockEntity.GROWTH_TIER_NAMES;
        return index >= 0 && index < names.length ? names[index] : "";
    }

    public int getRedstoneControl() {
        return data.get(PlantingCuttingStationBlockEntity.DATA_REDSTONE_CONTROL);
    }

    public boolean hasGasUpgrade() {
        return true;
    }

    public int getGasUpgradeCount() {
        int idx = PlantingCuttingStationBlockEntity.SLOT_GAS_UPGRADE;
        if (idx >= 0 && idx < machine.getItems().getSlots()
                && !machine.getItems().getStackInSlot(idx).isEmpty()) {
            return 1;
        }
        return 0;
    }

    @Override
    public Slot getSpeedUpgradeSlot() {
        return speedUpgradeSlot;
    }

    @Override
    public Slot getEnergyUpgradeSlot() {
        return energyUpgradeSlot;
    }

    public Slot getGasUpgradeSlot() {
        return gasUpgradeSlot;
    }

    public void setUpgradePageActive(boolean active) {
        this.upgradePageActive = active;
    }

    public boolean isUpgradePageActive() {
        return this.upgradePageActive;
    }

    public BlockPos getBlockPos() {
        return machine.getBlockPos();
    }

    private static final class PowerSlot extends SlotItemHandler implements IVirtualSlot {
        private PowerSlot(PlantingCuttingStationBlockEntity machine, int slot, int x, int y, PlantingCuttingStationMenu menu) {
            super(machine.getItems(), slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return PlantingCuttingStationBlockEntity.isUsablePowerItem(stack);
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

    private static final class NutrientSlot extends SlotItemHandler implements IVirtualSlot {
        private NutrientSlot(ItemStackHandler handler, int slot, int x, int y) {
            super(handler, slot, x, y);
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

    /** 生长方块格：什么都放得下（合不合格由配方白名单判，见 BE 的 updateGrowthStatus）。 */
    private static final class GrowthSlot extends SlotItemHandler implements IVirtualSlot {
        private GrowthSlot(ItemStackHandler handler, int slot, int x, int y) {
            super(handler, slot, x, y);
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return 1;
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
        private OutputSlot(PlantingCuttingStationBlockEntity machine, int slot, int x, int y) {
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

    private static final class UpgradeSlot extends SlotItemHandler {
        private final PlantingCuttingStationMenu menu;

        private UpgradeSlot(ItemStackHandler handler, int slot, int x, int y, PlantingCuttingStationMenu menu) {
            super(handler, slot, x, y);
            this.menu = menu;
        }

        @Override
        public boolean mayPickup(Player player) {
            // Allow gas upgrades to be removed; other upgrades stay locked
            return PlantingCuttingStationBlockEntity.isGasUpgrade(getItem());
        }

        @Override
        public boolean isActive() {
            return menu.isUpgradePageActive();
        }
    }
}
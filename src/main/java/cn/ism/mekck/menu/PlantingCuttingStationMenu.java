package cn.ism.mekck.menu;

import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.blockentity.PlantingCuttingStationBlockEntity;
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

public final class PlantingCuttingStationMenu extends AbstractContainerMenu implements ISideConfigurableMenu, IUpgradeMenu {
    private static final int MACHINE_SLOT_COUNT = 9;
    private final PlantingCuttingStationBlockEntity machine;
    private final ContainerData data;
    private boolean upgradePageActive = false;
    private final UpgradeSlot speedUpgradeSlot;
    private final UpgradeSlot energyUpgradeSlot;
    private final UpgradeSlot gasUpgradeSlot;

    /**
     * 客户端构造器：方块在 OpenScreen 到达前被破坏/替换、或区块被卸载时，
     * {@code getBlockEntity} 返回 null。旧写法直接强转后交给主构造器，
     * 主构造器随即读 {@code machine.getItems()} ⇒ NPE 崩客户端。
     * 这里显式判空（{@code instanceof} 同时挡掉类型不符），null 时构造「空菜单」：
     * 槽位数量与坐标照旧（客户端/服务端的槽位契约不能变），所有读取走
     * {@code machine == null} 的兜底分支。
     */
    public PlantingCuttingStationMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory,
                inventory.player.level().getBlockEntity(buffer.readBlockPos())
                        instanceof PlantingCuttingStationBlockEntity machine ? machine : null,
                new SimpleContainerData(PlantingCuttingStationBlockEntity.DATA_SIZE));
    }

    public PlantingCuttingStationMenu(int containerId, Inventory inventory, PlantingCuttingStationBlockEntity machine, ContainerData data) {
        super(MekCkFactories.PLANTING_CUTTING_STATION_MENU.get(), containerId);
        this.machine = machine;
        this.data = data;
        // 空菜单（客户端 BE 缺失）用等长的空 handler 兜底：槽位数量与坐标必须照旧，
        // 否则客户端与服务端的槽位契约不一致；读取路径全部走 machine == null 分支。
        ItemStackHandler items = machine == null
                ? new ItemStackHandler(PlantingCuttingStationBlockEntity.TOTAL_SLOTS)
                : machine.getItems();

        // Input slot at (56, 17)
        addSlot(new InputSlot(items, PlantingCuttingStationBlockEntity.INPUT_SLOT, 56, 17));
        // Nutrient slot at (56, 53)
        addSlot(new NutrientSlot(items, PlantingCuttingStationBlockEntity.NUTRIENT_SLOT, 56, 53));
        // Output slot at (116, 35)
        addSlot(new OutputSlot(items, PlantingCuttingStationBlockEntity.OUTPUT_SLOT, 116, 35));

        // Upgrade slots: speed (8, 17), energy (8, 53), creative (152, 35), gas (152, 17)
        this.speedUpgradeSlot = new UpgradeSlot(items, PlantingCuttingStationBlockEntity.SLOT_SPEED_UPGRADE, 8, 17, this);
        addSlot(this.speedUpgradeSlot);
        this.energyUpgradeSlot = new UpgradeSlot(items, PlantingCuttingStationBlockEntity.SLOT_ENERGY_UPGRADE, 8, 53, this);
        addSlot(this.energyUpgradeSlot);
        addSlot(new UpgradeSlot(items, PlantingCuttingStationBlockEntity.SLOT_CREATIVE_UPGRADE, 152, 35, this));
        this.gasUpgradeSlot = new UpgradeSlot(items, PlantingCuttingStationBlockEntity.SLOT_GAS_UPGRADE, 152, 17, this);
        addSlot(this.gasUpgradeSlot);

        // Power slot (energy items: energy cube / tablet / redstone), next to the energy bar
        // 空菜单：能源槽下标取 handler 常量（与 getPowerSlot() 同值），槽位数量与坐标照旧。
        int powerSlot = machine == null ? PlantingCuttingStationBlockEntity.SLOT_POWER : machine.getPowerSlot();
        addSlot(new PowerSlot(items, powerSlot, 7, 13, this));

        // 生长方块格（种子与营养液之间）：所有配方共用 1 格；只有神秘农业种子受它约束
        addSlot(new GrowthSlot(items, PlantingCuttingStationBlockEntity.GROWTH_SLOT, 56, 35));

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
        // 空菜单一律视为失效：服务端据此关闭窗口，客户端也不再接受交互。
        if (machine == null) {
            return false;
        }
        Level level = player.level();
        return level.getBlockEntity(machine.getBlockPos()) == machine
                && player.distanceToSqr(machine.getBlockPos().getX() + 0.5D, machine.getBlockPos().getY() + 0.5D,
                machine.getBlockPos().getZ() + 0.5D) <= 64.0D;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // 空菜单：槽位读的是空 handler，但 getPowerSlot()/getLevel() 仍会解引用 machine。
        if (machine == null) {
            return ItemStack.EMPTY;
        }
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
                    && cn.ism.mekck.recipe.RecipeInputMatcher.matchesPlantingSeed(machine.getLevel(), stack)) {
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
        return WideDataSlot.read(data,
                PlantingCuttingStationBlockEntity.DATA_ENERGY,
                PlantingCuttingStationBlockEntity.DATA_ENERGY_HI);
    }

    /**
     * 能量上限 —— 直接取 BE 上的 static final 常量，<b>不走 {@code ContainerData}</b>。
     *
     * <p>此前返回 {@code data.get(3)}（即旧的 {@code DATA_ENERGY_CAPACITY} 槽），
     * 而容量是客户端已知的常量，那个槽纯冗余，且同样会被 16 位通道截断
     * （10 万 → -31072，能源条比值变负、条纹为空）。该槽现已改作
     * {@link PlantingCuttingStationBlockEntity#DATA_ENERGY_HI}。</p>
     */
    public int getEnergyCapacity() {
        return PlantingCuttingStationBlockEntity.ENERGY_CAPACITY;
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
     * <b>16 位有符号</b>，上限 32767。容量常量是 {@code ENERGY_CAPACITY = 100_000}，
     * 到客户端会变成 <b>-31072</b>（0x186A0 取低 16 位得 0x86A0，
     * {@code readShort()} 返回有符号 short 后符号扩展），于是
     * {@code -31072 > 100000} 恒假。</p>
     *
     * <p>⚠️ 别被「{@code 100000 - 65536 = 34464}」那种说法骗了：那只是<b>无符号</b>解读；
     * 原版包里是 {@code this.value = p_178825_.readShort();}，会符号扩展。
     * 该值由 {@code TestWideDataSlot#channelIsSixteenBitSigned} 拿真实包往返实测钉住。</p>
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
        return machine == null ? 0 : machine.getSpeedUpgradeCount();
    }

    public int getEnergyUpgradeCount() {
        return machine == null ? 0 : machine.getEnergyUpgradeCount();
    }

    public boolean hasNutrient() {
        return machine != null && machine.hasNutrient();
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
        if (machine == null) {
            return 0;
        }
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

    /** 机器实例（客户端也持有；空菜单为 null，屏幕侧据此兜底，同 ElectricGrindingMachineMenu）。 */
    public PlantingCuttingStationBlockEntity getMachine() {
        return machine;
    }

    public BlockPos getBlockPos() {
        // 空菜单没有真实坐标，返回 ZERO 而不是 NPE（同 GrillMenu 的 tile == null 写法）。
        return machine == null ? BlockPos.ZERO : machine.getBlockPos();
    }

    private static final class PowerSlot extends SlotItemHandler implements IVirtualSlot {
        private PowerSlot(ItemStackHandler handler, int slot, int x, int y, PlantingCuttingStationMenu menu) {
            super(handler, slot, x, y);
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
        private OutputSlot(ItemStackHandler handler, int slot, int x, int y) {
            super(handler, slot, x, y);
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

package cn.ism.mekck.menu;

import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.blockentity.SmartCookingPotBlockEntity;
import cn.ism.mekck.util.MekCkTransfer;
import cn.ism.mekck.util.WideDataSlot;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.function.IntSupplier;
import cn.ism.mekck.registry.MekCkStandaloneMachines;

public final class SmartCookingPotMenu extends AbstractContainerMenu implements ISideConfigurableMenu, IUpgradeMenu {
    /**
     * 机器槽在 {@code slots} 里的数量：6 输入 + 产物 + 返还 + 速度 + 能量
     * + {@code STORAGE_SLOT_COUNT} 存储 + 能源 = <b>11 + STORAGE_SLOT_COUNT</b>。
     *
     * <p><b>原来的 {@code 10 + STORAGE_SLOT_COUNT} 少算了能源槽</b>，于是菜单下标 91 的能源槽
     * 落进 {@code quickMoveStack} 的 else 分支（当成玩家槽）；而 else 里指向能源槽的区间用的是
     * handler 常量 {@code SLOT_POWER = 92}（= 本菜单玩家背包第 0 格的下标）⇒ 点那一格且持能量
     * 物品时区间自指 → 原版 {@code moveItemStackTo} 的合并分支自我合并 → 数量翻倍。</p>
     */
    private static final int MACHINE_SLOT_COUNT = 11 + SmartCookingPotBlockEntity.STORAGE_SLOT_COUNT;
    private final SmartCookingPotBlockEntity machine;
    private final ContainerData data;
    private boolean upgradePageActive = false;

    private final UpgradeSlot speedUpgradeSlot;
    private final UpgradeSlot energyUpgradeSlot;

    /**
     * 能源槽的<b>菜单下标</b>（= 91），不是 handler 下标（= 92）。
     *
     * <p>handler 的 91 号「创造升级槽」在本菜单没有 addSlot，所以 91 之后所有 handler 下标
     * 都比菜单下标大 1；拿 {@code SLOT_POWER} 当菜单下标会指向玩家背包第 0 格。</p>
     */
    private final int powerSlotIndex;

    public SmartCookingPotMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory,
                (SmartCookingPotBlockEntity) inventory.player.level().getBlockEntity(buffer.readBlockPos()),
                new SimpleContainerData(SmartCookingPotBlockEntity.DATA_SIZE));
    }

    public SmartCookingPotMenu(int containerId, Inventory inventory, SmartCookingPotBlockEntity machine, ContainerData data) {
        super(MekCkStandaloneMachines.COOKING_POT_MENU.get(), containerId);
        this.machine = machine;
        this.data = data;

        // 6 input slots: 3 columns x 2 rows, starting from (38, 41), 18px spacing
        // Row 0: (38, 41), (56, 41), (74, 41)
        addSlot(new InputSlot(machine, SmartCookingPotBlockEntity.INPUT_SLOT_START + 0, 38, 41));
        addSlot(new InputSlot(machine, SmartCookingPotBlockEntity.INPUT_SLOT_START + 1, 56, 41));
        addSlot(new InputSlot(machine, SmartCookingPotBlockEntity.INPUT_SLOT_START + 2, 74, 41));
        // Row 1: (38, 59), (56, 59), (74, 59)
        addSlot(new InputSlot(machine, SmartCookingPotBlockEntity.INPUT_SLOT_START + 3, 38, 59));
        addSlot(new InputSlot(machine, SmartCookingPotBlockEntity.INPUT_SLOT_START + 4, 56, 59));
        addSlot(new InputSlot(machine, SmartCookingPotBlockEntity.INPUT_SLOT_START + 5, 74, 59));

        // Output slot (130, 41)
        addSlot(new OutputSlot(machine, SmartCookingPotBlockEntity.OUTPUT_SLOT, 130, 41));
        // Return slot (130, 59)
        addSlot(new OutputSlot(machine, SmartCookingPotBlockEntity.RETURN_SLOT, 130, 59));

        // Upgrade slots (visible only in upgrade page, not removable)
        this.speedUpgradeSlot = new UpgradeSlot(machine.getItems(), SmartCookingPotBlockEntity.SLOT_SPEED_UPGRADE, 40, 46, this);
        addSlot(this.speedUpgradeSlot);
        this.energyUpgradeSlot = new UpgradeSlot(machine.getItems(), SmartCookingPotBlockEntity.SLOT_ENERGY_UPGRADE, 40, 72, this);
        addSlot(this.energyUpgradeSlot);

        // 存储槽（81）：改「单列纵向滚动」（拍板 F1·方案 5，与烧烤工厂同款）。
        // 真实槽坐标全部移到屏幕外（-1000）——渲染与命中改由客户端 GuiVirtualSlot 承载，
        // 见 SmartCookingPotScreen。索引与数量保持 STORAGE_SLOT_COUNT 个不变 ⇒ 存档兼容。
        int storageSlots = SmartCookingPotBlockEntity.STORAGE_SLOT_COUNT;
        for (int i = 0; i < storageSlots; i++) {
            int slotIndex = SmartCookingPotBlockEntity.STORAGE_SLOT_START + i;
            addSlot(new StorageSlot(machine.getItems(), slotIndex, -1000, -1000));
        }

        // Power slot (energy items: energy cube / tablet / redstone), on the right near the energy bar
        this.powerSlotIndex = slots.size();
        addSlot(new PowerSlot(machine, machine.getPowerSlot(), 7, 13, this));

        // Player inventory: starting from (20, 152)——下移到流体计（y88~146）下方，修正旧版与流体计重叠的错位
        int invTop = 152;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, 20 + column * 18, invTop + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 20 + column * 18, invTop + 58));
        }
        addDataSlots(data);
    }

    public int getImageWidth() {
        return Math.max(176, 130 + 18 + 20);
    }

    // 存储区改「单列纵向滚动」后，GUI 高度不再由存储行数推导（此前 9 行 ⇒ 333 高，缩放 4 下出屏）。
    // invTop=152（流体计 y88~146 下方），imageHeight=invTop+83=235（快捷栏底 210+18=228 < 235）。
    public int getImageHeight() {
        return 152 + 83;
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
            if (SmartCookingPotBlockEntity.isUsablePowerItem(stack)) {
                // powerSlotIndex(=91) 是菜单下标；handler 常量 SLOT_POWER(=92) 会指向玩家背包第 0 格
                if (!moveItemStackTo(stack, powerSlotIndex, powerSlotIndex + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (SmartCookingPotBlockEntity.isSpeedUpgrade(stack)) {
                if (!moveItemStackTo(stack, SmartCookingPotBlockEntity.SLOT_SPEED_UPGRADE,
                        SmartCookingPotBlockEntity.SLOT_SPEED_UPGRADE + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (SmartCookingPotBlockEntity.isEnergyUpgrade(stack)) {
                if (!moveItemStackTo(stack, SmartCookingPotBlockEntity.SLOT_ENERGY_UPGRADE,
                        SmartCookingPotBlockEntity.SLOT_ENERGY_UPGRADE + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // Try to move to storage slots first
                if (!MekCkTransfer.moveItemStackTo(stack, slots, SmartCookingPotBlockEntity.STORAGE_SLOT_START,
                        SmartCookingPotBlockEntity.STORAGE_SLOT_START + SmartCookingPotBlockEntity.STORAGE_SLOT_COUNT, false)) {
                    // Then try input slots
                    if (!MekCkTransfer.moveItemStackTo(stack, slots, SmartCookingPotBlockEntity.INPUT_SLOT_START,
                            SmartCookingPotBlockEntity.INPUT_SLOT_END + 1, false)) {
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
        return WideDataSlot.read(data,
                SmartCookingPotBlockEntity.DATA_ENERGY,
                SmartCookingPotBlockEntity.DATA_ENERGY_HI);
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

    public void setUpgradePageActive(boolean active) {
        this.upgradePageActive = active;
    }

    public boolean isUpgradePageActive() {
        return this.upgradePageActive;
    }

    public int getOrderQuantity() {
        return data.get(SmartCookingPotBlockEntity.DATA_ORDER_QUANTITY);
    }

    public int getOrderCompleted() {
        return data.get(SmartCookingPotBlockEntity.DATA_ORDER_COMPLETED);
    }

    @Nullable
    public ResourceLocation getOrderRecipeId() {
        return machine.getOrderRecipeId();
    }

    public int getMaxOrderQuantity() {
        ResourceLocation recipeId = machine.getOrderRecipeId();
        if (recipeId == null) return 0;
        net.minecraft.world.item.crafting.Recipe<?> recipe = machine.getAvailableRecipes().stream()
                .filter(r -> r.getId().equals(recipeId))
                .findFirst().orElse(null);
        if (recipe == null) return 0;
        return machine.getMaxConsumableCountForOrder(recipe);
    }

    public BlockPos getBlockPos() {
        return machine.getBlockPos();
    }

    public SmartCookingPotBlockEntity getMachine() {
        return machine;
    }

    public int getRedstoneControl() {
        return data.get(SmartCookingPotBlockEntity.DATA_REDSTONE_CONTROL);
    }

    /** 机身温度（单位 0.01 ℃）。 */
    public int getTemperature() {
        return data.get(SmartCookingPotBlockEntity.DATA_TEMPERATURE);
    }

    /** 客户端重建指定槽位的 FluidStack；空槽返回 EMPTY。 */
    public FluidStack getFluidStack(int tankIndex) {
        // 量拆两槽（满罐可达 Integer.MAX_VALUE，裸读低槽会被读成负数 → 误判空罐），类型仍单槽。
        int amount = WideDataSlot.read(data,
                SmartCookingPotBlockEntity.DATA_FLUID_AMOUNT0 + tankIndex,
                SmartCookingPotBlockEntity.DATA_FLUID_AMOUNT_HI0 + tankIndex);
        if (amount <= 0) return FluidStack.EMPTY;
        int typeId = data.get(SmartCookingPotBlockEntity.DATA_FLUID_TYPE0 + tankIndex);
        Fluid fluid = typeId >= 0 ? BuiltInRegistries.FLUID.byId(typeId) : Fluids.EMPTY;
        if (fluid == null || fluid == Fluids.EMPTY) return FluidStack.EMPTY;
        return new FluidStack(fluid, amount);
    }

    public int getFluidCapacity() {
        return SmartCookingPotBlockEntity.FLUID_CAPACITY;
    }

    private static final class PowerSlot extends SlotItemHandler implements IVirtualSlot {
        private PowerSlot(SmartCookingPotBlockEntity machine, int slot, int x, int y, SmartCookingPotMenu menu) {
            super(machine.getItems(), slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return SmartCookingPotBlockEntity.isUsablePowerItem(stack);
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
        private InputSlot(SmartCookingPotBlockEntity machine, int slot, int x, int y) {
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
        private OutputSlot(SmartCookingPotBlockEntity machine, int slot, int x, int y) {
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
        /** 升级窗口未打开时把渲染位置移出屏幕：主屏便既不绘制、也命中不到它
         *  （Mek 的 VirtualSlotContainerScreen 渲染与 isMouseOverSlot 都走 getActualX/Y）。
         *  刻意<b>不改 isActive()</b> —— 那是槽的语义标志（服务端 mayPlace/转移逻辑依赖它），
         *  为了纯视觉的布局问题去改写它风险过大。 */
        private static final int HIDDEN_POS = -9999;

        private final SmartCookingPotMenu menu;
        private IGUIWindow linkedWindow;
        // 存供给器而非快照：窗口拖拽后 getActualX/Y 必须实时跟随。
        private IntSupplier xSupplier, ySupplier;
        private ItemStack stackToRender = ItemStack.EMPTY;
        private boolean overlay;
        private String tooltip;

        private UpgradeSlot(ItemStackHandler handler, int slot, int x, int y, SmartCookingPotMenu menu) {
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

    // 存储槽：改「单列纵向滚动」——与 grill 同款，实现 IVirtualSlot 承载渲染/命中；
    // getActualX/Y 走 supplier，实时跟随客户端 GuiVirtualSlot 的位置（真实槽已移到 -1000）。保留原有 getMaxStackSize 覆写。
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

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return getItemHandler().getSlotLimit(getContainerSlot());
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

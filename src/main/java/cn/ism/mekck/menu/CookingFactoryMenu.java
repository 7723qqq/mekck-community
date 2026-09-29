package cn.ism.mekck.menu;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.util.MekCkTransfer;
import cn.ism.mekck.blockentity.CookingFactoryBlockEntity;
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

public final class CookingFactoryMenu extends AbstractContainerMenu implements ISideConfigurableMenu, IUpgradeMenu {
    private final CookingFactoryBlockEntity machine;
    private final ContainerData data;
    private final CuttingMachineFactoryTier tier;
    private final int storageSlots;
    private final boolean hasStackUpgrade;
    private boolean upgradePageActive = false;

    private final UpgradeSlot speedUpgradeSlot;
    private final UpgradeSlot energyUpgradeSlot;
    private final UpgradeSlot stackUpgradeSlot;

    // Slot layout constants
    private static final int INPUT_START_X = 38;
    private static final int INPUT_START_Y = 41;
    private static final int INPUT_COLS = 3;
    private static final int INPUT_SPACING = 18;
    private static final int OUTPUT_START_X = 130;
    private static final int OUTPUT_START_Y = 41;
    private static final int OUTPUT_COLS = 3;
    private static final int RETURN_START_X = 130;
    private static final int RETURN_Y = OUTPUT_START_Y + 3 * INPUT_SPACING + 4;
    private static final int STORAGE_ROWS = 12; // 12 slots per row for storage
    private static final int STORAGE_START_Y = 63; // 存储区上移 1 格（原 81）
    private static final int INV_TOP = 160; // 玩家物品栏固定放在 GUI 内部，不受存储区高度影响

    // ================== 存储区布局（双端共用；槽位坐标是 final，客户端改不了，只能在菜单侧算） ==================
    /**
     * 存储区**双侧紧凑布局**：GUI 左右两侧各挂一块。
     *
     * <p>原先写死「左侧 12 列 × 12 行」：宽 216px、高 216px，且纵向从 GUI 内 y=63 一路排到 279 ——
     * 比 GUI 自身（244 高）还长，在窗口小 / GUI 缩放 4（480×270）下**直接排到屏幕外**（看不见也点不到）。</p>
     *
     * <p>现在的口径（两边合计容量 7×11 + 7×10 = 147 ≥ 144）：</p>
     * <ul>
     *   <li><b>左块</b> 7 列 × 11 行 = 77 槽，贴在 GUI 左侧（x 从 -130 到 -4）；</li>
     *   <li><b>右块</b> 7 列 × 10 行 = 70 槽（用 67），贴在 GUI 右侧；</li>
     *   <li>两块都从 y = 34 起：**避开**左上角的侧配 tab（y 6~30）与右侧的升级 tab（y 6~30）；
     *       右块只有 10 行 ⇒ 底边 214，**避开**右下角红石 tab（自 218 起）。</li>
     * </ul>
     * <p>缩放 4（480×270）下：左块屏幕 x 8~134、右块 346~472，纵向 47~245 — 全部在屏幕内。</p>
     */
    public static final int STORAGE_LEFT_COLS = 7;
    public static final int STORAGE_LEFT_ROWS = 11;
    public static final int STORAGE_RIGHT_COLS = 7;
    public static final int STORAGE_RIGHT_ROWS = 10;
    public static final int STORAGE_LEFT_CAPACITY = STORAGE_LEFT_COLS * STORAGE_LEFT_ROWS;
    /** 存储区起始 y（相对 GUI）：让开顶部两个 tab。 */
    public static final int STORAGE_TOP = 34;
    /** 存储区与 GUI 边缘的间距。 */
    public static final int STORAGE_EDGE = 4;

    /** 第 index 个存储槽的 x（相对 GUI 左上角；左侧为负）。 */
    public static int storageSlotX(int index, int guiWidth) {
        if (index < STORAGE_LEFT_CAPACITY) {
            return -(STORAGE_LEFT_COLS * INPUT_SPACING) - STORAGE_EDGE
                    + (index % STORAGE_LEFT_COLS) * INPUT_SPACING;
        }
        int j = index - STORAGE_LEFT_CAPACITY;
        return guiWidth + STORAGE_EDGE + (j % STORAGE_RIGHT_COLS) * INPUT_SPACING;
    }

    /** 第 index 个存储槽的 y（相对 GUI 左上角）。 */
    public static int storageSlotY(int index) {
        if (index < STORAGE_LEFT_CAPACITY) {
            return STORAGE_TOP + (index / STORAGE_LEFT_COLS) * INPUT_SPACING;
        }
        int j = index - STORAGE_LEFT_CAPACITY;
        return STORAGE_TOP + (j / STORAGE_RIGHT_COLS) * INPUT_SPACING;
    }



    public CookingFactoryMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory,
                (CookingFactoryBlockEntity) inventory.player.level().getBlockEntity(buffer.readBlockPos()));
    }

    public CookingFactoryMenu(int containerId, Inventory inventory, CookingFactoryBlockEntity machine) {
        this(containerId, inventory, machine, machine.getData());
    }

    public CookingFactoryMenu(int containerId, Inventory inventory, CookingFactoryBlockEntity machine, ContainerData data) {
        super(UniversalCuttingMachine.COOKING_FACTORY_MENU.get(), containerId);
        this.machine = machine;
        this.data = data;
        this.tier = machine.getTier();
        this.storageSlots = machine.getStorageSlots();
        this.hasStackUpgrade = machine.hasStackUpgradeSlot();

        // 6 input slots: 3 columns x 2 rows
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < INPUT_COLS; col++) {
                int slotIndex = row * INPUT_COLS + col;
                int x = INPUT_START_X + col * INPUT_SPACING;
                int y = INPUT_START_Y + row * INPUT_SPACING;
                addSlot(new InputSlot(machine.getItems(), slotIndex, x, y));
            }
        }

        // 存储槽：GUI **左右两侧**各挂一块（布局见本类顶部的 STORAGE_* 常量与 storageSlotX/Y）
        for (int i = 0; i < storageSlots; i++) {
            int slotIndex = CookingFactoryBlockEntity.INPUT_SLOTS + i;
            int x = storageSlotX(i, getImageWidth());
            int y = storageSlotY(i);
            addSlot(new StorageSlot(machine.getItems(), slotIndex, x, y));
        }

        // Output slots: 3x3 grid (9 slots)
        int outputSlotStart = machine.getOutputSlot();
        for (int i = 0; i < CookingFactoryBlockEntity.OUTPUT_SLOTS; i++) {
            int col = i % OUTPUT_COLS;
            int row = i / OUTPUT_COLS;
            int x = OUTPUT_START_X + col * INPUT_SPACING;
            int y = OUTPUT_START_Y + row * INPUT_SPACING;
            addSlot(new OutputSlot(machine.getItems(), outputSlotStart + i, x, y));
        }

        // Return slots: 3 slots below the output grid
        int returnSlotStart = machine.getReturnSlot();
        for (int i = 0; i < CookingFactoryBlockEntity.RETURN_SLOTS; i++) {
            int x = RETURN_START_X + i * INPUT_SPACING;
            addSlot(new OutputSlot(machine.getItems(), returnSlotStart + i, x, RETURN_Y));
        }

        // Upgrade slots (only visible in upgrade page)
        this.speedUpgradeSlot = new UpgradeSlot(machine.getItems(), machine.getSpeedUpgradeSlot(), 40, 46, this);
        addSlot(this.speedUpgradeSlot);
        this.energyUpgradeSlot = new UpgradeSlot(machine.getItems(), machine.getEnergyUpgradeSlot(), 40, 72, this);
        addSlot(this.energyUpgradeSlot);
        if (hasStackUpgrade) {
            this.stackUpgradeSlot = new UpgradeSlot(machine.getItems(), machine.getStackUpgradeSlot(), 40, 98, this);
            addSlot(this.stackUpgradeSlot);
        } else {
            this.stackUpgradeSlot = null;
        }

        // Power slot (energy items: energy cube / tablet / redstone), on the right near the energy bar
        addSlot(new PowerSlot(machine, machine.getPowerSlot(), 7, 13, this));

        // 玩家物品栏固定放在 GUI 内部（不再受存储区高度影响，避免屏幕外显示）
        int imageWidth = getImageWidth();
        int invLeft = (imageWidth - 162) / 2;
        int invTop = INV_TOP;

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

    public int getImageWidth() {
        return Math.max(176, OUTPUT_START_X + 3 * INPUT_SPACING + 20);
    }

    public int getImageHeight() {
        return INV_TOP + 58 + 26;
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
        int totalMachineSlots = machine.getTotalSlots();

        if (index < totalMachineSlots) {
            if (!moveItemStackTo(stack, totalMachineSlots, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            int speedUpgradeSlot = machine.getSpeedUpgradeSlot();
            int energyUpgradeSlot = machine.getEnergyUpgradeSlot();
            int stackUpgradeSlot = machine.getStackUpgradeSlot();
            int powerSlot = machine.getPowerSlot();
            int inputEnd = CookingFactoryBlockEntity.INPUT_SLOTS + machine.getStorageSlots(); // input + storage
            if (CookingFactoryBlockEntity.isUsablePowerItem(stack)) {
                if (!moveItemStackTo(stack, powerSlot, powerSlot + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (CookingFactoryBlockEntity.isSpeedUpgrade(stack)) {
                if (!moveItemStackTo(stack, speedUpgradeSlot, speedUpgradeSlot + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (CookingFactoryBlockEntity.isEnergyUpgrade(stack)) {
                if (!moveItemStackTo(stack, energyUpgradeSlot, energyUpgradeSlot + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (hasStackUpgrade && stackUpgradeSlot >= 0 && CookingFactoryBlockEntity.isStackUpgrade(stack)) {
                if (!moveItemStackTo(stack, stackUpgradeSlot, stackUpgradeSlot + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!MekCkTransfer.moveItemStackTo(stack, slots, CookingFactoryBlockEntity.INPUT_SLOTS, inputEnd, false)) {
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
        int progress = data.get(CookingFactoryBlockEntity.DATA_PROGRESS);
        int max = data.get(CookingFactoryBlockEntity.DATA_PROCESS_TIME);
        return max == 0 ? 0 : progress * 24 / max;
    }

    public int getEnergy() {
        return data.get(CookingFactoryBlockEntity.DATA_ENERGY);
    }

    public int getEnergyCapacity() {
        return data.get(CookingFactoryBlockEntity.DATA_ENERGY_CAPACITY);
    }

    public int getEncodedSideConfig() {
        return data.get(CookingFactoryBlockEntity.DATA_SIDE_CONFIG);
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
        return data.get(CookingFactoryBlockEntity.DATA_SPEED_UPGRADE);
    }

    public int getEnergyUpgradeCount() {
        return data.get(CookingFactoryBlockEntity.DATA_ENERGY_UPGRADE);
    }

    public int getStackUpgradeCount() {
        return data.get(CookingFactoryBlockEntity.DATA_STACK_UPGRADE);
    }

    public int getRedstoneControl() {
        return data.get(CookingFactoryBlockEntity.DATA_REDSTONE_CONTROL);
    }

    /** 机身温度（单位 0.01 ℃）。 */
    public int getTemperature() {
        return data.get(CookingFactoryBlockEntity.DATA_TEMPERATURE);
    }

    public int getFluidAmount() {
        return data.get(CookingFactoryBlockEntity.DATA_FLUID);
    }

    /** 客户端重建指定槽位的 FluidStack；空槽返回 EMPTY。 */
    public FluidStack getFluidStack(int tankIndex) {
        int amount = data.get(CookingFactoryBlockEntity.DATA_FLUID_AMOUNT0 + tankIndex);
        if (amount <= 0) return FluidStack.EMPTY;
        int typeId = data.get(CookingFactoryBlockEntity.DATA_FLUID_TYPE0 + tankIndex);
        Fluid fluid = typeId >= 0 ? BuiltInRegistries.FLUID.byId(typeId) : Fluids.EMPTY;
        if (fluid == null || fluid == Fluids.EMPTY) return FluidStack.EMPTY;
        return new FluidStack(fluid, amount);
    }

    public int getFluidCapacity() {
        return CookingFactoryBlockEntity.FLUID_CAPACITY;
    }

    public int getOrderQuantity() {
        return data.get(CookingFactoryBlockEntity.DATA_ORDER_QUANTITY);
    }

    public int getOrderCompleted() {
        return data.get(CookingFactoryBlockEntity.DATA_ORDER_COMPLETED);
    }

    @Nullable
    public ResourceLocation getOrderRecipeId() {
        return machine.getOrderRecipeId();
    }

    public int getMaxOrderQuantity() {
        ResourceLocation recipeId = machine.getOrderRecipeId();
        if (recipeId == null) return 0;
        // Find the recipe by ID and get max consumable count
        net.minecraft.world.item.crafting.Recipe<?> recipe = machine.getAvailableRecipes().stream()
                .filter(r -> r.getId().equals(recipeId))
                .findFirst().orElse(null);
        if (recipe == null) return 0;
        return machine.getMaxConsumableCountForOrder(recipe);
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

    public int getStorageSlots() {
        return storageSlots;
    }

    public void setUpgradePageActive(boolean active) {
        this.upgradePageActive = active;
    }

    public boolean isUpgradePageActive() {
        return this.upgradePageActive;
    }

    public CookingFactoryBlockEntity getMachine() {
        return machine;
    }

    private static final class PowerSlot extends SlotItemHandler implements IVirtualSlot {
        private PowerSlot(CookingFactoryBlockEntity machine, int slot, int x, int y, CookingFactoryMenu menu) {
            super(machine.getItems(), slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return CookingFactoryBlockEntity.isUsablePowerItem(stack);
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

    // Storage slot: NOT IVirtualSlot, so items are rendered by standard renderSlot()
    private static final class StorageSlot extends SlotItemHandler {
        private StorageSlot(ItemStackHandler handler, int slot, int x, int y) {
            super(handler, slot, x, y);
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return getItemHandler().getSlotLimit(getContainerSlot());
        }
    }

    private static final class UpgradeSlot extends SlotItemHandler implements IVirtualSlot {
        private final CookingFactoryMenu menu;
        private IGUIWindow linkedWindow;
        private int actualX, actualY;
        private ItemStack stackToRender = ItemStack.EMPTY;
        private boolean overlay;
        private String tooltip;

        private UpgradeSlot(ItemStackHandler handler, int slot, int x, int y, CookingFactoryMenu menu) {
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
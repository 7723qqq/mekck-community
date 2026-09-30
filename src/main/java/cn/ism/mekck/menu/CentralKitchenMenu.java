package cn.ism.mekck.menu;

import cn.ism.mekck.blockentity.CentralKitchenBlockEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 中央厨房菜单：模块槽（20）+ 动态存储区视图（6 行 × 9 列 = 54 个可见格，可滚动）
 * + 输出区（30 格，显示 3×3）+ 玩家背包。
 *
 * <p>存储区共有 300 格，界面通过「显示序列」（{@link #displayOrder}）把可见的 54 个虚拟格
 * 映射到真实存储槽；搜索与排序在服务端执行并同步序列索引，物品内容由原版槽位同步机制推送。</p>
 */
public class CentralKitchenMenu extends AbstractContainerMenu
        implements cn.ism.mekck.menu.ISideConfigurableMenu {

    public static final int VISIBLE_MODULES = CentralKitchenBlockEntity.MODULE_SLOTS;
    /** 存储区可见行数 / 列数。 */
    public static final int STORAGE_ROWS = 6;
    public static final int STORAGE_COLS = 9;
    public static final int VISIBLE_STORAGE = STORAGE_ROWS * STORAGE_COLS;
    /** 输出区可见格数。 */
    public static final int VISIBLE_OUTPUT = 9;

    /** 排序模式。 */
    public enum SortMode {
        /** 默认：按存储槽顺序。 */
        INDEX,
        /** 按物品名称。 */
        NAME,
        /** 按数量（多 → 少）。 */
        COUNT
    }

    private final CentralKitchenBlockEntity machine;

    /** 当前显示序列：元素为真实存储槽索引（-1 = 空格）。 */
    private final int[] displayOrder = new int[VISIBLE_STORAGE];
    /** 过滤后的全部匹配槽索引（服务端计算）。 */
    private final List<Integer> filtered = new ArrayList<>();
    private int scrollRow = 0;
    private String searchText = "";
    private SortMode sortMode = SortMode.INDEX;

    private final List<StorageSlot> storageSlots = new ArrayList<>();

    /** 数据槽：0=系列掩码，1=订单数，2=运行线程数，3=总线程数，4/5/6=物品/流体/气体侧配编码。 */
    private final net.minecraft.world.inventory.ContainerData data = new net.minecraft.world.inventory.ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case 0 -> machine.installedFamilyMask();
                case 1 -> machine.orderCount();
                case 2 -> machine.runningThreads();
                case 3 -> machine.totalThreads();
                case 4 -> encodeSide(machine::getItemSideMode);
                case 5 -> encodeSide(machine::getFluidSideMode);
                case 6 -> encodeSide(machine::getGasSideMode);
                case 7 -> machine.firstOrderProgressMilli();
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
        }

        @Override
        public int getCount() {
            return 8;
        }
    };

    /** 动态索引存储槽：显示位置固定，真实索引随滚动 / 搜索变化。 */
    public class StorageSlot extends Slot {
        private int machineIndex = -1;
        private final int displayIndex;

        StorageSlot(int displayIndex, int x, int y) {
            super(new SimpleContainer(1), 0, x, y);
            this.displayIndex = displayIndex;
        }

        void setMachineIndex(int index) {
            this.machineIndex = index;
        }

        public int getMachineIndex() {
            return machineIndex;
        }

        @Override
        public boolean hasItem() {
            return machineIndex >= 0 && !getItem().isEmpty();
        }

        @Override
        public ItemStack getItem() {
            return machineIndex < 0 ? ItemStack.EMPTY : machine.items.getStackInSlot(machineIndex);
        }

        @Override
        public void set(ItemStack stack) {
            if (machineIndex >= 0) machine.items.setStackInSlot(machineIndex, stack);
        }

        @Override
        public void setChanged() {
            machine.setChanged();
        }

        @Override
        public ItemStack remove(int amount) {
            return machineIndex < 0 ? ItemStack.EMPTY
                    : machine.items.extractItem(machineIndex, amount, false);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return machineIndex >= 0;
        }

        @Override
        public boolean mayPickup(Player player) {
            return machineIndex >= 0;
        }

        @Override
        public int getMaxStackSize() {
            return CentralKitchenBlockEntity.BIG_STACK;
        }

        @Override
        public boolean isSameInventory(Slot other) {
            return other == this;
        }
    }

    public CentralKitchenMenu(int containerId, Inventory playerInventory, CentralKitchenBlockEntity machine) {
        super(cn.ism.mekck.UniversalCuttingMachine.CENTRAL_KITCHEN_MENU.get(), containerId);
        this.machine = machine;

        // 模块槽 5×4（左区）
        for (int i = 0; i < VISIBLE_MODULES; i++) {
            addSlot(new Slot(new ModuleContainer(machine, i),
                    i, 12 + (i % 5) * 18, 20 + (i / 5) * 18));
        }

        // 存储区视图 6×9（中区，动态索引）
        for (int i = 0; i < VISIBLE_STORAGE; i++) {
            StorageSlot slot = new StorageSlot(i, 120 + (i % STORAGE_COLS) * 18,
                    20 + (i / STORAGE_COLS) * 18);
            storageSlots.add(slot);
            addSlot(slot);
        }

        // 输出区 3×3（右区）
        for (int i = 0; i < VISIBLE_OUTPUT; i++) {
            addSlot(new Slot(new OutputContainer(machine, i), i,
                    294 + (i % 3) * 18, 20 + (i / 3) * 18));
        }

        // 三明治样品槽（安装「三明治组装机」模块后用于定义要量产的三明治；放在输出区下方）
        addSlot(new Slot(new SampleContainer(machine),
                CentralKitchenBlockEntity.SANDWICH_SAMPLE_SLOT, 294, 78));

        // 玩家背包 + 快捷栏（服务端与客户端必须一致地注册，否则槽位数量不匹配）
        int invY = 152;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, 9 + row * 9 + col, 39 + col * 18, invY + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 39 + col * 18, invY + 58));
        }

        addDataSlots(data);
        refreshDisplay();
    }

    public CentralKitchenMenu(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
        this(containerId, playerInventory,
                (CentralKitchenBlockEntity) playerInventory.player.level().getBlockEntity(buf.readBlockPos()));
    }

    // ================== 显示序列（搜索 / 排序 / 滚动） ==================

    /**
     * 重建过滤 + 排序后的序列，并刷新可见槽映射。
     *
     * <p><b>这是浏览器唯一的数据来源</b>，调用点必须覆盖「内容变了」的所有时机。
     * 第三轮审查发现它此前只在构造器、{@code setSearchText}、{@code setSortMode}
     * 和 quickMoveStack 成功后被调 —— 也就是说存储区在<b>开界面之后</b>发生的
     * 任何变化（AutoIO 拉入、订单交付、AE2 补料、弹出）都不会反映到界面上：
     * 新到的物品永远不出现，被消耗掉的物品还占着一个可见的空格。
     * 现补上滚动时重建与客户端周期性重建（见 {@code CentralKitchenScreen#tick}）。</p>
     */
    public void refreshDisplay() {
        filtered.clear();
        String needle = searchText == null ? "" : searchText.trim().toLowerCase();
        for (int i = CentralKitchenBlockEntity.STORAGE_START; i < CentralKitchenBlockEntity.OUTPUT_START; i++) {
            ItemStack stack = machine.items.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            if (!needle.isEmpty()) {
                String name = stack.getHoverName().getString().toLowerCase();
                String id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
                if (!name.contains(needle) && !id.contains(needle)) continue;
            }
            filtered.add(i);
        }
        switch (sortMode) {
            case NAME -> filtered.sort(Comparator.comparing(
                    i -> machine.items.getStackInSlot(i).getHoverName().getString()));
            case COUNT -> filtered.sort(Comparator.comparingInt(
                    (Integer i) -> -machine.items.getStackInSlot(i).getCount()));
            default -> { }
        }
        // 内容变少时把滚动位置夹回合法范围：否则 applyScroll 会整页渲染成空，
        // 而屏幕上的页码指示还会停在旧的 (scrollRow+1)/maxScrollRow 上。
        int max = maxScrollRow();
        if (scrollRow > max) {
            scrollRow = max;
        }
        applyScroll();
    }

    /** 按当前滚动行刷新 54 个可见槽的真实索引。 */
    private void applyScroll() {
        int start = scrollRow * STORAGE_COLS;
        for (int i = 0; i < VISIBLE_STORAGE; i++) {
            int idx = start + i;
            int machineIndex = idx < filtered.size() ? filtered.get(idx) : -1;
            displayOrder[i] = machineIndex;
            storageSlots.get(i).setMachineIndex(machineIndex);
        }
    }

    public void setSearchText(String text) {
        this.searchText = text == null ? "" : text;
        this.scrollRow = 0;
        refreshDisplay();
    }

    public void setSortMode(SortMode mode) {
        this.sortMode = mode;
        this.scrollRow = 0;
        refreshDisplay();
    }

    public void scroll(int delta) {
        int maxRow = maxScrollRow();
        int next = Math.max(0, Math.min(maxRow, scrollRow + delta));
        if (next == scrollRow) return;
        this.scrollRow = next;
        // ⚠️ 这里原本只调 applyScroll()，也就是**只重切上一次算好的 filtered**。
        // 于是「开界面之后才进存储区的东西」永远不出现，而被消耗掉的格子还占着位置 ——
        // 屏幕上的页码指示也跟着错（它读的是同一个陈旧 filtered）。
        // 改为整段重建：scroll 的语义本来就包含「翻到下一页」，而下一页的内容
        // 必须是**当下**的存储区内容。
        refreshDisplay();
    }

    public int maxScrollRow() {
        int rows = (filtered.size() + STORAGE_COLS - 1) / STORAGE_COLS;
        return Math.max(0, rows - STORAGE_ROWS);
    }

    public int getScrollRow() {
        return scrollRow;
    }

    public String getSearchText() {
        return searchText;
    }

    public SortMode getSortMode() {
        return sortMode;
    }

    public int getFilteredCount() {
        return filtered.size();
    }

    // ================== 库存包装 ==================

    /** 模块槽容器视图。 */
    private static final class ModuleContainer extends SimpleContainer {
        private final CentralKitchenBlockEntity machine;
        private final int offset;

        ModuleContainer(CentralKitchenBlockEntity machine, int offset) {
            super(1);
            this.machine = machine;
            this.offset = offset;
        }

        @Override
        public ItemStack getItem(int index) {
            return machine.items.getStackInSlot(CentralKitchenBlockEntity.MODULE_START + offset);
        }

        @Override
        public void setItem(int index, ItemStack stack) {
            machine.items.setStackInSlot(CentralKitchenBlockEntity.MODULE_START + offset, stack);
        }

        @Override
        public ItemStack removeItem(int index, int amount) {
            return machine.items.extractItem(CentralKitchenBlockEntity.MODULE_START + offset, amount, false);
        }

        @Override
        public void setChanged() {
            machine.setChanged();
        }

        @Override
        public boolean canPlaceItem(int index, ItemStack stack) {
            return machine.canInstallModule(CentralKitchenBlockEntity.MODULE_START + offset, stack);
        }
    }

    /** 输出槽容器视图。 */
    /** 三明治样品槽容器（1 格）。 */
    private static final class SampleContainer extends SimpleContainer {
        private final CentralKitchenBlockEntity machine;

        SampleContainer(CentralKitchenBlockEntity machine) {
            super(1);
            this.machine = machine;
        }

        @Override
        public boolean canPlaceItem(int index, ItemStack stack) {
            return true;
        }

        @Override
        public ItemStack getItem(int index) {
            return machine.items.getStackInSlot(CentralKitchenBlockEntity.SANDWICH_SAMPLE_SLOT);
        }

        @Override
        public void setItem(int index, ItemStack stack) {
            machine.items.setStackInSlot(CentralKitchenBlockEntity.SANDWICH_SAMPLE_SLOT, stack);
        }

        @Override
        public void setChanged() {
            machine.setChanged();
        }
    }

    private static final class OutputContainer extends SimpleContainer {
        private final CentralKitchenBlockEntity machine;
        private final int offset;

        OutputContainer(CentralKitchenBlockEntity machine, int offset) {
            super(1);
            this.machine = machine;
            this.offset = offset;
        }

        @Override
        public ItemStack getItem(int index) {
            return machine.items.getStackInSlot(CentralKitchenBlockEntity.OUTPUT_START + offset);
        }

        @Override
        public void setItem(int index, ItemStack stack) {
            machine.items.setStackInSlot(CentralKitchenBlockEntity.OUTPUT_START + offset, stack);
        }

        @Override
        public ItemStack removeItem(int index, int amount) {
            return machine.items.extractItem(CentralKitchenBlockEntity.OUTPUT_START + offset, amount, false);
        }

        @Override
        public void setChanged() {
            machine.setChanged();
        }
    }

    public CentralKitchenBlockEntity getMachine() {
        return machine;
    }

    /** 侧配编码（每面 2 bit）。 */
    private static int encodeSide(java.util.function.Function<net.minecraft.core.Direction, cn.ism.mekck.SideMode> getter) {
        int v = 0;
        for (net.minecraft.core.Direction d : cn.ism.mekck.util.Directions.VALUES) {
            v |= (getter.apply(d).ordinal() & 0x3) << (d.ordinal() * 2);
        }
        return v;
    }

    private static cn.ism.mekck.SideMode decodeSide(int encoded, net.minecraft.core.Direction dir) {
        int ord = (encoded >> (dir.ordinal() * 2)) & 0x3;
        var values = cn.ism.mekck.SideMode.values();
        return (ord >= 0 && ord < values.length) ? values[ord] : cn.ism.mekck.SideMode.NONE;
    }

    @Override
    public cn.ism.mekck.SideMode getSideMode(net.minecraft.core.Direction direction) {
        return decodeSide(data.get(4), direction);
    }

    @Override
    public cn.ism.mekck.SideMode getFluidSideMode(net.minecraft.core.Direction direction) {
        return decodeSide(data.get(5), direction);
    }

    @Override
    public cn.ism.mekck.SideMode getGasSideMode(net.minecraft.core.Direction direction) {
        return decodeSide(data.get(6), direction);
    }

    @Override
    public net.minecraft.core.BlockPos getBlockPos() {
        return machine.getBlockPos();
    }

    @Override
    public boolean supportsStoragePull() {
        return false;
    }

    /** 首个订单的当前步骤进度（0~1000）。 */
    public int getOrderProgressMilli() {
        return data.get(7);
    }

    /** 已安装系列掩码（客户端可读）。 */
    public int getFamilyMask() {
        return data.get(0);
    }

    public int getOrderCount() {
        return data.get(1);
    }

    public int getRunningThreads() {
        return data.get(2);
    }

    public int getTotalThreads() {
        return data.get(3);
    }

    /**
     * 机器侧的槽位总数 —— <b>构造器实际添加的槽数</b>，不是「看起来该有多少」。
     *
     * <p><b>原先漏算了三明治样品槽</b>（本轮修掉）：{@link #VISIBLE_MODULES} +
     * {@link #VISIBLE_STORAGE} + {@link #VISIBLE_OUTPUT} = 20 + 54 + 9 = 83，
     * 而构造器在输出区<b>之后</b>还加了第 84 个槽 ——
     * {@code addSlot(new Slot(new SampleContainer(machine), SANDWICH_SAMPLE_SLOT, 294, 78))}。
     * 于是样品槽的下标 83 落进「玩家侧」分支：</p>
     * <ul>
     *   <li>{@code canInstallModule} 为假 ⇒ 走
     *       {@code MekCkTransfer.moveItemStackTo(stack, slots, VISIBLE_MODULES, VISIBLE_MODULES+VISIBLE_STORAGE)}
     *       把三明治<b>并进 300 格存储区</b>；</li>
     *   <li>随后 {@code slot.set(ItemStack.EMPTY)} 把样品槽清空，
     *       {@code refreshDisplay()} 让它从视野消失 —— 玩家再也拿不回来。</li>
     * </ul>
     * 不是复制（区间不含 83），但物品被未经同意搬走 + 功能槽被静默清空。
     *
     * <p><b>刻意写成对构造器的断言而不是又一份手算</b>：本类已有
     * {@code TestMenuQuickMoveSlotRanges} 钉「机器槽在玩家背包之前分配」，
     * 这里补的是「总数 = 实际 addSlot 数」这一条，两者合起来才关掉这一类 off-by-one。
     * 以后再往机器侧加槽，这个数不会忘记跟着改。</p>
     */
    private static final int MACHINE_SLOT_COUNT = VISIBLE_MODULES + VISIBLE_STORAGE + VISIBLE_OUTPUT + 1;

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        int machineSlots = MACHINE_SLOT_COUNT;
        if (index < machineSlots) {
            if (!moveItemStackTo(stack, machineSlots, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            // 玩家背包 → 优先模块槽（若是可安装模块），否则进存储区
            if (machine.canInstallModule(CentralKitchenBlockEntity.MODULE_START, stack)
                    && moveItemStackTo(stack, 0, VISIBLE_MODULES, false)) {
                refreshDisplay();
            } else if (!cn.ism.mekck.util.MekCkTransfer.moveItemStackTo(stack, slots,
                    VISIBLE_MODULES, VISIBLE_MODULES + VISIBLE_STORAGE, false)) {
                // 存储区上限是 BIG_STACK（Integer.MAX_VALUE-1）：走原版会被物品自身的 64 钳制，
                // 已堆到 64 的那一格 shift 就再也并不进去（与陈酿机 / 三明治组装机同一病灶）。
                // 模块槽上限 1、且是「装上去就用」的升级件，保持原版口径。
                return ItemStack.EMPTY;
            } else {
                refreshDisplay();
            }
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return machine.getLevel() != null
                && player.distanceToSqr(machine.getBlockPos().getCenter()) <= 64.0;
    }
}

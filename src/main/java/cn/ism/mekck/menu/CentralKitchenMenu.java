package cn.ism.mekck.menu;

import cn.ism.mekck.blockentity.CentralKitchenBlockEntity;
import net.minecraft.network.FriendlyByteBuf;
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

    // ================== 客户端渲染镜像（修 I-N4） ==================
    //
    // 存储浏览器此前在客户端**完全算不出来**：client 侧的 CentralKitchenBlockEntity
    // 是一个 items 全空的桩（300 格存储从不进 ContainerSynchronizer），
    // 而 refreshDisplay 遍历的是 machine.items ⇒ filtered 恒空 ⇒
    // applyScroll 把 54 个 machineIndex 全置 -1 ⇒ 界面一片空白。
    //
    // 搜索/排序/滚动的计算**本来就在服务端是对的**（KitchenViewPacket 落地后调的是
    // 服务端 menu），缺的只是把结果送下去。所以这里只镜像**当前可见的一页**，
    // 不镜像 300 格全量。
    //
    // clientVisible == null 表示「还没收到过快照」，此时按空处理（而不是回退去读
    // machine.items —— 那是空桩，回退等于什么都不改）。
    private ItemStack[] clientVisible;

    /** 客户端侧的「共 N 条」，供页码指示用。服务端用 filtered.size()。 */
    private int clientFilteredCount;

    /** 上次推送的存储区版本号；用于「内容变了才推」，避免 AutoIO 每 tick 都发 54 格。 */
    private int lastPushedStorageVersion = -1;

    /** 推送节流：AutoIO 可能每 tick 都改存储区，全量推送是带宽灾难。 */
    private long lastPushGameTime = Long.MIN_VALUE;
    private static final long PUSH_INTERVAL_TICKS = 4L;

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
            return !getItem().isEmpty();
        }

        /**
         * {@inheritDoc}
         *
         * <p><b>分端</b>（修 I-N4）：服务端读真实存储槽，客户端读
         * {@link CentralKitchenMenu#clientVisible} 里按<b>显示位置</b>索引的镜像。
         *
         * <p>客户端<b>不能</b>用 {@code machineIndex} —— 它是服务端过滤/排序后的
         * 真实存储下标，客户端那份 {@code filtered} 恒空、{@code machineIndex} 恒 -1，
         * 于是老写法在客户端永远返回 {@code ItemStack.EMPTY}，界面是死的。
         * 镜像按显示位置索引，正好对应「这一页的第几格」。</p>
         */
        @Override
        public ItemStack getItem() {
            if (isClientSide()) {
                ItemStack[] mirror = clientVisible;
                if (mirror == null || displayIndex < 0 || displayIndex >= mirror.length) {
                    return ItemStack.EMPTY;
                }
                return mirror[displayIndex];
            }
            return machineIndex < 0 ? ItemStack.EMPTY : machine.items.getStackInSlot(machineIndex);
        }

        @Override
        public void set(ItemStack stack) {
            // 客户端不写：真实变更由服务端执行，快照会覆盖这里的任何本地预测。
            // 旧写法在客户端写的是空桩 BE 的 items，写了也没人看，却会让
            // 「客户端算出的 machine.items 与服务端不一致」这类问题更难查。
            if (isClientSide()) {
                return;
            }
            if (machineIndex >= 0) machine.items.setStackInSlot(machineIndex, stack);
        }

        @Override
        public void setChanged() {
            if (isClientSide()) {
                return;
            }
            machine.setChanged();
        }

        @Override
        public ItemStack remove(int amount) {
            if (isClientSide()) {
                return ItemStack.EMPTY;
            }
            return machineIndex < 0 ? ItemStack.EMPTY
                    : machine.items.extractItem(machineIndex, amount, false);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !isClientSide() && machineIndex >= 0;
        }

        @Override
        public boolean mayPickup(Player player) {
            return !isClientSide() && machineIndex >= 0;
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
        // 客户端**不再自行计算**（修 I-N4）：它手里的 machine.items 是空桩，
        // 算出来的必然是空列表；页面数据一律由 KitchenStorageSyncPacket 下行。
        // 保留这个提前返回而不是删掉调用点，是为了让 quickMoveStack 等
        // 服务端路径仍然只调一个入口。
        if (isClientSide()) {
            return;
        }
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
        // ⚠️ 这里**不**推送：由调用方决定。构造器调本方法时玩家的 containerMenu
        // 还没设成本 menu，推了也没有观众；搜索/排序/滚动是玩家直接触发的、必须
        // 立刻回应；而 BE 每 tick 的补推要节流。三者的推送策略不同，
        // 所以由调用方各自 pushStorageSync(...)，而不是在这里一刀切。
    }

    // ================== 客户端快照落地（修 I-N4） ==================

    /**
     * 应用服务端下行的存储浏览器快照。<b>只在客户端被调用</b>。
     *
     * @param sortModeOrdinal 排序模式序号；越界一律落回 {@link SortMode#INDEX}，
     *                        不抛异常——脏数据不该把客户端界面打崩
     */
    public void applyStorageSnapshot(int scrollRow, int sortModeOrdinal, int filteredCount,
                                     List<ItemStack> visible) {
        SortMode[] modes = SortMode.values();
        this.sortMode = (sortModeOrdinal >= 0 && sortModeOrdinal < modes.length)
                ? modes[sortModeOrdinal] : SortMode.INDEX;
        this.scrollRow = Math.max(0, scrollRow);
        this.clientFilteredCount = Math.max(0, filteredCount);
        ItemStack[] mirror = new ItemStack[VISIBLE_STORAGE];
        for (int i = 0; i < VISIBLE_STORAGE; i++) {
            ItemStack src = (visible != null && i < visible.size()) ? visible.get(i) : null;
            // copy() 而不是直接持有包里的引用：包对象会被 GC，而 Slot 可能长期持有这个
            // ItemStack；不拷贝的话别名共享会让「取出的量」污染快照本身。
            mirror[i] = src == null || src.isEmpty() ? ItemStack.EMPTY : src.copy();
        }
        this.clientVisible = mirror;
    }

    /**
     * 把当前页推给正在看这个界面的玩家。
     *
     * @param force {@code true} 跳过「版本没变」与节流（搜索/排序/滚动这类
     *              由玩家直接触发、必须立刻回应的即时响应）
     */
    public void pushStorageSync(boolean force) {
        if (isClientSide() || machine == null) {
            return;
        }
        var level = machine.getLevel();
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        long now = serverLevel.getGameTime();
        int version = machine.storageVersion();
        if (!force) {
            if (version == lastPushedStorageVersion
                    && lastPushGameTime != Long.MIN_VALUE
                    && now - lastPushGameTime < PUSH_INTERVAL_TICKS) {
                return;
            }
        }

        List<ItemStack> page = new ArrayList<>(VISIBLE_STORAGE);
        for (int i = 0; i < VISIBLE_STORAGE; i++) {
            int idx = displayOrder[i];
            ItemStack stack = idx >= 0 ? machine.items.getStackInSlot(idx) : ItemStack.EMPTY;
            page.add(stack.copy());
        }
        int sent = 0;
        for (var player : serverLevel.players()) {
            if (player.containerMenu == this) {
                cn.ism.mekck.network.ModMessages.sendToPlayer(
                        new cn.ism.mekck.network.KitchenStorageSyncPacket(
                                machine.getBlockPos(), scrollRow, sortMode.ordinal(),
                                filtered.size(), page),
                        player);
                sent++;
            }
        }
        if (sent > 0) {
            lastPushedStorageVersion = version;
            lastPushGameTime = now;
        } else {
            // **没有观众就不记账**。
            // 构造器里 refreshDisplay() 调本方法时，玩家的 containerMenu 还没被设成
            // 本 menu（MenuProvider.createMenu 返回后才赋值），此刻 sent 恒为 0。
            // 若在这里照样记账，首推会被记成「已发」，随后 BE 的第一次 tick 看到
            // 「版本没变」就直接返回 —— 界面永远收不到第一页。
            // 不记账则下一 tick 自动重试，且重试成本只是一次 players() 遍历。
            lastPushGameTime = Long.MIN_VALUE;
            lastPushedStorageVersion = -1;
        }
    }

    /** BE 侧每 tick 调用：内容变了就补推一次（节流在 pushStorageSync 内）。 */
    public void tickStorageSync() {
        pushStorageSync(false);
    }

    private boolean isClientSide() {
        return machine != null && machine.getLevel() != null && machine.getLevel().isClientSide;
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
        // 搜索框每敲一个键就来一次 ⇒ 强制推送，不走节流。
        pushStorageSync(true);
    }

    public void setSortMode(SortMode mode) {
        this.sortMode = mode;
        this.scrollRow = 0;
        refreshDisplay();
        pushStorageSync(true);
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
        pushStorageSync(true);
    }

    public int maxScrollRow() {
        int rows = (filtered.size() + STORAGE_COLS - 1) / STORAGE_COLS;
        return Math.max(0, rows - STORAGE_ROWS);
    }

    public int getScrollRow() {
        // 双端通用：客户端的 scrollRow 由 applyStorageSnapshot 写入，
        // 服务端由 applyScroll 维护。
        return scrollRow;
    }

    public String getSearchText() {
        return searchText;
    }

    public SortMode getSortMode() {
        return sortMode;
    }

    /** 匹配总数。客户端取快照带来的值（客户端算不出来）。 */
    public int getFilteredCount() {
        return isClientSide() ? clientFilteredCount : filtered.size();
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
                pushStorageSync(true);
            } else if (!cn.ism.mekck.util.MekCkTransfer.moveItemStackTo(stack, slots,
                    VISIBLE_MODULES, VISIBLE_MODULES + VISIBLE_STORAGE, false)) {
                // 存储区上限是 BIG_STACK（Integer.MAX_VALUE-1）：走原版会被物品自身的 64 钳制，
                // 已堆到 64 的那一格 shift 就再也并不进去（与陈酿机 / 三明治组装机同一病灶）。
                // 模块槽上限 1、且是「装上去就用」的升级件，保持原版口径。
                return ItemStack.EMPTY;
            } else {
                refreshDisplay();
                pushStorageSync(true);
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

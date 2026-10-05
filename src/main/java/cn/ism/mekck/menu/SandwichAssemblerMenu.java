package cn.ism.mekck.menu;

import cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;
import cn.ism.mekck.registry.MekCkStandaloneMachines;
import mekanism.common.inventory.container.IGUIWindow;
import mekanism.common.inventory.container.slot.IVirtualSlot;

import java.util.function.IntSupplier;

/**
 * 三明治组装机菜单：有序输入格 32（8×4）+ 样品槽 + 材料区 27（9×3）+ 返还槽 3 + 输出槽 + 升级槽 3 + 能源槽。
 */
public class SandwichAssemblerMenu extends AbstractContainerMenu
        implements cn.ism.mekck.menu.ISideConfigurableMenu {

    public static final int IMAGE_WIDTH = 344;
    public static final int IMAGE_HEIGHT = 244;
    /** 槽位布局常量：屏幕的 GuiVirtualSlot 与本菜单的 addSlot 表达式同源引用。 */
    public static final int ORDERED_COLS = 8;
    public static final int MATERIAL_COLS = 9;

    private final SandwichAssemblerBlockEntity machine;

    /** 数据槽：0=模式，1=目标数量低 16 位，2=目标数量高 16 位，3=进度，4=总时长，5=侧面配置编码。 */
    private final ContainerData data = new ContainerData() {
        /**
         * 客户端镜像：{@code set} 写、{@code get} 读。
         *
         * <p>本仓的既定形态见 {@code SmartCookingPotBlockEntity} 的同名字段。这里必须有一份
         * 独立镜像，不能直接读客户端 BE：{@code ClientboundContainerSetDataPacket} 在客户端
         * 走的正是 {@code set}，而本方块在客户端<b>不注册 ticker、也没有 getUpdatePacket</b>
         * （{@code SandwichAssemblerBlock.getTicker} 客户端返回 null），BE 上那几个字段只在
         * 区块加载时被 {@code getUpdateTag} 写一次 —— 直接读它就是「整屏冻结在进区块那一刻」。</p>
         */
        private final int[] stored = new int[6];

        @Override
        public int get(int index) {
            // 客户端 BE 缺失（空菜单）时全部读 0：屏幕侧会读这些槽，
            // 不兜底就是「构造器不崩了、第一帧渲染崩」。
            if (machine == null) {
                return 0;
            }
            var level = machine.getLevel();
            if (level != null && level.isClientSide) {
                return index >= 0 && index < stored.length ? stored[index] : 0;
            }
            return switch (index) {
                case 0 -> machine.getMode();
                case 1 -> machine.getTargetCount() & 0xFFFF;
                case 2 -> (machine.getTargetCount() >> 16) & 0xFFFF;
                case 3 -> machine.getProgress();
                case 4 -> machine.totalProcessTime();
                case 5 -> machine.encodeSideConfig();
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            // 同步包在客户端走这里。服务端不调它，写进 stored 无副作用。
            if (index >= 0 && index < stored.length) {
                stored[index] = value;
            }
        }

        @Override
        public int getCount() {
            return 6;
        }
    };

    public SandwichAssemblerMenu(int containerId, Inventory playerInventory, SandwichAssemblerBlockEntity machine) {
        super(cn.ism.mekck.registry.MekCkStandaloneMachines.SANDWICH_ASSEMBLER_MENU.get(), containerId);
        this.machine = machine;
        // 空菜单（客户端 BE 缺失）用等长的空 handler 兜底：槽位数量与坐标必须照旧，
        // 否则客户端与服务端的槽位契约不一致；读取路径全部走 machine == null 分支。
        IItemHandler items = machine == null
                ? new ItemStackHandler(SandwichAssemblerBlockEntity.TOTAL_SLOTS)
                : machine.items;

        // 有序输入格 8×4
        for (int i = 0; i < SandwichAssemblerBlockEntity.ORDERED_SLOTS; i++) {
            addSlot(new InputSlot(items, SandwichAssemblerBlockEntity.ORDERED_START + i,
                    8 + (i % ORDERED_COLS) * 18, 20 + (i / ORDERED_COLS) * 18));
        }
        // 材料区 9×3
        for (int i = 0; i < SandwichAssemblerBlockEntity.MATERIAL_SLOTS; i++) {
            addSlot(new InputSlot(items, SandwichAssemblerBlockEntity.MATERIAL_START + i,
                    170 + (i % MATERIAL_COLS) * 18, 20 + (i / MATERIAL_COLS) * 18));
        }
        // 样品槽 / 输出槽 / 返还槽
        addSlot(new InputSlot(items, SandwichAssemblerBlockEntity.SAMPLE_SLOT, 170, 80));
        addSlot(new OutputSlot(items, SandwichAssemblerBlockEntity.OUTPUT_SLOT, 200, 80));
        for (int i = 0; i < SandwichAssemblerBlockEntity.RETURN_SLOTS; i++) {
            addSlot(new OutputSlot(items, SandwichAssemblerBlockEntity.RETURN_START + i, 240 + i * 18, 80));
        }
        // 升级槽
        addSlot(new UpgradeSlot(items, SandwichAssemblerBlockEntity.SLOT_SPEED_UPGRADE, 170, 104));
        addSlot(new UpgradeSlot(items, SandwichAssemblerBlockEntity.SLOT_ENERGY_UPGRADE, 170, 122));
        addSlot(new UpgradeSlot(items, SandwichAssemblerBlockEntity.SLOT_CREATIVE_UPGRADE, 170, 140));
        // 能源槽
        addSlot(new PowerSlot(items, SandwichAssemblerBlockEntity.SLOT_POWER, 310, 8));

        // 玩家背包 + 快捷栏
        int invY = 164;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, 9 + row * 9 + col, 8 + col * 18, invY + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, invY + 58));
        }

        addDataSlots(data);
    }

    /**
     * 客户端构造器：方块在 OpenScreen 到达前被破坏/替换、或区块被卸载时，
     * {@code getBlockEntity} 返回 null。旧写法直接强转后交给主构造器，
     * 主构造器第一行 {@code machine.items} 就 NPE 崩客户端。这里显式判空
     * （{@code instanceof} 同时挡掉类型不符），null 时构造「空菜单」：
     * 槽位数量与坐标照旧，所有读取走 {@code machine == null} 的兜底分支。
     */
    public SandwichAssemblerMenu(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
        this(containerId, playerInventory,
                playerInventory.player.level().getBlockEntity(buf.readBlockPos())
                        instanceof SandwichAssemblerBlockEntity machine ? machine : null);
    }

    private static cn.ism.mekck.SideMode decodeSide(int encoded, net.minecraft.core.Direction dir) {
        int ord = (encoded >> (dir.ordinal() * 2)) & 0x3;
        var values = cn.ism.mekck.SideMode.values();
        return (ord >= 0 && ord < values.length) ? values[ord] : cn.ism.mekck.SideMode.NONE;
    }

    @Override
    public cn.ism.mekck.SideMode getSideMode(net.minecraft.core.Direction direction) {
        return decodeSide(data.get(5), direction);
    }

    @Override
    public cn.ism.mekck.SideMode getFluidSideMode(net.minecraft.core.Direction direction) {
        return cn.ism.mekck.SideMode.NONE;
    }

    @Override
    public cn.ism.mekck.SideMode getGasSideMode(net.minecraft.core.Direction direction) {
        return cn.ism.mekck.SideMode.NONE;
    }

    @Override
    public net.minecraft.core.BlockPos getBlockPos() {
        // 空菜单没有真实坐标，返回 ZERO 而不是 NPE（同 GrillMenu 的 tile == null 写法）。
        return machine == null ? net.minecraft.core.BlockPos.ZERO : machine.getBlockPos();
    }

    @Override
    public boolean supportsStoragePull() {
        return false;
    }

    public SandwichAssemblerBlockEntity getMachine() {
        return machine;
    }

    public int getMode() {
        return data.get(0);
    }

    public int getTargetCount() {
        return (data.get(1) & 0xFFFF) | ((data.get(2) & 0xFFFF) << 16);
    }

    public int getProgress() {
        return data.get(3);
    }

    public int getTotalTime() {
        return data.get(4);
    }

    /** 进度比例（0~1）。 */
    public double getProgressRatio() {
        int total = getTotalTime();
        return total <= 0 ? 0.0 : Math.min(1.0, (double) getProgress() / total);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        int machineSlots = SandwichAssemblerBlockEntity.TOTAL_SLOTS;
        if (index < machineSlots) {
            if (!moveItemStackTo(stack, machineSlots, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            // 玩家背包 → 优先材料区。容量改用 handler 的槽位上限（本机除输出槽外都是超大堆叠），
            // 否则原版会用物品自身的 64 钳制，已堆到 64 的那一格 shift 就再也并不进去。
            // ⚠ 区间传的是 slots 里的**位置**，不是 handler 索引：本机添加序是 有序格 → 材料区
            // → 样品/输出/返还，而 MATERIAL_START(=33) 是 handler 索引（样品槽 32 排在它前面）
            // ⇒ 拿索引当位置传会漏掉材料区第一格、并把东西塞进样品槽。
            int materialStart = SandwichAssemblerBlockEntity.ORDERED_START
                    + SandwichAssemblerBlockEntity.ORDERED_SLOTS;
            if (!cn.ism.mekck.util.MekCkTransfer.moveItemStackTo(stack, slots, materialStart,
                    materialStart + SandwichAssemblerBlockEntity.MATERIAL_SLOTS, false)) {
                if (!cn.ism.mekck.util.MekCkTransfer.moveItemStackTo(stack, slots,
                        SandwichAssemblerBlockEntity.ORDERED_START,
                        SandwichAssemblerBlockEntity.ORDERED_START + SandwichAssemblerBlockEntity.ORDERED_SLOTS, false)) {
                    return ItemStack.EMPTY;
                }
            }
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        // 空菜单一律视为失效：服务端据此关闭窗口，客户端也不再接受交互。
        return machine != null && machine.getLevel() != null
                && player.distanceToSqr(machine.getBlockPos().getCenter()) <= 64.0;
    }

    // ── 轻量槽子类：实现 IVirtualSlot 供屏幕的 GuiVirtualSlot 绑定（Mek 体系标准接法，同
    // IceFactoryMenu / SmartCookingPotMenu 等）；mayPlace / mayPickup 全走 SlotItemHandler
    // 默认实现，与此前裸 SlotItemHandler 的行为逐字节一致 ──

    /** 输入类槽（有序格 / 材料区 / 样品槽）。 */
    private static final class InputSlot extends SlotItemHandler implements IVirtualSlot {
        private InputSlot(IItemHandler handler, int slot, int x, int y) {
            super(handler, slot, x, y);
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return getItemHandler().getSlotLimit(getContainerSlot());
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

    /** 输出 / 返还类槽。 */
    private static final class OutputSlot extends SlotItemHandler implements IVirtualSlot {
        private OutputSlot(IItemHandler handler, int slot, int x, int y) {
            super(handler, slot, x, y);
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

    /** 升级槽（速度 / 能量 / 创造）。 */
    private static final class UpgradeSlot extends SlotItemHandler implements IVirtualSlot {
        private UpgradeSlot(IItemHandler handler, int slot, int x, int y) {
            super(handler, slot, x, y);
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

    /** 能源槽。 */
    private static final class PowerSlot extends SlotItemHandler implements IVirtualSlot {
        private PowerSlot(IItemHandler handler, int slot, int x, int y) {
            super(handler, slot, x, y);
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
}

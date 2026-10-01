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
import net.minecraftforge.items.SlotItemHandler;
import cn.ism.mekck.registry.MekCkStandaloneMachines;

/**
 * 三明治组装机菜单：有序输入格 32（8×4）+ 样品槽 + 材料区 27（9×3）+ 返还槽 3 + 输出槽 + 升级槽 3 + 能源槽。
 */
public class SandwichAssemblerMenu extends AbstractContainerMenu
        implements cn.ism.mekck.menu.ISideConfigurableMenu {

    public static final int IMAGE_WIDTH = 344;
    public static final int IMAGE_HEIGHT = 244;
    private static final int ORDERED_COLS = 8;
    private static final int MATERIAL_COLS = 9;

    private final SandwichAssemblerBlockEntity machine;

    /** 数据槽：0=模式，1=目标数量低 16 位，2=目标数量高 16 位，3=进度，4=总时长，5=侧面配置编码。 */
    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
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
        }

        @Override
        public int getCount() {
            return 6;
        }
    };

    public SandwichAssemblerMenu(int containerId, Inventory playerInventory, SandwichAssemblerBlockEntity machine) {
        super(cn.ism.mekck.registry.MekCkStandaloneMachines.SANDWICH_ASSEMBLER_MENU.get(), containerId);
        this.machine = machine;
        IItemHandler items = machine.items;

        // 有序输入格 8×4
        for (int i = 0; i < SandwichAssemblerBlockEntity.ORDERED_SLOTS; i++) {
            addSlot(new SlotItemHandler(items, SandwichAssemblerBlockEntity.ORDERED_START + i,
                    8 + (i % ORDERED_COLS) * 18, 20 + (i / ORDERED_COLS) * 18));
        }
        // 材料区 9×3
        for (int i = 0; i < SandwichAssemblerBlockEntity.MATERIAL_SLOTS; i++) {
            addSlot(new SlotItemHandler(items, SandwichAssemblerBlockEntity.MATERIAL_START + i,
                    170 + (i % MATERIAL_COLS) * 18, 20 + (i / MATERIAL_COLS) * 18));
        }
        // 样品槽 / 输出槽 / 返还槽
        addSlot(new SlotItemHandler(items, SandwichAssemblerBlockEntity.SAMPLE_SLOT, 170, 80));
        addSlot(new SlotItemHandler(items, SandwichAssemblerBlockEntity.OUTPUT_SLOT, 200, 80));
        for (int i = 0; i < SandwichAssemblerBlockEntity.RETURN_SLOTS; i++) {
            addSlot(new SlotItemHandler(items, SandwichAssemblerBlockEntity.RETURN_START + i, 240 + i * 18, 80));
        }
        // 升级槽
        addSlot(new SlotItemHandler(items, SandwichAssemblerBlockEntity.SLOT_SPEED_UPGRADE, 170, 104));
        addSlot(new SlotItemHandler(items, SandwichAssemblerBlockEntity.SLOT_ENERGY_UPGRADE, 170, 122));
        addSlot(new SlotItemHandler(items, SandwichAssemblerBlockEntity.SLOT_CREATIVE_UPGRADE, 170, 140));
        // 能源槽
        addSlot(new SlotItemHandler(items, SandwichAssemblerBlockEntity.SLOT_POWER, 310, 8));

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

    public SandwichAssemblerMenu(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
        this(containerId, playerInventory,
                (SandwichAssemblerBlockEntity) playerInventory.player.level().getBlockEntity(buf.readBlockPos()));
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
        return machine.getBlockPos();
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
        return machine.getLevel() != null
                && player.distanceToSqr(machine.getBlockPos().getCenter()) <= 64.0;
    }
}

package cn.ism.mekck.menu;

import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.blockentity.SimpleMachineBlockEntity;
import cn.ism.mekck.config.MekckConfig;
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
import cn.ism.mekck.registry.MekCkLegacyMachines;

/** 四合一基础机器共用菜单（寿司卷制机/平均切段机/饭团成型机/凝乳成型机）。 */
public final class SimpleMachineMenu extends AbstractContainerMenu implements ISideConfigurableMenu, IUpgradeMenu {
    public static final int IMAGE_WIDTH = 220;
    public static final int IMAGE_HEIGHT = 184;
    public static final int INPUT_X = 16;
    public static final int INPUT_Y = 40;
    public static final int OUTPUT_X = 124;
    public static final int OUTPUT_Y = 40;
    public static final int INV_TOP = 96;

    // ===== winery 复刻 vinery 陈酿桶皮肤对位（仅 winery 且装了 vinery 时启用；否则用上面的通用坐标）=====
    // 槽位角色两套布局统一（0..2 配料 / 3 酒瓶·carrier / 4 弃用 / 5 产物 / 10 果汁 / 11 返还 / 12 流体物品），仅像素位置与背景不同。
    public static final int VINERY_IMAGE_W = 176;
    public static final int VINERY_IMAGE_H = 166;
    public static final int WV_JUICE_X = 39, WV_JUICE_Y = 17;    // 果汁格（瓶装→液位 ∪ 流体桶→inputTank，共用一格）
    public static final int WV_ING_X = 67, WV_ING_Y = 58;         // 配料 0..2（步长 18）
    public static final int WV_BOTTLE_X = 123, WV_BOTTLE_Y = 58;  // 酒瓶 / 酒馆 carrier（槽 3）
    public static final int WV_OUT_X = 103, WV_OUT_Y = 17;        // 产物
    /** 返还格：放配料行左侧（原 (123,17) 压在背景气泡装饰上，用户 2026-09-24 裁定改到配料格左边，后续可再调）。 */
    public static final int WV_RETURN_X = 49, WV_RETURN_Y = 58;
    /**
     * 电源格（简报 §九 2026-09-24）：vinery 皮肤下贴右侧能量条左边、与果汁格平齐。
     * <p><b>必须同时供菜单 addSlot 与 SimpleMachineScreen 的 GuiVirtualSlot 使用</b>：两处坐标一旦不一致，
     * {@code AbstractContainerScreen.renderSlots} 会在菜单坐标处多画一个**裸露的原版空槽框**
     * ——与升级槽当年「两个错误的原版格子」同一病根（见下方 L96 注释）；且 vinery 下电源格会点不到
     * （点击命中按 {@code Slot.x/y} 判）。机甲风沿用 Screen 侧一直在画的 (6,12)。
     */
    public static final int WV_POWER_X = 146, WV_POWER_Y = 17;
    // 原 WV_FLUID_*（流体物品输入格）已删除：流体桶与瓶装果汁共用果汁格，该格不再渲染（handler 索引 12 仍保留保 NBT 兼容）。

    private final SimpleMachineBlockEntity machine;
    private final ContainerData data;
    private boolean upgradePageActive = false;

    private final UpgradeSlot speedUpgradeSlot;
    private final UpgradeSlot energyUpgradeSlot;
    private final UpgradeSlot creativeUpgradeSlot;

    // ── 升级 / 能源 / 果汁槽的「菜单下标」（不是 handler 下标）──────────────
    //
    // 本菜单的 addSlot 顺序随机器类型变化：扩展输入槽机器（搅拌机 / 智能烤炉）把
    // handler 10..13 插在输入与输出之间，于是输出/升级/能源槽的**菜单下标整体比
    // handler 下标大 4**；陈酿机又在末尾追加果汁/返还/流体三格。
    // 因此 **不能用 handler 常量当菜单下标** 传给 moveItemStackTo：在扩展槽机器上
    // SLOT_SPEED_UPGRADE(=6) 落在菜单 6 = 扩展输入槽、SLOT_POWER(=9) 落在菜单 9 = 输出槽，
    // 两者的 mayPlace 都拒绝 ⇒ shift-click 静默失效（无提示、物品不动）。
    // 一律用构造期 slots.size() 现场捕获，三种布局自动正确。
    private final int speedSlotIndex;
    private final int energySlotIndex;
    private final int creativeSlotIndex;
    private final int powerSlotIndex;
    /** 陈酿机专用果汁格（handler 索引 JUICE_SLOT）的菜单下标；非陈酿机保持 -1。 */
    private int juiceSlotIndex = -1;

    public SimpleMachineMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory,
                (SimpleMachineBlockEntity) inventory.player.level().getBlockEntity(buffer.readBlockPos()),
                new SimpleContainerData(SimpleMachineBlockEntity.DATA_SIZE));
    }

    public SimpleMachineMenu(int containerId, Inventory inventory, SimpleMachineBlockEntity machine, ContainerData data) {
        super(MekCkLegacyMachines.SIMPLE_MACHINE_MENU.get(), containerId);
        this.machine = machine;
        this.data = data;

        // winery 复刻 vinery 时套用陈酿桶对位坐标；其余机器沿用通用输入行。
        boolean wl = vineryLayout();
        // 5 个输入槽（寿司机：0=底材、1..4=部件；其余机器用 0）
        for (int i = 0; i < SimpleMachineBlockEntity.INPUT_COUNT; i++) {
            int sx, sy;
            if (isWinery()) {
                // winery 统一角色：0..2 配料、3 酒瓶/carrier、4 弃用
                if (i <= 2) {
                    sx = (wl ? WV_ING_X : INPUT_X) + i * 18; sy = wl ? WV_ING_Y : INPUT_Y;
                } else if (i == SimpleMachineBlockEntity.WINERY_CARRIER_SLOT) {
                    sx = wl ? WV_BOTTLE_X : INPUT_X + 3 * 18; sy = wl ? WV_BOTTLE_Y : INPUT_Y;
                } else {
                    sx = -1000; sy = -1000;
                }
            } else {
                sx = INPUT_X + i * 18; sy = INPUT_Y;
            }
            addSlot(new InputSlot(machine.getItems(), i, sx, sy));
        }
        // 搅拌机/智能烤炉：扩展输入槽 10..13（第二行，第 6~9 个输入）
        if (machine.usesExtendedInputSlots()) {
            for (int i = 0; i < SimpleMachineBlockEntity.EXT_INPUT_COUNT; i++) {
                addSlot(new InputSlot(machine.getItems(), SimpleMachineBlockEntity.EXT_INPUT_START + i,
                        INPUT_X + i * 18, INPUT_Y + 18));
            }
        }
        addSlot(new OutputSlot(machine.getItems(), SimpleMachineBlockEntity.OUTPUT_SLOT,
                wl ? WV_OUT_X : OUTPUT_X, wl ? WV_OUT_Y : OUTPUT_Y));

        // 升级槽（仅升级弹窗打开时可用）：构造坐标故意放屏幕外（-1000），避免主界面 AbstractContainerScreen.renderSlots
        // 在真实坐标处画出两个裸露的原版槽框（之前“两个错误的原版格子”即来源于此）。
        // 升级弹窗内由 GuiUpgradeWindow.selectedSlot.updateVirtualSlot → IVirtualSlot.updatePosition 把 actualX/Y 重定位到窗口内，
        // 渲染与点击命中均走 actualX/Y（与这里的 x/y 无关），故不影响升级功能。
        this.speedSlotIndex = slots.size();
        this.speedUpgradeSlot = new UpgradeSlot(machine.getItems(), SimpleMachineBlockEntity.SLOT_SPEED_UPGRADE, -1000, -1000, this);
        addSlot(this.speedUpgradeSlot);
        this.energySlotIndex = slots.size();
        this.energyUpgradeSlot = new UpgradeSlot(machine.getItems(), SimpleMachineBlockEntity.SLOT_ENERGY_UPGRADE, -1000, -1000, this);
        addSlot(this.energyUpgradeSlot);

        // 创造升级槽：与速度/能量升级槽完全一致——主界面不常显，坐标放屏外，仅升级页选中时由 selectedSlot 重定位绘制；不可取出，靠升级页卸载按钮卸下。
        this.creativeSlotIndex = slots.size();
        this.creativeUpgradeSlot = new UpgradeSlot(machine.getItems(), SimpleMachineBlockEntity.SLOT_CREATIVE_UPGRADE, -1000, -1000, this);
        addSlot(this.creativeUpgradeSlot);

        // 能源槽（能量物品）：坐标与 SimpleMachineScreen 的 GuiVirtualSlot 完全一致（见 WV_POWER_* 注释），
        // 否则主界面会在旧位 (7,13) 残留一个裸露的原版空槽框。
        this.powerSlotIndex = slots.size();
        addSlot(new PowerSlot(machine.getItems(), SimpleMachineBlockEntity.SLOT_POWER,
                wl ? WV_POWER_X : 6, wl ? WV_POWER_Y : 12));

        // 陈酿机：专用果汁格（handler 索引 JUICE_SLOT=10）——坐标必须与 SimpleMachineScreen 里果汁格渲染位置一致，
        // 因为 AbstractContainerScreen 的点击命中按 Slot.x/y 判定（GuiVirtualSlot 只负责画图标），坐标错一者就点不到。
        if (isWinery()) {
            // 果汁格（瓶装→液位 ∪ 流体桶→inputTank，共用一格）：vinery 对位 (39,17)，否则沿用通用第二行首格
            this.juiceSlotIndex = slots.size();
            addSlot(new InputSlot(machine.getItems(), SimpleMachineBlockEntity.JUICE_SLOT,
                    wl ? WV_JUICE_X : INPUT_X, wl ? WV_JUICE_Y : INPUT_Y + 18));
            // 返还槽（只出不进）：vinery 对位放配料行左侧，否则产物正下方
            addSlot(new OutputSlot(machine.getItems(), SimpleMachineBlockEntity.RETURN_SLOT,
                    wl ? WV_RETURN_X : OUTPUT_X, wl ? WV_RETURN_Y : OUTPUT_Y + 18));
            // 流体物品输入格：已废弃（与果汁格共用），坐标移出屏外使其不渲染、不可点击；索引保留保 NBT 兼容。
            addSlot(new InputSlot(machine.getItems(), SimpleMachineBlockEntity.FLUID_ITEM_SLOT,
                    -1000, -1000));
        }

        // 玩家物品栏（居中）；winery 套 vinery 皮肤时按 176×166 框架对位（与 vinery 原生一致：inv 左上角 (8,84)）。
        int frameW = wl ? VINERY_IMAGE_W : IMAGE_WIDTH;
        int invLeft = wl ? 8 : (frameW - 162) / 2;
        int invTop = wl ? 84 : INV_TOP;
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
        // 机器槽数量：基础 10 槽（5 输入 + 输出 + 2 升级 + 创造 + 能源）+ 搅拌机扩展 4 输入槽 + 陈酿机果汁格/返还格/流体格 3 槽
        int machineSlotCount = SimpleMachineBlockEntity.TOTAL_SLOTS
                - (usesExtendedSlots() ? 0 : SimpleMachineBlockEntity.EXT_INPUT_COUNT)
                + (isWinery() ? 3 : 0);
        if (index < machineSlotCount) {
            if (!moveItemStackTo(stack, machineSlotCount, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            // 四个目标一律用构造期捕获的**菜单下标**：扩展槽机器上 handler 下标整体偏移 +4，
            // 用常量会把升级卡/能源物品指向扩展输入槽或输出槽 ⇒ shift-click 静默无效。
            if (SimpleMachineBlockEntity.isUsablePowerItem(stack)) {
                if (!moveItemStackTo(stack, powerSlotIndex, powerSlotIndex + 1, false)) return ItemStack.EMPTY;
            } else if (cn.ism.mekck.upgrade.UpgradeHelper.isSpeedUpgrade(stack)) {
                if (!moveItemStackTo(stack, speedSlotIndex, speedSlotIndex + 1, false)) return ItemStack.EMPTY;
            } else if (cn.ism.mekck.upgrade.UpgradeHelper.isEnergyUpgrade(stack)) {
                if (!moveItemStackTo(stack, energySlotIndex, energySlotIndex + 1, false)) return ItemStack.EMPTY;
            } else if (cn.ism.mekck.upgrade.UpgradeHelper.isCreativeUpgrade(stack)) {
                if (!moveItemStackTo(stack, creativeSlotIndex, creativeSlotIndex + 1, false)) return ItemStack.EMPTY;
            } else if (usesExtendedSlots() && MekCkTransfer.moveItemStackTo(stack, slots,
                    0, SimpleMachineBlockEntity.INPUT_COUNT, false)) {
                // 启用扩展槽的机器：先试常规输入槽（slots 里的位置 0..4，与 handler 索引一致）
            } else if (usesExtendedSlots() && MekCkTransfer.moveItemStackTo(stack, slots,
                    SimpleMachineBlockEntity.INPUT_COUNT,
                    SimpleMachineBlockEntity.INPUT_COUNT + SimpleMachineBlockEntity.EXT_INPUT_COUNT, false)) {
                // 再试扩展槽：它们在 slots 里紧跟常规输入槽之后（位置 5..8）。
                // 早先这里直接用 handler 索引 EXT_INPUT_START(10)..13 当范围，命中的其实是升级/创造/能源槽，
                // 于是常规输入槽一满，shift 就整块无处可去。位置与索引两套编号在此不重合，只能按位置算。
            } else if (!usesExtendedSlots()) {
                // 常规输入槽 0..4；陈酿机再补试专用果汁格与流体物品输入格
                boolean placed = MekCkTransfer.moveItemStackTo(stack, slots, 0, SimpleMachineBlockEntity.INPUT_COUNT, false);
                if (!placed && isWinery()) {
                    // 果汁格走构造期捕获的菜单下标（陈酿机上恰好等于 handler 索引 10，但不写死）
                    placed = MekCkTransfer.moveItemStackTo(stack, slots,
                            juiceSlotIndex, juiceSlotIndex + 1, false);
                }
                // 流体桶已与果汁格（JUICE_SLOT）共用一格，不再单独 shift-click 到已废弃的 FLUID_ITEM_SLOT（否则会被隐形槽吞掉）。
                if (!placed) return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }

    // ================== 数据访问 ==================
    public int getProgress() {
        int maximum = data.get(SimpleMachineBlockEntity.DATA_PROCESS_TIME);
        return maximum == 0 ? 0 : data.get(SimpleMachineBlockEntity.DATA_PROGRESS) * 24 / maximum;
    }

    /**
     * 当前储能量 —— 从<b>两个</b>槽（{@link SimpleMachineBlockEntity#DATA_ENERGY} 低 16 位 +
     * {@link SimpleMachineBlockEntity#DATA_ENERGY_HI} 高 16 位）合并回来。
     *
     * <p>此前是裸的 {@code data.get(DATA_ENERGY)}，而该通道经
     * {@code ClientboundContainerSetDataPacket} 只 {@code writeShort}（16 位有符号）——
     * 能量一过 32767，客户端拿到的就是 {@code (short)0x86A0 = -31072}，
     * 于是能源条 {@code getLevel()} 为负、条纹为空、tooltip 显示负的 FE。
     * 拆槽是无损的，见 {@link cn.ism.mekck.util.WideDataSlot}。</p>
     */
    public int getEnergy() {
        return WideDataSlot.read(data,
                SimpleMachineBlockEntity.DATA_ENERGY,
                SimpleMachineBlockEntity.DATA_ENERGY_HI);
    }

    public int getEnergyCapacity() {
        return SimpleMachineBlockEntity.ENERGY_CAPACITY;
    }

    public int getEnergyPerTick() {
        return machine.getEnergyPerTickBase();
    }

    /** 该机器是否支持勾选持续自动补料（简单配方机器）。 */
    public boolean supportsAutoPull() {
        return machine instanceof cn.ism.mekck.ae2.INetworkPullable && ((cn.ism.mekck.ae2.INetworkPullable) machine).supportsAutoPull();
    }

    /** 是否为加热类机器（GUI 显示机身温度）。 */
    public boolean isHeatingMachine() {
        return machine.isHeatingMachine();
    }

    /** 机身温度（单位 0.01 ℃）。 */
    public int getTemperature() {
        return data.get(SimpleMachineBlockEntity.DATA_TEMPERATURE);
    }

    /** 陈酿机：果汁液位（0~100；非陈酿机恒为 0）。 */
    public int getJuiceLevel() {
        return data.get(SimpleMachineBlockEntity.DATA_JUICE_LEVEL);
    }

    /** 陈酿机：果汁类型编号（VineryJuice.TYPES 下标；-1 = 空桶）。 */
    public int getJuiceTypeIndex() {
        return data.get(SimpleMachineBlockEntity.DATA_JUICE_TYPE);
    }

    /** 该机器的果汁类型（客户端由编号还原）；非陈酿机为空串。 */
    public String getJuiceType() {
        return cn.ism.mekck.util.VineryJuice.typeAt(getJuiceTypeIndex());
    }

    /** 是否为陈酿机（GUI 显示果汁液位条）。 */
    public boolean isWinery() {
        return machine.getMachineKind() == cn.ism.mekck.MachineKind.WINERY;
    }

    /** winery 是否套用 vinery 陈酿桶皮肤对位（kind==WINERY 且已装 vinery）。 */
    public boolean vineryLayout() {
        return isWinery() && net.minecraftforge.fml.ModList.get().isLoaded("vinery");
    }

    /** winery 内部 inputTank 当前流体（供液位条 widget 绘制）；非 winery 为空。 */
    public net.minecraftforge.fluids.FluidStack getFluidStack() {
        if (!isWinery()) return net.minecraftforge.fluids.FluidStack.EMPTY;
        // 不能读 machine.getInputTank().getFluid()：本机器没覆写任何 BE 同步，客户端那份 inputTank
        // 只在区块加载时随存档到达一次，之后服务端怎么抽都不更新 ⇒ 永远画为空。
        // 所以种类与量都从 ContainerData 重建（服务端现算、客户端收同步，两端口径一致）。
        int amount = data.get(SimpleMachineBlockEntity.DATA_INPUT_FLUID);
        if (amount <= 0) return net.minecraftforge.fluids.FluidStack.EMPTY;
        net.minecraft.world.level.material.Fluid fluid = net.minecraft.core.registries.BuiltInRegistries.FLUID
                .byId(data.get(SimpleMachineBlockEntity.DATA_INPUT_FLUID_ID));
        if (fluid == null || fluid == net.minecraft.world.level.material.Fluids.EMPTY) {
            return net.minecraftforge.fluids.FluidStack.EMPTY;
        }
        return new net.minecraftforge.fluids.FluidStack(fluid, amount);
    }

    /** winery 内部 inputTank 容量（mb）。 */
    public int getFluidCapacity() {
        return isWinery() ? machine.getInputTank().getCapacity() : 0;
    }

    /** 是否启用扩展输入槽（10..13）：搅拌机 / 智能烤炉。 */
    public boolean usesExtendedSlots() {
        return machine.usesExtendedInputSlots();
    }

    public int getEncodedSideConfig() {
        // 24-bit 侧配拆两槽，裸读低槽会丢 WEST/EAST 两面，见 WideDataSlot。
        return WideDataSlot.read(data,
                SimpleMachineBlockEntity.DATA_SIDE_CONFIG,
                SimpleMachineBlockEntity.DATA_SIDE_CONFIG_HI);
    }

    @Override
    public SideMode getSideMode(Direction direction) {
        int encoded = getEncodedSideConfig();
        int ordinal = (encoded >> (direction.ordinal() * 4)) & 0xF;
        SideMode[] values = SideMode.values();
        return (ordinal >= 0 && ordinal < values.length) ? values[ordinal] : SideMode.NONE;
    }

    /** 流体侧面配置（独立于物品侧配）。 */
    public SideMode getFluidSideMode(Direction direction) {
        int encoded = WideDataSlot.read(data,
                SimpleMachineBlockEntity.DATA_FLUID_SIDE_CONFIG,
                SimpleMachineBlockEntity.DATA_FLUID_SIDE_CONFIG_HI);
        int ordinal = (encoded >> (direction.ordinal() * 4)) & 0xF;
        SideMode[] values = SideMode.values();
        return (ordinal >= 0 && ordinal < values.length) ? values[ordinal] : SideMode.NONE;
    }

    public int getRedstoneControl() {
        return data.get(SimpleMachineBlockEntity.DATA_REDSTONE_CONTROL);
    }

    /** 机器实例（客户端也持有，槽位内容由菜单同步 ⇒ 可用于本机下单列表）。 */
    public cn.ism.mekck.blockentity.SimpleMachineBlockEntity getMachine() {
        return machine;
    }

    public BlockPos getBlockPos() {
        return machine.getBlockPos();
    }

    // ================== IUpgradeMenu ==================
    public int getSpeedUpgradeCount() {
        return data.get(SimpleMachineBlockEntity.DATA_SPEED_UPGRADE);
    }

    public int getEnergyUpgradeCount() {
        return data.get(SimpleMachineBlockEntity.DATA_ENERGY_UPGRADE);
    }

    /** 升级安装读条进度（0~1，供升级窗口进度条）。 */
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

    public double getUpgradeInstallProgress() {
        return data.get(SimpleMachineBlockEntity.DATA_UPGRADE_PROGRESS) / 100.0;
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
    public boolean hasCreativeUpgrade() {
        return true;
    }

    @Override
    public Slot getCreativeUpgradeSlot() {
        return creativeUpgradeSlot;
    }

    @Override
    public int getCreativeUpgradeCount() {
        ItemStack s = creativeUpgradeSlot.getItem();
        return (!s.isEmpty() && cn.ism.mekck.upgrade.UpgradeHelper.isCreativeUpgrade(s)) ? 1 : 0;
    }

    @Override
    public void setUpgradePageActive(boolean active) {
        this.upgradePageActive = active;
    }

    @Override
    public boolean isUpgradePageActive() {
        return this.upgradePageActive;
    }

    // ================== 槽位类 ==================

    private static final class InputSlot extends SlotItemHandler implements IVirtualSlot {
        private InputSlot(ItemStackHandler handler, int slot, int x, int y) {
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

    private static final class MachineSlot extends SlotItemHandler implements IVirtualSlot {
        private MachineSlot(ItemStackHandler handler, int slot, int x, int y) {
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

    private static final class UpgradeSlot extends SlotItemHandler implements IVirtualSlot {
        /** 升级窗口未打开时把渲染位置移出屏幕：主屏便既不绘制、也命中不到它
         *  （Mek 的 VirtualSlotContainerScreen 渲染与 isMouseOverSlot 都走 getActualX/Y）。
         *  刻意<b>不改 isActive()</b> —— 那是槽的语义标志（服务端 mayPlace/转移逻辑依赖它），
         *  为了纯视觉的布局问题去改写它风险过大。 */
        private static final int HIDDEN_POS = -9999;

        private final SimpleMachineMenu menu;
        private IGUIWindow linkedWindow;
        private int actualX, actualY;
        private ItemStack stackToRender = ItemStack.EMPTY;
        private boolean overlay;
        private String tooltip;

        private UpgradeSlot(ItemStackHandler handler, int slot, int x, int y, SimpleMachineMenu menu) {
            super(handler, slot, x, y);
            this.menu = menu;
            this.actualX = x;
            this.actualY = y;
        }

        @Override
        public boolean mayPickup(Player player) {
            // 升级槽（速度/能量/创造）一律不可直接取出；靠升级界面的卸载按钮卸下。
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

    private static final class PowerSlot extends SlotItemHandler implements IVirtualSlot {
        private PowerSlot(ItemStackHandler handler, int slot, int x, int y) {
            super(handler, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return SimpleMachineBlockEntity.isUsablePowerItem(stack);
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

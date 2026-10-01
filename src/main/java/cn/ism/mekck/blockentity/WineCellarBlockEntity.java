package cn.ism.mekck.blockentity;

import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.menu.WineCellarMenu;
import cn.ism.mekck.util.PowerSlotUtil;
import cn.ism.mekck.util.WineAgeCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import cn.ism.mekck.registry.MekCkStandaloneMachines;

/**
 * 陈化窖（时间悖论产生器，F20）方块实体：独立<b>容器</b>方块，非配方加工机。
 * <ul>
 *   <li>9 个储存格，<b>普通 64 堆叠</b>（{@link ItemStackHandler}，绝不接本仓极端堆叠 ⇒ 不同年份靠 NBT tag 天然不堆）；</li>
 *   <li>无输入/输出槽、<b>不耗气</b>、不支持升级槽；§F45 起有<b>电源槽</b>（能量物品/红石入机充能，同急冻制冰机口径）；</li>
 *   <li>玩家 GUI 选倍速 S∈[1,50]（存 NBT），<b>§F47 日粒度计时</b>：进度条每跑满一次 = 陈化 +1 游戏日，
 *       攒满 24 日（vinery 1 年）才把参与陈化的每格酒 Year 往过去推 1；</li>
 *   <li>耗电线性 {@code FE/t = 62.5 × 数量 × S}（校准点 64瓶@10×=40,000）；供能不足按 {@code rate=实抽/应抽} 降速；</li>
 *   <li>某格 {@code Year} 已到地板（推无可推）⇒ <b>停该格运行并停该格耗能</b>，不白烧；未装 vinery 全格 fail-safe。</li>
 * </ul>
 */
public final class WineCellarBlockEntity extends MekCkLegacyMachine implements MenuProvider {
    public static final int SLOT_COUNT = 9;
    /** §F45：电源槽（能量物品/红石充能），handler 索引 = 9 储存格之后。 */
    public static final int SLOT_POWER = 9;
    public static final int TOTAL_SLOTS = 10;

    /** §F47：一圈 = 1 游戏日（vinery 1 日 = 24,000 tick；@1× = 24,000t，除倍速即得各档）。 */
    private static final long TICKS_PER_WINE_DAY_BASE = 24_000L;
    /** vinery 日历：1 游戏年 = 24 游戏日（WineYears.DAYS_PER_YEAR，jar 字节码实锤）。 */
    private static final int DAYS_PER_WINE_YEAR = 24;
    /** 每满 24 日（=1 年）前推年数 K。§F47：进度条每跑满一次 = age +1 天，攒满 24 天 = Year−1（+1 游戏年）。 */
    private static final int YEARS_PER_CYCLE = 1;

    // 能量：容量/抽能数学用 long 计算，但 FE 存储本身用 Forge int（容量 4 亿 ≪ int 上限，满架@50×=1.8M FE/t）。
    public static final int ENERGY_CAPACITY = 400_000_000;
    /**
     * 外部管线/电源槽物品单侧最大输入速率。§F47：200k→1M——旧值恰等于满架@50×最大耗电
     * (62.5×64×50=200,000 FE/t)，Forge receiveEnergy 按此 clamp ⇒ 创造能量立方/线缆供能
     * 与耗电阻尼成零和拉扯「带不动」（瓶颈是自家 clamp，非 Mekanism）。5× 余量。
     */
    public static final int MAX_RECEIVE = 1_000_000;

    // ContainerData 索引
    public static final int DATA_ENERGY = 0;
    public static final int DATA_CAPACITY = 1;
    public static final int DATA_SPEED = 2;
    public static final int DATA_ACTIVE = 3;      // 本刻正在陈化的格数（闪电弧强度，见 §F20⑤）
    public static final int DATA_PROGRESS0 = 4;   // 4..12 = 每格进度百分比 0..100（-1 = 空格/非酒）；§F46 后在陈化格同源同值
    /**
     * {@link #DATA_ENERGY} 的<b>高 16 位</b> —— 能量被拆成两个槽传输。
     *
     * <p>原版 {@code ContainerData} 走 {@code ClientboundContainerSetDataPacket}，
     * 对每个值只 {@code writeShort}（16 位有符号），而本机容量是
     * {@link #ENERGY_CAPACITY} = 4 亿 ⇒ 不拆必然截断成负数。
     * 详见 {@link cn.ism.mekck.util.WideDataSlot}。</p>
     *
     * <p>取值 = 旧 {@code DATA_SIZE}，即<b>追加</b>到槽表末尾：现有下标一律不动
     * （移位会静默读到别的量，比现在更糟）。</p>
     */
    public static final int DATA_ENERGY_HI = 13;
    public static final int DATA_SIZE = 14;

    private final ItemStackHandler items = new ItemStackHandler(TOTAL_SLOTS) {
        @Override
        protected int getStackLimit(int slot, @NotNull ItemStack stack) {
            return 64; // 普通堆叠：钉死 64，不走极端堆叠（保证不同年份 NBT tag 不并格）
        }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            // 9 储存格箱子式来者不拒；电源槽只收能量物品/红石（PowerSlotUtil 与 Mekanism 行为对齐）
            if (slot == SLOT_POWER) return PowerSlotUtil.isValidEnergyItem(stack);
            return true;
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    /** 内部抽能：绕过对外 maxExtract（对外不可抽，仅供本机催陈耗电）。 */
    private static final class CellarEnergy extends EnergyStorage {
        CellarEnergy(int capacity, int maxReceive) {
            super(capacity, maxReceive, 0);
        }

        @Override
        public boolean canReceive() {
            return true;
        }

        @Override
        public boolean canExtract() {
            return false; // 外部管线只能充、不能抽
        }

        /** 内部扣能（不检查对外可抽取），返回实际扣除量。 */
        int drainInternal(long want) {
            int take = (int) Math.max(0L, Math.min(want, (long) energy));
            if (take > 0) {
                energy -= take;
            }
            return take;
        }

        void setStored(int value) {
            this.energy = Math.max(0, Math.min(value, capacity));
        }
    }

    /** 能量容器由基类持有；本机需要「内部扣能、对外只充」语义，覆写 createEnergyStorage 换容器类。 */
    private final LazyOptional<IItemHandler> itemCapability = LazyOptional.of(() -> items);

    @Override
    protected EnergyStorage createEnergyStorage(int capacity, int maxReceive) {
        return new CellarEnergy(capacity, maxReceive);
    }

    /**
     * 本机<b>没有红石功能</b>（陈化窖只有电源槽，不受红石控制）—— 覆写以免基类
     * 往存档里凭空写 {@code RedstoneControl} / {@code RedstonePowered} 两个键。 */
    @Override
    protected boolean usesRedstone() {
        return false;
    }

    /** 带类型的能量容器视图 —— 基类的 {@code energy} 字段声明为 {@link EnergyStorage}，
     *  而 {@code drainInternal} 只存在于本机的 {@link CellarEnergy} 上。 */
    private CellarEnergy cellarEnergy() {
        return (CellarEnergy) energy;
    }

    /** 玩家选定倍速 1..50（默认 = 最低 = 1）。 */
    private int speedSetting = 1;
    /** §F46：全局统一陈化进度（单位 tick，浮点以支持降速打折）——跑满 ticksPerDay = +1 天。 */
    private float globalProgress;
    /** §F47：本年内已累计的陈化天数 0..23，攒满 24 天 = 推 Year−1。 */
    private int agedDays;
    /** 本刻正在陈化的格数（服务端算，经 ContainerData 同步给闪电弧）。 */
    private int activeCount;

    private final ContainerData data = new ContainerData() {
        private final int[] stored = new int[DATA_SIZE];

        @Override
        public int get(int index) {
            if (level != null && level.isClientSide) {
                return index >= 0 && index < stored.length ? stored[index] : 0;
            }
            int value = switch (index) {
                // 能量拆两槽：writeShort 只送低 16 位且会符号扩展，见 WideDataSlot。
                case DATA_ENERGY -> energy.getEnergyStored() & 0xFFFF;
                case DATA_ENERGY_HI -> (energy.getEnergyStored() >>> 16) & 0xFFFF;
                case DATA_CAPACITY -> ENERGY_CAPACITY;
                case DATA_SPEED -> speedSetting;
                case DATA_ACTIVE -> activeCount;
                default -> {
                    int slot = index - DATA_PROGRESS0;
                    yield (slot >= 0 && slot < SLOT_COUNT) ? progressPercent(slot) : 0;
                }
            };
            if (index >= 0 && index < stored.length) stored[index] = value;
            return value;
        }

        @Override
        public void set(int index, int value) {
            if (index >= 0 && index < stored.length) stored[index] = value;
        }

        @Override
        public int getCount() {
            return DATA_SIZE;
        }
    };

    public WineCellarBlockEntity(BlockPos pos, BlockState state) {
        super(MekCkStandaloneMachines.WINE_CELLAR_BLOCK_ENTITY.get(), pos, state,
                ENERGY_CAPACITY, MAX_RECEIVE);
    }

    public ItemStackHandler getItems() {
        return items;
    }

    public ContainerData getData() {
        return data;
    }

    public int getSpeed() {
        return speedSetting;
    }

    /** 设定倍速：钳制到 [1,50]，越界回退默认 1。 */
    public void setSpeed(int s) {
        int clamped = Math.max(1, Math.min(50, s));
        if (clamped != speedSetting) {
            speedSetting = clamped;
            setChanged();
        }
    }

    public int getEnergyStored() {
        return energy.getEnergyStored();
    }

    public int getEnergyCapacity() {
        return ENERGY_CAPACITY;
    }

    // ================== 电源槽（§F45） ==================

    public int getPowerSlot() {
        return SLOT_POWER;
    }

    /** 从电源槽物品抽能/烧红石入机（同急冻制冰机口径）。 */
    public boolean drainPowerSlot() {
        ItemStack powerStack = items.getStackInSlot(SLOT_POWER);
        return PowerSlotUtil.drain(powerStack, energy, PowerSlotUtil.REDSTONE_PER_TICK);
    }

    public static boolean isUsablePowerItem(ItemStack stack) {
        return PowerSlotUtil.isValidEnergyItem(stack);
    }

    /** 某格陈化进度百分比 0..100；空格/非酒返回 -1。§F46：全局统一 ⇒ 在陈化格皆同一值。 */
    public int progressPercent(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) return 0;
        ItemStack st = items.getStackInSlot(slot);
        if (st.isEmpty() || !WineAgeCompat.hasWineAge(st)) return -1;
        long ticksPerDay = TICKS_PER_WINE_DAY_BASE / Math.max(1, speedSetting);
        return (int) Math.min(100L, (long) (globalProgress * 100f / ticksPerDay));
    }

    // ================== tick ==================

    public static void serverTick(Level level, BlockPos pos, BlockState state, WineCellarBlockEntity tile) {
        // §F45：先由电源槽充能再陈化，同 tick 内能量即可用
        if (tile.drainPowerSlot()) tile.setChanged();
        tile.tickAging();
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, WineCellarBlockEntity tile) {
        // 客户端无逻辑（进度/能量经 ContainerData 同步）
    }

    /**
     * §F47：全局统一计时——进度条每跑满一次 = 陈化 +1 游戏日；攒满 24 日（=1 游戏年）才对
     * 参与陈化的每格酒推 Year−1（vinery Year 是 int 年、日粒度物理不可推，见 WineAgeCompat）。
     * 耗电仍是全局 Σ 62.5×数量×S（供能不足按 rate 降速）；封顶格剔除、不白烧；空闲容器早退。
     */
    private void tickAging() {
        int s = Math.max(1, Math.min(50, speedSetting));
        long ticksPerDay = TICKS_PER_WINE_DAY_BASE / s;
        // 先收集可陈化格（有酒 + 未封顶）与应抽能耗
        java.util.List<Integer> eligible = new java.util.ArrayList<>();
        long need = 0;
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            ItemStack st = items.getStackInSlot(slot);
            if (st.isEmpty() || !WineAgeCompat.hasWineAge(st)) continue;
            if (WineAgeCompat.isAgedOut(st, level)) continue; // 效果封顶（§F44）：不计时不耗能
            eligible.add(slot);
            need += 625L * st.getCount() * s / 10; // = 62.5 × 数量 × S，定点避免浮点误差
        }
        activeCount = 0;
        if (eligible.isEmpty() || need <= 0) {
            return;
        }
        int drained = cellarEnergy().drainInternal(need);
        if (drained <= 0) {
            return; // 完全没电：全局暂停（不清进度）
        }
        float rate = (float) drained / (float) need; // [0,1]，供能不足自动降速
        activeCount = eligible.size();
        boolean dirty = false;
        globalProgress += rate;
        while (globalProgress >= ticksPerDay && !eligible.isEmpty()) {
            globalProgress -= ticksPerDay; // 进度条跑满一次 = 1 个陈化日
            agedDays++;
            if (agedDays < DAYS_PER_WINE_YEAR) {
                continue; // 未满一年：只记日，不动 Year（vinery 粒度所限）
            }
            agedDays = 0; // 攒满 24 日 = 1 游戏年：统一推年
            for (java.util.Iterator<Integer> it = eligible.iterator(); it.hasNext(); ) {
                int slot = it.next();
                ItemStack aged = WineAgeCompat.accelerate(items.getStackInSlot(slot), YEARS_PER_CYCLE);
                items.setStackInSlot(slot, aged);
                if (WineAgeCompat.isAgedOut(aged, level)) {
                    it.remove(); // 本轮推至封顶：后续轮次不再为其推年
                }
            }
            dirty = true;
        }
        if (dirty) setChanged();
    }

    // ================== 存档 ==================

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Items", items.serializeNBT());
        // Energy / RedstoneControl 由 MekCkLegacyMachine.saveAdditional 统一写。
        tag.putInt("Speed", speedSetting);
        tag.putFloat("Progress", globalProgress); // §F46：单值全局进度（替旧逐格 Prog0..8）
        tag.putInt("AgedDays", agedDays);         // §F47：年内已累计陈化日 0..23
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("Items")) {
            items.deserializeNBT(tag.getCompound("Items"));
            // §F45：旧档 9 槽 → 10 槽（+电源槽）扩容迁移（同急冻制冰机模板）
            if (items.getSlots() != TOTAL_SLOTS) {
                net.minecraft.nbt.ListTag oldList = tag.getCompound("Items").getList("Items",
                        net.minecraft.nbt.Tag.TAG_COMPOUND);
                net.minecraft.nbt.ListTag newList = new net.minecraft.nbt.ListTag();
                for (int i = 0; i < oldList.size(); i++) {
                    CompoundTag itemTags = oldList.getCompound(i);
                    int slot = itemTags.getInt("Slot");
                    if (slot >= 0 && slot < TOTAL_SLOTS) newList.add(itemTags);
                }
                CompoundTag newTag = new CompoundTag();
                newTag.putInt("Size", TOTAL_SLOTS);
                newTag.put("Items", newList);
                items.deserializeNBT(newTag);
            }
        }
        // 能量与红石由 MekCkLegacyMachine.load 统一读。
        setSpeedInternal(tag.getInt("Speed"));
        // §F46：全局进度；旧档逐格 Prog0..8 取最大值迁移（多格同起时各格进度本就近似）
        if (tag.contains("Progress")) {
            globalProgress = tag.getFloat("Progress");
        } else {
            float max = 0f;
            for (int i = 0; i < SLOT_COUNT; i++) {
                if (tag.contains("Prog" + i)) max = Math.max(max, tag.getFloat("Prog" + i));
            }
            globalProgress = max;
        }
        // §F47：旧档（一圈=1年）进度按新周期取模无缝衔接（多出的整圈视作刚推完年、清零）
        long ticksPerDay = TICKS_PER_WINE_DAY_BASE / Math.max(1, speedSetting);
        if (ticksPerDay > 0) globalProgress %= ticksPerDay;
        agedDays = tag.getInt("AgedDays");
    }

    /** load 期不设脏标志地应用倍速。 */
    private void setSpeedInternal(int s) {
        this.speedSetting = Math.max(1, Math.min(50, s == 0 ? 1 : s));
    }

    // ================== 能力 ==================

    @Override
    protected <T> LazyOptional<T> exposeItemCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) return itemCapability.cast();
        return super.exposeItemCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        itemCapability.invalidate();
    }

    // ================== MenuProvider ==================

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.mekck.wine_cellar");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new WineCellarMenu(containerId, inventory, this);
    }
}

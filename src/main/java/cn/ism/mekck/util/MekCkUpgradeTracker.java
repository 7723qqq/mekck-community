package cn.ism.mekck.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * 升级安装读条（复刻 Mekanism TileComponentUpgrade 的语义）：
 * 槽位放入升级物品后，每 tick 推进 1 点进度，达到 {@link #TICKS_REQUIRED}（20 tick = 1 秒）后
 * 把槽位内数量一次性安装（受上限约束），槽位相应扣除；已安装数量独立于槽位保存。
 *
 * 与 Mekanism 一致的关键点：
 * - 升级效果以「已安装数量」为准，而非槽位物品数量；
 * - 读条期间物品仍在槽位中，可被取出（取出则进度清零）；
 * - 已安装数量随机器 NBT 持久化。
 *
 * <p><b>上限是惰性求值的</b>（{@link IntSupplier}），而不是构造时固化的 int。原因是
 * 方块实体那边存在初始化顺序约束：{@code tier} 在构造器体内赋值，而读条对象是实例
 * 字段初始化器（先于构造器体执行），所以初始化器里<b>不能</b>读 {@code tier}。
 * 惰性求值顺带让 {@code /reload} 改配置后立即对已放置的机器生效，无需额外的同步代码。</p>
 */
public final class MekCkUpgradeTracker {

    /** 安装一个批次所需的 tick 数（与 Mekanism 相同）。 */
    public static final int TICKS_REQUIRED = 20;

    private final IntSupplier maxSupplier;
    private int installed;
    private int progress;

    /** 上限固定不变时使用（例：创造模式升级恒为 1）。 */
    public MekCkUpgradeTracker(int max) {
        this(() -> max);
    }

    /** 上限由外部动态提供（例：读 {@code MekckConfig}），每次 {@link #getMax()} 时重新求值。 */
    public MekCkUpgradeTracker(IntSupplier maxSupplier) {
        this.maxSupplier = maxSupplier;
    }

    public int getInstalled() {
        return installed;
    }

    public int getMax() {
        return Math.max(0, maxSupplier.getAsInt());
    }

    /** 安装进度（0~1），供 GUI 进度条使用。 */
    public double getProgress() {
        return Math.min(1.0, (double) progress / TICKS_REQUIRED);
    }

    /**
     * 每 tick 调用：槽位有合法升级物品时推进读条，读满后安装槽位内数量（不超过上限）。
     *
     * @param stack 升级槽物品
     * @param valid 该物品是否属于本槽位接受的升级类型
     * @return 本次是否发生了状态变化（用于 setChanged）
     */
    public boolean tick(ItemStack stack, Predicate<ItemStack> valid) {
        int max = getMax();
        if (stack.isEmpty() || !valid.test(stack) || installed >= max) {
            if (progress != 0) {
                progress = 0;
                return true;
            }
            return false;
        }
        if (progress < TICKS_REQUIRED) {
            progress++;
            return true;
        }
        // 读条完成：一次性安装槽位内数量（受上限约束）
        int canAdd = Math.min(stack.getCount(), max - installed);
        if (canAdd <= 0) {
            progress = 0;
            return true;
        }
        installed += canAdd;
        stack.shrink(canAdd);
        progress = 0;
        return true;
    }

    /** 卸载：把已安装数量交给调用方（如放入卸载输出槽），返回实际卸载数量。 */
    public int uninstall(int amount) {
        int removed = Math.min(installed, Math.max(0, amount));
        installed -= removed;
        return removed;
    }

    /** 立即安装（用于管理员/兼容旧存档的强制同步），返回实际安装数量。 */
    public int installDirect(int amount) {
        int added = Math.min(Math.max(0, amount), getMax() - installed);
        installed += added;
        return added;
    }

    public void resetProgress() {
        progress = 0;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Installed", installed);
        return tag;
    }

    public void load(CompoundTag tag) {
        if (tag != null) {
            installed = Math.min(Math.max(0, tag.getInt("Installed")), getMax());
        }
        progress = 0;
    }
}
